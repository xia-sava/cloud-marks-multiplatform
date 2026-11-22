package to.sava.cloudmarksmultiplatform.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.browse.BrowseState
import to.sava.cloudmarksmultiplatform.browse.searchItems
import to.sava.cloudmarksmultiplatform.browser.BrowserTab
import to.sava.cloudmarksmultiplatform.browser.CHROME
import to.sava.cloudmarksmultiplatform.browser.FIREFOX
import to.sava.cloudmarksmultiplatform.browser.FakeBrowserOpener
import to.sava.cloudmarksmultiplatform.browser.FakeItemSharer
import to.sava.cloudmarksmultiplatform.operations.ItemRef
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.GetFault
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.Request
import to.sava.cloudmarksmultiplatform.settings.SETTINGS
import to.sava.cloudmarksmultiplatform.settings.SettingsForm
import to.sava.cloudmarksmultiplatform.settings.TabLoading
import to.sava.cloudmarksmultiplatform.storage.FolderEntry
import to.sava.cloudmarksmultiplatform.storage.Item
import to.sava.cloudmarksmultiplatform.storage.Manifest
import to.sava.cloudmarksmultiplatform.storage.itemsFileKey
import to.sava.cloudmarksmultiplatform.sync.A_FILE_ID
import to.sava.cloudmarksmultiplatform.sync.A_ITEMS
import to.sava.cloudmarksmultiplatform.sync.FOLDER_A
import to.sava.cloudmarksmultiplatform.sync.FolderReadResult
import to.sava.cloudmarksmultiplatform.sync.ROOT_ID
import to.sava.cloudmarksmultiplatform.sync.ROOT_ITEMS
import to.sava.cloudmarksmultiplatform.sync.fileId
import to.sava.cloudmarksmultiplatform.sync.folderId
import to.sava.cloudmarksmultiplatform.sync.gets
import to.sava.cloudmarksmultiplatform.sync.initialManifest
import to.sava.cloudmarksmultiplatform.sync.item
import to.sava.cloudmarksmultiplatform.sync.itemsFileBody
import to.sava.cloudmarksmultiplatform.sync.puts
import to.sava.cloudmarksmultiplatform.sync.replaceItemsAsOtherWriter
import to.sava.cloudmarksmultiplatform.sync.seed
import to.sava.cloudmarksmultiplatform.sync.storedItems
import to.sava.cloudmarksmultiplatform.sync.storedManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 検索のために読むフォルダ。ルートフォルダの子に、B のあとに並べる。それぞれ項目ファイルを 1 つ持ち、項目を 1 つ持つ。
 *
 * @property itemsFileId 項目ファイルの ID
 */
private data class ExtraFolder(val id: String, val itemsFileId: String, val items: List<Item>)

/** 同時に読む数の上限（4）より多くの、検索のために読むフォルダ。 */
private val EXTRA_FOLDERS: List<ExtraFolder> = (1..6).map { n -> ExtraFolder(folderId(10 + n), fileId(10 + n), listOf(item(10 + n))) }

/** 同時に読むフォルダの数の上限。 */
private const val CONCURRENT_READS = 4

/** フォルダ A の 3 つの項目。開いているフォルダでないフォルダの項目への操作を確かめる。 */
private val THREE_A_ITEMS = listOf(item(3), item(4), item(5))

/** 開いているフォルダでないフォルダ A の、真ん中の項目。 */
private val A_MIDDLE = ItemRef(FOLDER_A, item(4).id)

/** [initialManifest] のルートフォルダの子に、[EXTRA_FOLDERS] を足したもの。ルートフォルダ → A、B、C1〜C6。 */
private fun manifestWithExtraFolders(): Manifest =
    initialManifest().let { manifest ->
        manifest.copy(
            folders = manifest.folders + EXTRA_FOLDERS.mapIndexed { index, extra ->
                FolderEntry(extra.id, ROOT_ID, "C${index + 1}", extra.itemsFileId, extra.items.size)
            },
        )
    }

/** [manifestWithExtraFolders] を、ルートフォルダと A と [EXTRA_FOLDERS] の項目ファイルと一緒に置く。 */
private fun FakeObjectStore.seedWithExtraFolders() {
    EXTRA_FOLDERS.forEach { overwrite(itemsFileKey(prefix, it.id, it.itemsFileId), itemsFileBody(it.id, it.items)) }
    seed(manifestWithExtraFolders())
}

/** [store] の、フォルダ [extra] の項目ファイルのキー。 */
private fun ControllerFixture.itemsKey(extra: ExtraFolder): String = itemsKey(extra.id, extra.itemsFileId)

/** [store] の、項目ファイルの GET（マニフェストでない GET）。 */
private fun ControllerFixture.itemsFileGets(): List<Request.Get> = store.gets { !isManifest(it) }

