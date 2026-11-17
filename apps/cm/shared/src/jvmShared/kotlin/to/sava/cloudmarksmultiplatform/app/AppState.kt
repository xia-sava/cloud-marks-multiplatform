package to.sava.cloudmarksmultiplatform.app

import to.sava.cloudmarksmultiplatform.browse.BrowseState
import to.sava.cloudmarksmultiplatform.browser.Browser
import to.sava.cloudmarksmultiplatform.settings.DeviceSettings
import to.sava.cloudmarksmultiplatform.settings.DisplayScale
import to.sava.cloudmarksmultiplatform.settings.SettingsField
import to.sava.cloudmarksmultiplatform.settings.SettingsForm
import to.sava.cloudmarksmultiplatform.settings.SettingsReadResult
import to.sava.cloudmarksmultiplatform.settings.TabLoading
import to.sava.cloudmarksmultiplatform.storage.StorageProblem
import to.sava.cloudmarksmultiplatform.sync.SyncFailure

/** 出す画面。 */
enum class Screen {
    /** 起動して、設定を読んでいる。 */
    STARTING,

    /** メインの画面。フォルダのツリーと、選んだフォルダのリスト。 */
    MAIN,

    /** 設定の画面。メインの画面の場所に出す。 */
    SETTINGS,
}

/** 設定の画面の、保存の進み具合と結果。 */
sealed interface SettingsStatus {
    /** 入力している。 */
    data object Editing : SettingsStatus

    /** 入力の足りない欄があるので、保存しなかった。 */
    data class Incomplete(val fields: Set<SettingsField>) : SettingsStatus

    /** 設定を書き、続けてマニフェストを読んでいる。 */
    data object Saving : SettingsStatus

    /** 認証情報を暗号化する仕組みが使えないので、保存しなかった。使えない理由はログにだけ書く。 */
    data object ProtectionUnavailable : SettingsStatus

    /** 設定のファイルを書けなかった。 */
    data class WriteFailed(val detail: String) : SettingsStatus

    /** 保存したが、マニフェストが無い。マニフェストを作れる。 */
    data object ManifestNotFound : SettingsStatus

    /** マニフェストを作っている。 */
    data object CreatingManifest : SettingsStatus

    /** 保存したが、マニフェストを読めなかった、または作れなかった。権限が無い、繋がらない、そのほか。 */
    data class ConnectionFailed(val failure: SyncFailure) : SettingsStatus

    /** 設定を書いているか、S3 を読み書きしている最中か。そのあいだは入力も保存も受け付けない。 */
    val busy: Boolean get() = this == Saving || this == CreatingManifest
}

/**
 * 設定の画面の状態。保存済みのシークレットは持たない。
 *
 * @property form 入力した値。保存済みの設定を開いたときは、シークレットの欄を空にする
 * @property hasStoredSettings 保存済みの設定があるか。あれば、シークレットの欄は変えるときだけ入れればよい
 * @property readProblem 起動したときに設定を読めなかった理由。[SettingsReadResult.Found] にはしない。
 * 読めたか、保存し直したら null
 * @property canReturn メインの画面へ戻れるか。保存済みの設定で S3 への接続を作ってあるとき
 */
data class SettingsScreenState(
    val form: SettingsForm = SettingsForm(),
    val hasStoredSettings: Boolean = false,
    val readProblem: SettingsReadResult? = null,
    val status: SettingsStatus = SettingsStatus.Editing,
    val canReturn: Boolean = false,
)

/** 項目をブラウザで開こうとして、利用者に伝えること。 */
sealed interface BrowserNotice {
    /** ブラウザの一覧に既定のブラウザが無く、Windows に既定のブラウザで開かせようとして、開けなかった。 */
    data object DefaultBrowserLaunchFailed : BrowserNotice

    /** ブラウザ [browserName] を起動できなかった。[detail] は詳細の行に出す文。無ければ null。 */
    data class LaunchFailed(val browserName: String, val detail: String?) : BrowserNotice

    /** ブラウザ [browserName] へまとめて開くように渡したコマンドが、拡張機能に届かなかったかもしれない。 */
    data class CommandUndelivered(val browserName: String) : BrowserNotice

    /** 開くフォルダの項目を読めなかった。 */
    data class FolderReadFailed(val failure: SyncFailure) : BrowserNotice

    /** 開くフォルダの項目ファイルが壊れている。 */
    data class FolderBroken(val problem: StorageProblem) : BrowserNotice
}

/**
 * 画面全体の状態。
 *
 * @property write データを変える操作の、書き込みの進み具合と結果
 * @property displayScale 画面全体の表示の大きさ。端末ごとの設定で、どの画面でも同じ
 * @property tabLoading 2 つ以上の項目をまとめてブラウザで開くときの、タブの読み込み方。端末ごとの設定
 * @property opensMultipleItems 2 つ以上の項目をまとめてブラウザで開けるプラットフォームか。偽なら、[tabLoading] の欄を出さない
 * @property browsers PC に入っているブラウザ。項目とフォルダのメニューに、ブラウザで開く行として並べる
 * @property browserNotice 項目をブラウザで開こうとして、利用者に伝えること。無いか、閉じたら null
 */
data class AppState(
    val screen: Screen = Screen.STARTING,
    val browse: BrowseState = BrowseState(),
    val settings: SettingsScreenState = SettingsScreenState(),
    val write: WriteState = WriteState(),
    val displayScale: DisplayScale = DisplayScale.DEFAULT,
    val tabLoading: TabLoading = TabLoading(),
    val opensMultipleItems: Boolean = false,
    val browsers: List<Browser> = emptyList(),
    val browserNotice: BrowserNotice? = null,
) {
    /** 端末ごとの設定。 */
    val deviceSettings: DeviceSettings get() = DeviceSettings(displayScale, tabLoading)

    /**
     * 戻る操作でできることがあるか。設定の画面では、メインの画面へ戻れて、保存の最中でないとき。
     * メインの画面では、[BrowseState.canGoBack]。
     */
    val canGoBack: Boolean
        get() = when (screen) {
            Screen.STARTING -> false
            Screen.MAIN -> browse.canGoBack
            Screen.SETTINGS -> settings.canReturn && !settings.status.busy
        }

    /** S3 から読み直せるか。メインの画面で、マニフェストを読んでいる最中でも、操作を書いている最中でもないとき。 */
    val canReload: Boolean get() = screen == Screen.MAIN && !browse.loading && !write.writing

    /**
     * データを変える操作を受け付けるか。メインの画面で、読み書きできるツリーがあり、マニフェストを読んでいる最中でも、
     * 操作を書いている最中でもないとき。
     */
    val canWrite: Boolean
        get() = screen == Screen.MAIN && browse.tree != null && !browse.readOnly && !browse.loading && !write.writing

    /**
     * 戻る。Android の戻る操作と画面の戻るボタンが呼ぶ。
     *
     * 設定の画面ならメインの画面へ戻る。メインの画面なら [BrowseState.back]（ドロワーが開いていれば閉じ、
     * そうでなければ開いたフォルダの履歴を戻る）。できることが無ければ何もしない。
     */
    fun back(): AppState =
        when {
            !canGoBack -> this
            screen == Screen.SETTINGS -> copy(screen = Screen.MAIN)
            else -> copy(browse = browse.back())
        }
}
