package to.sava.cloudmarksmultiplatform.sync

import kotlinx.coroutines.test.runTest
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.GetFault
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.Request
import to.sava.cloudmarksmultiplatform.storage.Item
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [StorageSync.readFolder]：読んであるツリーから、1 つのフォルダの項目ファイルだけを読む。
 * [StorageSync.readAfterMissing]：そのファイルが `404` を返したあとに、マニフェストを一度だけ読み直す。
 */
class FolderReadTest {
    /** マニフェストだけを読んだツリー（フォルダの項目は読んでいない）と、そのフィクスチャ。読んだあとのリクエストの数も返す。 */
    private suspend fun treeWithoutItems(fixture: SyncFixture): Pair<LoadedTree, Int> {
        val tree = assertIs<TreeReadResult.Readable>(fixture.sync.read()).tree
        return tree to fixture.store.requests.size
    }

    // ローカルキャッシュに無ければ、そのフォルダの項目ファイルだけを GET し（マニフェストは GET しない）、読めたものをローカルキャッシュに持つ
    @Test
    fun getsOnlyFolderFileAndKeepsIt() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        val (tree, requestsBefore) = treeWithoutItems(fixture)
        val key = fixture.itemsFileKey(FOLDER_A, A_FILE_ID)

        val result = fixture.sync.readFolder(tree, FOLDER_A)

