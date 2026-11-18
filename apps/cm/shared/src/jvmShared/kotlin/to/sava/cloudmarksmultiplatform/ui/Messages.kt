package to.sava.cloudmarksmultiplatform.ui

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import to.sava.cloudmarksmultiplatform.app.BrowserNotice
import to.sava.cloudmarksmultiplatform.app.SettingsStatus
import to.sava.cloudmarksmultiplatform.app.WriteOutcome
import to.sava.cloudmarksmultiplatform.settings.DisplayScale
import to.sava.cloudmarksmultiplatform.settings.SettingsReadResult
import to.sava.cloudmarksmultiplatform.storage.StorageProblem
import to.sava.cloudmarksmultiplatform.sync.ManifestCounts
import to.sava.cloudmarksmultiplatform.sync.RevisionFailure
import to.sava.cloudmarksmultiplatform.sync.SyncFailure
import kotlin.time.Instant

/** ルートフォルダを、ツリーと AppBar に出すときの名前。ルートフォルダの名前は空なので、代わりにこれを出す。 */
internal const val ROOT_FOLDER_LABEL = "トップ"

internal const val APP_TITLE = "Cloud Marks"
internal const val SETTINGS_TITLE = "設定"

/** マニフェストの `version` が知っている版より新しく、読むだけにしていることを伝える文。 */
internal const val READ_ONLY_NOTICE = "データの形式が新しいため、表示はできますが変更はできません。アプリを更新してください。"

/** フォルダが空であることを伝える文。 */
internal const val EMPTY_FOLDER_NOTICE = "このフォルダは空です。"

/** 消す操作。AppBar のボタン、項目とフォルダのメニューの項目、フォルダの削除の確認の、確定のボタン。 */
internal const val DELETE_LABEL = "削除"

/** 移す操作。AppBar のボタン、項目とフォルダのメニューの項目、移動先を選ぶダイアログの、確定のボタン。 */
internal const val MOVE_LABEL = "移動"

/** 項目のタイトルを変える操作。項目のメニューの項目と、ダイアログの題。 */
internal const val RENAME_ITEM_LABEL = "タイトルを変更"

/** フォルダの名前を変える操作。フォルダのメニューの項目と、ダイアログの題。 */
internal const val RENAME_FOLDER_LABEL = "名前を変更"

/** フォルダを作る操作。フォルダのメニューの項目と、ダイアログの題。 */
internal const val NEW_FOLDER_LABEL = "新しいフォルダ"

/** 項目のタイトルを変えるダイアログの、入力欄。 */
internal const val TITLE_FIELD_LABEL = "タイトル"

/** フォルダの名前を入れるダイアログの、入力欄。 */
internal const val NAME_FIELD_LABEL = "名前"

/** 名前とタイトルを変えるダイアログの、確定のボタン。 */
internal const val CHANGE_LABEL = "変更"

/** フォルダを作るダイアログの、確定のボタン。 */
internal const val CREATE_LABEL = "作成"

/** ダイアログを閉じるボタン。 */
internal const val CANCEL_LABEL = "キャンセル"

/** フォルダの名前を入れるダイアログで、名前が空のときに欄の下に添える文。 */
internal const val EMPTY_NAME_NOTICE = "名前を入力してください。"

/** フォルダの削除の確認の題。 */
internal const val DELETE_FOLDER_TITLE = "フォルダを削除しますか？"

/** 移動先を選ぶダイアログの題。 */
internal const val MOVE_DESTINATION_TITLE = "移動先のフォルダを選んでください"

/** 項目の削除を戻す、お知らせのボタン。 */
internal const val UNDO_LABEL = "元に戻す"

/** 書き込みの結果の帯と、マニフェストの読み込みの失敗の帯を閉じるボタン。 */
internal const val CLOSE_LABEL = "閉じる"

/** データを変える操作を書いている最中であることを伝える文。 */
internal const val WRITING_NOTICE = "保存しています…"

/** AppBar の、ツリーを出し入れするボタン。ボタンの説明とツールチップに使う。 */
internal const val TREE_PANE_DESCRIPTION = "フォルダの一覧"

/** AppBar の、戻るボタン。ボタンの説明とツールチップに使う。 */
internal const val BACK_DESCRIPTION = "戻る"

/** ドロワーの、ドロワーを閉じるボタン。ボタンの説明とツールチップに使う。 */
internal const val CLOSE_DRAWER_DESCRIPTION = "フォルダの一覧を閉じる"

