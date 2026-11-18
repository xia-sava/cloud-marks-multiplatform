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
import to.sava.cloudmarksmultiplatform.sync.LoadedRevision
import to.sava.cloudmarksmultiplatform.sync.Revision
import to.sava.cloudmarksmultiplatform.sync.RevisionFailure
import to.sava.cloudmarksmultiplatform.sync.SyncFailure

/** 出す画面。 */
enum class Screen {
    /** 起動して、設定を読んでいる。 */
    STARTING,

    /** メインの画面。フォルダのツリーと、選んだフォルダのリスト。 */
    MAIN,

    /** 設定の画面。メインの画面の場所に出す。 */
    SETTINGS,

    /** 以前の状態に戻す画面。設定の画面から開き、メインの画面の場所に出す。 */
    REVISIONS,
}

/** 以前の状態に戻す画面の、戻せる時点の一覧。 */
sealed interface RevisionListState {
    /** 一覧を読んでいる。 */
    data object Loading : RevisionListState

    /** 読めた。[revisions] は `LastModified` の新しい順。 */
    data class Listed(val revisions: List<Revision>) : RevisionListState

    /** 読めなかった。 */
    data class Failed(val failure: SyncFailure) : RevisionListState
}

/** 以前の状態に戻す画面で選んだ時点の、読み込みと戻すことの進み具合。 */
sealed interface RevisionSelection {
    /** 選んだ時点 [revision] のマニフェストを読んでいる。 */
    data class Loading(val revision: Revision) : RevisionSelection

    /** 読んだ時点 [loaded] へ戻すかを確かめている。 */
    data class Confirming(val loaded: LoadedRevision) : RevisionSelection

    /** 読んだ時点 [loaded] へ戻している。 */
    data class Restoring(val loaded: LoadedRevision) : RevisionSelection
}

/** 以前の状態に戻す画面で、利用者に伝えること。 */
sealed interface RevisionNotice {
    /** 選んだ時点のマニフェストを読めなかった。 */
    data class ReadFailed(val failure: RevisionFailure) : RevisionNotice

    /** 選んだ時点へ戻せなかった。 */
    data class RestoreFailed(val failure: RevisionFailure) : RevisionNotice
}

/**
 * 以前の状態に戻す画面の状態。画面を開くたびに、初めの値から始める。
 *
 * @property list 戻せる時点の一覧
 * @property selection 選んだ時点の読み込みと戻すことの進み具合。選んでいなければ null
 * @property notice 利用者に伝えること。無いか、閉じたか、次の時点を選んだら null
 */
data class RevisionsScreenState(
    val list: RevisionListState = RevisionListState.Loading,
    val selection: RevisionSelection? = null,
    val notice: RevisionNotice? = null,
)

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

/** 項目をブラウザかほかのアプリで開こうとして（共有を含む）、利用者に伝えること。 */
sealed interface BrowserNotice {
    /** ブラウザの一覧に既定のブラウザが無く、ブラウザを調べずに既定のブラウザで開こうとして、開けなかった。 */
    data object DefaultBrowserLaunchFailed : BrowserNotice

    /** ブラウザ [browserName] を起動できなかった。[detail] は詳細の行に出す文。無ければ null。 */
    data class LaunchFailed(val browserName: String, val detail: String?) : BrowserNotice

    /** ブラウザ [browserName] へまとめて開くように渡したコマンドが、拡張機能に届かなかったかもしれない。 */
    data class CommandUndelivered(val browserName: String) : BrowserNotice

    /** 開くフォルダの項目を読めなかった。 */
    data class FolderReadFailed(val failure: SyncFailure) : BrowserNotice

    /** 開くフォルダの項目ファイルが壊れている。 */
    data class FolderBroken(val problem: StorageProblem) : BrowserNotice

    /** 項目をほかのアプリで開くか共有しようとして、受け取れるアプリが無かった。 */
    data object NoAppForItem : BrowserNotice

    /** 項目をほかのアプリで開くか共有しようとして、ほかの理由で、受け取るアプリの一覧を出せなかった。理由はログにだけ書く。 */
    data object ShareFailed : BrowserNotice
}

