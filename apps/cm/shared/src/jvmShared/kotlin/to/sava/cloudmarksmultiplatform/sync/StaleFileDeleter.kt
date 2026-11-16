package to.sava.cloudmarksmultiplatform.sync

import to.sava.cloudmarksmultiplatform.remote.DeleteObjectResult
import to.sava.cloudmarksmultiplatform.remote.ListObjectsResult
import to.sava.cloudmarksmultiplatform.remote.ListedObject
import to.sava.cloudmarksmultiplatform.remote.ObjectStore
import to.sava.cloudmarksmultiplatform.storage.Manifest
import to.sava.cloudmarksmultiplatform.storage.foldersKeyPrefix
import to.sava.cloudmarksmultiplatform.storage.isUuidShape
import to.sava.cloudmarksmultiplatform.storage.itemsFileKey
import to.sava.cloudmarksmultiplatform.storage.referencedItemsFileKeys
import kotlin.time.Instant

private const val FORBIDDEN_DETAIL = "HTTP 403"

/**
 * 目録から参照されなくなったフォルダのファイルを消す。手順は `docs/storage-format.md` の「古いファイルの削除」のとおり。
 *
 * 目録は手元のキャッシュを使わずに GET し、読み書きできるもののときだけ続ける。
 * `<プレフィックス>/folders/` の一覧を最後まで読み、`folders/<UUID>/<UUID>.jsonl.gz` の形に厳密に合い、
 * 目録のどのフォルダの `<id>/<items>` の組とも一致せず、一覧の応答の `Date` と `LastModified` の差が猶予
 * （[STALE_FILE_GRACE_PERIOD]）を過ぎたキーを、1 つずつ消す。
 */
internal class StaleFileDeleter(
    private val store: ObjectStore,
    private val reader: TreeReader,
) {
    suspend fun delete(): DeleteStaleFilesResult {
        val manifest = when (val read = reader.readManifest(useCache = false)) {
            is ManifestRead.Unusable -> return DeleteStaleFilesResult.Failed(read.failure)
            is ManifestRead.Usable ->
                if (read.readOnly) {
                    return DeleteStaleFilesResult.Failed(SyncFailure.ReadOnly(read.manifest.header.version, read.manifest.header.readableBy))
                } else {
                    read.manifest
                }
        }
        return when (val listed = store.list(foldersKeyPrefix(store.prefix))) {
            is ListObjectsResult.Listed -> deleteStale(manifest, listed.objects, listed.serverTime)
            ListObjectsResult.Forbidden -> DeleteStaleFilesResult.Failed(SyncFailure.Forbidden)
            is ListObjectsResult.Unknown -> DeleteStaleFilesResult.Failed(SyncFailure.Unreachable(listed.detail))
            is ListObjectsResult.Failed -> DeleteStaleFilesResult.Failed(SyncFailure.Other(listed.detail))
        }
    }

    private suspend fun deleteStale(manifest: Manifest, objects: List<ListedObject>, serverTime: Instant): DeleteStaleFilesResult.Success {
        val referenced = referencedItemsFileKeys(store.prefix, manifest)
        val (shaped, untouched) = objects.partition { isItemsFileKey(it.key) }
        val unreferenced = shaped.filter { it.key !in referenced }
        val (stale, withinGrace) = unreferenced.partition { serverTime - it.lastModified > STALE_FILE_GRACE_PERIOD }
        val failures = stale.mapNotNull { failureOf(store.delete(it.key)) }
        return DeleteStaleFilesResult.Success(
            deleted = stale.size - failures.size,
            failed = failures.size,
            lastFailure = failures.lastOrNull(),
            untouched = untouched.size,
            withinGrace = withinGrace.size,
        )
    }

    /** `<プレフィックス>/folders/<UUID>/<UUID>.jsonl.gz` の形に厳密に合うキーか。 */
    private fun isItemsFileKey(key: String): Boolean =
        key.removePrefix(foldersKeyPrefix(store.prefix)).split('/').let { parts ->
            parts.size == 2 &&
                isUuidShape(parts[0]) &&
                parts[1].substringBefore('.').let { fileId ->
                    isUuidShape(fileId) && itemsFileKey(store.prefix, parts[0], fileId) == key
                }
        }

    /** 削除が失敗なら、短い説明。成功なら null。 */
    private fun failureOf(result: DeleteObjectResult): String? =
        when (result) {
            DeleteObjectResult.Success -> null
            DeleteObjectResult.Forbidden -> FORBIDDEN_DETAIL
            is DeleteObjectResult.Unknown -> result.detail
            is DeleteObjectResult.Failed -> result.detail
        }
}
