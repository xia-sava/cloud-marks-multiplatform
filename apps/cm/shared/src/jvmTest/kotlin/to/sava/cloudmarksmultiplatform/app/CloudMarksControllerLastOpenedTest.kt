package to.sava.cloudmarksmultiplatform.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.browse.SelectionMode
import to.sava.cloudmarksmultiplatform.browser.BrowserOpenResult
import to.sava.cloudmarksmultiplatform.browser.CHROME
import to.sava.cloudmarksmultiplatform.browser.FIREFOX
import to.sava.cloudmarksmultiplatform.browser.FakeBrowserOpener
import to.sava.cloudmarksmultiplatform.browser.FakeItemSharer
import to.sava.cloudmarksmultiplatform.browser.ItemShareResult
import to.sava.cloudmarksmultiplatform.sync.A_ITEMS
import to.sava.cloudmarksmultiplatform.sync.FOLDER_A
import to.sava.cloudmarksmultiplatform.sync.ROOT_ID
import to.sava.cloudmarksmultiplatform.sync.ROOT_ITEMS
import to.sava.cloudmarksmultiplatform.sync.seed
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 記録のファイルにある、最後に開いた項目の ID。無ければ null。 */
private fun ControllerFixture.recordedLastOpened(): String? = LastOpenedItemFile(appFiles.lastOpenedItemFile) {}.read()

/**
 * 項目を開く 1 つの場合。
 *
 * @property browserOpener フィクスチャに渡す、項目をブラウザで開く仕組み
 * @property fail 起動したフィクスチャで、このあとの [open] が開けないようにする。開ける場合では何もしない
 * @property open 起動したフィクスチャで、項目を開く
 */
private class OpenCase(
    val name: String,
    val browserOpener: () -> FakeBrowserOpener = { FakeBrowserOpener() },
    val fail: (ControllerFixture) -> Unit = {},
    val open: (ControllerFixture) -> Unit,
)

/** ルートフォルダの項目 [index] を選んでから、[open] で開く。 */
private fun selectingRootItem(index: Int, open: (CloudMarksController) -> Unit): (ControllerFixture) -> Unit = { fixture ->
    fixture.controller.selectItem(ROOT_ITEMS[index].id, SelectionMode.TOGGLE)
    open(fixture.controller)
}

