package to.sava.cloudmarksmultiplatform.sync

import to.sava.cloudmarksmultiplatform.operations.TreeState
import to.sava.cloudmarksmultiplatform.operations.toTreeState
import to.sava.cloudmarksmultiplatform.remote.GetObjectResult
import to.sava.cloudmarksmultiplatform.remote.ObjectStore
import to.sava.cloudmarksmultiplatform.storage.FolderEntry
import to.sava.cloudmarksmultiplatform.storage.Item
import to.sava.cloudmarksmultiplatform.storage.ItemsFileDecodeResult
import to.sava.cloudmarksmultiplatform.storage.Manifest
import to.sava.cloudmarksmultiplatform.storage.ManifestDecodeResult
import to.sava.cloudmarksmultiplatform.storage.StorageProblem
import to.sava.cloudmarksmultiplatform.storage.decodeItemsFileGzip
import to.sava.cloudmarksmultiplatform.storage.decodeManifestGzip
import to.sava.cloudmarksmultiplatform.storage.itemsFileKey
import to.sava.cloudmarksmultiplatform.storage.manifestKey

private const val UNEXPECTED_NOT_MODIFIED = "条件を付けていない GET に 304 が返った"

/** マニフェストを読んだ結果。 */
internal sealed interface ManifestRead {
    /** 読めた。[readOnly] は、`version` が知っている版より新しく、読むだけにするとき。 */
    class Usable(val manifest: Manifest, val eTag: String, val readOnly: Boolean) : ManifestRead

    /** 読めなかった。 */
    class Unusable(val failure: SyncFailure) : ManifestRead
}

