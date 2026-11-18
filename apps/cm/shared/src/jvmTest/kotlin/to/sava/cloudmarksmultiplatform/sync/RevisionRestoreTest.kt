package to.sava.cloudmarksmultiplatform.sync

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import to.sava.cloudmarksmultiplatform.operations.AddBookmarks
import to.sava.cloudmarksmultiplatform.operations.NewItem
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.GetFault
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.ListFault
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.PutFault
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.Request
import to.sava.cloudmarksmultiplatform.remote.PutCondition
import to.sava.cloudmarksmultiplatform.storage.FolderEntry
import to.sava.cloudmarksmultiplatform.storage.Item
import to.sava.cloudmarksmultiplatform.storage.Manifest
import to.sava.cloudmarksmultiplatform.storage.encodeManifestGzip
import to.sava.cloudmarksmultiplatform.storage.newId
import to.sava.cloudmarksmultiplatform.storage.truncatedToMilliseconds
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** ほかの書き手があとから置く、ルートフォルダの項目ファイルの ID と項目。 */
private val LATER_ROOT_FILE_ID = fileId(5)
private val LATER_ROOT_ITEMS = ROOT_ITEMS + item(9)

/** [initialManifest] の時点の、項目ファイルの ID を除いたフォルダの行と、各フォルダの項目。 */
private val INITIAL_CONTENTS: List<Pair<FolderEntry, List<Item>>> =
    initialManifest().folders.map { it.copy(items = null) }.zip(listOf(ROOT_ITEMS, A_ITEMS, emptyList()))

/** [manifest] の、項目ファイルの ID を除いたフォルダの行と、各フォルダの置いてある項目。 */
private fun FakeObjectStore.contentsOf(manifest: Manifest): List<Pair<FolderEntry, List<Item>>> =
    manifest.folders.map { it.copy(items = null) to storedItems(manifest, it.id) }

/** 置いてあるマニフェストの、項目ファイルの ID を除いたフォルダの行と、各フォルダの置いてある項目。 */
private fun FakeObjectStore.storedContents(): List<Pair<FolderEntry, List<Item>>> = contentsOf(storedManifest())

/** リクエストが同時に走っていた数の最大を数える。 */
private class ConcurrencyCounter {
    private var running = 0

    var max = 0
        private set

    /** 走っている数に 1 を足して待ち、1 を引く。待つあいだに、並べて送ったほかのリクエストが走る。 */
    suspend fun hold() {
        running += 1
        max = maxOf(max, running)
        delay(1)
        running -= 1
    }
}

/** 戻せる時点の状態へ戻す（[StorageSync.restoreRevision]）。 */
class RevisionRestoreTest {
    /** [initialManifest] が指すルートフォルダの項目ファイルのキー。 */
    private val SyncFixture.initialRootKey: String get() = itemsFileKey(ROOT_ID, ROOT_FILE_ID)

    /** [initialManifest] が指す A の項目ファイルのキー。 */
    private val SyncFixture.initialAKey: String get() = itemsFileKey(FOLDER_A, A_FILE_ID)

    /**
     * [initialManifest] とその項目ファイルを置き、その時点を覚えてから [change] でいまの状態を替え、覚えた時点を読んで返す。
     * [change] の既定は、ほかの書き手としてルートフォルダの項目を [LATER_ROOT_ITEMS] に替える。
     */
    private suspend fun SyncFixture.prepare(change: SyncFixture.() -> Unit = { replaceRootAsOtherWriter() }): LoadedRevision {
        store.seed()
        val revision = currentManifestRevision()
        change()
        return loadRevision(revision)
    }

    private fun SyncFixture.replaceRootAsOtherWriter() {
        store.replaceItemsAsOtherWriter(ROOT_ID, LATER_ROOT_FILE_ID, LATER_ROOT_ITEMS)
    }

    private fun SyncFixture.replaceManifest(manifest: Manifest) {
        store.overwrite(manifestKey, encodeManifestGzip(manifest))
    }

    /** [key] を消し、古いバージョンも消えたものとする。削除マーカーだけが残る。 */
    private fun SyncFixture.removeAllVersions(key: String) {
        store.remove(key)
        store.expireNoncurrentVersions(key)
    }

