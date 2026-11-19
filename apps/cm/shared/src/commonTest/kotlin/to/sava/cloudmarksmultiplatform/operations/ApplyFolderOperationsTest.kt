package to.sava.cloudmarksmultiplatform.operations

import to.sava.cloudmarksmultiplatform.storage.Item
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApplyFolderOperationsTest {
    private val newFolder = folderId(50)
    private val newItems = listOf(
        NewItem(itemId(401), "https://example.com/1", "one"),
        NewItem(itemId(402), "https://example.com/2", "two"),
    )

    // フォルダとして追加：ルートフォルダの子のフォルダの末尾に作り、項目を入力の順に入れて added を設定する
    @Test
    fun addFolderAppendsFolderToRootWithItemsInOrder() {
        val result = applyOne(SAMPLE_STATE, AddFolder(newFolder, "新しい", newItems, ADDED))

        assertEquals(OperationStatus.Applied, result.singleStatus())
        assertEquals(listOf(FOLDER_A, FOLDER_B, FOLDER_C, newFolder), result.state.childIds(ROOT))
        assertEquals(
            FolderNode(newFolder, ROOT, "新しい", emptyList(), null, 0),
            result.state.folders.getValue(newFolder),
        )
        assertEquals(
            newItems.map { Item(it.id, it.url, it.title, ADDED) },
            result.state.items.getValue(newFolder),
        )
        assertEquals(setOf(newFolder), result.changedFolderIds)
        assertTrue(result.treeChanged)
    }

    // フォルダとして追加：フォルダの ID が既にあれば適用済み。2 回目は状態が変わらない
    @Test
    fun addFolderIsIdempotent() {
        assertIdempotent(SAMPLE_STATE, AddFolder(newFolder, "新しい", newItems, ADDED))
    }

    // フォルダとして追加：名前が空なら適用しない
    @Test
    fun addFolderWithEmptyNameIsNotApplied() {
        val result = applyOne(SAMPLE_STATE, AddFolder(newFolder, "", newItems, ADDED))

        assertEquals(notApplied(NotAppliedReason.EmptyName), result.singleStatus())
        assertTrue(result.unchanged)
    }

    // フォルダの作成：位置に指定したフォルダの前、末尾（位置の指定なし・位置に指定したフォルダが親の子でない）に入る。項目は空で読み込んだ状態になる
    @Test
    fun createFolderPlacesBeforeMarkerOrAtEnd() {
        val cases = mapOf(
            "位置に指定したフォルダの前" to (FOLDER_B to listOf(FOLDER_A, newFolder, FOLDER_B, FOLDER_C)),
            "先頭" to (FOLDER_A to listOf(newFolder, FOLDER_A, FOLDER_B, FOLDER_C)),
            "指定なし" to (null to listOf(FOLDER_A, FOLDER_B, FOLDER_C, newFolder)),
            "位置に指定したフォルダが親の子でない" to (FOLDER_A1 to listOf(FOLDER_A, FOLDER_B, FOLDER_C, newFolder)),
            "位置に指定したフォルダが無い" to (folderId(99) to listOf(FOLDER_A, FOLDER_B, FOLDER_C, newFolder)),
        )

        cases.forEach { (name, case) ->
            val (before, expected) = case
            val result = applyOne(SAMPLE_STATE, CreateFolder(newFolder, ROOT, "新しい", before))

            assertEquals(OperationStatus.Applied, result.singleStatus(), name)
            assertEquals(expected, result.state.childIds(ROOT), name)
            assertEquals(emptyList(), result.state.items.getValue(newFolder), name)
            assertTrue(result.treeChanged, name)
            assertTrue(result.changedFolderIds.isEmpty(), name)
        }
    }

    // フォルダの作成：ID が既にあれば適用済み。親が無ければ、名前が空なら適用しない
    @Test
    fun createFolderIdempotencyAndRejections() {
        assertIdempotent(SAMPLE_STATE, CreateFolder(newFolder, FOLDER_A, "新しい", null))

        val noParent = applyOne(SAMPLE_STATE, CreateFolder(newFolder, folderId(99), "新しい", null))
        assertEquals(notApplied(NotAppliedReason.DestinationNotFound), noParent.singleStatus())
        assertTrue(noParent.unchanged)

        val emptyName = applyOne(SAMPLE_STATE, CreateFolder(newFolder, ROOT, "", null))
        assertEquals(notApplied(NotAppliedReason.EmptyName), emptyName.singleStatus())
        assertTrue(emptyName.unchanged)
    }

    // フォルダの移動：子孫ごと別の親へ移り、位置に指定したフォルダの前に入る。親の子の並びと、移したフォルダの親が替わる
    @Test
    fun moveFolderMovesWithDescendantsBeforeMarker() {
        val result = applyOne(SAMPLE_STATE, MoveFolder(FOLDER_A, FOLDER_C, before = null))

        assertEquals(OperationStatus.Applied, result.singleStatus())
        assertEquals(listOf(FOLDER_B, FOLDER_C), result.state.childIds(ROOT))
        assertEquals(listOf(FOLDER_A), result.state.childIds(FOLDER_C))
        assertEquals(FOLDER_C, result.state.folders.getValue(FOLDER_A).parent)
        assertEquals(listOf(FOLDER_A1), result.state.childIds(FOLDER_A))
        assertTrue(result.treeChanged)
        assertTrue(result.changedFolderIds.isEmpty())

        val beforeMarker = applyOne(SAMPLE_STATE, MoveFolder(FOLDER_A1, ROOT, before = FOLDER_B))
        assertEquals(listOf(FOLDER_A, FOLDER_A1, FOLDER_B, FOLDER_C), beforeMarker.state.childIds(ROOT))
        assertEquals(emptyList(), beforeMarker.state.childIds(FOLDER_A))
    }

    // フォルダの移動：同じ親の中での並べ替え。位置に指定したフォルダが対象そのものなら、外したあとの並びで決まるので末尾
    @Test
    fun moveFolderReordersWithinSameParent() {
        val toFront = applyOne(SAMPLE_STATE, MoveFolder(FOLDER_C, ROOT, before = FOLDER_A))
        assertEquals(listOf(FOLDER_C, FOLDER_A, FOLDER_B), toFront.state.childIds(ROOT))

        val markerIsTarget = applyOne(SAMPLE_STATE, MoveFolder(FOLDER_A, ROOT, before = FOLDER_A))
        assertEquals(listOf(FOLDER_B, FOLDER_C, FOLDER_A), markerIsTarget.state.childIds(ROOT))
    }

    // フォルダの移動：対象か新しい親が無いとき、対象がルートフォルダのとき、新しい親が対象そのものかその子孫のときは適用しない
    @Test
    fun moveFolderRejections() {
        val cases = mapOf(
            "対象が無い" to (MoveFolder(folderId(99), ROOT, null) to NotAppliedReason.TargetNotFound),
            "新しい親が無い" to (MoveFolder(FOLDER_A, folderId(99), null) to NotAppliedReason.DestinationNotFound),
            "対象がルートフォルダ" to (MoveFolder(ROOT, FOLDER_A, null) to NotAppliedReason.RootNotAllowed),
            "新しい親が対象そのもの" to
                (MoveFolder(FOLDER_A, FOLDER_A, null) to NotAppliedReason.NewParentIsSelfOrDescendant),
            "新しい親が子" to
                (MoveFolder(FOLDER_A, FOLDER_A1, null) to NotAppliedReason.NewParentIsSelfOrDescendant),
        )

        cases.forEach { (name, case) ->
            val (operation, reason) = case
            val result = applyOne(SAMPLE_STATE, operation)

            assertEquals(notApplied(reason), result.singleStatus(), name)
            assertTrue(result.unchanged, name)
        }
    }

    // フォルダの移動：孫の子孫を新しい親にしても適用しない（子だけでなく深い子孫も対象）
    @Test
    fun moveFolderIntoDeepDescendantIsNotApplied() {
        val deep = applyOne(SAMPLE_STATE, CreateFolder(folderId(60), FOLDER_A1, "deep", null)).state

        val result = applyOne(deep, MoveFolder(FOLDER_A, folderId(60), null))

        assertEquals(notApplied(NotAppliedReason.NewParentIsSelfOrDescendant), result.singleStatus())
        assertTrue(result.unchanged)
    }

    // フォルダの名前の変更：名前だけが変わり、構成と項目は変わらない
    @Test
    fun renameFolderChangesOnlyName() {
        val result = applyOne(SAMPLE_STATE, RenameFolder(FOLDER_A, "A/B"))

        assertEquals(OperationStatus.Applied, result.singleStatus())
        assertEquals("A/B", result.state.folders.getValue(FOLDER_A).name)
        assertEquals(SAMPLE_STATE.folders.getValue(FOLDER_A).copy(name = "A/B"), result.state.folders.getValue(FOLDER_A))
        assertEquals(SAMPLE_STATE.items, result.state.items)
        assertTrue(result.treeChanged)
        assertTrue(result.changedFolderIds.isEmpty())
    }

    // フォルダの名前の変更：対象が無い・ルートフォルダ・名前が空のときは適用しない
    @Test
    fun renameFolderRejections() {
        val cases = mapOf(
            "対象が無い" to (RenameFolder(folderId(99), "x") to NotAppliedReason.TargetNotFound),
            "ルートフォルダ" to (RenameFolder(ROOT, "x") to NotAppliedReason.RootNotAllowed),
            "名前が空" to (RenameFolder(FOLDER_A, "") to NotAppliedReason.EmptyName),
        )

        cases.forEach { (name, case) ->
            val (operation, reason) = case
            val result = applyOne(SAMPLE_STATE, operation)

            assertEquals(notApplied(reason), result.singleStatus(), name)
            assertTrue(result.unchanged, name)
        }
    }

    // フォルダの削除：子孫のフォルダと、それらの項目ごと消える。ほかのフォルダと項目は残る
    @Test
    fun deleteFolderRemovesDescendantsAndTheirItems() {
        val result = applyOne(SAMPLE_STATE, DeleteFolder(FOLDER_A))

        assertEquals(OperationStatus.Applied, result.singleStatus())
        assertEquals(setOf(ROOT, FOLDER_B, FOLDER_C), result.state.folders.keys)
        assertEquals(listOf(FOLDER_B, FOLDER_C), result.state.childIds(ROOT))
        assertNull(result.state.items[FOLDER_A])
        assertNull(result.state.items[FOLDER_A1])
        assertEquals(listOf(ROOT_ITEM_1, ROOT_ITEM_2), result.state.itemIds(ROOT))
        assertEquals(listOf(B_ITEM_1), result.state.itemIds(FOLDER_B))
        assertTrue(result.treeChanged)
        assertTrue(result.changedFolderIds.isEmpty())
    }

    // フォルダの削除：もう無ければ何もせず、報告もしない。ルートフォルダは消せない
    @Test
    fun deleteFolderIsIdempotentAndRefusesRoot() {
        val operation = DeleteFolder(FOLDER_A)
        assertIdempotent(SAMPLE_STATE, operation)

        val missing = applyOne(SAMPLE_STATE, DeleteFolder(folderId(99)))
        assertEquals(OperationStatus.AlreadyApplied, missing.singleStatus())
        assertTrue(missing.unchanged)

        val root = applyOne(SAMPLE_STATE, DeleteFolder(ROOT))
        assertEquals(notApplied(NotAppliedReason.RootNotAllowed), root.singleStatus())
        assertTrue(root.unchanged)
    }

    // 複数の操作：途中の操作が適用されなくても後続は適用される。結果は入力の順に返る
    @Test
    fun laterOperationsAreAppliedAfterOneIsNotApplied() {
        val operations = listOf(
            RenameFolder(ROOT, "x"),
            RenameFolder(FOLDER_B, "B2"),
            DeleteItems(listOf(ItemRef(FOLDER_A, itemId(999)))),
            ChangeItemTitle(ItemRef(FOLDER_A, A_ITEM_1), "t"),
        )

        val result = applyOperations(SAMPLE_STATE, operations)

        assertEquals(
            listOf(
                notApplied(NotAppliedReason.RootNotAllowed),
                OperationStatus.Applied,
                OperationStatus.AlreadyApplied,
                OperationStatus.Applied,
            ),
            result.results.map { it.status },
        )
        assertEquals(operations, result.results.map { it.operation })
        assertEquals("B2", result.state.folders.getValue(FOLDER_B).name)
        assertEquals("t", result.state.items.getValue(FOLDER_A).first().title)
        assertEquals(setOf(FOLDER_A), result.changedFolderIds)
        assertTrue(result.treeChanged)
        assertFalse(result.unchanged)
    }

    // 複数の操作：先の操作が作ったフォルダを、後の操作が使える（作成 → 項目の移動 → 名前の変更）
    @Test
    fun laterOperationsCanUseFolderCreatedEarlier() {
        val result = applyOperations(
            SAMPLE_STATE,
            listOf(
                CreateFolder(newFolder, ROOT, "x", null),
                MoveItems(listOf(ItemRef(FOLDER_A, A_ITEM_1)), newFolder, null),
                RenameFolder(newFolder, "y"),
            ),
        )

        assertTrue(result.results.all { it.status == OperationStatus.Applied })
        assertEquals(listOf(A_ITEM_1), result.state.itemIds(newFolder))
        assertEquals("y", result.state.folders.getValue(newFolder).name)
        assertEquals(setOf(FOLDER_A, newFolder), result.changedFolderIds)
    }

    // 全体として何も変わらない：もう無い項目の削除だけ、または空の並び
    @Test
    fun noEffectiveOperationsMeansUnchanged() {
        val onlyMissingDelete = applyOperations(
            SAMPLE_STATE,
            listOf(
                DeleteItems(listOf(ItemRef(FOLDER_A, itemId(999)))),
                DeleteFolder(folderId(99)),
            ),
        )
        assertTrue(onlyMissingDelete.unchanged)
        assertTrue(onlyMissingDelete.changedFolderIds.isEmpty())
        assertFalse(onlyMissingDelete.treeChanged)
        assertEquals(SAMPLE_STATE, onlyMissingDelete.state)

        val empty = applyOperations(SAMPLE_STATE, emptyList())
        assertTrue(empty.unchanged)
        assertTrue(empty.results.isEmpty())
    }

    // 全体として何も変わらない：作ってすぐ消すなど、結果として元と同じ状態に戻る並びも「変わらない」
    @Test
    fun operationsThatCancelOutMeanUnchanged() {
        val result = applyOperations(
            SAMPLE_STATE,
            listOf(CreateFolder(newFolder, ROOT, "x", null), DeleteFolder(newFolder)),
        )

        assertTrue(result.unchanged)
        assertFalse(result.treeChanged)
        assertTrue(result.changedFolderIds.isEmpty())
    }
}
