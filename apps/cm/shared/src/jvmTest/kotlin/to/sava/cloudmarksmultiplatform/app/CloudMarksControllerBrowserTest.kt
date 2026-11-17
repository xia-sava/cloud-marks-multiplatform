package to.sava.cloudmarksmultiplatform.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.browse.SelectionMode
import to.sava.cloudmarksmultiplatform.browser.BrowserOpenResult
import to.sava.cloudmarksmultiplatform.browser.BrowserTab
import to.sava.cloudmarksmultiplatform.browser.CHROME
import to.sava.cloudmarksmultiplatform.browser.FIREFOX
import to.sava.cloudmarksmultiplatform.browser.FakeBrowserOpener
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.GetFault
import to.sava.cloudmarksmultiplatform.settings.DeviceSettingsFile
import to.sava.cloudmarksmultiplatform.settings.TabLoading
import to.sava.cloudmarksmultiplatform.storage.Item
import to.sava.cloudmarksmultiplatform.sync.A_FILE_ID
import to.sava.cloudmarksmultiplatform.sync.A_ITEMS
import to.sava.cloudmarksmultiplatform.sync.FOLDER_A
import to.sava.cloudmarksmultiplatform.sync.FOLDER_B
import to.sava.cloudmarksmultiplatform.sync.ROOT_ITEMS
import to.sava.cloudmarksmultiplatform.sync.SyncFailure
import to.sava.cloudmarksmultiplatform.sync.fileId
import to.sava.cloudmarksmultiplatform.sync.item
import to.sava.cloudmarksmultiplatform.sync.replaceItemsAsOtherWriter
import to.sava.cloudmarksmultiplatform.sync.seed
import to.sava.cloudmarksmultiplatform.sync.storedManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

/** 既定と違うタブの読み込み方。 */
private val TAB_LOADING = TabLoading(loadAtOnce = 2, loadAhead = 8)

/** 起動の失敗の詳細。 */
private const val LAUNCH_DETAIL = "IOException: 起動できない"

/** [items] を開くときのタブ。 */
private fun tabsOf(items: List<Item>): List<BrowserTab> = items.map { BrowserTab(it.url, it.title) }

