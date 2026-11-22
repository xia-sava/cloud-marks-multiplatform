package to.sava.cloudmarksmultiplatform.browse

import to.sava.cloudmarksmultiplatform.operations.ItemRef
import to.sava.cloudmarksmultiplatform.storage.FolderEntry
import to.sava.cloudmarksmultiplatform.storage.StorageProblem
import to.sava.cloudmarksmultiplatform.sync.FolderReadResult
import to.sava.cloudmarksmultiplatform.sync.SyncFailure
import to.sava.cloudmarksmultiplatform.sync.TreeReadResult
import to.sava.cloudmarksmultiplatform.sync.fileId
import to.sava.cloudmarksmultiplatform.sync.item
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private val FAILURE = SyncFailure.Unreachable("HTTP 500")
private val BROKEN = StorageProblem(line = null, message = "壊れている")

/** [treeEntries] の A1A と A2 にも項目ファイルを持たせた行。A1・A1A・A2 が項目を持つ。 */
private fun entriesWithItemFiles(): List<FolderEntry> =
    treeEntries().map { entry ->
        when (entry.id) {
            A1A -> entry.copy(items = fileId(3), count = 1)
            A2 -> entry.copy(items = fileId(2), count = 1)
            else -> entry
        }
    }

/** [entriesWithItemFiles] のツリーを読んだ状態。A1・A1A・A2 の項目は読んでいない。ルートフォルダを開いている。 */
private fun browseWithUnreadItems(): BrowseState = browseWithTree(loadedTree(entriesWithItemFiles()))

/** A1 を開き、その項目として [A1_ITEMS] を読んだ状態。 */
private fun browseWithA1Loaded(): BrowseState =
    browseWithUnreadItems().openFolder(A1).withFolderRead("\"etag-1\"", A1, FolderReadResult.Loaded(A1_ITEMS))

class BrowseSearchTest {
    // 検索を開くと、入力した語は空で、選んでいる項目と範囲で選ぶときの起点を外す。開く前は閉じている
    @Test
    fun openSearchStartsEmptyAndClearsSelection() {
        val selected = browseWithA1Loaded().selectItem(item(1).id, SelectionMode.SINGLE)
        assertNull(selected.search)

        val opened = selected.openSearch()

        assertEquals(ItemSearchState(""), opened.search)
        assertEquals(emptySet(), opened.selectedItemIds)
        assertNull(opened.selectionAnchorId)
        assertEquals(A1, opened.currentFolderId)
    }

    // 検索を開くと、狭い画面で開いているドロワーを閉じる
    @Test
    fun openSearchClosesDrawer() {
        val drawerOpen = browseWithA1Loaded().withWideLayout(false).toggleTreePane()
        assertTrue(drawerOpen.drawerOpen)

        val opened = drawerOpen.openSearch()

        assertFalse(opened.drawerOpen)
        assertEquals(ItemSearchState(), opened.search)
    }

    // 検索を開くと、読めなかった（失敗した）フォルダの読んだ結果を除いて読み直せるようにする。
    // 読めた・壊れた・項目ファイルが無かった（Missing）フォルダは残す
    @Test
    fun openSearchDropsFailedReadsOnly() {
        val base = browseWithA1Loaded()
        val state = base.copy(
            folderReads = base.folderReads +
                (A1A to FolderReadResult.Failed(FAILURE)) +
                (A2 to FolderReadResult.Broken(BROKEN)) +
                (B to FolderReadResult.Missing),
        )

        val opened = state.openSearch()

        assertFalse(A1A in opened.folderReads)
        assertEquals(FolderReadResult.Broken(BROKEN), opened.folderReads[A2])
        assertEquals(FolderReadResult.Missing, opened.folderReads[B])
        assertEquals(FolderReadResult.Loaded(A1_ITEMS), opened.folderReads[A1])
    }

