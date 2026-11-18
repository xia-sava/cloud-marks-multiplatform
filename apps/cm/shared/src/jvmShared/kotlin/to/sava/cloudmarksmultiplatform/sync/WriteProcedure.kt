package to.sava.cloudmarksmultiplatform.sync

import to.sava.cloudmarksmultiplatform.operations.OperationResult
import to.sava.cloudmarksmultiplatform.operations.toTreeState
import to.sava.cloudmarksmultiplatform.remote.ObjectStore
import to.sava.cloudmarksmultiplatform.remote.PutCondition
import to.sava.cloudmarksmultiplatform.remote.PutObjectResult
import to.sava.cloudmarksmultiplatform.storage.FolderEntry
import to.sava.cloudmarksmultiplatform.storage.Item
import to.sava.cloudmarksmultiplatform.storage.Manifest
import to.sava.cloudmarksmultiplatform.storage.ManifestHeader
import to.sava.cloudmarksmultiplatform.storage.encodeManifestGzip
import to.sava.cloudmarksmultiplatform.storage.itemsFileKey
import to.sava.cloudmarksmultiplatform.storage.manifestKey
import to.sava.cloudmarksmultiplatform.storage.newId
import to.sava.cloudmarksmultiplatform.storage.truncatedToMilliseconds
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** PUT が `404` を返したときの失敗の内容。`If-None-Match: *` の PUT では、バケットが無いときに返る。 */
internal const val PUT_NOT_FOUND_DETAIL = "PUT に 404 が返った"

/**
 * 書き込みの手順 1 で読んだマニフェストから、書く中身を決める（手順 2）。
 *
 * @param R 書かずに断るときの理由の型
 */
internal fun interface WritePlanner<out R> {
    /**
     * @param manifest 手順 1 で読んだ、読み書きできるマニフェスト
     * @param rereadManifest マニフェストを読み直す。手順 1 と同じ読み方で、マニフェストの PUT を送ったあとは、
     * S3 につながらないときに GET を再送する
     */
    suspend fun plan(manifest: ManifestRead.Usable, rereadManifest: suspend () -> ManifestRead): WritePlan<R>
}

/** 書き込みの手順 2 で決めた、書く中身。 */
internal sealed interface WritePlan<out R> {
    /**
     * 読んだ状態から変えないので、PUT せずに成功とする。
     *
     * @property tree 成功として返すツリー
     * @property results 成功として返す、操作ごとの結果
     */
    class Unchanged(val tree: LoadedTree, val results: List<OperationResult>) : WritePlan<Nothing>

    /**
     * 項目ファイルを置き（手順 3）、マニフェストを書き換える（手順 4）。
     *
     * @property baseETag 書く中身を決めるもとにしたマニフェストの ETag。マニフェストの PUT の `If-Match` に付ける
     * @property version 書くマニフェストのヘッダーの `version`
     * @property readableBy 書くマニフェストのヘッダーの `readableBy`
     * @property files 項目ファイルを置くフォルダの ID と、置く項目ファイルの中身（gzip のまま）。フォルダごとに新しいファイルの ID で置く
     * @property foldersOf [files] のフォルダの ID から置いたファイルの ID への対応を受け取り、書くマニフェストのフォルダの行を返す
     * @property loadedItems 書いたあとのツリーで、項目を読み込んであるものとするフォルダの項目
     * @property results 書けたときに返す、操作ごとの結果
     */
    class Change(
        val baseETag: String,
        val version: Int,
        val readableBy: Int,
        val files: Map<String, ByteArray>,
        val foldersOf: (newFileIds: Map<String, String>) -> List<FolderEntry>,
        val loadedItems: Map<String, List<Item>>,
        val results: List<OperationResult>,
    ) : WritePlan<Nothing>

    /** 書かずに、[failure] の失敗で書き込みを終える。 */
    class Failed(val failure: SyncFailure) : WritePlan<Nothing>

    /** 書かずに、[reason] で断る。 */
    class Rejected<out R>(val reason: R) : WritePlan<R>
}

/** 書き込みの手順を行った結果。 */
internal sealed interface WriteProcedureResult<out R> {
    /** 書き込みを終えた。書けなかったとき、書けたかどうかを確かめられなかったときを含む。 */
    class Finished(val result: WriteResult) : WriteProcedureResult<Nothing>

    /** [WritePlanner] が [reason] で断り、届いたかどうかが分からないマニフェストの PUT を残さずに終えた。 */
    class Rejected<out R>(val reason: R) : WriteProcedureResult<R>
}

