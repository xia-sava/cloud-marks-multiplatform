package to.sava.cloudmarksmultiplatform.sync

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import to.sava.cloudmarksmultiplatform.operations.ApplyResult
import to.sava.cloudmarksmultiplatform.operations.Operation
import to.sava.cloudmarksmultiplatform.operations.applyOperations
import to.sava.cloudmarksmultiplatform.operations.requiredFolders
import to.sava.cloudmarksmultiplatform.operations.toFolderEntries
import to.sava.cloudmarksmultiplatform.operations.toTreeState
import to.sava.cloudmarksmultiplatform.remote.ObjectStore
import to.sava.cloudmarksmultiplatform.remote.PutCondition
import to.sava.cloudmarksmultiplatform.remote.PutObjectResult
import to.sava.cloudmarksmultiplatform.storage.Item
import to.sava.cloudmarksmultiplatform.storage.ItemsFile
import to.sava.cloudmarksmultiplatform.storage.ItemsFileHeader
import to.sava.cloudmarksmultiplatform.storage.Manifest
import to.sava.cloudmarksmultiplatform.storage.encodeItemsFileGzip
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
 * 操作の並びを、書き込みの手順で 1 回の書き込みとして書く。書き込みごとに作る。
 *
 * @param updatedBy マニフェストのヘッダーの `updatedBy`
 * @param clock マニフェストのヘッダーの `updatedAt` に使う時計
 * @param timeSource 手順 3 の最初の PUT からの経過を測る、モノトニッククロック
 * @param wait 再送と再適用の前に、与えた長さだけ間を空ける
 */