    /** 以降のマニフェストの GET を、再送の上限を超えるまで 5xx にする。 */
    private fun SyncFixture.failManifestRereads() {
        repeat(MAX_RESENDS + 1) { store.onNextGet(GetFault.ServerError, key = isManifest) }
    }

    private fun SyncFixture.requestsFor(key: String): List<Request> = store.requests.filter { it.key == key }

    /** [key] の、削除マーカーでないただ 1 つのバージョンの ID。 */
    private fun SyncFixture.onlyVersionOf(key: String): String = store.versionsOf(key).single { it.stored != null }.versionId

    // フォルダの並びが戻す時点のものになる。いまと同じ項目ファイルを指すフォルダ（A）は、GET も PUT もせずにそのファイルを指す。
    // 違うファイルを指すフォルダ（ルートフォルダ）は、戻す時点のファイルの中身を、新しいファイルの ID で If-None-Match: * を付けて置く。
    // 戻す時点で項目の無いフォルダ（B）は items が null になり、いまだけにあるフォルダ（C）は無くなる
    @Test
    fun restoresFoldersOfTheRevision() = runTest {
        val fixture = SyncFixture()
        val laterBFileId = fileId(6)
        val loaded = fixture.prepare {
            store.overwrite(itemsFileKey(ROOT_ID, LATER_ROOT_FILE_ID), itemsFileBody(ROOT_ID, LATER_ROOT_ITEMS))
            store.overwrite(itemsFileKey(FOLDER_B, laterBFileId), itemsFileBody(FOLDER_B, listOf(item(7))))
            val (root, a, b) = initialManifest().folders
            replaceManifest(
                Manifest(
                    header = initialManifest().header.copy(commit = newId()),
                    folders = listOf(
                        root.copy(items = LATER_ROOT_FILE_ID, count = LATER_ROOT_ITEMS.size),
                        a,
                        b.copy(items = laterBFileId, count = 1),
                        FolderEntry(folderId(3), ROOT_ID, "C", null, 0),
                    ),
                ),
            )
        }

        assertIs<RestoreResult.Success>(fixture.sync.restoreRevision(loaded))

        val stored = fixture.store.storedManifest()
        val rootFileId = assertNotNull(stored.folders.first().items)
        assertTrue(rootFileId !in setOf(ROOT_FILE_ID, LATER_ROOT_FILE_ID))
        assertEquals(initialManifest().folders.map { if (it.id == ROOT_ID) it.copy(items = rootFileId) else it }, stored.folders)
        assertEquals(INITIAL_CONTENTS, fixture.store.contentsOf(stored))
        val filePuts = fixture.store.puts { it != fixture.manifestKey }
        assertEquals(listOf(fixture.itemsFileKey(ROOT_ID, rootFileId)), filePuts.map { it.key })
        assertEquals(PutCondition.IfNoneMatchAny, filePuts.single().condition)
        assertEquals(emptyList(), fixture.store.requests.filter { fixture.isItemsFileOf(FOLDER_A)(it.key) })
    }

    // 書くマニフェストは、commit が新しく、updatedAt が時計の現在をミリ秒に切り詰めたもの、updatedBy が書き手のもの、
    // version と readableBy が戻す時点のもの。If-Match は手順 1 で読んだいまのマニフェストの ETag。
    // 書いたマニフェストと ETag、置いたファイルをローカルキャッシュに持ち、置いたフォルダの項目を読み込んだツリーを返す
    @Test
    fun writesManifestHeaderAndUpdatesCache() = runTest {
        val fixture = SyncFixture()
        val loaded = fixture.prepare()
        val currentETag = fixture.store.storedManifestETag()
        val currentCommit = fixture.store.storedManifest().header.commit

        val success = assertIs<RestoreResult.Success>(fixture.sync.restoreRevision(loaded))

        val stored = fixture.store.storedManifest()
        assertTrue(stored.header.commit !in setOf(INITIAL_COMMIT, currentCommit))
        assertEquals(NOW.truncatedToMilliseconds(), stored.header.updatedAt)
        assertEquals(UPDATED_BY, stored.header.updatedBy)
        assertEquals(
            loaded.manifest.header.version to loaded.manifest.header.readableBy,
            stored.header.version to stored.header.readableBy,
        )
        assertEquals(PutCondition.IfMatch(currentETag), fixture.store.puts(fixture.isManifest).single().condition)

        assertEquals(stored, success.tree.manifest)
        assertEquals(fixture.store.storedManifestETag(), success.tree.eTag)
        assertEquals(fixture.store.storedManifestETag(), fixture.cache.manifest()?.eTag)
        assertNotNull(fixture.cache.itemsFile(fixture.itemsFileKey(ROOT_ID, assertNotNull(stored.folders.first().items))))
        assertEquals(mapOf(ROOT_ID to ROOT_ITEMS, FOLDER_B to emptyList()), success.tree.state.items)
        assertEquals(emptyMap(), success.tree.brokenFolders)
    }

