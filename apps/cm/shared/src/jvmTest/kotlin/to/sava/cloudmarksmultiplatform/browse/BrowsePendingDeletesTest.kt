package to.sava.cloudmarksmultiplatform.browse

import to.sava.cloudmarksmultiplatform.operations.ItemRef
import to.sava.cloudmarksmultiplatform.storage.FolderEntry
import to.sava.cloudmarksmultiplatform.sync.FolderReadResult
import to.sava.cloudmarksmultiplatform.sync.TreeReadResult
import to.sava.cloudmarksmultiplatform.sync.fileId
import to.sava.cloudmarksmultiplatform.sync.item
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A1 の 3 つの項目。 */
private val THREE_ITEMS = (1..3).map { item(it) }

/** A2 の項目の数。A2 の項目は読まない。 */
private const val A2_COUNT = 4

/** [treeEntries] の A1 を [THREE_ITEMS] の数にし、A2 に [A2_COUNT] 個の項目を持たせた行。 */
private fun entriesWithCounts(): List<FolderEntry> =
    treeEntries().map { entry ->
        when (entry.id) {
            A1 -> entry.copy(count = THREE_ITEMS.size)
            A2 -> entry.copy(items = fileId(2), count = A2_COUNT)
            else -> entry
        }
    }

/** [entriesWithCounts] のツリーを読み、A1 を開いてその項目として [THREE_ITEMS] を読んだ状態。A2 の項目は読んでいない。 */
private fun browseWithA1Open(): BrowseState =
    browseWithTree(loadedTree(entriesWithCounts())).openFolder(A1).withFolderRead("\"etag-1\"", A1, FolderReadResult.Loaded(THREE_ITEMS))

class BrowsePendingDeletesTest {
    // 溜めた削除の項目は、開いているフォルダの項目から除いて見せる。並びはそのまま。読んだ項目そのものは替えない
    @Test
    fun pendingItemsAreHiddenFromCurrentItems() {
        val state = browseWithA1Open().withPendingDeletes(setOf(ItemRef(A1, item(2).id)))

        assertEquals(FolderReadResult.Loaded(listOf(item(1), item(3))), state.currentItems)
        assertEquals(listOf(item(1), item(3)), state.currentLoadedItems)
        assertEquals(FolderReadResult.Loaded(THREE_ITEMS), state.folderReads[A1])
    }

    // 別のフォルダを指す溜めた削除では、同じ ID の項目でも隠さない（項目は、フォルダの ID と項目の ID の組で指す）
    @Test
    fun pendingDeleteOfOtherFolderDoesNotHide() {
        val state = browseWithA1Open().withPendingDeletes(setOf(ItemRef(A2, item(2).id)))

        assertEquals(FolderReadResult.Loaded(THREE_ITEMS), state.currentItems)
    }

    // フォルダの件数から溜めた削除の項目を除く。読んだフォルダは読んだ項目で数え、読んでいないフォルダはマニフェストの count から
    // そのフォルダを指す溜めた削除の数を引く（0 未満にはしない）。子孫まで足した数も同じく除く
    @Test
    fun pendingItemsAreNotCounted() {
        val base = browseWithA1Open()
        val cases: List<Triple<String, Set<ItemRef>, Map<String, Int>>> = listOf(
            Triple("溜めた削除なし", emptySet(), mapOf(A1 to 3, A2 to A2_COUNT, A to 3 + A2_COUNT)),
            Triple(
                "読んだフォルダの項目",
                setOf(ItemRef(A1, item(1).id), ItemRef(A1, item(3).id)),
                mapOf(A1 to 1, A2 to A2_COUNT, A to 1 + A2_COUNT),
            ),
            Triple(
                "読んだフォルダに無い項目は数えない",
                setOf(ItemRef(A1, item(9).id)),
                mapOf(A1 to 3, A to 3 + A2_COUNT),
            ),
            Triple(
                "読んでいないフォルダ",
                setOf(ItemRef(A2, item(10).id), ItemRef(A2, item(11).id)),
                mapOf(A2 to A2_COUNT - 2, A to 3 + A2_COUNT - 2),
            ),
            Triple(
                "読んでいないフォルダの count より多い",
                (10..20).map { ItemRef(A2, item(it).id) }.toSet(),
                mapOf(A2 to 0, A to 3),
            ),
        )
        cases.forEach { (name, pending, expected) ->
            val state = base.withPendingDeletes(pending)

            expected.forEach { (folderId, count) ->
                val actual = if (folderId == A) state.itemCountWithDescendantsOf(folderId) else state.itemCountOf(folderId)
                assertEquals(count, actual, "$name: $folderId")
            }
        }
    }