internal class OperationWriter(
    private val store: ObjectStore,
    private val cache: SyncCache,
    private val reader: TreeReader,
    private val operations: List<Operation>,
    private val updatedBy: String,
    private val clock: Clock,
    private val timeSource: TimeSource,
    private val wait: suspend (Duration) -> Unit,
) {
    /** この書き込みのあいだに手順 4 で送ったマニフェストの `commit` と、その書き込み。成功の判定に使う。 */
    private val sentManifests = mutableMapOf<String, ManifestWrite>()

    /** 結果が不明か `412` を受けたマニフェストの PUT のうち、届いていないとも届いたとも確かめていないもの。無ければ null。 */
    private var unconfirmedPut: UnconfirmedPut? = null

    suspend fun write(): WriteResult = writeFrom(reapplies = 0)

    /** 手順 1 から行う。[reapplies] は、ここまでに再適用した回数。 */
    private suspend fun writeFrom(reapplies: Int): WriteResult {
        if (reapplies > MAX_REAPPLIES) return endWith(unconfirmedPut?.failure ?: SyncFailure.Busy)
        if (reapplies > 0) wait(retryDelay(reapplies))
        return when (val pass = applyAndWrite()) {
            is Pass.Done -> pass.result
            Pass.Reapply -> writeFrom(reapplies + 1)
        }
    }

    /** 手順 1 から手順 5 までを 1 回行う。 */
    private suspend fun applyAndWrite(): Pass {
        // 手順 1
        val manifest = when (val read = readManifest()) {
            is ManifestRead.Unusable -> return ended(read.failure)
            is ManifestRead.Usable -> read
        }
        judge(manifest)?.let { return Pass.Done(it) }
        if (manifest.readOnly) return ended(readOnlyFailure(manifest.manifest))
        val tree = when (val read = readFoldersForWrite(manifest)) {
            is StepResult.Failed -> return ended(read.failure)
            is StepResult.Ok -> read.value
        }

        // 手順 2
        val applied = applyOperations(tree.state, operations)
        if (applied.unchanged) return Pass.Done(WriteResult.Success(tree, applied.results))

        // 手順 3
        val filesStarted = timeSource.markNow()
        val placed = when (val files = putItemsFiles(applied.itemsToWrite)) {
            is StepResult.Failed -> return ended(files.failure)
            is StepResult.Ok -> files.value
        }

        // 手順 4 と手順 5
        return sendManifest(manifestWriteOf(tree, applied, placed), tree.eTag, filesStarted)
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
     * 手順 1 の残り。操作の適用に要るフォルダの項目を読む。
     *
     * 項目ファイルが無く、読み直したマニフェストが読むだけにするものなら、失敗にする。操作の適用に要るフォルダの
     * 壊れたファイルも、失敗にする。
     */
    private suspend fun readFoldersForWrite(manifest: ManifestRead.Usable): StepResult<LoadedTree> {
        val read = reader.readTree(manifest, rereadManifest = { readManifest() }) { state ->
            requiredFolders(state, operations) - state.items.keys
        }
        val tree = when (read) {
            is TreeReadResult.Readable -> read.tree
            is TreeReadResult.ReadOnly -> return StepResult.Failed(readOnlyFailure(read.tree.manifest))
            is TreeReadResult.Failed -> return StepResult.Failed(read.failure)
        }
        val brokenRequired = tree.brokenFolders.filterKeys { it in requiredFolders(tree.state, operations) }
        return if (brokenRequired.isEmpty()) {
            StepResult.Ok(tree)
        } else {
            StepResult.Failed(SyncFailure.FolderCorrupt(brokenRequired))
        }
    }

    /** 手順 3。中身の変わったフォルダごとに、項目ファイルを並べて PUT する。すべて置けたら、置いたファイルを返す。 */
    private suspend fun putItemsFiles(itemsToWrite: Map<String, List<Item>>): StepResult<List<PlacedFile>> =
        coroutineScope {
            itemsToWrite.map { (folderId, items) ->
                async { putItemsFile(folderId, encodeItemsFileGzip(ItemsFile(ItemsFileHeader(folderId), items))) }
            }.awaitAll()
        }.let { results ->
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

    /** 手順 4 で送るマニフェストを作る。ヘッダーの `version` と `readableBy` は読んだマニフェストのまま、`commit` は新しく振る。 */
    private fun manifestWriteOf(tree: LoadedTree, applied: ApplyResult, placed: List<PlacedFile>): ManifestWrite {
        val header = tree.manifest.header.copy(
            commit = newId(),
            updatedAt = clock.now().truncatedToMilliseconds(),
            updatedBy = updatedBy,
        )
        val manifest = Manifest(header, applied.toFolderEntries(placed.associate { it.folderId to it.fileId }))
        return ManifestWrite(manifest, encodeManifestGzip(manifest), applied, placed)
    }

    /**
     * 手順 4 と手順 5。マニフェストを `If-Match` を付けて PUT し、応答に応じて扱う。
     *
     * 手順 3 の最初の PUT から [MAX_FILES_TO_MANIFEST] を過ぎていたら、送らずに手順 1 からやり直す。
     * `409` なら同じ PUT を再送する。`412` と結果が不明のときは、成功の判定をする。
     *
     * @param eTag 手順 1 で読んだマニフェストの ETag
     * @param filesStarted 手順 3 の最初の PUT の時点
     * @param resends ここまでに再送した回数
     */
    private suspend fun sendManifest(write: ManifestWrite, eTag: String, filesStarted: TimeMark, resends: Int = 0): Pass {
        if (resends > 0) wait(retryDelay(resends))
        if (filesStarted.elapsedNow() > MAX_FILES_TO_MANIFEST) return Pass.Reapply

        sentManifests[write.manifest.header.commit] = write
        val result = store.put(manifestKey(store.prefix), write.body, STORAGE_CONTENT_TYPE, PutCondition.IfMatch(eTag))
        return when (result) {
            is PutObjectResult.Success -> Pass.Done(succeed(write, result.eTag))
            PutObjectResult.Conflict ->
                if (resends < MAX_RESENDS) {
                    sendManifest(write, eTag, filesStarted, resends + 1)
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

    /** 成功の判定。マニフェストを読み直して [judge] する。成功していなければ、手順 1 から操作を再適用する。 */
    private suspend fun judgeSuccess(): Pass =
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
        val state = written.copy(items = written.items + write.applied.state.items)
        // 操作の適用に要る項目ファイルが壊れていれば書かないので、書けたときに壊れていると分かっているフォルダは無い
        return WriteResult.Success(LoadedTree(write.manifest, eTag, state, brokenFolders = emptyMap()), write.applied.results)
    }

    /** 失敗で書き込みを終える。届いたかどうかが分からないマニフェストの PUT が残っていれば、[WriteResult.Unconfirmed] にする。 */
    private fun ended(failure: SyncFailure): Pass = Pass.Done(endWith(failure))

    private fun endWith(failure: SyncFailure): WriteResult =
        if (unconfirmedPut == null) WriteResult.Failed(failure) else WriteResult.Unconfirmed(failure)
}

/** 手順 1 から手順 5 までを 1 回行った結果。 */
private sealed interface Pass {
    /** 書き込みを終える。 */
    class Done(val result: WriteResult) : Pass

    /** 手順 1 から操作を再適用する。 */
    data object Reapply : Pass
}

/** 手順の途中の結果。失敗なら、そこで書き込みを終える。 */
private sealed interface StepResult<out T> {
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

/** 手順 4 で送るマニフェストと、そのもとになった適用の結果、置いた項目ファイル。 */
private class ManifestWrite(
    val manifest: Manifest,
    val body: ByteArray,
    val applied: ApplyResult,
    val placedFiles: List<PlacedFile>,
)

/**
 * 届いたかどうかが分からないマニフェストの PUT。
 *
 * @property ifMatch その PUT に付けた `If-Match` の ETag。マニフェストの ETag がこれと違えば、その PUT はもう届かない
 * @property failure 再適用の上限を超えて終わるときの失敗。結果が不明だったなら [SyncFailure.Unreachable]、
 * `412` なら [SyncFailure.Busy]
 */
private class UnconfirmedPut(val ifMatch: String, val failure: SyncFailure)

private fun readOnlyFailure(manifest: Manifest): SyncFailure =
    SyncFailure.ReadOnly(manifest.header.version, manifest.header.readableBy)
