package to.sava.cloudmarksmultiplatform.browse

import to.sava.cloudmarksmultiplatform.sync.item
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A1 を開き、[clicks] の順に項目を選んだ状態。A1 の項目は [A1_ITEMS] に item(3) を足した 3 つ。 */
private fun browseWithSelection(vararg clicks: Pair<Int, SelectionMode>): BrowseState {
    val items = A1_ITEMS + item(3)
    val opened = browseWithTree(loadedTree(loadedItems = mapOf(A1 to items))).openFolder(A1)
    return clicks.fold(opened) { browse, (n, mode) -> browse.selectItem(item(n).id, mode) }
}

class MoveSubjectTest {
    private val tree = loadedTree().state

    // 選んでいる項目は、開いているフォルダの項目として、選んだ順ではなく表示の並び順に並べる
    @Test
    fun selectedItemsSubjectFollowsDisplayOrder() {
        val browse = browseWithSelection(3 to SelectionMode.SINGLE, 1 to SelectionMode.TOGGLE)

        assertEquals(MoveSubject.Items(A1, listOf(item(1).id, item(3).id)), browse.selectedItemsSubject)
    }

    // 掴んだ項目が選んでいる項目に含まれていれば、選んでいる項目すべてを表示の並び順に移す
    @Test
    fun grabbedSelectedItemMovesWholeSelection() {
        val browse = browseWithSelection(3 to SelectionMode.SINGLE, 1 to SelectionMode.RANGE)

        assertEquals(
            MoveSubject.Items(A1, listOf(item(1).id, item(2).id, item(3).id)),
            browse.draggedItems(item(2).id),
        )
    }

    // 掴んだ項目が選んでいる項目に含まれていなければ、選んでいる項目があってもなくても、掴んだ項目だけを移す
    @Test
    fun grabbedUnselectedItemMovesOnlyItself() {
        val cases = listOf(
            "ほかの項目を選んでいる" to browseWithSelection(1 to SelectionMode.SINGLE, 3 to SelectionMode.TOGGLE),
            "何も選んでいない" to browseWithSelection(),
        )
        cases.forEach { (name, browse) ->
            assertEquals(MoveSubject.Items(A1, listOf(item(2).id)), browse.draggedItems(item(2).id), name)
        }
    }

    // ツリーを読めていなければ（開いているフォルダが無ければ）、移す項目は無い
    @Test
    fun noSubjectWithoutOpenFolder() {
        assertNull(BrowseState().selectedItemsSubject)
        assertNull(BrowseState().draggedItems(item(1).id))
    }

    // 項目は、項目のあるフォルダのほかの、ツリーにあるどのフォルダへも移せる（ルートフォルダも含む）。
    // 項目のあるフォルダと、ツリーに無いフォルダへは移せない
    @Test
    fun itemsCanMoveToAnyOtherFolderInTree() {
        val items = MoveSubject.Items(A1, listOf(item(1).id))
        val expected = mapOf(
            A1 to false,
            ROOT to true,
            A to true,
            A1A to true,
            A2 to true,
            B to true,
            "unknown" to false,
        )

        assertEquals(expected, expected.keys.associateWith { items.canMoveTo(tree, it) })
    }

    // フォルダは、自身と子孫と、いまの親へは移せない。それ以外のツリーにあるフォルダへは移せる（祖先・兄弟とその子孫）
    @Test
    fun folderCannotMoveIntoItselfDescendantsOrCurrentParent() {
        val folder = MoveSubject.Folder(A1)
        val expected = mapOf(
            A1 to false,
            A1A to false,
            A to false,
            ROOT to true,
            A2 to true,
            B to true,
            "unknown" to false,
        )

        assertEquals(expected, expected.keys.associateWith { folder.canMoveTo(tree, it) })
    }

    // 親がルートフォルダのフォルダは、ルートフォルダへは移せない
    @Test
    fun folderUnderRootCannotMoveToRoot() {
        assertFalse(MoveSubject.Folder(B).canMoveTo(tree, ROOT))
        assertTrue(MoveSubject.Folder(B).canMoveTo(tree, A))
    }

    // 並べ替えた並びは、移すものを外してから、指定したものの前へ移す順に入れる。指定が無いか、移すものに含まれていれば末尾へ入れる
    @Test
    fun reorderedFollowsOperationPositionRule() {
        val order = listOf("1", "2", "3", "4", "5")
        val cases = listOf(
            Triple(listOf("1"), "4", listOf("2", "3", "1", "4", "5")),
            Triple(listOf("5"), "2", listOf("1", "5", "2", "3", "4")),
            Triple(listOf("4", "2"), "1", listOf("4", "2", "1", "3", "5")),
            Triple(listOf("2"), null, listOf("1", "3", "4", "5", "2")),
            // 前に置くものが移すものに含まれていれば末尾
            Triple(listOf("2", "3"), "3", listOf("1", "4", "5", "2", "3")),
            // 並びに無いもの（別のフォルダから移す項目）は、前に置くものの前へ足す
            Triple(listOf("x"), "1", listOf("x", "1", "2", "3", "4", "5")),
        )
        cases.forEach { (moved, before, expected) ->
            assertEquals(expected, reordered(order, moved, before), "$moved の前: $before")
        }
    }

