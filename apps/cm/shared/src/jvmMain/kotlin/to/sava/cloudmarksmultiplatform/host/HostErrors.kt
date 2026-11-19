package to.sava.cloudmarksmultiplatform.host

import to.sava.cloudmarksmultiplatform.sync.SyncFailure
import to.sava.cloudmarksmultiplatform.sync.WriteResult

/** 設定のファイルが無いか、未入力の項目がある。 */
internal val SETTINGS_MISSING_ERROR = HostError(
    HostErrorCode.NOT_CONFIGURED,
    "S3 の設定がまだありません。Cloud Marks のアプリで、保存先とアクセスキーを設定してください。",
)

/** 設定のファイルか、その中のアクセスキーを読めない。 */
internal val SETTINGS_UNREADABLE_ERROR = HostError(
    HostErrorCode.NOT_CONFIGURED,
    "アプリの設定を読み込めませんでした。Cloud Marks のアプリで、設定を保存し直してください。",
)

/** S3 に接続できない（[SyncFailure.Unreachable]）。 */
internal val UNREACHABLE_ERROR = HostError(
    HostErrorCode.STORAGE_UNAVAILABLE,
    "S3 に接続できませんでした。ネットワークの状態を確認してください。",
)

/** S3 の権限が無い（[SyncFailure.Forbidden]）。 */
internal val FORBIDDEN_ERROR = HostError(
    HostErrorCode.STORAGE_UNAVAILABLE,
    "S3 へのアクセスが拒否されました。Cloud Marks のアプリで、バケット名、プレフィックス、アクセスキーの権限を確認してください。",
)

/** データの形式のバージョンがホストより新しい（[SyncFailure.ReadOnly]・[SyncFailure.UpdateRequired]）。 */
internal val UPDATE_REQUIRED_ERROR = HostError(
    HostErrorCode.UPDATE_REQUIRED,
    "データの形式が新しいため、追加できません。Cloud Marks のアプリを更新してください。",
)

/** マニフェストが壊れている（[SyncFailure.ManifestCorrupt]）。 */
internal val MANIFEST_CORRUPT_ERROR = HostError(
    HostErrorCode.DATA_BROKEN,
    "保存先のデータが壊れているため、追加できません。",
)

/** マニフェストがまだ無い（[SyncFailure.ManifestNotFound]）。 */
internal val MANIFEST_NOT_FOUND_ERROR = HostError(
    HostErrorCode.DATA_BROKEN,
    "この保存先にはまだデータがありません。Cloud Marks のアプリの設定画面で保存し直すと、新しく作成できます。",
)

/** マニフェストが消されている（[SyncFailure.ManifestDeleted]）。 */
internal val MANIFEST_DELETED_ERROR = HostError(
    HostErrorCode.DATA_BROKEN,
    "保存先のデータが見つかりません。削除された可能性があります。",
)

/** 追加先のフォルダの項目ファイルが壊れている（[SyncFailure.FolderCorrupt]）。 */
internal val FOLDER_CORRUPT_ERROR = HostError(
    HostErrorCode.DATA_BROKEN,
    "追加先のフォルダのデータが壊れているため、追加できません。",
)

/** 再送か再適用が、回数の上限を超えた（[SyncFailure.Busy]）。 */
internal val BUSY_ERROR = HostError(
    HostErrorCode.BUSY,
    "S3 が混み合っているため、追加を終えられませんでした。しばらくしてからやり直してください。",
)

/** 同じ操作で書き込みをやり直しても、書けたかどうかを確かめられなかった（[WriteResult.Unconfirmed]）。 */
internal val UNCONFIRMED_ERROR = HostError(
    HostErrorCode.UNCONFIRMED,
    "追加できたかどうかを確認できませんでした。もう一度追加すると、同じものが二重に入ることがあります。" +
        "先に Cloud Marks のアプリで確認してください。",
)

/** 要求が取り決めに合わない。 */
internal val INVALID_REQUEST_ERROR = HostError(
    HostErrorCode.INVALID_REQUEST,
    "追加する内容を受け付けられませんでした。拡張機能とアプリのバージョンが合っていない可能性があります。両方を最新にしてください。",
)

/** デスクトップのアプリを起動できなかった。 */
internal val LAUNCH_FAILED_ERROR = HostError(
    HostErrorCode.LAUNCH_FAILED,
    "Cloud Marks を開けませんでした。スタートメニューから起動してください。",
)

/** 開発版のホストなので、デスクトップのアプリを起動しない。開発版のアプリは配布物のランチャーを持たない。 */
internal val DEV_HOST_LAUNCH_ERROR = HostError(
    HostErrorCode.LAUNCH_FAILED,
    "開発版の Cloud Marks は、拡張機能から開けません。",
)

/** S3 とのやり取りの、そのほかの失敗（[SyncFailure.Other]）。 */
internal val STORAGE_OTHER_ERROR = HostError(
    HostErrorCode.INTERNAL,
    "S3 とのやり取りでエラーが起きたため、追加できませんでした。",
)

/** そのほかの失敗。予期しない例外、適用できなかった操作など。 */
internal val INTERNAL_ERROR = HostError(
    HostErrorCode.INTERNAL,
    "エラーが起きたため、追加できませんでした。",
)

/** S3 の読み書きの失敗 [failure] を、応答の `error` にする。 */
internal fun hostErrorOf(failure: SyncFailure): HostError =
    when (failure) {
        is SyncFailure.Unreachable -> UNREACHABLE_ERROR
        SyncFailure.Forbidden -> FORBIDDEN_ERROR
        is SyncFailure.ReadOnly, is SyncFailure.UpdateRequired -> UPDATE_REQUIRED_ERROR
        is SyncFailure.ManifestCorrupt -> MANIFEST_CORRUPT_ERROR
        SyncFailure.ManifestNotFound -> MANIFEST_NOT_FOUND_ERROR
        SyncFailure.ManifestDeleted -> MANIFEST_DELETED_ERROR
        is SyncFailure.FolderCorrupt -> FOLDER_CORRUPT_ERROR
        SyncFailure.Busy -> BUSY_ERROR
        is SyncFailure.Other -> STORAGE_OTHER_ERROR
    }

/**
 * 書き込みの結果 [result] を、応答にする。
 *
 * 書けても、適用できなかった操作があれば（[to.sava.cloudmarksmultiplatform.operations.OperationResult.hasNoticeForUser]）、
 * そのほかの失敗にする。確かめられなかったときは、中の失敗の種類によらず [UNCONFIRMED_ERROR]。
 */
internal fun hostResponseOf(result: WriteResult): HostResponse =
    when (result) {
        is WriteResult.Success ->
            if (result.results.any { it.hasNoticeForUser }) HostResponse.Failed(INTERNAL_ERROR) else HostResponse.Ok
        is WriteResult.Failed -> HostResponse.Failed(hostErrorOf(result.failure))
        is WriteResult.Unconfirmed -> HostResponse.Failed(UNCONFIRMED_ERROR)
    }