    // いまのマニフェストに無いフォルダ（消された A）は、戻す時点のファイルの中身を新しいファイルの ID で置いて戻す。
    // いまだけにあるフォルダ（あとから作られた C）は無くなる
    @Test
    fun restoresRemovedFolderAndDropsAddedFolder() = runTest {
        val fixture = SyncFixture()
        val folderC = folderId(3)
        val loaded = fixture.prepare {
            val cFileId = fileId(8)
            store.overwrite(itemsFileKey(folderC, cFileId), itemsFileBody(folderC, listOf(item(8))))
            val (root, _, b) = initialManifest().folders
            replaceManifest(
                Manifest(
                    header = initialManifest().header.copy(commit = newId()),
                    folders = listOf(root, b, FolderEntry(folderC, ROOT_ID, "C", cFileId, 1)),
                ),
            )
        }

        assertIs<RestoreResult.Success>(fixture.sync.restoreRevision(loaded))

        val stored = fixture.store.storedManifest()
        val aFileId = assertNotNull(stored.folders.single { it.id == FOLDER_A }.items)
        assertNotEquals(A_FILE_ID, aFileId)
        assertEquals(initialManifest().folders.map { if (it.id == FOLDER_A) it.copy(items = aFileId) else it }, stored.folders)
        assertEquals(INITIAL_CONTENTS, fixture.store.contentsOf(stored))
        assertEquals(listOf(fixture.itemsFileKey(FOLDER_A, aFileId)), fixture.store.puts { it != fixture.manifestKey }.map { it.key })
    }

    // 戻す時点のファイルが古いファイルの削除で消されている（削除マーカーが付いている）：GET の 404 のあと、そのキーのバージョンを一覧し、
    // 削除マーカーでない最も新しいバージョンから中身を得て戻す。削除マーカーは外さず、DELETE を送らない
    @Test
    fun takesContentOfDeletedFileFromOlderVersion() = runTest {
        val fixture = SyncFixture()
        val loaded = fixture.prepare {
            replaceRootAsOtherWriter()
            store.remove(initialRootKey)
        }
        val key = fixture.initialRootKey
        val versionId = fixture.onlyVersionOf(key)

        assertIs<RestoreResult.Success>(fixture.sync.restoreRevision(loaded))

        assertEquals(INITIAL_CONTENTS, fixture.store.storedContents())
        assertEquals(
            listOf(Request.Get(key, null), Request.ListVersionsPage(key, null), Request.GetVersion(key, versionId)),
            fixture.requestsFor(key),
        )
        assertNull(fixture.store.objectAt(key))
        assertNull(fixture.store.versionsOf(key).first().stored)
        assertTrue(fixture.store.requests.none { it is Request.Delete })
    }

    // 戻す時点のファイルが消されていて、GET が 404 でなく 403 を返す（s3:ListBucket が無い）：古いバージョンから中身を得て戻す
    @Test
    fun forbiddenGetTakesContentFromOlderVersion() = runTest {
        val fixture = SyncFixture()
        val loaded = fixture.prepare {
            replaceRootAsOtherWriter()
            store.remove(initialRootKey)
        }
        val key = fixture.initialRootKey
        val versionId = fixture.onlyVersionOf(key)
        fixture.store.onNextGet(GetFault.Forbidden, key = { it == key })

        assertIs<RestoreResult.Success>(fixture.sync.restoreRevision(loaded))

        assertEquals(INITIAL_CONTENTS, fixture.store.storedContents())
        assertEquals(
            listOf(Request.Get(key, null), Request.ListVersionsPage(key, null), Request.GetVersion(key, versionId)),
            fixture.requestsFor(key),
        )
    }