/**
 * 書き込みの手順で、1 回の書き込みを行う。書き込みごとに作る。
 *
 * 手順は `docs/storage-format.md` の「書き込みの手順」「応答ごとの扱い」「成功の判定」のとおり。
 * 手順 2（書く中身を決める）は [planner] が行う。再適用では手順 1 から行い、読み直したマニフェストで [planner] に書く中身を決め直させる。
 *
 * [planner] が断ったとき、届いたかどうかが分からないマニフェストの PUT が残っていれば、[WriteResult.Unconfirmed] で終える。
 *
 * @param updatedBy マニフェストのヘッダーの `updatedBy`
 * @param clock マニフェストのヘッダーの `updatedAt` に使う時計
 * @param timeSource 手順 3 の最初の PUT からの経過を測る、モノトニッククロック
 * @param wait 再送と再適用の前に、与えた長さだけ間を空ける
 * @param planner 手順 2 を行う
 */
internal class WriteProcedure<R>(
    private val store: ObjectStore,
    private val cache: SyncCache,
    private val reader: TreeReader,
    private val updatedBy: String,
    private val clock: Clock,
    private val timeSource: TimeSource,
    private val wait: suspend (Duration) -> Unit,
    private val planner: WritePlanner<R>,
) {
    /** この書き込みのあいだに手順 4 で送ったマニフェストの `commit` と、その書き込み。成功の判定に使う。 */
    private val sentManifests = mutableMapOf<String, ManifestWrite>()

    /** 結果が不明か `412` を受けたマニフェストの PUT のうち、届いていないとも届いたとも確かめていないもの。無ければ null。 */
    private var unconfirmedPut: UnconfirmedPut? = null

    suspend fun run(): WriteProcedureResult<R> = writeFrom(reapplies = 0)

    /** 手順 1 から行う。[reapplies] は、ここまでに再適用した回数。 */
    private suspend fun writeFrom(reapplies: Int): WriteProcedureResult<R> {
        if (reapplies > MAX_REAPPLIES) {
            return WriteProcedureResult.Finished(endWith(unconfirmedPut?.failure ?: SyncFailure.Busy))
        }
        if (reapplies > 0) wait(retryDelay(reapplies))
        return when (val pass = planAndWrite()) {
            is Pass.Done -> WriteProcedureResult.Finished(pass.result)
            is Pass.Rejected -> WriteProcedureResult.Rejected(pass.reason)
            Pass.Reapply -> writeFrom(reapplies + 1)
        }
    }

    /** 手順 1 から手順 5 までを 1 回行う。 */
    private suspend fun planAndWrite(): Pass<R> {
        // 手順 1
        val manifest = when (val read = readManifest()) {
            is ManifestRead.Unusable -> return ended(read.failure)
            is ManifestRead.Usable -> read
        }
        judge(manifest)?.let { return Pass.Done(it) }
        if (manifest.readOnly) return ended(readOnlyFailure(manifest.manifest))

        // 手順 2
        val change = when (val plan = planner.plan(manifest, rereadManifest = { readManifest() })) {
            is WritePlan.Unchanged -> return Pass.Done(WriteResult.Success(plan.tree, plan.results))
            is WritePlan.Failed -> return ended(plan.failure)
            is WritePlan.Rejected -> return rejected(plan.reason)
            is WritePlan.Change -> plan
        }

        // 手順 3
        val filesStarted = timeSource.markNow()
        val placed = when (val files = putItemsFiles(change.files)) {
            is StepResult.Failed -> return ended(files.failure)
            is StepResult.Ok -> files.value
        }

        // 手順 4 と手順 5
        return sendManifest(manifestWriteOf(change, placed), filesStarted)
    }

    /**
     * マニフェストを読む。マニフェストの PUT を 1 回でも送ったあとは、S3 につながらないとき、
     * 間を空けて [MAX_RESENDS] 回まで GET を再送する。
     *
     * @param resends ここまでに再送した回数
     */
    private suspend fun readManifest(resends: Int = 0): ManifestRead {
        val read = reader.readManifest()
        val resendable = sentManifests.isNotEmpty() && resends < MAX_RESENDS &&
            read is ManifestRead.Unusable && read.failure is SyncFailure.Unreachable
        if (!resendable) return read
        wait(retryDelay(resends + 1))
        return readManifest(resends + 1)
    }

    /**
     * 手順 3。フォルダごとに、項目ファイルを並べて PUT する。同時に送るのは [MAX_CONCURRENT_REQUESTS] 個まで。
     * すべて置けたら、置いたファイルを返す。
     */
    private suspend fun putItemsFiles(files: Map<String, ByteArray>): StepResult<List<PlacedFile>> =
        mapConcurrently(files.entries) { (folderId, body) -> putItemsFile(folderId, body) }.let { results ->
            results.firstNotNullOfOrNull { (it as? StepResult.Failed)?.failure }
                ?.let { StepResult.Failed(it) }
                ?: StepResult.Ok(results.mapNotNull { result -> (result as? StepResult.Ok)?.value })
        }

    /**
     * 項目ファイルを 1 つ、`If-None-Match: *` を付けて PUT する。
     *
     * `409` と結果が不明のときは、同じ PUT を再送する。`412` は、再送したあとなら先の PUT が成功していたとみなし、
     * 最初の PUT で返ったならファイルの ID を振り直して置き直す。上限を超えたら [resendsExhausted] の失敗にする。
     *
     * @param resends ここまでに再送した、または置き直した回数
     * @param resent [fileId] へ送るのが再送か
     */
    private suspend fun putItemsFile(
        folderId: String,
        body: ByteArray,
        fileId: String = newId(),
        resends: Int = 0,
        resent: Boolean = false,
    ): StepResult<PlacedFile> {
        if (resends > 0) wait(retryDelay(resends))
        val key = itemsFileKey(store.prefix, folderId, fileId)
        val placed = PlacedFile(folderId, fileId, key, body)
        val canResend = resends < MAX_RESENDS
        return when (val result = store.put(key, body, STORAGE_CONTENT_TYPE, PutCondition.IfNoneMatchAny)) {
            is PutObjectResult.Success -> StepResult.Ok(placed)
            PutObjectResult.Conflict, is PutObjectResult.Unknown ->
                if (canResend) {
                    putItemsFile(folderId, body, fileId, resends + 1, resent = true)
                } else {
                    StepResult.Failed(resendsExhausted(result))
                }
            PutObjectResult.PreconditionFailed -> when {
                resent -> StepResult.Ok(placed)
                canResend -> putItemsFile(folderId, body, newId(), resends + 1, resent = false)
                else -> StepResult.Failed(resendsExhausted(result))
            }
            PutObjectResult.Forbidden -> StepResult.Failed(SyncFailure.Forbidden)
            PutObjectResult.NotFound -> StepResult.Failed(SyncFailure.Other(PUT_NOT_FOUND_DETAIL))
            is PutObjectResult.Failed -> StepResult.Failed(SyncFailure.Other(result.detail))
        }
    }

    /** 手順 4 で送るマニフェストを作る。ヘッダーの `version` と `readableBy` は [change] のもの、`commit` は新しく振る。 */
    private fun manifestWriteOf(change: WritePlan.Change, placed: List<PlacedFile>): ManifestWrite {
        val header = ManifestHeader(
            version = change.version,
            readableBy = change.readableBy,
            commit = newId(),
            updatedAt = clock.now().truncatedToMilliseconds(),
            updatedBy = updatedBy,
        )
        val manifest = Manifest(header, change.foldersOf(placed.associate { it.folderId to it.fileId }))
        return ManifestWrite(manifest, encodeManifestGzip(manifest), change, placed)
    }

    /**
     * 手順 4 と手順 5。マニフェストを、`If-Match` に [WritePlan.Change.baseETag] を付けて PUT し、応答に応じて扱う。
     *
     * 手順 3 の最初の PUT から [MAX_FILES_TO_MANIFEST] を過ぎていたら、送らずに手順 1 からやり直す。
     * `409` なら同じ PUT を再送する。`412` と結果が不明のときは、成功の判定をする。
     *
     * @param filesStarted 手順 3 の最初の PUT の時点
     * @param resends ここまでに再送した回数
     */
    private suspend fun sendManifest(write: ManifestWrite, filesStarted: TimeMark, resends: Int = 0): Pass<Nothing> {
        if (resends > 0) wait(retryDelay(resends))
        if (filesStarted.elapsedNow() > MAX_FILES_TO_MANIFEST) return Pass.Reapply

        val eTag = write.change.baseETag
        sentManifests[write.manifest.header.commit] = write
        val result = store.put(manifestKey(store.prefix), write.body, STORAGE_CONTENT_TYPE, PutCondition.IfMatch(eTag))
        return when (result) {
            is PutObjectResult.Success -> Pass.Done(succeed(write, result.eTag))
            PutObjectResult.Conflict ->
                if (resends < MAX_RESENDS) {
                    sendManifest(write, filesStarted, resends + 1)
                } else {
                    ended(resendsExhausted(result))
                }
            PutObjectResult.PreconditionFailed -> {
                unconfirmedPut = UnconfirmedPut(ifMatch = eTag, failure = SyncFailure.Busy)
                judgeSuccess()
            }
            is PutObjectResult.Unknown -> {
                unconfirmedPut = UnconfirmedPut(ifMatch = eTag, failure = SyncFailure.Unreachable(result.detail))
                judgeSuccess()
            }
            PutObjectResult.NotFound -> ended(SyncFailure.ManifestDeleted)
            PutObjectResult.Forbidden -> ended(SyncFailure.Forbidden)
            is PutObjectResult.Failed -> ended(SyncFailure.Other(result.detail))
        }
    }

    /** 成功の判定。マニフェストを読み直して [judge] する。成功していなければ、手順 1 から再適用する。 */
    private suspend fun judgeSuccess(): Pass<Nothing> =
        when (val read = readManifest()) {
            is ManifestRead.Unusable -> ended(read.failure)
            is ManifestRead.Usable -> judge(read)?.let { Pass.Done(it) } ?: Pass.Reapply
        }

    /**
     * 読んだマニフェストで、この書き込みのマニフェストの PUT が届いていたかを確かめる。
     *
     * `commit` がこの書き込みで送ったどれかなら、その書き込みが成功していたとして返す。そうでなく、
     * 届いたかどうかが分からないマニフェストの PUT があり、読んだマニフェストの ETag がその `If-Match` と違えば、
     * その PUT はもう届かないとして記録を消す（届いていたなら、その変更を含むマニフェストがほかの書き手に書き換えられている）。
     */
    private suspend fun judge(read: ManifestRead.Usable): WriteResult.Success? {
        sentManifests[read.manifest.header.commit]?.let { return succeed(it, read.eTag) }
        if (unconfirmedPut?.ifMatch != read.eTag) unconfirmedPut = null
        return null
    }

    /**
     * 書いたマニフェストと ETag、置いた項目ファイルをローカルキャッシュに持ち、書いたあとのツリーを返す。
     * 書いたマニフェストが指さない項目ファイルは、ローカルキャッシュから消す。
     */
    private suspend fun succeed(write: ManifestWrite, eTag: String): WriteResult.Success {
        write.placedFiles.forEach { cache.putItemsFile(it.key, it.body) }
        cache.acceptManifest(store.prefix, write.manifest, write.body, eTag)
        val written = write.manifest.toTreeState()
        val state = written.copy(items = written.items + write.change.loadedItems)
        // 項目ファイルが壊れていると分かっているフォルダは、書いたあとのツリーに持ち越さない
        return WriteResult.Success(LoadedTree(write.manifest, eTag, state, brokenFolders = emptyMap()), write.change.results)
    }

    /** [planner] が断ったときの終え方。届いたかどうかが分からないマニフェストの PUT が残っていれば、[WriteResult.Unconfirmed] にする。 */
    private fun rejected(reason: R): Pass<R> =
        unconfirmedPut?.let { Pass.Done(WriteResult.Unconfirmed(it.failure)) } ?: Pass.Rejected(reason)

    /** 失敗で書き込みを終える。届いたかどうかが分からないマニフェストの PUT が残っていれば、[WriteResult.Unconfirmed] にする。 */
    private fun ended(failure: SyncFailure): Pass<Nothing> = Pass.Done(endWith(failure))

    private fun endWith(failure: SyncFailure): WriteResult =
        if (unconfirmedPut == null) WriteResult.Failed(failure) else WriteResult.Unconfirmed(failure)
}

