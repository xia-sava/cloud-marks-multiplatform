package to.sava.cloudmarksmultiplatform.browse

import to.sava.cloudmarksmultiplatform.sync.item
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 行の高さとして使う、画素の数。 */
private const val HEIGHT = 40f

/** 上の端の帯の中の位置。 */
private const val TOP_Y = 5f

/** 真ん中の帯の中の位置。 */
private const val MIDDLE_Y = 20f

/** 下の端の帯の中の位置。 */
private const val BOTTOM_Y = 35f

/** リストの項目の行の、上半分の中の位置。 */
private const val UPPER_HALF_Y = 15f

/** リストの項目の行の、下半分の中の位置。 */
private const val LOWER_HALF_Y = 25f

/** ルートフォルダ → A（→ A1（→ A1A）、A2）、B のツリーを読み、すべてのノードを開いた状態。開いているフォルダはルートフォルダ。 */
private fun fullyExpanded(): BrowseState = browseWithTree().let { it.copy(expandedFolders = checkNotNull(it.tree).state.folders.keys) }

/** [browse] のツリーの行。 */
private fun rowsOf(browse: BrowseState): List<FolderTreeRow> = flattenFolderTree(checkNotNull(browse.tree).state, browse.expandedFolders)

/** A1 を開き、その項目として item(1)〜item(4) を読んだ状態。 */
private fun browseWithFourItemsInA1(): BrowseState =
    browseWithTree(loadedTree(loadedItems = mapOf(A1 to (1..4).map { item(it) }))).openFolder(A1)

class MoveDropTest {
    // 上下の端は、それぞれ行の高さの 1/4。境目ちょうどは、上の端の境目は真ん中、下の端の境目は下の端に入る
    @Test
    fun edgeBandsAreQuarterOfRowHeight() {
        val cases = mapOf(
            0f to RowBand.TOP,
            9.9f to RowBand.TOP,
            10f to RowBand.MIDDLE,
            29.9f to RowBand.MIDDLE,
            30f to RowBand.BOTTOM,
            39.9f to RowBand.BOTTOM,
        )

        assertEquals(cases, cases.keys.associateWith { edgeBandOf(it, HEIGHT) })
    }

    // 項目の行は、上半分と下半分に分ける。真ん中ちょうどは下半分に入る
    @Test
    fun halfBandsSplitRowInTwo() {
        val cases = mapOf(0f to RowBand.TOP, 19.9f to RowBand.TOP, 20f to RowBand.BOTTOM, 39.9f to RowBand.BOTTOM)

        assertEquals(cases, cases.keys.associateWith { halfBandOf(it, HEIGHT) })
    }

    // ツリーでフォルダを落とすと、上の端はその行のフォルダの前、真ん中はその中の末尾、下の端は（開いていて子のある行なので）その行の
    // フォルダの先頭の子の前。線はその行の端に引き、上の端はその行の深さ、下の端は子の深さで字下げする
    @Test
    fun folderDropOnTreeRowBands() {
        val browse = fullyExpanded()
        val rows = rowsOf(browse)
        val subject = MoveSubject.Folder(B)
        val cases = listOf(
            Triple("A の上の端", TOP_Y, MoveDrop(MoveDestination.At(ROOT, A), DropRow.TreeFolder(A), DropLine(RowEdge.TOP, depth = 1))),
            Triple("A の真ん中", MIDDLE_Y, MoveDrop(MoveDestination.Into(A), DropRow.TreeFolder(A), line = null)),
            Triple("A の下の端", BOTTOM_Y, MoveDrop(MoveDestination.At(A, A1), DropRow.TreeFolder(A), DropLine(RowEdge.BOTTOM, depth = 2))),
        )
        cases.forEach { (name, y, expected) ->
            assertEquals(expected, browse.treeDrop(rows, A, y, HEIGHT, subject), name)
        }
    }

