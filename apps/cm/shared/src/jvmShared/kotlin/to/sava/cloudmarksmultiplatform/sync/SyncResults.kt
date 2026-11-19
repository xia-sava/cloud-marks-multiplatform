package to.sava.cloudmarksmultiplatform.sync

import to.sava.cloudmarksmultiplatform.operations.OperationResult
import to.sava.cloudmarksmultiplatform.operations.TreeState
import to.sava.cloudmarksmultiplatform.storage.Item
import to.sava.cloudmarksmultiplatform.storage.Manifest
import to.sava.cloudmarksmultiplatform.storage.StorageProblem

/**
 * 読み書きの失敗の種類。
 *
 * `docs/native-messaging.md` の `code` へは、[Unreachable] と [Forbidden] が `storageUnavailable`、
 * [ReadOnly] と [UpdateRequired] が `updateRequired`、[ManifestCorrupt]・[ManifestNotFound]・[ManifestDeleted]・
 * [FolderCorrupt] が `dataBroken`、[Busy] が `busy`、[Other] が `internal` に当たる。
 * [WriteResult.Unconfirmed] は、中の失敗の種類によらず `unconfirmed` に当たる。
 *
 * `detail` は後から追うための短い文で、秘密情報を含まない。
 */
sealed interface SyncFailure {
    /** S3 につながらない。通信の失敗、タイムアウト、`5xx` など、結果が不明のまま終わった。 */
    data class Unreachable(val detail: String) : SyncFailure

    /** 権限が無い（`403`）。 */
    data object Forbidden : SyncFailure

    /** マニフェストの `version` が知っているバージョンより新しいので、読むだけにして書かない。アプリの更新が要る。 */
    data class ReadOnly(val version: Int, val readableBy: Int) : SyncFailure

    /** マニフェストの `readableBy` が知っているバージョンより新しいか、`version` が新しく検証に失敗したので、読めない。アプリの更新が要る。 */
    data class UpdateRequired(val version: Int, val readableBy: Int) : SyncFailure

    /** マニフェストが壊れている。 */
    data class ManifestCorrupt(val problem: StorageProblem) : SyncFailure

    /**
     * マニフェストが無い（`404`）。読み書きの手順では、ローカルキャッシュにマニフェストも無いとき。
     * 古いファイルの削除はローカルキャッシュを使わないので、キャッシュの有無によらずマニフェストの `404` はこれになる。
     * マニフェストは、利用者が明示したときだけ新しく作る。
     */
    data object ManifestNotFound : SyncFailure

    /**
     * マニフェストが消されている。ローカルキャッシュにマニフェストがあるのにマニフェストの GET が `404` を返した、
     * またはマニフェストの PUT が `404` を返した。
     */
    data object ManifestDeleted : SyncFailure

    /** 操作が項目を読む必要のあるフォルダの、項目ファイルが壊れている。[problems] はフォルダの ID ごとの問題。 */
    data class FolderCorrupt(val problems: Map<String, StorageProblem>) : SyncFailure

    /** 再送または再適用が、回数の上限を超えた。 */
    data object Busy : SyncFailure

    /** そのほかの失敗。 */
    data class Other(val detail: String) : SyncFailure
}

/**
 * 読んだマニフェストと、項目を読み込んだフォルダ。
 *
 * @property eTag [manifest] の ETag。次の書き込みの `If-Match` に使う
 * @property state [manifest] のツリー。項目を読み込んだフォルダと、項目を持たないフォルダの項目を含む
 * @property brokenFolders 項目ファイルが壊れていたフォルダの ID と、その問題。GET が `404` を返したファイルも、
 * マニフェストを読み直してなおそのファイルを指していれば含む。マニフェストが [TreeReadResult.ReadOnly] のときは、
 * 形式のバージョンが新しいために読めなかったものも含む
 */
data class LoadedTree(
    val manifest: Manifest,
    val eTag: String,
    val state: TreeState,
    val brokenFolders: Map<String, StorageProblem>,
)

/** マニフェストとフォルダを読んだ結果。 */
sealed interface TreeReadResult {
    /** 読めた。読み書きできる。 */
    data class Readable(val tree: LoadedTree) : TreeReadResult

    /** 読めたが、マニフェストの `version` が知っているバージョンより新しいので、読むだけにする。アプリの更新が要る。 */
    data class ReadOnly(val tree: LoadedTree) : TreeReadResult

    /**
     * 読めなかった。
     *
     * 形式のバージョンの扱いは [SyncFailure.UpdateRequired]、検証の失敗は [SyncFailure.ManifestCorrupt]、マニフェストの `404` は
     * [SyncFailure.ManifestNotFound]（ローカルキャッシュにマニフェストが無いとき）か [SyncFailure.ManifestDeleted]（あるとき）になる。
     */
    data class Failed(val failure: SyncFailure) : TreeReadResult
}

