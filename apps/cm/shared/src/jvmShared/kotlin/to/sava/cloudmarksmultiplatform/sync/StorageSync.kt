package to.sava.cloudmarksmultiplatform.sync

import kotlinx.coroutines.delay
import to.sava.cloudmarksmultiplatform.operations.Operation
import to.sava.cloudmarksmultiplatform.operations.TreeState
import to.sava.cloudmarksmultiplatform.operations.toTreeState
import to.sava.cloudmarksmultiplatform.remote.ObjectStore
import to.sava.cloudmarksmultiplatform.remote.PutCondition
import to.sava.cloudmarksmultiplatform.remote.PutObjectResult
import to.sava.cloudmarksmultiplatform.storage.FolderEntry
import to.sava.cloudmarksmultiplatform.storage.Manifest
import to.sava.cloudmarksmultiplatform.storage.ManifestHeader
import to.sava.cloudmarksmultiplatform.storage.SUPPORTED_FORMAT_VERSION
import to.sava.cloudmarksmultiplatform.storage.encodeManifestGzip
import to.sava.cloudmarksmultiplatform.storage.manifestKey
import to.sava.cloudmarksmultiplatform.storage.newId
import to.sava.cloudmarksmultiplatform.storage.truncatedToMilliseconds
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * S3 上のデータを読み、操作を書き込み、マニフェストを新しく作り、マニフェストの古いバージョンの時点の状態へ戻す。
 *
 * 手順は `docs/storage-format.md` の「書き込みの手順」「キャッシュ」「マニフェストを新しく作る」「以前の状態に戻す」のとおり。
 *
 * @param updatedBy 書き込むマニフェストのヘッダーの `updatedBy`。`"android"`・`"desktop"`・`"host"` のどれか
 * @param timeSource 書き込みの手順 3 から手順 4 までの経過を測るモノトニッククロック。Android は端末の休止中も進む時計
 * （`SystemClock.elapsedRealtimeNanos()` を基にしたもの）を渡す。`System.nanoTime()` は休止中に止まりうる
 * @param clock マニフェストのヘッダーの `updatedAt` に使う時計
 * @param wait 再送と再適用の前に、与えた長さだけ間を空ける
 */
