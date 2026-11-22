package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.unit.IntSize
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.app.AppState
import to.sava.cloudmarksmultiplatform.app.ControllerFixture
import to.sava.cloudmarksmultiplatform.app.Screen
import to.sava.cloudmarksmultiplatform.browse.BrowseState
import to.sava.cloudmarksmultiplatform.browser.BrowserTab
import to.sava.cloudmarksmultiplatform.browser.CHROME
import to.sava.cloudmarksmultiplatform.browser.FIREFOX
import to.sava.cloudmarksmultiplatform.browser.FakeBrowserOpener
import to.sava.cloudmarksmultiplatform.browser.FakeItemSharer
import to.sava.cloudmarksmultiplatform.operations.ItemRef
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.GetFault
import to.sava.cloudmarksmultiplatform.settings.TabLoading
import to.sava.cloudmarksmultiplatform.storage.Item
import to.sava.cloudmarksmultiplatform.sync.A_FILE_ID
import to.sava.cloudmarksmultiplatform.sync.FOLDER_A
import to.sava.cloudmarksmultiplatform.sync.FOLDER_B
import to.sava.cloudmarksmultiplatform.sync.ROOT_ID
import to.sava.cloudmarksmultiplatform.sync.ROOT_ITEMS
import to.sava.cloudmarksmultiplatform.sync.fileId
import to.sava.cloudmarksmultiplatform.sync.initialManifest
import to.sava.cloudmarksmultiplatform.sync.item
import to.sava.cloudmarksmultiplatform.sync.replaceItemsAsOtherWriter
import to.sava.cloudmarksmultiplatform.sync.seed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** どの項目にも合わない語。 */
private const val NO_MATCH = "zzz"

/** どの項目のタイトルにも含まれる語。 */
private const val ALL_TITLES = "title"

/** タイトルを変更するテストで入れる、新しいタイトル。 */
private const val RENAMED_TITLE = "renamed title"

/** 読むだけのツリーにするための、マニフェストの形式のバージョン（対応しているバージョンより新しい）。 */
private const val NEWER_VERSION = 2

/** B に置く項目ファイルの ID。 */
private val B_FILE_ID = fileId(32)

/** フォルダ B に、項目を 1 つ（[item] の 8）持たせる、起動の前の用意。 */
private val oneItemInB: (ControllerFixture) -> Unit = { it.store.replaceItemsAsOtherWriter(FOLDER_B, B_FILE_ID, listOf(item(8))) }

/** A に置く、テストの画面に収まらない数の項目。番号は、ルートフォルダの項目と重ならない。 */
private val LONG_A_ITEMS: List<Item> = (101..140).map { item(it) }

/** フォルダ A の項目を [LONG_A_ITEMS] にする、起動の前の用意。 */
private val longAItems: (ControllerFixture) -> Unit = { it.store.replaceItemsAsOtherWriter(FOLDER_A, fileId(31), LONG_A_ITEMS) }

/** 検索の結果を並べ替えたときに、先頭に見せる行の番号。テストの画面に収まらないだけ下。 */
private const val RESULT_SCROLL_INDEX = 30

/** 縦に持った電話のような、狭い画面の区分の窓の大きさ（幅 600dp 未満）。テストの画面に収まる。 */
private val PORTRAIT = IntSize(400, 760)

/** Ctrl を押しながら F を押した、キーの操作。 */
@OptIn(InternalComposeUiApi::class)
private val CTRL_F = KeyEvent(Key.F, KeyEventType.KeyDown, isCtrlPressed = true)

/** 不確定の進み具合の線。 */
private val indeterminateProgress = SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)

/** [items] を開くときのタブ。 */
private fun tabsOf(items: List<Item>): List<BrowserTab> = items.map { BrowserTab(it.url, it.title) }