/** マニフェストと項目ファイルを、ローカルキャッシュを使って読む。 */
internal class TreeReader(
    private val store: ObjectStore,
    private val cache: SyncCache,
) {
    /** マニフェストを読み、[foldersToLoad] が返すフォルダの項目を読む。 */
    suspend fun read(foldersToLoad: (TreeState) -> Set<String>): TreeReadResult =
        when (val manifest = readManifest()) {
            is ManifestRead.Usable -> readTree(manifest, rereadManifest = { readManifest() }, foldersToLoad = foldersToLoad)
            is ManifestRead.Unusable -> TreeReadResult.Failed(manifest.failure)
        }

    /**
     * マニフェストを読む（書き込みの手順 1）。
     *
     * ローカルキャッシュにマニフェストがあれば `If-None-Match` を付けて GET し、`304` ならローカルキャッシュのものを使う。
     * 読んだマニフェストは検証し、版を確かめる。
     * `304` でもローカルキャッシュのマニフェストがマニフェストとして読めない（壊れている）ときは、
     * ローカルキャッシュを丸ごと消し、条件を付けずに GET し直した結果を使う。
     * `200` で返ったマニフェストは、読み書きできるか読むだけにするものなら、ETag と一緒にローカルキャッシュに持ち、
     * そのマニフェストが指さない項目ファイルをローカルキャッシュから消す。
     * `404` は、ローカルキャッシュにマニフェストが無ければマニフェストが無い、あればマニフェストが消されているとする。
     *
     * @param useCache false なら、ローカルキャッシュのマニフェストを読まず、条件を付けずに GET する。
     * ローカルキャッシュのマニフェストも替えない。`404` はマニフェストが無いとする
     */
    suspend fun readManifest(useCache: Boolean = true): ManifestRead =
        getManifest(if (useCache) cache.manifest() else null, useCache)

    /** マニフェストを GET して読む。[cached] があれば、その ETag を `If-None-Match` に付ける。 */
    private suspend fun getManifest(cached: CachedManifest?, useCache: Boolean): ManifestRead =
        when (val response = store.get(manifestKey(store.prefix), cached?.eTag)) {
            is GetObjectResult.Found -> manifestReadOf(response.body, response.eTag).also { read ->
                if (useCache && read is ManifestRead.Usable) {
                    cache.acceptManifest(store.prefix, read.manifest, response.body, response.eTag)
                }
            }
            GetObjectResult.NotModified -> readCachedManifest(cached, useCache)
            GetObjectResult.NotFound ->
                ManifestRead.Unusable(if (cached == null) SyncFailure.ManifestNotFound else SyncFailure.ManifestDeleted)
            GetObjectResult.Forbidden -> ManifestRead.Unusable(SyncFailure.Forbidden)
            is GetObjectResult.Unknown -> ManifestRead.Unusable(SyncFailure.Unreachable(response.detail))
            is GetObjectResult.Failed -> ManifestRead.Unusable(SyncFailure.Other(response.detail))
        }

    /**
     * `304` を受けたときの、ローカルキャッシュのマニフェスト [cached] の読み。壊れていれば、ローカルキャッシュを丸ごと消し、
     * ローカルキャッシュにマニフェストが無いものとして条件を付けずに GET し直す。
     */
    private suspend fun readCachedManifest(cached: CachedManifest?, useCache: Boolean): ManifestRead {
        val read = cached?.let { manifestReadOf(it.body, it.eTag) }
            ?: return ManifestRead.Unusable(SyncFailure.Other(UNEXPECTED_NOT_MODIFIED))
        val corrupt = read is ManifestRead.Unusable && read.failure is SyncFailure.ManifestCorrupt
        if (!corrupt) return read
        cache.clear()
        return getManifest(cached = null, useCache = useCache)
    }

    /**
     * [manifest] のツリーで、[foldersToLoad] が返すフォルダの項目を読む。項目を持たないフォルダと、ツリーに無いフォルダは読まない。
     *
     * いまのマニフェストが指す項目ファイルが `404` を返したら、マニフェストを一度だけ読み直し、
     * 読み直したマニフェストのツリーで読み直す。
     * 読み直したマニフェストがまだ同じファイルを指していれば、そのフォルダは壊れているとみなす。
     * 読み直したマニフェストのツリーで項目ファイルが `404` を返したときも、そのフォルダは壊れているとみなす。
     * 項目ファイルの GET が `200` と `404` 以外を返したら、読めなかったとする。
     *
     * @param rereadManifest マニフェストを読み直す
     */
    suspend fun readTree(
        manifest: ManifestRead.Usable,
        rereadManifest: suspend () -> ManifestRead,
        foldersToLoad: (TreeState) -> Set<String>,
    ): TreeReadResult {
        val first = when (val load = loadFolders(manifest.manifest, foldersToLoad, missingBefore = emptyMap())) {
            is FolderLoad.Failed -> return TreeReadResult.Failed(load.failure)
            is FolderLoad.Loaded -> load
        }
        if (first.missing.isEmpty()) return treeReadResult(manifest, first.state, first.broken)
        return readAfterMissing(first.missing, rereadManifest, foldersToLoad)
    }

    /**
     * 項目ファイルが `404` を返したあとに、マニフェストを [rereadManifest] で一度だけ読み直し、
     * 読み直したマニフェストのツリーで [foldersToLoad] が返すフォルダの項目を読む。
     *
     * 読み直したマニフェストがまだ同じファイルを指しているフォルダは、GET せずに壊れているとみなす。
     * 読み直したマニフェストのツリーで項目ファイルが `404` を返したときも、そのフォルダは壊れているとみなし、
     * マニフェストをもう読み直さない。
     *
     * @param missing `404` を返したフォルダの ID と、そのファイルの ID
     * @param rereadManifest マニフェストを読み直す
     */
    suspend fun readAfterMissing(
        missing: Map<String, String>,
        rereadManifest: suspend () -> ManifestRead,
        foldersToLoad: (TreeState) -> Set<String>,
    ): TreeReadResult {
        val reread = when (val read = rereadManifest()) {
            is ManifestRead.Unusable -> return TreeReadResult.Failed(read.failure)
            is ManifestRead.Usable -> read
        }
        return when (val load = loadFolders(reread.manifest, foldersToLoad, missingBefore = missing)) {
            is FolderLoad.Failed -> TreeReadResult.Failed(load.failure)
            is FolderLoad.Loaded ->
                treeReadResult(reread, load.state, load.broken + load.missing.mapValues { (_, fileId) -> missingFileProblem(fileId) })
        }
    }

    /**
     * [manifest] のツリーで、フォルダの項目を読む。
     *
     * @param missingBefore マニフェストを読み直す前に `404` を返したフォルダの ID と、そのファイルの ID。
     * マニフェストがまだ同じファイルを指しているフォルダは、GET せずに無いとする
     */
    private suspend fun loadFolders(
        manifest: Manifest,
        foldersToLoad: (TreeState) -> Set<String>,
        missingBefore: Map<String, String>,
    ): FolderLoad {
        val base = manifest.toTreeState()
        val entries = manifest.folders.associateBy { it.id }
        val targets = foldersToLoad(base).filter { it in base.folders && it !in base.items }
        val reads = buildMap {
            for (folderId in targets) {
                val entry = entries.getValue(folderId)
                val fileId = checkNotNull(entry.items) { "項目を持つフォルダは、項目ファイルを指す" }
                val read = if (missingBefore[folderId] == fileId) {
                    ItemsFileRead.Missing(fileId)
                } else {
                    readItemsFile(manifest, entry, fileId)
                }
                if (read is ItemsFileRead.Failed) return FolderLoad.Failed(read.failure)
                put(folderId, read)
            }
        }
        return FolderLoad.Loaded(
            state = reads.entries.fold(base) { state, (folderId, read) ->
                if (read is ItemsFileRead.Loaded) state.withLoadedItems(folderId, read.items) else state
            },
            broken = reads.mapNotNull { (folderId, read) -> (read as? ItemsFileRead.Broken)?.let { folderId to it.problem } }.toMap(),
            missing = reads.mapNotNull { (folderId, read) -> (read as? ItemsFileRead.Missing)?.let { folderId to it.fileId } }.toMap(),
        )
    }

    /**
     * [manifest] のツリーで、フォルダ [folderId] の項目を読む。マニフェストは読まない。
     *
     * 項目を持たないフォルダは、読まずに空とする。項目ファイルの読み方と検証は [readTree] と同じ。
     * `404` を返してもマニフェストを読み直さず、[FolderReadResult.Missing] で返す。
     * マニフェストを読み直すのは [readAfterMissing]。
     *
     * @throws IllegalArgumentException [folderId] が [manifest] に無いとき
     */
    suspend fun readFolder(manifest: Manifest, folderId: String): FolderReadResult {
        val entry = requireNotNull(manifest.folders.firstOrNull { it.id == folderId }) { "フォルダ $folderId はマニフェストに無い" }
        val fileId = entry.items ?: return FolderReadResult.Loaded(emptyList())
        return when (val read = readItemsFile(manifest, entry, fileId)) {
            is ItemsFileRead.Loaded -> FolderReadResult.Loaded(read.items)
            is ItemsFileRead.Broken -> FolderReadResult.Broken(read.problem)
            is ItemsFileRead.Missing -> FolderReadResult.Missing
            is ItemsFileRead.Failed -> FolderReadResult.Failed(read.failure)
        }
    }

    /**
     * 項目ファイルを、ローカルキャッシュにあって検証に通ればそれを、そうでなければ GET して読む。
     * ローカルキャッシュの中身が検証に通らなければ、ローカルキャッシュを丸ごと消してから GET する。
     * GET して読めたものはローカルキャッシュに持つ。
     */
    private suspend fun readItemsFile(manifest: Manifest, entry: FolderEntry, fileId: String): ItemsFileRead {
        val key = itemsFileKey(store.prefix, entry.id, fileId)
        val cached = cache.itemsFile(key)?.let { itemsFileReadOf(it, manifest, entry) }
        if (cached is ItemsFileRead.Loaded) return cached
        if (cached != null) cache.clear()
        return when (val response = store.get(key)) {
            is GetObjectResult.Found -> itemsFileReadOf(response.body, manifest, entry).also { read ->
                if (read is ItemsFileRead.Loaded) cache.putItemsFile(key, response.body)
            }
            GetObjectResult.NotFound -> ItemsFileRead.Missing(fileId)
            GetObjectResult.Forbidden -> ItemsFileRead.Failed(SyncFailure.Forbidden)
            is GetObjectResult.Unknown -> ItemsFileRead.Failed(SyncFailure.Unreachable(response.detail))
            GetObjectResult.NotModified -> ItemsFileRead.Failed(SyncFailure.Other(UNEXPECTED_NOT_MODIFIED))
            is GetObjectResult.Failed -> ItemsFileRead.Failed(SyncFailure.Other(response.detail))
        }
    }
}