/** AppBar の、マニフェストを読み直すボタン。ボタンの説明とツールチップに使う。 */
internal const val RELOAD_DESCRIPTION = "再読み込み"

/** AppBar の、設定の画面を開くボタン。ボタンの説明とツールチップに使う。 */
internal const val SETTINGS_DESCRIPTION = "設定"

/** タッチの画面の、項目を選んでいるあいだの AppBar の題。[count] は選んでいる項目の数。 */
internal fun selectionTitle(count: Int): String = "$count 件を選択中"

/** タッチの画面の、項目を選んでいるあいだの AppBar の、選ぶのをやめるボタン。ボタンの説明とツールチップに使う。 */
internal const val CLEAR_SELECTION_DESCRIPTION = "選択を解除"

/** タッチの画面の、項目を選んでいるあいだの AppBar の、その他のオプションのメニューを出すボタン。ボタンの説明とツールチップに使う。 */
internal const val MORE_OPTIONS_DESCRIPTION = "その他のオプション"

/** 項目を、既定のブラウザで開く操作。タッチの画面の、項目を長押ししたときのメニューの項目。 */
internal const val OPEN_LABEL = "開く"

/** 項目を、ほかのアプリで開く操作。AppBar のその他のオプションのメニューと、タッチの画面の、項目を長押ししたときのメニューの項目。 */
internal const val OPEN_IN_OTHER_APP_LABEL = "アプリを選んで開く"

/** 項目を共有する操作。AppBar のその他のオプションのメニューと、タッチの画面の、項目を長押ししたときのメニューの項目。 */
internal const val SHARE_LABEL = "共有"

/** 長押しした項目だけを選び、選ぶ状態に入る操作。タッチの画面の、項目を長押ししたときのメニューの項目。 */
internal const val SELECT_LABEL = "選択"

/** ツリーの、閉じたノードを開くボタン。ボタンの説明とツールチップに使う。 */
internal const val EXPAND_DESCRIPTION = "展開"

/** ツリーの、開いたノードを閉じるボタン。ボタンの説明とツールチップに使う。 */
internal const val COLLAPSE_DESCRIPTION = "折りたたみ"

/** 設定の画面の、表示の大きさを選ぶ欄の題。 */
internal const val DISPLAY_SCALE_LABEL = "表示の大きさ"

/** 表示の大きさの段 [scale] の表記。`80%` のように、百分率の数字に `%` を付ける。 */
internal fun displayScaleLabel(scale: DisplayScale): String = "${scale.percent}%"

/** 設定の画面の、まとめてブラウザで開くときの欄の見出し。 */
internal const val OPEN_IN_BROWSER_SECTION_TITLE = "ブラウザで開くとき"

/** 設定の画面の、同時に読み込むタブの数の欄の題。 */
internal const val LOAD_AT_ONCE_LABEL = "同時に読み込むタブの数"

/** 設定の画面の、先に読み込むタブの数の欄の題。 */
internal const val LOAD_AHEAD_LABEL = "先に読み込むタブの数"

/** 設定の画面の、先に読み込むタブの数の欄の下に、入力が正しいときに添える文。 */
internal const val LOAD_AHEAD_HINT = "これより後ろのタブは、表示したときに読み込みます。0 にすると、すべてのタブを順に読み込みます。"

/** 設定の画面の、同時に読み込むタブの数の欄に 1 以上の整数でないものを入れたときに、欄の下に添える文。 */
internal const val TAB_COUNT_INVALID_NOTICE = "1 以上の数を入力してください。"

/** 設定の画面の、先に読み込むタブの数の欄に 0 以上の整数でないものを入れたときに、[LOAD_AHEAD_HINT] の代わりに欄の下に添える文。 */
internal const val LOAD_AHEAD_INVALID_NOTICE = "0 以上の数を入力してください。"

/** 拡張機能が入っていると分かっていないブラウザの、まとめて開く行の、押せない理由のツールチップ。 */
internal const val EXTENSION_REQUIRED_NOTICE =
    "このブラウザに Cloud Marks の拡張機能が入っていることを確認できていないため、まとめて開けません。" +
        "拡張機能を入れてから、ブラウザを起動し直してください。"