    // 戻す時点のファイルのどのバージョンも残っていない（削除マーカーだけ）：残っていないとして返し、PUT を 1 つも送らない
    @Test
    fun missingFileIsIncompleteWithoutPuts() = runTest {
        val fixture = SyncFixture()
        val loaded = fixture.prepare {
            replaceRootAsOtherWriter()
            removeAllVersions(initialRootKey)
        }
        val manifestBefore = fixture.store.storedManifest()

        val result = fixture.sync.restoreRevision(loaded)

        assertEquals(RestoreResult.Failed(RevisionFailure.Incomplete), result)
        assertEquals(emptyList(), fixture.store.puts { true })
        assertEquals(manifestBefore, fixture.store.storedManifest())
    }

    // 戻す時点のファイルが壊れている（項目の数が count と合わない、folder がフォルダの ID と合わない）：
    // 壊れているとして返し、PUT を 1 つも送らない
    @Test
    fun corruptFileIsCorruptWithoutPuts() = runTest {
        val cases = listOf(
            "count" to itemsFileBody(ROOT_ID, ROOT_ITEMS + item(9)),
            "folder" to itemsFileBody(FOLDER_B, ROOT_ITEMS),
        )

        cases.forEach { (case, body) ->
            val fixture = SyncFixture()
            val loaded = fixture.prepare {
                replaceRootAsOtherWriter()
                store.overwrite(initialRootKey, body)
            }

            val result = fixture.sync.restoreRevision(loaded)

            assertIs<RevisionFailure.Corrupt>(assertIs<RestoreResult.Failed>(result, case).failure, case)
            assertEquals(emptyList(), fixture.store.puts { true }, case)
        }
    }

    // 戻す時点のファイルを得られず 403 に当たる：GET の 404 のあとのバージョンの一覧か古いバージョンの GET が 403、
    // GET の 403 のあとに古いバージョンが無いかバージョンの一覧が 403。どれも権限が無い失敗として返し、PUT を 1 つも送らない
    @Test
    fun forbiddenWhileFetchingFailsWithoutPuts() = runTest {
        val cases: List<Pair<String, SyncFixture.() -> Unit>> = listOf(
            "404 のあとのバージョンの一覧が 403" to {
                store.remove(initialRootKey)
                store.onNextListVersions(ListFault.Forbidden, key = { it == initialRootKey })
            },
            "404 のあとの古いバージョンの GET が 403" to {
                store.remove(initialRootKey)
                // GET と古いバージョンの GET は同じ事象の列を使うので、GET に当たる何もしない事象を先に積む
                store.onNextGet(GetFault.RunBefore {}, key = { it == initialRootKey })
                store.onNextGet(GetFault.Forbidden, key = { it == initialRootKey })
            },
            "403 のあとに古いバージョンが無い" to {
                removeAllVersions(initialRootKey)
                store.onNextGet(GetFault.Forbidden, key = { it == initialRootKey })
            },
            "403 のあとのバージョンの一覧が 403" to {
                store.onNextGet(GetFault.Forbidden, key = { it == initialRootKey })
                store.onNextListVersions(ListFault.Forbidden, key = { it == initialRootKey })
            },
        )

        cases.forEach { (case, arrange) ->
            val fixture = SyncFixture()
            val loaded = fixture.prepare()
            fixture.arrange()

            val result = fixture.sync.restoreRevision(loaded)

            assertEquals(RestoreResult.Failed(RevisionFailure.Sync(SyncFailure.Forbidden)), result, case)
            assertEquals(emptyList(), fixture.store.puts { true }, case)
        }
    }

