package to.sava.cloudmarksmultiplatform.sync

import to.sava.cloudmarksmultiplatform.operations.OperationResult
import to.sava.cloudmarksmultiplatform.operations.TreeState
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
    /** S3 につながらない。通信の失敗、タイムアウト、`5xx` など、結果が分からないまま終わった。 */
    data class Unreachable(val detail: String) : SyncFailure

    /** 権限が無い（`403`）。 */
    data object Forbidden : SyncFailure

    /** 目録の `version` が知っている版より新しいので、読むだけにして書かない。アプリの更新が要る。 */
    data class ReadOnly(val version: Int, val readableBy: Int) : SyncFailure

    /** 目録の `readableBy` が知っている版より新しいか、`version` が新しく検証に失敗したので、読めない。アプリの更新が要る。 */
    data class UpdateRequired(val version: Int, val readableBy: Int) : SyncFailure

    /** 目録が壊れている。 */
    data class ManifestCorrupt(val problem: StorageProblem) : SyncFailure

    /**
     * 目録が無い（`404`）。読み書きの手順では、手元に目録のキャッシュも無いとき。古いファイルの削除は手元のキャッシュを使わないので、
     * キャッシュの有無によらず目録の `404` はこれになる。目録は、利用者が明示したときだけ新しく作る。
     */
    data object ManifestNotFound : SyncFailure

    /** 目録が消されている。手元に目録のキャッシュがあるのに目録の GET が `404` を返した、または目録の PUT が `404` を返した。 */
    data object ManifestDeleted : SyncFailure

    /** 操作が項目を読む必要のあるフォルダの、フォルダのファイルが壊れている。[problems] はフォルダの ID ごとの問題。 */
    data class FolderCorrupt(val problems: Map<String, StorageProblem>) : SyncFailure

    /** 送り直しまたは適用し直しが、回数の上限を超えた。 */
    data object Busy : SyncFailure

    /** そのほかの失敗。 */
    data class Other(val detail: String) : SyncFailure
}

/**
 * 読んだ目録と、項目を読み込んだフォルダ。
 *
 * @property eTag [manifest] の ETag。次の書き込みの `If-Match` に使う
 * @property state [manifest] の木。項目を読み込んだフォルダと、項目を持たないフォルダの項目を含む
 * @property brokenFolders フォルダのファイルが壊れていたフォルダの ID と、その問題。GET が `404` を返したファイルも、
 * 目録を読み直してなおそのファイルを指していれば含む。目録が [TreeReadResult.ReadOnly] のときは、版が新しいために
 * 読めなかったものも含む
 */
data class LoadedTree(
    val manifest: Manifest,
    val eTag: String,
    val state: TreeState,
    val brokenFolders: Map<String, StorageProblem>,
)

/** 目録とフォルダを読んだ結果。 */
sealed interface TreeReadResult {
    /** 読めた。読み書きできる。 */
    data class Readable(val tree: LoadedTree) : TreeReadResult

    /** 読めたが、目録の `version` が知っている版より新しいので、読むだけにする。アプリの更新が要る。 */
    data class ReadOnly(val tree: LoadedTree) : TreeReadResult

    /**
     * 読めなかった。
     *
     * 版の扱いは [SyncFailure.UpdateRequired]、検証の失敗は [SyncFailure.ManifestCorrupt]、目録の `404` は
     * [SyncFailure.ManifestNotFound]（手元にキャッシュが無いとき）か [SyncFailure.ManifestDeleted]（あるとき）になる。
     */
    data class Failed(val failure: SyncFailure) : TreeReadResult
}

/** 操作を書き込んだ結果。 */
sealed interface WriteResult {
    /**
     * 書けた。操作を適用した結果が読んだ状態と同じで、PUT しなかったときも含む。
     *
     * @property tree 書いたあとの目録と木。PUT しなかったときは読んだもの
     * @property results 操作ごとの結果。入力の順。適用しなかった操作を含む
     */
    data class Success(val tree: LoadedTree, val results: List<OperationResult>) : WriteResult

    /** 書けなかった。届いたかどうかが分からない目録の PUT を残さずに終わった。 */
    data class Failed(val failure: SyncFailure) : WriteResult

    /**
     * 書けたかどうかを確かめられなかった。結果が分からないか `412` を受けた目録の PUT があり、届いたとも届いていないとも
     * 成功の判定で確かめられないまま終わった。書き換えが済んでいることもある。
     *
     * 同じ `Operation` の並び（同じ ID）でもう一度 `write` すれば、届いていた分は適用済みになり二重にならない。
     * そのやり直した `write` が [Success] 以外で終わったら、確かめられないままとして扱う。やり直しの [Failed] は、
     * 先の目録の PUT が届いていないことを示さない。
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
     * @property deleted 消したフォルダのファイルの数
     * @property failed 消そうとして消せなかった数
     * @property lastFailure 最後に失敗した削除の短い説明（秘密情報を含まない）。失敗が無ければ null
     * @property untouched `folders/<UUID>/<UUID>.jsonl.gz` の形に合わず、触らなかったキーの数
     * @property withinGrace 目録から参照されていないが、猶予（最終更新から 24 時間）の中なので残した数
     */
    data class Success(
        val deleted: Int,
        val failed: Int,
        val lastFailure: String?,
        val untouched: Int,
        val withinGrace: Int,
    ) : DeleteStaleFilesResult

    /**
     * 何も消さずに終わった。目録を読めなかった、目録が読み書きできるものでなかった、一覧を読めなかった。
     *
     * 目録の版の扱いは [SyncFailure.ReadOnly]（`version` が新しい）と [SyncFailure.UpdateRequired]、
     * 検証の失敗は [SyncFailure.ManifestCorrupt]、目録の `404` は（手元のキャッシュの有無によらず）[SyncFailure.ManifestNotFound] になる。
     * 一覧の `403` は [SyncFailure.Forbidden]、結果が分からないものは [SyncFailure.Unreachable] になる。
     */
    data class Failed(val failure: SyncFailure) : DeleteStaleFilesResult
}

/** 目録を新しく作った結果。 */
sealed interface CreateManifestResult {
    /** 作った。 */
    data class Created(val tree: LoadedTree) : CreateManifestResult

    /** 既に目録があるので、作らなかった（`412`）。そのあと目録を読めず、自分の作った目録かを確かめられなかったときも含む。 */
    data object AlreadyExists : CreateManifestResult

    /** 作れなかった。 */
    data class Failed(val failure: SyncFailure) : CreateManifestResult
}