/**
 * 既定のブラウザを起動できなかったことを伝える文。項目をほかのアプリで開くか共有しようとして、受け取れるアプリが無かったこと以外の理由で
 * 受け取るアプリの一覧を出せなかったときにも使う。
 */
internal const val DEFAULT_BROWSER_LAUNCH_FAILED = "ブラウザを起動できませんでした。"

/** フォルダのメニューからブラウザで開こうとして、そのフォルダの項目ファイルが壊れていたことを伝える文。詳細の行に壊れ方を出す。 */
internal const val FOLDER_BROKEN_FOR_BROWSER_NOTICE = "フォルダのデータが壊れているため、ブラウザで開けません。"

/** 項目をほかのアプリで開くか共有しようとして、受け取れるアプリが無かったことを伝える文。 */
internal const val NO_APP_FOR_URL_NOTICE = "この URL を開けるアプリがありません。"

/** 項目とフォルダのメニューの、ブラウザ [browserName] で開く行。 */
internal fun openInBrowserLabel(browserName: String): String = "$browserName で開く"

/** 選んだブラウザ [browserName] を起動できなかったことを伝える文。 */
internal fun browserLaunchFailed(browserName: String): String = "$browserName を起動できませんでした。"

/** ブラウザ [browserName] へまとめて開くように渡したコマンドが、拡張機能に届かなかったかもしれないことを伝える文。 */
internal fun commandUndeliveredNotice(browserName: String): String =
    "$browserName でタブを開けなかった可能性があります。" +
        "$browserName に Cloud Marks の拡張機能が入っていて、有効になっているか確認してください。"

/**
 * 項目をブラウザかほかのアプリで開こうとして（共有を含む）伝えることを、利用者に見せる文にする。フォルダの項目を読めなかったときは、
 * 読めなかった理由の文にする。ほかのアプリで開くか共有しようとして、受け取れるアプリが無かったこと以外の理由で出せなかったときは、
 * 既定のブラウザを起動できなかったときと同じ文にする。
 */
internal fun describeBrowserNotice(notice: BrowserNotice): UiMessage =
    when (notice) {
        BrowserNotice.DefaultBrowserLaunchFailed -> UiMessage(DEFAULT_BROWSER_LAUNCH_FAILED)
        is BrowserNotice.LaunchFailed -> UiMessage(browserLaunchFailed(notice.browserName), notice.detail)
        is BrowserNotice.CommandUndelivered -> UiMessage(commandUndeliveredNotice(notice.browserName))
        is BrowserNotice.FolderReadFailed -> describeSyncFailure(notice.failure)
        is BrowserNotice.FolderBroken -> UiMessage(FOLDER_BROKEN_FOR_BROWSER_NOTICE, notice.problem.toString())
        BrowserNotice.NoAppForItem -> UiMessage(NO_APP_FOR_URL_NOTICE)
        BrowserNotice.ShareFailed -> UiMessage(DEFAULT_BROWSER_LAUNCH_FAILED)
    }

/** 項目を [count] 件消したあとの、お知らせの本文。 */
internal fun deletedItemsNotice(count: Int): String = "$count 件の項目を削除しました。"

/** 項目を [count] 件消し、S3 につながらないために溜めたあとの、お知らせの本文。[deletedItemsNotice] の代わりに出す。 */
internal fun pendingDeletedItemsNotice(count: Int): String = "$count 件の項目を削除しました。ネットワークにつながったら保存先に反映します。"

/** 溜めた削除があるあいだ、メインの画面の帯に出す文。[count] は溜めた項目の数。 */
internal fun pendingDeletesNotice(count: Int): String =
    "削除した $count 件の項目は、まだ保存先に反映していません。ネットワークにつながったら反映します。"

/** 2 つ以上の項目をドラッグしているあいだの札。[count] はドラッグしている項目の数。 */
internal fun draggedItemsLabel(count: Int): String = "$count 件の項目"

/** フォルダ [name] の削除の確認の本文。 */
internal fun deleteFolderConfirmation(name: String): String =
    "「$name」と、その中のフォルダと項目をすべて削除します。この操作は元に戻せません。"

/**
 * データを変える操作の結果を、利用者に見せる文にする。書けて、伝えることが無ければ null。
 * 以前の状態に戻せたときも null（お知らせの [restoredNotice] で伝える）。
 */
