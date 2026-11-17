package to.sava.cloudmarksmultiplatform.browser

import to.sava.cloudmarksmultiplatform.settings.TabLoading

/** 既定のブラウザで、拡張機能が入っているブラウザ。 */
internal val CHROME = Browser(name = "Google Chrome", executable = "C:\\chrome\\chrome.exe", isDefault = true, opensMultipleItems = true)

/** 既定でなく、拡張機能が入っていると分かっていないブラウザ。 */
internal val FIREFOX =
    Browser(name = "Mozilla Firefox", executable = "C:\\firefox\\firefox.exe", isDefault = false, opensMultipleItems = false)

/**
 * 呼ばれたことを記録し、決めた結果を返す [BrowserOpener]。ブラウザを起動しない。
 *
 * @property browsers [BrowserOpener.browsers] が返すブラウザ。替えると、次に読み直したときから返す
 */
internal class FakeBrowserOpener(
    var browsers: List<Browser> = listOf(CHROME, FIREFOX),
    override val opensMultipleItems: Boolean = true,
) : BrowserOpener {
    /** 選んだブラウザで開くように頼まれたこと。 */
    data class OpenRequest(val browser: Browser, val tabs: List<BrowserTab>, val tabLoading: TabLoading)

    /** 既定のブラウザで開くように頼まれた URL。頼まれた順。 */
    val defaultOpened = mutableListOf<String>()

    /** 選んだブラウザで開くように頼まれたこと。頼まれた順。 */
    val opened = mutableListOf<OpenRequest>()

    /** 選んだブラウザで開くように頼まれた、ブラウザの名前とタブの URL の並び。頼まれた順。 */
    val openedUrls: List<Pair<String, List<String>>>
        get() = opened.map { request -> request.browser.name to request.tabs.map { it.url } }

    /** [browsers] を読んだ回数。 */
    var browsersReads = 0
        private set

    /** 既定のブラウザで開いたときに返す結果。 */
    var defaultResult: BrowserOpenResult = BrowserOpenResult.Opened

    /** 選んだブラウザで開いたときに返す結果。 */
    var browserResult: BrowserOpenResult = BrowserOpenResult.Opened

    override suspend fun browsers(): List<Browser> = browsers.also { browsersReads++ }

    override suspend fun openInDefaultBrowser(url: String): BrowserOpenResult = defaultResult.also { defaultOpened += url }

    override suspend fun openInBrowser(browser: Browser, tabs: List<BrowserTab>, tabLoading: TabLoading): BrowserOpenResult =
        browserResult.also { opened += OpenRequest(browser, tabs, tabLoading) }
}
