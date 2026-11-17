package to.sava.cloudmarksmultiplatform.browse

import to.sava.cloudmarksmultiplatform.operations.FolderNode
import to.sava.cloudmarksmultiplatform.operations.TreeState
import to.sava.cloudmarksmultiplatform.storage.Item
import to.sava.cloudmarksmultiplatform.sync.FolderReadResult
import to.sava.cloudmarksmultiplatform.sync.LoadedTree
import to.sava.cloudmarksmultiplatform.sync.SyncFailure
import to.sava.cloudmarksmultiplatform.sync.TreeReadResult

/** リストで項目を選ぶときの、選び方。 */
enum class SelectionMode {
    /**
     * その項目だけを選ぶ。選んでいない項目の右クリックとドラッグ、タッチで選んでいる項目が無いときの押下、キーボードでの押下。
     * マウスのふつうのクリックは、選ばずに項目を開く。
     */
    SINGLE,

    /**
     * その項目を、選んでいなければ足し、選んでいれば外す。マウスでは Ctrl+クリック。
     * タッチでは、選んでいる項目が無いときの長押し（選ぶ状態に入る）と、選んでいる項目があるあいだの押下と長押し。
     */
    TOGGLE,

    /** 範囲の起点から押した項目までの、リストの並びで連続する項目を選ぶ。Shift+クリック。 */
    RANGE,
}

/**
 * 項目を押したときの修飾キーから決める選び方。Shift を押していれば [SelectionMode.RANGE]（Ctrl も押していても）、
 * Ctrl だけを押していれば [SelectionMode.TOGGLE]、どちらも押していなければ [SelectionMode.SINGLE]。
 */
fun selectionModeOf(ctrlPressed: Boolean, shiftPressed: Boolean): SelectionMode =
    when {
        shiftPressed -> SelectionMode.RANGE
        ctrlPressed -> SelectionMode.TOGGLE
        else -> SelectionMode.SINGLE
    }

