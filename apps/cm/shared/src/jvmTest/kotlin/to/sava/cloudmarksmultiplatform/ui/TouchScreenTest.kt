package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.app.ControllerFixture
import to.sava.cloudmarksmultiplatform.browser.CHROME
import to.sava.cloudmarksmultiplatform.browser.FakeItemSharer
import to.sava.cloudmarksmultiplatform.browser.ItemShareResult
import to.sava.cloudmarksmultiplatform.sync.FOLDER_A
import to.sava.cloudmarksmultiplatform.sync.FOLDER_B
import to.sava.cloudmarksmultiplatform.sync.ROOT_ID
import to.sava.cloudmarksmultiplatform.sync.ROOT_ITEMS
import to.sava.cloudmarksmultiplatform.sync.initialManifest
import to.sava.cloudmarksmultiplatform.sync.item
import to.sava.cloudmarksmultiplatform.sync.seed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 選ぶ状態でないときの AppBar のボタンの説明。 */
private val NORMAL_TOP_BAR_BUTTONS = listOf(TREE_PANE_DESCRIPTION, BACK_DESCRIPTION, RELOAD_DESCRIPTION, SETTINGS_DESCRIPTION)

/** 読むだけのツリーにするための、マニフェストの形式のバージョン（対応しているバージョンより新しい）。 */
private const val NEWER_VERSION = 2

/** タイトルを変更するテストで入れる、新しいタイトル。 */
private const val RENAMED_TITLE = "renamed title"

/** フォルダのメニューの行（ルートフォルダでないフォルダ）。 */
private val FOLDER_MENU_LABELS = listOf(NEW_FOLDER_LABEL, RENAME_FOLDER_LABEL, MOVE_LABEL, DELETE_LABEL)

/** 選ぶ状態でないときに項目の行を長押しして出す、項目のメニューの行（項目をほかのアプリで開く仕組みを持つとき）。上からこの順に並ぶ。 */
private val ITEM_LONG_PRESS_MENU_LABELS =
    listOf(OPEN_LABEL, OPEN_IN_OTHER_APP_LABEL, SHARE_LABEL, RENAME_ITEM_LABEL, MOVE_LABEL, DELETE_LABEL, SELECT_LABEL)

/** 項目をほかのアプリで開く仕組みを持たないときの、項目のメニューの行。上からこの順に並ぶ。 */
private val ITEM_LONG_PRESS_MENU_LABELS_WITHOUT_SHARER = listOf(OPEN_LABEL, RENAME_ITEM_LABEL, MOVE_LABEL, DELETE_LABEL, SELECT_LABEL)

/** 項目のメニューの、データを変える行。書けないあいだは押せない。 */
private val ITEM_WRITE_MENU_LABELS = listOf(RENAME_ITEM_LABEL, MOVE_LABEL, DELETE_LABEL)