// runCurrent が ExperimentalCoroutinesApi であるため。
@OptIn(ExperimentalCoroutinesApi::class)
class CloudMarksControllerBrowserTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** マニフェストと保存済みの設定を置いて起動したフィクスチャ。 */
    private fun TestScope.started(
        browserOpener: FakeBrowserOpener = FakeBrowserOpener(),
        arrange: (ControllerFixture) -> Unit = {},
    ): ControllerFixture {
        val fixture = ControllerFixture(this, temporaryFolder, browserOpener = browserOpener)
        fixture.store.seed()
        fixture.storeSettings()
        arrange(fixture)
        fixture.controller.start()
        runCurrent()
        return fixture
    }

    // 起動すると PC に入っているブラウザを読み、メニューを開くたびに読み直す（拡張機能を入れたブラウザが、まとめて開けるようになる）
    @Test
    fun browsersAreReadAtStartAndRefreshed() = runTest {
        val fixture = started()
        assertEquals(listOf(CHROME, FIREFOX), fixture.state.browsers)

        val firefoxWithExtension = FIREFOX.copy(opensMultipleItems = true)
        fixture.browserOpener.browsers = listOf(CHROME, firefoxWithExtension)
        fixture.controller.refreshBrowsers()
        runCurrent()

        assertEquals(listOf(CHROME, firefoxWithExtension), fixture.state.browsers)
        assertEquals(2, fixture.browserOpener.browsersReads)
    }

    // まとめて開けるかは、渡した仕組みのとおりに状態に出す（設定の画面の欄を出すかを決める）
    @Test
    fun opensMultipleItemsFollowsOpener() = runTest {
        listOf(true, false).forEach { opensMultiple ->
            val fixture = ControllerFixture(this, temporaryFolder, browserOpener = FakeBrowserOpener(opensMultipleItems = opensMultiple))

            assertEquals(opensMultiple, fixture.state.opensMultipleItems)
        }
    }

    // 項目を開くと、ブラウザの一覧の既定のブラウザで、メニューでそのブラウザを選んだときと同じく 1 つのタブとして開く。
    // 選んでいる項目は変えない
    @Test
    fun openItemUsesDefaultBrowserInList() = runTest {
        val fixture = started()
        fixture.controller.selectItem(ROOT_ITEMS[1].id, SelectionMode.TOGGLE)

        fixture.controller.openItem(ROOT_ITEMS[0].id)
        runCurrent()

        assertEquals(listOf(FakeBrowserOpener.OpenRequest(CHROME, tabsOf(ROOT_ITEMS.take(1)), TabLoading())), fixture.browserOpener.opened)
        assertEquals(emptyList(), fixture.browserOpener.defaultOpened)
        assertEquals(setOf(ROOT_ITEMS[1].id), fixture.state.browse.selectedItemIds)
        assertNull(fixture.state.browserNotice)
    }

    // 一覧の既定のブラウザを起動できなければ、そのブラウザの名前と詳細を添えて知らせる
    @Test
    fun defaultBrowserInListFailureIsNoticedWithName() = runTest {
        val fixture = started()
        fixture.browserOpener.browserResult = BrowserOpenResult.LaunchFailed(LAUNCH_DETAIL)

        fixture.controller.openItem(ROOT_ITEMS[0].id)
        runCurrent()

        assertEquals(BrowserNotice.LaunchFailed(CHROME.name, LAUNCH_DETAIL), fixture.state.browserNotice)
    }

    // ブラウザの一覧に既定のブラウザが無ければ、Windows に既定のブラウザで開かせる
    @Test
    fun openItemFallsBackToWindowsDefault() = runTest {
        val fixture = started(browserOpener = FakeBrowserOpener(browsers = listOf(FIREFOX)))

        fixture.controller.openItem(ROOT_ITEMS[0].id)
        runCurrent()

        assertEquals(listOf(ROOT_ITEMS[0].url), fixture.browserOpener.defaultOpened)
        assertEquals(emptyList(), fixture.browserOpener.opened)
    }

    // Windows に開かせて開けなければ、詳細の無い知らせを出し、閉じると消える。次に開き始めたときも消す
    @Test
    fun windowsDefaultFailureIsNoticedWithoutDetail() = runTest {
        val fixture = started(browserOpener = FakeBrowserOpener(browsers = emptyList()))
        fixture.browserOpener.defaultResult = BrowserOpenResult.LaunchFailed(LAUNCH_DETAIL)

        fixture.controller.openItem(ROOT_ITEMS[0].id)
        runCurrent()
        assertEquals(BrowserNotice.DefaultBrowserLaunchFailed, fixture.state.browserNotice)

        fixture.controller.dismissBrowserNotice()
        assertNull(fixture.state.browserNotice)

        fixture.controller.openItem(ROOT_ITEMS[0].id)
        runCurrent()
        fixture.browserOpener.defaultResult = BrowserOpenResult.Opened
        fixture.controller.openItem(ROOT_ITEMS[1].id)
        assertNull(fixture.state.browserNotice)
    }

    // 選んでいる項目を、選んだ順でなく表示の並び順に、いまのタブの読み込み方を添えて、選んだブラウザで開く
    @Test
    fun selectedItemsAreOpenedInDisplayOrder() = runTest {
        val fixture = started()
        fixture.controller.setTabLoading(TAB_LOADING)
        fixture.controller.selectItem(ROOT_ITEMS[1].id, SelectionMode.TOGGLE)
        fixture.controller.selectItem(ROOT_ITEMS[0].id, SelectionMode.TOGGLE)

        fixture.controller.openSelectedItems(CHROME)
        runCurrent()

        assertEquals(listOf(FakeBrowserOpener.OpenRequest(CHROME, tabsOf(ROOT_ITEMS), TAB_LOADING)), fixture.browserOpener.opened)
    }

    // 2 つ以上の項目は、まとめて開けないブラウザでは開かない。1 つなら開く
    @Test
    fun multipleItemsAreNotOpenedInBrowserWithoutExtension() = runTest {
        val fixture = started()
        fixture.controller.selectItem(ROOT_ITEMS[0].id, SelectionMode.TOGGLE)
        fixture.controller.selectItem(ROOT_ITEMS[1].id, SelectionMode.TOGGLE)

        fixture.controller.openSelectedItems(FIREFOX)
        runCurrent()
        assertEquals(emptyList(), fixture.browserOpener.opened)

        fixture.controller.selectItem(ROOT_ITEMS[1].id, SelectionMode.TOGGLE)
        fixture.controller.openSelectedItems(FIREFOX)
        runCurrent()
        assertEquals(listOf(tabsOf(ROOT_ITEMS.take(1))), fixture.browserOpener.opened.map { it.tabs })
    }

    // 選んだブラウザを起動できなかったとき、まとめて開くコマンドが届かなかったかもしれないときは、ブラウザの名前を添えて知らせる
    @Test
    fun browserFailuresAreNoticedWithName() = runTest {
        val cases = listOf(
            BrowserOpenResult.LaunchFailed(LAUNCH_DETAIL) to BrowserNotice.LaunchFailed(CHROME.name, LAUNCH_DETAIL),
            BrowserOpenResult.CommandUndelivered to BrowserNotice.CommandUndelivered(CHROME.name),
            BrowserOpenResult.Opened to null,
        )
        cases.forEach { (result, notice) ->
            val fixture = started()
            fixture.browserOpener.browserResult = result
            fixture.controller.selectItem(ROOT_ITEMS[0].id, SelectionMode.TOGGLE)
            fixture.controller.selectItem(ROOT_ITEMS[1].id, SelectionMode.TOGGLE)

            fixture.controller.openSelectedItems(CHROME)
            runCurrent()

            assertEquals(notice, fixture.state.browserNotice, "$result")
        }
    }

    // フォルダの直下の項目を開く。まだ読んでいなければ、そのフォルダの項目ファイルだけを読む（マニフェストは GET しない）。
    // 開いているフォルダは替えない
    @Test
    fun folderItemsAreReadAndOpened() = runTest {
        val fixture = started()
        val manifestGets = fixture.store.requests.count { fixture.isManifest(it.key) }

        fixture.controller.openFolderItems(FOLDER_A, FIREFOX)
        runCurrent()

        assertEquals(listOf(FakeBrowserOpener.OpenRequest(FIREFOX, tabsOf(A_ITEMS), TabLoading())), fixture.browserOpener.opened)
        assertEquals(manifestGets, fixture.store.requests.count { fixture.isManifest(it.key) })
        assertEquals(fixture.store.storedManifest().folders.first().id, fixture.state.browse.currentFolderId)
    }

    // 子孫のフォルダの項目は含めず、直下に項目が無いフォルダは何もしない
    @Test
    fun folderWithoutDirectItemsOpensNothing() = runTest {
        val fixture = started()

        fixture.controller.openFolderItems(FOLDER_B, CHROME)
        fixture.controller.openFolderItems(fixture.store.storedManifest().folders.first().id, CHROME)
        runCurrent()

        assertEquals(listOf(tabsOf(ROOT_ITEMS)), fixture.browserOpener.opened.map { it.tabs })
    }

    // フォルダの項目ファイルが壊れていれば、壊れ方を添えて知らせ、開かない
    @Test
    fun brokenFolderIsNoticed() = runTest {
        val fixture = started()
        fixture.store.overwrite(fixture.itemsKey(FOLDER_A, A_FILE_ID), "not gzip".encodeToByteArray())

        fixture.controller.openFolderItems(FOLDER_A, CHROME)
        runCurrent()

        assertIs<BrowserNotice.FolderBroken>(fixture.state.browserNotice)
        assertEquals(emptyList(), fixture.browserOpener.opened)
    }

    // フォルダの項目を読めなければ、理由を知らせて開かない
    @Test
    fun folderReadFailureIsNoticed() = runTest {
        val fixture = started()
        fixture.store.onNextGet(GetFault.Forbidden, key = { it == fixture.itemsKey(FOLDER_A, A_FILE_ID) })

        fixture.controller.openFolderItems(FOLDER_A, CHROME)
        runCurrent()

        assertEquals(BrowserNotice.FolderReadFailed(SyncFailure.Forbidden), fixture.state.browserNotice)
        assertEquals(emptyList(), fixture.browserOpener.opened)
    }

    // フォルダの項目ファイルが 404 を返したら、マニフェストを読み直し、新しい項目ファイルの項目を開く
    @Test
    fun missingFolderFileRereadsManifestAndOpens() = runTest {
        val fixture = started()
        val newItems = listOf(item(7), item(8))
        fixture.store.replaceItemsAsOtherWriter(FOLDER_A, fileId(9), newItems)
        fixture.store.remove(fixture.itemsKey(FOLDER_A, A_FILE_ID))

        fixture.controller.openFolderItems(FOLDER_A, CHROME)
        runCurrent()

        assertEquals(listOf(tabsOf(newItems)), fixture.browserOpener.opened.map { it.tabs })
        assertEquals(fixture.store.storedManifest(), fixture.state.browse.tree?.manifest)
        assertFalse(fixture.state.browse.loading)
    }

    // タブの読み込み方を替えると、すぐ状態に入り、端末ごとの設定のファイルに書く。作り直したコントローラも同じ値で起動する。
    // 表示の大きさは保つ
    @Test
    fun tabLoadingIsKeptAcrossControllers() = runTest {
        val first = started()
        val scale = first.state.displayScale

        first.controller.setTabLoading(TAB_LOADING)
        assertEquals(TAB_LOADING, first.state.tabLoading)
        runCurrent()

        assertEquals(TAB_LOADING, DeviceSettingsFile(first.appFiles.deviceSettingsFile) {}.read().tabLoading)
        val recreated = ControllerFixture(this, temporaryFolder, directory = first.directory)
        recreated.controller.start()
        runCurrent()

        assertEquals(TAB_LOADING, recreated.state.tabLoading)
        assertEquals(scale, recreated.state.displayScale)
    }
}