    // ツリーの行の下の端は、開いていて子のある行なら、その行のフォルダの先頭の子の前で、線は子の深さ。
    // 閉じている行と子の無い行なら、その行のフォルダの次の兄弟の前（最後なら親の子の末尾）で、線はその行の深さ。線はどれもその行の下端に引く
    @Test
    fun bottomEdgeDependsOnWhetherRowIsOpen() {
        val expanded = fullyExpanded()
        val expandedRows = rowsOf(expanded)
        val cases = listOf(
            // 開いていて子のある A1：A1 の先頭の子 A1A の前
            Triple(
                "開いている A1",
                expanded.treeDrop(expandedRows, A1, BOTTOM_Y, HEIGHT, MoveSubject.Folder(B)),
                MoveDrop(MoveDestination.At(A1, A1A), DropRow.TreeFolder(A1), DropLine(RowEdge.BOTTOM, depth = 3)),
            ),
            // 子の無い A1A（A1 の最後の子）：A1 の子の末尾
            Triple(
                "子の無い A1A",
                expanded.treeDrop(expandedRows, A1A, BOTTOM_Y, HEIGHT, MoveSubject.Folder(B)),
                MoveDrop(MoveDestination.At(A1, null), DropRow.TreeFolder(A1A), DropLine(RowEdge.BOTTOM, depth = 3)),
            ),
            // 子の無い B（ルートフォルダの最後の子）：ルートフォルダの子の末尾
            Triple(
                "子の無い B",
                expanded.treeDrop(expandedRows, B, BOTTOM_Y, HEIGHT, MoveSubject.Folder(A1A)),
                MoveDrop(MoveDestination.At(ROOT, null), DropRow.TreeFolder(B), DropLine(RowEdge.BOTTOM, depth = 1)),
            ),
        )
        cases.forEach { (name, actual, expected) -> assertEquals(expected, actual, name) }

        // 閉じている A（子はある）：A の次の兄弟 B の前
        val closed = browseWithTree()
        assertEquals(
            MoveDrop(MoveDestination.At(ROOT, B), DropRow.TreeFolder(A), DropLine(RowEdge.BOTTOM, depth = 1)),
            closed.treeDrop(rowsOf(closed), A, BOTTOM_Y, HEIGHT, MoveSubject.Folder(A1)),
        )
    }

    // 開いている A の最後の子 A2 の下の端と、A の次の兄弟 B の上の端は、同じ行の境目でも落とす先が違う。
    // A2 の下の端は A の子の末尾（A2 の深さの線）、B の上の端はルートフォルダの B の前（B の深さの線）
    @Test
    fun sameGapIsToldApartByEdge() {
        val browse = fullyExpanded()
        val rows = rowsOf(browse)
        val subject = MoveSubject.Folder(A1A)

        assertEquals(
            MoveDrop(MoveDestination.At(A, null), DropRow.TreeFolder(A2), DropLine(RowEdge.BOTTOM, depth = 2)),
            browse.treeDrop(rows, A2, BOTTOM_Y, HEIGHT, subject),
        )
        assertEquals(
            MoveDrop(MoveDestination.At(ROOT, B), DropRow.TreeFolder(B), DropLine(RowEdge.TOP, depth = 1)),
            browse.treeDrop(rows, B, TOP_Y, HEIGHT, subject),
        )
    }

    // 自分の行の上の端と、すぐ後ろの兄弟の上の端と、親の下の端（親の先頭の子の前で、自分が先頭）は、並びが変わらないので落とせない。
    // 開いていて子のある自分の行の下の端（自分の先頭の子の前）と、自分と子孫の行の真ん中には、自分の中へ移すことになるので落とせない。
    // 子の無い自分の行の下の端（自分が最後の子）も、並びが変わらないので落とせない
    @Test
    fun treeDropThatKeepsOrderOrMovesIntoItselfIsRefused() {
        val browse = fullyExpanded()
        val rows = rowsOf(browse)
        val subject = MoveSubject.Folder(A1)
        val cases = listOf(
            "自分の上の端" to (A1 to TOP_Y),
            "自分の下の端（自分の先頭の子の前）" to (A1 to BOTTOM_Y),
            "すぐ後ろの兄弟の上の端" to (A2 to TOP_Y),
            "親の下の端（親の先頭の子の前）" to (A to BOTTOM_Y),
            "自分の真ん中" to (A1 to MIDDLE_Y),
            "子の真ん中" to (A1A to MIDDLE_Y),
            "子の上の端（新しい親が自分）" to (A1A to TOP_Y),
            "いまの親の真ん中" to (A to MIDDLE_Y),
        )
        cases.forEach { (name, case) ->
            val (folderId, y) = case
            assertNull(browse.treeDrop(rows, folderId, y, HEIGHT, subject), name)
        }
        assertNull(browse.treeDrop(rows, A2, BOTTOM_Y, HEIGHT, MoveSubject.Folder(A2)), "子の無い自分の下の端")
        // 前の兄弟の上の端なら、並びが変わる
        assertEquals(MoveDestination.At(A, A1), browse.treeDrop(rows, A1, TOP_Y, HEIGHT, MoveSubject.Folder(A2))?.destination)
    }