/** リストの項目 [n]（[item]）の行を、タッチで長押しする。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.longPressItemRow(n: Int) {
    itemRow(n).performTouchInput { longClick() }
    waitForIdle()
}

/** リストの項目 [n] の行を長押しして項目のメニューを出し、「選択」を押して、その項目だけを選んだ選ぶ状態に入る。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.selectItemRow(n: Int) {
    longPressItemRow(n)
    tapText(SELECT_LABEL)
}

/** メニューの行 [labels] が、どれも出ていて、上からこの順に並んでいるか。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.assertMenuRowsInOrder(labels: List<String>) {
    val tops = labels.map { label -> onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top }
    assertEquals(tops.sorted(), tops)
    assertEquals(labels.size, tops.toSet().size)
}

/** ダイアログの中の、文が [text] の要素。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.inDialog(text: String): SemanticsNodeInteraction = onNode(hasText(text) and hasAnyAncestor(isDialog()))

/** リストの項目 [n]（[item]）の行を、タッチで押す。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.tapItemRow(n: Int) {
    itemRow(n).performTouchInput { click() }
    waitForIdle()
}

/** 項目を選んでいるあいだの AppBar の題と、ボタンが、[count] 件を選んでいるときのものか。[moreOptions] は、その他のオプションのボタンを出すか。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.assertSelectionTopBar(count: Int, moreOptions: Boolean) {
    onNodeWithText(selectionTitle(count)).assertIsDisplayed()
    listOf(CLEAR_SELECTION_DESCRIPTION, DELETE_LABEL, MOVE_LABEL).forEach { onNodeWithContentDescription(it).assertIsDisplayed() }
    if (moreOptions) {
        onNodeWithContentDescription(MORE_OPTIONS_DESCRIPTION).assertIsDisplayed()
    } else {
        onNodeWithContentDescription(MORE_OPTIONS_DESCRIPTION).assertDoesNotExist()
    }
    NORMAL_TOP_BAR_BUTTONS.forEach { onNodeWithContentDescription(it).assertDoesNotExist() }
}

/** 選ぶ状態でないときの AppBar か。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.assertNormalTopBar() {
    NORMAL_TOP_BAR_BUTTONS.forEach { onNodeWithContentDescription(it).assertIsDisplayed() }
    onNodeWithContentDescription(CLEAR_SELECTION_DESCRIPTION).assertDoesNotExist()
}

@OptIn(ExperimentalTestApi::class)
class TouchScreenTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** タッチの画面の振る舞いで、項目をほかのアプリで開く仕組みと共有する仕組みを持つ画面で、[block] を走らせる。 */
    private fun runTouchScreenTest(block: suspend ComposeUiTest.(ControllerFixture) -> Unit) =
        runCloudMarksScreenTest(temporaryFolder, itemSharer = FakeItemSharer(), touchScreen = true, block = block)

    // 選ぶ状態でないときに項目を押すと、既定のブラウザで開く。選ぶ状態には入らない
    @Test
    fun tapOpensItemInDefaultBrowser() = runTouchScreenTest { fixture ->
        tapItemRow(1)

        assertEquals(listOf(CHROME.name to listOf(item(1).url)), fixture.browserOpener.openedUrls)
        assertEquals(emptySet(), fixture.state.browse.selectedItemIds)
        assertNormalTopBar()
    }

    // 項目をダブルタップしても、1 回だけ開く。ダブルタップを決める時間がたってから押せば、また開く
    @Test
    fun doubleTapOpensOnce() = runTouchScreenTest { fixture ->
        val openedOnce = listOf(CHROME.name to listOf(item(2).url))

        itemRow(2).performTouchInput { doubleClick() }
        waitForIdle()
        assertEquals(openedOnce, fixture.browserOpener.openedUrls)

        itemRow(2).performTouchInput {
            advanceEventTime(viewConfiguration.doubleTapTimeoutMillis * 2)
            click()
        }
        waitForIdle()

        assertEquals(openedOnce + openedOnce, fixture.browserOpener.openedUrls)
        assertEquals(emptySet(), fixture.state.browse.selectedItemIds)
    }

    // 選ぶ状態でないときに項目を長押しすると、選ぶ状態に入らずに、項目のメニューを出す。行は上から、開く・アプリを選んで開く・共有・
    // タイトルを変更・移動・削除・選択の順で、どれも押せる。項目は開かない
    @Test
    fun longPressShowsItemMenu() = runTouchScreenTest { fixture ->
        longPressItemRow(1)

        assertMenuRowsInOrder(ITEM_LONG_PRESS_MENU_LABELS)
        ITEM_LONG_PRESS_MENU_LABELS.forEach { onNodeWithText(it).assertIsEnabled() }
        assertEquals(emptySet(), fixture.state.browse.selectedItemIds)
        assertNormalTopBar()
        assertEquals(emptyList(), fixture.browserOpener.openedUrls)
    }

    // 項目のメニューは、長押しした位置の横に出す。行の右寄りを長押しすれば、左寄りを長押ししたときより右に出す
    @Test
    fun itemMenuIsShownAtPressedPosition() {
        val lefts = listOf(0.1f, 0.6f).map { fraction ->
            var left = 0f
            runTouchScreenTest {
                itemRow(1).performTouchInput { longClick(percentOffset(fraction, 0.5f)) }
                waitForIdle()
                left = onNodeWithText(SELECT_LABEL).fetchSemanticsNode().boundsInRoot.left
            }
            left
        }

        assertTrue(lefts[0] < lefts[1], "$lefts")
    }

    // 項目をほかのアプリで開く仕組みを持たなければ、項目のメニューに、ほかのアプリで開く行と共有の行を出さない。開く行は出す
    @Test
    fun itemMenuWithoutSharerHasNoShareRows() = runCloudMarksScreenTest(temporaryFolder, touchScreen = true) {
        longPressItemRow(1)

        assertMenuRowsInOrder(ITEM_LONG_PRESS_MENU_LABELS_WITHOUT_SHARER)
        onNodeWithText(OPEN_IN_OTHER_APP_LABEL).assertDoesNotExist()
        onNodeWithText(SHARE_LABEL).assertDoesNotExist()
    }

    // 項目のメニューの「開く」は、選ぶ状態でないときに押したときと同じに、長押しした項目を既定のブラウザで開いて最後に開いた項目として覚える。
    // メニューは閉じ、選ぶ状態には入らない
    @Test
    fun itemMenuOpenRowOpensItemInDefaultBrowser() = runTouchScreenTest { fixture ->
        longPressItemRow(2)

        tapText(OPEN_LABEL)

        assertEquals(listOf(CHROME.name to listOf(item(2).url)), fixture.browserOpener.openedUrls)
        assertEquals(item(2).id, fixture.state.lastOpenedItemId)
        assertEquals(emptySet(), fixture.state.browse.selectedItemIds)
        onNodeWithText(SELECT_LABEL).assertDoesNotExist()
        assertNormalTopBar()
    }

    // 項目をほかのアプリで開く仕組みを持たなくても、項目のメニューの「開く」は項目を開き、最後に開いた項目として覚える
    @Test
    fun itemMenuOpenRowWorksWithoutSharer() = runCloudMarksScreenTest(temporaryFolder, touchScreen = true) { fixture ->
        longPressItemRow(1)

        tapText(OPEN_LABEL)

        assertEquals(listOf(CHROME.name to listOf(item(1).url)), fixture.browserOpener.openedUrls)
        assertEquals(item(1).id, fixture.state.lastOpenedItemId)
    }

    // 書けないあいだ（読むだけのツリー）は、項目のメニューのタイトルを変更・移動・削除を押せない。開く・アプリを選んで開く・共有・選択は押せる
    @Test
    fun itemMenuWriteRowsAreDisabledWhenNotWritable() = runCloudMarksScreenTest(
        temporaryFolder,
        arrange = { it.store.seed(initialManifest(version = NEWER_VERSION)) },
        itemSharer = FakeItemSharer(),
        touchScreen = true,
    ) { fixture ->
        assertFalse(fixture.state.canWrite)
        longPressItemRow(1)

        ITEM_WRITE_MENU_LABELS.forEach { onNodeWithText(it).assertIsNotEnabled() }
        listOf(OPEN_LABEL, OPEN_IN_OTHER_APP_LABEL, SHARE_LABEL, SELECT_LABEL).forEach { onNodeWithText(it).assertIsEnabled() }
    }

    // 項目のメニューの「選択」は、長押しした項目だけを選んで選ぶ状態に入り、AppBar を、選ぶのをやめるボタン・選んでいる数の題・削除・移動・
    // その他のオプションに替える。ハンバーガー・戻る・再読み込み・設定のボタンは出さない。メニューは閉じる
    @Test
    fun selectRowEntersSelection() = runTouchScreenTest { fixture ->
        longPressItemRow(2)

        tapText(SELECT_LABEL)

        assertEquals(setOf(item(2).id), fixture.state.browse.selectedItemIds)
        assertSelectionTopBar(count = 1, moreOptions = true)
        onNodeWithText(SELECT_LABEL).assertDoesNotExist()
        assertEquals(emptyList(), fixture.browserOpener.openedUrls)
    }

    // 項目のメニューの「ほかのアプリで開く」は、長押しした項目の URL をほかのアプリで開く。「共有」は、URL とタイトルを共有する。
    // どちらもメニューを閉じ、選ぶ状態には入らない
    @Test
    fun itemMenuOpensInOtherAppAndShares() = runTouchScreenTest { fixture ->
        val sharer = checkNotNull(fixture.itemSharer)
        longPressItemRow(2)

        tapText(OPEN_IN_OTHER_APP_LABEL)

        assertEquals(listOf(item(2).url), sharer.openedUrls)
        assertEquals(emptyList(), sharer.shared)
        onNodeWithText(SELECT_LABEL).assertDoesNotExist()

        longPressItemRow(1)
        tapText(SHARE_LABEL)

        assertEquals(listOf(item(1).url to item(1).title), sharer.shared)
        assertEquals(emptySet(), fixture.state.browse.selectedItemIds)
        assertNormalTopBar()
    }

    // 項目のメニューの「タイトルを変更」は、長押しした項目のタイトルを入れるダイアログを開き、変更を確定するとその項目のタイトルを書く
    @Test
    fun itemMenuRenamesLongPressedItem() = runTouchScreenTest { fixture ->
        longPressItemRow(2)

        tapText(RENAME_ITEM_LABEL)

        onNode(hasSetTextAction()).assert(hasText(item(2).title))
        onNode(hasSetTextAction()).performTextClearance()
        onNode(hasSetTextAction()).performTextInput(RENAMED_TITLE)
        onNodeWithText(CHANGE_LABEL).performClick()
        waitForIdle()

        assertEquals(listOf(ROOT_ITEMS[0], ROOT_ITEMS[1].copy(title = RENAMED_TITLE)), fixture.storedItems(ROOT_ID))
        assertEquals(emptySet(), fixture.state.browse.selectedItemIds)
    }

    // 項目のメニューの「移動」は、長押しした項目の移動先を選ぶダイアログを開き、移動先を選んで確定すると、その項目だけを移す
    @Test
    fun itemMenuMovesLongPressedItem() = runTouchScreenTest { fixture ->
        longPressItemRow(1)

        tapText(MOVE_LABEL)
        inDialog(MOVE_DESTINATION_TITLE).assertIsDisplayed()
        inDialog("B").performClick()
        inDialog(MOVE_LABEL).performClick()
        waitForIdle()

        assertEquals(listOf(ROOT_ITEMS[1]), fixture.storedItems(ROOT_ID))
        assertEquals(listOf(ROOT_ITEMS[0]), fixture.storedItems(FOLDER_B))
    }

    // 項目のメニューの「削除」は、長押しした項目だけを消し、元に戻すお知らせを出す。「元に戻す」を押すと元の位置へ戻す
    @Test
    fun itemMenuDeletesLongPressedItemWithUndo() = runTouchScreenTest { fixture ->
        longPressItemRow(1)

        tapText(DELETE_LABEL)

        assertEquals(listOf(ROOT_ITEMS[1]), fixture.storedItems(ROOT_ID))
        assertEquals(emptySet(), fixture.state.browse.selectedItemIds)
        onNodeWithText(deletedItemsNotice(1)).assertIsDisplayed()

        onNodeWithText(UNDO_LABEL).performClick()
        waitForIdle()

        assertEquals(ROOT_ITEMS, fixture.storedItems(ROOT_ID))
    }

    // 選ぶ状態で項目を長押ししても、メニューは出さずに、選ぶ・外すを切り替える
    @Test
    fun longPressWhileSelectingToggles() = runTouchScreenTest { fixture ->
        selectItemRow(1)

        longPressItemRow(2)
        assertEquals(setOf(item(1).id, item(2).id), fixture.state.browse.selectedItemIds)
        onNodeWithText(SELECT_LABEL).assertDoesNotExist()

        longPressItemRow(1)
        assertEquals(setOf(item(2).id), fixture.state.browse.selectedItemIds)
        onNodeWithText(SELECT_LABEL).assertDoesNotExist()
        assertSelectionTopBar(count = 1, moreOptions = true)
    }

    // 選ぶ状態で項目を押すと、選ぶ・外すを切り替え、題の数も替わる。2 つ以上を選んでいるあいだは、その他のオプションのボタンを出さない
    @Test
    fun tapWhileSelectingToggles() = runTouchScreenTest { fixture ->
        selectItemRow(1)

        tapItemRow(2)
        assertEquals(setOf(item(1).id, item(2).id), fixture.state.browse.selectedItemIds)
        assertSelectionTopBar(count = 2, moreOptions = false)

        tapItemRow(1)
        assertEquals(setOf(item(2).id), fixture.state.browse.selectedItemIds)
        assertSelectionTopBar(count = 1, moreOptions = true)
        assertEquals(emptyList(), fixture.browserOpener.openedUrls)
    }

    // 選ぶのをやめるボタンを押すと、選んでいる項目を外し、AppBar を元に戻す
    @Test
    fun clearSelectionButtonRestoresTopBar() = runTouchScreenTest { fixture ->
        selectItemRow(1)

        onNodeWithContentDescription(CLEAR_SELECTION_DESCRIPTION).performClick()
        waitForIdle()

        assertEquals(emptySet(), fixture.state.browse.selectedItemIds)
        assertNormalTopBar()
        onNodeWithText(selectionTitle(1)).assertDoesNotExist()
    }

    // 選ぶ状態の AppBar の削除のボタンは、選んでいる項目を消す。移動のボタンは、移動先を選ぶダイアログを開く
    @Test
    fun selectionTopBarDeletesAndMoves() = runTouchScreenTest { fixture ->
        selectItemRow(1)
        onNodeWithContentDescription(MOVE_LABEL).performClick()
        onNodeWithText(MOVE_DESTINATION_TITLE).assertIsDisplayed()
        onNodeWithText(CANCEL_LABEL).performClick()
        waitForIdle()

        onNodeWithContentDescription(DELETE_LABEL).performClick()
        waitForIdle()

        assertEquals(listOf(ROOT_ITEMS[1]), fixture.storedItems(ROOT_ID))
    }

    // その他のオプションのメニューの「ほかのアプリで開く」は、選んでいる項目の URL をほかのアプリで開き、選ぶのをやめる。
    // 「共有」は、URL とタイトルを共有し、選ぶのをやめる
    @Test
    fun moreOptionsOpenInOtherAppAndShare() = runTouchScreenTest { fixture ->
        val sharer = checkNotNull(fixture.itemSharer)
        selectItemRow(1)
        onNodeWithContentDescription(MORE_OPTIONS_DESCRIPTION).performClick()
        onNodeWithText(SHARE_LABEL).assertIsDisplayed()

        onNodeWithText(OPEN_IN_OTHER_APP_LABEL).performClick()
        waitForIdle()

        assertEquals(listOf(item(1).url), sharer.openedUrls)
        assertEquals(emptySet(), fixture.state.browse.selectedItemIds)
        assertNormalTopBar()

        selectItemRow(2)
        onNodeWithContentDescription(MORE_OPTIONS_DESCRIPTION).performClick()
        onNodeWithText(SHARE_LABEL).performClick()
        waitForIdle()

        assertEquals(listOf(item(2).url to item(2).title), sharer.shared)
        assertEquals(emptySet(), fixture.state.browse.selectedItemIds)
    }

    // ほかのアプリで開こうとして、開けるアプリが無ければ、そのことを伝える帯を出す
    @Test
    fun noAppForUrlShowsBanner() = runTouchScreenTest { fixture ->
        checkNotNull(fixture.itemSharer).result = ItemShareResult.NoApp
        selectItemRow(1)
        onNodeWithContentDescription(MORE_OPTIONS_DESCRIPTION).performClick()

        onNodeWithText(OPEN_IN_OTHER_APP_LABEL).performClick()
        waitForIdle()

        onNodeWithText(NO_APP_FOR_URL_NOTICE).assertIsDisplayed()
    }

    // 項目をほかのアプリで開く仕組みを持てば、その他のオプションのメニューは、ほかのアプリで開く・共有・タイトルを変更の順に並べる
    @Test
    fun moreOptionsListsOpenShareAndRenameInOrder() = runTouchScreenTest {
        selectItemRow(1)
        onNodeWithContentDescription(MORE_OPTIONS_DESCRIPTION).performClick()

        val labels = listOf(OPEN_IN_OTHER_APP_LABEL, SHARE_LABEL, RENAME_ITEM_LABEL)
        assertMenuRowsInOrder(labels)
        labels.forEach { onNodeWithText(it).assertIsEnabled() }
    }

    // その他のオプションのメニューの「タイトルを変更」は、選んでいる項目のタイトルを入れるダイアログを開き、選ぶのをやめる。
    // 選ぶのをやめても、ダイアログの対象は選んでいた項目のままで、変更を確定するとその項目のタイトルを書く
    @Test
    fun moreOptionsRenameOpensDialogForSelectedItem() = runTouchScreenTest { fixture ->
        selectItemRow(2)
        onNodeWithContentDescription(MORE_OPTIONS_DESCRIPTION).performClick()

        onNodeWithText(RENAME_ITEM_LABEL).performClick()
        waitForIdle()

        assertEquals(emptySet(), fixture.state.browse.selectedItemIds)
        assertNormalTopBar()
        onNodeWithText(CHANGE_LABEL).assertIsDisplayed()
        onNode(hasSetTextAction()).assert(hasText(item(2).title))

        onNode(hasSetTextAction()).performTextClearance()
        onNode(hasSetTextAction()).performTextInput(RENAMED_TITLE)
        onNodeWithText(CHANGE_LABEL).performClick()
        waitForIdle()

        assertEquals(listOf(ROOT_ITEMS[0], ROOT_ITEMS[1].copy(title = RENAMED_TITLE)), fixture.storedItems(ROOT_ID))
    }

    // 書けないあいだ（読むだけのツリー）は、その他のオプションのメニューの「タイトルを変更」を押せない。ほかのアプリで開く・共有は押せる
    @Test
    fun moreOptionsRenameIsDisabledWhenNotWritable() = runCloudMarksScreenTest(
        temporaryFolder,
        arrange = { it.store.seed(initialManifest(version = NEWER_VERSION)) },
        itemSharer = FakeItemSharer(),
        touchScreen = true,
    ) { fixture ->
        assertFalse(fixture.state.canWrite)
        selectItemRow(1)
        onNodeWithContentDescription(MORE_OPTIONS_DESCRIPTION).performClick()

        onNodeWithText(RENAME_ITEM_LABEL).assertIsNotEnabled()
        onNodeWithText(OPEN_IN_OTHER_APP_LABEL).assertIsEnabled()
        onNodeWithText(SHARE_LABEL).assertIsEnabled()
    }

    // 項目をほかのアプリで開く仕組みを持たなくても、1 つだけ選んでいれば、その他のオプションのボタンを出す。メニューは「タイトルを変更」だけを並べる
    @Test
    fun moreOptionsHasOnlyRenameWithoutSharer() = runCloudMarksScreenTest(temporaryFolder, touchScreen = true) { fixture ->
        selectItemRow(1)

        assertSelectionTopBar(count = 1, moreOptions = true)
        onNodeWithContentDescription(MORE_OPTIONS_DESCRIPTION).performClick()
        onNodeWithText(RENAME_ITEM_LABEL).assertIsDisplayed().assertIsEnabled()
        onNodeWithText(OPEN_IN_OTHER_APP_LABEL).assertDoesNotExist()
        onNodeWithText(SHARE_LABEL).assertDoesNotExist()

        onNodeWithText(RENAME_ITEM_LABEL).performClick()
        waitForIdle()

        onNodeWithText(CHANGE_LABEL).assertIsDisplayed()
        assertEquals(emptySet(), fixture.state.browse.selectedItemIds)
    }

    // ツリーのフォルダの行を長押しすると、フォルダのメニューを出す。開いているフォルダは替えない
    @Test
    fun longPressOnTreeFolderShowsFolderMenu() = runTouchScreenTest { fixture ->
        treeRow("A").performTouchInput { longClick() }
        waitForIdle()

        FOLDER_MENU_LABELS.forEach { onNodeWithText(it).assertIsDisplayed() }
        assertEquals(ROOT_ID, fixture.state.browse.currentFolderId)

        onNodeWithText(NEW_FOLDER_LABEL).performClick()
        onNodeWithText(CREATE_LABEL).assertIsDisplayed()
    }

    // リストの子のフォルダの行を長押ししても、フォルダのメニューを出す。開いているフォルダは替えない
    @Test
    fun longPressOnListFolderShowsFolderMenu() = runTouchScreenTest { fixture ->
        listFolderRow("A").performTouchInput { longClick() }
        waitForIdle()

        FOLDER_MENU_LABELS.forEach { onNodeWithText(it).assertIsDisplayed() }
        assertEquals(ROOT_ID, fixture.state.browse.currentFolderId)
    }

    // フォルダの行を押せば、リストの子のフォルダもツリーのフォルダも、そのフォルダを開く。メニューは出さない
    @Test
    fun tapOnFolderRowsOpensFolder() = runTouchScreenTest { fixture ->
        listFolderRow("A").performTouchInput { click() }
        waitForIdle()
        assertEquals(FOLDER_A, fixture.state.browse.currentFolderId)

        treeRow("B").performTouchInput { click() }
        waitForIdle()
        assertEquals(FOLDER_B, fixture.state.browse.currentFolderId)
        onNodeWithText(NEW_FOLDER_LABEL).assertDoesNotExist()
    }

    // タッチの画面の振る舞いで動かさなければ、項目を選んでいるあいだも AppBar を替えず、ハンバーガー・戻る・再読み込み・設定の横に、
    // 削除と移動のボタンを足すだけにする
    @Test
    fun desktopKeepsTopBarWhileSelecting() = runCloudMarksScreenTest(temporaryFolder, itemSharer = FakeItemSharer()) {
        clickItemRowWithKey(1, Key.CtrlLeft)
        waitForIdle()

        assertNormalTopBar()
        listOf(DELETE_LABEL, MOVE_LABEL).forEach { onNodeWithContentDescription(it).assertIsDisplayed() }
        onNodeWithContentDescription(MORE_OPTIONS_DESCRIPTION).assertDoesNotExist()
        onNodeWithText(selectionTitle(1)).assertDoesNotExist()
    }

    // タッチの画面の振る舞いで動かさなければ、タッチで項目を押すとその項目だけを選び、開かない
    @Test
    fun desktopTapSelectsItem() = runCloudMarksScreenTest(temporaryFolder, itemSharer = FakeItemSharer()) { fixture ->
        tapItemRow(1)

        assertEquals(setOf(item(1).id), fixture.state.browse.selectedItemIds)
        assertEquals(emptyList(), fixture.browserOpener.openedUrls)
    }

    // タッチの画面の振る舞いで動かさなければ、タッチで項目を長押しすると、項目のメニューを出さずに、その項目を足す（選ぶ状態に入る）
    @Test
    fun desktopLongPressTogglesWithoutMenu() = runCloudMarksScreenTest(temporaryFolder, itemSharer = FakeItemSharer()) { fixture ->
        longPressItemRow(1)
        longPressItemRow(2)

        assertEquals(setOf(item(1).id, item(2).id), fixture.state.browse.selectedItemIds)
        onNodeWithText(SELECT_LABEL).assertDoesNotExist()
        onNodeWithText(RENAME_ITEM_LABEL).assertDoesNotExist()
    }

    // タッチの画面の振る舞いで動かさなければ、ツリーのフォルダの行を長押ししても、フォルダのメニューを出さない
    @Test
    fun desktopTreeFolderLongPressShowsNoMenu() = runCloudMarksScreenTest(temporaryFolder, itemSharer = FakeItemSharer()) {
        treeRow("A").performTouchInput { longClick() }
        waitForIdle()

        FOLDER_MENU_LABELS.forEach { onNodeWithText(it).assertDoesNotExist() }
    }

    // タッチの画面の振る舞いで動かさなければ、リストの子のフォルダの行を長押ししても、フォルダのメニューを出さない
    @Test
    fun desktopListFolderLongPressShowsNoMenu() = runCloudMarksScreenTest(temporaryFolder, itemSharer = FakeItemSharer()) {
        listFolderRow("A").performTouchInput { longClick() }
        waitForIdle()

        FOLDER_MENU_LABELS.forEach { onNodeWithText(it).assertDoesNotExist() }
    }
}
