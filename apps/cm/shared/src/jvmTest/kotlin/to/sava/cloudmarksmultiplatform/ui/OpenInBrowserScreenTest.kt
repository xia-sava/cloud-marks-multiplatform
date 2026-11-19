package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.app.ControllerFixture
import to.sava.cloudmarksmultiplatform.browser.Browser
import to.sava.cloudmarksmultiplatform.browser.BrowserOpenResult
import to.sava.cloudmarksmultiplatform.browser.BrowserTab
import to.sava.cloudmarksmultiplatform.browser.CHROME
import to.sava.cloudmarksmultiplatform.browser.FIREFOX
import to.sava.cloudmarksmultiplatform.browser.FakeBrowserOpener
import to.sava.cloudmarksmultiplatform.settings.DeviceSettingsFile
import to.sava.cloudmarksmultiplatform.settings.TabLoading
import to.sava.cloudmarksmultiplatform.storage.Item
import to.sava.cloudmarksmultiplatform.sync.A_FILE_ID
import to.sava.cloudmarksmultiplatform.sync.A_ITEMS
import to.sava.cloudmarksmultiplatform.sync.FOLDER_A
import to.sava.cloudmarksmultiplatform.sync.ROOT_ID
import to.sava.cloudmarksmultiplatform.sync.fileId
import to.sava.cloudmarksmultiplatform.sync.item
import to.sava.cloudmarksmultiplatform.sync.replaceItemsAsOtherWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/** 選び方を確かめるための、ルートフォルダの 5 つの項目。 */
private val FIVE_ITEMS = (1..5).map { item(it) }

/** ルートフォルダの項目を [FIVE_ITEMS] にする、起動の前の用意。 */
private val fiveRootItems: (ControllerFixture) -> Unit = { it.store.replaceItemsAsOtherWriter(ROOT_ID, fileId(30), FIVE_ITEMS) }

/** A の項目を 2 つにする、起動の前の用意。 */
private val twoItemsInA: (ControllerFixture) -> Unit = { it.store.replaceItemsAsOtherWriter(FOLDER_A, fileId(31), listOf(item(6), item(7))) }

/** 行の無いところへポインターを置く、画面の下端からの距離（画素）。 */
private const val BLANK_INSET = 40f

/** 起動の失敗の詳細。 */
private const val LAUNCH_DETAIL = "IOException: 起動できない"

/** 長押しになるまで押し続ける時間（ミリ秒）。長押しを決める時間（500 ミリ秒）より長い。 */
private const val LONG_PRESS_MILLIS = 1_000L

/** 行と行の境目の線（1 画素）を押すときの、上の行の下端からの距離（画素）。 */
private const val BORDER_INSET = 0.5f

/** [items] を開くときのタブ。 */
private fun tabsOf(items: List<Item>): List<BrowserTab> = items.map { BrowserTab(it.url, it.title) }

