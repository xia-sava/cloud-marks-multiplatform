package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntSize
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.app.ControllerFixture
import to.sava.cloudmarksmultiplatform.browser.FakeItemSharer
import to.sava.cloudmarksmultiplatform.storage.FolderEntry
import to.sava.cloudmarksmultiplatform.sync.FOLDER_A
import to.sava.cloudmarksmultiplatform.sync.ROOT_ID
import to.sava.cloudmarksmultiplatform.sync.ROOT_ITEMS
import to.sava.cloudmarksmultiplatform.sync.folderId
import to.sava.cloudmarksmultiplatform.sync.initialManifest
import to.sava.cloudmarksmultiplatform.sync.item
import to.sava.cloudmarksmultiplatform.sync.seed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 横に持った電話のような、広い画面の区分の窓の大きさ（幅 600dp 以上。テストの画面の密度は 1）。テストの画面に収まる。 */
private val LANDSCAPE = IntSize(760, 400)

/** 縦に持った電話のような、狭い画面の区分の窓の大きさ（幅 600dp 未満）。テストの画面に収まる。 */
private val PORTRAIT = IntSize(400, 760)

/** ルートフォルダに足す子のフォルダの数。ツリーの行（36dp）が、狭い画面のドロワーの高さに収まらないだけ並ぶ。 */
private const val MANY_FOLDER_COUNT = 100

/** ルートフォルダに足す子のフォルダの、最初のフォルダの番号。[folderId] の番号が、ほかのフォルダと重ならないようにする。 */
private const val FIRST_ADDED_FOLDER = 10

/** ルートフォルダに足す子のフォルダの名前。 */
private fun addedFolderName(n: Int): String = "F$n"

/** ルートフォルダに、子のフォルダを [MANY_FOLDER_COUNT] 個足したマニフェストを置く、起動の前の用意。 */
private val manyRootFolders: (ControllerFixture) -> Unit = {
    val added = (FIRST_ADDED_FOLDER until FIRST_ADDED_FOLDER + MANY_FOLDER_COUNT).map { n ->
        FolderEntry(folderId(n), ROOT_ID, addedFolderName(n), null, 0)
    }
    it.store.seed(initialManifest().let { manifest -> manifest.copy(folders = manifest.folders + added) })
}

/**
 * リストをスクロールしたときに、先頭に見せる行の番号（子のフォルダ A・B の行に続く、項目の行のどれか）。
 * そこから後ろの行が、狭い画面のリストの高さより長く並ぶ（リストの末尾に詰めて見せ直さない）。
 */
private const val LIST_SCROLL_INDEX = 15

/** ツリーをスクロールしたときに、先頭に見せる行の番号。 */
private const val TREE_SCROLL_INDEX = 40

/** ツリーをスクロールしたときに見える、足した子のフォルダの名前。先頭に見せる行（[TREE_SCROLL_INDEX]）から数行先。 */
private val FOLDER_SHOWN_AFTER_TREE_SCROLL = addedFolderName(FIRST_ADDED_FOLDER + TREE_SCROLL_INDEX)

/** ダイアログに入れるタイトル。 */
private const val TYPED_TITLE = "typed"