    // 戻す時点のファイルを古いバージョンから得られない：バージョンの一覧のあとに古いバージョンが消えた（古いバージョンの GET が 404）なら、
    // GET の 404 が起点のときは残っていない、403 が起点のときは権限が無い失敗。バージョンの一覧か古いバージョンの GET の結果が不明なら、
    // つながらない失敗。どれも PUT を 1 つも送らない
    @Test
    fun failuresWhileFetchingOlderVersionFailWithoutPuts() = runTest {
        class Case(
            val name: String,
            val arrange: SyncFixture.() -> Unit,
            val expected: RevisionFailure,
            val lastRequest: KClass<out Request>,
        )
        // GET と古いバージョンの GET は同じ事象の列を使うので、古いバージョンの GET に事象を差すときは、GET に当たる事象を先に積む
        val cases = listOf(
            Case(
                "404 のあと、古いバージョンの GET が 404",
                {
                    store.onNextGet(GetFault.RunBefore {}, key = { it == initialRootKey })
                    store.onNextGet(GetFault.RunBefore { store.expireNoncurrentVersions(initialRootKey) }, key = { it == initialRootKey })
                },
                RevisionFailure.Incomplete,
                Request.GetVersion::class,
            ),
            Case(
                "403 のあと、古いバージョンの GET が 404",
                {
                    store.onNextGet(GetFault.Forbidden, key = { it == initialRootKey })
                    store.onNextGet(GetFault.RunBefore { store.expireNoncurrentVersions(initialRootKey) }, key = { it == initialRootKey })
                },
                RevisionFailure.Sync(SyncFailure.Forbidden),
                Request.GetVersion::class,
            ),
            Case(
                "404 のあと、バージョンの一覧の結果が不明",
                { store.onNextListVersions(ListFault.ServerError, key = { it == initialRootKey }) },
                RevisionFailure.Sync(SyncFailure.Unreachable("HTTP 500")),
                Request.ListVersionsPage::class,
            ),
            Case(
                "404 のあと、古いバージョンの GET の結果が不明",
                {
                    store.onNextGet(GetFault.RunBefore {}, key = { it == initialRootKey })
                    store.onNextGet(GetFault.ServerError, key = { it == initialRootKey })
                },
                RevisionFailure.Sync(SyncFailure.Unreachable("HTTP 500")),
                Request.GetVersion::class,
            ),
        )

        cases.forEach { case ->
            val fixture = SyncFixture()
            val loaded = fixture.prepare {
                replaceRootAsOtherWriter()
                store.remove(initialRootKey)
            }
            fixture.apply(case.arrange)

            val result = fixture.sync.restoreRevision(loaded)

            assertEquals(RestoreResult.Failed(case.expected), result, case.name)
            assertEquals(emptyList(), fixture.store.puts { true }, case.name)
            assertEquals(case.lastRequest, fixture.requestsFor(fixture.initialRootKey).last()::class, case.name)
        }
    }

    // 置き直すフォルダが、同時に送る数の上限より多い：中身の GET も、手順 3 の項目ファイルの PUT も、同時に走るのは上限の数まで
    @Test
    fun boundsConcurrentRequestsByTheLimit() = runTest {
        val fixture = SyncFixture()
        val folderCount = MAX_CONCURRENT_REQUESTS + 4
        val folders = (0 until folderCount).map { n ->
            if (n == 0) {
                FolderEntry(ROOT_ID, null, "", fileId(100), 1)
            } else {
                FolderEntry(folderId(10 + n), ROOT_ID, "F$n", fileId(100 + n), 1)
            }
        }
        folders.forEachIndexed { n, entry ->
            fixture.store.overwrite(fixture.itemsFileKey(entry.id, assertNotNull(entry.items)), itemsFileBody(entry.id, listOf(item(n + 1))))
        }
        fixture.replaceManifest(Manifest(initialManifest().header, folders))
        val revision = fixture.currentManifestRevision()
        fixture.replaceManifest(Manifest(initialManifest().header.copy(commit = newId()), listOf(FolderEntry(ROOT_ID, null, "", null, 0))))
        val loaded = fixture.loadRevision(revision)
        val isItemsFile = { key: String -> key.startsWith("${fixture.store.prefix}/folders/") }
        val gets = ConcurrencyCounter()
        val puts = ConcurrencyCounter()
        repeat(folderCount) {
            fixture.store.onNextGet(GetFault.RunBefore { gets.hold() }, key = isItemsFile)
            fixture.store.onNextPut(PutFault.RunBefore { puts.hold() }, key = isItemsFile)
        }

        assertIs<RestoreResult.Success>(fixture.sync.restoreRevision(loaded))

        assertEquals(MAX_CONCURRENT_REQUESTS, gets.max)
        assertEquals(MAX_CONCURRENT_REQUESTS, puts.max)
        assertEquals(folderCount, fixture.store.puts(isItemsFile).size)
        val stored = fixture.store.storedManifest()
        assertEquals(folders.map { it.copy(items = null) }, stored.folders.map { it.copy(items = null) })
        assertEquals(folders.indices.map { listOf(item(it + 1)) }, stored.folders.map { fixture.store.storedItems(stored, it.id) })
    }