    // 既に開いていれば、検索を開いても何も変わらない（入力した語も残る）
    @Test
    fun openSearchWhenOpenDoesNothing() {
        val opened = browseWithA1Loaded().openSearch().withSearchQuery("foo")

        assertSame(opened, opened.openSearch())
        assertEquals("foo", opened.openSearch().search?.query)
    }

    // 入力した語を替える。検索を閉じていれば何もしない
    @Test
    fun withSearchQueryChangesQueryOnlyWhenOpen() {
        val closed = browseWithA1Loaded()
        assertSame(closed, closed.withSearchQuery("foo"))

        val changed = closed.openSearch().withSearchQuery("foo").withSearchQuery("bar baz")

        assertEquals(ItemSearchState("bar baz"), changed.search)
    }

    // 検索を閉じる
    @Test
    fun closeSearchClosesIt() {
        assertNull(browseWithA1Loaded().openSearch().withSearchQuery("foo").closeSearch().search)
    }

    // フォルダを開くと検索を閉じる。開いているフォルダと同じフォルダを開いたときも閉じる
    @Test
    fun openFolderClosesSearch() {
        val searching = browseWithA1Loaded().openSearch()

        val other = searching.openFolder(B)
        val same = searching.openFolder(A1)

        assertNull(other.search)
        assertEquals(B, other.currentFolderId)
        assertNull(same.search)
        assertEquals(A1, same.currentFolderId)
        assertEquals(listOf(ROOT), same.history)
    }

    // 戻る：ドロワーが開いていれば、閉じるだけ。検索はそのまま
    @Test
    fun backClosesDrawerBeforeSearch() {
        val state = browseWithA1Loaded().openSearch().withWideLayout(false).toggleTreePane()
        assertTrue(state.drawerOpen)

        val back = state.back()

        assertFalse(back.drawerOpen)
        assertEquals(ItemSearchState(), back.search)
    }

    // 戻る：検索を開いていれば、検索を閉じるだけ。履歴は戻らない。履歴が無くても、検索を開いていれば戻れる
    @Test
    fun backClosesSearchBeforeHistory() {
        val withHistory = browseWithA1Loaded().openSearch()
        assertEquals(listOf(ROOT), withHistory.history)
        assertTrue(withHistory.canGoBack)

        val back = withHistory.back()

        assertNull(back.search)
        assertEquals(A1, back.currentFolderId)
        assertEquals(listOf(ROOT), back.history)

        val noHistory = browseWithUnreadItems().openSearch()
        assertTrue(noHistory.canGoBack)
        assertFalse(noHistory.back().canGoBack)
    }

    // 戻る：検索を閉じていれば、履歴を戻る
    @Test
    fun backWithoutSearchGoesBackInHistory() {
        val back = browseWithA1Loaded().openSearch().closeSearch().back()

        assertEquals(ROOT, back.currentFolderId)
        assertEquals(emptyList(), back.history)
    }

    // 項目が入っているフォルダを開く：検索を閉じ、そのフォルダを開いて履歴に積み、その項目だけを選ぶ
    @Test
    fun openFolderOfOpensFolderAndSelectsItem() {
        val searching = browseWithUnreadItems()
            .withFolderRead("\"etag-1\"", A1, FolderReadResult.Loaded(A1_ITEMS))
            .openSearch()

        val opened = searching.openFolderOf(ItemRef(A1, item(2).id))

        assertNull(opened.search)
        assertEquals(A1, opened.currentFolderId)
        assertEquals(listOf(ROOT), opened.history)
        assertEquals(setOf(item(2).id), opened.selectedItemIds)
        assertEquals(item(2).id, opened.selectionAnchorId)
        assertEquals(listOf(item(2)), opened.selectedItems)
        assertTrue(A1 in opened.expandedFolders)
    }