/** 文が [text] の要素の上端（根の座標）。無ければ空。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.topsOf(text: String): List<Float> = onAllNodesWithText(text).fetchSemanticsNodes().map { it.boundsInRoot.top }

/** 窓の大きさを [size] に替えて、端末を回したときと同じく窓の幅の区分を替え、描き終えるまで待つ。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.rotate(window: MutableState<IntSize>, size: IntSize) {
    window.value = size
    waitForIdle()
}

@OptIn(ExperimentalTestApi::class)
class LayoutSwitchScreenTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** 窓の大きさを [initial] で始めて、タッチの画面の振る舞いで、[block] を走らせる。窓の大きさは [block] に渡す状態で替える。 */
    private fun runRotatingTest(
        initial: IntSize,
        arrange: (ControllerFixture) -> Unit = {},
        block: suspend ComposeUiTest.(ControllerFixture, MutableState<IntSize>) -> Unit,
    ) {
        val window = mutableStateOf(initial)
        runCloudMarksScreenTest(
            temporaryFolder,
            arrange = arrange,
            itemSharer = FakeItemSharer(),
            touchScreen = true,
            containerSize = window,
        ) { fixture -> block(fixture, window) }
    }

    // 窓の大きさが替わると、窓の幅の区分に合わせて、広い画面と狭い画面を替える
    @Test
    fun windowSizeSwitchesLayout() = runRotatingTest(LANDSCAPE) { fixture, window ->
        assertTrue(fixture.state.browse.wideLayout)

        rotate(window, PORTRAIT)
        assertFalse(fixture.state.browse.wideLayout)

        rotate(window, LANDSCAPE)
        assertTrue(fixture.state.browse.wideLayout)
    }

    // 窓の幅の区分が替わっても、リストのスクロール位置を保つ（縦にして戻しても、横にして戻しても）。
    // 回帰の見張り。スクロール位置（LazyListState）は窓の幅の区分の外で持つので、リストを作り直して移しても（MainScreenParts を
    // 使わなくても）この画面のテストでは保たれ、作り直さずに移すことは判別しない
    @Test
    fun listScrollPositionIsKeptAcrossLayouts() = listOf(LANDSCAPE to PORTRAIT, PORTRAIT to LANDSCAPE).forEach { (initial, other) ->
        runRotatingTest(initial, arrange = longRootItems) { _, window ->
            onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText(item(1).title))).performScrollToIndex(LIST_SCROLL_INDEX)
            waitForIdle()
            val scrolledTitle = item(LIST_SCROLL_INDEX - 1).title
            val top = assertNotNull(itemRowTop(LIST_SCROLL_INDEX - 1), "$initial")

            rotate(window, other)
            assertNotNull(topsOf(scrolledTitle).singleOrNull(), "$initial → $other")
            assertNull(itemRowTop(1), "$initial → $other")

            rotate(window, initial)
            assertEquals(top, itemRowTop(LIST_SCROLL_INDEX - 1), "$initial → $other → $initial")
        }
    }

    // 窓の幅の区分が替わっても、ツリーのスクロール位置を保つ。開いているフォルダの行（ルートフォルダ）が見えていなくても、
    // その行までスクロールし直さない
    @Test
    fun treeScrollPositionIsKeptAcrossLayouts() = runRotatingTest(LANDSCAPE, arrange = manyRootFolders) { _, window ->
        onAllNodes(hasScrollToIndexAction() and hasAnyDescendant(hasText(ROOT_FOLDER_LABEL)))[0].performScrollToIndex(TREE_SCROLL_INDEX)
        waitForIdle()
        val top = assertNotNull(topsOf(FOLDER_SHOWN_AFTER_TREE_SCROLL).singleOrNull())

        rotate(window, PORTRAIT)
        onNodeWithContentDescription(TREE_PANE_DESCRIPTION).performClick()
        waitForIdle()
        assertEquals(listOf(top), topsOf(FOLDER_SHOWN_AFTER_TREE_SCROLL), "ドロワーのツリー")
        onNodeWithContentDescription(CLOSE_DRAWER_DESCRIPTION).performClick()
        waitForIdle()

        rotate(window, LANDSCAPE)
        assertEquals(listOf(top), topsOf(FOLDER_SHOWN_AFTER_TREE_SCROLL), "ツリーの枠")
    }

    // フォルダを替えたら、リストを先頭から見せる。窓の幅の区分を替えたあとも同じ
    @Test
    fun openingFolderShowsListFromTop() = runRotatingTest(LANDSCAPE, arrange = longRootItems) { fixture, window ->
        rotate(window, PORTRAIT)
        onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText(item(1).title))).performScrollToIndex(LIST_SCROLL_INDEX)
        waitForIdle()
        assertNull(itemRowTop(1))

        runOnUiThread { fixture.controller.openFolder(FOLDER_A) }
        waitForIdle()
        runOnUiThread { fixture.controller.back() }
        waitForIdle()

        assertEquals(ROOT_ID, fixture.state.browse.currentFolderId)
        onNodeWithText(item(1).title).assertIsDisplayed()
    }

    // 項目の行を長押しして出したメニューは、窓の幅の区分が替わっても閉じず、そのまま長押しした項目の操作を行う
    @Test
    fun itemMenuStaysOpenAcrossLayouts() = runRotatingTest(LANDSCAPE) { fixture, window ->
        itemRow(2).performTouchInput { longClick() }
        waitForIdle()
        onNodeWithText(SELECT_LABEL).assertIsDisplayed()

        rotate(window, PORTRAIT)
        onNodeWithText(SELECT_LABEL).assertIsDisplayed()
        rotate(window, LANDSCAPE)
        onNodeWithText(SELECT_LABEL).assertIsDisplayed()

        tapText(SELECT_LABEL)
        assertEquals(setOf(item(2).id), fixture.state.browse.selectedItemIds)
    }

    // 選ぶ状態の AppBar の、その他のオプションのメニューは、窓の幅の区分が替わっても閉じず、そのまま選んでいる項目の操作を行う
    @Test
    fun moreOptionsMenuStaysOpenAcrossLayouts() = runRotatingTest(PORTRAIT) { fixture, window ->
        itemRow(1).performTouchInput { longClick() }
        waitForIdle()
        tapText(SELECT_LABEL)
        onNodeWithContentDescription(MORE_OPTIONS_DESCRIPTION).performClick()
        waitForIdle()
        onNodeWithText(SHARE_LABEL).assertIsDisplayed()

        rotate(window, LANDSCAPE)
        onNodeWithText(SHARE_LABEL).assertIsDisplayed()
        rotate(window, PORTRAIT)
        onNodeWithText(SHARE_LABEL).assertIsDisplayed()

        tapText(SHARE_LABEL)
        assertEquals(listOf(item(1).url to item(1).title), checkNotNull(fixture.itemSharer).shared)
    }

    // リストの子のフォルダの行を長押しして出したフォルダのメニューは、窓の幅の区分が替わっても閉じない
    @Test
    fun listFolderMenuStaysOpenAcrossLayouts() = runRotatingTest(PORTRAIT) { _, window ->
        onNode(hasText("A") and !hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)))
            .performTouchInput { longClick() }
        waitForIdle()
        onNodeWithText(NEW_FOLDER_LABEL).assertIsDisplayed()

        rotate(window, LANDSCAPE)
        onNodeWithText(NEW_FOLDER_LABEL).assertIsDisplayed()
        rotate(window, PORTRAIT)
        onNodeWithText(NEW_FOLDER_LABEL).assertIsDisplayed()
    }

    // 開いているダイアログは、窓の幅の区分が替わっても閉じず、入れた文も残る。確定すると、ダイアログを開いた項目に書く。
    // 回帰の見張り。ダイアログは窓の幅の区分の外に出すので、AppBar とリストとツリーを作り直して移しても（MainScreenParts を
    // 使わなくても）保たれ、作り直さずに移すことは判別しない
    @Test
    fun dialogStaysOpenAcrossLayouts() = runRotatingTest(LANDSCAPE) { fixture, window ->
        itemRow(1).performTouchInput { longClick() }
        waitForIdle()
        tapText(RENAME_ITEM_LABEL)
        onNode(hasSetTextAction()).performTextClearance()
        onNode(hasSetTextAction()).performTextInput(TYPED_TITLE)

        rotate(window, PORTRAIT)
        onNode(hasSetTextAction()).assert(hasText(TYPED_TITLE))
        rotate(window, LANDSCAPE)
        onNode(hasSetTextAction()).assert(hasText(TYPED_TITLE))

        onNodeWithText(CHANGE_LABEL).performClick()
        waitForIdle()
        assertEquals(listOf(ROOT_ITEMS[0].copy(title = TYPED_TITLE), ROOT_ITEMS[1]), fixture.storedItems(ROOT_ID))
    }
}
