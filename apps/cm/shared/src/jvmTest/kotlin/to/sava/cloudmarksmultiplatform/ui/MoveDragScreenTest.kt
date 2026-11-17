package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.app.ControllerFixture
import to.sava.cloudmarksmultiplatform.settings.DeviceSettings
import to.sava.cloudmarksmultiplatform.settings.DeviceSettingsFile
import to.sava.cloudmarksmultiplatform.settings.DisplayScale
import to.sava.cloudmarksmultiplatform.storage.FolderEntry
import to.sava.cloudmarksmultiplatform.storage.Manifest
import to.sava.cloudmarksmultiplatform.sync.A_ITEMS
import to.sava.cloudmarksmultiplatform.sync.FOLDER_A
import to.sava.cloudmarksmultiplatform.sync.FOLDER_B
import to.sava.cloudmarksmultiplatform.sync.ROOT_ID
import to.sava.cloudmarksmultiplatform.sync.fileId
import to.sava.cloudmarksmultiplatform.sync.folderId
import to.sava.cloudmarksmultiplatform.sync.initialManifest
import to.sava.cloudmarksmultiplatform.sync.item
import to.sava.cloudmarksmultiplatform.sync.replaceItemsAsOtherWriter
import to.sava.cloudmarksmultiplatform.sync.seed
import to.sava.cloudmarksmultiplatform.sync.storedManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** ルートフォルダの子の 3 つ目のフォルダ。 */
private val FOLDER_C = folderId(3)

/** A の子のフォルダ。項目を 3 つ持つ。 */
private val CHILD_OF_A = folderId(4)

/** [CHILD_OF_A] の項目の数。 */
private const val CHILD_OF_A_COUNT = 3

/** 並べ替えを確かめるための、ルートフォルダの 5 つの項目。 */
private val FIVE_ITEMS = (1..5).map { item(it) }

/** AppBar の中の、根の座標での高さ（画素）。AppBar は画面の上端にある。 */
private const val APP_BAR_Y = 10f

/**
 * ルートフォルダ（項目 2 つ）→ A（項目 1 つ）（→ A1（項目 3 つ））、B、C のマニフェスト。A1 の項目ファイルは置かない
 * （ルートフォルダを開いているあいだは読まない）。
 */
private fun manifestWithThreeFolders(): Manifest =
    initialManifest().let { manifest ->
        val (root, a, b) = manifest.folders
        manifest.copy(
            folders = listOf(
                root,
                a,
                FolderEntry(CHILD_OF_A, FOLDER_A, "A1", fileId(40), CHILD_OF_A_COUNT),
                b,
                FolderEntry(FOLDER_C, ROOT_ID, "C", null, 0),
            ),
        )
    }

/** [manifestWithThreeFolders] を置き、ルートフォルダの項目を [FIVE_ITEMS] にする、起動の前の用意。 */
private val threeFoldersAndFiveItems: (ControllerFixture) -> Unit = {
    it.store.seed(manifestWithThreeFolders())
    it.store.replaceItemsAsOtherWriter(ROOT_ID, fileId(30), FIVE_ITEMS)
}

/** テストの画面（幅 1024）を、狭い画面の区分（幅 600dp 未満）にする表示の大きさ。幅が 512dp になる。 */
private val NARROW_SCALE = checkNotNull(DisplayScale.ofPercent(200))

/**
 * リストの下端からの、ポインターを置く距離（画素。テストの画面の密度は 1 なので dp と同じ）。
 * ドラッグしているあいだに枠を自動でスクロールさせる、枠の下の端の帯（48dp）の中。
 */
private const val BOTTOM_BAND_INSET = 8f

/** B の子のフォルダ。 */
private val CHILD_OF_B = folderId(5)

/** ルートフォルダ → A、B（→ B1）のマニフェストを置く、起動の前の用意。 */
private val folderBWithChild: (ControllerFixture) -> Unit = {
    it.store.seed(initialManifest().let { manifest -> manifest.copy(folders = manifest.folders + FolderEntry(CHILD_OF_B, FOLDER_B, "B1", null, 0)) })
}

/** ツリーの最後の行の下端から、空いたところへポインターを置く距離（画素）。ツリーの行の高さより長い。 */
private const val BELOW_TREE_ROWS_DISTANCE = 100f

/** 時計を進めるときの、1 フレームの長さ（ミリ秒）。 */
private const val FRAME_MILLIS = 16L

/** 自動でスクロールしていることを確かめるために、ポインターを帯に置いてから進めるフレームの数。 */
private const val SCROLLING_FRAMES = 5