internal fun describeWriteOutcome(outcome: WriteOutcome): UiMessage? =
    when (outcome) {
        WriteOutcome.Written, is WriteOutcome.Restored -> null
        is WriteOutcome.NotApplied -> UiMessage("対象が見つからなかったため、一部の変更を反映できませんでした。")
        is WriteOutcome.Unconfirmed ->
            UiMessage("変更を保存できたかどうかを確認できませんでした。再読み込みして確認してください。")
        is WriteOutcome.Failed ->
            describeSyncFailure(outcome.failure).let { failure ->
                failure.copy(text = "変更を保存できませんでした。\n${failure.text}")
            }
    }

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

/** 設定の画面の、以前の状態に戻す画面を開くボタンと、その画面の題。 */
internal const val REVISIONS_TITLE = "以前の状態に戻す"

/** 設定の画面の、以前の状態に戻す画面を開くボタンの下に添える説明。 */
internal const val REVISIONS_DESCRIPTION = "保存先のデータを、過去のある時点の状態に戻します。"

/** 以前の状態に戻す画面で、戻せる時点が無いことを伝える文。 */
internal const val NO_REVISIONS_NOTICE = "戻せる以前の状態がありません。"

/** 以前の状態に戻す確認の題。 */
internal const val RESTORE_CONFIRMATION_TITLE = "この時点の状態に戻しますか？"

/** 以前の状態に戻す確認の、確定のボタン。 */
internal const val RESTORE_LABEL = "戻す"

/** 以前の状態に戻している最中であることを伝える文。 */
internal const val RESTORING_NOTICE = "以前の状態に戻しています…"

/** 戻せる時点の一覧を読めなかったことを伝える文。S3 の失敗の文を次の行に続ける。 */
private const val REVISION_LIST_FAILED_NOTICE = "以前の状態の一覧を読み込めませんでした。"

/** 選んだ時点のデータを読めなかったことを伝える文。S3 の失敗の文を次の行に続ける。 */
private const val REVISION_READ_FAILED_NOTICE = "この時点のデータを読み込めませんでした。"

/** 以前の状態に戻せなかったことを伝える文。S3 の失敗の文を次の行に続ける。 */
private const val RESTORE_FAILED_NOTICE = "以前の状態に戻せませんでした。"

private const val REVISION_INCOMPLETE_NOTICE = "この時点のデータの一部が残っていないため、戻せません。"
private const val REVISION_UPDATE_REQUIRED_NOTICE = "この時点のデータは形式が新しいため、戻せません。アプリを更新してください。"
private const val REVISION_CORRUPT_NOTICE = "この時点のデータが壊れているため、戻せません。"

private val REVISION_TIME_FORMAT = LocalDateTime.Format {
    year()
    char('/')
    monthNumber()
    char('/')
    day()
    char(' ')
    hour()
    char(':')
    minute()
}

/** 戻せる時点の日時の表記。[timeZone] のローカル時刻の `YYYY/MM/DD HH:mm`（例：`2026/11/17 14:08`）。月・日・時・分は 2 桁。 */
internal fun formatRevisionTime(time: Instant, timeZone: TimeZone = TimeZone.currentSystemDefault()): String =
    REVISION_TIME_FORMAT.format(time.toLocalDateTime(timeZone))

/** 戻せる時点の一覧を読めなかった失敗を、利用者に見せる文にする。 */
internal fun describeRevisionListFailure(failure: SyncFailure): UiMessage =
    describeSyncFailure(failure).let { message -> message.copy(text = "$REVISION_LIST_FAILED_NOTICE\n${message.text}") }

/** 選んだ時点のデータを読めなかった失敗を、利用者に見せる文にする。 */
internal fun describeRevisionReadFailure(failure: RevisionFailure): UiMessage =
    describeRevisionFailure(failure, REVISION_READ_FAILED_NOTICE)

/** 以前の状態に戻せなかった失敗を、利用者に見せる文にする。 */
internal fun describeRestoreFailure(failure: RevisionFailure): UiMessage =
    describeRevisionFailure(failure, RESTORE_FAILED_NOTICE)

private fun describeRevisionFailure(failure: RevisionFailure, syncFailureHeading: String): UiMessage =
    when (failure) {
        is RevisionFailure.Sync ->
            describeSyncFailure(failure.failure).let { message ->
                message.copy(text = "$syncFailureHeading\n${message.text}")
            }
        RevisionFailure.Incomplete -> UiMessage(REVISION_INCOMPLETE_NOTICE)
        RevisionFailure.UpdateRequired -> UiMessage(REVISION_UPDATE_REQUIRED_NOTICE)
        is RevisionFailure.Corrupt -> UiMessage(REVISION_CORRUPT_NOTICE, failure.problem.toString())
    }

