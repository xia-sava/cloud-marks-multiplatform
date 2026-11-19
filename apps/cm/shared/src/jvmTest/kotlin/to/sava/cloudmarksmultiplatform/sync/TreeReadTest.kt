package to.sava.cloudmarksmultiplatform.sync

import kotlinx.coroutines.test.runTest
import to.sava.cloudmarksmultiplatform.operations.TreeState
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.GetFault
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.Request
import to.sava.cloudmarksmultiplatform.storage.Manifest
import to.sava.cloudmarksmultiplatform.storage.encodeManifestGzip
import to.sava.cloudmarksmultiplatform.storage.newId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TreeReadTest {
    private val rootAndA: (TreeState) -> Set<String> = { setOf(ROOT_ID, FOLDER_A) }

    // キャッシュが無いとき：条件を付けずにマニフェストを GET し、求めたフォルダの項目ファイルを読み、読んだものをキャッシュに持つ
    @Test
    fun readsWithoutCacheAndKeepsWhatItRead() = runTest {
        val fixture = SyncFixture().apply { store.seed() }

        val result = fixture.sync.read(rootAndA)

        val tree = assertIs<TreeReadResult.Readable>(result).tree
        assertEquals(initialManifest(), tree.manifest)
        assertEquals(fixture.store.storedManifestETag(), tree.eTag)
        assertEquals(mapOf(ROOT_ID to ROOT_ITEMS, FOLDER_A to A_ITEMS, FOLDER_B to emptyList()), tree.state.items)
        assertEquals(emptyMap(), tree.brokenFolders)
        assertEquals(Request.Get(fixture.manifestKey, ifNoneMatch = null), fixture.store.requests.first())
        assertEquals(fixture.store.storedManifestETag(), fixture.cache.manifest()?.eTag)
        assertNotNull(fixture.cache.itemsFile(fixture.itemsFileKey(ROOT_ID, ROOT_FILE_ID)))
        assertNotNull(fixture.cache.itemsFile(fixture.itemsFileKey(FOLDER_A, A_FILE_ID)))
    }

    // 求めないフォルダの項目ファイルは読まない。項目を持たないフォルダは読まずに空として持つ
    @Test
    fun readsOnlyRequestedFoldersWithItems() = runTest {
        val fixture = SyncFixture().apply { store.seed() }

        val result = fixture.sync.read { setOf(FOLDER_A, FOLDER_B) }

        val tree = assertIs<TreeReadResult.Readable>(result).tree
        assertEquals(mapOf(FOLDER_A to A_ITEMS, FOLDER_B to emptyList()), tree.state.items)
        assertEquals(listOf(fixture.manifestKey, fixture.itemsFileKey(FOLDER_A, A_FILE_ID)), fixture.store.requests.map { it.key })
    }

    // 2 回目：ローカルキャッシュの ETag を If-None-Match に付け、304 ならローカルキャッシュのマニフェストを使う。ローカルキャッシュにある項目ファイルは GET しない
    @Test
    fun usesCachedManifestOnNotModifiedAndSkipsCachedFiles() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        val first = assertIs<TreeReadResult.Readable>(fixture.sync.read(rootAndA)).tree
        val requestsBefore = fixture.store.requests.size

        val second = assertIs<TreeReadResult.Readable>(fixture.sync.read(rootAndA)).tree

        assertEquals(first, second)
        assertEquals(
            listOf(Request.Get(fixture.manifestKey, ifNoneMatch = first.eTag)),
            fixture.store.requests.drop(requestsBefore),
        )
    }

    // マニフェストが替わっていれば 200 で読み直し、ローカルキャッシュのマニフェストと ETag を替える
    @Test
    fun replacesCachedManifestWhenChanged() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.sync.read()
        fixture.store.addFolderAsOtherWriter(folderId(9))

        val tree = assertIs<TreeReadResult.Readable>(fixture.sync.read()).tree

        assertEquals(fixture.store.storedManifest(), tree.manifest)
        assertEquals(fixture.store.storedManifestETag(), fixture.cache.manifest()?.eTag)
    }

    // 200 で受けたマニフェストをローカルキャッシュに持つとき、そのマニフェストが指す項目ファイルのキーだけを残す。
    // マニフェストが指さなくなった項目ファイルはローカルキャッシュから消え、指すものは残る
    @Test
    fun retainsOnlyItemsFilesReferencedByNewManifest() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        val rootKey = fixture.itemsFileKey(ROOT_ID, ROOT_FILE_ID)
        val folderAKey = fixture.itemsFileKey(FOLDER_A, A_FILE_ID)
        fixture.sync.read(rootAndA)
        assertEquals(listOf(setOf(rootKey, folderAKey)), fixture.cache.retained)

        val current = initialManifest()
        val withoutAItems = Manifest(
            header = current.header.copy(commit = newId()),
            folders = current.folders.map { if (it.id == FOLDER_A) it.copy(items = null, count = 0) else it },
        )
        fixture.store.overwrite(fixture.manifestKey, encodeManifestGzip(withoutAItems))
        fixture.sync.read()

        assertEquals(setOf(rootKey), fixture.cache.retained.last())
        assertNotNull(fixture.cache.itemsFile(rootKey))
        assertNull(fixture.cache.itemsFile(folderAKey))
    }

    // 読むだけにするマニフェスト（形式のバージョンが新しい）を 200 で読んだときも、そのマニフェストが指す項目ファイルのキーで retain を呼ぶ
    @Test
    fun retainsItemsFilesWhenManifestIsReadOnly() = runTest {
        val fixture = SyncFixture().apply { store.seed(initialManifest(version = 2, readableBy = 1)) }

        val result = fixture.sync.read()

        assertIs<TreeReadResult.ReadOnly>(result)
        assertEquals(
            listOf(setOf(fixture.itemsFileKey(ROOT_ID, ROOT_FILE_ID), fixture.itemsFileKey(FOLDER_A, A_FILE_ID))),
            fixture.cache.retained,
        )
        assertEquals(fixture.store.storedManifestETag(), fixture.cache.manifest()?.eTag)
    }

    // ローカルキャッシュの項目ファイルの中身が壊れていれば、ローカルキャッシュを丸ごと消し、ローカルキャッシュに何も無いものとして GET する。
    // S3 の正しい中身が読め、壊れていないほかの項目ファイルとマニフェストもローカルキャッシュから消え、
    // ローカルキャッシュに残るのは S3 から読み直した項目ファイルだけになる
    @Test
    fun corruptCachedItemsFileClearsCacheAndIsReplacedByGet() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        val rootKey = fixture.itemsFileKey(ROOT_ID, ROOT_FILE_ID)
        val aKey = fixture.itemsFileKey(FOLDER_A, A_FILE_ID)
        fixture.sync.read(rootAndA)
        fixture.cache.putItemsFile(aKey, "not gzip".toByteArray())
        val requestsBefore = fixture.store.requests.size

        val tree = assertIs<TreeReadResult.Readable>(fixture.sync.read(rootAndA)).tree

        assertEquals(mapOf(ROOT_ID to ROOT_ITEMS, FOLDER_A to A_ITEMS, FOLDER_B to emptyList()), tree.state.items)
        assertEquals(emptyMap(), tree.brokenFolders)
        assertEquals(
            listOf(Request.Get(fixture.manifestKey, ifNoneMatch = tree.eTag), Request.Get(aKey, ifNoneMatch = null)),
            fixture.store.requests.drop(requestsBefore),
        )
        assertNull(fixture.cache.manifest())
        assertNull(fixture.cache.itemsFile(rootKey))
        assertContentEquals(assertNotNull(fixture.store.objectAt(aKey)).body, fixture.cache.itemsFile(aKey))
    }

    // ローカルキャッシュのマニフェストの中身が壊れていて 304 が返ったときは、ローカルキャッシュを丸ごと消し、ローカルキャッシュにマニフェストが無いものとして
    // 条件を付けずに GET し直す。S3 のマニフェストが読め、ローカルキャッシュに持っていた項目ファイルも消えているので GET し直し、
    // ローカルキャッシュは S3 から読み直したマニフェストと項目ファイルだけになる
    @Test
    fun corruptCachedManifestOnNotModifiedClearsCacheAndIsReplacedByUnconditionalGet() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        val rootKey = fixture.itemsFileKey(ROOT_ID, ROOT_FILE_ID)
        val aKey = fixture.itemsFileKey(FOLDER_A, A_FILE_ID)
        fixture.sync.read(rootAndA)
        val eTag = fixture.store.storedManifestETag()
        fixture.cache.putManifest(CachedManifest("not gzip".toByteArray(), eTag))
        val requestsBefore = fixture.store.requests.size

        val tree = assertIs<TreeReadResult.Readable>(fixture.sync.read(rootAndA)).tree

        assertEquals(initialManifest(), tree.manifest)
        assertEquals(eTag, tree.eTag)
        assertEquals(mapOf(ROOT_ID to ROOT_ITEMS, FOLDER_A to A_ITEMS, FOLDER_B to emptyList()), tree.state.items)
        assertEquals(
            listOf(
                Request.Get(fixture.manifestKey, ifNoneMatch = eTag),
                Request.Get(fixture.manifestKey, ifNoneMatch = null),
                Request.Get(rootKey, ifNoneMatch = null),
                Request.Get(aKey, ifNoneMatch = null),
            ),
            fixture.store.requests.drop(requestsBefore),
        )
        assertContentEquals(assertNotNull(fixture.store.objectAt(fixture.manifestKey)).body, fixture.cache.manifest()?.body)
        assertEquals(eTag, fixture.cache.manifest()?.eTag)
        assertContentEquals(assertNotNull(fixture.store.objectAt(rootKey)).body, fixture.cache.itemsFile(rootKey))
        assertContentEquals(assertNotNull(fixture.store.objectAt(aKey)).body, fixture.cache.itemsFile(aKey))
    }

    // useCache = false のときは、ローカルキャッシュのマニフェストを読まず、条件を付けずに GET する。ローカルキャッシュのマニフェストは替えない
    @Test
    fun readManifestWithoutCacheDoesNotReadCachedManifest() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        val cached = CachedManifest("not gzip".toByteArray(), fixture.store.storedManifestETag())
        fixture.cache.putManifest(cached)
        val reader = TreeReader(fixture.store, ThrowingOnManifestRead(fixture.cache))

        val read = reader.readManifest(useCache = false)

        assertEquals(initialManifest(), assertIs<ManifestRead.Usable>(read).manifest)
        assertEquals(listOf(Request.Get(fixture.manifestKey, ifNoneMatch = null)), fixture.store.requests)
        assertEquals(cached, fixture.cache.manifest())
    }

    // 304 でローカルキャッシュのマニフェストをそのまま使うときは、ローカルキャッシュに持ち直さないので、項目ファイルも消さない
    @Test
    fun doesNotRetainWhenManifestIsNotModified() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.sync.read(rootAndA)
        val retainedBefore = fixture.cache.retained.size

        fixture.sync.read(rootAndA)

        assertEquals(retainedBefore, fixture.cache.retained.size)
    }

    // マニフェストが読めなかったとき（壊れている）は、ローカルキャッシュの項目ファイルを消さない
    @Test
    fun doesNotRetainWhenManifestIsUnusable() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.store.overwrite(fixture.manifestKey, "not gzip".toByteArray())

        fixture.sync.read()

        assertEquals(emptyList(), fixture.cache.retained)
    }

    // キャッシュが無くマニフェストが 404：「マニフェストが無い」として返し、マニフェストを作らない
    @Test
    fun missingManifestWithoutCacheIsNotFound() = runTest {
        val fixture = SyncFixture()

        val result = fixture.sync.read(rootAndA)

        assertEquals(TreeReadResult.Failed(SyncFailure.ManifestNotFound), result)
        assertTrue(fixture.store.requests.none { it is Request.Put })
        assertEquals(emptySet(), fixture.store.keys)
    }

    // キャッシュがあるのにマニフェストが 404：失敗（マニフェストが消されている）として返し、マニフェストを作らない
    @Test
    fun missingManifestWithCacheIsDeleted() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.sync.read()
        fixture.store.remove(fixture.manifestKey)

        val result = fixture.sync.read()

        assertEquals(TreeReadResult.Failed(SyncFailure.ManifestDeleted), result)
        assertTrue(fixture.store.requests.none { it is Request.Put })
    }

    // 形式のバージョンの 4 通り：version が知っているバージョン以下なら読み書きできる、readableBy ≤ 知っているバージョン < version なら読むだけ、
    // readableBy が新しければ更新が要る、検証に通らなければ壊れている
    @Test
    fun distinguishesFormatVersionsAndCorruption() = runTest {
        val cases: List<Pair<ByteArray, (TreeReadResult) -> Unit>> = listOf(
            encodeManifestGzip(initialManifest(version = 1, readableBy = 1)) to { result ->
                assertIs<TreeReadResult.Readable>(result)
            },
            encodeManifestGzip(initialManifest(version = 2, readableBy = 1)) to { result ->
                assertEquals(initialManifest(version = 2, readableBy = 1), assertIs<TreeReadResult.ReadOnly>(result).tree.manifest)
            },
            encodeManifestGzip(initialManifest(version = 2, readableBy = 2)) to { result ->
                assertEquals(TreeReadResult.Failed(SyncFailure.UpdateRequired(version = 2, readableBy = 2)), result)
            },
            "not gzip".toByteArray() to { result ->
                assertIs<SyncFailure.ManifestCorrupt>(assertIs<TreeReadResult.Failed>(result).failure)
            },
        )

        cases.forEach { (body, check) ->
            val fixture = SyncFixture().apply { store.seed() }
            fixture.store.overwrite(fixture.manifestKey, body)

            check(fixture.sync.read(rootAndA))
        }
    }

    // 項目ファイルが 404：マニフェストを一度だけ読み直し、まだ同じファイルを指していれば、そのファイルを GET し直さずに
    // 壊れているとみなす。ほかのフォルダは読める
    @Test
    fun missingItemsFileStillReferencedAfterRereadIsBroken() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        val aKey = fixture.itemsFileKey(FOLDER_A, A_FILE_ID)
        fixture.store.remove(aKey)

        val result = fixture.sync.read(rootAndA)

        val tree = assertIs<TreeReadResult.Readable>(result).tree
        assertEquals(setOf(FOLDER_A), tree.brokenFolders.keys)
        assertEquals(ROOT_ITEMS, tree.state.items[ROOT_ID])
        assertEquals(null, tree.state.items[FOLDER_A])
        assertEquals(2, fixture.store.gets(fixture.isManifest).size)
        assertEquals(1, fixture.store.gets { it == aKey }.size)
    }

    // 項目ファイルが 404 で、読み直したマニフェストが別のファイルを指していれば、そのファイルを読む
    @Test
    fun missingItemsFileReplacedInRereadManifestIsRead() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        val oldKey = fixture.itemsFileKey(FOLDER_A, A_FILE_ID)
        val newFileId = fileId(20)
        val newItems = listOf(item(30), item(31))
        fixture.store.remove(oldKey)
        fixture.store.onNextGet(
            // ほかの書き手が A の中身を替え、古いファイルは消された
            GetFault.RunBefore { fixture.store.replaceItemsAsOtherWriter(FOLDER_A, newFileId, newItems) },
            key = { it == oldKey },
        )

        val result = fixture.sync.read(rootAndA)

        val tree = assertIs<TreeReadResult.Readable>(result).tree
        assertEquals(emptyMap(), tree.brokenFolders)
        assertEquals(newItems, tree.state.items[FOLDER_A])
        assertEquals(newFileId, tree.manifest.folders.single { it.id == FOLDER_A }.items)
    }

    // 項目ファイルが検証に通らなければ、そのフォルダを壊れているとして返し、ほかのフォルダは読める
    @Test
    fun corruptItemsFileIsReportedPerFolder() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        // マニフェストの count（1）と項目の数が合わない
        fixture.store.overwrite(fixture.itemsFileKey(FOLDER_A, A_FILE_ID), itemsFileBody(FOLDER_A, listOf(item(3), item(4))))

        val tree = assertIs<TreeReadResult.Readable>(fixture.sync.read(rootAndA)).tree

        assertEquals(setOf(FOLDER_A), tree.brokenFolders.keys)
        assertEquals(ROOT_ITEMS, tree.state.items[ROOT_ID])
        assertEquals(null, fixture.cache.itemsFile(fixture.itemsFileKey(FOLDER_A, A_FILE_ID)))
    }

    // マニフェストの GET が 5xx：S3 につながらない失敗
    @Test
    fun serverErrorOnManifestIsUnreachable() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.store.onNextGet(GetFault.ServerError)

        val result = fixture.sync.read(rootAndA)

        assertIs<SyncFailure.Unreachable>(assertIs<TreeReadResult.Failed>(result).failure)
    }

    // 項目ファイルの GET が 5xx：読めなかったとして失敗にし、残りのフォルダは GET しない
    @Test
    fun serverErrorOnItemsFileFailsTheRead() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.store.onNextGet(GetFault.ServerError, key = fixture.isItemsFileOf(ROOT_ID))

        val result = fixture.sync.read(rootAndA)

        assertIs<SyncFailure.Unreachable>(assertIs<TreeReadResult.Failed>(result).failure)
        assertEquals(0, fixture.store.gets(fixture.isItemsFileOf(FOLDER_A)).size)
    }

    // ローカルキャッシュに持つマニフェストは GET で受けたバイト列そのもの
    @Test
    fun cachesManifestBodyAsReceived() = runTest {
        val fixture = SyncFixture().apply { store.seed() }

        fixture.sync.read()

        assertContentEquals(
            assertNotNull(fixture.store.objectAt(fixture.manifestKey)).body,
            fixture.cache.manifest()?.body,
        )
    }
}

/** [delegate] と同じに振る舞うが、ローカルキャッシュのマニフェストを読むと失敗にする。 */
private class ThrowingOnManifestRead(private val delegate: SyncCache) : SyncCache by delegate {
    override suspend fun manifest(): CachedManifest? = error("ローカルキャッシュのマニフェストを読んではいけない")
}