    // 置き直すフォルダが 2 つあり、どちらの中身も得られない：並べて得たうちフォルダの並びで先のもの（ルートフォルダ）の失敗を返し、
    // PUT を 1 つも送らない
    @Test
    fun returnsFailureOfFirstFolderInOrder() = runTest {
        val cases: List<Triple<String, SyncFixture.() -> Unit, RevisionFailure>> = listOf(
            Triple(
                "ルートフォルダが残っていない、A が 5xx",
                {
                    removeAllVersions(initialRootKey)
                    store.onNextGet(GetFault.ServerError, key = { it == initialAKey })
                },
                RevisionFailure.Incomplete,
            ),
            Triple(
                "ルートフォルダが 5xx、A が残っていない",
                {
                    store.onNextGet(GetFault.ServerError, key = { it == initialRootKey })
                    removeAllVersions(initialAKey)
                },
                RevisionFailure.Sync(SyncFailure.Unreachable("HTTP 500")),
            ),
        )

        cases.forEach { (case, arrange, expected) ->
            val fixture = SyncFixture()
            val loaded = fixture.prepare {
                replaceRootAsOtherWriter()
                store.replaceItemsAsOtherWriter(FOLDER_A, fileId(7), listOf(item(8)))
            }
            fixture.arrange()

            val result = fixture.sync.restoreRevision(loaded)

            assertEquals(RestoreResult.Failed(expected), result, case)
            assertEquals(emptyList(), fixture.store.puts { true }, case)
            assertEquals(1, fixture.store.gets { it == fixture.initialAKey }.size, case)
        }
    }

    // マニフェストの PUT の前にほかの書き手が A の項目を替えた（412）：手順 1 から読み直したいまのマニフェストと比べ直し、
    // 1 回目にそのままにした A も、戻す時点のファイルの中身で置き直す。結果は戻す時点の状態。
    // 1 回目に得たルートフォルダの中身は覚えておき、GET し直さない
    @Test
    fun reappliesAgainstRereadManifestWithoutRefetching() = runTest {
        val fixture = SyncFixture()
        val loaded = fixture.prepare()
        fixture.store.onNextPut(
            PutFault.RunBefore { fixture.store.replaceItemsAsOtherWriter(FOLDER_A, fileId(7), listOf(item(8))) },
            key = fixture.isManifest,
        )

        assertIs<RestoreResult.Success>(fixture.sync.restoreRevision(loaded))

        assertEquals(INITIAL_CONTENTS, fixture.store.storedContents())
        assertEquals(1, fixture.store.gets { it == fixture.initialRootKey }.size)
        assertEquals(1, fixture.store.gets { it == fixture.initialAKey }.size)
        assertEquals(2, fixture.store.puts(fixture.isItemsFileOf(ROOT_ID)).size)
        assertEquals(1, fixture.store.puts(fixture.isItemsFileOf(FOLDER_A)).size)
        assertEquals(2, fixture.store.puts(fixture.isManifest).size)
        assertEquals(listOf(retryDelay(1)), fixture.waits)
    }

    // マニフェストの PUT が書き込まれたのに応答が切れた：読み直したマニフェストの commit が送ったものなので成功とし、PUT し直さない
    @Test
    fun lostResponseAfterManifestWriteIsJudgedSuccessful() = runTest {
        val fixture = SyncFixture()
        val loaded = fixture.prepare()
        fixture.store.onNextPut(PutFault.WriteThenUnknown(), key = fixture.isManifest)

        val success = assertIs<RestoreResult.Success>(fixture.sync.restoreRevision(loaded))

        assertEquals(fixture.store.storedManifest(), success.tree.manifest)
        assertEquals(fixture.store.storedManifestETag(), success.tree.eTag)
        assertEquals(1, fixture.store.puts(fixture.isManifest).size)
        assertEquals(INITIAL_CONTENTS, fixture.store.storedContents())
    }