/** フォルダの項目を読んだ結果。 */
private sealed interface FolderLoad {
    /**
     * @property broken 項目ファイルが壊れていたフォルダの ID と、その問題
     * @property missing 項目ファイルが `404` を返したフォルダの ID と、そのファイルの ID
     */
    class Loaded(
        val state: TreeState,
        val broken: Map<String, StorageProblem>,
        val missing: Map<String, String>,
    ) : FolderLoad

    class Failed(val failure: SyncFailure) : FolderLoad
}

/** 項目ファイルを 1 つ読んだ結果。 */
private sealed interface ItemsFileRead {
    class Loaded(val items: List<Item>) : ItemsFileRead

    /** 壊れている。マニフェストが読むだけにするものなら、版が新しいために読めなかったものを含む。 */
    class Broken(val problem: StorageProblem) : ItemsFileRead

    /** `404` を返した。 */
    class Missing(val fileId: String) : ItemsFileRead

    class Failed(val failure: SyncFailure) : ItemsFileRead
}

private fun manifestReadOf(body: ByteArray, eTag: String): ManifestRead =
    when (val decoded = decodeManifestGzip(body)) {
        is ManifestDecodeResult.Readable -> ManifestRead.Usable(decoded.manifest, eTag, readOnly = false)
        is ManifestDecodeResult.ReadOnly -> ManifestRead.Usable(decoded.manifest, eTag, readOnly = true)
        is ManifestDecodeResult.UpdateRequired ->
            ManifestRead.Unusable(SyncFailure.UpdateRequired(decoded.version, decoded.readableBy))
        is ManifestDecodeResult.Corrupt -> ManifestRead.Unusable(SyncFailure.ManifestCorrupt(decoded.problem))
    }

private fun itemsFileReadOf(body: ByteArray, manifest: Manifest, entry: FolderEntry): ItemsFileRead =
    when (val decoded = decodeItemsFileGzip(body, keyFolderId = entry.id, folder = entry, manifestVersion = manifest.header.version)) {
        is ItemsFileDecodeResult.Readable -> ItemsFileRead.Loaded(decoded.file.items)
        is ItemsFileDecodeResult.UpdateRequired -> ItemsFileRead.Broken(decoded.cause)
        is ItemsFileDecodeResult.Corrupt -> ItemsFileRead.Broken(decoded.problem)
    }

private fun missingFileProblem(fileId: String): StorageProblem =
    StorageProblem(line = null, message = "マニフェストが指す項目ファイルが無い: $fileId")

private fun treeReadResult(
    manifest: ManifestRead.Usable,
    state: TreeState,
    brokenFolders: Map<String, StorageProblem>,
): TreeReadResult =
    LoadedTree(manifest.manifest, manifest.eTag, state, brokenFolders).let { tree ->
        if (manifest.readOnly) TreeReadResult.ReadOnly(tree) else TreeReadResult.Readable(tree)
    }
