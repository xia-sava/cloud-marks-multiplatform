package to.sava.cloudmarksmultiplatform.operations

import to.sava.cloudmarksmultiplatform.storage.FolderEntry
import to.sava.cloudmarksmultiplatform.storage.Manifest
import to.sava.cloudmarksmultiplatform.storage.ManifestDecodeResult
import to.sava.cloudmarksmultiplatform.storage.decodeManifest
import to.sava.cloudmarksmultiplatform.storage.encodeManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class TreeStateTest {
    // マニフェスト → ツリー → フォルダの行：変更が無ければ元の folders と等しい（項目を読み込んでいない状態でも、読み込んでいても）
    @Test
    fun manifestRoundTripKeepsFolders() {
        val unloaded = applyOperations(SAMPLE_MANIFEST.toTreeState(), emptyList())
        val loaded = applyOperations(SAMPLE_STATE, emptyList())

        assertEquals(SAMPLE_MANIFEST.folders, unloaded.toFolderEntries(emptyMap()))
        assertEquals(SAMPLE_MANIFEST.folders, loaded.toFolderEntries(emptyMap()))
    }

    // マニフェスト → ツリー：子の並びは行の順。項目を持たないフォルダは、最初から項目を読み込んだ状態（空）になる
    @Test
    fun toTreeStateBuildsChildrenInRowOrderAndPreloadsEmptyFolders() {
        val state = SAMPLE_MANIFEST.toTreeState()

        assertEquals(ROOT, state.rootId)
        assertEquals(listOf(FOLDER_A, FOLDER_B, FOLDER_C), state.childIds(ROOT))
        assertEquals(listOf(FOLDER_A1), state.childIds(FOLDER_A))
        assertEquals(setOf(FOLDER_A1, FOLDER_C), state.items.keys)
    }

    // ファイルを置くフォルダには新しいファイルの ID と項目の数が入り、変わっていないフォルダは元の items と count を引き継ぐ
    @Test
    fun toFolderEntriesAssignsNewFileIdsToWrittenFoldersOnly() {
        val result = applyOne(SAMPLE_STATE, DeleteItems(listOf(ItemRef(FOLDER_A, A_ITEM_1))))

        val entries = result.toFolderEntries(mapOf(FOLDER_A to fileId(90)))

        assertEquals(
            SAMPLE_MANIFEST.folders.map { if (it.id == FOLDER_A) it.copy(items = fileId(90), count = 2) else it },
            entries,
        )
    }

    // 項目が 0 件になったフォルダはファイルを置かないので、新しいファイルの ID は要らず、items は null、count は 0 になる
    @Test
    fun toFolderEntriesNullsItemsOfEmptiedFolder() {
        val result = applyOne(SAMPLE_STATE, DeleteItems(listOf(ItemRef(FOLDER_B, B_ITEM_1))))

        val entry = result.toFolderEntries(emptyMap()).single { it.id == FOLDER_B }

        assertEquals(FolderEntry(FOLDER_B, ROOT, "B", null, 0), entry)
    }

    // 並べ替えやタイトルの変更は項目の数が変わらないが、ファイルを置くフォルダなので新しいファイルの ID が要る。
    // 入れ忘れは例外になる
    @Test
    fun toFolderEntriesFailsWhenWrittenFolderHasNoNewFileId() {
        val changes = mapOf(
            "削除" to DeleteItems(listOf(ItemRef(FOLDER_A, A_ITEM_1))),
            "並べ替え" to MoveItems(listOf(ItemRef(FOLDER_A, A_ITEM_3)), FOLDER_A, before = A_ITEM_1),
            "タイトルの変更" to ChangeItemTitle(ItemRef(FOLDER_A, A_ITEM_1), "new"),
        )

        changes.forEach { (name, operation) ->
            val result = applyOne(SAMPLE_STATE, operation)

            assertEquals(setOf(FOLDER_A), result.itemsToWrite.keys, name)
            assertFailsWith<IllegalArgumentException>(name) { result.toFolderEntries(emptyMap()) }
        }
    }

    // 余計な ID（変わっていないフォルダ、項目が無くなったフォルダ、ツリーに無いフォルダ）を渡すと例外になる
    @Test
    fun toFolderEntriesFailsWhenGivenExtraFileIds() {
        val reordered = applyOne(
            SAMPLE_STATE,
            MoveItems(listOf(ItemRef(FOLDER_A, A_ITEM_3)), FOLDER_A, before = A_ITEM_1),
        )
        val emptied = applyOne(SAMPLE_STATE, DeleteItems(listOf(ItemRef(FOLDER_B, B_ITEM_1))))

        assertFailsWith<IllegalArgumentException>("変わっていないフォルダ") {
            reordered.toFolderEntries(mapOf(FOLDER_A to fileId(90), FOLDER_B to fileId(91)))
        }
        assertFailsWith<IllegalArgumentException>("項目が無くなったフォルダ") {
            emptied.toFolderEntries(mapOf(FOLDER_B to fileId(91)))
        }
        assertFailsWith<IllegalArgumentException>("ツリーに無いフォルダ") {
            reordered.toFolderEntries(mapOf(FOLDER_A to fileId(90), folderId(99) to fileId(91)))
        }
    }

    // フォルダとして追加したあとの行：項目ありなら（新しいファイルの ID, 件数）、項目なしなら（null, 0）
    @Test
    fun entriesAfterAddFolderCarryFileIdAndCountOrNull() {
        val withItems = applyOne(
            SAMPLE_STATE,
            AddFolder(folderId(50), null, "n", listOf(NewItem(itemId(401), "https://example.com/1", "t")), ADDED),
        )
        val withoutItems = applyOne(SAMPLE_STATE, AddFolder(folderId(51), null, "n", emptyList(), ADDED))

        assertEquals(
            FolderEntry(folderId(50), ROOT, "n", fileId(90), 1),
            withItems.toFolderEntries(mapOf(folderId(50) to fileId(90))).last(),
        )
        assertEquals(
            FolderEntry(folderId(51), ROOT, "n", null, 0),
            withoutItems.toFolderEntries(emptyMap()).last(),
        )
    }

    // フォルダの移動・作成・削除のあと、行は深さ優先の行きがけ順になり、マニフェストとして読める
    @Test
    fun entriesAfterTreeChangesAreDepthFirstPreOrderAndDecodable() {
        val operations = listOf(
            CreateFolder(folderId(50), FOLDER_A1, "A1-child", null),
            MoveFolder(FOLDER_A, FOLDER_C, before = null),
            MoveFolder(FOLDER_B, FOLDER_C, before = FOLDER_A),
            CreateFolder(folderId(51), ROOT, "first", before = FOLDER_C),
            DeleteFolder(folderId(99)),
        )
        val result = applyOperations(SAMPLE_STATE, operations)

        val entries = result.toFolderEntries(emptyMap())

        assertEquals(
            listOf(ROOT, folderId(51), FOLDER_C, FOLDER_B, FOLDER_A, FOLDER_A1, folderId(50)),
            entries.map { it.id },
        )
        assertDecodable(entries)
    }

    // フォルダの削除のあとの行も、親が先にある順で、マニフェストとして読める
    @Test
    fun entriesAfterDeleteAreDecodable() {
        val result = applyOperations(SAMPLE_STATE, listOf(DeleteFolder(FOLDER_A), DeleteFolder(FOLDER_C)))

        val entries = result.toFolderEntries(emptyMap())

        assertEquals(listOf(ROOT, FOLDER_B), entries.map { it.id })
        assertDecodable(entries)
    }

    private fun assertDecodable(entries: List<FolderEntry>) {
        val manifest = Manifest(SAMPLE_MANIFEST.header, entries)

        val decoded = decodeManifest(encodeManifest(manifest))

        assertIs<ManifestDecodeResult.Readable>(decoded, decoded.toString())
        assertEquals(manifest, decoded.manifest)
    }
}