    // マニフェストの PUT が書き込まれたのに、応答が切れた、または 412 が返り、読み直しの GET も再送の上限まで失敗した：
    // 戻せたかどうかを確かめられなかったとして返す
    @Test
    fun unconfirmedWhenRereadsFail() = runTest {
        val cases: List<Pair<String, SyncFixture.() -> Unit>> = listOf(
            "応答が切れた" to {
                store.onNextPut(PutFault.WriteThenUnknown { failManifestRereads() }, key = isManifest)
            },
            "412" to {
                // 手順 1 のマニフェストの GET のあと、項目ファイルの PUT の時点で、以降のマニフェストの GET の失敗を差し込む
                store.onNextPut(PutFault.RunBefore { failManifestRereads() }, key = isItemsFileOf(ROOT_ID))
                store.onNextPut(PutFault.WriteThenPreconditionFailed, key = isManifest)
            },
        )

        cases.forEach { (case, arrange) ->
            val fixture = SyncFixture()
            val loaded = fixture.prepare()
            fixture.arrange()

            val result = fixture.sync.restoreRevision(loaded)

            assertIs<SyncFailure.Unreachable>(assertIs<RestoreResult.Unconfirmed>(result, case).failure, case)
            assertEquals(1, fixture.store.puts(fixture.isManifest).size, case)
        }
    }

    // いまのマニフェストが読むだけのもの・無い・壊れている：項目ファイルも読まず、PUT を 1 つも送らずに、S3 への読み書きの失敗として返す
    @Test
    fun doesNotWriteUnlessCurrentManifestIsWritable() = runTest {
        val cases = listOf<Pair<SyncFixture.() -> Unit, (SyncFailure) -> Unit>>(
            Pair(
                { replaceManifest(initialManifest(version = 2, readableBy = 1)) },
                { failure -> assertEquals(SyncFailure.ReadOnly(version = 2, readableBy = 1), failure) },
            ),
            Pair(
                { store.remove(manifestKey) },
                { failure -> assertEquals(SyncFailure.ManifestNotFound, failure) },
            ),
            Pair(
                { store.overwrite(manifestKey, "not gzip".toByteArray()) },
                { failure -> assertIs<SyncFailure.ManifestCorrupt>(failure) },
            ),
        )

        cases.forEach { (breakManifest, check) ->
            val fixture = SyncFixture()
            val loaded = fixture.prepare {
                replaceRootAsOtherWriter()
                breakManifest()
            }
            val requestsBefore = fixture.store.requests.size

            val result = fixture.sync.restoreRevision(loaded)

            check(assertIs<RevisionFailure.Sync>(assertIs<RestoreResult.Failed>(result).failure).failure)
            assertEquals(listOf(fixture.manifestKey), fixture.store.requests.drop(requestsBefore).map { it.key })
        }
    }

    // いまの状態が戻す時点と同じ（ほかの書き手が同じ中身のマニフェストを置き直した）：項目ファイルを読まず、
    // PUT を 1 つも送らずに、いまのマニフェストで成功とする
    @Test
    fun sameStateSucceedsWithoutPuts() = runTest {
        val fixture = SyncFixture()
        val loaded = fixture.prepare { replaceManifest(initialManifest()) }

        val success = assertIs<RestoreResult.Success>(fixture.sync.restoreRevision(loaded))

        assertEquals(initialManifest(), success.tree.manifest)
        assertEquals(fixture.store.storedManifestETag(), success.tree.eTag)
        assertEquals(emptyList(), fixture.store.puts { true })
        assertTrue(fixture.store.requests.none { it.key.startsWith("${fixture.store.prefix}/folders/") })
    }

