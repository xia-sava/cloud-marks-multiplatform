package to.sava.cloudmarksmultiplatform.browser

import to.sava.cloudmarksmultiplatform.settings.TabLoading

/**
 * この端末に入っているブラウザ。
 *
 * @property name 画面に出す名前
 * @property executable 実行ファイルの絶対パス
 * @property isDefault 既定のブラウザか
 * @property opensMultipleItems 2 つ以上の項目をまとめて開けるか。Cloud Marks の拡張機能が入っていると分かっているとき
 */
data class Browser(
    val name: String,
    val executable: String,
    val isDefault: Boolean,
    val opensMultipleItems: Boolean,
)

/**
 * ブラウザのタブの URL とタイトル。項目をブラウザで開くときのタブと、拡張機能が追加の要求で送ってきたタブの両方に使う。
 *
 * @property title タブのタイトル。空でもよい
 */
data class BrowserTab(val url: String, val title: String)

/** ブラウザで開いた結果。 */
sealed interface BrowserOpenResult {
    /** 開いた。まとめて開いたときは、コマンドが拡張機能に届いたことも含む。 */
    data object Opened : BrowserOpenResult

    /** ブラウザを起動できなかった。[detail] は、画面の詳細の行に出す、後から追うための短い文。出すものが無ければ null。 */
    data class LaunchFailed(val detail: String?) : BrowserOpenResult

    /** まとめて開こうとしてブラウザを起動したが、決めた時間がたってもコマンドのファイルが残っていた。拡張機能に届かなかったかもしれない。 */
    data object CommandUndelivered : BrowserOpenResult
}

/**
 * 項目をブラウザで開く仕組み。プラットフォームごとに作り、`createCloudMarksController` に渡す。
 * どの関数も、ブロックする処理は自分で I/O 用のスレッドへ移す。
 */
interface BrowserOpener {
    /** 2 つ以上の項目をまとめて開けるか。偽なら、まとめて開くときの設定の欄を出さない。 */
    val opensMultipleItems: Boolean

    /** この端末に入っているブラウザ。読めなければ空。 */
    suspend fun browsers(): List<Browser>

    /**
     * ブラウザを調べずに、既定のブラウザで [url] を開く。[browsers] に既定のブラウザが無いときの予備の開き方。
     * http と https の URL だけを開く。
     */
    suspend fun openInDefaultBrowser(url: String): BrowserOpenResult

    /**
     * [browser] で [tabs] を開く。[tabs] は 1 つ以上。
     * 1 つならその URL でブラウザを起動する。2 つ以上なら、[tabLoading] の読み込み方を添えたコマンドを拡張機能へ渡し、
     * 届いたかを確かめてから返す。
     */
    suspend fun openInBrowser(browser: Browser, tabs: List<BrowserTab>, tabLoading: TabLoading): BrowserOpenResult
}