/** S3 に置いてあるマニフェストが指す、フォルダ [folderId] の項目。 */
private fun ControllerFixture.storedItems(folderId: String): List<Item> = store.storedItems(store.storedManifest(), folderId)

/** [items] を開くときのタブ。 */
private fun tabsOf(items: List<Item>): List<BrowserTab> = items.map { BrowserTab(it.url, it.title) }

// runCurrent が ExperimentalCoroutinesApi であるため。
@OptIn(ExperimentalCoroutinesApi::class)
class CloudMarksControllerSearchTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /**
     * [manifestWithExtraFolders] を置いて保存済みの設定で起動し、ルートフォルダを開いたフィクスチャ。ルートフォルダの項目だけを読んである。
     * [arrange] は起動の前に走らせる。
     */
    private fun TestScope.started(arrange: (ControllerFixture) -> Unit = {}): ControllerFixture {
        val fixture = ControllerFixture(this, temporaryFolder, itemSharer = FakeItemSharer())
        fixture.store.seedWithExtraFolders()
        fixture.storeSettings()
        arrange(fixture)
        fixture.controller.start()
        runCurrent()
        return fixture
    }

    /** [started] のあと、検索を開いて、すべてのフォルダの項目を読んだフィクスチャ。 */
    private fun TestScope.searching(arrange: (ControllerFixture) -> Unit = {}): ControllerFixture =
        started(arrange).apply {
            controller.openSearch()
            runCurrent()
        }

    // 起動しただけでは、開いているフォルダ（ルートフォルダ）の項目だけを読む。検索を開くと、項目を読んでいないフォルダをすべて、
    // マニフェストを GET せずに 1 回ずつ読み、結果に入る
    @Test
    fun openSearchReadsAllUnreadFolders() = runTest {
        val fixture = started()
        assertEquals(listOf(FOLDER_A) + EXTRA_FOLDERS.map { it.id }, fixture.state.browse.foldersToLoadForSearch())
        val manifestGetsBefore = fixture.store.gets(fixture.isManifest).size
        val itemsGetsBefore = fixture.itemsFileGets().size

        fixture.controller.openSearch()
        runCurrent()

        val browse = fixture.state.browse
        assertEquals(FolderReadResult.Loaded(A_ITEMS), browse.folderReads[FOLDER_A])
        EXTRA_FOLDERS.forEach { assertEquals(FolderReadResult.Loaded(it.items), browse.folderReads[it.id], it.id) }
        assertEquals(emptyList(), browse.foldersToLoadForSearch())
        assertFalse(browse.searchLoading)
        assertEquals(manifestGetsBefore, fixture.store.gets(fixture.isManifest).size)
        assertEquals(1 + EXTRA_FOLDERS.size, fixture.itemsFileGets().size - itemsGetsBefore)

        fixture.controller.setSearchQuery("title-13")

        val results = fixture.state.browse.let { it.searchItems(assertNotNull(it.search).query) }
        assertEquals(listOf(ItemRef(EXTRA_FOLDERS[2].id, item(13).id)), results.map { it.ref })
    }

    // 検索のために読むフォルダは、すぐに読んでいる最中にし、同時に読むのは 4 本まで。1 本読み終えるたびに、次の 1 本を読み始める
    @Test
    fun searchReadsAtMostFourFoldersAtOnce() = runTest {
        val fixture = started()
        val keys = listOf(fixture.itemsKey(FOLDER_A, A_FILE_ID)) + EXTRA_FOLDERS.map { fixture.itemsKey(it) }
        val gates = keys.map { key -> fixture.stopNextGet { it == key } }
        val itemsGetsBefore = fixture.itemsFileGets().size

        fixture.controller.openSearch()

        assertEquals((listOf(FOLDER_A) + EXTRA_FOLDERS.map { it.id }).toSet(), fixture.state.browse.loadingFolders)
        runCurrent()
        assertEquals(keys.take(CONCURRENT_READS), fixture.itemsFileGets().drop(itemsGetsBefore).map { it.key })
        assertTrue(fixture.state.browse.searchLoading)

        gates.forEachIndexed { index, gate ->
            gate.complete(Unit)
            runCurrent()

            val started = fixture.itemsFileGets().size - itemsGetsBefore
            assertEquals(minOf(CONCURRENT_READS + index + 1, keys.size), started, "$index 本を読み終えたあと")
        }
        assertEquals(keys, fixture.itemsFileGets().drop(itemsGetsBefore).map { it.key })
        assertFalse(fixture.state.browse.searchLoading)
        EXTRA_FOLDERS.forEach { assertEquals(FolderReadResult.Loaded(it.items), fixture.state.browse.folderReads[it.id], it.id) }
    }

    // 検索を開いているあいだにマニフェストを読み直すと（一緒に読むのは開いているフォルダだけ）、読んだ結果は、項目ファイルの ID が同じフォルダのものを
    // 引き継ぎ、ほかの書き手が替えたフォルダだけを、そのあとに読み直して新しい項目にする
    @Test
    fun rereadWhileSearchingReadsOnlyReplacedFoldersAgain() = runTest {
        val fixture = searching()
        val replaced = EXTRA_FOLDERS[2]
        val newItems = listOf(item(40))
        fixture.store.replaceItemsAsOtherWriter(replaced.id, fileId(40), newItems)

        fixture.controller.reload()
        runCurrent()

        val browse = fixture.state.browse
        assertEquals(fixture.store.storedManifest(), browse.tree?.manifest)
        assertEquals(FolderReadResult.Loaded(newItems), browse.folderReads[replaced.id])
        assertEquals(FolderReadResult.Loaded(A_ITEMS), browse.folderReads[FOLDER_A])
        (EXTRA_FOLDERS - replaced).forEach { assertEquals(FolderReadResult.Loaded(it.items), browse.folderReads[it.id], it.id) }
        assertFalse(browse.searchLoading)
    }

    // 検索を開いて全フォルダを読み終えたあと、マニフェストが変わらない読み直しをしても、項目ファイルを GET し直さず、
    // 検索の結果は読み直しのあいだも減らない
    @Test
    fun rereadWithSameManifestWhileSearchingKeepsResults() = runTest {
        val fixture = searching()
        fixture.controller.setSearchQuery("title")
        val resultsOf = { browse: BrowseState -> browse.searchItems(assertNotNull(browse.search).query).map { it.ref } }
        val expected = resultsOf(fixture.state.browse)
        assertEquals(ROOT_ITEMS.size + A_ITEMS.size + EXTRA_FOLDERS.size, expected.size)
        val treeBefore = assertNotNull(fixture.state.browse.tree)
        val itemsGetsBefore = fixture.itemsFileGets().size
        val observed = mutableListOf<List<ItemRef>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.controller.state.collect { observed += resultsOf(it.browse) }
        }

        fixture.controller.reload()
        runCurrent()

        val browse = fixture.state.browse
        assertTrue(treeBefore !== assertNotNull(browse.tree))
        assertEquals(treeBefore.eTag, browse.tree?.eTag)
        assertEquals(itemsGetsBefore, fixture.itemsFileGets().size)
        assertTrue(observed.size > 1)
        observed.forEach { assertEquals(expected, it) }
        assertFalse(browse.searchLoading)
    }

    // 検索を開いているあいだに操作を書くと、書いたあとのツリーで項目ファイルが替わったフォルダ（ほかの書き手が替えた）と、
    // 読めなかったフォルダを読み直す
    @Test
    fun writeWhileSearchingReadsSearchFoldersAgain() = runTest {
        val failed = EXTRA_FOLDERS[1]
        val fixture = started()
        fixture.store.onNextGet(GetFault.ServerError) { it == fixture.itemsKey(failed) }
        fixture.controller.openSearch()
        runCurrent()
        assertIs<FolderReadResult.Failed>(fixture.state.browse.folderReads[failed.id])
        val replaced = EXTRA_FOLDERS[2]
        val newItems = listOf(item(40))
        fixture.store.replaceItemsAsOtherWriter(replaced.id, fileId(40), newItems)

        fixture.controller.createFolder(ROOT_ID, "D")
        runCurrent()

        val browse = fixture.state.browse
        assertEquals(WriteOutcome.Written, fixture.state.write.outcome)
        assertEquals(FolderReadResult.Loaded(newItems), browse.folderReads[replaced.id])
        assertEquals(FolderReadResult.Loaded(failed.items), browse.folderReads[failed.id])
        assertFalse(browse.searchLoading)
    }

    // 検索のために読んだ 2 つのフォルダの項目ファイルが 404 なら（ほかの書き手が替え、古いファイルは消えた）、マニフェストを読み直す。
    // 読み直しているあいだに 404 を返したフォルダでは、もう 1 本読み直しを始めない。読み直したツリーで、どちらのフォルダも新しい項目になる
    @Test
    fun missingSearchFoldersRereadManifestOnce() = runTest {
        val missing = EXTRA_FOLDERS.take(2)
        val fixture = started()
        missing.forEachIndexed { index, extra ->
            fixture.store.replaceItemsAsOtherWriter(extra.id, fileId(40 + index), listOf(item(40 + index)))
            fixture.store.remove(fixture.itemsKey(extra))
        }
        val manifestGetsBefore = fixture.store.gets(fixture.isManifest).size
        val gate = fixture.stopNextGet(fixture.isManifest)

        fixture.controller.openSearch()
        runCurrent()

        val reading = fixture.state.browse
        assertTrue(reading.loading)
        assertTrue(reading.searchLoading)
        assertEquals(FolderReadResult.Missing, reading.folderReads[missing[1].id])
        assertEquals(manifestGetsBefore + 1, fixture.store.gets(fixture.isManifest).size)

        gate.complete(Unit)
        runCurrent()

        val browse = fixture.state.browse
        assertEquals(fixture.store.storedManifest(), browse.tree?.manifest)
        missing.forEachIndexed { index, extra ->
            assertEquals(FolderReadResult.Loaded(listOf(item(40 + index))), browse.folderReads[extra.id], extra.id)
        }
        assertFalse(browse.loading)
        assertFalse(browse.searchLoading)
        assertEquals(manifestGetsBefore + 1, fixture.store.gets(fixture.isManifest).size)
    }

    // 読み直したマニフェストがまだ同じファイルを指していれば、そのフォルダは壊れているとし、マニフェストを 1 回読み直すだけで終える
    @Test
    fun missingSearchFolderStillReferencedIsBroken() = runTest {
        val missing = EXTRA_FOLDERS[0]
        val fixture = started { it.store.remove(it.itemsKey(missing)) }
        val manifestGetsBefore = fixture.store.gets(fixture.isManifest).size

        fixture.controller.openSearch()
        runCurrent()

        val browse = fixture.state.browse
        assertIs<FolderReadResult.Broken>(browse.folderReads[missing.id])
        assertEquals(listOf(missing.id), browse.unreadableFoldersForSearch())
        assertFalse(browse.searchLoading)
        assertEquals(manifestGetsBefore + 1, fixture.store.gets(fixture.isManifest).size)
        assertEquals(1, fixture.store.gets { it == fixture.itemsKey(missing) }.size)
    }

    // 検索のために読む 2 つのフォルダの項目ファイルが消えたまま、マニフェストがそれを指していれば（マニフェストは替わらない）、
    // 両方とも壊れているとし、マニフェストを 1 回読み直すだけで終える
    @Test
    fun twoMissingSearchFoldersStillReferencedAreBroken() = runTest {
        val missing = EXTRA_FOLDERS.take(2)
        val fixture = started { fixture -> missing.forEach { fixture.store.remove(fixture.itemsKey(it)) } }
        val manifestGetsBefore = fixture.store.gets(fixture.isManifest).size

        fixture.controller.openSearch()
        runCurrent()

        val browse = fixture.state.browse
        missing.forEach { assertIs<FolderReadResult.Broken>(browse.folderReads[it.id], it.id) }
        assertEquals(missing.map { it.id }, browse.unreadableFoldersForSearch())
        assertFalse(browse.searchLoading)
        assertNull(browse.failure)
        assertEquals(manifestGetsBefore + 1, fixture.store.gets(fixture.isManifest).size)
    }

    // 古いツリーで読んだフォルダの項目ファイルが 404 を返しても（止めているあいだにマニフェストを読み直し、ほかの書き手が替えた新しい
    // ファイルを読めた）、マニフェストを読み直さない
    @Test
    fun missingFromOldTreeDoesNotRereadManifest() = runTest {
        val replaced = EXTRA_FOLDERS[0]
        val newItems = listOf(item(40))
        val fixture = started()
        val oldKey = fixture.itemsKey(replaced)
        val gate = fixture.stopNextGet { it == oldKey }
        fixture.controller.openSearch()
        runCurrent()
        fixture.store.replaceItemsAsOtherWriter(replaced.id, fileId(40), newItems)
        fixture.store.remove(oldKey)
        fixture.controller.reload()
        runCurrent()
        assertEquals(FolderReadResult.Loaded(newItems), fixture.state.browse.folderReads[replaced.id])
        val manifestGetsBefore = fixture.store.gets(fixture.isManifest).size

        gate.complete(Unit)
        runCurrent()

        val browse = fixture.state.browse
        assertEquals(manifestGetsBefore, fixture.store.gets(fixture.isManifest).size)
        assertFalse(browse.loading)
        assertFalse(browse.searchLoading)
        assertEquals(FolderReadResult.Loaded(newItems), browse.folderReads[replaced.id])
    }

    // 検索のための読み込みを止めて順番待ちがあるあいだに操作を書いても、止めを外したあとは、読んでいる最中のフォルダが残らず、
    // すべてのフォルダを読めている（古いツリーの順番待ちは読まず、書いたあとのツリーで読む）
    @Test
    fun writeWhileSearchReadsAreQueuedEndsWithAllLoaded() = runTest {
        val fixture = started()
        val keys = listOf(fixture.itemsKey(FOLDER_A, A_FILE_ID)) + EXTRA_FOLDERS.take(CONCURRENT_READS - 1).map { fixture.itemsKey(it) }
        val gates = keys.map { key -> fixture.stopNextGet { it == key } }
        val itemsGetsBefore = fixture.itemsFileGets().size
        fixture.controller.openSearch()
        runCurrent()
        assertEquals(keys, fixture.itemsFileGets().drop(itemsGetsBefore).map { it.key })

        fixture.controller.createFolder(ROOT_ID, "D")
        runCurrent()
        assertEquals(WriteOutcome.Written, fixture.state.write.outcome)
        gates.forEach { it.complete(Unit) }
        runCurrent()

        val browse = fixture.state.browse
        assertEquals(emptySet(), browse.loadingFolders)
        assertFalse(browse.searchLoading)
        assertEquals(FolderReadResult.Loaded(A_ITEMS), browse.folderReads[FOLDER_A])
        EXTRA_FOLDERS.forEach { assertEquals(FolderReadResult.Loaded(it.items), browse.folderReads[it.id], it.id) }
    }

    // 検索のための読み込みを止めたまま設定を保存して接続を作り直すと、止めた読み込みを取り消してから前の接続を閉じる。
    // 同時に読む数の上限は使われたまま残らず、新しい接続で検索を開くと、すべてのフォルダを読める
    @Test
    fun reconnectingReleasesFolderReadLimit() = runTest {
        val fixture = started()
        val keys = listOf(fixture.itemsKey(FOLDER_A, A_FILE_ID)) + EXTRA_FOLDERS.take(CONCURRENT_READS - 1).map { fixture.itemsKey(it) }
        keys.forEach { key -> fixture.stopNextGet { it == key } }
        fixture.controller.openSearch()
        runCurrent()

        fixture.controller.openSettings()
        fixture.controller.editSettings(SettingsForm.of(SETTINGS))
        fixture.controller.saveSettings()
        runCurrent()
        assertEquals(List(CONCURRENT_READS) { CANCELLED } + CLOSED, fixture.events)
        assertEquals(Screen.MAIN, fixture.state.screen)
        assertNull(fixture.state.browse.search)

        fixture.controller.openSearch()
        runCurrent()

        val browse = fixture.state.browse
        assertEquals(FolderReadResult.Loaded(A_ITEMS), browse.folderReads[FOLDER_A])
        EXTRA_FOLDERS.forEach { assertEquals(FolderReadResult.Loaded(it.items), browse.folderReads[it.id], it.id) }
        assertFalse(browse.searchLoading)
    }

    // 検索を閉じても、読み始めていた（GET を送った）フォルダの読み込みは取り消さず、読み終えて結果に入る
    @Test
    fun closingSearchKeepsRunningReads() = runTest {
        val fixture = started()
        val extra = EXTRA_FOLDERS[0]
        val gate = fixture.stopNextGet { it == fixture.itemsKey(extra) }
        fixture.controller.openSearch()
        runCurrent()

        fixture.controller.closeSearch()
        runCurrent()
        assertNull(fixture.state.browse.search)
        gate.complete(Unit)
        runCurrent()

        assertEquals(FolderReadResult.Loaded(extra.items), fixture.state.browse.folderReads[extra.id])
        assertEquals(emptySet(), fixture.state.browse.loadingFolders)
        assertFalse(CANCELLED in fixture.events)
    }

    // GET を止めて順番待ちがあるあいだに検索を閉じ、順番待ちのフォルダの 1 つを開くと、走っている 1 本が終わったあとに、開いたフォルダを読む。
    // 順番待ちだったほかの検索のためのフォルダは GET せず、読んでいる最中から外す。走っていた読み込みは、読み終えて結果に入る
    @Test
    fun closingSearchSkipsQueuedSearchReads() = runTest {
        val fixture = started()
        val runningExtras = EXTRA_FOLDERS.take(CONCURRENT_READS - 1)
        val keys = listOf(fixture.itemsKey(FOLDER_A, A_FILE_ID)) + runningExtras.map { fixture.itemsKey(it) }
        val gates = keys.map { key -> fixture.stopNextGet { it == key } }
        val opened = EXTRA_FOLDERS[4]
        val skipped = listOf(EXTRA_FOLDERS[3], EXTRA_FOLDERS[5])
        val getsOf: (ExtraFolder) -> Int = { extra -> fixture.store.gets { it == fixture.itemsKey(extra) }.size }
        fixture.controller.openSearch()
        runCurrent()

        fixture.controller.closeSearch()
        fixture.controller.openFolder(opened.id)
        runCurrent()
        assertEquals(0, getsOf(opened))
        assertTrue(opened.id in fixture.state.browse.loadingFolders)

        gates[0].complete(Unit)
        runCurrent()

        val browse = fixture.state.browse
        assertEquals(FolderReadResult.Loaded(opened.items), browse.currentItems)
        assertEquals(1, getsOf(opened))
        skipped.forEach { extra ->
            assertEquals(0, getsOf(extra), extra.id)
            assertFalse(extra.id in browse.folderReads, extra.id)
        }
        assertEquals(runningExtras.map { it.id }.toSet(), browse.loadingFolders)

        gates.drop(1).forEach { it.complete(Unit) }
        runCurrent()

        val after = fixture.state.browse
        assertEquals(emptySet(), after.loadingFolders)
        assertEquals(FolderReadResult.Loaded(A_ITEMS), after.folderReads[FOLDER_A])
        runningExtras.forEach { assertEquals(FolderReadResult.Loaded(it.items), after.folderReads[it.id], it.id) }
        skipped.forEach { assertEquals(0, getsOf(it), it.id) }
    }

    // マニフェストが変わらない読み直しでツリーが替わると、前のツリーで順番を待っていた読み込みは読まず、読み直したツリーの読み込みだけを読む。
    // 順番を待っていたフォルダの項目ファイルは壊れていて、ローカルキャッシュに持たないので、読むたびに GET する。GET はフォルダごとに 1 回
    @Test
    fun rereadWithSameManifestSkipsQueuedReadsOfOldTree() = runTest {
        val queued = EXTRA_FOLDERS.drop(CONCURRENT_READS - 1)
        val fixture = started { fixture -> queued.forEach { fixture.store.overwrite(fixture.itemsKey(it), "not gzip".toByteArray()) } }
        val keys = listOf(fixture.itemsKey(FOLDER_A, A_FILE_ID)) + EXTRA_FOLDERS.take(CONCURRENT_READS - 1).map { fixture.itemsKey(it) }
        val gates = keys.map { key -> fixture.stopNextGet { it == key } }
        fixture.controller.openSearch()
        runCurrent()
        val treeBefore = assertNotNull(fixture.state.browse.tree)

        fixture.controller.reload()
        runCurrent()
        val treeAfter = assertNotNull(fixture.state.browse.tree)
        assertEquals(treeBefore.eTag, treeAfter.eTag)
        assertTrue(treeBefore !== treeAfter)
        gates.forEach { it.complete(Unit) }
        runCurrent()

        val browse = fixture.state.browse
        queued.forEach { extra ->
            assertEquals(1, fixture.store.gets { it == fixture.itemsKey(extra) }.size, extra.id)
            assertIs<FolderReadResult.Broken>(browse.folderReads[extra.id], extra.id)
        }
        assertEquals(emptySet(), browse.loadingFolders)
        assertFalse(browse.searchLoading)
    }

    // 検索を開いて前に読めなかった（失敗した）フォルダは、検索を開き直すと読み直す
    @Test
    fun reopeningSearchRetriesFailedFolders() = runTest {
        val failed = EXTRA_FOLDERS[3]
        val fixture = started()
        fixture.store.onNextGet(GetFault.Forbidden) { it == fixture.itemsKey(failed) }
        fixture.controller.openSearch()
        runCurrent()
        assertEquals(listOf(failed.id), fixture.state.browse.unreadableFoldersForSearch())

        fixture.controller.closeSearch()
        fixture.controller.openSearch()
        runCurrent()

        assertEquals(FolderReadResult.Loaded(failed.items), fixture.state.browse.folderReads[failed.id])
        assertEquals(emptyList(), fixture.state.browse.unreadableFoldersForSearch())
    }

    // 項目が入っているフォルダを開くと、検索を閉じて、そのフォルダを開き（履歴に積む）、その項目だけを選ぶ
    @Test
    fun openFolderOfOpensFolderAndSelectsItem() = runTest {
        val fixture = searching()
        val extra = EXTRA_FOLDERS[4]

        fixture.controller.openFolderOf(ItemRef(extra.id, extra.items[0].id))

        val browse = fixture.state.browse
        assertNull(browse.search)
        assertEquals(extra.id, browse.currentFolderId)
        assertEquals(listOf(ROOT_ID), browse.history)
        assertEquals(setOf(extra.items[0].id), browse.selectedItemIds)
    }

    // 項目を読んでいないフォルダの項目なら、そのフォルダを開いてから項目を読む（項目は選ばない）
    @Test
    fun openFolderOfUnreadFolderLoadsIt() = runTest {
        val fixture = started()

        fixture.controller.openFolderOf(ItemRef(FOLDER_A, A_ITEMS[0].id))
        runCurrent()

        val browse = fixture.state.browse
        assertEquals(FOLDER_A, browse.currentFolderId)
        assertEquals(FolderReadResult.Loaded(A_ITEMS), browse.currentItems)
        assertEquals(emptySet(), browse.selectedItemIds)
    }

    /** 場合の名前、コントローラへの操作、そのあとの確かめ方（フィクスチャと場合の名前を受け取る）。 */
    private data class RefCase(
        val name: String,
        val act: (CloudMarksController) -> Unit,
        val check: (ControllerFixture, String) -> Unit,
    )

    // 組で受け取る操作は、開いているフォルダでないフォルダの項目に効く。開いているフォルダと選んでいる項目と検索は変えない
    @Test
    fun refOperationsActOnItemsOfOtherFolders() = runTest {
        val middle = THREE_A_ITEMS[1]
        val cases = listOf(
            RefCase("既定のブラウザで開く", act = { it.openItem(A_MIDDLE) }) { fixture, name ->
                assertEquals(
                    listOf(FakeBrowserOpener.OpenRequest(CHROME, tabsOf(listOf(middle)), TabLoading())),
                    fixture.browserOpener.opened,
                    name,
                )
                assertEquals(middle.id, fixture.state.lastOpenedItemId, name)
            },
            RefCase("指定したブラウザで開く", act = { it.openItem(A_MIDDLE, FIREFOX) }) { fixture, name ->
                assertEquals(
                    listOf(FakeBrowserOpener.OpenRequest(FIREFOX, tabsOf(listOf(middle)), TabLoading())),
                    fixture.browserOpener.opened,
                    name,
                )
                assertEquals(middle.id, fixture.state.lastOpenedItemId, name)
            },
            RefCase("タイトルを変える", act = { it.renameItem(A_MIDDLE, "renamed") }) { fixture, name ->
                assertEquals(listOf(THREE_A_ITEMS[0], middle.copy(title = "renamed"), THREE_A_ITEMS[2]), fixture.storedItems(FOLDER_A), name)
                assertEquals(WriteOutcome.Written, fixture.state.write.outcome, name)
            },
            RefCase("ほかのアプリで開く", act = { it.openItemInOtherApp(A_MIDDLE) }) { fixture, name ->
                assertEquals(listOf(middle.url), fixture.sharer.openedUrls, name)
                assertEquals(middle.id, fixture.state.lastOpenedItemId, name)
            },
            RefCase("共有する", act = { it.shareItem(A_MIDDLE) }) { fixture, name ->
                assertEquals(listOf(middle.url to middle.title), fixture.sharer.shared, name)
                assertNull(fixture.state.lastOpenedItemId, name)
            },
        )
        cases.forEach { (name, act, check) ->
            val fixture = searching { it.store.replaceItemsAsOtherWriter(FOLDER_A, fileId(30), THREE_A_ITEMS) }
            fixture.controller.setSearchQuery("title")

            act(fixture.controller)
            runCurrent()

            check(fixture, name)
            assertEquals(ROOT_ID, fixture.state.browse.currentFolderId, name)
            assertEquals("title", fixture.state.browse.search?.query, name)
            assertEquals(emptySet(), fixture.state.browse.selectedItemIds, name)
        }
    }

    // 組で受け取って消すと、開いているフォルダでないフォルダの項目を消し、元に戻すと元の位置へ戻す
    @Test
    fun deleteItemOfOtherFolderIsUndoable() = runTest {
        val fixture = searching { it.store.replaceItemsAsOtherWriter(FOLDER_A, fileId(30), THREE_A_ITEMS) }

        fixture.controller.deleteItem(A_MIDDLE)
        runCurrent()

        assertEquals(listOf(THREE_A_ITEMS[0], THREE_A_ITEMS[2]), fixture.storedItems(FOLDER_A))
        assertEquals(listOf(THREE_A_ITEMS[0], THREE_A_ITEMS[2]), fixture.state.browse.loadedItemsOf(FOLDER_A))
        assertEquals(ROOT_ID, fixture.state.browse.currentFolderId)
        val undoable = assertNotNull(fixture.state.write.undoableDelete)
        assertFalse(undoable.pending)
        assertEquals(1, undoable.count)

        fixture.controller.undoDelete()
        runCurrent()

        assertEquals(THREE_A_ITEMS, fixture.storedItems(FOLDER_A))
        assertEquals(THREE_A_ITEMS, fixture.state.browse.loadedItemsOf(FOLDER_A))
    }

    // 組で受け取って消すとき、S3 につながらなければ、その削除を溜め、溜めた削除として元に戻せるようにする。検索の結果からも消える
    @Test
    fun deleteItemOfOtherFolderIsPendedWhenUnreachable() = runTest {
        val fixture = searching { it.store.replaceItemsAsOtherWriter(FOLDER_A, fileId(30), THREE_A_ITEMS) }
        fixture.store.unreachable = true

        fixture.controller.deleteItem(A_MIDDLE)
        runCurrent()

        val browse = fixture.state.browse
        assertEquals(setOf(A_MIDDLE), browse.pendingDeletes)
        assertEquals(listOf(THREE_A_ITEMS[0], THREE_A_ITEMS[2]), browse.loadedItemsOf(FOLDER_A))
        assertEquals(listOf(THREE_A_ITEMS[0], THREE_A_ITEMS[2]), browse.searchItems("example.com/").filter { it.ref.folderId == FOLDER_A }.map { it.item })
        assertNull(fixture.state.write.outcome)
        val undoable = assertNotNull(fixture.state.write.undoableDelete)
        assertEquals(setOf(A_MIDDLE), undoable.pendingTargets)

        assertTrue(fixture.controller.undoDelete())
        runCurrent()

        assertEquals(emptySet(), fixture.state.browse.pendingDeletes)
        assertEquals(THREE_A_ITEMS, fixture.state.browse.loadedItemsOf(FOLDER_A))
    }

    // 組で受け取る操作は、そのフォルダの読めた項目に無ければ何もしない：項目を読んでいないフォルダ、溜めた削除の項目、もう無い項目
    @Test
    fun refOperationsNeedLoadedItem() = runTest {
        val operations: List<Pair<String, (CloudMarksController, ItemRef) -> Unit>> = listOf(
            "既定のブラウザで開く" to { controller, ref -> controller.openItem(ref) },
            "指定したブラウザで開く" to { controller, ref -> controller.openItem(ref, FIREFOX) },
            "タイトルを変える" to { controller, ref -> controller.renameItem(ref, "renamed") },
            "消す" to { controller, ref -> controller.deleteItem(ref) },
            "ほかのアプリで開く" to { controller, ref -> controller.openItemInOtherApp(ref) },
            "共有する" to { controller, ref -> controller.shareItem(ref) },
        )
        val conditions: List<Triple<String, TestScope.() -> ControllerFixture, ItemRef>> = listOf(
            Triple("項目を読んでいないフォルダ", { started() }, ItemRef(FOLDER_A, A_ITEMS[0].id)),
            Triple(
                "溜めた削除の項目",
                {
                    searching().apply {
                        store.unreachable = true
                        controller.deleteItem(ItemRef(FOLDER_A, A_ITEMS[0].id))
                        runCurrent()
                        assertEquals(setOf(ItemRef(FOLDER_A, A_ITEMS[0].id)), state.browse.pendingDeletes)
                        store.unreachable = false
                        controller.dismissUndo()
                    }
                },
                ItemRef(FOLDER_A, A_ITEMS[0].id),
            ),
            Triple("もう無い項目", { searching() }, ItemRef(FOLDER_A, item(99).id)),
        )
        conditions.forEach { (condition, arrange, ref) ->
            operations.forEach { (name, act) ->
                val fixture = arrange(this)
                val requestsBefore = fixture.store.requests.size

                act(fixture.controller, ref)
                runCurrent()

                val label = "$condition, $name"
                assertEquals(requestsBefore, fixture.store.requests.size, label)
                assertEquals(emptyList(), fixture.browserOpener.opened, label)
                assertEquals(emptyList(), fixture.browserOpener.defaultOpened, label)
                assertEquals(emptyList(), fixture.sharer.openedUrls, label)
                assertEquals(emptyList(), fixture.sharer.shared, label)
                assertNull(fixture.state.write.outcome, label)
                assertNull(fixture.state.write.undoableDelete, label)
            }
        }
    }

    // 開いているフォルダの項目 ID を受け取る操作は、ほかのフォルダの読めた項目の ID を渡されても、開いているフォルダの項目として扱うので
    // 何もしない
    @Test
    fun itemIdOperationsStayInCurrentFolder() = runTest {
        val operations: List<Pair<String, (CloudMarksController) -> Unit>> = listOf(
            "消す" to { it.deleteItems(setOf(A_ITEMS[0].id)) },
            "ほかのアプリで開く" to { it.openItemInOtherApp(A_ITEMS[0].id) },
            "共有する" to { it.shareItem(A_ITEMS[0].id) },
        )
        operations.forEach { (name, act) ->
            val fixture = searching()
            assertEquals(A_ITEMS, fixture.state.browse.loadedItemsOf(FOLDER_A), name)
            val putsBefore = fixture.store.puts { true }.size

            act(fixture.controller)
            runCurrent()

            assertEquals(putsBefore, fixture.store.puts { true }.size, name)
            assertEquals(emptyList(), fixture.browserOpener.opened, name)
            assertEquals(emptyList(), fixture.browserOpener.defaultOpened, name)
            assertEquals(emptyList(), fixture.sharer.openedUrls, name)
            assertEquals(emptyList(), fixture.sharer.shared, name)
            assertNull(fixture.state.write.outcome, name)
        }
    }

    // 開いているフォルダの項目 ID を受け取る操作は、開いているフォルダの項目に効く（組で受け取る操作に渡す）
    @Test
    fun itemIdOperationsActOnCurrentFolder() = runTest {
        val fixture = searching()
        fixture.controller.closeSearch()
        fixture.controller.openFolder(FOLDER_A)
        runCurrent()

        fixture.controller.openItemInOtherApp(A_ITEMS[0].id)
        fixture.controller.shareItem(A_ITEMS[0].id)
        runCurrent()

        assertEquals(listOf(A_ITEMS[0].url), fixture.sharer.openedUrls)
        assertEquals(listOf(A_ITEMS[0].url to A_ITEMS[0].title), fixture.sharer.shared)

        fixture.controller.deleteItems(setOf(A_ITEMS[0].id))
        runCurrent()

        assertEquals(emptyList(), fixture.storedItems(FOLDER_A))
        assertEquals(1, fixture.state.write.undoableDelete?.count)
    }
}
