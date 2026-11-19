package to.sava.cloudmarksmultiplatform.sync

import to.sava.cloudmarksmultiplatform.remote.GetObjectResult
import to.sava.cloudmarksmultiplatform.remote.ListVersionsResult
import to.sava.cloudmarksmultiplatform.remote.ListedVersion
import to.sava.cloudmarksmultiplatform.remote.ObjectStore
import to.sava.cloudmarksmultiplatform.storage.ManifestDecodeResult
import to.sava.cloudmarksmultiplatform.storage.decodeManifestGzip
import to.sava.cloudmarksmultiplatform.storage.manifestKey

/**
 * バージョンの一覧の結果のうち、読めなかったものを失敗にする。
 *
 * `403` は [SyncFailure.Forbidden]、結果が不明なものは [SyncFailure.Unreachable]、そのほかの失敗は [SyncFailure.Other]。
 *
 * @throws IllegalArgumentException [result] が [ListVersionsResult.Listed] のとき
 */
internal fun listVersionsFailureOf(result: ListVersionsResult): SyncFailure =
    when (result) {
        ListVersionsResult.Forbidden -> SyncFailure.Forbidden
        is ListVersionsResult.Unknown -> SyncFailure.Unreachable(result.detail)
        is ListVersionsResult.Failed -> SyncFailure.Other(result.detail)
        is ListVersionsResult.Listed -> throw IllegalArgumentException("読めなかったバージョンの一覧の結果でない")
    }

/**
 * マニフェストの古いバージョンを、戻せる時点として一覧し、読む。
 *
 * [TreeReader] とローカルキャッシュは通さない。古いバージョンのマニフェストをローカルキャッシュに持つと、
 * いまのマニフェストのローカルキャッシュと、それが指す項目ファイルのローカルキャッシュが替わるため。
 */
internal class RevisionReader(private val store: ObjectStore) {
    /**
     * マニフェストのキーのバージョンを最後のページまで読み、削除マーカーといまのバージョンを除いて、`LastModified` の新しい順に並べる。
     * `LastModified` が同じものは、S3 の返した順のまま。同じ versionId のものは 1 つにする。
     */
    suspend fun list(): RevisionListResult =
        when (val listed = store.listVersions(manifestKey(store.prefix))) {
            is ListVersionsResult.Listed -> RevisionListResult.Listed(revisionsOf(listed.versions))
            ListVersionsResult.Forbidden, is ListVersionsResult.Unknown, is ListVersionsResult.Failed ->
                RevisionListResult.Failed(listVersionsFailureOf(listed))
        }

    /**
     * マニフェストのバージョン [revision] を GET して検証する。
     *
     * 形式のバージョンが知っているものより新しければ（読むだけにするものを含む）[RevisionFailure.UpdateRequired]、壊れていれば
     * [RevisionFailure.Corrupt]、そのバージョンが無ければ（`404`）[RevisionFailure.Incomplete]、GET のそのほかの失敗は
     * [RevisionFailure.Sync] で返す。
     */
    suspend fun read(revision: Revision): RevisionReadResult =
        when (val response = store.getVersion(manifestKey(store.prefix), revision.versionId)) {
            is GetObjectResult.Found -> revisionReadOf(revision, response.body)
            GetObjectResult.NotFound -> RevisionReadResult.Failed(RevisionFailure.Incomplete)
            GetObjectResult.Forbidden, is GetObjectResult.Unknown, GetObjectResult.NotModified, is GetObjectResult.Failed ->
                RevisionReadResult.Failed(RevisionFailure.Sync(getFailureOf(response)))
        }

    private fun revisionsOf(versions: List<ListedVersion>): List<Revision> =
        versions
            .filter { !it.isDeleteMarker && !it.isLatest }
            .distinctBy { it.versionId }
            .sortedByDescending { it.lastModified }
            .map { Revision(it.versionId, it.lastModified) }

    private fun revisionReadOf(revision: Revision, body: ByteArray): RevisionReadResult =
        when (val decoded = decodeManifestGzip(body)) {
            is ManifestDecodeResult.Readable -> RevisionReadResult.Loaded(LoadedRevision(revision, decoded.manifest))
            is ManifestDecodeResult.ReadOnly, is ManifestDecodeResult.UpdateRequired ->
                RevisionReadResult.Failed(RevisionFailure.UpdateRequired)
            is ManifestDecodeResult.Corrupt -> RevisionReadResult.Failed(RevisionFailure.Corrupt(decoded.problem))
        }
}