    // 戻したあと、猶予を過ぎてから古いファイルの削除をしても、戻したマニフェストのどのフォルダの項目ファイルも消えない。
    // 消えるのは、戻す前のマニフェストだけが指していたファイル
    @Test
    fun restoredTreeSurvivesStaleFileDeletion() = runTest {
        val fixture = SyncFixture()
        val loaded = fixture.prepare {
            replaceRootAsOtherWriter()
            store.remove(initialRootKey)
        }
        assertIs<RestoreResult.Success>(fixture.sync.restoreRevision(loaded))
        fixture.store.serverTime += 25.hours

        val result = fixture.sync.deleteStaleFiles()

        assertEquals(1, assertIs<DeleteStaleFilesResult.Success>(result).deleted)
        assertNull(fixture.store.objectAt(fixture.itemsFileKey(ROOT_ID, LATER_ROOT_FILE_ID)))
        assertEquals(INITIAL_CONTENTS, fixture.store.storedContents())
    }

    // 戻したのを取り消す：初めの時点へ戻したあと、戻す前の時点へもう一度戻すと、戻す前の状態と一致する。
    // あいだの古いファイルの削除で消えた、戻す前の時点のファイルの中身は、古いバージョンから得る
    @Test
    fun restoringTheStateBeforeUndoesTheRestore() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        val initial = fixture.currentManifestRevision()
        val added = AddBookmarks(listOf(NewItem(itemId(100), "https://example.com/new", "new")), ADDED)
        assertIs<WriteResult.Success>(fixture.sync.write(listOf(added)))
        val beforeRestore = fixture.currentManifestRevision()
        val contentsBeforeRestore = fixture.store.storedContents()
        val rootKeyBeforeRestore = fixture.itemsFileKey(ROOT_ID, assertNotNull(fixture.store.storedManifest().folders.first().items))

        assertIs<RestoreResult.Success>(fixture.sync.restoreRevision(fixture.loadRevision(initial)))
        assertEquals(INITIAL_CONTENTS, fixture.store.storedContents())
        fixture.store.serverTime += 25.hours
        assertIs<DeleteStaleFilesResult.Success>(fixture.sync.deleteStaleFiles())
        assertNull(fixture.store.objectAt(rootKeyBeforeRestore))

        val result = fixture.sync.restoreRevision(fixture.loadRevision(beforeRestore))

        assertIs<RestoreResult.Success>(result)
        assertEquals(contentsBeforeRestore, fixture.store.storedContents())
        assertEquals(1, fixture.store.requests.count { it is Request.GetVersion && it.key == rootKeyBeforeRestore })
    }

    // 手順 3 の最初の PUT から 1 時間を測る起点は、中身を得たあと。中身の GET のあいだに 61 分たっても、手順 1 へ戻らずにマニフェストを送る
    @Test
    fun hourLimitStartsAfterFetchingContents() = runTest {
        val fixture = SyncFixture()
        val loaded = fixture.prepare()
        fixture.store.onNextGet(GetFault.RunBefore { fixture.timeSource += 61.minutes }, key = { it == fixture.initialRootKey })

        assertIs<RestoreResult.Success>(fixture.sync.restoreRevision(loaded))

        assertEquals(1, fixture.store.gets(fixture.isManifest).size)
        assertEquals(1, fixture.store.puts(fixture.isItemsFileOf(ROOT_ID)).size)
        assertEquals(1, fixture.store.puts(fixture.isManifest).size)
    }

    // 置き直す項目ファイルの最初の PUT が 412：ファイルの ID を振り直して同じ中身を置き直し、新しいファイルをマニフェストから指す
    @Test
    fun itemsFilePreconditionFailedGetsNewFileId() = runTest {
        val fixture = SyncFixture()
        val loaded = fixture.prepare()
        fixture.store.onNextPut(PutFault.PreconditionFailed, key = fixture.isItemsFileOf(ROOT_ID))

        assertIs<RestoreResult.Success>(fixture.sync.restoreRevision(loaded))

        val filePuts = fixture.store.puts(fixture.isItemsFileOf(ROOT_ID))
        assertEquals(2, filePuts.size)
        assertNotEquals(filePuts[0].key, filePuts[1].key)
        assertContentEquals(filePuts[0].body, filePuts[1].body)
        assertEquals(fileIdOfKey(filePuts[1].key), fixture.store.storedManifest().folders.first().items)
        assertEquals(INITIAL_CONTENTS, fixture.store.storedContents())
    }
}