/**
 * 画面全体の状態。
 *
 * @property write データを変える操作の、書き込みの進み具合と結果。以前の状態に戻すことも、データを変える操作と同じく扱う
 * @property revisions 以前の状態に戻す画面の状態。その画面を出していないあいだは、初めの値
 * @property displayScale 画面全体の表示の大きさ。端末ごとの設定で、どの画面でも同じ
 * @property tabLoading 2 つ以上の項目をまとめてブラウザで開くときの、タブの読み込み方。端末ごとの設定
 * @property opensMultipleItems 2 つ以上の項目をまとめてブラウザで開けるプラットフォームか。偽なら、[tabLoading] の欄を出さない
 * @property sharesItems 項目をほかのアプリで開くことと共有することができるプラットフォームか。偽なら、タッチの画面の、
 * 項目を選んでいるあいだの AppBar の、その他のオプションのメニューと、項目の行を長押ししたときのメニューに、ほかのアプリで開く行と
 * 共有の行を出さない
 * @property browsers この端末に入っているブラウザ。項目とフォルダのメニューに、ブラウザで開く行として並べる
 * @property browserNotice 項目をブラウザかほかのアプリで開こうとして（共有を含む）、利用者に伝えること。無いか、閉じたら null
 * @property lastOpenedItemId 最後に開いた項目の ID。端末ごとの記録で、リストのその項目の行を強調する。項目が消えたり別のフォルダへ
 * 移ったりしても替えない。覚えていなければ null
 */
data class AppState(
    val screen: Screen = Screen.STARTING,
    val browse: BrowseState = BrowseState(),
    val settings: SettingsScreenState = SettingsScreenState(),
    val write: WriteState = WriteState(),
    val revisions: RevisionsScreenState = RevisionsScreenState(),
    val displayScale: DisplayScale = DisplayScale.DEFAULT,
    val tabLoading: TabLoading = TabLoading(),
    val opensMultipleItems: Boolean = false,
    val sharesItems: Boolean = false,
    val browsers: List<Browser> = emptyList(),
    val browserNotice: BrowserNotice? = null,
    val lastOpenedItemId: String? = null,
) {
    /** 端末ごとの設定。 */
    val deviceSettings: DeviceSettings get() = DeviceSettings(displayScale, tabLoading)

    /**
     * 戻る操作でできることがあるか。設定の画面では、メインの画面へ戻れて、保存の最中でないとき。
     * 以前の状態に戻す画面では、戻している最中でないとき。メインの画面では、[BrowseState.canGoBack]。
     */
    val canGoBack: Boolean
        get() = when (screen) {
            Screen.STARTING -> false
            Screen.MAIN -> browse.canGoBack
            Screen.SETTINGS -> settings.canReturn && !settings.status.busy
            Screen.REVISIONS -> !write.writing
        }

    /**
     * 以前の状態に戻すことを受け付けるか。読み書きできるツリーがあり、マニフェストを読んでいる最中でも、操作を書いている最中
     * （戻している最中を含む）でも、設定の保存の最中でもないとき。以前の状態に戻す画面を開くときと、戻すと決めたときに確かめる。
     */
    val canRestore: Boolean
        get() = browse.tree != null && !browse.readOnly && !browse.loading && !write.writing && !settings.status.busy

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
     * 設定の画面ならメインの画面へ戻る。以前の状態に戻す画面なら設定の画面へ戻り、その画面の状態を初めの値にする。
     * メインの画面なら [BrowseState.back]（ドロワーが開いていれば閉じ、そうでなければ開いたフォルダの履歴を戻る）。
     * できることが無ければ何もしない。
     */
    fun back(): AppState =
        when {
            !canGoBack -> this
            screen == Screen.SETTINGS -> copy(screen = Screen.MAIN)
            screen == Screen.REVISIONS -> copy(screen = Screen.SETTINGS, revisions = RevisionsScreenState())
            else -> copy(browse = browse.back())
        }
}