class StorageSync(
    private val store: ObjectStore,
    private val cache: SyncCache,
    private val updatedBy: String,
    private val timeSource: TimeSource,
    private val clock: Clock = Clock.System,
    private val wait: suspend (Duration) -> Unit = { delay(it) },
) {
    private val reader = TreeReader(store, cache)
    private val revisionReader = RevisionReader(store)

    /**
     * マニフェストを読み、[foldersToLoad] が返すフォルダの項目を読む。
     *
     * マニフェストはローカルキャッシュを使って条件付きで GET し、項目ファイルはローカルキャッシュにあれば GET しない。
     * マニフェストが無くても作らない。
     *
     * @param foldersToLoad 読んだマニフェストのツリーを受け取り、項目を読むフォルダの ID を返す
     */
    suspend fun read(foldersToLoad: (TreeState) -> Set<String> = { emptySet() }): TreeReadResult =
        reader.read(foldersToLoad)

    /**
     * 読んである [tree] のマニフェストで、フォルダ [folderId] の項目を読む。マニフェストは GET しない。
     *
     * 項目ファイルは、ローカルキャッシュにあって検証に通ればそれを使い、無ければ GET してローカルキャッシュに持つ。項目を持たないフォルダは読まない。
     * 項目ファイルが `404` を返してもマニフェストを読み直さず、[FolderReadResult.Missing] で返す。
     * マニフェストを読み直すときは、続けて [readAfterMissing] を呼ぶ。
     *
     * @throws IllegalArgumentException [folderId] が [tree] のマニフェストに無いとき
     */
    suspend fun readFolder(tree: LoadedTree, folderId: String): FolderReadResult =
        reader.readFolder(tree.manifest, folderId)

    /**
     * [readFolder] がフォルダ [folderId] で [FolderReadResult.Missing] を返したあとに、マニフェストを一度だけ読み直し、
     * [foldersToLoad] が返すフォルダの項目を読む。マニフェストと項目ファイルの読み方は [read] と同じ。
     *
     * 読み直したマニフェストで [folderId] がまだ [tree] と同じファイルを指していれば、GET せずに、そのフォルダは壊れているとする。
     * 読み直したマニフェストのツリーで項目ファイルが `404` を返したときも、そのフォルダは壊れているとし、マニフェストをもう読み直さない。
     *
     * @param tree [readFolder] に渡したツリー
     * @throws IllegalArgumentException [folderId] が [tree] のマニフェストに無いか、項目ファイルを指していないとき
     */
    suspend fun readAfterMissing(
        tree: LoadedTree,
        folderId: String,
        foldersToLoad: (TreeState) -> Set<String>,
    ): TreeReadResult {
        val fileId = requireNotNull(tree.state.folders[folderId]?.itemsFileId) {
            "フォルダ $folderId は、マニフェストで項目ファイルを指していない"
        }
        return reader.readAfterMissing(mapOf(folderId to fileId), rereadManifest = { reader.readManifest() }, foldersToLoad)
    }

    /**
     * [operations] を、書き込みの手順で 1 回の書き込みとして書く。
     *
     * マニフェストが読み書きできるものでないとき、マニフェストが無いときは書かない。操作を適用した結果が読んだ状態と同じなら PUT しない。
     * 書けたら、書いたマニフェストと ETag、置いた項目ファイルをローカルキャッシュに持ち、
     * 書いたマニフェストが指さない項目ファイルをローカルキャッシュから消す。
     *
     * 操作が項目を読む必要のあるフォルダの項目ファイルが壊れていれば、ほかの操作も含めて書かず、
     * [SyncFailure.FolderCorrupt] で返す。呼び出し側は [SyncFailure.FolderCorrupt.problems] のキーで操作を分けて書き直せる。
     *
     * キャンセルすると、書けたかどうかが分からないことがある。そのときは同じ操作の並びでもう一度 `write` する
     * （[WriteResult.Unconfirmed] と同じく、届いていた分は適用済みになり二重にならない）。
     * [WriteResult.Unconfirmed] を受けて同じ操作の並びでやり直した `write` が [WriteResult.Success] 以外で終わったら、
     * 確かめられないままとして扱う。やり直しの [WriteResult.Failed] は、先のマニフェストの PUT が届いていないことを示さない。
     */
    suspend fun write(operations: List<Operation>): WriteResult =
        OperationWriter(store, cache, reader, operations, updatedBy, clock, timeSource, wait).write()

    /**
     * マニフェストから参照されなくなった項目ファイルを消す（古いファイルの削除）。デスクトップのアプリだけが呼ぶ。1 日に 1 回程度。
     *
     * マニフェストはローカルキャッシュを使わずに GET する。`200` で検証に通り、`version` が知っている版以下のときだけ続け、
     * それ以外（[SyncFailure.ReadOnly]・[SyncFailure.UpdateRequired]・[SyncFailure.ManifestCorrupt]・
     * [SyncFailure.ManifestNotFound]・GET の失敗）は何も消さずに [DeleteStaleFilesResult.Failed] で返す。
     * 一覧を読めなかったときも、何も消さずに [DeleteStaleFilesResult.Failed] で返す。
     * 1 つの削除が失敗しても、残りは続ける。
     */
    suspend fun deleteStaleFiles(): DeleteStaleFilesResult = StaleFileDeleter(store, reader).delete()

    /**
     * 戻せる時点（マニフェストの古いバージョン）の一覧を返す。
     *
     * マニフェストのキーのバージョンを ListObjectVersions で最後のページまで読み、削除マーカーといまのバージョンを除いて、
     * `LastModified` の新しい順に並べる。`LastModified` が同じものは S3 の返した順のまま。
     * バージョニングを有効にしていないバケットでは空になる。ローカルキャッシュは使わない。
     */
    suspend fun listRevisions(): RevisionListResult = revisionReader.list()

    /**
     * 戻せる時点 [revision] のマニフェストを、そのバージョンの GET で読んで検証する。ローカルキャッシュは使わず、替えない。
     *
     * 版が知っているものより新しければ（読むだけにするものを含む）[RevisionFailure.UpdateRequired]、壊れていれば
     * [RevisionFailure.Corrupt]、そのバージョンがもう無ければ [RevisionFailure.Incomplete] で返す。
     */
    suspend fun readRevision(revision: Revision): RevisionReadResult = revisionReader.read(revision)

    /**
     * いまのマニフェストを、読んである時点 [revision] の状態へ戻す。書き込みの手順で 1 回の書き込みとして書く。
     *
     * フォルダの並びは [revision] のものにする。いまのマニフェストが同じフォルダに同じ項目ファイルを指しているフォルダは、
     * そのファイルをそのまま指す。そうでないフォルダは、[revision] が指す項目ファイルの中身を得て検証し、新しいファイルの ID で置く。
     * 中身は GET で得て、`404` か `403` なら、そのキーの削除マーカーでないバージョンのうち `LastModified` が最も新しいものから得る。
     * そのバージョンが無ければ、`404` のときは [RevisionFailure.Incomplete]、`403` のときは [SyncFailure.Forbidden] の失敗にする。
     * 中身が壊れていれば [RevisionFailure.Corrupt] にする。中身をすべて得てから PUT するので、得られなければ何も PUT しない。
     * ヘッダーの `version` と `readableBy` は [revision] のもの。いまの状態が [revision] と同じなら PUT しない。
     *
     * 再適用では、読み直したいまのマニフェストと比べ直す。得た中身は覚えておき、取り直さない。
     * いまのマニフェストが読み書きできるものでないとき、マニフェストが無いときは書かない。
     * 削除マーカーは外さず、何も消さない。[RestoreResult.Unconfirmed] でも、[writeConfirming] のように書き直さない。
     */
    suspend fun restoreRevision(revision: LoadedRevision): RestoreResult =
        RevisionRestorer(store, cache, reader, revision, updatedBy, clock, timeSource, wait).restore()

    /**
     * マニフェストを新しく作る。利用者が明示したとき（初期設定、既存の cloud-marks からの移行）だけ呼ぶ。
     *
     * ヘッダーとルートフォルダだけのマニフェストを、`If-None-Match: *` を付けて PUT する。`409` と結果が不明のときは同じ PUT を
     * 再送する。`412` ならマニフェストを 1 回読み、`commit` がこのマニフェストのものなら、先の PUT が届いて作れていたとする。
     * そうでなければ、または読めなければ、既にマニフェストがあるので作らない。
     */
    suspend fun createManifest(): CreateManifestResult {
        val manifest = Manifest(
            header = ManifestHeader(
                version = SUPPORTED_FORMAT_VERSION,
                readableBy = SUPPORTED_FORMAT_VERSION,
                commit = newId(),
                updatedAt = clock.now().truncatedToMilliseconds(),
                updatedBy = updatedBy,
            ),
            folders = listOf(FolderEntry(id = newId(), parent = null, name = "", items = null, count = 0)),
        )
        return sendNewManifest(manifest, encodeManifestGzip(manifest))
    }

    /** 新しいマニフェストを PUT する。再送が上限を超えたら [resendsExhausted] の失敗にする。[resends] は、ここまでに再送した回数。 */
    private suspend fun sendNewManifest(manifest: Manifest, body: ByteArray, resends: Int = 0): CreateManifestResult {
        if (resends > 0) wait(retryDelay(resends))
        val result = store.put(manifestKey(store.prefix), body, STORAGE_CONTENT_TYPE, PutCondition.IfNoneMatchAny)
        return when (result) {
            is PutObjectResult.Success -> {
                cache.acceptManifest(store.prefix, manifest, body, result.eTag)
                CreateManifestResult.Created(LoadedTree(manifest, result.eTag, manifest.toTreeState(), emptyMap()))
            }
            PutObjectResult.Conflict, is PutObjectResult.Unknown ->
                if (resends < MAX_RESENDS) {
                    sendNewManifest(manifest, body, resends + 1)
                } else {
                    CreateManifestResult.Failed(resendsExhausted(result))
                }
            PutObjectResult.PreconditionFailed -> existingManifestResult(manifest)
            PutObjectResult.Forbidden -> CreateManifestResult.Failed(SyncFailure.Forbidden)
            PutObjectResult.NotFound -> CreateManifestResult.Failed(SyncFailure.Other(PUT_NOT_FOUND_DETAIL))
            is PutObjectResult.Failed -> CreateManifestResult.Failed(SyncFailure.Other(result.detail))
        }
    }

    /**
     * `412` を受けたあと、マニフェストを 1 回読む。`commit` が [manifest] のものなら、先の PUT が届いて作れていたとする。
     * そうでなければ、または読めなければ、既にマニフェストがあるので作らなかったとする。
     */
    private suspend fun existingManifestResult(manifest: Manifest): CreateManifestResult =
        (reader.readManifest() as? ManifestRead.Usable)
            ?.takeIf { it.manifest.header.commit == manifest.header.commit }
            ?.let { read ->
                CreateManifestResult.Created(LoadedTree(read.manifest, read.eTag, read.manifest.toTreeState(), emptyMap()))
            }
            ?: CreateManifestResult.AlreadyExists
}

/**
 * [operations] を書く。[WriteResult.Unconfirmed] なら、[StorageSync.write] のとおり同じ操作の並びでもう一度だけ書く。
 * やり直しが [WriteResult.Success] で終わらなければ、確かめられないままとする。
 */
internal suspend fun StorageSync.writeConfirming(operations: List<Operation>): WriteResult =
    when (val first = write(operations)) {
        is WriteResult.Success, is WriteResult.Failed -> first
        is WriteResult.Unconfirmed -> when (val retried = write(operations)) {
            is WriteResult.Success, is WriteResult.Unconfirmed -> retried
            is WriteResult.Failed -> WriteResult.Unconfirmed(retried.failure)
        }
    }
