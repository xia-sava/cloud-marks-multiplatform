package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.toSize
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.sync.item
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals

/** [this] の要素の、根の座標の範囲。窓からはみ出した部分も含める（窓の端で切らない）。 */
private fun SemanticsNodeInteraction.unclippedBounds(): Rect =
    fetchSemanticsNode().let { node -> Rect(node.positionInRoot, node.size.toSize()) }

/** ツリーの枠（ツリーの行を並べる枠）の、根の座標の範囲。ルートフォルダの行を含む、スクロールする枠。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.treePaneBounds(): Rect =
    onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText(ROOT_FOLDER_LABEL))).unclippedBounds()

/** リストの枠の、根の座標の範囲。項目の行を含む、スクロールする枠。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.listPaneBounds(): Rect =
    onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText(item(1).title))).unclippedBounds()

/**
 * テストの画面の幅（画素。テストの画面の密度は 1 なので dp と同じ）。ボタンを押したあとはツールチップの窓が別の根として重なるので、
 * 最初の根（窓の中身）の幅を使う。
 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.windowWidth(): Float = onAllNodes(isRoot())[0].fetchSemanticsNode().size.width.toFloat()

/** ツリーの枠とリストの境目の真ん中を、マウスで掴んで、x が [toX] の位置まで横へ動かして離す。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.dragPaneDivider(toX: Float) {
    val tree = treePaneBounds()
    val from = Offset(tree.right + PANE_DIVIDER_HANDLE_WIDTH.value / 2, tree.center.y)
    dragWithMouse(from, Offset(toX, from.y))
}

/** ツリーの枠が左端から [treeWidth] の幅で、その右に境目、残りの幅すべてがリストの枠か。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.assertPanes(treeWidth: Float) {
    val tree = treePaneBounds()
    val list = listPaneBounds()
    assertEquals(0f, tree.left)
    assertEquals(treeWidth, tree.width)
    assertEquals(tree.right + PANE_DIVIDER_HANDLE_WIDTH.value, list.left)
    assertEquals(windowWidth(), list.right)
}

@OptIn(ExperimentalTestApi::class)
class WideMainBodyScreenTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // 広い画面では、初めは左にツリーの枠（280dp）、その右に境目（8dp）を置き、残りの幅すべてをリストの枠にする
    @Test
    fun initialPanes() = runCloudMarksScreenTest(temporaryFolder) {
        assertPanes(treeWidth = DEFAULT_TREE_PANE_WIDTH.value)
    }

    // 境目を左へドラッグしても、ツリーの枠は下限（160dp）より狭くしない。リストの枠は、境目の右から右端まで広がる
    @Test
    fun draggingDividerLeftStopsAtMinimum() = runCloudMarksScreenTest(temporaryFolder) {
        dragPaneDivider(toX = 0f)

        assertPanes(treeWidth = MIN_TREE_PANE_WIDTH.value)
    }

    // 境目を右へドラッグしても、ツリーの枠は上限（本体の幅の 60%）より広くしない
    @Test
    fun draggingDividerRightStopsAtMaximum() = runCloudMarksScreenTest(temporaryFolder) {
        dragPaneDivider(toX = windowWidth() - 1)

        assertPanes(treeWidth = (windowWidth() * MAX_TREE_PANE_FRACTION).roundToInt().toFloat())
    }

    // ハンバーガーでツリーの枠を消すと、境目も消し、リストの枠を左端から右端までの幅にする。もう一度押すと、元の幅で出す
    @Test
    fun hidingTreePaneWidensList() = runCloudMarksScreenTest(temporaryFolder) {
        onNodeWithContentDescription(TREE_PANE_DESCRIPTION).performClick()
        waitForIdle()

        onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText(ROOT_FOLDER_LABEL))).assertDoesNotExist()
        val list = listPaneBounds()
        assertEquals(0f, list.left)
        assertEquals(windowWidth(), list.right)

        onNodeWithContentDescription(TREE_PANE_DESCRIPTION).performClick()
        waitForIdle()

        assertPanes(treeWidth = DEFAULT_TREE_PANE_WIDTH.value)
    }
}
