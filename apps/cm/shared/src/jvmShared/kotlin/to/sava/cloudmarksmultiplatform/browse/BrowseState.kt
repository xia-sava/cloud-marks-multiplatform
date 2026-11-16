package to.sava.cloudmarksmultiplatform.browse

import to.sava.cloudmarksmultiplatform.operations.FolderNode
import to.sava.cloudmarksmultiplatform.operations.TreeState
import to.sava.cloudmarksmultiplatform.sync.FolderReadResult
import to.sava.cloudmarksmultiplatform.sync.LoadedTree
import to.sava.cloudmarksmultiplatform.sync.SyncFailure
import to.sava.cloudmarksmultiplatform.sync.TreeReadResult

/**
 * メインの画面の状態。読んだマニフェストのツリー、開いているフォルダとその履歴、ツリーで開いているノード、選んでいる項目、
 * ツリーの枠とドロワーの出し入れ。不変の値で、変えるたびに新しい値を作る。
 *
 * 狭い画面でだけ、ドロワーが開く（[drawerOpen] が true になるのは [wideLayout] が false のときだけ）。
 *
 * @property tree 読んだマニフェストのツリー。まだ読めていなければ null。読み直しに失敗しても、前に読めたものを残す
 * @property readOnly [tree] のマニフェストが、読むだけにするもの（`version` が知っている版より新しい）か
 * @property loading マニフェストを読んでいる最中か
 * @property failure 最後にマニフェストを読んだときの失敗。読めたら null
 * @property currentFolderId 開いているフォルダ。[tree] が null なら null
 * @property history 戻る先のフォルダ。最後が、直前に開いていたもの
 * @property expandedFolders ツリーで開いているノード
 * @property folderReads フォルダの項目を読んだ結果。[tree] のマニフェストで読んだものだけを持つ。まだ読んでいないフォルダと、
 * 読んでいる最中のフォルダは含まない。[FolderReadResult.Missing] は、そのあとにマニフェストを読み直しているあいだだけ持つ
 * @property loadingFolders 項目を読んでいる最中のフォルダ
 * @property selectedItemId リストで選んでいる、開いているフォルダの項目
 * @property wideLayout 広い画面か。広ければツリーの枠を出し、狭ければツリーをドロワーにする
 * @property treePaneVisible 広い画面で、ツリーの枠を出すか
 * @property drawerOpen 狭い画面で、ドロワーが開いているか
 */