    // ツリーに無いフォルダの数は 0
    @Test
    fun unknownFolderCountIsZero() {
        val state = browseWithA1Open().withPendingDeletes(setOf(ItemRef("unknown", item(1).id)))

        assertEquals(0, state.itemCountOf("unknown"))
        assertEquals(0, state.itemCountWithDescendantsOf("unknown"))
    }

    // 溜めた削除になった項目は、選んでいる項目と範囲の起点から外す。ほかの選択は残す
    @Test
    fun pendingItemsAreDeselected() {
        val selected = browseWithA1Open()
            .selectItem(item(1).id, SelectionMode.SINGLE)
            .selectItem(item(2).id, SelectionMode.TOGGLE)

        val state = selected.withPendingDeletes(setOf(ItemRef(A1, item(2).id)))

        assertEquals(setOf(item(1).id), state.selectedItemIds)
        assertNull(state.selectionAnchorId)
        assertEquals(listOf(item(1)), state.selectedItems)
    }

    // 溜めた削除の項目は選べず、範囲で選んでも飛ばす
    @Test
    fun pendingItemsCannotBeSelected() {
        val state = browseWithA1Open().withPendingDeletes(setOf(ItemRef(A1, item(2).id)))

        assertEquals(emptySet(), state.selectItem(item(2).id, SelectionMode.SINGLE).selectedItemIds)
        val ranged = state.selectItem(item(1).id, SelectionMode.SINGLE).selectItem(item(3).id, SelectionMode.RANGE)
        assertEquals(setOf(item(1).id, item(3).id), ranged.selectedItemIds)
    }

    // 溜めた削除から外すと、項目をまた見せる
    @Test
    fun removingPendingDeleteShowsItemAgain() {
        val hidden = browseWithA1Open().withPendingDeletes(setOf(ItemRef(A1, item(2).id)))

        val shown = hidden.withPendingDeletes(emptySet())

        assertEquals(FolderReadResult.Loaded(THREE_ITEMS), shown.currentItems)
        assertEquals(3, shown.itemCountOf(A1))
    }

    // 並べ替えが並びを変えるかは、溜めた削除の項目を除いた並びで決める（隠れた項目をはさんだだけの位置は、並びが変わらない）
    @Test
    fun reorderingIgnoresPendingItems() {
        val state = browseWithA1Open().withPendingDeletes(setOf(ItemRef(A1, item(2).id)))
        val firstItem = MoveSubject.Items(A1, listOf(item(1).id))

        // 1 を 3 の前へ：見えている並び（1, 3）は変わらない
        assertFalse(state.canMove(firstItem, MoveDestination.At(A1, item(3).id)))
        // 1 を末尾へ：見えている並びが（3, 1）に変わる
        assertTrue(state.canMove(firstItem, MoveDestination.At(A1, null)))
    }

    // 接続先を替えたとき（cleared）は、溜めた削除も捨てる
    @Test
    fun clearingDropsPendingDeletes() {
        val state = browseWithA1Open().withPendingDeletes(setOf(ItemRef(A1, item(2).id)))

        assertEquals(emptySet(), state.cleared().pendingDeletes)
    }

    // ツリーを読み直しても、書いたあとのツリーに替えても、溜めた削除は残し、新しいツリーの上に重ねて見せる
    @Test
    fun pendingDeletesSurviveTreeReplacement() {
        val pending = setOf(ItemRef(A1, item(2).id))
        val state = browseWithA1Open().withPendingDeletes(pending)
        val reread = loadedTree(entriesWithCounts(), eTag = "\"etag-2\"", loadedItems = mapOf(A1 to THREE_ITEMS))

        val afterRead = state.startLoading().withTreeRead(TreeReadResult.Readable(reread))
        val afterWrite = state.withWrittenTree(reread)

        listOf(afterRead, afterWrite).forEach { replaced ->
            assertEquals(pending, replaced.pendingDeletes)
            assertEquals(FolderReadResult.Loaded(listOf(item(1), item(3))), replaced.currentItems)
        }
    }
}