/** AppBar の検索の入力欄。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.searchField(): SemanticsNodeInteraction = onNode(hasSetTextAction() and !hasAnyAncestor(isDialog()))

/** ダイアログの中の、文が [text] の要素。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.inDialog(text: String): SemanticsNodeInteraction = onNode(hasText(text) and hasAnyAncestor(isDialog()))

/** AppBar のルーペを押して検索を開き、[query] が空でなければ、入力欄に入れる。描き終えるまで待つ。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.search(query: String) {
    onNodeWithContentDescription(SEARCH_DESCRIPTION).performClick()
    waitForIdle()
    if (query.isNotEmpty()) searchField().performTextInput(query)
    waitForIdle()
}

/** 検索の結果の、項目 [n]（[item]）の行。入力欄にタイトルと同じ語を入れていても、入力欄は除く。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.resultRow(n: Int): SemanticsNodeInteraction = onNode(hasText(item(n).title) and !hasSetTextAction())

/** 検索の結果の、項目 [n]（[item]）の行を右クリックし、項目のメニューを出す。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.rightClickResult(n: Int) {
    resultRow(n).performMouseInput { rightClick() }
    waitForIdle()
}

/** メニューかダイアログの、文が [text] の行かボタンを押し、描き終えるまで待つ。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.clickText(text: String) {
    onNodeWithText(text).performClick()
    waitForIdle()
}

/**
 * 検索の結果の、項目 [n]（[item]）の行に、フォルダの表記 [label] を、タイトルの下に、タイトルと左をそろえて出しているか。
 * 表記は、行の中にある、文が [label] の要素（ツリーの同じ名前の行は除く）。
 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.assertFolderLabelUnderTitle(n: Int, label: String) {
    val row = resultRow(n).fetchSemanticsNode().boundsInRoot
    val title = onNode(hasText(item(n).title) and !hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    val caption = onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes()
        .map { it.boundsInRoot }
        .single { row.contains(it.center) }
    assertTrue(caption.top >= title.bottom, "表記 $caption、タイトル $title")
    assertEquals(title.left, caption.left)
}

@OptIn(ExperimentalTestApi::class)
class SearchScreenTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // ルーペを押すと検索を開き、入力欄に焦点を移す。空の入力欄には案内を出す。AppBar は、ハンバーガーと戻るの代わりに検索を閉じるボタンを、
    // 題の代わりに入力欄を出し、ルーペは出さず、再読み込みと設定は出したままにする
    @Test
    fun searchButtonOpensSearchWithFocusedField() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        onNodeWithContentDescription(SEARCH_DESCRIPTION).assertIsEnabled()

        search("")

        assertNotNull(fixture.state.browse.search)
        searchField().assertIsFocused()
        onNodeWithText(SEARCH_PLACEHOLDER).assertIsDisplayed()
        onNodeWithContentDescription(CLOSE_SEARCH_DESCRIPTION).assertIsDisplayed()
        listOf(TREE_PANE_DESCRIPTION, BACK_DESCRIPTION, SEARCH_DESCRIPTION).forEach { onNodeWithContentDescription(it).assertDoesNotExist() }
        listOf(RELOAD_DESCRIPTION, SETTINGS_DESCRIPTION).forEach { onNodeWithContentDescription(it).assertIsDisplayed() }
    }

    // ルーペは読み直すボタンの左に置く。項目を選んでいるあいだは、選んでいる項目を消すボタンと移すボタンが、ルーペの左に並ぶ
    @Test
    fun searchButtonSitsBetweenSelectedItemButtonsAndReload() = runCloudMarksScreenTest(temporaryFolder) {
        clickItemRowWithKey(1, Key.CtrlLeft)
        waitForIdle()

        val lefts = listOf(DELETE_LABEL, MOVE_LABEL, SEARCH_DESCRIPTION, RELOAD_DESCRIPTION, SETTINGS_DESCRIPTION).map {
            onNodeWithContentDescription(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.left
        }
        assertEquals(lefts.sorted(), lefts)
        assertEquals(lefts.size, lefts.toSet().size)
    }

    // 語を入れると、合う項目をツリーの順に並べ、タイトルの下にフォルダの表記を出す。ルートフォルダ直下の項目は「トップ」
    @Test
    fun typingShowsResultsWithFolderLabels() = runCloudMarksScreenTest(temporaryFolder) {
        search(ALL_TITLES)

        val tops = (1..3).map { n -> resultRow(n).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops)
        assertFolderLabelUnderTitle(1, ROOT_FOLDER_LABEL)
        assertFolderLabelUnderTitle(2, ROOT_FOLDER_LABEL)
        assertFolderLabelUnderTitle(3, "A")
        resultRow(3).assert(hasText(item(3).url))
    }

    // 語を替えると、結果も替わる
    @Test
    fun changingQueryChangesResults() = runCloudMarksScreenTest(temporaryFolder) {
        search(item(3).title)
        resultRow(3).assertIsDisplayed()
        resultRow(1).assertDoesNotExist()

        searchField().performTextClearance()
        searchField().performTextInput(item(1).title)
        waitForIdle()

        resultRow(1).assertIsDisplayed()
        resultRow(3).assertDoesNotExist()
    }

    // 語が空か空白だけのあいだは、結果の枠に何も出さない（一致する項目が無いことも伝えない）
    @Test
    fun blankQueryShowsNothing() = runCloudMarksScreenTest(temporaryFolder) {
        listOf("", " ", "　 ").forEach { query ->
            search("")
            if (query.isNotEmpty()) searchField().performTextInput(query)
            waitForIdle()

            (1..3).forEach { n -> resultRow(n).assertDoesNotExist() }
            onNodeWithText(NO_SEARCH_RESULTS_NOTICE).assertDoesNotExist()
            onNodeWithContentDescription(CLOSE_SEARCH_DESCRIPTION).performClick()
            waitForIdle()
        }
    }

    // 検索のために読んでいるあいだは、進み具合の線を出し、一致する項目が無くてもそのことを伝えない。読み終えて一致する項目が無ければ伝え、線を消す
    @Test
    fun loadingShowsProgressAndHoldsNoResultsNotice() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        val gate = fixture.stopNextGet { it == fixture.itemsKey(FOLDER_A, A_FILE_ID) }

        search(NO_MATCH)

        assertTrue(fixture.state.browse.searchLoading)
        onNode(indeterminateProgress).assertIsDisplayed()
        onNodeWithText(NO_SEARCH_RESULTS_NOTICE).assertDoesNotExist()

        gate.complete(Unit)
        waitForIdle()

        assertFalse(fixture.state.browse.searchLoading)
        onNode(indeterminateProgress).assertDoesNotExist()
        onNodeWithText(NO_SEARCH_RESULTS_NOTICE).assertIsDisplayed()
    }

    // 検索を開いているあいだにマニフェストを読み直すと、進み具合の線は AppBar の下の 1 本だけを出す（結果の枠には出さない）。
    // 読み直しているあいだも、一致する項目が無いことは伝えない
    @Test
    fun manifestReloadWhileSearchingShowsSingleProgressLine() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        search(NO_MATCH)
        onAllNodes(indeterminateProgress).assertCountEquals(0)
        val gate = fixture.stopNextGet(fixture.isManifest)

        onNodeWithContentDescription(RELOAD_DESCRIPTION).performClick()
        waitForIdle()

        assertTrue(fixture.state.browse.loading)
        assertTrue(fixture.state.browse.searchLoading)
        onAllNodes(indeterminateProgress).assertCountEquals(1)
        onNodeWithText(NO_SEARCH_RESULTS_NOTICE).assertDoesNotExist()

        gate.complete(Unit)
        waitForIdle()

        onAllNodes(indeterminateProgress).assertCountEquals(0)
        onNodeWithText(NO_SEARCH_RESULTS_NOTICE).assertIsDisplayed()
    }

    // 語を替えずに、フォルダの項目を読み終えたら、そのフォルダの合う項目を結果に足す
    @Test
    fun finishedFolderReadAddsResults() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        val gate = fixture.stopNextGet { it == fixture.itemsKey(FOLDER_A, A_FILE_ID) }
        search(item(3).title)
        resultRow(3).assertDoesNotExist()

        gate.complete(Unit)
        waitForIdle()

        resultRow(3).assertIsDisplayed()
    }

    // S3 につながらないあいだに結果の項目を削除すると、溜めた削除にして、その行を結果から消す
    @Test
    fun pendingDeleteHidesResult() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        search(ALL_TITLES)
        fixture.store.unreachable = true
        rightClickResult(3)

        clickText(DELETE_LABEL)

        assertEquals(setOf(ItemRef(FOLDER_A, item(3).id)), fixture.state.browse.pendingDeletes)
        resultRow(3).assertDoesNotExist()
        resultRow(1).assertIsDisplayed()
        resultRow(2).assertIsDisplayed()
    }

    // 項目を読めなかったフォルダがあれば、その数を伝える帯を、閉じるボタンを置かずに出す。読めたフォルダの項目は結果に出す
    @Test
    fun unreadableFoldersShowBannerWithCount() = runCloudMarksScreenTest(temporaryFolder, arrange = oneItemInB) { fixture ->
        fixture.store.onNextGet(GetFault.Forbidden) { it == fixture.itemsKey(FOLDER_A, A_FILE_ID) }
        fixture.store.onNextGet(GetFault.Forbidden) { it == fixture.itemsKey(FOLDER_B, B_FILE_ID) }

        search(ALL_TITLES)

        onNodeWithText(unreadableFoldersNotice(2)).assertIsDisplayed()
        onNodeWithText(CLOSE_LABEL).assertDoesNotExist()
        resultRow(1).assertIsDisplayed()
        resultRow(2).assertIsDisplayed()
        resultRow(3).assertDoesNotExist()
        resultRow(8).assertDoesNotExist()
    }

    // 結果の項目をマウスでクリックすると、既定のブラウザで開き、最後に開いた項目として覚える。開いているフォルダでないフォルダの項目も開く
    @Test
    fun clickOpensResultInDefaultBrowser() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        search(ALL_TITLES)

        resultRow(3).performMouseInput { click() }
        waitForIdle()

        assertEquals(listOf(FakeBrowserOpener.OpenRequest(CHROME, tabsOf(listOf(item(3))), TabLoading())), fixture.browserOpener.opened)
        assertEquals(item(3).id, fixture.state.lastOpenedItemId)
        assertNotNull(fixture.state.browse.search)
    }

    // Ctrl や Shift を押しながらクリックしても、選ばずに開く
    @Test
    fun modifiedClickOpensWithoutSelecting() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        search(ALL_TITLES)

        clickItemRowWithKey(3, Key.CtrlLeft)
        clickItemRowWithKey(1, Key.ShiftLeft)
        waitForIdle()

        assertEquals(listOf(CHROME.name to listOf(item(3).url), CHROME.name to listOf(item(1).url)), fixture.browserOpener.openedUrls)
        assertEquals(emptySet(), fixture.state.browse.selectedItemIds)
    }

    // 結果の項目をダブルクリックしても、1 回だけ開く
    @Test
    fun doubleClickOpensResultOnce() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        search(ALL_TITLES)

        resultRow(3).performMouseInput { doubleClick() }
        waitForIdle()

        assertEquals(listOf(CHROME.name to listOf(item(3).url)), fixture.browserOpener.openedUrls)
    }

    // 最後に開いた項目の行は、開いているフォルダのリストと同じ色で強調する。ほかの行は、リストの強調しない行と同じ色
    @Test
    fun lastOpenedResultIsHighlightedLikeList() = runCloudMarksScreenTest(temporaryFolder) {
        itemRow(1).performMouseInput { click() }
        moveMouseToBlank()
        val lastOpened = itemRowBackground(1)
        val plain = itemRowBackground(2)

        search(ALL_TITLES)
        moveMouseToBlank()

        assertEquals(lastOpened, itemRowBackground(1))
        assertEquals(plain, itemRowBackground(2))
    }

    // 結果の項目の右クリックのメニューは、上から、ブラウザで開く行（既定のブラウザが先）・タイトルを変更・移動・削除・入っているフォルダを開く。
    // 右クリックしても項目を選ばない
    @Test
    fun contextMenuListsRowsInOrder() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        search(ALL_TITLES)

        rightClickResult(3)

        val labels = listOf(
            openInBrowserLabel(CHROME.name),
            openInBrowserLabel(FIREFOX.name),
            RENAME_ITEM_LABEL,
            MOVE_LABEL,
            DELETE_LABEL,
            OPEN_CONTAINING_FOLDER_LABEL,
        )
        assertMenuRowsInOrder(labels)
        labels.forEach { onNodeWithText(it).assertIsEnabled() }
        assertEquals(emptySet(), fixture.state.browse.selectedItemIds)
    }

    // 書けないあいだ（読むだけのツリー）は、右クリックのメニューのタイトルを変更・移動・削除を押せない。ブラウザで開く・入っているフォルダを開くは押せる
    @Test
    fun contextMenuWriteRowsAreDisabledWhenNotWritable() = runCloudMarksScreenTest(
        temporaryFolder,
        arrange = { it.store.seed(initialManifest(version = NEWER_VERSION)) },
    ) { fixture ->
        assertFalse(fixture.state.canWrite)
        search(ALL_TITLES)

        rightClickResult(3)

        listOf(RENAME_ITEM_LABEL, MOVE_LABEL, DELETE_LABEL).forEach { onNodeWithText(it).assertIsNotEnabled() }
        listOf(openInBrowserLabel(CHROME.name), OPEN_CONTAINING_FOLDER_LABEL).forEach { onNodeWithText(it).assertIsEnabled() }
    }

    // 右クリックのメニューのブラウザの行は、開いているフォルダでないフォルダの項目を、そのブラウザで開く
    @Test
    fun contextMenuOpensResultInChosenBrowser() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        search(ALL_TITLES)
        rightClickResult(3)

        clickText(openInBrowserLabel(FIREFOX.name))

        assertEquals(listOf(FakeBrowserOpener.OpenRequest(FIREFOX, tabsOf(listOf(item(3))), TabLoading())), fixture.browserOpener.opened)
        onNodeWithText(OPEN_CONTAINING_FOLDER_LABEL).assertDoesNotExist()
    }

    // 右クリックのメニューの「タイトルを変更」は、その項目のタイトルを入れたダイアログを開き、変更を確定すると、開いているフォルダでない
    // フォルダのその項目のタイトルを書く
    @Test
    fun contextMenuRenamesResult() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        search(ALL_TITLES)
        rightClickResult(3)

        clickText(RENAME_ITEM_LABEL)
        val field = onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))
        field.assert(hasText(item(3).title))
        field.performTextClearance()
        field.performTextInput(RENAMED_TITLE)
        inDialog(CHANGE_LABEL).performClick()
        waitForIdle()

        assertEquals(listOf(item(3).copy(title = RENAMED_TITLE)), fixture.storedItems(FOLDER_A))
        assertEquals(ROOT_ITEMS, fixture.storedItems(ROOT_ID))
    }

    // 右クリックのメニューの「移動」は、その項目の移動先を選ぶダイアログを開き、移動先を選んで確定すると、その項目だけを移す
    @Test
    fun contextMenuMovesResult() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        search(ALL_TITLES)
        rightClickResult(3)

        clickText(MOVE_LABEL)
        inDialog(MOVE_DESTINATION_TITLE).assertIsDisplayed()
        inDialog("B").performClick()
        inDialog(MOVE_LABEL).performClick()
        waitForIdle()

        assertEquals(emptyList(), fixture.storedItems(FOLDER_A))
        assertEquals(listOf(item(3)), fixture.storedItems(FOLDER_B))
        assertEquals(ROOT_ITEMS, fixture.storedItems(ROOT_ID))
    }

    // 右クリックのメニューの「削除」は、確かめずにその項目だけを消し、元に戻すお知らせを出す。「元に戻す」を押すと元に戻す
    @Test
    fun contextMenuDeletesResultWithUndo() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        search(ALL_TITLES)
        rightClickResult(3)

        clickText(DELETE_LABEL)

        assertEquals(emptyList(), fixture.storedItems(FOLDER_A))
        assertEquals(ROOT_ITEMS, fixture.storedItems(ROOT_ID))
        onNodeWithText(deletedItemsNotice(1)).assertIsDisplayed()
        resultRow(3).assertDoesNotExist()

        clickText(UNDO_LABEL)

        assertEquals(listOf(item(3)), fixture.storedItems(FOLDER_A))
    }

    // 右クリックのメニューの「入っているフォルダを開く」は、検索を閉じて、その項目が入っているフォルダを開き、その項目だけを選ぶ
    @Test
    fun contextMenuOpensContainingFolder() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        search(ALL_TITLES)
        rightClickResult(3)

        clickText(OPEN_CONTAINING_FOLDER_LABEL)

        val browse = fixture.state.browse
        assertNull(browse.search)
        assertEquals(FOLDER_A, browse.currentFolderId)
        assertEquals(setOf(item(3).id), browse.selectedItemIds)
        itemRow(3).assertIsDisplayed()
        onNodeWithContentDescription(TREE_PANE_DESCRIPTION).assertIsDisplayed()
    }

    // 入っているフォルダを開いたら、開いたフォルダのリストで、その項目の行が全部見える位置までスクロールする
    @Test
    fun openingContainingFolderScrollsToItem() = runCloudMarksScreenTest(temporaryFolder, arrange = longAItems) { fixture ->
        val target = LONG_A_ITEMS.last()
        search(target.title)
        rightClickResult(140)

        clickText(OPEN_CONTAINING_FOLDER_LABEL)

        assertEquals(FOLDER_A, fixture.state.browse.currentFolderId)
        assertEquals(setOf(target.id), fixture.state.browse.selectedItemIds)
        val row = itemRow(140).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(row.bottom <= onRoot().fetchSemanticsNode().size.height, "$row")
    }

    // 入っているフォルダを開いたら、リストに焦点を移すので、そのまま Delete キーを押すと、選んだ項目を消す
    @Test
    fun deleteKeyAfterOpeningContainingFolderDeletesItem() = runCloudMarksScreenTest(temporaryFolder, arrange = longAItems) { fixture ->
        val target = LONG_A_ITEMS.last()
        search(target.title)
        rightClickResult(140)
        clickText(OPEN_CONTAINING_FOLDER_LABEL)

        onRoot().performKeyInput { pressKey(Key.Delete) }
        waitForIdle()

        assertEquals(LONG_A_ITEMS.dropLast(1), fixture.storedItems(FOLDER_A))
        itemRow(140).assertDoesNotExist()
    }

    // 入っているフォルダを開いたら、リストに焦点を移すので、そのまま Esc キーを押すと、選ぶのをやめる
    @Test
    fun escapeKeyAfterOpeningContainingFolderClearsSelection() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        search(ALL_TITLES)
        rightClickResult(3)
        clickText(OPEN_CONTAINING_FOLDER_LABEL)
        assertEquals(setOf(item(3).id), fixture.state.browse.selectedItemIds)

        onRoot().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()

        assertEquals(emptySet(), fixture.state.browse.selectedItemIds)
        assertEquals(listOf(item(3)), fixture.storedItems(FOLDER_A))
    }

    // 語を替えると、結果を先頭から見せる
    @Test
    fun changingQueryScrollsResultsToTop() = runCloudMarksScreenTest(temporaryFolder, arrange = longRootItems) {
        search(ALL_TITLES)
        onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText(item(1).title))).performScrollToIndex(RESULT_SCROLL_INDEX)
        waitForIdle()
        resultRow(1).assertDoesNotExist()

        searchField().performTextInput("-")
        waitForIdle()

        resultRow(1).assertIsDisplayed()
    }

    // 検索を閉じるボタンを押すと検索を閉じ、AppBar をハンバーガー・戻る・ルーペのある形に戻し、開いているフォルダのリストを出す
    @Test
    fun closeButtonClosesSearch() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        search(item(3).title)

        onNodeWithContentDescription(CLOSE_SEARCH_DESCRIPTION).performClick()
        waitForIdle()

        assertNull(fixture.state.browse.search)
        listOf(TREE_PANE_DESCRIPTION, BACK_DESCRIPTION, SEARCH_DESCRIPTION).forEach { onNodeWithContentDescription(it).assertIsDisplayed() }
        searchField().assertDoesNotExist()
        itemRow(1).assertIsDisplayed()
        itemRow(3).assertDoesNotExist()
    }

    // 入力欄で Esc を押すと、検索を閉じる
    @Test
    fun escapeInFieldClosesSearch() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        search(item(3).title)

        searchField().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()

        assertNull(fixture.state.browse.search)
        searchField().assertDoesNotExist()
    }

    // ツリーでフォルダを押すと、検索を閉じて、そのフォルダを開く
    @Test
    fun treeFolderClickClosesSearch() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        search("")

        treeRow("A").performClick()
        waitForIdle()

        assertNull(fixture.state.browse.search)
        assertEquals(FOLDER_A, fixture.state.browse.currentFolderId)
        itemRow(3).assertIsDisplayed()
    }

    // 狭い画面では、メインの位置に結果を出す
    @Test
    fun narrowScreenShowsResultsInMain() = runCloudMarksScreenTest(temporaryFolder, containerSize = mutableStateOf(PORTRAIT)) {
        search(item(3).title)

        resultRow(3).assertIsDisplayed()
        assertFolderLabelUnderTitle(3, "A")
    }

    // 検索を開くキーの操作（Ctrl+F）で、検索を開いて入力欄に焦点を移す
    @Test
    fun ctrlFOpensSearchWithFocusedField() {
        var shortcut: SearchShortcut? = null
        runCloudMarksScreenTest(temporaryFolder, searchShortcutOf = { SearchShortcut(it).also { created -> shortcut = created } }) { fixture ->
            val handled = runOnUiThread { checkNotNull(shortcut).onKeyEvent(CTRL_F) }
            waitForIdle()

            assertTrue(handled)
            assertNotNull(fixture.state.browse.search)
            searchField().assertIsFocused()
        }
    }

    // 検索を開いているあいだに Ctrl+F を押すと、入れた語を残したまま、入力欄に焦点を移す
    @Test
    fun ctrlFWhileSearchingFocusesField() {
        var shortcut: SearchShortcut? = null
        runCloudMarksScreenTest(temporaryFolder, searchShortcutOf = { SearchShortcut(it).also { created -> shortcut = created } }) { fixture ->
            search(ALL_TITLES)
            resultRow(1).requestFocus()
            waitForIdle()
            searchField().assertIsNotFocused()

            val handled = runOnUiThread { checkNotNull(shortcut).onKeyEvent(CTRL_F) }
            waitForIdle()

            assertTrue(handled)
            searchField().assertIsFocused()
            assertEquals(ALL_TITLES, fixture.state.browse.search?.query)
        }
    }

    // 設定の画面では、Ctrl+F を受けず（ほかの要素に渡し）、検索を開かない
    @Test
    fun ctrlFIsIgnoredOnSettingsScreen() {
        var shortcut: SearchShortcut? = null
        runCloudMarksScreenTest(temporaryFolder, searchShortcutOf = { SearchShortcut(it).also { created -> shortcut = created } }) { fixture ->
            onNodeWithContentDescription(SETTINGS_DESCRIPTION).performClick()
            waitForIdle()

            val handled = runOnUiThread { checkNotNull(shortcut).onKeyEvent(CTRL_F) }
            waitForIdle()

            assertFalse(handled)
            assertNull(fixture.state.browse.search)
            assertEquals(Screen.SETTINGS, fixture.state.screen)
        }
    }

    // メインの画面でも、ツリーを読めていなければ検索を開けない。設定の画面でも開けない
    @Test
    fun searchNeedsTreeOnMainScreen() {
        assertFalse(AppState(screen = Screen.MAIN, browse = BrowseState()).canOpenSearch)
        assertFalse(AppState(screen = Screen.SETTINGS, browse = BrowseState()).canOpenSearch)
    }
}

@OptIn(ExperimentalTestApi::class)
class SearchTouchScreenTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** タッチの画面の振る舞いで、項目をほかのアプリで開く仕組みと共有する仕組みを持つ画面で、[block] を走らせる。 */
    private fun runTouchScreenTest(block: suspend ComposeUiTest.(ControllerFixture) -> Unit) =
        runCloudMarksScreenTest(temporaryFolder, itemSharer = FakeItemSharer(), touchScreen = true, block = block)

    /** 検索の結果の、項目 [n]（[item]）の行を、タッチで長押しする。 */
    private fun ComposeUiTest.longPressResult(n: Int) {
        resultRow(n).performTouchInput { longClick() }
        waitForIdle()
    }

    // 結果の項目を押すと、既定のブラウザで開く
    @Test
    fun tapOpensResult() = runTouchScreenTest { fixture ->
        search(ALL_TITLES)

        resultRow(3).performTouchInput { click() }
        waitForIdle()

        assertEquals(listOf(CHROME.name to listOf(item(3).url)), fixture.browserOpener.openedUrls)
        assertEquals(emptySet(), fixture.state.browse.selectedItemIds)
    }

    // 結果の項目を長押しすると、押した位置に項目のメニューを出す。行は上から、開く・アプリを選んで開く・共有・タイトルを変更・移動・削除・
    // 入っているフォルダを開くの順で、「選択」は無い。項目は開かず、選ばない
    @Test
    fun longPressShowsMenuWithoutSelect() = runTouchScreenTest { fixture ->
        search(ALL_TITLES)

        longPressResult(3)

        val labels = listOf(
            OPEN_LABEL,
            OPEN_IN_OTHER_APP_LABEL,
            SHARE_LABEL,
            RENAME_ITEM_LABEL,
            MOVE_LABEL,
            DELETE_LABEL,
            OPEN_CONTAINING_FOLDER_LABEL,
        )
        assertMenuRowsInOrder(labels)
        labels.forEach { onNodeWithText(it).assertIsEnabled() }
        onNodeWithText(SELECT_LABEL).assertDoesNotExist()
        assertEquals(emptyList(), fixture.browserOpener.openedUrls)
        assertEquals(emptySet(), fixture.state.browse.selectedItemIds)
    }

    // 長押しのメニューの「開く」は、その項目を既定のブラウザで開く
    @Test
    fun longPressMenuOpensResult() = runTouchScreenTest { fixture ->
        search(ALL_TITLES)
        longPressResult(3)

        tapText(OPEN_LABEL)

        assertEquals(listOf(CHROME.name to listOf(item(3).url)), fixture.browserOpener.openedUrls)
        assertEquals(item(3).id, fixture.state.lastOpenedItemId)
    }

    // 長押しのメニューの「アプリを選んで開く」と「共有」は、開いているフォルダでないフォルダのその項目を渡す
    @Test
    fun longPressMenuOpensInOtherAppAndShares() = runTouchScreenTest { fixture ->
        val sharer = checkNotNull(fixture.itemSharer)
        search(ALL_TITLES)
        longPressResult(3)

        tapText(OPEN_IN_OTHER_APP_LABEL)

        assertEquals(listOf(item(3).url), sharer.openedUrls)

        longPressResult(3)
        tapText(SHARE_LABEL)

        assertEquals(listOf(item(3).url to item(3).title), sharer.shared)
    }

    // 長押しのメニューの「削除」は、その項目だけを消す
    @Test
    fun longPressMenuDeletesResult() = runTouchScreenTest { fixture ->
        search(ALL_TITLES)
        longPressResult(3)

        tapText(DELETE_LABEL)

        assertEquals(emptyList(), fixture.storedItems(FOLDER_A))
        assertEquals(ROOT_ITEMS, fixture.storedItems(ROOT_ID))
    }

    // 長押しのメニューの「入っているフォルダを開く」は、検索を閉じて、その項目が入っているフォルダを開き、その項目だけを選ぶ
    @Test
    fun longPressMenuOpensContainingFolder() = runTouchScreenTest { fixture ->
        search(ALL_TITLES)
        longPressResult(3)

        tapText(OPEN_CONTAINING_FOLDER_LABEL)

        val browse = fixture.state.browse
        assertNull(browse.search)
        assertEquals(FOLDER_A, browse.currentFolderId)
        assertEquals(setOf(item(3).id), browse.selectedItemIds)
    }
}
