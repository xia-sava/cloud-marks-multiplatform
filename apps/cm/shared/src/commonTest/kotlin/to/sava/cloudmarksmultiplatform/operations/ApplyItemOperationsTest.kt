package to.sava.cloudmarksmultiplatform.operations

import to.sava.cloudmarksmultiplatform.storage.Item
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class ApplyItemOperationsTest {
    private val newItem1 = NewItem(itemId(401), "https://example.com/1", "one")
    private val newItem2 = NewItem(itemId(402), "https://example.com/2", "two")

    // ブックマークとして追加：ルートフォルダの項目の末尾に入力の順で足し、added を設定する。ほかのフォルダの項目は変わらない
    @Test
    fun addBookmarksAppendsToRootInInputOrderWithAdded() {
        val result = applyOne(SAMPLE_STATE, AddBookmarks(null, listOf(newItem1, newItem2), ADDED))

        assertEquals(OperationStatus.Applied, result.singleStatus())
        assertEquals(listOf(ROOT_ITEM_1, ROOT_ITEM_2, newItem1.id, newItem2.id), result.state.itemIds(ROOT))
        assertEquals(
            Item(newItem1.id, newItem1.url, newItem1.title, ADDED),
            result.state.items.getValue(ROOT)[2],
        )
        assertEquals(setOf(ROOT), result.changedFolderIds)
        assertFalse(result.treeChanged)
        assertFalse(result.unchanged)
    }

    // ブックマークとして追加：追加先にフォルダを指定すれば、そのフォルダの項目の末尾に足す。ほかのフォルダの項目は変わらない。
    // 項目を持たないフォルダにも足せる
    @Test
    fun addBookmarksAppendsToGivenDestination() {
        val toA = applyOne(SAMPLE_STATE, AddBookmarks(FOLDER_A, listOf(newItem1, newItem2), ADDED))

        assertEquals(OperationStatus.Applied, toA.singleStatus())
        assertEquals(listOf(A_ITEM_1, A_ITEM_2, A_ITEM_3, newItem1.id, newItem2.id), toA.state.itemIds(FOLDER_A))
        assertEquals(listOf(ROOT_ITEM_1, ROOT_ITEM_2), toA.state.itemIds(ROOT))
        assertEquals(Item(newItem1.id, newItem1.url, newItem1.title, ADDED), toA.state.items.getValue(FOLDER_A)[3])
        assertEquals(setOf(FOLDER_A), toA.changedFolderIds)
        assertEquals(mapOf(FOLDER_A to toA.state.items.getValue(FOLDER_A)), toA.itemsToWrite)

        val toEmpty = applyOne(SAMPLE_STATE, AddBookmarks(FOLDER_C, listOf(newItem1), ADDED))
        assertEquals(listOf(newItem1.id), toEmpty.state.itemIds(FOLDER_C))
        assertEquals(setOf(FOLDER_C), toEmpty.changedFolderIds)

        val toRoot = applyOne(SAMPLE_STATE, AddBookmarks(ROOT, listOf(newItem1), ADDED))
        assertEquals(applyOne(SAMPLE_STATE, AddBookmarks(null, listOf(newItem1), ADDED)).state, toRoot.state)
    }

    // ブックマークとして追加：追加先が無ければ適用しない。状態は変わらない
    @Test
    fun addBookmarksWithMissingDestinationIsNotApplied() {
        val result = applyOne(SAMPLE_STATE, AddBookmarks(folderId(99), listOf(newItem1), ADDED))

        assertEquals(notApplied(NotAppliedReason.DestinationNotFound), result.singleStatus())
        assertTrue(result.unchanged)
    }

    // ブックマークとして追加：追加先のフォルダに同じ ID の項目があれば適用済み。一部だけあれば、無いものだけ足す
    @Test
    fun addBookmarksSkipsItemsAlreadyInDestination() {
        listOf(null, FOLDER_A).forEach { destination ->
            val folderId = destination ?: ROOT
            val operation = AddBookmarks(destination, listOf(newItem1, newItem2), ADDED)
            assertIdempotent(SAMPLE_STATE, operation)

            val partial = applyOne(
                SAMPLE_STATE.withLoadedItems(folderId, listOf(item(newItem1.id))),
                operation,
            )
            assertEquals(OperationStatus.Applied, partial.singleStatus(), "追加先 $destination")
            assertEquals(listOf(newItem1.id, newItem2.id), partial.state.itemIds(folderId), "追加先 $destination")
        }
    }

    // ブックマークとして追加：追加先の項目を読み込んでいなければ、プログラムの誤りとして例外にする
    @Test
    fun addBookmarksFailsWhenDestinationItemsAreNotLoaded() {
        val unloadedRoot = SAMPLE_STATE.copy(items = SAMPLE_STATE.items - ROOT)
        assertFailsWith<IllegalStateException> { applyOne(unloadedRoot, AddBookmarks(null, listOf(newItem1), ADDED)) }

        val unloadedA = SAMPLE_STATE.copy(items = SAMPLE_STATE.items - FOLDER_A)
        assertFailsWith<IllegalStateException> { applyOne(unloadedA, AddBookmarks(FOLDER_A, listOf(newItem1), ADDED)) }
    }

    // 項目の削除：対象だけを消し、残りの並びを保つ。もう無い対象は何も起こさず、報告もされない
    @Test
    fun deleteItemsRemovesTargetsAndIgnoresMissingOnes() {
        val operation = DeleteItems(
            listOf(ItemRef(FOLDER_A, A_ITEM_2), ItemRef(FOLDER_B, B_ITEM_1)),
        )
        val result = applyOne(SAMPLE_STATE, operation)

        assertEquals(OperationStatus.Applied, result.singleStatus())
        assertEquals(listOf(A_ITEM_1, A_ITEM_3), result.state.itemIds(FOLDER_A))
        assertEquals(emptyList(), result.state.itemIds(FOLDER_B))
        assertEquals(setOf(FOLDER_A, FOLDER_B), result.changedFolderIds)
        // 空になった B はファイルを置かない
        assertEquals(mapOf(FOLDER_A to listOf(item(A_ITEM_1), item(A_ITEM_3))), result.itemsToWrite)

        val second = applyOne(result.state, operation)
        assertEquals(OperationStatus.AlreadyApplied, second.singleStatus())
        assertTrue(second.unchanged)
        assertTrue(second.results.none { it.status is OperationStatus.NotApplied })
    }

    // 項目の削除：対象の項目が別のフォルダにあるとき、指したフォルダに無いので何もしない
    @Test
    fun deleteItemsOnlyMatchesItemInTheNamedFolder() {
        val result = applyOne(SAMPLE_STATE, DeleteItems(listOf(ItemRef(FOLDER_B, A_ITEM_1))))

        assertEquals(OperationStatus.AlreadyApplied, result.singleStatus())
        assertTrue(result.unchanged)
    }

    // 項目の削除：ツリーに無いフォルダを指していても何もせず、項目を読み込んでいなくても例外にならない
    @Test
    fun deleteItemsFromMissingFolderDoesNothing() {
        val result = applyOne(SAMPLE_STATE, DeleteItems(listOf(ItemRef(folderId(99), itemId(1)))))

        assertEquals(OperationStatus.AlreadyApplied, result.singleStatus())
        assertTrue(result.unchanged)
    }

    // 項目の挿入：位置に指定した項目の前に入力の順で入れ、位置に指定した項目が無い・null なら末尾に入れる
    @Test
    fun insertItemsPlacesBeforeMarkerOrAtEnd() {
        val inserted = listOf(item(itemId(501)), item(itemId(502)))
        val cases = mapOf(
            "位置に指定した項目の前" to (A_ITEM_2 to listOf(A_ITEM_1, itemId(501), itemId(502), A_ITEM_2, A_ITEM_3)),
            "先頭の項目の前" to (A_ITEM_1 to listOf(itemId(501), itemId(502), A_ITEM_1, A_ITEM_2, A_ITEM_3)),
            "指定なし" to (null to listOf(A_ITEM_1, A_ITEM_2, A_ITEM_3, itemId(501), itemId(502))),
            "位置に指定した項目が無い" to (itemId(999) to listOf(A_ITEM_1, A_ITEM_2, A_ITEM_3, itemId(501), itemId(502))),
        )

        cases.forEach { (name, case) ->
            val (before, expected) = case
            val result = applyOne(SAMPLE_STATE, InsertItems(FOLDER_A, inserted, before))

            assertEquals(OperationStatus.Applied, result.singleStatus(), name)
            assertEquals(expected, result.state.itemIds(FOLDER_A), name)
            assertEquals(setOf(FOLDER_A), result.changedFolderIds, name)
        }
    }

    // 項目の挿入：消す前の値（追加した日時を含む）をそのまま使う。項目が無かったフォルダにも入れられる
    @Test
    fun insertItemsKeepsGivenValuesAndFillsEmptyFolder() {
        val withAdded = item(itemId(501), title = "", added = ADDED)
        val withoutAdded = item(itemId(502))
        val result = applyOne(SAMPLE_STATE, InsertItems(FOLDER_C, listOf(withAdded, withoutAdded), before = null))

        assertEquals(listOf(withAdded, withoutAdded), result.state.items.getValue(FOLDER_C))
        assertEquals(setOf(FOLDER_C), result.changedFolderIds)
    }

    // 項目の挿入：同じ ID の項目が既にあれば適用済み。2 回適用しても状態は変わらない。挿入先が無ければ適用しない
    @Test
    fun insertItemsIsIdempotentAndRequiresExistingFolder() {
        val operation = InsertItems(FOLDER_A, listOf(item(itemId(501))), A_ITEM_2)
        assertIdempotent(SAMPLE_STATE, operation)

        val missing = applyOne(SAMPLE_STATE, InsertItems(folderId(99), listOf(item(itemId(501))), null))
        assertEquals(notApplied(NotAppliedReason.DestinationNotFound), missing.singleStatus())
        assertTrue(missing.unchanged)
    }

    // 項目の移動：別のフォルダへ、位置に指定した項目の前に入力の順で入れる。元のフォルダから外れる
    @Test
    fun moveItemsToAnotherFolderBeforeMarker() {
        val result = applyOne(
            SAMPLE_STATE,
            MoveItems(
                targets = listOf(ItemRef(FOLDER_A, A_ITEM_3), ItemRef(FOLDER_A, A_ITEM_1)),
                destinationId = FOLDER_B,
                before = B_ITEM_1,
            ),
        )

        assertEquals(OperationStatus.Applied, result.singleStatus())
        assertEquals(emptyList(), result.results.single().skippedTargets)
        assertEquals(listOf(A_ITEM_2), result.state.itemIds(FOLDER_A))
        assertEquals(listOf(A_ITEM_3, A_ITEM_1, B_ITEM_1), result.state.itemIds(FOLDER_B))
        assertEquals(setOf(FOLDER_A, FOLDER_B), result.changedFolderIds)
        assertFalse(result.treeChanged)
    }

    // 項目の移動：移動先の末尾（位置の指定なし）。複数のフォルダから、入力の順に集める
    @Test
    fun moveItemsFromSeveralFoldersToEndWithoutMarker() {
        val result = applyOne(
            SAMPLE_STATE,
            MoveItems(
                targets = listOf(ItemRef(FOLDER_B, B_ITEM_1), ItemRef(FOLDER_A, A_ITEM_2)),
                destinationId = ROOT,
                before = null,
            ),
        )

        assertEquals(listOf(ROOT_ITEM_1, ROOT_ITEM_2, B_ITEM_1, A_ITEM_2), result.state.itemIds(ROOT))
        assertEquals(listOf(A_ITEM_1, A_ITEM_3), result.state.itemIds(FOLDER_A))
        assertEquals(emptyList(), result.state.itemIds(FOLDER_B))
    }

    // 項目の移動：同じフォルダの中での並べ替え。位置は対象を外したあとの並びで決める
    @Test
    fun moveItemsReordersWithinSameFolder() {
        val result = applyOne(
            SAMPLE_STATE,
            MoveItems(listOf(ItemRef(FOLDER_A, A_ITEM_3)), FOLDER_A, before = A_ITEM_1),
        )

        assertEquals(listOf(A_ITEM_3, A_ITEM_1, A_ITEM_2), result.state.itemIds(FOLDER_A))
        assertEquals(setOf(FOLDER_A), result.changedFolderIds)
    }

    // 項目の移動：位置は対象を外したあとの並びで決める（元の並びの添字ではなく、位置に指定した項目の前に入る）
    @Test
    fun moveItemsDecidesPositionAfterRemovingTargets() {
        val cases = mapOf(
            "位置に指定した後ろの項目の前" to (A_ITEM_3 to listOf(A_ITEM_2, A_ITEM_1, A_ITEM_3)),
            "位置に指定した項目が対象自身なので末尾" to (A_ITEM_1 to listOf(A_ITEM_2, A_ITEM_3, A_ITEM_1)),
        )

        cases.forEach { (name, case) ->
            val (before, expected) = case
            val result = applyOne(SAMPLE_STATE, MoveItems(listOf(ItemRef(FOLDER_A, A_ITEM_1)), FOLDER_A, before))

            assertEquals(expected, result.state.itemIds(FOLDER_A), name)
        }
    }

    // 項目の移動：対象が空なら何もせず、適用済みとして扱う
    @Test
    fun moveItemsWithNoTargetsIsAlreadyApplied() {
        val result = applyOne(SAMPLE_STATE, MoveItems(emptyList(), FOLDER_B, null))

        assertEquals(OperationStatus.AlreadyApplied, result.singleStatus())
        assertTrue(result.unchanged)
    }

    // 項目の移動：位置に指定した項目が対象に含まれていれば末尾に置く
    @Test
    fun moveItemsPutsAtEndWhenMarkerIsAmongTargets() {
        val result = applyOne(
            SAMPLE_STATE,
            MoveItems(
                targets = listOf(ItemRef(FOLDER_A, A_ITEM_1), ItemRef(FOLDER_A, A_ITEM_2)),
                destinationId = FOLDER_A,
                before = A_ITEM_2,
            ),
        )

        assertEquals(listOf(A_ITEM_3, A_ITEM_1, A_ITEM_2), result.state.itemIds(FOLDER_A))
    }

    // 項目の移動：位置に指定した項目が無い（指定なし・並びに無い ID）ときは末尾に置く
    @Test
    fun moveItemsPutsAtEndWhenMarkerIsAbsentOrUnknown() {
        listOf(null, itemId(999)).forEach { before ->
            val result = applyOne(
                SAMPLE_STATE,
                MoveItems(listOf(ItemRef(FOLDER_A, A_ITEM_1)), FOLDER_A, before),
            )

            assertEquals(listOf(A_ITEM_2, A_ITEM_3, A_ITEM_1), result.state.itemIds(FOLDER_A), "before=$before")
        }
    }

    // 項目の移動：無い対象は飛ばし、残りを移す。飛ばした対象を返す
    @Test
    fun moveItemsSkipsMissingTargetsAndReportsThem() {
        val missing = ItemRef(FOLDER_A, itemId(999))
        val missingFolder = ItemRef(folderId(99), A_ITEM_1)
        val result = applyOne(
            SAMPLE_STATE,
            MoveItems(listOf(missing, ItemRef(FOLDER_A, A_ITEM_1), missingFolder), FOLDER_B, before = null),
        )

        assertEquals(OperationStatus.Applied, result.singleStatus())
        assertEquals(listOf(missing, missingFolder), result.results.single().skippedTargets)
        assertEquals(listOf(B_ITEM_1, A_ITEM_1), result.state.itemIds(FOLDER_B))
    }

    // 項目の移動：対象がすべて無ければ、適用しないとして飛ばした対象を返す。状態は変わらない
    @Test
    fun moveItemsWithOnlyMissingTargetsIsNotApplied() {
        val missing = ItemRef(FOLDER_A, itemId(999))
        val result = applyOne(SAMPLE_STATE, MoveItems(listOf(missing), FOLDER_B, null))

        assertEquals(notApplied(NotAppliedReason.TargetNotFound), result.singleStatus())
        assertEquals(listOf(missing), result.results.single().skippedTargets)
        assertTrue(result.unchanged)
    }

    // 項目の移動：移動先のフォルダが無ければ、操作全体を適用しない
    @Test
    fun moveItemsToMissingDestinationIsNotApplied() {
        val result = applyOne(
            SAMPLE_STATE,
            MoveItems(listOf(ItemRef(FOLDER_A, A_ITEM_1)), folderId(99), null),
        )

        assertEquals(notApplied(NotAppliedReason.DestinationNotFound), result.singleStatus())
        assertTrue(result.unchanged)
        assertEquals(listOf(A_ITEM_1, A_ITEM_2, A_ITEM_3), result.state.itemIds(FOLDER_A))
    }

    // 項目の移動：同じ対象が重複して入力されても、1 回だけ移す
    @Test
    fun moveItemsMovesDuplicatedTargetOnce() {
        val target = ItemRef(FOLDER_A, A_ITEM_1)
        val result = applyOne(SAMPLE_STATE, MoveItems(listOf(target, target), FOLDER_B, null))

        assertEquals(listOf(B_ITEM_1, A_ITEM_1), result.state.itemIds(FOLDER_B))
    }

    // 項目のタイトルの変更：対象のタイトルだけが変わる。空にもできる。ほかの欄と項目は変わらない
    @Test
    fun changeItemTitleUpdatesOnlyTheTitle() {
        val original = item(A_ITEM_2, added = ADDED)
        val state = SAMPLE_STATE.withLoadedItems(FOLDER_A, listOf(item(A_ITEM_1), original, item(A_ITEM_3)))

        listOf("new title", "").forEach { title ->
            val result = applyOne(state, ChangeItemTitle(ItemRef(FOLDER_A, A_ITEM_2), title))

            assertEquals(OperationStatus.Applied, result.singleStatus())
            assertEquals(original.copy(title = title), result.state.items.getValue(FOLDER_A)[1])
            assertEquals(listOf(A_ITEM_1, A_ITEM_2, A_ITEM_3), result.state.itemIds(FOLDER_A))
            assertEquals(setOf(FOLDER_A), result.changedFolderIds)
        }
    }

    // 項目のタイトルの変更：同じタイトルへの変更は適用したことになるが、状態は変わらない
    @Test
    fun changeItemTitleToSameTitleIsAppliedButUnchanged() {
        val current = SAMPLE_STATE.items.getValue(FOLDER_A).first().title

        val result = applyOne(SAMPLE_STATE, ChangeItemTitle(ItemRef(FOLDER_A, A_ITEM_1), current))

        assertEquals(OperationStatus.Applied, result.singleStatus())
        assertTrue(result.unchanged)
        assertTrue(result.changedFolderIds.isEmpty())
        assertTrue(result.itemsToWrite.isEmpty())
    }

    // 入力の中で同じ ID の項目が複数あれば、最初の 1 つにまとめる（ブックマークの追加・項目の挿入・フォルダとして追加）
    @Test
    fun duplicatedIdsInInputAreMerged() {
        val first = NewItem(itemId(401), "https://example.com/first", "first")
        val duplicate = NewItem(itemId(401), "https://example.com/dup", "dup")

        val bookmarks = applyOne(SAMPLE_STATE, AddBookmarks(null, listOf(first, duplicate), ADDED))
        assertEquals(listOf(ROOT_ITEM_1, ROOT_ITEM_2, first.id), bookmarks.state.itemIds(ROOT))
        assertEquals("first", bookmarks.state.items.getValue(ROOT).last().title)

        val inserted = applyOne(
            SAMPLE_STATE,
            InsertItems(FOLDER_A, listOf(item(itemId(501), "first"), item(itemId(501), "dup")), null),
        )
        assertEquals(listOf(A_ITEM_1, A_ITEM_2, A_ITEM_3, itemId(501)), inserted.state.itemIds(FOLDER_A))
        assertEquals("first", inserted.state.items.getValue(FOLDER_A).last().title)

        val folder = applyOne(SAMPLE_STATE, AddFolder(folderId(50), null, "n", listOf(first, duplicate), ADDED))
        assertEquals(listOf(first.id), folder.state.itemIds(folderId(50)))
        assertEquals("first", folder.state.items.getValue(folderId(50)).single().title)
    }

    // 追加した日時は、ミリ秒未満を切り捨てた値で状態に入る（書いて読み直した値と一致する）
    @Test
    fun addedIsTruncatedToMilliseconds() {
        val precise = Instant.parse("2026-11-16T12:30:15.123456789Z")
        val truncated = Instant.parse("2026-11-16T12:30:15.123Z")

        val bookmarks = applyOne(SAMPLE_STATE, AddBookmarks(null, listOf(newItem1), precise))
        val folder = applyOne(SAMPLE_STATE, AddFolder(folderId(50), null, "n", listOf(newItem1), precise))

        assertEquals(truncated, bookmarks.state.items.getValue(ROOT).last().added)
        assertEquals(truncated, folder.state.items.getValue(folderId(50)).single().added)
    }

    // 新しい項目の URL は空でない
    @Test
    fun newItemRejectsEmptyUrl() {
        assertFailsWith<IllegalArgumentException> { NewItem(itemId(401), "", "t") }
    }

    // 項目の追加・挿入で項目が空なら、何もせず適用済みとして扱う
    @Test
    fun addingOrInsertingNoItemsIsAlreadyApplied() {
        assertEquals(OperationStatus.AlreadyApplied, applyOne(SAMPLE_STATE, AddBookmarks(null, emptyList(), ADDED)).singleStatus())
        assertEquals(OperationStatus.AlreadyApplied, applyOne(SAMPLE_STATE, InsertItems(FOLDER_A, emptyList(), null)).singleStatus())
    }

    // 利用者に伝えるもの：適用しなかった操作と、飛ばした対象がある操作だけ
    @Test
    fun hasNoticeForUserOnlyForNotAppliedOrSkippedTargets() {
        val missing = ItemRef(FOLDER_A, itemId(999))
        val result = applyOperations(
            SAMPLE_STATE,
            listOf(
                DeleteItems(listOf(missing)),
                RenameFolder(ROOT, "x"),
                MoveItems(listOf(ItemRef(FOLDER_A, A_ITEM_1), missing), FOLDER_B, null),
                RenameFolder(FOLDER_B, "B2"),
            ),
        )

        assertEquals(listOf(false, true, true, false), result.results.map { it.hasNoticeForUser })
    }

    // 項目のタイトルの変更：対象のフォルダまたは項目が無ければ適用しない
    @Test
    fun changeItemTitleOfMissingTargetIsNotApplied() {
        listOf(ItemRef(FOLDER_A, itemId(999)), ItemRef(folderId(99), A_ITEM_1), ItemRef(FOLDER_B, A_ITEM_1))
            .forEach { target ->
                val result = applyOne(SAMPLE_STATE, ChangeItemTitle(target, "x"))

                assertEquals(notApplied(NotAppliedReason.TargetNotFound), result.singleStatus(), "$target")
                assertTrue(result.unchanged, "$target")
            }
    }
}