/** 手順 1 から手順 5 までを 1 回行った結果。 */
private sealed interface Pass<out R> {
    /** 書き込みを終える。 */
    class Done(val result: WriteResult) : Pass<Nothing>

    /** [WritePlanner] が [reason] で断ったので、書かずに終える。 */
    class Rejected<out R>(val reason: R) : Pass<R>

    /** 手順 1 から再適用する。 */
    data object Reapply : Pass<Nothing>
}

/** 手順の途中の結果。失敗なら、そこで書き込みを終える。 */
internal sealed interface StepResult<out T> {
    class Ok<out T>(val value: T) : StepResult<T>

    class Failed(val failure: SyncFailure) : StepResult<Nothing>
}

/** 置いた項目ファイル。 */
private class PlacedFile(
    val folderId: String,
    val fileId: String,
    val key: String,
    val body: ByteArray,
)

/** 手順 4 で送るマニフェストと、そのもとになった書く中身、置いた項目ファイル。 */
private class ManifestWrite(
    val manifest: Manifest,
    val body: ByteArray,
    val change: WritePlan.Change,
    val placedFiles: List<PlacedFile>,
)

/**
 * 届いたかどうかが分からないマニフェストの PUT。
 *
 * @property ifMatch その PUT に付けた `If-Match` の ETag。マニフェストの ETag がこれと違えば、その PUT はもう届かない
 * @property failure 再適用の上限を超えて終わるときと、[WritePlanner] が断って終わるときの失敗（[WriteResult.Unconfirmed] の中身）。
 * 結果が不明だったなら [SyncFailure.Unreachable]、`412` なら [SyncFailure.Busy]
 */
private class UnconfirmedPut(val ifMatch: String, val failure: SyncFailure)

/** 読むだけにするマニフェスト [manifest] に書こうとしたときの失敗。 */
internal fun readOnlyFailure(manifest: Manifest): SyncFailure =
    SyncFailure.ReadOnly(manifest.header.version, manifest.header.readableBy)