    // ルートフォルダの行は、どこに落としても中へ。項目は、ツリーの行のどこに落としても中へ
    @Test
    fun rootRowAndItemsDropInto() {
        val browse = fullyExpanded()
        val rows = rowsOf(browse)
        val items = MoveSubject.Items(A1, listOf(item(1).id))

        listOf(TOP_Y, MIDDLE_Y, BOTTOM_Y).forEach { y ->
            assertEquals(
                MoveDrop(MoveDestination.Into(ROOT), DropRow.TreeFolder(ROOT), line = null),
                browse.treeDrop(rows, ROOT, y, HEIGHT, MoveSubject.Folder(A1)),
                "ルートフォルダの行 $y",
            )
            assertEquals(
                MoveDrop(MoveDestination.Into(B), DropRow.TreeFolder(B), line = null),
                browse.treeDrop(rows, B, y, HEIGHT, items),
                "項目 $y",
            )
        }
    }

    // ツリーの最後の行より下の空いたところへフォルダを落とすと、ルートフォルダの子の末尾へ並べ替える（どの深さのフォルダでも）。
    // 線は最後の行の下端に、最後の行の深さによらず、ルートフォルダの子の深さ（1）で引く。ルートフォルダの子の末尾にいるフォルダは、並びが変わらないので落とせない
    @Test
    fun folderDropBelowTreeRowsMovesToEndOfRootChildren() {
        val browse = fullyExpanded()
        val rows = rowsOf(browse)
        val line = DropLine(RowEdge.BOTTOM, depth = 1)

        listOf(A, A1, A1A).forEach { folderId ->
            assertEquals(
                MoveDrop(MoveDestination.At(ROOT, null), DropRow.TreeFolder(B), line),
                browse.treeEndDrop(rows, MoveSubject.Folder(folderId)),
                folderId,
            )
        }
        assertNull(browse.treeEndDrop(rows, MoveSubject.Folder(B)))
    }

    // ツリーの最後の行より下の空いたところへ項目を落とすと、ルートフォルダの中（ルートフォルダの行を強調）。
    // いま開いているフォルダがルートフォルダなら、そこから移す先にならないので落とせない
    @Test
    fun itemDropBelowTreeRowsMovesIntoRoot() {
        val inA1 = fullyExpanded().openFolder(A1)
        val items = MoveSubject.Items(A1, listOf(item(1).id))

        assertEquals(
            MoveDrop(MoveDestination.Into(ROOT), DropRow.TreeFolder(ROOT), line = null),
            inA1.treeEndDrop(rowsOf(inA1), items),
        )
        val inRoot = fullyExpanded()
        assertNull(inRoot.treeEndDrop(rowsOf(inRoot), MoveSubject.Items(ROOT, listOf(item(1).id))))
    }

    // ツリーを読めていないか、行が無いときは、落とす先にしない
    @Test
    fun dropBelowTreeRowsWithoutTreeOrRowsIsRefused() {
        assertNull(BrowseState().treeEndDrop(emptyList(), MoveSubject.Folder(A)))
        val browse = fullyExpanded()
        assertNull(browse.treeEndDrop(emptyList(), MoveSubject.Folder(A)))
    }

    // リストの項目の行に項目を落とすと、上半分はその項目の前、下半分は次の項目の前（最後なら末尾）へ並べ替える。線はその行の端で、字下げしない
    @Test
    fun itemDropOnListItemRowReorders() {
        val browse = browseWithFourItemsInA1()
        val subject = MoveSubject.Items(A1, listOf(item(1).id))
        val cases = listOf(
            Triple(3 to UPPER_HALF_Y, MoveDestination.At(A1, item(3).id), RowEdge.TOP),
            Triple(3 to LOWER_HALF_Y, MoveDestination.At(A1, item(4).id), RowEdge.BOTTOM),
            Triple(4 to LOWER_HALF_Y, MoveDestination.At(A1, null), RowEdge.BOTTOM),
        )
        cases.forEach { (case, destination, edge) ->
            val (n, y) = case
            val row = DropRow.ListItem(item(n).id)
            assertEquals(MoveDrop(destination, row, DropLine(edge, depth = 0)), browse.listDrop(row, y, HEIGHT, subject), "$n $y")
        }
    }

