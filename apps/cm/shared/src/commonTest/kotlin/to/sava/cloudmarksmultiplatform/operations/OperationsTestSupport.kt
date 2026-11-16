package to.sava.cloudmarksmultiplatform.operations

import to.sava.cloudmarksmultiplatform.storage.FolderEntry
import to.sava.cloudmarksmultiplatform.storage.Item
import to.sava.cloudmarksmultiplatform.storage.Manifest
import to.sava.cloudmarksmultiplatform.storage.ManifestHeader
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

private fun uuid(prefix: Int, n: Int): String = "$prefix" + "0000000-0000-4000-8000-" + n.toString().padStart(12, '0')

internal fun folderId(n: Int): String = uuid(0, n)

internal fun fileId(n: Int): String = uuid(1, n)

internal fun itemId(n: Int): String = uuid(2, n)

internal val ROOT = folderId(0)
internal val FOLDER_A = folderId(1)
internal val FOLDER_A1 = folderId(2)
internal val FOLDER_B = folderId(3)
internal val FOLDER_C = folderId(4)

internal val ROOT_ITEM_1 = itemId(101)
internal val ROOT_ITEM_2 = itemId(102)
internal val A_ITEM_1 = itemId(201)
internal val A_ITEM_2 = itemId(202)
internal val A_ITEM_3 = itemId(203)
internal val B_ITEM_1 = itemId(301)

internal val ADDED = Instant.parse("2026-11-16T12:30:15.123Z")

internal fun item(id: String, title: String = "title-$id", added: Instant? = null): Item =
    Item(id, "https://example.com/$id", title, added)

/**
 * 根 → A（→ A1）、B、C の木。根・A・B は項目を持つ。A1 と C は項目を持たない。
 * 並びは 根、A、A1、B、C の行きがけ順。
 */
internal val SAMPLE_MANIFEST = Manifest(
    header = ManifestHeader(1, 1, "30000000-0000-4000-8000-000000000001", ADDED, "test"),
    folders = listOf(
        FolderEntry(ROOT, null, "", fileId(1), 2),
        FolderEntry(FOLDER_A, ROOT, "A", fileId(2), 3),
        FolderEntry(FOLDER_A1, FOLDER_A, "A1", null, 0),
        FolderEntry(FOLDER_B, ROOT, "B", fileId(3), 1),
        FolderEntry(FOLDER_C, ROOT, "C", null, 0),
    ),
)

/** [SAMPLE_MANIFEST] の木の状態。項目は、根と A と B を読み込んだ状態。 */
internal val SAMPLE_STATE: TreeState = SAMPLE_MANIFEST.toTreeState()
    .withLoadedItems(ROOT, listOf(item(ROOT_ITEM_1), item(ROOT_ITEM_2)))
    .withLoadedItems(FOLDER_A, listOf(item(A_ITEM_1), item(A_ITEM_2), item(A_ITEM_3)))
    .withLoadedItems(FOLDER_B, listOf(item(B_ITEM_1)))

internal fun TreeState.itemIds(folderId: String): List<String> =
    items.getValue(folderId).map { it.id }

internal fun TreeState.childIds(folderId: String): List<String> =
    folders.getValue(folderId).children

internal fun applyOne(state: TreeState, operation: Operation): ApplyResult =
    applyOperations(state, listOf(operation))

internal fun ApplyResult.singleStatus(): OperationStatus = results.single().status

internal fun notApplied(reason: NotAppliedReason): OperationStatus = OperationStatus.NotApplied(reason)

/** 操作を 1 回適用した結果の状態が、もう 1 回適用しても変わらず、2 回目は [expectedSecond] になることを確かめる。 */
internal fun assertIdempotent(
    state: TreeState,
    operation: Operation,
    expectedSecond: OperationStatus = OperationStatus.AlreadyApplied,
) {
    val first = applyOne(state, operation)
    val second = applyOne(first.state, operation)
    assertEquals(expectedSecond, second.singleStatus(), "2 回目の結果: $operation")
    assertEquals(first.state, second.state, "2 回目で状態が変わった: $operation")
    assertTrue(second.unchanged, "2 回目で変わらなかったことになっていない: $operation")
}