// runCurrent が ExperimentalCoroutinesApi であるため。
@OptIn(ExperimentalCoroutinesApi::class)
class CloudMarksControllerLastOpenedTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** マニフェストと保存済みの設定を置き、[arrange] のあとに起動したフィクスチャ。 */
    private fun TestScope.started(
        browserOpener: FakeBrowserOpener = FakeBrowserOpener(),
        arrange: (ControllerFixture) -> Unit = {},
    ): ControllerFixture {
        val fixture = ControllerFixture(this, temporaryFolder, browserOpener = browserOpener, itemSharer = FakeItemSharer())
        fixture.store.seed()
        fixture.storeSettings()
        arrange(fixture)
        fixture.controller.start()
        runCurrent()
        return fixture
    }

    // 項目を 1 つだけ開けたら、その項目を最後に開いた項目として覚え、記録のファイルに書く。開き方は、一覧の既定のブラウザ、
    // ブラウザを調べずに開く既定のブラウザ、メニューで選んだブラウザ（選んだ 1 つの項目、直下の項目が 1 つのフォルダ）、ほかのアプリ
    @Test
    fun singleOpenedItemIsRemembered() = runTest {
        val cases = listOf(
            OpenCase("一覧の既定のブラウザ", open = { it.controller.openItem(ROOT_ITEMS[1].id) }) to ROOT_ITEMS[1].id,
            OpenCase(
                "ブラウザを調べずに開く既定のブラウザ",
                browserOpener = { FakeBrowserOpener(browsers = listOf(FIREFOX)) },
                open = { it.controller.openItem(ROOT_ITEMS[1].id) },
            ) to ROOT_ITEMS[1].id,
            OpenCase("選んだ 1 つの項目", open = selectingRootItem(1) { it.openSelectedItems(FIREFOX) }) to ROOT_ITEMS[1].id,
            OpenCase("直下の項目が 1 つのフォルダ", open = { it.controller.openFolderItems(FOLDER_A, CHROME) }) to A_ITEMS.single().id,
            OpenCase("ほかのアプリ", open = selectingRootItem(1) { it.openSelectedItemInOtherApp() }) to ROOT_ITEMS[1].id,
        )
        cases.forEach { (case, expected) ->
            val fixture = started(browserOpener = case.browserOpener())

            case.open(fixture)
            runCurrent()

            assertEquals(expected, fixture.state.lastOpenedItemId, case.name)
            assertEquals(expected, fixture.recordedLastOpened(), case.name)
        }
    }

    // 開けなければ覚えない。前に開いた項目を、状態にも記録のファイルにも残す。開けないのは、一覧の既定のブラウザと、
    // ブラウザを調べずに開く既定のブラウザを起動できないとき、ほかのアプリで開こうとして、受け取れるアプリが無いか、一覧を出せないとき
    @Test
    fun failedOpenKeepsPreviousItem() = runTest {
        val launchFailed = BrowserOpenResult.LaunchFailed(detail = null)
        val openSecondInOtherApp = selectingRootItem(1) { it.openSelectedItemInOtherApp() }
        val cases = listOf(
            OpenCase(
                "一覧の既定のブラウザ",
                fail = { it.browserOpener.browserResult = launchFailed },
                open = { it.controller.openItem(ROOT_ITEMS[1].id) },
            ),
            OpenCase(
                "ブラウザを調べずに開く既定のブラウザ",
                browserOpener = { FakeBrowserOpener(browsers = listOf(FIREFOX)) },
                fail = { it.browserOpener.defaultResult = launchFailed },
                open = { it.controller.openItem(ROOT_ITEMS[1].id) },
            ),
            OpenCase(
                "ほかのアプリで、受け取れるアプリが無い",
                fail = { it.sharer.result = ItemShareResult.NoApp },
                open = openSecondInOtherApp,
            ),
            OpenCase(
                "ほかのアプリで、一覧を出せない",
                fail = { it.sharer.result = ItemShareResult.Failed },
                open = openSecondInOtherApp,
            ),
        )
        cases.forEach { case ->
            val fixture = started(browserOpener = case.browserOpener())
            fixture.controller.openItem(ROOT_ITEMS[0].id)
            runCurrent()
            assertEquals(ROOT_ITEMS[0].id, fixture.state.lastOpenedItemId, case.name)
            case.fail(fixture)

            case.open(fixture)
            runCurrent()

            assertNotNull(fixture.state.browserNotice, case.name)
            assertEquals(ROOT_ITEMS[0].id, fixture.state.lastOpenedItemId, case.name)
            assertEquals(ROOT_ITEMS[0].id, fixture.recordedLastOpened(), case.name)
        }
    }

    // 2 つ以上をまとめて開いたとき（選んだ項目、フォルダの直下）と、共有したときは、最後に開いた項目を替えない。
    // まとめて開く項目と共有する項目には、前に開いた項目と違う項目を先頭に置く
    @Test
    fun multipleOpenAndShareKeepLastOpened() = runTest {
        val fixture = started()
        fixture.controller.openItem(ROOT_ITEMS[1].id)
        runCurrent()
        assertEquals(ROOT_ITEMS[1].id, fixture.state.lastOpenedItemId)

        ROOT_ITEMS.forEach { fixture.controller.selectItem(it.id, SelectionMode.TOGGLE) }
        fixture.controller.openSelectedItems(CHROME)
        fixture.controller.clearSelection()
        fixture.controller.openFolderItems(ROOT_ID, CHROME)
        fixture.controller.selectItem(ROOT_ITEMS[0].id, SelectionMode.TOGGLE)
        fixture.controller.shareSelectedItem()
        runCurrent()

        assertEquals(
            listOf(listOf(ROOT_ITEMS[1]), ROOT_ITEMS, ROOT_ITEMS).map { items -> items.map { it.url } },
            fixture.browserOpener.opened.map { request -> request.tabs.map { it.url } },
        )
        assertEquals(listOf(ROOT_ITEMS[0].url to ROOT_ITEMS[0].title), fixture.sharer.shared)
        assertEquals(ROOT_ITEMS[1].id, fixture.state.lastOpenedItemId)
        assertEquals(ROOT_ITEMS[1].id, fixture.recordedLastOpened())
    }

    // 最後に開いた項目は、作り直したコントローラも、起動すると記録のファイルから読む
    @Test
    fun lastOpenedIsKeptAcrossControllers() = runTest {
        val first = started()
        first.controller.openItem(ROOT_ITEMS[1].id)
        runCurrent()

        val recreated = ControllerFixture(this, temporaryFolder, directory = first.directory)
        assertNull(recreated.state.lastOpenedItemId)
        recreated.controller.start()
        runCurrent()

        assertEquals(ROOT_ITEMS[1].id, recreated.state.lastOpenedItemId)
    }

    // 記録が壊れていれば、覚えていないものとして起動し、ログに書く。画面には何も出さない
    @Test
    fun brokenRecordStartsWithoutLastOpened() = runTest {
        val fixture = started { fixture ->
            Files.createDirectories(fixture.appFiles.stateDirectory)
            Files.writeString(fixture.appFiles.lastOpenedItemFile, "not json")
        }

        assertNull(fixture.state.lastOpenedItemId)
        assertEquals(1, fixture.logs.count { it.startsWith("最後に開いた項目の記録が") })
        assertEquals(Screen.MAIN, fixture.state.screen)
        assertNull(fixture.state.browserNotice)
    }

    // 記録を書けなくても、ログに書くだけで、画面には何も出さない。最後に開いた項目は状態に入れる
    @Test
    fun writeFailureIsOnlyLogged() = runTest {
        val fixture = started { fixture -> Files.createDirectories(fixture.appFiles.lastOpenedItemFile.resolve("child")) }

        fixture.controller.openItem(ROOT_ITEMS[1].id)
        runCurrent()

        assertEquals(ROOT_ITEMS[1].id, fixture.state.lastOpenedItemId)
        assertEquals(1, fixture.logs.count { it.startsWith("最後に開いた項目の記録を書けなかった") })
        assertNull(fixture.state.browserNotice)
    }

    // 最後に開いた項目を別のフォルダへ移しても、消しても、最後に開いた項目と記録はそのまま
    @Test
    fun moveAndDeleteKeepLastOpened() = runTest {
        val fixture = started()
        val opened = ROOT_ITEMS[0].id
        fixture.controller.openItem(opened)
        runCurrent()

        fixture.controller.moveItems(ROOT_ID, listOf(opened), FOLDER_A)
        runCurrent()
        assertEquals(WriteOutcome.Written, fixture.state.write.outcome)
        fixture.controller.openFolder(FOLDER_A)
        runCurrent()
        fixture.controller.selectItem(opened, SelectionMode.SINGLE)
        fixture.controller.deleteSelectedItems()
        runCurrent()
        assertEquals(WriteOutcome.Written, fixture.state.write.outcome)

        assertEquals(opened, fixture.state.lastOpenedItemId)
        assertEquals(opened, fixture.recordedLastOpened())
    }
}