/** 自動のスクロールが止まるのを待つために進めるフレームの数。 */
private const val SETTLING_FRAMES = 3

/** 自動のスクロールが続いていないことを確かめるために進める時間（ミリ秒）。 */
private const val LONG_WAIT_MILLIS = 1_000L

/** ドロワーに覆われたリストの上へポインターを置く、窓の右端からの距離（画素）。ドロワーの幅より内側。 */
private const val RIGHT_EDGE_INSET = 40f

/** 時計を [frames] フレームだけ進める。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.advanceFrames(frames: Int) = mainClock.advanceTimeBy(FRAME_MILLIS * frames)

/** 長いリストの項目（項目 1 を除く）の行の上端。リストに無ければ null。項目 1 は掴んでいると札にも出るので除く。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.rowTops(): List<Float?> = (2..LONG_ROOT_ITEMS.size).map { itemRowTop(it) }

/** ドロワーの中のツリーの、名前が [name] のフォルダの行。ドロワーは、メニューの領域の題（paneTitle）を持つ。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.drawerTreeRow(name: String) =
    onNode(hasText(name) and hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)))

@OptIn(ExperimentalTestApi::class)
class MoveDragScreenTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** 場合の名前、ドラッグの仕方、そのあとの確かめ方。 */
    private data class DragCase(
        val name: String,
        val drag: ComposeUiTest.() -> Unit,
        val check: (ControllerFixture) -> Unit,
    )

    /** [cases] のそれぞれを、[threeFoldersAndFiveItems] で用意した新しい画面で試す。 */
    private fun runDragCases(cases: List<DragCase>) {
        cases.forEach { (name, drag, check) ->
            runCloudMarksScreenTest(temporaryFolder, arrange = threeFoldersAndFiveItems) { fixture ->
                drag()

                assertEquals(fixture.store.storedManifest(), fixture.state.browse.tree?.manifest, name)
                check(fixture)
            }
        }
    }

    // リストの子のフォルダの行には、子孫のフォルダの項目まで足した数を出す（A は自分の 1 つと A1 の 3 つ）
    @Test
    fun childFolderRowShowsCountWithDescendants() = runCloudMarksScreenTest(temporaryFolder, arrange = threeFoldersAndFiveItems) {
        onNodeWithText("${A_ITEMS.size + CHILD_OF_A_COUNT} 件").assertIsDisplayed()
        onAllNodesWithText("0 件").assertCountEquals(2)
    }

    // フォルダを開くと、ツリーでそのフォルダ自身も開き、子のフォルダの行が出る（ツリーの行を押しても、リストの子のフォルダの行を押しても）
    @Test
    fun openingFolderExpandsItInTree() {
        val opens: List<Pair<String, ComposeUiTest.() -> Unit>> = listOf(
            "ツリーの行" to { treeRow("A").performClick() },
            "リストの子のフォルダの行" to { listFolderRow("A").performClick() },
        )
        opens.forEach { (name, open) ->
            runCloudMarksScreenTest(temporaryFolder, arrange = threeFoldersAndFiveItems) { fixture ->
                onAllNodesWithText("A1").assertCountEquals(0)

                open()
                waitForIdle()

                assertEquals(FOLDER_A, fixture.state.browse.currentFolderId, name)
                assertTrue(FOLDER_A in fixture.state.browse.expandedFolders, name)
                // ツリーの行と、リストの子のフォルダの行
                onAllNodesWithText("A1").assertCountEquals(2)
            }
        }
    }

    // ツリーのフォルダの行の上の端に落とすとその前へ、下の端に落とすと次の兄弟の前へ並べ替え、真ん中に落とすとその中の末尾へ移す
    @Test
    fun draggingFolderOntoTreeRowBands() = runDragCases(
        listOf(
            DragCase("C を A の上の端へ", { dragWithMouse(treeRow("C").spot(RowSpot.MIDDLE), treeRow("A").spot(RowSpot.TOP_EDGE)) }) {
                assertEquals(listOf(FOLDER_C, FOLDER_A, FOLDER_B), it.storedChildren(ROOT_ID))
            },
            DragCase("A を B の下の端へ", { dragWithMouse(treeRow("A").spot(RowSpot.MIDDLE), treeRow("B").spot(RowSpot.BOTTOM_EDGE)) }) {
                assertEquals(listOf(FOLDER_B, FOLDER_A, FOLDER_C), it.storedChildren(ROOT_ID))
            },
            DragCase("A を C の下の端へ（末尾）", { dragWithMouse(treeRow("A").spot(RowSpot.MIDDLE), treeRow("C").spot(RowSpot.BOTTOM_EDGE)) }) {
                assertEquals(listOf(FOLDER_B, FOLDER_C, FOLDER_A), it.storedChildren(ROOT_ID))
            },
            DragCase("C を B の真ん中へ", { dragWithMouse(treeRow("C").spot(RowSpot.MIDDLE), treeRow("B").spot(RowSpot.MIDDLE)) }) {
                assertEquals(listOf(FOLDER_A, FOLDER_B), it.storedChildren(ROOT_ID))
                assertEquals(listOf(FOLDER_C), it.storedChildren(FOLDER_B))
            },
        ),
    )

    // 並びが変わらないところ（自分のすぐ後ろの兄弟の上の端）に落としても、何も書かない
    @Test
    fun draggingFolderNextToItselfWritesNothing() =
        runCloudMarksScreenTest(temporaryFolder, arrange = threeFoldersAndFiveItems) { fixture ->
            val manifestBefore = fixture.store.storedManifest()

            dragWithMouse(treeRow("A").spot(RowSpot.MIDDLE), treeRow("B").spot(RowSpot.TOP_EDGE))

            assertEquals(manifestBefore, fixture.store.storedManifest())
            assertEquals(null, fixture.state.write.outcome)
        }

    // リストの項目を、項目の行どうしのあいだへ落とすと並べ替える（行の上半分はその項目の前、下半分は次の項目の前）。
    // 選んでいる複数の項目を掴めば、まとめて並べ替える
    @Test
    fun draggingItemsBetweenListRowsReorders() = runDragCases(
        listOf(
            DragCase("1 を 3 の下半分へ", { dragWithMouse(itemRow(1).spot(RowSpot.MIDDLE), itemRow(3).spot(RowSpot.BOTTOM_EDGE)) }) {
                assertEquals(listOf(2, 3, 1, 4, 5).map { n -> item(n) }, it.storedItems(ROOT_ID))
            },
            DragCase("5 を 2 の上半分へ", { dragWithMouse(itemRow(5).spot(RowSpot.MIDDLE), itemRow(2).spot(RowSpot.TOP_EDGE)) }) {
                assertEquals(listOf(1, 5, 2, 3, 4).map { n -> item(n) }, it.storedItems(ROOT_ID))
            },
            DragCase("1 を 5 の下半分へ（末尾）", { dragWithMouse(itemRow(1).spot(RowSpot.MIDDLE), itemRow(5).spot(RowSpot.BOTTOM_EDGE)) }) {
                assertEquals(listOf(2, 3, 4, 5, 1).map { n -> item(n) }, it.storedItems(ROOT_ID))
            },
            DragCase(
                "選んだ 1 と 2 を 4 の下半分へ",
                {
                    clickItemRowWithKey(1, Key.CtrlLeft)
                    clickItemRowWithKey(2, Key.ShiftLeft)
                    dragWithMouse(itemRow(2).spot(RowSpot.MIDDLE), itemRow(4).spot(RowSpot.BOTTOM_EDGE))
                },
            ) {
                assertEquals(listOf(3, 4, 1, 2, 5).map { n -> item(n) }, it.storedItems(ROOT_ID))
            },
        ),
    )

    // リストの子のフォルダの行を掴んで、子のフォルダの行の上下の端へ落とすと並べ替え、真ん中へ落とすとその中へ移す。
    // 項目を子のフォルダの行へ落とすと、そのフォルダの項目の末尾へ移す
    @Test
    fun draggingOntoListFolderRows() = runDragCases(
        listOf(
            DragCase(
                "C を A の上の端へ",
                { dragWithMouse(listFolderRow("C").spot(RowSpot.MIDDLE), listFolderRow("A").spot(RowSpot.TOP_EDGE)) },
            ) {
                assertEquals(listOf(FOLDER_C, FOLDER_A, FOLDER_B), it.storedChildren(ROOT_ID))
            },
            DragCase(
                "A を B の下の端へ",
                { dragWithMouse(listFolderRow("A").spot(RowSpot.MIDDLE), listFolderRow("B").spot(RowSpot.BOTTOM_EDGE)) },
            ) {
                assertEquals(listOf(FOLDER_B, FOLDER_A, FOLDER_C), it.storedChildren(ROOT_ID))
            },
            DragCase("C を B の真ん中へ", { dragWithMouse(listFolderRow("C").spot(RowSpot.MIDDLE), listFolderRow("B").spot(RowSpot.MIDDLE)) }) {
                assertEquals(listOf(FOLDER_C), it.storedChildren(FOLDER_B))
            },
            DragCase("項目 2 を B の上の端へ", { dragWithMouse(itemRow(2).spot(RowSpot.MIDDLE), listFolderRow("B").spot(RowSpot.TOP_EDGE)) }) {
                assertEquals(listOf(item(2)), it.storedItems(FOLDER_B))
                assertEquals(listOf(1, 3, 4, 5).map { n -> item(n) }, it.storedItems(ROOT_ID))
            },
        ),
    )

    // ドラッグしているあいだは、移しているものの札（3 つの項目なら「3 件の項目」）を出す。落とすと消える
    @Test
    fun dragLabelIsShownWhileDragging() = runCloudMarksScreenTest(temporaryFolder, arrange = threeFoldersAndFiveItems) { fixture ->
        clickItemRowWithKey(1, Key.CtrlLeft)
        clickItemRowWithKey(3, Key.ShiftLeft)
        val label = draggedItemsLabel(3)
        // 落とせるところのない、AppBar の真ん中で離す
        val outside = Offset(onRoot().fetchSemanticsNode().size.width / 2f, APP_BAR_Y)

        onRoot().performMouseInput {
            moveTo(itemRow(2).spot(RowSpot.MIDDLE))
            press()
            moveBy(BEYOND_THRESHOLD)
            moveTo(itemRow(5).spot(RowSpot.MIDDLE))
        }
        waitForIdle()
        onNodeWithText(label).assertIsDisplayed()

        onRoot().performMouseInput {
            moveTo(outside)
            release()
        }
        waitForIdle()

        onAllNodesWithText(label).assertCountEquals(0)
        assertEquals(FIVE_ITEMS, fixture.storedItems(ROOT_ID))
    }

    // 狭い画面でドロワーを開き、ドロワーのツリーの行を別のツリーの行へドラッグすると、ドロワーの下に隠れたリストの行ではなく、
    // ツリーの行に落とす（C をツリーの A の真ん中へ落とすと、A の中へ移す。A のツリーの行の下には、リストの B の行が重なっている）
    @Test
    fun dragInDrawerDropsOnTreeRowOverHiddenList() = runCloudMarksScreenTest(
        temporaryFolder,
        arrange = {
            threeFoldersAndFiveItems(it)
            DeviceSettingsFile(it.appFiles.deviceSettingsFile) {}.write(DeviceSettings(displayScale = NARROW_SCALE))
        },
    ) { fixture ->
        onNodeWithContentDescription(TREE_PANE_DESCRIPTION).performClick()
        waitForIdle()
        assertFalse(fixture.state.browse.wideLayout)
        assertTrue(fixture.state.browse.drawerOpen)
        // ドロワーを開くと、ドロワーを閉じるボタンに焦点が移り、そのツールチップの窓が別の根として重なる。ドラッグは最初の根（窓の中身）へ渡す
        val windowRoot = onAllNodes(isRoot())[0]

        dragWithMouse(drawerTreeRow("C").spot(RowSpot.MIDDLE), drawerTreeRow("A").spot(RowSpot.MIDDLE), root = windowRoot)

        assertEquals(listOf(CHILD_OF_A, FOLDER_C), fixture.storedChildren(FOLDER_A))
        assertEquals(emptyList(), fixture.storedChildren(FOLDER_B))
    }

    // 長いリストで、項目を掴んでリストの下の端の帯へ動かすと、リストが下へスクロールし続ける。掴んだ行がスクロールで見えなくなっても
    // ドラッグは続き、スクロールして出てきた、画面の外だった位置へ並べ替えられる（先頭の項目を、最後の項目の下半分へ落として末尾へ）
    @Test
    fun draggingToListBottomEdgeScrollsAndReordersOffScreen() =
        runCloudMarksScreenTest(temporaryFolder, arrange = longRootItems) { fixture ->
            val last = LONG_ROOT_ITEMS.size
            onAllNodesWithText(item(last).title).assertCountEquals(0)
            val grab = itemRow(1).spot(RowSpot.MIDDLE)
            val bottomBand = Offset(grab.x, onRoot().fetchSemanticsNode().size.height - BOTTOM_BAND_INSET)

            onRoot().performMouseInput {
                moveTo(grab)
                press()
                moveBy(BEYOND_THRESHOLD)
                moveTo(bottomBand)
            }
            waitForIdle()
            // 掴んだ項目 1 のタイトルは札に出ているので、その次の行がスクロールで外れたことを確かめる
            onAllNodesWithText(item(2).title).assertCountEquals(0)
            val target = itemRow(last).spot(RowSpot.BOTTOM_EDGE)
            onRoot().performMouseInput {
                moveTo(target)
                release()
            }
            waitForIdle()

            assertEquals(LONG_ROOT_ITEMS.drop(1) + LONG_ROOT_ITEMS.first(), fixture.storedItems(ROOT_ID))
        }

    // B を開いてツリーの最後の行が B の子 B1 になっているとき、A をツリーの最後の行より下の空いたところへ落とすと、
    // ルートフォルダの子の末尾（B の後ろ）へ並べ替える。B の子は変わらない
    @Test
    fun draggingFolderBelowTreeRowsMovesAfterLastRootChild() = runCloudMarksScreenTest(temporaryFolder, arrange = folderBWithChild) { fixture ->
        treeRow("B").performClick()
        waitForIdle()
        assertEquals(listOf(FOLDER_A, FOLDER_B), fixture.storedChildren(ROOT_ID))
        val belowRows = treeRow("B1").spot(RowSpot.BOTTOM_EDGE) + Offset(0f, BELOW_TREE_ROWS_DISTANCE)

        dragWithMouse(treeRow("A").spot(RowSpot.MIDDLE), belowRows)

        assertEquals(listOf(FOLDER_B, FOLDER_A), fixture.storedChildren(ROOT_ID))
        assertEquals(listOf(CHILD_OF_B), fixture.storedChildren(FOLDER_B))
    }

    // 項目を掴んでリストの下の端の帯へ動かすと自動でスクロールする。そのあと、ポインターを帯の外へ動かすか離すと、
    // さらに時間を進めても行の位置は変わらない
    @Test
    fun autoScrollStopsWhenPointerLeavesBandOrIsReleased() {
        val stops: List<Pair<String, ComposeUiTest.() -> Unit>> = listOf(
            "帯の外へ動かす" to { onRoot().performMouseInput { moveTo(center) } },
            "離す" to { onRoot().performMouseInput { release() } },
        )
        stops.forEach { (name, stop) ->
            runCloudMarksScreenTest(temporaryFolder, arrange = longRootItems) {
                mainClock.autoAdvance = false
                val before = rowTops()
                val grab = itemRow(1).spot(RowSpot.MIDDLE)
                val bottomBand = Offset(grab.x, onRoot().fetchSemanticsNode().size.height - BOTTOM_BAND_INSET)

                onRoot().performMouseInput {
                    moveTo(grab)
                    press()
                    moveBy(BEYOND_THRESHOLD)
                    moveTo(bottomBand)
                }
                advanceFrames(SCROLLING_FRAMES)
                assertNotEquals(before, rowTops(), "$name: 帯の中ではスクロールする")

                stop()
                advanceFrames(SETTLING_FRAMES)
                val stopped = rowTops()
                mainClock.advanceTimeBy(LONG_WAIT_MILLIS)

                assertEquals(stopped, rowTops(), name)
            }
        }
    }

    // 狭い画面でドロワーを開いてドロワーのツリーの行を掴み、ドロワーに覆われたリストの下の端の帯へ動かしても、
    // 隠れたリストは自動でスクロールせず、そこへ落としても何も書かない
    @Test
    fun hiddenListDoesNotAutoScrollWhileDrawerIsOpen() = runCloudMarksScreenTest(
        temporaryFolder,
        arrange = {
            longRootItems(it)
            DeviceSettingsFile(it.appFiles.deviceSettingsFile) {}.write(DeviceSettings(displayScale = NARROW_SCALE))
        },
    ) { fixture ->
        onNodeWithContentDescription(TREE_PANE_DESCRIPTION).performClick()
        waitForIdle()
        assertTrue(fixture.state.browse.drawerOpen)
        val windowRoot = onAllNodes(isRoot())[0]
        val manifestBefore = fixture.store.storedManifest()
        val itemsBefore = fixture.storedItems(ROOT_ID)
        mainClock.autoAdvance = false
        val before = rowTops()
        val size = windowRoot.fetchSemanticsNode().size
        val overHiddenList = Offset(size.width - RIGHT_EDGE_INSET, size.height - BOTTOM_BAND_INSET)

        windowRoot.performMouseInput {
            moveTo(drawerTreeRow("A").spot(RowSpot.MIDDLE))
            press()
            moveBy(BEYOND_THRESHOLD)
            moveTo(overHiddenList)
        }
        advanceFrames(SCROLLING_FRAMES)
        mainClock.advanceTimeBy(LONG_WAIT_MILLIS)
        windowRoot.performMouseInput { release() }
        advanceFrames(SETTLING_FRAMES)

        assertEquals(before, rowTops())
        assertEquals(manifestBefore, fixture.store.storedManifest())
        assertEquals(itemsBefore, fixture.storedItems(ROOT_ID))
    }
}