data class BrowseState(
    val tree: LoadedTree? = null,
    val readOnly: Boolean = false,
    val loading: Boolean = false,
    val failure: SyncFailure? = null,
    val currentFolderId: String? = null,
    val history: List<String> = emptyList(),
    val expandedFolders: Set<String> = emptySet(),
    val folderReads: Map<String, FolderReadResult> = emptyMap(),
    val loadingFolders: Set<String> = emptySet(),
    val selectedItemId: String? = null,
    val wideLayout: Boolean = true,
    val treePaneVisible: Boolean = true,
    val drawerOpen: Boolean = false,
) {
    /** 開いているフォルダ。 */
    val currentFolder: FolderNode? get() = currentFolderId?.let { tree?.state?.folders?.get(it) }

    /** 開いているフォルダの子のフォルダ。マニフェストの行の順。 */
    val currentChildren: List<FolderNode>
        get() = currentFolder?.children.orEmpty().mapNotNull { tree?.state?.folders?.get(it) }

    /** 開いているフォルダの項目を読んだ結果。まだ読んでいなければ（読んでいる最中も）null。 */
    val currentItems: FolderReadResult? get() = currentFolderId?.let { folderReads[it] }

    /** 戻る操作でできることがあるか。ドロワーが開いているか、履歴があるとき。 */
    val canGoBack: Boolean get() = drawerOpen || history.isNotEmpty()

    /** マニフェストを読み始める。 */
    fun startLoading(): BrowseState = copy(loading = true)

    /** マニフェストのツリーと、それに係る状態を捨てる。画面の幅の区分と、ツリーの枠を出すかだけを残す。接続先を替えたときに使う。 */
    fun cleared(): BrowseState = BrowseState(wideLayout = wideLayout, treePaneVisible = treePaneVisible)

    /** 新しく読んだツリー [tree] で開くフォルダ。開いているフォルダが [tree] に残っていればそれ、無ければルートフォルダ。 */
    fun folderToOpenIn(tree: TreeState): String = currentFolderId?.takeIf { it in tree.folders } ?: tree.rootId

    /**
     * マニフェストを読んだ結果を受け取る。
     *
     * 読めたらマニフェストのツリーを替える。開いていたフォルダが新しいツリーに残っていればそのまま開き、消えていればルートフォルダを開く。
     * 履歴とツリーで開いているノードからは、新しいツリーに無いフォルダを除く。初めてツリーを読んだときは、ルートフォルダだけを開く。
     * 選んでいた項目は、開いているフォルダが替わらず、その項目がまだあれば残す。
     * 項目を読んだ結果は、新しいツリーと一緒に読んだものだけにする。
     *
     * 読めなければ、前に読めたツリーを残したまま、失敗を持つ。項目ファイルが無かった（[FolderReadResult.Missing]）
     * フォルダは、その失敗で読めなかったとする（開き直すと読み直す）。
     */
    fun withTreeRead(result: TreeReadResult): BrowseState =
        when (result) {
            is TreeReadResult.Readable -> withTree(result.tree, readOnly = false)
            is TreeReadResult.ReadOnly -> withTree(result.tree, readOnly = true)
            is TreeReadResult.Failed -> copy(
                loading = false,
                failure = result.failure,
                folderReads = folderReads.mapValues { (_, read) ->
                    if (read is FolderReadResult.Missing) FolderReadResult.Failed(result.failure) else read
                },
            )
        }

    /**
     * フォルダ [folderId] を開く。開いていたフォルダを履歴に積み、ドロワーを閉じる。
     *
     * 開いているフォルダと同じなら、履歴に積まずにドロワーだけを閉じる。ツリーに無いフォルダは開かない。
     */
    fun openFolder(folderId: String): BrowseState {
        val folders = tree?.state?.folders ?: return this
        if (folderId !in folders) return this
        if (folderId == currentFolderId) return copy(drawerOpen = false)
        return showFolder(folderId, history + listOfNotNull(currentFolderId)).copy(drawerOpen = false)
    }

    /**
     * 戻る。ドロワーが開いていれば閉じるだけにする。そうでなければ、履歴の最後のフォルダを開き、履歴から除く。
     * どちらでもなければ何もしない。親のフォルダへは上がらない。
     */
    fun back(): BrowseState =
        when {
            drawerOpen -> copy(drawerOpen = false)
            history.isNotEmpty() -> showFolder(history.last(), history.dropLast(1))
            else -> this
        }

    /** ツリーのノード [folderId] を開く、または閉じる。 */
    fun toggleExpanded(folderId: String): BrowseState =
        copy(expandedFolders = if (folderId in expandedFolders) expandedFolders - folderId else expandedFolders + folderId)

    /** 開いているフォルダの項目 [itemId] を選ぶ。 */
    fun selectItem(itemId: String): BrowseState = copy(selectedItemId = itemId)

    /** ツリーを出し入れする。広い画面ではツリーの枠を、狭い画面ではドロワーを。 */
    fun toggleTreePane(): BrowseState =
        if (wideLayout) copy(treePaneVisible = !treePaneVisible) else copy(drawerOpen = !drawerOpen)

    /** ドロワーを閉じる。 */
    fun closeDrawer(): BrowseState = copy(drawerOpen = false)

    /** 画面の幅の区分を受け取る。区分が替わったら、ドロワーを閉じる。 */
    fun withWideLayout(wide: Boolean): BrowseState =
        if (wide == wideLayout) this else copy(wideLayout = wide, drawerOpen = false)

    /** 開いているフォルダの項目を読む必要があれば、その ID。読んだ結果があるか、読んでいる最中なら null。 */
    fun folderToLoad(): String? =
        currentFolderId?.takeIf { tree != null && it !in folderReads && it !in loadingFolders }

    /** フォルダ [folderId] の項目を読み始める。 */
    fun withFolderLoading(folderId: String): BrowseState = copy(loadingFolders = loadingFolders + folderId)

    /**
     * ETag が [eTag] のマニフェストのツリーで、フォルダ [folderId] の項目を読んだ結果を受け取る。
     * いまのツリーのマニフェストの ETag と違えば、前のツリーで読んだものなので捨てる。
     * [FolderReadResult.Missing] を渡したら、呼び出し側は続けてマニフェストを読み直し、その結果を [withTreeRead] に渡す。
     */
    fun withFolderRead(eTag: String, folderId: String, result: FolderReadResult): BrowseState =
        if (tree?.eTag != eTag) {
            this
        } else {
            copy(folderReads = folderReads + (folderId to result), loadingFolders = loadingFolders - folderId)
        }

    private fun withTree(loaded: LoadedTree, readOnly: Boolean): BrowseState {
        val folders = loaded.state.folders
        val current = folderToOpenIn(loaded.state)
        val reads = loaded.state.items.mapValues { (_, items) -> FolderReadResult.Loaded(items) } +
            loaded.brokenFolders.mapValues { (_, problem) -> FolderReadResult.Broken(problem) }
        val keptSelection = selectedItemId?.takeIf { selected ->
            current == currentFolderId &&
                (reads[current] as? FolderReadResult.Loaded)?.items.orEmpty().any { it.id == selected }
        }
        return copy(
            tree = loaded,
            readOnly = readOnly,
            loading = false,
            failure = null,
            currentFolderId = current,
            history = history.filter { it in folders }.withoutRepeats(current),
            expandedFolders = if (tree == null) setOf(loaded.state.rootId) else expandedFolders.filter { it in folders }.toSet(),
            folderReads = reads,
            loadingFolders = emptySet(),
            selectedItemId = keptSelection,
        )
    }

    /**
     * [folderId] を開いているフォルダにし、履歴を [newHistory] にする。選んでいた項目を外し、ツリーでその祖先を開く。
     * 前に読めなかった（失敗した）フォルダは、読んだ結果を除いて読み直せるようにする。
     */
    private fun showFolder(folderId: String, newHistory: List<String>): BrowseState {
        val ancestors = tree?.state?.ancestorsOf(folderId).orEmpty()
        val retry = folderReads[folderId] is FolderReadResult.Failed
        return copy(
            currentFolderId = folderId,
            history = newHistory,
            selectedItemId = null,
            expandedFolders = expandedFolders + ancestors,
            folderReads = if (retry) folderReads - folderId else folderReads,
        )
    }
}

/** 続けて同じフォルダが並ぶところを 1 つにし、末尾の、開いているフォルダ [current] と同じものを除く。 */
private fun List<String>.withoutRepeats(current: String): List<String> =
    buildList {
        for (folderId in this@withoutRepeats) {
            if (lastOrNull() != folderId) add(folderId)
        }
    }.dropLastWhile { it == current }
