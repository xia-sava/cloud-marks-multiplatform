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

    // キャッシュが無いとき：条件を付けずに目録を GET し、求めたフォルダのファイルを読み、読んだものをキャッシュに持つ
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

    // 求めないフォルダのファイルは読まない。項目を持たないフォルダは読まずに空として持つ
    @Test
    fun readsOnlyRequestedFoldersWithItems() = runTest {
        val fixture = SyncFixture().apply { store.seed() }

        val result = fixture.sync.read { setOf(FOLDER_A, FOLDER_B) }

        val tree = assertIs<TreeReadResult.Readable>(result).tree
        assertEquals(mapOf(FOLDER_A to A_ITEMS, FOLDER_B to emptyList()), tree.state.items)
        assertEquals(listOf(fixture.manifestKey, fixture.itemsFileKey(FOLDER_A, A_FILE_ID)), fixture.store.requests.map { it.key })
    }

    // 2 回目：手元の ETag を If-None-Match に付け、304 なら手元の目録を使う。手元にあるフォルダのファイルは GET しない
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

    // 目録が替わっていれば 200 で読み直し、手元の目録と ETag を替える
    @Test
    fun replacesCachedManifestWhenChanged() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.sync.read()
        fixture.store.addFolderAsOtherWriter(folderId(9))

        val tree = assertIs<TreeReadResult.Readable>(fixture.sync.read()).tree

        assertEquals(fixture.store.storedManifest(), tree.manifest)
        assertEquals(fixture.store.storedManifestETag(), fixture.cache.manifest()?.eTag)
    }

    // 200 で受けた目録を手元に持つとき、その目録が指すフォルダのファイルのキーだけを残す。
    // 目録が指さなくなったフォルダのファイルは手元から消え、指すものは残る
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

    // 読むだけにする目録（版が新しい）を 200 で読んだときも、その目録が指すフォルダのファイルのキーで retain を呼ぶ
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

    // 手元のフォルダのファイルの中身が壊れていれば、手元のキャッシュを丸ごと消し、手元に何も無いものとして GET する。
    // S3 の正しい中身が読め、壊れていないほかのフォルダのファイルと目録も手元から消え、
    // 手元に残るのは S3 から読み直したフォルダのファイルだけになる
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

    // 手元の目録の中身が壊れていて 304 が返ったときは、手元のキャッシュを丸ごと消し、手元に目録が無いものとして
    // 条件を付けずに GET し直す。S3 の目録が読め、手元に持っていたフォルダのファイルも消えているので GET し直し、
    // 手元は S3 から読み直した目録とフォルダのファイルだけになる
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

    // useCache = false のときは、手元の目録を読まず、条件を付けずに GET する。手元の目録は替えない
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

    // 304 で手元の目録をそのまま使うときは、手元に持ち直さないので、フォルダのファイルも消さない
    @Test
    fun doesNotRetainWhenManifestIsNotModified() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.sync.read(rootAndA)
        val retainedBefore = fixture.cache.retained.size

        fixture.sync.read(rootAndA)

        assertEquals(retainedBefore, fixture.cache.retained.size)
    }

    // 目録が読めなかったとき（壊れている）は、手元のフォルダのファイルを消さない
    @Test
    fun doesNotRetainWhenManifestIsUnusable() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.store.overwrite(fixture.manifestKey, "not gzip".toByteArray())

        fixture.sync.read()

        assertEquals(emptyList(), fixture.cache.retained)
    }

    // キャッシュが無く目録が 404：「目録が無い」として返し、目録を作らない
    @Test
    fun missingManifestWithoutCacheIsNotFound() = runTest {
        val fixture = SyncFixture()

        val result = fixture.sync.read(rootAndA)

        assertEquals(TreeReadResult.Failed(SyncFailure.ManifestNotFound), result)
        assertTrue(fixture.store.requests.none { it is Request.Put })
        assertEquals(emptySet(), fixture.store.keys)
    }

    // キャッシュがあるのに目録が 404：失敗（目録が消されている）として返し、目録を作らない
    @Test
    fun missingManifestWithCacheIsDeleted() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.sync.read()
        fixture.store.remove(fixture.manifestKey)

        val result = fixture.sync.read()

        assertEquals(TreeReadResult.Failed(SyncFailure.ManifestDeleted), result)
        assertTrue(fixture.store.requests.none { it is Request.Put })
    }

    // 版の 4 通り：version が知っている版以下なら読み書きできる、readableBy ≤ 知っている版 < version なら読むだけ、
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

    // フォルダのファイルが 404：目録を一度だけ読み直し、まだ同じファイルを指していれば、そのファイルを GET し直さずに
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

    // フォルダのファイルが 404 で、読み直した目録が別のファイルを指していれば、そのファイルを読む
    @Test
    fun missingItemsFileReplacedInRereadManifestIsRead() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        val oldKey = fixture.itemsFileKey(FOLDER_A, A_FILE_ID)
        val newFileId = fileId(20)
        val newItems = listOf(item(30), item(31))
        fixture.store.remove(oldKey)
        fixture.store.onNextGet(
            GetFault.RunBefore {
                // ほかの書き手が A の中身を替え、古いファイルは消された
                fixture.store.overwrite(fixture.itemsFileKey(FOLDER_A, newFileId), itemsFileBody(FOLDER_A, newItems))
                val manifest = initialManifest().let { manifest ->
                    manifest.copy(
                        header = manifest.header.copy(commit = newId()),
                        folders = manifest.folders.map { if (it.id == FOLDER_A) it.copy(items = newFileId, count = newItems.size) else it },
                    )
                }
                fixture.store.overwrite(fixture.manifestKey, encodeManifestGzip(manifest))
            },
            key = { it == oldKey },
        )

        val result = fixture.sync.read(rootAndA)

        val tree = assertIs<TreeReadResult.Readable>(result).tree
        assertEquals(emptyMap(), tree.brokenFolders)
        assertEquals(newItems, tree.state.items[FOLDER_A])
        assertEquals(newFileId, tree.manifest.folders.single { it.id == FOLDER_A }.items)
    }

    // フォルダのファイルが検証に通らなければ、そのフォルダを壊れているとして返し、ほかのフォルダは読める
    @Test
    fun corruptItemsFileIsReportedPerFolder() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        // 目録の count（1）と項目の数が合わない
        fixture.store.overwrite(fixture.itemsFileKey(FOLDER_A, A_FILE_ID), itemsFileBody(FOLDER_A, listOf(item(3), item(4))))

        val tree = assertIs<TreeReadResult.Readable>(fixture.sync.read(rootAndA)).tree

        assertEquals(setOf(FOLDER_A), tree.brokenFolders.keys)
        assertEquals(ROOT_ITEMS, tree.state.items[ROOT_ID])
        assertEquals(null, fixture.cache.itemsFile(fixture.itemsFileKey(FOLDER_A, A_FILE_ID)))
    }

    // 目録の GET が 5xx：S3 につながらない失敗
    @Test
    fun serverErrorOnManifestIsUnreachable() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.store.onNextGet(GetFault.ServerError)

        val result = fixture.sync.read(rootAndA)

        assertIs<SyncFailure.Unreachable>(assertIs<TreeReadResult.Failed>(result).failure)
    }

    // フォルダのファイルの GET が 5xx：読めなかったとして失敗にし、残りのフォルダは GET しない
    @Test
    fun serverErrorOnItemsFileFailsTheRead() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.store.onNextGet(GetFault.ServerError, key = fixture.isItemsFileOf(ROOT_ID))

        val result = fixture.sync.read(rootAndA)

        assertIs<SyncFailure.Unreachable>(assertIs<TreeReadResult.Failed>(result).failure)
        assertEquals(0, fixture.store.gets(fixture.isItemsFileOf(FOLDER_A)).size)
    }

    // 手元に持つ目録は GET で受けたバイト列そのもの
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

/** [delegate] と同じに振る舞うが、手元の目録を読むと失敗にする。 */
private class ThrowingOnManifestRead(private val delegate: SyncCache) : SyncCache by delegate {
    override suspend fun manifest(): CachedManifest? = error("手元の目録を読んではいけない")
}