    // 移す項目の前後（自分の行、すぐ前の行の下半分、すぐ後ろの行の上半分）へは、並びが変わらないので落とせない。
    // 選んだ複数の項目の中の行に落としても、移すものの前を指さず、その後ろの移さない項目の前になる
    @Test
    fun itemDropThatKeepsOrderIsRefused() {
        val browse = browseWithFourItemsInA1()
        val second = MoveSubject.Items(A1, listOf(item(2).id))
        val cases = listOf(
            "自分の上半分" to (2 to UPPER_HALF_Y),
            "自分の下半分" to (2 to LOWER_HALF_Y),
            "すぐ前の行の下半分" to (1 to LOWER_HALF_Y),
            "すぐ後ろの行の上半分" to (3 to UPPER_HALF_Y),
        )
        cases.forEach { (name, case) ->
            val (n, y) = case
            assertNull(browse.listDrop(DropRow.ListItem(item(n).id), y, HEIGHT, second), name)
        }

        val firstAndThird = MoveSubject.Items(A1, listOf(item(1).id, item(3).id))
        assertEquals(
            MoveDestination.At(A1, item(4).id),
            browse.listDrop(DropRow.ListItem(item(3).id), UPPER_HALF_Y, HEIGHT, firstAndThird)?.destination,
        )
    }

    // リストの子のフォルダの行にフォルダを落とすと、上下の端で子のフォルダどうしを並べ替え、真ん中でその中へ。項目はどこでも中へ。
    // フォルダは項目の行に落とせない
    @Test
    fun dropOnListFolderRow() {
        val browse = browseWithTree().openFolder(A)
        val a2Row = DropRow.ListFolder(A2)
        val a1Row = DropRow.ListFolder(A1)

        assertEquals(
            MoveDrop(MoveDestination.At(A, A1), a1Row, DropLine(RowEdge.TOP)),
            browse.listDrop(a1Row, TOP_Y, HEIGHT, MoveSubject.Folder(A2)),
        )
        assertEquals(
            MoveDrop(MoveDestination.At(A, null), a2Row, DropLine(RowEdge.BOTTOM)),
            browse.listDrop(a2Row, BOTTOM_Y, HEIGHT, MoveSubject.Folder(A1)),
        )
        assertEquals(
            MoveDrop(MoveDestination.Into(A2), a2Row, line = null),
            browse.listDrop(a2Row, MIDDLE_Y, HEIGHT, MoveSubject.Folder(A1)),
        )
        // A2 の下の端は A2 の後ろ（末尾）で、A2 自身は末尾にあるので並びが変わらない
        assertNull(browse.listDrop(a2Row, BOTTOM_Y, HEIGHT, MoveSubject.Folder(A2)))
        listOf(TOP_Y, MIDDLE_Y, BOTTOM_Y).forEach { y ->
            assertEquals(
                MoveDestination.Into(A2),
                browse.listDrop(a2Row, y, HEIGHT, MoveSubject.Items(A1, listOf(item(1).id)))?.destination,
                "項目 $y",
            )
        }
        val itemsBrowse = browseWithFourItemsInA1()
        assertNull(itemsBrowse.listDrop(DropRow.ListItem(item(1).id), UPPER_HALF_Y, HEIGHT, MoveSubject.Folder(B)))
    }

    // 見えている行が状態の並びに無いとき（ツリーの行の並びに無いフォルダ、開いているフォルダの子のフォルダや読めた項目に無い行）は、
    // 例外にせず、落とす先を null にする
    @Test
    fun rowMissingFromStateDropsNowhere() {
        val items = browseWithFourItemsInA1()
        val itemSubject = MoveSubject.Items(A1, listOf(item(1).id))
        val inA = browseWithTree().openFolder(A)
        // A を閉じたツリーの行。A の子 A1 の行は無い
        val closed = browseWithTree()
        val cases = listOf(
            "読めた項目に無い項目の行の上半分" to { items.listDrop(DropRow.ListItem(item(9).id), UPPER_HALF_Y, HEIGHT, itemSubject) },
            "読めた項目に無い項目の行の下半分" to { items.listDrop(DropRow.ListItem(item(9).id), LOWER_HALF_Y, HEIGHT, itemSubject) },
            "子のフォルダに無いフォルダの行の上の端" to { inA.listDrop(DropRow.ListFolder(B), TOP_Y, HEIGHT, MoveSubject.Folder(A2)) },
            "子のフォルダに無いフォルダの行の下の端" to { inA.listDrop(DropRow.ListFolder(B), BOTTOM_Y, HEIGHT, MoveSubject.Folder(A2)) },
            "ツリーに無いフォルダの行" to { closed.treeDrop(rowsOf(closed), "unknown", TOP_Y, HEIGHT, MoveSubject.Folder(B)) },
            "ツリーの行の並びに無いフォルダの行" to { closed.treeDrop(rowsOf(closed), A1, TOP_Y, HEIGHT, MoveSubject.Folder(B)) },
        )
        cases.forEach { (name, drop) -> assertNull(drop(), name) }
    }
}
