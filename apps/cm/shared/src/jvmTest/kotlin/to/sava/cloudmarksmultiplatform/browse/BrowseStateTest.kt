package to.sava.cloudmarksmultiplatform.browse

import to.sava.cloudmarksmultiplatform.app.AppState
import to.sava.cloudmarksmultiplatform.app.Screen
import to.sava.cloudmarksmultiplatform.app.SettingsScreenState
import to.sava.cloudmarksmultiplatform.storage.StorageProblem
import to.sava.cloudmarksmultiplatform.sync.FolderReadResult
import to.sava.cloudmarksmultiplatform.sync.SyncFailure
import to.sava.cloudmarksmultiplatform.sync.TreeReadResult
import to.sava.cloudmarksmultiplatform.sync.item
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BrowseStateTest {
    // 初めてフォルダのツリーを読むと、ルートフォルダを開き、ツリーではルートフォルダだけを開く。履歴は空で、戻れない
    @Test
    fun firstTreeOpensRoot() {
        val state = browseWithTree()

        assertEquals(ROOT, state.currentFolderId)
        assertEquals(setOf(ROOT), state.expandedFolders)
        assertEquals(emptyList(), state.history)
        assertFalse(state.loading)
        assertFalse(state.canGoBack)
    }

    // フォルダを開くと、開いていたフォルダを履歴に積む。戻ると履歴の最後を開き、履歴から除く（親へ上がるのではない）
    @Test
    fun backFollowsOpenedHistory() {
        val opened = browseWithTree().openFolder(A1A).openFolder(B)

        assertEquals(listOf(ROOT, A1A), opened.history)
        assertTrue(opened.canGoBack)

        val once = opened.back()
        assertEquals(A1A, once.currentFolderId)
        assertEquals(listOf(ROOT), once.history)

        val twice = once.back()
        assertEquals(ROOT, twice.currentFolderId)
        assertEquals(emptyList(), twice.history)
        assertFalse(twice.canGoBack)
    }

    // 履歴が無ければ、戻っても何も変わらない
    @Test
    fun backWithoutHistoryDoesNothing() {
        val state = browseWithTree()

        assertEquals(state, state.back())
    }

    // 開いているフォルダをもう一度開いても、履歴に積まない。フォルダのツリーに無いフォルダは開かない
    @Test
    fun reopeningOrUnknownFolderDoesNotPushHistory() {
        val state = browseWithTree().openFolder(A)

        assertEquals(state, state.openFolder(A))
        assertEquals(state, state.openFolder("unknown"))
    }

    // フォルダを開くと、ツリーでその祖先を開き、選んでいた項目を外す
    @Test
    fun openingFolderExpandsAncestorsAndClearsSelection() {
        val state = browseWithTree().selectItem(item(1).id).openFolder(A1A)

        assertEquals(setOf(ROOT, A, A1), state.expandedFolders)
        assertNull(state.selectedItemId)
    }

    // ツリーで祖先を閉じたあとでも、戻って開いたフォルダの祖先は、ツリーで開いた状態になる
    @Test
    fun backExpandsAncestorsOfReturnedFolder() {
        val collapsed = browseWithTree().openFolder(A1A).openFolder(B)
            .toggleExpanded(A).toggleExpanded(A1)
        assertEquals(setOf(ROOT), collapsed.expandedFolders)

        val back = collapsed.back()

        assertEquals(A1A, back.currentFolderId)
        assertEquals(setOf(ROOT, A, A1), back.expandedFolders)
    }

    // 狭い画面でドロワーが開いていれば、戻るはまずドロワーを閉じ、フォルダは替えない。もう一度戻ると履歴を戻る
    @Test
    fun backClosesDrawerFirst() {
        val drawerOpen = browseWithTree().withWideLayout(false).openFolder(A).toggleTreePane()
        assertTrue(drawerOpen.drawerOpen)
        assertTrue(drawerOpen.canGoBack)

        val closed = drawerOpen.back()
        assertFalse(closed.drawerOpen)
        assertEquals(A, closed.currentFolderId)
        assertEquals(listOf(ROOT), closed.history)

        assertEquals(ROOT, closed.back().currentFolderId)
    }

    // 画面全体の戻るも、メインの画面ではドロワーを先に閉じる。設定の画面では、戻れるときだけメインの画面へ戻る
    @Test
    fun appBackClosesDrawerFirstAndLeavesSettings() {
        val browse = browseWithTree().withWideLayout(false).openFolder(A).toggleTreePane()
        val main = AppState(screen = Screen.MAIN, browse = browse)

        val back = main.back()
        assertEquals(Screen.MAIN, back.screen)
        assertFalse(back.browse.drawerOpen)
        assertEquals(A, back.browse.currentFolderId)

        val returnable = AppState(screen = Screen.SETTINGS, browse = browse, settings = SettingsScreenState(canReturn = true))
        assertEquals(Screen.MAIN, returnable.back().screen)
        val notReturnable = AppState(screen = Screen.SETTINGS, settings = SettingsScreenState(canReturn = false))
        assertFalse(notReturnable.canGoBack)
        assertEquals(notReturnable, notReturnable.back())
    }

    // ハンバーガーは、広い画面ではツリーの枠を、狭い画面ではドロワーを出し入れする
    @Test
    fun treePaneToggleDependsOnLayout() {
        val wide = browseWithTree().toggleTreePane()
        assertFalse(wide.treePaneVisible)
        assertFalse(wide.drawerOpen)

        val narrow = browseWithTree().withWideLayout(false).toggleTreePane()
        assertTrue(narrow.treePaneVisible)
        assertTrue(narrow.drawerOpen)
    }

    // ドロワーから開いたフォルダはドロワーを閉じる。広い画面へ替わってもドロワーを閉じる
    @Test
    fun drawerClosesOnOpenAndOnWideLayout() {
        val drawerOpen = browseWithTree().withWideLayout(false).toggleTreePane()

        assertFalse(drawerOpen.openFolder(A).drawerOpen)
        assertFalse(drawerOpen.openFolder(ROOT).drawerOpen)
        assertFalse(drawerOpen.withWideLayout(true).drawerOpen)
    }

    // 読み直したあと、開いていたフォルダが残っていれば開いたままにし、選んでいた項目もまだあれば残す
    @Test
    fun reloadKeepsOpenFolderAndSelection() {
        val selected = A1_ITEMS.first().id
        val before = browseWithTree()
            .openFolder(A1)
            .withFolderRead("\"etag-1\"", A1, FolderReadResult.Loaded(A1_ITEMS))
            .selectItem(selected)
        val reloaded = loadedTree(eTag = "\"etag-2\"", loadedItems = mapOf(A1 to A1_ITEMS))

        val after = before.startLoading().withTreeRead(TreeReadResult.Readable(reloaded))

        assertEquals(A1, after.currentFolderId)
        assertEquals(selected, after.selectedItemId)
        assertEquals(listOf(ROOT), after.history)
        assertEquals(FolderReadResult.Loaded(A1_ITEMS), after.currentItems)
        assertFalse(after.loading)
    }

    // 読み直したあと、選んでいた項目が無くなっていれば、選択を外す
    @Test
    fun reloadClearsSelectionWhenItemIsGone() {
        val before = browseWithTree()
            .openFolder(A1)
            .withFolderRead("\"etag-1\"", A1, FolderReadResult.Loaded(A1_ITEMS))
            .selectItem(A1_ITEMS.first().id)
        val reloaded = loadedTree(eTag = "\"etag-2\"", loadedItems = mapOf(A1 to listOf(item(9))))

        val after = before.withTreeRead(TreeReadResult.Readable(reloaded))

        assertEquals(A1, after.currentFolderId)
        assertNull(after.selectedItemId)
    }

    // 読み直したあと、開いていたフォルダが消えていればルートフォルダを開く。ツリーで開いているノードから、消えたフォルダを除く
    @Test
    fun reloadFallsBackToRootWhenOpenFolderIsGone() {
        val before = browseWithTree().openFolder(A1A)
        assertEquals(setOf(ROOT, A, A1), before.expandedFolders)
        val withoutA1 = loadedTree(treeEntries(without = setOf(A1)), eTag = "\"etag-2\"")

        val after = before.withTreeRead(TreeReadResult.Readable(withoutA1))

        assertEquals(ROOT, after.currentFolderId)
        assertNull(after.selectedItemId)
        assertEquals(setOf(ROOT, A), after.expandedFolders)
    }

    // 読み直したあと、履歴から消えたフォルダを除く。除いた結果、続けて同じフォルダが並ぶところは 1 つにし、
    // 末尾の、開いているフォルダと同じものも除く
    @Test
    fun reloadRemovesGoneFoldersFromHistory() {
        val cases: List<Triple<List<String>, Set<String>, List<String>>> = listOf(
            // 履歴 ROOT, A, B, A から B を除くと A が続くので 1 つにする
            Triple(listOf(A, B, A, A1A), setOf(A1, B), listOf(ROOT, A)),
            // 履歴 ROOT, A1A から A1A を除くと、末尾が開いているルートフォルダと同じになるので除く
            Triple(listOf(A1A, ROOT), setOf(A1), emptyList()),
        )
        cases.forEach { (opens, removed, expectedHistory) ->
            val before = opens.fold(browseWithTree()) { state, folderId -> state.openFolder(folderId) }

            val after = before.withTreeRead(TreeReadResult.Readable(loadedTree(treeEntries(without = removed), eTag = "\"etag-2\"")))

            assertEquals(ROOT, after.currentFolderId, "$opens")
            assertEquals(expectedHistory, after.history, "$opens")
        }
    }

    // 読み直しに失敗したら、前に読めたフォルダのツリーをそのまま残し、失敗を持つ
    @Test
    fun failedReloadKeepsPreviousTree() {
        val before = browseWithTree().openFolder(A)
        val failure = SyncFailure.Unreachable("HTTP 500")

        val after = before.startLoading().withTreeRead(TreeReadResult.Failed(failure))

        assertSame(before.tree, after.tree)
        assertEquals(A, after.currentFolderId)
        assertEquals(failure, after.failure)
        assertFalse(after.loading)
    }

    // 項目を読み始めたフォルダは、読み終えるまでもう一度読まない。前のフォルダのツリー（ETag が違う）で読んだ結果は捨てる
    @Test
    fun folderReadAppliesOnlyToCurrentTree() {
        val loading = browseWithTree().openFolder(A1).let { it.withFolderLoading(checkNotNull(it.folderToLoad())) }
        assertNull(loading.folderToLoad())

        val stale = loading.withFolderRead("\"etag-old\"", A1, FolderReadResult.Loaded(A1_ITEMS))
        assertEquals(loading, stale)

        val applied = loading.withFolderRead("\"etag-1\"", A1, FolderReadResult.Loaded(A1_ITEMS))
        assertEquals(FolderReadResult.Loaded(A1_ITEMS), applied.currentItems)
        assertEquals(emptySet(), applied.loadingFolders)
        assertNull(applied.folderToLoad())
    }

    // 読めなかった（失敗した）フォルダは、開き直すと読み直す。壊れているフォルダと、ファイルが無かったので
    // マニフェストを読み直している最中のフォルダは読み直さない
    @Test
    fun failedFolderIsReadAgainOnReopen() {
        val cases = listOf(
            FolderReadResult.Failed(SyncFailure.Unreachable("HTTP 500")) to A1,
            FolderReadResult.Broken(StorageProblem(line = null, message = "壊れている")) to null,
            FolderReadResult.Missing to null,
        )
        cases.forEach { (result, expectedToLoad) ->
            val failed = browseWithTree().openFolder(A1).withFolderRead("\"etag-1\"", A1, result)
            assertNull(failed.folderToLoad(), "$result")

            val reopened = failed.openFolder(ROOT).openFolder(A1)

            assertEquals(expectedToLoad, reopened.folderToLoad(), "$result")
        }
    }

    // ファイルが無かったあとのマニフェストの読み直しが失敗したら、そのフォルダはその失敗で読めなかったとし、開き直すと読み直す。
    // ほかのフォルダの読んだ結果は替えない
    @Test
    fun failedRereadTurnsMissingFolderIntoFailure() {
        val failure = SyncFailure.Unreachable("HTTP 500")
        val missing = browseWithTree().openFolder(A1).withFolderRead("\"etag-1\"", A1, FolderReadResult.Missing).startLoading()

        val after = missing.withTreeRead(TreeReadResult.Failed(failure))

        assertEquals(FolderReadResult.Failed(failure), after.currentItems)
        assertEquals(missing.folderReads - A1, after.folderReads - A1)
        assertEquals(A1, after.openFolder(ROOT).openFolder(A1).folderToLoad())
    }

    // ファイルが無かったあとにマニフェストを読み直せたら、そのフォルダの結果は、読み直したフォルダのツリーと一緒に読んだものに替わる
    @Test
    fun rereadReplacesMissingFolderResult() {
        val problem = StorageProblem(line = null, message = "マニフェストが指す項目ファイルが無い")
        val missing = browseWithTree().openFolder(A1).withFolderRead("\"etag-1\"", A1, FolderReadResult.Missing).startLoading()
        val reread = loadedTree(eTag = "\"etag-1\"").copy(brokenFolders = mapOf(A1 to problem))

        val after = missing.withTreeRead(TreeReadResult.Readable(reread))

        assertEquals(FolderReadResult.Broken(problem), after.currentItems)
        assertFalse(after.loading)
    }

    // 戻った先のフォルダが読めていなかったときも、読み直す
    @Test
    fun failedFolderIsReadAgainOnBack() {
        val state = browseWithTree()
            .openFolder(A1)
            .withFolderRead("\"etag-1\"", A1, FolderReadResult.Failed(SyncFailure.Forbidden))
            .openFolder(B)

        assertEquals(A1, state.back().folderToLoad())
    }

    // 接続先を替えてフォルダのツリーを捨てても、画面の幅の区分とツリーの枠を出すかは残す
    @Test
    fun clearedKeepsLayout() {
        val state = browseWithTree().withWideLayout(false).toggleTreePane().withWideLayout(true).toggleTreePane()

        val cleared = state.cleared()

        assertEquals(BrowseState(wideLayout = true, treePaneVisible = false), cleared)
    }
}