        assertEquals(FolderReadResult.Loaded(A_ITEMS), result)
        assertEquals(listOf(Request.Get(key, ifNoneMatch = null)), fixture.store.requests.drop(requestsBefore))
        assertContentEquals(assertNotNull(fixture.store.objectAt(key)).body, fixture.cache.itemsFile(key))
    }

    // ローカルキャッシュにあれば GET しない。ローカルキャッシュのものを読んだ項目を返す
    @Test
    fun usesCachedFolderFileWithoutGet() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        val (tree, _) = treeWithoutItems(fixture)
        fixture.sync.readFolder(tree, FOLDER_A)
        val requestsBefore = fixture.store.requests.size

        val result = fixture.sync.readFolder(tree, FOLDER_A)

        assertEquals(FolderReadResult.Loaded(A_ITEMS), result)
        assertEquals(emptyList(), fixture.store.requests.drop(requestsBefore))
    }

    // 項目を持たないフォルダは、何も読まずに空として返す
    @Test
    fun folderWithoutItemsIsEmptyWithoutRequests() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        val (tree, requestsBefore) = treeWithoutItems(fixture)

        val result = fixture.sync.readFolder(tree, FOLDER_B)

        assertEquals(FolderReadResult.Loaded(emptyList()), result)
        assertEquals(emptyList(), fixture.store.requests.drop(requestsBefore))
    }

    // S3 の項目ファイルが検証に通らなければ、壊れているとして返し、ローカルキャッシュには持たない
    @Test
    fun corruptFolderFileIsBroken() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        val key = fixture.itemsFileKey(FOLDER_A, A_FILE_ID)
        fixture.store.overwrite(key, "not gzip".toByteArray())
        val (tree, _) = treeWithoutItems(fixture)

        val result = fixture.sync.readFolder(tree, FOLDER_A)

        assertIs<FolderReadResult.Broken>(result)
        assertNull(fixture.cache.itemsFile(key))
    }

    // 読めないときの結果：404 は無い（マニフェストは読み直さない）、5xx は繋がらない、403 は権限が無い
    @Test
    fun failuresDoNotRereadManifest() = runTest {
        val cases: List<Pair<(SyncFixture) -> Unit, FolderReadResult>> = listOf(
            { fixture: SyncFixture -> fixture.store.remove(fixture.itemsFileKey(FOLDER_A, A_FILE_ID)) } to
                FolderReadResult.Missing,
            { fixture: SyncFixture -> fixture.store.onNextGet(GetFault.ServerError, key = fixture.isItemsFileOf(FOLDER_A)) } to
                FolderReadResult.Failed(SyncFailure.Unreachable("HTTP 500")),
            { fixture: SyncFixture -> fixture.store.onNextGet(GetFault.Forbidden, key = fixture.isItemsFileOf(FOLDER_A)) } to
                FolderReadResult.Failed(SyncFailure.Forbidden),
        )
        cases.forEach { (arrange, expected) ->
            val fixture = SyncFixture().apply { store.seed() }
            val (tree, requestsBefore) = treeWithoutItems(fixture)
            arrange(fixture)

            val result = fixture.sync.readFolder(tree, FOLDER_A)

            assertEquals(expected, result)
            assertEquals(emptyList(), fixture.store.gets(fixture.isManifest).drop(1), "$expected")
            assertEquals(1, fixture.store.requests.drop(requestsBefore).size, "$expected")
        }
    }

    // ツリーに無いフォルダを渡すのは呼び出し側の誤り。項目ファイルを指さないフォルダで読み直すのも誤り
    @Test
    fun unknownFolderIsRejected() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        val (tree, _) = treeWithoutItems(fixture)

        assertFailsWith<IllegalArgumentException> { fixture.sync.readFolder(tree, folderId(99)) }
        assertFailsWith<IllegalArgumentException> { fixture.sync.readAfterMissing(tree, FOLDER_B) { emptySet() } }
    }

    // 404 のあとの読み直し：マニフェストを 1 回だけ GET し、読み直したマニフェストがまだ同じファイルを指していれば、
    // そのファイルを GET し直さずに壊れているとする
    @Test
    fun rereadAfterMissingWithSameFileIsBroken() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        val (tree, _) = treeWithoutItems(fixture)
        fixture.store.remove(fixture.itemsFileKey(FOLDER_A, A_FILE_ID))
        assertEquals(FolderReadResult.Missing, fixture.sync.readFolder(tree, FOLDER_A))
        val requestsBefore = fixture.store.requests.size

        val result = fixture.sync.readAfterMissing(tree, FOLDER_A) { setOf(FOLDER_A) }

        val reread = assertIs<TreeReadResult.Readable>(result).tree
        assertEquals(setOf(FOLDER_A), reread.brokenFolders.keys)
        assertEquals(
            listOf(Request.Get(fixture.manifestKey, ifNoneMatch = tree.eTag)),
            fixture.store.requests.drop(requestsBefore),
        )
    }

    // 404 のあとの読み直し：読み直したマニフェストが別のファイルを指していれば、そのファイルを読む。
    // そのファイルも 404 なら、壊れているとして、マニフェストをもう読み直さない
    @Test
    fun rereadAfterMissingReadsReplacedFile() = runTest {
        val newFileId = fileId(20)
        val newItems = listOf(item(30), item(31))
        // 新しいファイルがあるか、読み直したマニフェストのツリーでの A の項目、壊れているフォルダ
        val cases: List<Triple<Boolean, List<Item>?, Set<String>>> = listOf(
            Triple(true, newItems, emptySet()),
            Triple(false, null, setOf(FOLDER_A)),
        )
        cases.forEach { (newFilePresent, expectedItems, expectedBroken) ->
            val fixture = SyncFixture().apply { store.seed() }
            val (tree, _) = treeWithoutItems(fixture)
            val newKey = fixture.itemsFileKey(FOLDER_A, newFileId)
            // ほかの書き手が A の中身を替え、古いファイルは消された
            fixture.store.replaceItemsAsOtherWriter(FOLDER_A, newFileId, newItems)
            fixture.store.remove(fixture.itemsFileKey(FOLDER_A, A_FILE_ID))
            if (!newFilePresent) fixture.store.remove(newKey)
            assertEquals(FolderReadResult.Missing, fixture.sync.readFolder(tree, FOLDER_A))
            val requestsBefore = fixture.store.requests.size

            val result = fixture.sync.readAfterMissing(tree, FOLDER_A) { setOf(FOLDER_A) }

            val reread = assertIs<TreeReadResult.Readable>(result).tree
            assertEquals(fixture.store.storedManifest(), reread.manifest, "$newFilePresent")
            assertEquals(expectedItems, reread.state.items[FOLDER_A], "$newFilePresent")
            assertEquals(expectedBroken, reread.brokenFolders.keys, "$newFilePresent")
            assertEquals(
                listOf(Request.Get(fixture.manifestKey, ifNoneMatch = tree.eTag), Request.Get(newKey, ifNoneMatch = null)),
                fixture.store.requests.drop(requestsBefore),
                "$newFilePresent",
            )
        }
    }

    // 404 のあとの読み直しでマニフェストを読めなければ、読めなかったとして返す
    @Test
    fun rereadAfterMissingFailsWhenManifestIsUnreadable() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        val (tree, _) = treeWithoutItems(fixture)
        fixture.store.onNextGet(GetFault.ServerError, key = fixture.isManifest)

        val result = fixture.sync.readAfterMissing(tree, FOLDER_A) { setOf(FOLDER_A) }

        assertEquals(TreeReadResult.Failed(SyncFailure.Unreachable("HTTP 500")), result)
    }
}
