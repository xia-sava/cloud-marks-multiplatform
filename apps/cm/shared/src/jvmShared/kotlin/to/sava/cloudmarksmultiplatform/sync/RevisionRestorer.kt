package to.sava.cloudmarksmultiplatform.sync

import to.sava.cloudmarksmultiplatform.operations.toTreeState
import to.sava.cloudmarksmultiplatform.remote.GetObjectResult
import to.sava.cloudmarksmultiplatform.remote.ListVersionsResult
import to.sava.cloudmarksmultiplatform.remote.ObjectStore
import to.sava.cloudmarksmultiplatform.storage.FolderEntry
import to.sava.cloudmarksmultiplatform.storage.Item
import to.sava.cloudmarksmultiplatform.storage.ItemsFileDecodeResult
import to.sava.cloudmarksmultiplatform.storage.Manifest
import to.sava.cloudmarksmultiplatform.storage.decodeItemsFileGzip
import to.sava.cloudmarksmultiplatform.storage.itemsFileKey
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * いまのマニフェストを、戻せる時点 [revision] の状態へ戻す。書き込みの手順で 1 回の書き込みとして書く。戻すごとに作る。
 *
 * 手順 2 では、手順 1 で読んだいまのマニフェストと [revision] のマニフェストを比べて書く中身を決める。それ以外の手順は
 * [WriteProcedure] が行う。
 *
 * - フォルダの並びは [revision] のものにする
 * - いまのマニフェストが同じフォルダに同じ項目ファイルを指しているフォルダは、そのファイルをそのまま指す。GET も PUT もしない
 * - そうでないフォルダは、[revision] が指す項目ファイルの中身を得て検証し、新しいファイルの ID で置く。`items` が null のフォルダは null のまま
 * - ヘッダーの `version` と `readableBy` は [revision] のもの
 *
 * 中身をすべて得てから PUT を始めるので、得られないときは何も PUT しない。得た中身は再適用をまたいで覚えておき、取り直さない。
 * 削除マーカーは外さず、何も消さない。
 *
 * @param updatedBy マニフェストのヘッダーの `updatedBy`
 * @param clock マニフェストのヘッダーの `updatedAt` に使う時計
 * @param timeSource 手順 3 の最初の PUT からの経過を測る、モノトニッククロック
 * @param wait 再送と再適用の前に、与えた長さだけ間を空ける
 */