    // 開いているフォルダと同じフォルダの項目なら、履歴に積まずに、その項目だけを選ぶ（それまでの選択は外れる）
    @Test
    fun openFolderOfCurrentFolderDoesNotPushHistory() {
        val state = browseWithA1Loaded().selectItem(item(1).id, SelectionMode.SINGLE).openSearch()

        val opened = state.openFolderOf(ItemRef(A1, item(2).id))

        assertNull(opened.search)
        assertEquals(listOf(ROOT), opened.history)
        assertEquals(setOf(item(2).id), opened.selectedItemIds)
    }

    // 検索を開いていなくても、同じフォルダの別の項目を選んでいる状態から、その項目だけを選ぶ
    @Test
    fun openFolderOfSelectsOnlyItemWithoutSearch() {
        val selected = browseWithA1Loaded().selectItem(item(1).id, SelectionMode.SINGLE)
        assertNull(selected.search)

        val opened = selected.openFolderOf(ItemRef(A1, item(2).id))

        assertNull(opened.search)
        assertEquals(setOf(item(2).id), opened.selectedItemIds)
        assertEquals(item(2).id, opened.selectionAnchorId)
        assertEquals(listOf(ROOT), opened.history)
    }

    // 開いたフォルダの読めた項目に無ければ、項目は選ばない：溜めた削除の項目、項目を読めていないフォルダ。フォルダは開いて検索を閉じる
    @Test
    fun openFolderOfWithoutLoadedItemSelectsNothing() {
        val pending = browseWithUnreadItems()
            .withFolderRead("\"etag-1\"", A1, FolderReadResult.Loaded(A1_ITEMS))
            .withPendingDeletes(setOf(ItemRef(A1, item(2).id)))
            .openSearch()
            .openFolderOf(ItemRef(A1, item(2).id))
        val unread = browseWithUnreadItems().openSearch().openFolderOf(ItemRef(A2, item(3).id))

        assertEquals(A1, pending.currentFolderId)
        assertEquals(emptySet(), pending.selectedItemIds)
        assertNull(pending.selectionAnchorId)
        assertNull(pending.search)
        assertEquals(A2, unread.currentFolderId)
        assertEquals(emptySet(), unread.selectedItemIds)
        assertNull(unread.search)
    }

    // ツリーに無いフォルダの項目なら、何もしない（検索も開いたまま）
    @Test
    fun openFolderOfUnknownFolderDoesNothing() {
        val state = browseWithA1Loaded().openSearch()

        assertSame(state, state.openFolderOf(ItemRef("unknown", item(1).id)))
    }

    // 検索で読む必要のあるフォルダ：読んだ結果も読んでいる最中の印も無いフォルダを、ツリーの行きがけ順で返す
    @Test
    fun foldersToLoadForSearchSkipsReadAndLoading() {
        val state = browseWithUnreadItems()
        assertEquals(listOf(A1, A1A, A2), state.foldersToLoadForSearch())

        val partlyRead = state
            .withFolderLoading(A1A)
            .withFolderRead("\"etag-1\"", A2, FolderReadResult.Failed(FAILURE))

        assertEquals(listOf(A1), partlyRead.foldersToLoadForSearch())
        assertEquals(emptyList(), BrowseState().foldersToLoadForSearch())
    }

    // 検索で読めなかったフォルダ：読んだ結果が失敗か壊れているフォルダを、ツリーの行きがけ順で返す
    @Test
    fun unreadableFoldersForSearchListsFailedAndBroken() {
        val state = browseWithUnreadItems()
            .withFolderRead("\"etag-1\"", A2, FolderReadResult.Failed(FAILURE))
            .withFolderRead("\"etag-1\"", A1, FolderReadResult.Broken(BROKEN))
            .withFolderRead("\"etag-1\"", A1A, FolderReadResult.Loaded(listOf(item(3))))

        assertEquals(listOf(A1, A2), state.unreadableFoldersForSearch())
        assertEquals(emptyList(), BrowseState().unreadableFoldersForSearch())
    }

