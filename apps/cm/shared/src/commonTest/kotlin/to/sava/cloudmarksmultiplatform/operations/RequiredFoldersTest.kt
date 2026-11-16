package to.sava.cloudmarksmultiplatform.operations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequiredFoldersTest {
    // 操作ごとに、適用の前に項目を読み込んでおくフォルダを求める。木に無いフォルダは含まない
    @Test
    fun requiredFoldersPerOperation() {
        val newItem = NewItem(itemId(401), "https://example.com/", "t")
        val cases = mapOf(
            "フォルダとして追加" to (AddFolder(folderId(50), "n", listOf(newItem), ADDED) to emptySet()),
            "ブックマークとして追加" to (AddBookmarks(listOf(newItem), ADDED) to setOf(ROOT)),
            "項目の削除" to (
                DeleteItems(listOf(ItemRef(FOLDER_A, A_ITEM_1), ItemRef(FOLDER_A, A_ITEM_2), ItemRef(FOLDER_B, B_ITEM_1))) to
                    setOf(FOLDER_A, FOLDER_B)
                ),
            "項目の削除（木に無いフォルダ）" to (DeleteItems(listOf(ItemRef(folderId(99), itemId(1)))) to emptySet()),
            "項目の挿入" to (InsertItems(FOLDER_A, listOf(item(itemId(501))), null) to setOf(FOLDER_A)),
            "項目の挿入（木に無いフォルダ）" to (InsertItems(folderId(99), listOf(item(itemId(501))), null) to emptySet()),
            "項目の移動" to (
                MoveItems(listOf(ItemRef(FOLDER_A, A_ITEM_1), ItemRef(FOLDER_B, B_ITEM_1)), FOLDER_C, null) to
                    setOf(FOLDER_A, FOLDER_B, FOLDER_C)
                ),
            "項目の移動（同じフォルダ内）" to
                (MoveItems(listOf(ItemRef(FOLDER_A, A_ITEM_1)), FOLDER_A, null) to setOf(FOLDER_A)),
            "項目のタイトルの変更" to (ChangeItemTitle(ItemRef(FOLDER_B, B_ITEM_1), "t") to setOf(FOLDER_B)),
            "フォルダの作成" to (CreateFolder(folderId(50), ROOT, "n", null) to emptySet()),
            "フォルダの移動" to (MoveFolder(FOLDER_A, FOLDER_C, null) to emptySet()),
            "フォルダの名前の変更" to (RenameFolder(FOLDER_A, "n") to emptySet()),
            "フォルダの削除" to (DeleteFolder(FOLDER_A) to emptySet()),
        )

        cases.forEach { (name, case) ->
            val (operation, expected) = case

            assertEquals(expected, requiredFolders(SAMPLE_STATE, operation), name)
        }
    }

    // 操作の並びでは、各操作の分の和になる
    @Test
    fun requiredFoldersOfSequenceIsUnion() {
        val operations = listOf(
            AddBookmarks(listOf(NewItem(itemId(401), "https://example.com/", "t")), ADDED),
            ChangeItemTitle(ItemRef(FOLDER_B, B_ITEM_1), "t"),
            DeleteFolder(FOLDER_A),
        )

        assertEquals(setOf(ROOT, FOLDER_B), requiredFolders(SAMPLE_STATE, operations))
    }

    // 根と A の項目だけを読み込めば、根と A だけに係る操作の並びを適用できる。
    // 項目を持つ B は読み込まなくてよく、B を消す操作も通る
    @Test
    fun applyingNeedsOnlyRequiredFolders() {
        val operations = listOf(
            AddBookmarks(listOf(NewItem(itemId(401), "https://example.com/", "t")), ADDED),
            MoveItems(listOf(ItemRef(FOLDER_A, A_ITEM_1)), ROOT, null),
            ChangeItemTitle(ItemRef(FOLDER_A, A_ITEM_2), "new"),
            DeleteFolder(FOLDER_B),
        )
        val required = requiredFolders(SAMPLE_STATE, operations)
        val onlyRequired = SAMPLE_STATE.copy(items = SAMPLE_STATE.items.filterKeys { it in required })

        val result = applyOperations(onlyRequired, operations)

        assertEquals(setOf(ROOT, FOLDER_A), required)
        assertFalse(FOLDER_B in onlyRequired.items)
        assertTrue(result.results.all { it.status == OperationStatus.Applied })
        assertEquals(setOf(ROOT, FOLDER_A), result.changedFolderIds)
    }

    // 求めたフォルダの項目を読み込んでいなければ、プログラムの誤りとして例外になる
    @Test
    fun applyingFailsWhenRequiredFolderIsNotLoaded() {
        val operations = listOf(ChangeItemTitle(ItemRef(FOLDER_B, B_ITEM_1), "new"))
        val unloaded = SAMPLE_STATE.copy(items = SAMPLE_STATE.items - FOLDER_B)

        assertFailsWith<IllegalStateException> { applyOperations(unloaded, operations) }
    }
}