/** メニューの、ブラウザ [browser] で開く行。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.openInRow(browser: Browser): SemanticsNodeInteraction = onNodeWithText(openInBrowserLabel(browser.name))

/** メニューの、ブラウザ [browser] で開く行の文字の太さ。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.openInRowWeight(browser: Browser): FontWeight? {
    val results = mutableListOf<TextLayoutResult>()
    onNodeWithText(openInBrowserLabel(browser.name), useUnmergedTree = true)
        .fetchSemanticsNode()
        .config[SemanticsActions.GetTextLayoutResult]
        .action
        ?.invoke(results)
    return results.single().layoutInput.style.fontWeight
}

@OptIn(ExperimentalTestApi::class)
class OpenInBrowserScreenTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // 項目をマウスでクリックすると、ブラウザの一覧の既定のブラウザ（メニューでそのブラウザを選んだときと同じ経路）で開く。項目は選ばない
    @Test
    fun clickOpensItemInDefaultBrowser() = runCloudMarksScreenTest(temporaryFolder, arrange = fiveRootItems) { fixture ->
        itemRow(2).performMouseInput { click() }
        waitForIdle()

        assertEquals(listOf(FakeBrowserOpener.OpenRequest(CHROME, tabsOf(listOf(item(2))), TabLoading())), fixture.browserOpener.opened)
        assertEquals(emptyList(), fixture.browserOpener.defaultOpened)
        assertEquals(emptySet(), fixture.state.browse.selectedItemIds)
    }

    // 項目をダブルクリックしても、1 回だけ開く。ダブルクリックを決める時間がたってからクリックすれば、また開く
    @Test
    fun doubleClickOpensOnce() = runCloudMarksScreenTest(temporaryFolder, arrange = fiveRootItems) { fixture ->
        val openedOnce = listOf(CHROME.name to listOf(item(2).url))

        itemRow(2).performMouseInput { doubleClick() }
        waitForIdle()
        assertEquals(openedOnce, fixture.browserOpener.openedUrls)

        itemRow(2).performMouseInput {
            advanceEventTime(viewConfiguration.doubleTapTimeoutMillis * 2)
            click()
        }
        waitForIdle()

        assertEquals(openedOnce + openedOnce, fixture.browserOpener.openedUrls)
        assertEquals(emptySet(), fixture.state.browse.selectedItemIds)
    }

    // ブラウザの一覧に既定のブラウザが無ければ、ブラウザを調べずに既定のブラウザで開く
    @Test
    fun clickWithoutDefaultInListOpensWithoutCheckingBrowsers() =
        runCloudMarksScreenTest(temporaryFolder, browserOpener = FakeBrowserOpener(browsers = listOf(FIREFOX))) { fixture ->
            itemRow(1).performMouseInput { click() }
            waitForIdle()

            assertEquals(listOf(item(1).url), fixture.browserOpener.defaultOpened)
            assertEquals(emptyList(), fixture.browserOpener.opened)
        }

    // マウスで長く押しても、押しているあいだは開かず、動かさずに離したときに開く。項目は選ばない
    @Test
    fun longMousePressOpensOnRelease() = runCloudMarksScreenTest(temporaryFolder, arrange = fiveRootItems) { fixture ->
        itemRow(2).performMouseInput {
            moveTo(center)
            press()
        }
        mainClock.advanceTimeBy(LONG_PRESS_MILLIS)
        waitForIdle()
        assertEquals(emptyList(), fixture.browserOpener.openedUrls)

        itemRow(2).performMouseInput { release() }
        waitForIdle()

        assertEquals(listOf(CHROME.name to listOf(item(2).url)), fixture.browserOpener.openedUrls)
        assertEquals(emptySet(), fixture.state.browse.selectedItemIds)
    }

    // Ctrl を押しながらクリックすると、選ぶ項目を足し、選んでいる項目なら外す。開かない
    @Test
    fun ctrlClickTogglesSelection() = runCloudMarksScreenTest(temporaryFolder, arrange = fiveRootItems) { fixture ->
        clickItemRowWithKey(1, Key.CtrlLeft)
        clickItemRowWithKey(3, Key.CtrlLeft)
        assertEquals(setOf(item(1).id, item(3).id), fixture.state.browse.selectedItemIds)

        clickItemRowWithKey(1, Key.CtrlLeft)
        waitForIdle()

        assertEquals(setOf(item(3).id), fixture.state.browse.selectedItemIds)
        assertEquals(emptyList(), fixture.browserOpener.openedUrls)
    }

    // 項目の行と行の境目を Ctrl を押しながらクリックしても、選んでいる項目は外れない（行の無いところのクリックとしない）
    @Test
    fun ctrlClickOnRowBorderKeepsSelection() = runCloudMarksScreenTest(temporaryFolder, arrange = fiveRootItems) { fixture ->
        clickItemRowWithKey(1, Key.CtrlLeft)
        val bounds = itemRow(1).fetchSemanticsNode().boundsInRoot
        val border = Offset(bounds.center.x, bounds.bottom + BORDER_INSET)

        onRoot().performKeyInput { keyDown(Key.CtrlLeft) }
        onRoot().performMouseInput { click(border) }
        onRoot().performKeyInput { keyUp(Key.CtrlLeft) }
        waitForIdle()

        assertEquals(setOf(item(1).id), fixture.state.browse.selectedItemIds)
    }

    // Shift を押しながらクリックすると、起点（直前に Ctrl で足した項目）からの範囲を選ぶ。起点が無ければその項目だけを選ぶ
    @Test
    fun shiftClickSelectsRange() = runCloudMarksScreenTest(temporaryFolder, arrange = fiveRootItems) { fixture ->
        fun selected() = fixture.state.browse.selectedItemIds

        clickItemRowWithKey(2, Key.ShiftLeft)
        assertEquals(setOf(item(2).id), selected())

        clickItemRowWithKey(4, Key.ShiftLeft)
        assertEquals(setOf(2, 3, 4).map { item(it).id }.toSet(), selected())

        clickItemRowWithKey(5, Key.CtrlLeft)
        clickItemRowWithKey(3, Key.ShiftLeft)
        assertEquals(setOf(3, 4, 5).map { item(it).id }.toSet(), selected())
    }

    // 選んでいる最中も、ふつうのクリックは項目を開き、選んでいる項目は変えない
    @Test
    fun plainClickWhileSelectingOpensWithoutChangingSelection() = runCloudMarksScreenTest(temporaryFolder, arrange = fiveRootItems) { fixture ->
        clickItemRowWithKey(1, Key.CtrlLeft)
        clickItemRowWithKey(2, Key.CtrlLeft)

        itemRow(4).performMouseInput { click() }
        waitForIdle()

        assertEquals(listOf(CHROME.name to listOf(item(4).url)), fixture.browserOpener.openedUrls)
        assertEquals(setOf(item(1).id, item(2).id), fixture.state.browse.selectedItemIds)
    }

    // Esc キーを押すと、選ぶのをやめる
    @Test
    fun escapeClearsSelection() = runCloudMarksScreenTest(temporaryFolder, arrange = fiveRootItems) { fixture ->
        clickItemRowWithKey(1, Key.CtrlLeft)
        clickItemRowWithKey(2, Key.CtrlLeft)

        onRoot().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()

        assertEquals(emptySet(), fixture.state.browse.selectedItemIds)
    }

    // リストの行の無いところをクリックすると、選ぶのをやめる。何も開かない
    @Test
    fun blankClickClearsSelection() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        clickItemRowWithKey(1, Key.CtrlLeft)
        val blank = Offset(itemRow(1).spot(RowSpot.MIDDLE).x, onRoot().fetchSemanticsNode().size.height - BLANK_INSET)

        onRoot().performMouseInput { click(blank) }
        waitForIdle()

        assertEquals(emptySet(), fixture.state.browse.selectedItemIds)
        assertEquals(emptyList(), fixture.browserOpener.openedUrls)
        assertEquals(emptyList(), fixture.browserOpener.defaultOpened)
    }

    // リストのフォルダの行をクリックすると、そのフォルダを開く。ブラウザでは開かない
    @Test
    fun listFolderRowClickOpensFolder() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        listFolderRow("A").performMouseInput { click() }
        waitForIdle()

        assertEquals(FOLDER_A, fixture.state.browse.currentFolderId)
        assertEquals(emptyList(), fixture.browserOpener.openedUrls)
        assertEquals(emptyList(), fixture.browserOpener.defaultOpened)
    }

    // 項目の右クリックのメニューに、この端末に入っているブラウザで開く行を並べ、既定のブラウザの行を太字にする。
    // 1 つの項目なら、拡張機能の無いブラウザでも開ける
    @Test
    fun itemMenuListsBrowsersWithDefaultInBold() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        itemRow(1).performMouseInput { rightClick() }

        openInRow(CHROME).assertIsEnabled()
        openInRow(FIREFOX).assertIsEnabled()
        assertEquals(FontWeight.Bold, openInRowWeight(CHROME))
        assertNotEquals(FontWeight.Bold, openInRowWeight(FIREFOX))
        onNodeWithText(RENAME_ITEM_LABEL).assertIsDisplayed()

        openInRow(FIREFOX).performClick()
        waitForIdle()

        assertEquals(listOf(FakeBrowserOpener.OpenRequest(FIREFOX, tabsOf(listOf(item(1))), TabLoading())), fixture.browserOpener.opened)
    }

    // 2 つ以上の項目を選んだ上のメニューでは、拡張機能が入っていると分かっていないブラウザの行を押せなくし、マウスを載せると理由を出す。
    // ほかのブラウザでは、選んでいる項目をまとめて開く
    @Test
    fun multipleItemsNeedExtension() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        clickItemRowWithKey(1, Key.CtrlLeft)
        clickItemRowWithKey(2, Key.CtrlLeft)
        itemRow(1).performMouseInput { rightClick() }

        openInRow(CHROME).assertIsEnabled()
        openInRow(FIREFOX).assertIsNotEnabled()
        openInRow(FIREFOX).performMouseInput { moveTo(center) }
        waitForIdle()
        onNodeWithText(EXTENSION_REQUIRED_NOTICE).assertIsDisplayed()

        openInRow(CHROME).performClick()
        waitForIdle()

        assertEquals(listOf(tabsOf(listOf(item(1), item(2)))), fixture.browserOpener.opened.map { it.tabs })
    }

    // メニューを開くたびにブラウザを読み直すので、拡張機能を入れたブラウザは、アプリを起動し直さなくてもまとめて開ける
    @Test
    fun menuRereadsBrowsers() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        assertFalse(fixture.state.browsers.single { it.name == FIREFOX.name }.opensMultipleItems)
        fixture.browserOpener.browsers = listOf(CHROME, FIREFOX.copy(opensMultipleItems = true))
        clickItemRowWithKey(1, Key.CtrlLeft)
        clickItemRowWithKey(2, Key.CtrlLeft)

        itemRow(1).performMouseInput { rightClick() }
        waitForIdle()

        openInRow(FIREFOX).assertIsEnabled()
    }

    // ツリーのフォルダの右クリックのメニューにも、ブラウザで開く行を並べ、そのフォルダの直下の項目を開く
    @Test
    fun treeFolderMenuOpensDirectItems() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        treeRow("A").performMouseInput { rightClick() }

        onNodeWithText(NEW_FOLDER_LABEL).assertIsDisplayed()
        openInRow(FIREFOX).assertIsEnabled()
        openInRow(CHROME).performClick()
        waitForIdle()

        assertEquals(listOf(FakeBrowserOpener.OpenRequest(CHROME, tabsOf(A_ITEMS), TabLoading())), fixture.browserOpener.opened)
    }

    // リストのフォルダの行の右クリックのメニューにも、ブラウザで開く行と、フォルダのメニューを並べる
    @Test
    fun listFolderMenuOpensDirectItems() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        listFolderRow("A").performMouseInput { rightClick() }

        listOf(NEW_FOLDER_LABEL, RENAME_FOLDER_LABEL, MOVE_LABEL, DELETE_LABEL).forEach { onNodeWithText(it).assertIsDisplayed() }
        openInRow(CHROME).performClick()
        waitForIdle()

        assertEquals(listOf(tabsOf(A_ITEMS)), fixture.browserOpener.opened.map { it.tabs })
    }

    // フォルダのメニューの行は、直下の項目の数で決める。2 つ以上なら拡張機能の無いブラウザの行を押せず、無ければどの行も押せない
    @Test
    fun folderMenuRowsFollowDirectItemCount() {
        runCloudMarksScreenTest(temporaryFolder, arrange = twoItemsInA) {
            treeRow("A").performMouseInput { rightClick() }

            openInRow(CHROME).assertIsEnabled()
            openInRow(FIREFOX).assertIsNotEnabled()
        }
        runCloudMarksScreenTest(temporaryFolder) {
            treeRow("B").performMouseInput { rightClick() }

            openInRow(CHROME).assertIsNotEnabled()
            openInRow(FIREFOX).assertIsNotEnabled()
        }
    }

    // ブラウザの一覧に既定のブラウザが無く、ブラウザを調べずに既定のブラウザで開こうとして開けなければ、詳細の行の無い帯を出し、
    // 「閉じる」で消す
    @Test
    fun defaultWithoutCheckingBrowsersFailureShowsBannerWithoutDetail() =
        runCloudMarksScreenTest(temporaryFolder, browserOpener = FakeBrowserOpener(browsers = listOf(FIREFOX))) { fixture ->
            fixture.browserOpener.defaultResult = BrowserOpenResult.LaunchFailed(LAUNCH_DETAIL)

            itemRow(1).performMouseInput { click() }
            waitForIdle()

            onNodeWithText(DEFAULT_BROWSER_LAUNCH_FAILED).assertIsDisplayed()
            onAllNodesWithText(DETAIL_LABEL, substring = true).assertCountEquals(0)
            onNodeWithText(CLOSE_LABEL).performClick()
            onNodeWithText(DEFAULT_BROWSER_LAUNCH_FAILED).assertDoesNotExist()
        }

    // 一覧の既定のブラウザを起動できなければ、そのブラウザの名前と詳細を入れた帯を出す
    @Test
    fun defaultBrowserInListFailureShowsBannerWithName() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        fixture.browserOpener.browserResult = BrowserOpenResult.LaunchFailed(LAUNCH_DETAIL)

        itemRow(1).performMouseInput { click() }
        waitForIdle()

        onNodeWithText(browserLaunchFailed(CHROME.name)).assertIsDisplayed()
        onNodeWithText(DETAIL_LABEL + LAUNCH_DETAIL).assertIsDisplayed()
    }

    // フォルダのメニューから開こうとして、そのフォルダの項目ファイルが壊れていれば、ブラウザで開けないことを、壊れ方の詳細を添えて伝える
    @Test
    fun brokenFolderFromMenuShowsBanner() = runCloudMarksScreenTest(
        temporaryFolder,
        arrange = { it.store.overwrite(it.itemsKey(FOLDER_A, A_FILE_ID), "not gzip".encodeToByteArray()) },
    ) { fixture ->
        treeRow("A").performMouseInput { rightClick() }

        openInRow(CHROME).performClick()
        waitForIdle()

        onNodeWithText(FOLDER_BROKEN_FOR_BROWSER_NOTICE).assertIsDisplayed()
        onAllNodesWithText(DETAIL_LABEL, substring = true).assertCountEquals(1)
        assertEquals(emptyList(), fixture.browserOpener.opened)
    }

    // 選んだブラウザを起動できなかったとき、まとめて開くコマンドが届かなかったかもしれないときは、ブラウザの名前を入れた帯を出す
    @Test
    fun chosenBrowserFailuresShowBanners() {
        val cases = listOf(
            BrowserOpenResult.LaunchFailed(LAUNCH_DETAIL) to browserLaunchFailed(CHROME.name),
            BrowserOpenResult.CommandUndelivered to commandUndeliveredNotice(CHROME.name),
        )
        cases.forEach { (result, banner) ->
            runCloudMarksScreenTest(temporaryFolder) { fixture ->
                fixture.browserOpener.browserResult = result
                treeRow("A").performMouseInput { rightClick() }

                openInRow(CHROME).performClick()
                waitForIdle()

                onNodeWithText(banner).assertIsDisplayed()
            }
        }
    }

    // 設定の画面の「ブラウザで開くとき」の欄は、正しい整数を入れるとすぐに使い、端末ごとの設定のファイルに残す。
    // 同時に読み込むタブの数は 1 以上、先に読み込むタブの数は 0 以上。それ未満や数でないものを入れると、入れ直すよう添え、前の値を使い続ける
    @Test
    fun tabCountFieldsAreValidatedAndStored() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        onNodeWithContentDescription(SETTINGS_DESCRIPTION).performClick()
        onNodeWithText(OPEN_IN_BROWSER_SECTION_TITLE).performScrollTo().assertIsDisplayed()
        val cases = listOf(
            listOf(LOAD_AT_ONCE_LABEL, "0", "5", TAB_COUNT_INVALID_NOTICE),
            listOf(LOAD_AT_ONCE_LABEL, "多め", "5", TAB_COUNT_INVALID_NOTICE),
            listOf(LOAD_AHEAD_LABEL, "-1", "30", LOAD_AHEAD_INVALID_NOTICE),
            listOf(LOAD_AHEAD_LABEL, "多め", "30", LOAD_AHEAD_INVALID_NOTICE),
        )

        cases.forEach { (label, invalid, valid, notice) ->
            val before = fixture.state.tabLoading
            settingsField(label).performScrollTo().performTextReplacement(invalid)
            waitForIdle()
            onNodeWithText(notice).assertExists()
            assertEquals(before, fixture.state.tabLoading, "$label $invalid")

            settingsField(label).performTextReplacement(valid)
            waitForIdle()
            onNodeWithText(notice).assertDoesNotExist()
        }

        val expected = TabLoading(loadAtOnce = 5, loadAhead = 30)
        assertEquals(expected, fixture.state.tabLoading)
        assertEquals(expected, DeviceSettingsFile(fixture.appFiles.deviceSettingsFile) {}.read().tabLoading)
    }

    // 先に読み込むタブの数の欄は、0 を受け付け、端末ごとの設定のファイルに残す
    @Test
    fun loadAheadFieldAcceptsZero() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        onNodeWithContentDescription(SETTINGS_DESCRIPTION).performClick()

        settingsField(LOAD_AHEAD_LABEL).performScrollTo().performTextReplacement("0")
        waitForIdle()

        onNodeWithText(LOAD_AHEAD_INVALID_NOTICE).assertDoesNotExist()
        assertEquals(0, fixture.state.tabLoading.loadAhead)
        assertEquals(0, DeviceSettingsFile(fixture.appFiles.deviceSettingsFile) {}.read().tabLoading.loadAhead)
    }

    // 先に読み込むタブの数の欄の下には、入力が正しいあいだは説明を出し、正しくないあいだは説明の代わりに誤りの文を出す。
    // 同時に読み込むタブの数の欄には、説明を出さない
    @Test
    fun loadAheadHintIsReplacedByInvalidNotice() = runCloudMarksScreenTest(temporaryFolder) {
        onNodeWithContentDescription(SETTINGS_DESCRIPTION).performClick()
        onNodeWithText(OPEN_IN_BROWSER_SECTION_TITLE).performScrollTo().assertIsDisplayed()

        onNodeWithText(LOAD_AHEAD_HINT).performScrollTo().assertIsDisplayed()
        onNodeWithText(LOAD_AHEAD_INVALID_NOTICE).assertDoesNotExist()

        settingsField(LOAD_AHEAD_LABEL).performTextReplacement("-1")
        waitForIdle()
        onNodeWithText(LOAD_AHEAD_INVALID_NOTICE).assertExists()
        onNodeWithText(LOAD_AHEAD_HINT).assertDoesNotExist()

        settingsField(LOAD_AHEAD_LABEL).performTextReplacement("0")
        waitForIdle()
        onNodeWithText(LOAD_AHEAD_HINT).assertExists()
        onNodeWithText(LOAD_AHEAD_INVALID_NOTICE).assertDoesNotExist()

        settingsField(LOAD_AT_ONCE_LABEL).performScrollTo().performTextReplacement("0")
        waitForIdle()
        onNodeWithText(TAB_COUNT_INVALID_NOTICE).assertExists()
        onNodeWithText(LOAD_AHEAD_HINT).assertExists()
    }

    // まとめて開けないプラットフォームでは、設定の画面に「ブラウザで開くとき」の欄を出さない
    @Test
    fun tabCountFieldsAreHiddenWithoutMultipleOpening() =
        runCloudMarksScreenTest(temporaryFolder, browserOpener = FakeBrowserOpener(opensMultipleItems = false)) {
            onNodeWithContentDescription(SETTINGS_DESCRIPTION).performClick()
            waitForIdle()

            onNodeWithText(DISPLAY_SCALE_LABEL).assertExists()
            onNodeWithText(OPEN_IN_BROWSER_SECTION_TITLE).assertDoesNotExist()
            onNodeWithText(LOAD_AT_ONCE_LABEL).assertDoesNotExist()
        }
}
