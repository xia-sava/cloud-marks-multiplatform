package to.sava.cloudmarksmultiplatform.browse

import to.sava.cloudmarksmultiplatform.app.AppState
import to.sava.cloudmarksmultiplatform.app.Screen
import to.sava.cloudmarksmultiplatform.app.SettingsScreenState
import to.sava.cloudmarksmultiplatform.storage.Item
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

/** 範囲で選ぶ場合を確かめるための、5 つの項目。 */
private val FIVE_ITEMS = (1..5).map { item(it) }

/** 初めてツリーを読んだあとに A1 を開き、その項目として [items] を読んだ状態。 */
private fun browseWithA1Items(items: List<Item>): BrowseState =
    browseWithTree().openFolder(A1).withFolderRead("\"etag-1\"", A1, FolderReadResult.Loaded(items))

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

    // フォルダを開くと、ツリーでそのフォルダ自身と祖先を開き、選んでいた項目と範囲の起点を外す
    @Test
    fun openingFolderExpandsItselfAndAncestorsAndClearsSelection() {
        val selected = browseWithA1Items(A1_ITEMS).selectItem(A1_ITEMS.first().id, SelectionMode.SINGLE)

        val state = selected.openFolder(A1A)

        assertEquals(setOf(ROOT, A, A1, A1A), state.expandedFolders)
        assertEquals(emptySet(), state.selectedItemIds)
        assertNull(state.selectionAnchorId)
    }

    // 子のフォルダのあるフォルダを開くと、ツリーでそのフォルダの子の行が出る。閉じてから戻る操作で開き直しても、また開く
    @Test
    fun openedFolderShowsItsChildrenInTreeOnEveryOpening() {
        val opened = browseWithTree().openFolder(A)
        val rows = flattenFolderTree(checkNotNull(opened.tree).state, opened.expandedFolders)
        assertEquals(listOf(ROOT, A, A1, A2, B), rows.map { it.folderId })

        val reopened = opened.openFolder(B).toggleExpanded(A).back()

        assertEquals(A, reopened.currentFolderId)
        assertTrue(A in reopened.expandedFolders)
    }

    // 選び方ごとの、選んでいる項目と範囲の起点。ふつうのクリックはその項目だけを選び、トグルは足す・外す、
    // 範囲は起点から押した項目までのリストの並びで連続する項目を選ぶ。ふつうのクリックとトグルは起点を押した項目にし、範囲は起点を替えない
    @Test
    fun selectionModes() {
        data class SelectionCase(
            val name: String,
            val clicks: List<Pair<Int, SelectionMode>>,
            val selected: Set<Int>,
            val anchor: Int,
        )
        val cases = listOf(
            SelectionCase("ふつうのクリックは、前の選択を外す", listOf(2 to SelectionMode.SINGLE, 4 to SelectionMode.SINGLE), setOf(4), 4),
            SelectionCase("トグルで足す", listOf(2 to SelectionMode.SINGLE, 4 to SelectionMode.TOGGLE), setOf(2, 4), 4),
            SelectionCase(
                "トグルで外す",
                listOf(2 to SelectionMode.SINGLE, 4 to SelectionMode.TOGGLE, 2 to SelectionMode.TOGGLE),
                setOf(4),
                2,
            ),
            SelectionCase("範囲を後ろへ", listOf(2 to SelectionMode.SINGLE, 4 to SelectionMode.RANGE), setOf(2, 3, 4), 2),
            SelectionCase(
                "範囲を続けて押すと、同じ起点からの範囲に替わる",
                listOf(3 to SelectionMode.SINGLE, 5 to SelectionMode.RANGE, 1 to SelectionMode.RANGE),
                setOf(1, 2, 3),
                3,
            ),
            SelectionCase(
                "トグルで押した項目が範囲の起点になる",
                listOf(1 to SelectionMode.SINGLE, 5 to SelectionMode.TOGGLE, 3 to SelectionMode.RANGE),
                setOf(3, 4, 5),
                5,
            ),
            SelectionCase("起点が無ければ、範囲はその項目だけを選ぶ", listOf(3 to SelectionMode.RANGE), setOf(3), 3),
        )
        cases.forEach { (name, clicks, selected, anchor) ->
            val state = clicks.fold(browseWithA1Items(FIVE_ITEMS)) { state, (n, mode) -> state.selectItem(item(n).id, mode) }

            assertEquals(selected.map { item(it).id }.toSet(), state.selectedItemIds, name)
            assertEquals(item(anchor).id, state.selectionAnchorId, name)
            assertEquals(FIVE_ITEMS.filter { it.id in state.selectedItemIds }, state.selectedItems, name)
        }
    }

    // 押したときの修飾キーから選び方を決める。Shift を押していれば Ctrl も押していても範囲、Ctrl だけならトグル、どちらも無ければその項目だけ
    @Test
    fun selectionModeFollowsModifierKeys() {
        val cases = listOf(
            Triple(false, false, SelectionMode.SINGLE),
            Triple(true, false, SelectionMode.TOGGLE),
            Triple(false, true, SelectionMode.RANGE),
            Triple(true, true, SelectionMode.RANGE),
        )
        cases.forEach { (ctrl, shift, expected) ->
            assertEquals(expected, selectionModeOf(ctrlPressed = ctrl, shiftPressed = shift), "Ctrl: $ctrl, Shift: $shift")
        }
    }

    // 開いているフォルダの読めた項目に無い項目は、どの選び方でも選ばない（リストに無い項目、項目をまだ読めていないフォルダ）
    @Test
    fun itemOutsideCurrentListIsNotSelected() {
        val cases = listOf(
            "リストに無い項目" to browseWithA1Items(A1_ITEMS).selectItem(A1_ITEMS.first().id, SelectionMode.SINGLE),
            "項目をまだ読めていないフォルダ" to browseWithTree().openFolder(A1),
        )
        cases.forEach { (name, before) ->
            SelectionMode.entries.forEach { mode ->
                assertEquals(before, before.selectItem(item(9).id, mode), "$name $mode")
            }
        }
    }

    // 選択を空にすると、選んでいる項目と範囲の起点を外す
    @Test
    fun clearSelectionRemovesSelectionAndAnchor() {
        val selected = browseWithA1Items(FIVE_ITEMS)
            .selectItem(item(2).id, SelectionMode.SINGLE)
            .selectItem(item(4).id, SelectionMode.RANGE)

        val cleared = selected.clearSelection()

        assertEquals(emptySet(), cleared.selectedItemIds)
        assertNull(cleared.selectionAnchorId)
        assertEquals(emptyList(), cleared.selectedItems)
    }

    // ツリーで祖先とそのフォルダ自身を閉じたあとでも、戻って開いたフォルダの祖先とそのフォルダ自身は、ツリーで開いた状態になる
    @Test
    fun backExpandsReturnedFolderAndItsAncestors() {
        val collapsed = browseWithTree().openFolder(A1A).openFolder(B)
            .toggleExpanded(A).toggleExpanded(A1).toggleExpanded(A1A)
        assertEquals(setOf(ROOT, B), collapsed.expandedFolders)

        val back = collapsed.back()

        assertEquals(A1A, back.currentFolderId)
        assertEquals(setOf(ROOT, A, A1, A1A, B), back.expandedFolders)
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

    // 読み直したあと、開いていたフォルダが残っていれば開いたままにし、選んでいた項目と範囲の起点もまだあれば残す
    @Test
    fun reloadKeepsOpenFolderAndSelection() {
        val selected = A1_ITEMS.map { it.id }.toSet()
        val before = browseWithA1Items(A1_ITEMS)
            .selectItem(A1_ITEMS[0].id, SelectionMode.SINGLE)
            .selectItem(A1_ITEMS[1].id, SelectionMode.TOGGLE)
        val reloaded = loadedTree(eTag = "\"etag-2\"", loadedItems = mapOf(A1 to A1_ITEMS))

        val after = before.startLoading().withTreeRead(TreeReadResult.Readable(reloaded))

        assertEquals(A1, after.currentFolderId)
        assertEquals(selected, after.selectedItemIds)
        assertEquals(A1_ITEMS[1].id, after.selectionAnchorId)
        assertEquals(listOf(ROOT), after.history)
        assertEquals(FolderReadResult.Loaded(A1_ITEMS), after.currentItems)
        assertFalse(after.loading)
    }

    // 読み直したあと、選んでいた項目のうち無くなったものを外し、まだあるものは残す。範囲の起点が無くなっていれば、起点も外す
    @Test
    fun reloadKeepsOnlyRemainingSelection() {
        val before = browseWithA1Items(A1_ITEMS)
            .selectItem(A1_ITEMS[0].id, SelectionMode.SINGLE)
            .selectItem(A1_ITEMS[1].id, SelectionMode.TOGGLE)
        val reloaded = loadedTree(eTag = "\"etag-2\"", loadedItems = mapOf(A1 to listOf(A1_ITEMS[0], item(9))))

        val after = before.withTreeRead(TreeReadResult.Readable(reloaded))

        assertEquals(A1, after.currentFolderId)
        assertEquals(setOf(A1_ITEMS[0].id), after.selectedItemIds)
        assertNull(after.selectionAnchorId)
    }

    // 書けたあとのツリーが開いているフォルダの項目を持たなくても、項目ファイルの ID が同じなら、前に読んだ項目と選択を引き継ぐ
    @Test
    fun writtenTreeKeepsReadsOfFoldersWithSameItemsFile() {
        val before = browseWithA1Items(A1_ITEMS)
            .selectItem(A1_ITEMS[0].id, SelectionMode.SINGLE)
            .selectItem(A1_ITEMS[1].id, SelectionMode.TOGGLE)
        val written = loadedTree(eTag = "\"etag-2\"")

        val after = before.withWrittenTree(written)

        assertSame(written, after.tree)
        assertEquals(A1, after.currentFolderId)
        assertEquals(FolderReadResult.Loaded(A1_ITEMS), after.currentItems)
        assertNull(after.folderToLoad())
        assertEquals(A1_ITEMS.map { it.id }.toSet(), after.selectedItemIds)
        assertEquals(A1_ITEMS[1].id, after.selectionAnchorId)
        assertFalse(after.readOnly)
    }

    // 書けたあとのツリーで項目ファイルの ID が替わったフォルダは、前に読んだ項目を引き継がず、書いたあとのツリーが持つ項目にする。
    // 持たなければ読み直す。選択は、書いたあとの項目にまだあるものだけを残す
    @Test
    fun writtenTreeDropsReadsOfFoldersWithNewItemsFile() {
        val before = browseWithA1Items(A1_ITEMS)
            .selectItem(A1_ITEMS[0].id, SelectionMode.SINGLE)
            .selectItem(A1_ITEMS[1].id, SelectionMode.TOGGLE)
        val replacedEntries = treeEntries().map { if (it.id == A1) it.copy(items = fileId(50)) else it }

        val withItems = before.withWrittenTree(
            loadedTree(replacedEntries, eTag = "\"etag-2\"", loadedItems = mapOf(A1 to listOf(A1_ITEMS[0]))),
        )
        val withoutItems = before.withWrittenTree(loadedTree(replacedEntries, eTag = "\"etag-2\""))

        assertEquals(FolderReadResult.Loaded(listOf(A1_ITEMS[0])), withItems.currentItems)
        assertEquals(setOf(A1_ITEMS[0].id), withItems.selectedItemIds)
        assertNull(withItems.selectionAnchorId)
        assertNull(withoutItems.currentItems)
        assertEquals(A1, withoutItems.folderToLoad())
        assertEquals(emptySet(), withoutItems.selectedItemIds)
    }

    // 読み直したあと、開いていたフォルダが消えていればルートフォルダを開く。ツリーで開いているノードから、消えたフォルダを除く
    @Test
    fun reloadFallsBackToRootWhenOpenFolderIsGone() {
        val before = browseWithTree().openFolder(A1A)
        assertEquals(setOf(ROOT, A, A1, A1A), before.expandedFolders)
        val withoutA1 = loadedTree(treeEntries(without = setOf(A1)), eTag = "\"etag-2\"")

        val after = before.withTreeRead(TreeReadResult.Readable(withoutA1))

        assertEquals(ROOT, after.currentFolderId)
        assertEquals(emptySet(), after.selectedItemIds)
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

    // 失敗の帯を閉じると、失敗を持たなくなり、ツリーと開いているフォルダは残す。
    // ツリーをまだ読めていなければ、失敗は帯ではなくリストに出すので、閉じても残す
    @Test
    fun dismissFailureClearsFailureOnlyWithTree() {
        val failed = TreeReadResult.Failed(SyncFailure.Forbidden)
        val withTree = browseWithTree().openFolder(A).startLoading().withTreeRead(failed)

        val dismissed = withTree.dismissFailure()

        assertNull(dismissed.failure)
        assertSame(withTree.tree, dismissed.tree)
        assertEquals(A, dismissed.currentFolderId)
        val withoutTree = BrowseState().startLoading().withTreeRead(failed)
        assertEquals(SyncFailure.Forbidden, withoutTree.dismissFailure().failure)
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
