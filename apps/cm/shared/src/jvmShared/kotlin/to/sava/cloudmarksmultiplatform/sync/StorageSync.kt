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
 * S3 上のデータを読み、操作を書き込み、目録を新しく作る。
 *
 * 手順は `docs/storage-format.md` の「書き込みの手順」「キャッシュ」「目録を新しく作る」のとおり。
 *
 * @param updatedBy 書き込む目録の見出しの `updatedBy`。`"android"`・`"desktop"`・`"host"` のどれか
 * @param timeSource 書き込みの手順 3 から手順 4 までの経過を測る、単調な時計。Android は端末の休止中も進む時計
 * （`SystemClock.elapsedRealtimeNanos()` を基にしたもの）を渡す。`System.nanoTime()` は休止中に止まりうる
 * @param clock 目録の見出しの `updatedAt` に使う時計
 * @param wait 送り直しと適用し直しの前に、与えた長さだけ間を空ける
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

    /**
     * 目録を読み、[foldersToLoad] が返すフォルダの項目を読む。
     *
     * 目録は手元のキャッシュを使って条件付きで GET し、フォルダのファイルは手元にあれば GET しない。
     * 目録が無くても作らない。
     *
     * @param foldersToLoad 読んだ目録の木を受け取り、項目を読むフォルダの ID を返す
     */
    suspend fun read(foldersToLoad: (TreeState) -> Set<String> = { emptySet() }): TreeReadResult =
        reader.read(foldersToLoad)

    /**
     * [operations] を、書き込みの手順で 1 回の書き込みとして書く。
     *
     * 目録が読み書きできるものでないとき、目録が無いときは書かない。操作を適用した結果が読んだ状態と同じなら PUT しない。
     * 書けたら、書いた目録と ETag、置いたフォルダのファイルを手元のキャッシュに持ち、
     * 書いた目録が指さないフォルダのファイルを手元から消す。
     *
     * 操作が項目を読む必要のあるフォルダのファイルが壊れていれば、ほかの操作も含めて書かず、
     * [SyncFailure.FolderCorrupt] で返す。呼び出し側は [SyncFailure.FolderCorrupt.problems] のキーで操作を分けて書き直せる。
     *
     * キャンセルすると、書けたかどうかが分からないことがある。そのときは同じ操作の並びでもう一度 `write` する
     * （[WriteResult.Unconfirmed] と同じく、届いていた分は適用済みになり二重にならない）。
     * [WriteResult.Unconfirmed] を受けて同じ操作の並びでやり直した `write` が [WriteResult.Success] 以外で終わったら、
     * 確かめられないままとして扱う。やり直しの [WriteResult.Failed] は、先の目録の PUT が届いていないことを示さない。
     */
    suspend fun write(operations: List<Operation>): WriteResult =
        OperationWriter(store, cache, reader, operations, updatedBy, clock, timeSource, wait).write()

    /**
     * 目録から参照されなくなったフォルダのファイルを消す（古いファイルの削除）。デスクトップのアプリだけが呼ぶ。1 日に 1 回程度。
     *
     * 目録は手元のキャッシュを使わずに GET する。`200` で検証に通り、`version` が知っている版以下のときだけ続け、
     * それ以外（[SyncFailure.ReadOnly]・[SyncFailure.UpdateRequired]・[SyncFailure.ManifestCorrupt]・
     * [SyncFailure.ManifestNotFound]・GET の失敗）は何も消さずに [DeleteStaleFilesResult.Failed] で返す。
     * 一覧を読めなかったときも、何も消さずに [DeleteStaleFilesResult.Failed] で返す。
     * 1 つの削除が失敗しても、残りは続ける。
     */
    suspend fun deleteStaleFiles(): DeleteStaleFilesResult = StaleFileDeleter(store, reader).delete()

    /**
     * 目録を新しく作る。利用者が明示したとき（初期設定、既存の cloud-marks からの移行）だけ呼ぶ。
     *
     * 見出しと根のフォルダだけの目録を、`If-None-Match: *` を付けて PUT する。`409` と結果が分からないときは同じ PUT を
     * 送り直す。`412` なら目録を 1 回読み、`commit` がこの目録のものなら、先の PUT が届いて作れていたとする。
     * そうでなければ、または読めなければ、既に目録があるので作らない。
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

    /** 新しい目録を PUT する。送り直しが上限を超えたら [resendsExhausted] の失敗にする。[resends] は、ここまでに送り直した回数。 */
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
     * `412` を受けたあと、目録を 1 回読む。`commit` が [manifest] のものなら、先の PUT が届いて作れていたとする。
     * そうでなければ、または読めなければ、既に目録があるので作らなかったとする。
     */
    private suspend fun existingManifestResult(manifest: Manifest): CreateManifestResult =
        (reader.readManifest() as? ManifestRead.Usable)
            ?.takeIf { it.manifest.header.commit == manifest.header.commit }
            ?.let { read ->
                CreateManifestResult.Created(LoadedTree(read.manifest, read.eTag, read.manifest.toTreeState(), emptyMap()))
            }
            ?: CreateManifestResult.AlreadyExists
}