/**
 * メインの画面の状態。読んだマニフェストのツリー、開いているフォルダとその履歴、ツリーで開いているノード、選んでいる項目、
 * ツリーの枠とドロワーの出し入れ。不変の値で、変えるたびに新しい値を作る。
 *
 * 狭い画面でだけ、ドロワーが開く（[drawerOpen] が true になるのは [wideLayout] が false のときだけ）。
 *
 * @property tree 読んだマニフェストのツリー。まだ読めていなければ null。読み直しに失敗しても、前に読めたものを残す
 * @property readOnly [tree] のマニフェストが、読むだけにするもの（`version` が知っている版より新しい）か
 * @property loading マニフェストを読んでいる最中か
 * @property failure 最後にマニフェストを読んだときの失敗。読めたか、失敗の帯を閉じたら null
 * @property currentFolderId 開いているフォルダ。[tree] が null なら null
 * @property history 戻る先のフォルダ。最後が、直前に開いていたもの
 * @property expandedFolders ツリーで開いているノード
 * @property folderReads フォルダの項目を読んだ結果。[tree] のマニフェストで読んだものだけを持つ。まだ読んでいないフォルダと、
 * 読んでいる最中のフォルダは含まない。[FolderReadResult.Missing] は、そのあとにマニフェストを読み直しているあいだだけ持つ
 * @property loadingFolders 項目を読んでいる最中のフォルダ
 * @property selectedItemIds リストで選んでいる、開いているフォルダの項目。開いているフォルダの読めた項目にあるものだけを持つ
 * @property selectionAnchorId 範囲で選ぶときの起点にする、開いているフォルダの項目。その項目だけを選んだときと、
 * 選ぶ・外すを切り替えたときに押した項目になる。無ければ null
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
    val selectedItemIds: Set<String> = emptySet(),
    val selectionAnchorId: String? = null,
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

    /** 開いているフォルダの、読めた項目。表示の並び順。読めていなければ空。 */
    val currentLoadedItems: List<Item> get() = (currentItems as? FolderReadResult.Loaded)?.items.orEmpty()

    /** 開いているフォルダの項目のうち、選んでいるもの。表示の並び順。 */
    val selectedItems: List<Item> get() = currentLoadedItems.filter { it.id in selectedItemIds }

    /** 戻る操作でできることがあるか。ドロワーが開いているか、履歴があるとき。 */
    val canGoBack: Boolean get() = drawerOpen || history.isNotEmpty()

    /** マニフェストを読み始める。 */
    fun startLoading(): BrowseState = copy(loading = true)

    /**
     * マニフェストを読んだときの失敗の帯を閉じる。失敗を、次に読むまで持たない。
     * ツリーがまだ読めていなければ、失敗は帯ではなくリストに出すので、何もしない。
     */
    fun dismissFailure(): BrowseState = if (tree == null) this else copy(failure = null)

    /** マニフェストのツリーと、それに係る状態を捨てる。画面の幅の区分と、ツリーの枠を出すかだけを残す。接続先を替えたときに使う。 */
    fun cleared(): BrowseState = BrowseState(wideLayout = wideLayout, treePaneVisible = treePaneVisible)

    /** 新しく読んだツリー [tree] で開くフォルダ。開いているフォルダが [tree] に残っていればそれ、無ければルートフォルダ。 */
    fun folderToOpenIn(tree: TreeState): String = currentFolderId?.takeIf { it in tree.folders } ?: tree.rootId

    /**
     * マニフェストを読んだ結果を受け取る。
     *
     * 読めたらマニフェストのツリーを替える。開いていたフォルダが新しいツリーに残っていればそのまま開き、消えていればルートフォルダを開く。
     * 履歴とツリーで開いているノードからは、新しいツリーに無いフォルダを除く。初めてツリーを読んだときは、ルートフォルダだけを開く。
     * 選んでいた項目は、開いているフォルダが替わらなければ、新しいツリーと一緒に読んだそのフォルダの項目にまだあるものだけを残す。
     * 範囲で選ぶときの起点も、同じくまだあれば残す。
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
     * 操作を書けたときの、書いたあとのツリー [written] を受け取る。読み書きできるツリーとして替える。
     *
     * [written] が持つ項目は、操作の適用に要ったフォルダのものだけなので、[withTreeRead] と違い、いまのツリーで読めた項目を引き継ぐ。
     * 引き継ぐのは、[written] でも項目ファイルの ID が同じフォルダのもの（項目ファイルは置くたびに新しい ID なので、
     * ID が同じなら中身も同じ）。[written] が持つ項目は、引き継いだものより優先する。
     * 選んでいた項目と範囲で選ぶときの起点は、引き継いだ項目にまだあるものだけを残す。
     */
    fun withWrittenTree(written: LoadedTree): BrowseState {
        val writtenFolders = written.state.folders
        val kept = folderReads.filter { (folderId, read) ->
            read is FolderReadResult.Loaded &&
                writtenFolders[folderId]?.let { it.itemsFileId == tree?.state?.folders?.get(folderId)?.itemsFileId } == true
        }
        return withTree(written, readOnly = false, keptReads = kept)
    }

    /**
     * フォルダ [folderId] を開く。開いていたフォルダを履歴に積み、ドロワーを閉じる。ツリーでは、そのフォルダ自身と祖先を開く。
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
    fun toggleExpanded(folderId: String): BrowseState = copy(expandedFolders = expandedFolders.withToggled(folderId))

    /**
     * 開いているフォルダの項目 [itemId] を、[mode] の選び方で選ぶ。開いているフォルダの読めた項目に無ければ何もしない。
     *
     * [SelectionMode.SINGLE] と [SelectionMode.TOGGLE] は、範囲で選ぶときの起点を [itemId] にする。
     * [SelectionMode.RANGE] は起点を替えない。起点が無いか、開いているフォルダの読めた項目に無ければ、[SelectionMode.SINGLE] と同じにする。
     */
    fun selectItem(itemId: String, mode: SelectionMode): BrowseState {
        val ids = currentLoadedItems.map { it.id }
        val index = ids.indexOf(itemId)
        if (index < 0) return this
        val anchorIndex = selectionAnchorId?.let { ids.indexOf(it) } ?: -1
        return when {
            mode == SelectionMode.TOGGLE -> copy(
                selectedItemIds = if (itemId in selectedItemIds) selectedItemIds - itemId else selectedItemIds + itemId,
                selectionAnchorId = itemId,
            )
            mode == SelectionMode.RANGE && anchorIndex >= 0 ->
                copy(selectedItemIds = ids.slice(minOf(anchorIndex, index)..maxOf(anchorIndex, index)).toSet())
            else -> copy(selectedItemIds = setOf(itemId), selectionAnchorId = itemId)
        }
    }

    /** 選んでいる項目と、範囲で選ぶときの起点を外す。 */
    fun clearSelection(): BrowseState = copy(selectedItemIds = emptySet(), selectionAnchorId = null)

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

    /** [keptReads] は、[loaded] と一緒に読んだ項目に足して持つ、前のツリーから引き継いだ項目の読み。同じフォルダなら [loaded] のものが優先する。 */
    private fun withTree(loaded: LoadedTree, readOnly: Boolean, keptReads: Map<String, FolderReadResult> = emptyMap()): BrowseState {
        val folders = loaded.state.folders
        val current = folderToOpenIn(loaded.state)
        val reads = keptReads + loaded.state.items.mapValues { (_, items) -> FolderReadResult.Loaded(items) } +
            loaded.brokenFolders.mapValues { (_, problem) -> FolderReadResult.Broken(problem) }
        val remainingIds = if (current == currentFolderId) {
            (reads[current] as? FolderReadResult.Loaded)?.items.orEmpty().map { it.id }.toSet()
        } else {
            emptySet()
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
            selectedItemIds = selectedItemIds intersect remainingIds,
            selectionAnchorId = selectionAnchorId?.takeIf { it in remainingIds },
        )
    }

    /**
     * [folderId] を開いているフォルダにし、履歴を [newHistory] にする。選んでいた項目と範囲で選ぶときの起点を外し、
     * ツリーでそのフォルダ自身と祖先を開く。前に読めなかった（失敗した）フォルダは、読んだ結果を除いて読み直せるようにする。
     */
    private fun showFolder(folderId: String, newHistory: List<String>): BrowseState {
        val ancestors = tree?.state?.ancestorsOf(folderId).orEmpty()
        val retry = folderReads[folderId] is FolderReadResult.Failed
        return copy(
            currentFolderId = folderId,
            history = newHistory,
            selectedItemIds = emptySet(),
            selectionAnchorId = null,
            expandedFolders = expandedFolders + ancestors + folderId,
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