    // 位置を指定した移動は、項目のあるフォルダの中の並べ替えを受け付ける。並びが変わらないもの（すぐ前・すぐ後ろ・末尾の項目を末尾へ）と、
    // 移す項目が無いものは受け付けない。別のフォルダの位置へは、並びを確かめずに受け付ける
    @Test
    fun positionedItemMoveAcceptsOnlyOrderChanges() {
        val browse = browseWithSelection()
        fun items(vararg numbers: Int) = MoveSubject.Items(A1, numbers.map { item(it).id })
        val cases = listOf(
            Triple("先頭を末尾へ", items(1) to MoveDestination.At(A1, null), true),
            Triple("末尾を先頭へ", items(3) to MoveDestination.At(A1, item(1).id), true),
            Triple("離れた 2 つを先頭へまとめる", items(1, 3) to MoveDestination.At(A1, item(2).id), true),
            Triple("自分のすぐ後ろの前（並びが変わらない）", items(2) to MoveDestination.At(A1, item(3).id), false),
            Triple("自分の前（末尾を指す。末尾でなければ並びが変わる）", items(1) to MoveDestination.At(A1, item(1).id), true),
            Triple("末尾の項目を末尾へ", items(3) to MoveDestination.At(A1, null), false),
            Triple("続けて並ぶ 2 つを、すぐ後ろの前へ", items(1, 2) to MoveDestination.At(A1, item(3).id), false),
            Triple("移す項目が無い", items() to MoveDestination.At(A1, null), false),
            Triple("別のフォルダの末尾", items(1) to MoveDestination.At(B, null), true),
            Triple("ツリーに無いフォルダ", items(1) to MoveDestination.At("unknown", null), false),
        )
        cases.forEach { (name, case, expected) ->
            val (subject, destination) = case
            assertEquals(expected, browse.canMove(subject, destination), name)
        }
    }

    // 位置を指定したフォルダの移動は、同じ親の中の並べ替えと、ほかのフォルダの位置への移動を受け付ける。並びが変わらないもの、
    // ルートフォルダ、自身と子孫の中へは受け付けない
    @Test
    fun positionedFolderMoveAcceptsOnlyOrderChangesOutsideItself() {
        val browse = browseWithTree()
        val cases = listOf(
            Triple("兄弟の前へ（並べ替え）", MoveSubject.Folder(B) to MoveDestination.At(ROOT, A), true),
            Triple("同じ親の末尾へ（並べ替え）", MoveSubject.Folder(A) to MoveDestination.At(ROOT, null), true),
            Triple("いまの親の末尾にあるものを末尾へ", MoveSubject.Folder(B) to MoveDestination.At(ROOT, null), false),
            Triple("自分のすぐ後ろの前", MoveSubject.Folder(A) to MoveDestination.At(ROOT, B), false),
            Triple("ほかのフォルダの子の前へ", MoveSubject.Folder(B) to MoveDestination.At(A, A2), true),
            Triple("祖先の位置へ", MoveSubject.Folder(A1A) to MoveDestination.At(ROOT, B), true),
            Triple("自身の中", MoveSubject.Folder(A) to MoveDestination.At(A, null), false),
            Triple("子孫の中", MoveSubject.Folder(A) to MoveDestination.At(A1, null), false),
            Triple("ルートフォルダ", MoveSubject.Folder(ROOT) to MoveDestination.At(A, null), false),
            Triple("ツリーに無いフォルダ", MoveSubject.Folder("unknown") to MoveDestination.At(ROOT, null), false),
        )
        cases.forEach { (name, case, expected) ->
            val (subject, destination) = case
            assertEquals(expected, browse.canMove(subject, destination), name)
        }
    }

    // 位置を指定しない移動は、canMoveTo と同じ決まり（いまのフォルダ・いまの親へは移さない）。ツリーを読めていなければ、どれも移せない
    @Test
    fun intoMoveFollowsCanMoveTo() {
        val browse = browseWithTree()

        assertFalse(browse.canMove(MoveSubject.Folder(B), MoveDestination.Into(ROOT)))
        assertTrue(browse.canMove(MoveSubject.Folder(B), MoveDestination.Into(A)))
        assertFalse(browse.canMove(MoveSubject.Items(A1, listOf(item(1).id)), MoveDestination.Into(A1)))
        assertFalse(BrowseState().canMove(MoveSubject.Folder(B), MoveDestination.At(ROOT, A)))
    }
}
