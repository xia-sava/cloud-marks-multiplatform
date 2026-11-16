package to.sava.cloudmarksmultiplatform.ui

import to.sava.cloudmarksmultiplatform.app.SettingsStatus
import to.sava.cloudmarksmultiplatform.settings.SettingsReadResult
import to.sava.cloudmarksmultiplatform.storage.StorageProblem
import to.sava.cloudmarksmultiplatform.sync.SyncFailure

/** ルートフォルダを、ツリーと AppBar に出すときの名前。ルートフォルダの名前は空なので、代わりにこれを出す。 */
internal const val ROOT_FOLDER_LABEL = "トップ"

internal const val APP_TITLE = "Cloud Marks"
internal const val SETTINGS_TITLE = "設定"

/** マニフェストの `version` が知っている版より新しく、読むだけにしていることの知らせ。 */
internal const val READ_ONLY_NOTICE = "データの形式が新しいため、表示はできますが変更はできません。アプリを更新してください。"

/** フォルダが空であることの知らせ。 */
internal const val EMPTY_FOLDER_NOTICE = "このフォルダは空です。"

/**
 * 利用者に見せる文。
 *
 * @property text 本文
 * @property detail 例外の種類や壊れている箇所などの細かい理由。無ければ null。本文の下に、小さく薄い文字で出す
 */
internal data class UiMessage(val text: String, val detail: String? = null)

/** S3 の読み書きの失敗を、利用者に見せる文にする。 */
internal fun describeSyncFailure(failure: SyncFailure): UiMessage =
    when (failure) {
        is SyncFailure.Unreachable ->
            UiMessage("S3 に接続できませんでした。ネットワークの状態を確認してください。", failure.detail)
        SyncFailure.Forbidden ->
            UiMessage("S3 へのアクセスが拒否されました。バケット名、プレフィックス、アクセスキーの権限を確認してください。")
        is SyncFailure.ReadOnly -> UiMessage(READ_ONLY_NOTICE)
        is SyncFailure.UpdateRequired -> UiMessage("データの形式が新しいため、読み込めません。アプリを更新してください。")
        is SyncFailure.ManifestCorrupt ->
            UiMessage("保存先のデータが壊れているため、読み込めません。", failure.problem.toString())
        SyncFailure.ManifestNotFound ->
            UiMessage("この保存先にはまだデータがありません。設定画面で保存し直すと、新しく作成できます。")
        SyncFailure.ManifestDeleted -> UiMessage("保存先のデータが見つかりません。削除された可能性があります。")
        is SyncFailure.FolderCorrupt -> UiMessage("フォルダのデータが壊れているため、変更できません。")
        SyncFailure.Busy ->
            UiMessage("S3 が混み合っているため、処理を終えられませんでした。しばらくしてからやり直してください。")
        is SyncFailure.Other -> UiMessage("S3 とのやり取りでエラーが起きました。", failure.detail)
    }

/** 項目ファイルが壊れているフォルダを、利用者に見せる文にする。 */
internal fun describeBrokenFolder(problem: StorageProblem): UiMessage =
    UiMessage("このフォルダのデータが壊れているため、表示できません。", problem.toString())

/** 起動したときに設定を読めなかった理由を、利用者に見せる文にする。読めたときは null。 */
internal fun describeSettingsReadProblem(problem: SettingsReadResult): UiMessage? =
    when (problem) {
        SettingsReadResult.NotFound -> UiMessage("データを保存する S3 のバケットと、アクセスキーを入力してください。")
        is SettingsReadResult.Corrupt ->
            UiMessage("設定ファイルを読み込めませんでした。もう一度入力して保存してください。", problem.detail)
        is SettingsReadResult.Undecryptable ->
            UiMessage(
                "保存されているアクセスキーを読み込めませんでした。" +
                    "別のユーザーや別の PC で保存した設定は使えないため、もう一度入力して保存してください。",
                problem.detail,
            )
        is SettingsReadResult.Failed -> UiMessage("設定ファイルを読み込めませんでした。", problem.detail)
        is SettingsReadResult.Found -> null
    }

/** 設定の画面の、保存の進み具合と結果を、利用者に見せる文にする。入力しているときは null。 */
internal fun describeSettingsStatus(status: SettingsStatus): UiMessage? =
    when (status) {
        SettingsStatus.Editing -> null
        is SettingsStatus.Incomplete -> UiMessage("未入力の項目があります。")
        SettingsStatus.Saving -> UiMessage("保存して、S3 に接続しています…")
        is SettingsStatus.ProtectionUnavailable ->
            UiMessage("この環境ではアクセスキーを暗号化できないため、保存しませんでした。", status.detail)
        is SettingsStatus.WriteFailed -> UiMessage("設定を保存できませんでした。", status.detail)
        SettingsStatus.ManifestNotFound -> UiMessage("保存しました。この保存先にはまだデータがありません。新しく作成しますか？")
        SettingsStatus.CreatingManifest -> UiMessage("データを作成しています…")
        is SettingsStatus.ConnectionFailed ->
            describeSyncFailure(status.failure).let { failure ->
                failure.copy(text = "設定は保存しましたが、S3 からデータを読み込めませんでした。\n${failure.text}")
            }
    }