/** 以前の状態に戻す確認の本文。[time] は戻す時点の日時（[formatRevisionTime]）。 */
internal fun restoreConfirmation(time: String): String = "$time の状態に戻します。それより後の変更は、すべて取り消されます。"

/** 以前の状態に戻す確認の、戻す時点のフォルダと項目の数の行。 */
internal fun revisionCountsLine(counts: ManifestCounts): String = "この時点：フォルダ ${counts.folders} 個、項目 ${counts.items} 件"

/** 以前の状態に戻す確認の、いまのフォルダと項目の数の行。 */
internal fun currentCountsLine(counts: ManifestCounts): String = "現在：フォルダ ${counts.folders} 個、項目 ${counts.items} 件"

/** 以前の状態に戻す確認の、その時点のデータを保存したアプリの行。[updatedBy] が `android`・`desktop`・`host` のどれでもなければ null。 */
internal fun savedByLine(updatedBy: String): String? =
    when (updatedBy) {
        "android" -> "保存したアプリ：Android 版"
        "desktop" -> "保存したアプリ：デスクトップ版"
        "host" -> "保存したアプリ：ブラウザの拡張機能"
        else -> null
    }

/** 以前の状態に戻せたことのお知らせ。[time] は戻した時点の日時（[formatRevisionTime]）。 */
internal fun restoredNotice(time: String): String = "$time の状態に戻しました。"

/** 項目ファイルが壊れているフォルダを、利用者に見せる文にする。 */
internal fun describeBrokenFolder(problem: StorageProblem): UiMessage =
    UiMessage("このフォルダのデータが壊れているため、表示できません。", problem.toString())

/**
 * 保存されているアクセスキーを復号できなかったことを伝える文。プラットフォームごとに違い、デスクトップは、別のユーザーや別の PC で
 * 保存した設定は使えないことを添える。
 */
internal expect val UNDECRYPTABLE_CREDENTIALS_NOTICE: String

/**
 * 起動したときに設定を読めなかった理由を、利用者に見せる文にする。読めたときは null。
 * 認証情報を復号できなかったときは、詳細を付けない（暗号化の仕組みが返した文は、ログにだけ書く）。
 */
internal fun describeSettingsReadProblem(problem: SettingsReadResult): UiMessage? =
    when (problem) {
        SettingsReadResult.NotFound -> UiMessage("データを保存する S3 のバケットと、アクセスキーを入力してください。")
        is SettingsReadResult.Corrupt ->
            UiMessage("設定ファイルを読み込めませんでした。もう一度入力して保存してください。", problem.detail)
        is SettingsReadResult.Undecryptable -> UiMessage(UNDECRYPTABLE_CREDENTIALS_NOTICE)
        is SettingsReadResult.Failed -> UiMessage("設定ファイルを読み込めませんでした。", problem.detail)
        is SettingsReadResult.Found -> null
    }

/**
 * 設定の画面の、保存の進み具合と結果を、利用者に見せる文にする。入力しているときは null。
 * 暗号化の仕組みが使えなかったときは、詳細を付けない（暗号化の仕組みが返した文は、ログにだけ書く）。
 */
internal fun describeSettingsStatus(status: SettingsStatus): UiMessage? =
    when (status) {
        SettingsStatus.Editing -> null
        is SettingsStatus.Incomplete -> UiMessage("未入力の項目があります。")
        SettingsStatus.Saving -> UiMessage("保存して、S3 に接続しています…")
        SettingsStatus.ProtectionUnavailable -> UiMessage("この環境ではアクセスキーを暗号化できないため、保存しませんでした。")
        is SettingsStatus.WriteFailed -> UiMessage("設定を保存できませんでした。", status.detail)
        SettingsStatus.ManifestNotFound -> UiMessage("保存しました。この保存先にはまだデータがありません。新しく作成しますか？")
        SettingsStatus.CreatingManifest -> UiMessage("データを作成しています…")
        is SettingsStatus.ConnectionFailed ->
            describeSyncFailure(status.failure).let { failure ->
                failure.copy(text = "設定は保存しましたが、S3 からデータを読み込めませんでした。\n${failure.text}")
            }
    }