    // 項目ファイルが無かった（Missing）フォルダは、読む必要があるものにも、読めなかったものにも含めない
    @Test
    fun missingFoldersAreNeitherToLoadNorUnreadable() {
        val state = browseWithUnreadItems().withFolderRead("\"etag-1\"", A1, FolderReadResult.Missing)

        assertEquals(listOf(A1A, A2), state.foldersToLoadForSearch())
        assertEquals(emptyList(), state.unreadableFoldersForSearch())
    }

    // 操作を書いたあとのツリーに替えるとき、項目ファイルの ID が同じフォルダの読めた項目と壊れていた結果は引き継ぐ。
    // 読めなかった結果は引き継がず、項目ファイルの ID が替わったフォルダは読み直す
    @Test
    fun withWrittenTreeKeepsLoadedAndBrokenReadsOfSameItemsFile() {
        val state = browseWithUnreadItems()
            .withFolderRead("\"etag-1\"", A1, FolderReadResult.Broken(BROKEN))
            .withFolderRead("\"etag-1\"", A1A, FolderReadResult.Loaded(listOf(item(3))))
            .withFolderRead("\"etag-1\"", A2, FolderReadResult.Failed(FAILURE))
        val sameFiles = loadedTree(entriesWithItemFiles(), eTag = "\"etag-2\"")
        val replacedA1A = loadedTree(
            entriesWithItemFiles().map { if (it.id == A1A) it.copy(items = fileId(99)) else it },
            eTag = "\"etag-2\"",
        )

        val kept = state.withWrittenTree(sameFiles)
        val replaced = state.withWrittenTree(replacedA1A)

        assertEquals(FolderReadResult.Broken(BROKEN), kept.folderReads[A1])
        assertEquals(FolderReadResult.Loaded(listOf(item(3))), kept.folderReads[A1A])
        assertFalse(A2 in kept.folderReads)
        assertEquals(listOf(A2), kept.foldersToLoadForSearch())
        assertEquals(FolderReadResult.Broken(BROKEN), replaced.folderReads[A1])
        assertFalse(A1A in replaced.folderReads)
        assertEquals(listOf(A1A, A2), replaced.foldersToLoadForSearch())
    }

    // マニフェストを読み直すと、ツリーと一緒に読まなかったフォルダの項目は読んだ結果から外れ、検索で読む必要があるフォルダに戻る
    @Test
    fun withTreeReadMakesUnreadFoldersLoadableAgain() {
        val before = browseWithA1Loaded()
            .withFolderRead("\"etag-1\"", A1A, FolderReadResult.Loaded(listOf(item(3))))
            .withFolderRead("\"etag-1\"", A2, FolderReadResult.Loaded(listOf(item(4))))
        assertEquals(emptyList(), before.foldersToLoadForSearch())
        val reread = loadedTree(entriesWithItemFiles(), eTag = "\"etag-2\"", loadedItems = mapOf(A1 to A1_ITEMS))

        val after = before.startLoading().withTreeRead(TreeReadResult.Readable(reread))

        assertEquals(listOf(A1A, A2), after.foldersToLoadForSearch())
    }

    // マニフェストを読み直しても、書いたあとのツリーに替えても、検索の状態は残す。接続先を替えたとき（cleared）は捨てる
    @Test
    fun searchSurvivesTreeReplacementAndIsClearedOnReset() {
        val searching = browseWithA1Loaded().openSearch().withSearchQuery("foo")
        val reread = loadedTree(entriesWithItemFiles(), eTag = "\"etag-2\"", loadedItems = mapOf(A1 to A1_ITEMS))

        val afterRead = searching.startLoading().withTreeRead(TreeReadResult.Readable(reread))
        val afterWrite = searching.withWrittenTree(reread)
        val afterFailure = searching.startLoading().withTreeRead(TreeReadResult.Failed(FAILURE))

        listOf(afterRead, afterWrite, afterFailure).forEach { assertEquals(ItemSearchState("foo"), it.search) }
        assertNull(searching.cleared().search)
    }
}