internal class RevisionRestorer(
    private val store: ObjectStore,
    private val cache: SyncCache,
    private val reader: TreeReader,
    revision: LoadedRevision,
    private val updatedBy: String,
    private val clock: Clock,
    private val timeSource: TimeSource,
    private val wait: suspend (Duration) -> Unit,
) {
    private val target: Manifest = revision.manifest

    /** 得た項目ファイルの中身。項目ファイルのキーごとに、再適用をまたいで覚える。 */
    private val restoredFiles = mutableMapOf<String, RestoredFile>()

    suspend fun restore(): RestoreResult {
        val planner = WritePlanner<RevisionFailure> { manifest, _ -> plan(manifest) }
        return when (val result = WriteProcedure(store, cache, reader, updatedBy, clock, timeSource, wait, planner).run()) {
            is WriteProcedureResult.Finished -> restoreResultOf(result.result)
            is WriteProcedureResult.Rejected -> RestoreResult.Failed(result.reason)
        }
    }

    /** 手順 2。いまの状態が戻す時点と同じなら、書かない。 */
    private suspend fun plan(manifest: ManifestRead.Usable): WritePlan<RevisionFailure> {
        val current = manifest.manifest
        if (isSameState(current)) {
            return WritePlan.Unchanged(LoadedTree(current, manifest.eTag, current.toTreeState(), brokenFolders = emptyMap()), emptyList())
        }
        val files = when (val restored = restoreFiles(foldersToPlace(current))) {
            is RevisionStep.Failed -> return planOf(restored.failure)
            is RevisionStep.Ok -> restored.value
        }
        return WritePlan.Change(
            baseETag = manifest.eTag,
            version = target.header.version,
            readableBy = target.header.readableBy,
            files = files.mapValues { (_, file) -> file.body },
            foldersOf = { newFileIds ->
                target.folders.map { entry -> newFileIds[entry.id]?.let { fileId -> entry.copy(items = fileId) } ?: entry }
            },
            loadedItems = files.mapValues { (_, file) -> file.items },
            results = emptyList(),
        )
    }

    /** フォルダの並びと、ヘッダーの `version` と `readableBy` が、戻す時点と同じか。 */
    private fun isSameState(current: Manifest): Boolean =
        current.folders == target.folders &&
            current.header.version == target.header.version &&
            current.header.readableBy == target.header.readableBy

    /** 戻す時点のフォルダのうち、項目ファイルを置き直すもの。項目ファイルを指し、いまのマニフェストの同じフォルダが別のファイルを指すか、そのフォルダが無い。 */
    private fun foldersToPlace(current: Manifest): List<FolderEntry> {
        val currentFileIds = current.folders.associate { it.id to it.items }
        return target.folders.filter { it.items != null && it.items != currentFileIds[it.id] }
    }

    /**
     * [entries] の項目ファイルの中身を、並べて得る。同時に送るのは [MAX_CONCURRENT_REQUESTS] 個まで。
     * すべて得られたら、フォルダの ID ごとの中身を返す。得られないものがあれば、並びの最初のものの失敗を返す。得た中身は覚えておく。
     */
    private suspend fun restoreFiles(entries: List<FolderEntry>): RevisionStep<Map<String, RestoredFile>> =
        mapConcurrently(entries) { entry -> entry to restoreFile(entry) }.let { results ->
            val restored = results.mapNotNull { (entry, result) -> (result as? RevisionStep.Ok)?.let { entry to it.value } }
            restored.forEach { (entry, file) -> restoredFiles[keyOf(entry)] = file }
            results.firstNotNullOfOrNull { (_, result) -> (result as? RevisionStep.Failed)?.failure }
                ?.let { RevisionStep.Failed(it) }
                ?: RevisionStep.Ok(restored.associate { (entry, file) -> entry.id to file })
        }

    /** フォルダ [entry] が戻す時点で指す項目ファイルの中身を得て、検証する。覚えてあれば、それを使う。 */
    private suspend fun restoreFile(entry: FolderEntry): RevisionStep<RestoredFile> {
        restoredFiles[keyOf(entry)]?.let { return RevisionStep.Ok(it) }
        return when (val fetched = fetchBody(keyOf(entry))) {
            is RevisionStep.Failed -> fetched
            is RevisionStep.Ok -> restoredFileOf(entry, fetched.value)
        }
    }

    private fun keyOf(entry: FolderEntry): String =
        itemsFileKey(store.prefix, entry.id, checkNotNull(entry.items) { "置き直すフォルダは、項目ファイルを指す" })

    /**
     * 項目ファイル [key] の中身を得る。GET が `404` か `403` なら、そのキーの古いバージョンから得る。
     *
     * 手順 4 より前の GET なので、結果が不明でも再送しない。
     */
    private suspend fun fetchBody(key: String): RevisionStep<ByteArray> =
        when (val response = store.get(key)) {
            is GetObjectResult.Found -> RevisionStep.Ok(response.body)
            GetObjectResult.NotFound -> fetchOlderVersion(key, whenMissing = RevisionFailure.Incomplete)
            GetObjectResult.Forbidden -> fetchOlderVersion(key, whenMissing = RevisionFailure.Sync(SyncFailure.Forbidden))
            is GetObjectResult.Unknown, GetObjectResult.NotModified, is GetObjectResult.Failed ->
                RevisionStep.Failed(RevisionFailure.Sync(getFailureOf(response)))
        }

    /**
     * 項目ファイル [key] のバージョンのうち、削除マーカーでなく `LastModified` が最も新しいものの中身を得る。
     *
     * @param whenMissing そのバージョンが無いときの失敗
     */
    private suspend fun fetchOlderVersion(key: String, whenMissing: RevisionFailure): RevisionStep<ByteArray> {
        val versionId = when (val listed = store.listVersions(key)) {
            is ListVersionsResult.Listed ->
                listed.versions.filterNot { it.isDeleteMarker }.maxByOrNull { it.lastModified }?.versionId
                    ?: return RevisionStep.Failed(whenMissing)
            ListVersionsResult.Forbidden, is ListVersionsResult.Unknown, is ListVersionsResult.Failed ->
                return RevisionStep.Failed(RevisionFailure.Sync(listVersionsFailureOf(listed)))
        }
        return when (val response = store.getVersion(key, versionId)) {
            is GetObjectResult.Found -> RevisionStep.Ok(response.body)
            GetObjectResult.NotFound -> RevisionStep.Failed(whenMissing)
            GetObjectResult.Forbidden, is GetObjectResult.Unknown, GetObjectResult.NotModified, is GetObjectResult.Failed ->
                RevisionStep.Failed(RevisionFailure.Sync(getFailureOf(response)))
        }
    }

    /** [body] を、戻す時点のフォルダ [entry] の項目ファイルとして検証する。 */
    private fun restoredFileOf(entry: FolderEntry, body: ByteArray): RevisionStep<RestoredFile> =
        when (val decoded = decodeItemsFileGzip(body, keyFolderId = entry.id, folder = entry, manifestVersion = target.header.version)) {
            is ItemsFileDecodeResult.Readable -> RevisionStep.Ok(RestoredFile(body, decoded.file.items))
            is ItemsFileDecodeResult.UpdateRequired -> RevisionStep.Failed(RevisionFailure.Corrupt(decoded.cause))
            is ItemsFileDecodeResult.Corrupt -> RevisionStep.Failed(RevisionFailure.Corrupt(decoded.problem))
        }
}

/** S3 への読み書きの失敗は書き込みの失敗として終え、そのほかは断る。 */
private fun planOf(failure: RevisionFailure): WritePlan<RevisionFailure> =
    if (failure is RevisionFailure.Sync) WritePlan.Failed(failure.failure) else WritePlan.Rejected(failure)

private fun restoreResultOf(result: WriteResult): RestoreResult =
    when (result) {
        is WriteResult.Success -> RestoreResult.Success(result.tree)
        is WriteResult.Failed -> RestoreResult.Failed(RevisionFailure.Sync(result.failure))
        is WriteResult.Unconfirmed -> RestoreResult.Unconfirmed(result.failure)
    }

/** 戻す時点の項目ファイルから得た中身（gzip のまま）と、検証で読んだ項目。 */
private class RestoredFile(val body: ByteArray, val items: List<Item>)

/** 中身を得る途中の結果。失敗なら、そこで戻すのを終える。 */
private sealed interface RevisionStep<out T> {
    class Ok<out T>(val value: T) : RevisionStep<T>

    class Failed(val failure: RevisionFailure) : RevisionStep<Nothing>
}
