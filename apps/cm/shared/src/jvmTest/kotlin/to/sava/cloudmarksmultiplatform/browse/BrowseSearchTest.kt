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

/** [browseWithUnreadItems] で、A1 と A1A の項目を読んだ状態。A2 だけが、項目を読んでいないフォルダ。ルートフォルダを開いている。 */
private fun browseWithA2Unread(): BrowseState =
    browseWithUnreadItems()
        .withFolderRead("\"etag-1\"", A1, FolderReadResult.Loaded(A1_ITEMS))
        .withFolderRead("\"etag-1\"", A1A, FolderReadResult.Loaded(listOf(item(3))))

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

    // マニフェストを読み直したとき（読み書きできるツリーでも読むだけのツリーでも）、ツリーと一緒に読まなかったフォルダのうち、
    // 項目ファイルの ID が同じものの読めた項目と壊れていた結果は引き継ぐ。読めなかった結果と項目ファイルが無かった結果は引き継がず、読み直す
    @Test
    fun withTreeReadKeepsLoadedAndBrokenReadsOfSameItemsFile() {
        val before = browseWithUnreadItems()
            .withFolderRead("\"etag-1\"", A1, FolderReadResult.Broken(BROKEN))
            .withFolderRead("\"etag-1\"", A1A, FolderReadResult.Loaded(listOf(item(3))))
            .withFolderRead("\"etag-1\"", A2, FolderReadResult.Failed(FAILURE))
        val missing = browseWithUnreadItems()
            .withFolderRead("\"etag-1\"", A1, FolderReadResult.Loaded(A1_ITEMS))
            .withFolderRead("\"etag-1\"", A1A, FolderReadResult.Missing)
            .withFolderRead("\"etag-1\"", A2, FolderReadResult.Loaded(listOf(item(4))))
        val reread = loadedTree(entriesWithItemFiles(), eTag = "\"etag-2\"")
        val results = listOf(TreeReadResult.Readable(reread), TreeReadResult.ReadOnly(reread))

        results.forEach { result ->
            val after = before.startLoading().withTreeRead(result)
            assertEquals(FolderReadResult.Broken(BROKEN), after.folderReads[A1], "$result")
            assertEquals(FolderReadResult.Loaded(listOf(item(3))), after.folderReads[A1A], "$result")
            assertFalse(A2 in after.folderReads, "$result")
            assertEquals(listOf(A2), after.foldersToLoadForSearch(), "$result")

            val afterMissing = missing.startLoading().withTreeRead(result)
            assertEquals(FolderReadResult.Loaded(A1_ITEMS), afterMissing.folderReads[A1], "$result")
            assertFalse(A1A in afterMissing.folderReads, "$result")
            assertEquals(FolderReadResult.Loaded(listOf(item(4))), afterMissing.folderReads[A2], "$result")
            assertEquals(listOf(A1A), afterMissing.foldersToLoadForSearch(), "$result")
        }
    }

    // マニフェストを読み直したとき、項目ファイルの ID が替わったフォルダ（ほかの書き手が替えた）の結果は引き継がず、検索で読む必要があるフォルダに戻る。
    // 読めた結果も壊れていた結果も同じ
    @Test
    fun withTreeReadDropsReadsOfFoldersWithNewItemsFile() {
        val before = browseWithUnreadItems()
            .withFolderRead("\"etag-1\"", A1, FolderReadResult.Broken(BROKEN))
            .withFolderRead("\"etag-1\"", A1A, FolderReadResult.Loaded(listOf(item(3))))
            .withFolderRead("\"etag-1\"", A2, FolderReadResult.Loaded(listOf(item(4))))
        val replacedEntries = entriesWithItemFiles().map { if (it.id == A1 || it.id == A1A) it.copy(items = fileId(99)) else it }
        val reread = loadedTree(replacedEntries, eTag = "\"etag-2\"")

        val after = before.startLoading().withTreeRead(TreeReadResult.Readable(reread))

        assertFalse(A1 in after.folderReads)
        assertFalse(A1A in after.folderReads)
        assertEquals(FolderReadResult.Loaded(listOf(item(4))), after.folderReads[A2])
        assertEquals(listOf(A1, A1A), after.foldersToLoadForSearch())
    }

    // マニフェストを読み直したとき、ツリーと一緒に読んだ結果は、引き継いだ結果より優先する
    @Test
    fun withTreeReadPrefersReadsLoadedWithTree() {
        val before = browseWithUnreadItems()
            .withFolderRead("\"etag-1\"", A1, FolderReadResult.Loaded(A1_ITEMS))
            .withFolderRead("\"etag-1\"", A1A, FolderReadResult.Loaded(listOf(item(3))))
            .withFolderRead("\"etag-1\"", A2, FolderReadResult.Loaded(listOf(item(4))))
        val reread = loadedTree(entriesWithItemFiles(), eTag = "\"etag-2\"", loadedItems = mapOf(A1 to listOf(item(9))))
            .copy(brokenFolders = mapOf(A1A to BROKEN))

        val after = before.startLoading().withTreeRead(TreeReadResult.Readable(reread))

        assertEquals(FolderReadResult.Loaded(listOf(item(9))), after.folderReads[A1])
        assertEquals(FolderReadResult.Broken(BROKEN), after.folderReads[A1A])
        assertEquals(FolderReadResult.Loaded(listOf(item(4))), after.folderReads[A2])
    }

    // 接続を作り直した（cleared）あとは前の結果が無いので、読んだマニフェストと一緒に読んだ結果のほかは何も引き継がない
    @Test
    fun withTreeReadAfterClearedKeepsNothing() {
        val before = browseWithUnreadItems()
            .withFolderRead("\"etag-1\"", A1, FolderReadResult.Loaded(A1_ITEMS))
            .withFolderRead("\"etag-1\"", A1A, FolderReadResult.Broken(BROKEN))
        val reread = loadedTree(entriesWithItemFiles(), eTag = "\"etag-2\"")

        val after = before.cleared().startLoading().withTreeRead(TreeReadResult.Readable(reread))

        assertEquals(reread.state.items.mapValues { (_, items) -> FolderReadResult.Loaded(items) }, after.folderReads)
        assertEquals(listOf(A1, A1A, A2), after.foldersToLoadForSearch())
    }

    // 検索のための読み込みの最中か：検索を開いていて、読む必要があるフォルダ・読んでいる最中のフォルダ・項目ファイルが無かったフォルダの
    // どれかがあるか、マニフェストを読んでいる最中なら最中。読めなかった・壊れていたフォルダが残るだけなら終わり。検索を閉じていれば最中でない
    @Test
    fun searchLoadingFollowsFolderReadsAndManifestLoading() {
        val allRead = browseWithA2Unread().withFolderRead("\"etag-1\"", A2, FolderReadResult.Loaded(listOf(item(4))))
        val cases: List<Triple<String, BrowseState, Boolean>> = listOf(
            Triple("読む必要があるフォルダがある", browseWithA2Unread().openSearch(), true),
            Triple("読んでいる最中のフォルダがある", browseWithA2Unread().openSearch().withFolderLoading(A2), true),
            Triple(
                "項目ファイルが無かったフォルダがある",
                browseWithA2Unread().withFolderRead("\"etag-1\"", A2, FolderReadResult.Missing).openSearch(),
                true,
            ),
            Triple("マニフェストを読んでいる最中", allRead.openSearch().startLoading(), true),
            Triple("すべて読めた", allRead.openSearch(), false),
            Triple(
                "読めなかった・壊れていたフォルダが残るだけ",
                browseWithA2Unread().openSearch()
                    .withFolderRead("\"etag-1\"", A2, FolderReadResult.Failed(FAILURE))
                    .withFolderRead("\"etag-1\"", A1A, FolderReadResult.Broken(BROKEN)),
                false,
            ),
            Triple("検索を閉じている（読む必要があるフォルダがある）", browseWithUnreadItems(), false),
            Triple("検索を閉じている（読んでいる最中）", browseWithUnreadItems().withFolderLoading(A1).startLoading(), false),
        )

        cases.forEach { (name, state, expected) -> assertEquals(expected, state.searchLoading, name) }
    }

    // 読んでいる最中のフォルダと項目ファイルが無かったフォルダは、それぞれ単独で最中にする（読む必要があるフォルダが残っていなくても）
    @Test
    fun searchLoadingWithoutFoldersToLoad() {
        val loading = browseWithA2Unread().openSearch().withFolderLoading(A2)
        val missing = browseWithA2Unread().withFolderRead("\"etag-1\"", A2, FolderReadResult.Missing).openSearch()

        listOf(loading, missing).forEach { state ->
            assertEquals(emptyList(), state.foldersToLoadForSearch())
            assertFalse(state.loading)
            assertTrue(state.searchLoading)
        }
    }

    // 読まずにやめると、そのフォルダだけを読んでいる最中から外し、ほかの読んでいる最中のフォルダと読んだ結果は替えない。
    // 読んでいる最中でないフォルダなら何も替えない。外したフォルダは、検索のために読む必要があるフォルダに戻る
    @Test
    fun withoutFolderLoadingRemovesOnlyThatFolder() {
        val loading = browseWithUnreadItems().openSearch().withFolderLoading(A1).withFolderLoading(A2)

        val stopped = loading.withoutFolderLoading(A2)

        assertEquals(setOf(A1), stopped.loadingFolders)
        assertEquals(loading.folderReads, stopped.folderReads)
        assertEquals(listOf(A1A, A2), stopped.foldersToLoadForSearch())
        assertEquals(loading, loading.withoutFolderLoading(A1A))
    }

    // フォルダの読めた項目は、開いているフォルダでなくても引ける。溜めた削除の項目は除き、並びは変えない。
    // 読んでいないフォルダ・読めなかったフォルダ・ツリーに無いフォルダは空
    @Test
    fun loadedItemsOfAnyFolderExcludesPendingDeletes() {
        val state = browseWithA2Unread()
            .withFolderRead("\"etag-1\"", A1A, FolderReadResult.Failed(FAILURE))
            .withPendingDeletes(setOf(ItemRef(A1, item(1).id)))
        assertEquals(ROOT, state.currentFolderId)

        assertEquals(listOf(item(2)), state.loadedItemsOf(A1))
        assertEquals(A1_ITEMS, browseWithA2Unread().loadedItemsOf(A1))
        assertEquals(emptyList(), state.loadedItemsOf(A2))
        assertEquals(emptyList(), state.loadedItemsOf(A1A))
        assertEquals(emptyList(), state.loadedItemsOf("unknown"))
    }

    // フォルダと項目の組から、そのフォルダの読めた項目を引く。開いているフォルダでなくても引ける。溜めた削除の項目、
    // 読んでいないフォルダの項目、そのフォルダの読めた項目に無い項目（ほかのフォルダにある項目を含む）は null
    @Test
    fun loadedItemOfLooksUpItsFolder() {
        val state = browseWithA2Unread().withPendingDeletes(setOf(ItemRef(A1, item(1).id)))
        assertEquals(ROOT, state.currentFolderId)

        assertEquals(item(2), state.loadedItemOf(ItemRef(A1, item(2).id)))
        assertEquals(item(3), state.loadedItemOf(ItemRef(A1A, item(3).id)))
        assertNull(state.loadedItemOf(ItemRef(A1, item(1).id)))
        assertNull(state.loadedItemOf(ItemRef(A2, item(4).id)))
        assertNull(state.loadedItemOf(ItemRef(A1, item(99).id)))
        assertNull(state.loadedItemOf(ItemRef(A1A, item(2).id)))
        assertNull(state.loadedItemOf(ItemRef("unknown", item(2).id)))
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