/** 読んであるマニフェストのツリーで、1 つのフォルダの項目を読んだ結果。 */
sealed interface FolderReadResult {
    /** 読めた。[items] は、表示の並び順の項目。 */
    data class Loaded(val items: List<Item>) : FolderReadResult

    /** 項目ファイルが壊れている。マニフェストが読むだけにするものなら、形式のバージョンが新しいために読めなかったものを含む。 */
    data class Broken(val problem: StorageProblem) : FolderReadResult

    /**
     * マニフェストが指す項目ファイルが無い（`404`）。読んであるマニフェストが古く、指していたファイルが古いファイルの削除で
     * 消えていることがある。マニフェストから読み直すと（[StorageSync.readAfterMissing]）、新しいファイルを指していることがある。
     */
    data object Missing : FolderReadResult

    /** 読めなかった。 */
    data class Failed(val failure: SyncFailure) : FolderReadResult
}

/** 操作を書き込んだ結果。 */
sealed interface WriteResult {
    /**
     * 書けた。操作を適用した結果が読んだ状態と同じで、PUT しなかったときも含む。
     *
     * @property tree 書いたあとのマニフェストとツリー。PUT しなかったときは読んだもの
     * @property results 操作ごとの結果。入力の順。適用しなかった操作を含む
     */
    data class Success(val tree: LoadedTree, val results: List<OperationResult>) : WriteResult

    /** 書けなかった。届いたかどうかが分からないマニフェストの PUT を残さずに終わった。 */
    data class Failed(val failure: SyncFailure) : WriteResult

    /**
     * 書けたかどうかを確かめられなかった。結果が不明か `412` を受けたマニフェストの PUT があり、届いたとも届いていないとも
     * 成功の判定で確かめられないまま終わった。書き換えが済んでいることもある。
     *
     * 同じ `Operation` の並び（同じ ID）でもう一度 `write` すれば、届いていた分は適用済みになり二重にならない。
     * そのやり直した `write` が [Success] 以外で終わったら、確かめられないままとして扱う。やり直しの [Failed] は、
     * 先のマニフェストの PUT が届いていないことを示さない。
     *
     * @property failure 終わったときの失敗
     */
    data class Unconfirmed(val failure: SyncFailure) : WriteResult
}

/** 古いファイルの削除の結果。 */
sealed interface DeleteStaleFilesResult {
    /**
     * 最後まで進めた。1 つの削除が失敗しても、残りは続けている。
     *
     * [failed] が 0 でなければ、消せなかったファイルがある。呼び出し側は失敗として伝える。
     *
     * @property deleted 消した項目ファイルの数
     * @property failed 消そうとして消せなかった数
     * @property lastFailure 最後に失敗した削除の短い説明（秘密情報を含まない）。失敗が無ければ null
     * @property untouched `folders/<UUID>/<UUID>.jsonl.gz` の形に合わず、触らなかったキーの数
     * @property withinGrace マニフェストから参照されていないが、猶予（最終更新から 24 時間）の中なので残した数
     */
    data class Success(
        val deleted: Int,
        val failed: Int,
        val lastFailure: String?,
        val untouched: Int,
        val withinGrace: Int,
    ) : DeleteStaleFilesResult

    /**
     * 何も消さずに終わった。マニフェストを読めなかった、マニフェストが読み書きできるものでなかった、一覧を読めなかった。
     *
     * マニフェストの形式のバージョンの扱いは [SyncFailure.ReadOnly]（`version` が新しい）と [SyncFailure.UpdateRequired]、
     * 検証の失敗は [SyncFailure.ManifestCorrupt]、
     * マニフェストの `404` は（ローカルキャッシュの有無によらず）[SyncFailure.ManifestNotFound] になる。
     * 一覧の `403` は [SyncFailure.Forbidden]、結果が不明のものは [SyncFailure.Unreachable] になる。
     */
    data class Failed(val failure: SyncFailure) : DeleteStaleFilesResult
}

/** マニフェストを新しく作った結果。 */
sealed interface CreateManifestResult {
    /** 作った。 */
    data class Created(val tree: LoadedTree) : CreateManifestResult

    /**
     * 既にマニフェストがあるので、作らなかった（`412`）。
     * そのあとマニフェストを読めず、自分の作ったマニフェストかを確かめられなかったときも含む。
     */
    data object AlreadyExists : CreateManifestResult

    /** 作れなかった。 */
    data class Failed(val failure: SyncFailure) : CreateManifestResult
}
