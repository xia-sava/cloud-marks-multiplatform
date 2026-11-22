package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.sava.cloudmarksmultiplatform.browse.BrowseState
import to.sava.cloudmarksmultiplatform.browse.DropRow
import to.sava.cloudmarksmultiplatform.browse.FolderTreeRow
import to.sava.cloudmarksmultiplatform.browse.MoveSubject
import to.sava.cloudmarksmultiplatform.browse.flattenFolderTree
import to.sava.cloudmarksmultiplatform.browse.treeDrop
import to.sava.cloudmarksmultiplatform.browse.treeEndDrop

/** ツリーの 1 行の高さ。 */
private val TREE_ROW_HEIGHT = 36.dp

/** 深さ 1 つあたりの字下げ。 */
private val TREE_INDENT = 16.dp

/** 開閉ボタンの大きさ。ボタンの無い行も、同じ幅を空ける。 */
private val TREE_TOGGLE_SIZE = 32.dp

/** フォルダのアイコンの大きさ。 */
private val TREE_ICON_SIZE = 20.dp

private val TREE_ICON_GAP = 8.dp
private val TREE_ROW_END_PADDING = 8.dp

/** 押せない行の、アイコンと文字の不透明度。Material Design の、無効にした要素の中身の不透明度。 */
private const val DISABLED_CONTENT_ALPHA = 0.38f

/**
 * メインの画面の、フォルダのツリー。マニフェストだけで描き、開いているノードの子だけを平らにして並べる。
 * ルートフォルダは最上段に、家のアイコンと「トップ」で出す。開いているフォルダの行を強調し、開いているフォルダが替わったら、
 * その行が見える位置までスクロールする。
 *
 * 行を右クリックするとフォルダのメニュー（[FolderContextMenu]）を出す。タッチの画面（[LocalTouchScreen]）では、行を長押ししても出す。
 * [dragsToMove] のプラットフォームでは、ルートフォルダ以外の行を
 * ドラッグして、別の行へ落とせる（[treeDrop]）。行の上の端に落とすとその行の前へ、下の端に落とすとその行の後ろへ（開いていて子のある
 * 行なら、その先頭の子の前へ）並べ替え、真ん中に落とすと、その行のフォルダの子のフォルダの末尾へ移す。リストの項目とフォルダを
 * ドラッグしてきたときも同じ（項目は行のどこでも中へ）。最後の行より下の空いたところに落とすと、フォルダはルートフォルダの子の末尾へ、
 * 項目はルートフォルダの中へ移す（[treeEndDrop]）。
 * ドラッグしているあいだは、中へ移すならその行を強調し、並べ替えるならその行の端に、挿入先の深さで字下げした線を引く。
 * ポインターがツリーの上下の端にあれば、その向きへスクロールし続ける（[moveDropTargets]）。
 *
 * @param listState スクロール位置
 * @param actions データを変える操作の入口
 * @param browserActions 項目をブラウザで開く操作の入口
 * @param onOpen 行を押したときに、そのフォルダを開く
 * @param onToggle 開閉ボタンを押したときに、そのノードを開く、または閉じる
 */
@Composable
internal fun FolderTreePane(
    browse: BrowseState,
    listState: LazyListState,
    actions: OperationActions,
    browserActions: BrowserActions,
    onOpen: (String) -> Unit,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tree = browse.tree?.state
    if (tree == null) {
        Box(modifier)
        return
    }
    val rows = remember(tree, browse.expandedFolders) { flattenFolderTree(tree, browse.expandedFolders) }
    ScrollToCurrentFolder(rows, browse.currentFolderId, listState)
    val dropTargets = modifier.moveDropTargets(
        actions.dragState,
        listState,
        dropBelowRows = { subject -> browse.treeEndDrop(rows, subject) },
    ) { hit, subject ->
        (hit.key as? String)?.let { folderId -> browse.treeDrop(rows, folderId, hit.y, hit.height, subject) }
    }
    FolderTree(rows, listState, dropTargets) { row ->
        OperableFolderTreeRow(
            row = row,
            current = row.folderId == browse.currentFolderId,
            itemCount = browse.itemCountOf(row.folderId),
            actions = actions,
            browserActions = browserActions,
            onOpen = { onOpen(row.folderId) },
            onToggle = { onToggle(row.folderId) },
        )
    }
}

/**
 * フォルダのツリーの行 [rows]（[flattenFolderTree] の並び）を、上から並べる。行の中身は [rowContent] で描く。
 * 右端に縦のスクロールバー（[ListVerticalScrollbar]）を出す。
 * メインの画面のツリーと、移動先を選ぶダイアログのツリーが使う。
 */
@Composable
internal fun FolderTree(
    rows: List<FolderTreeRow>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    rowContent: @Composable (FolderTreeRow) -> Unit,
) {
    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(rows, key = { it.folderId }) { row -> rowContent(row) }
        }
        ListVerticalScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/**
 * 開いているフォルダが替わったら、そのフォルダの行が全部見える位置までツリーをスクロールする。
 * 行が既に全部見えていれば動かさない。
 *
 * 戻る操作やリストの子のフォルダを押す操作で開いているフォルダが替わると、祖先を開いた新しい行の並びで描き直す。
 * そのレイアウトが済んでから、行の位置を調べる。
 */
@Composable
private fun ScrollToCurrentFolder(rows: List<FolderTreeRow>, currentFolderId: String?, listState: LazyListState) {
    LaunchedEffect(currentFolderId) {
        val index = rows.indexOfFirst { it.folderId == currentFolderId }
        if (index < 0) return@LaunchedEffect
        withFrameNanos { }
        if (!listState.isFullyVisible(currentFolderId)) listState.scrollToItem(index)
    }
}

/** キーが [key] の行が、表示の範囲に全部収まっているか。ツリーとリストが、行が見える位置までスクロールするときに使う。 */
internal fun LazyListState.isFullyVisible(key: String?): Boolean {
    val info = layoutInfo
    val row = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return false
    return row.offset >= info.viewportStartOffset && row.offset + row.size <= info.viewportEndOffset
}

/**
 * メインの画面のツリーの行。右クリック（タッチの画面では長押しでも）のメニューと、ドラッグして移す元を付ける。
 * ドラッグして移すものを、この行のフォルダの中へ移すなら
 * 強調し、この行の端が挿入する位置なら、その端に、挿入先の深さの行と同じだけ字下げした線を引く。
 *
 * @param current 開いているフォルダの行か
 * @param itemCount 行のフォルダの直下の項目の数（溜めた削除の項目は数えない。[BrowseState.itemCountOf]）
 */
@Composable
private fun OperableFolderTreeRow(
    row: FolderTreeRow,
    current: Boolean,
    itemCount: Int,
    actions: OperationActions,
    browserActions: BrowserActions,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    val drag = actions.dragState
    val menu = rememberContextMenuState()
    val touchScreen = LocalTouchScreen.current
    val mark = drag.drop?.takeIf { actions.canWrite && it.row == DropRow.TreeFolder(row.folderId) }
    val dragSource = if (row.isRoot) {
        Modifier
    } else {
        Modifier.moveDragSource(
            state = drag,
            canWrite = actions.canWrite,
            onDragStart = { MoveSubject.Folder(row.folderId) },
        )
    }
    Box(Modifier.dropLine(mark?.line, depthIndent = TREE_INDENT)) {
        FolderTreeRowView(
            row = row,
            highlighted = current,
            dropTarget = mark != null && mark.line == null,
            onOpen = onOpen,
            onToggle = onToggle,
            onLongClick = folderMenuOnLongPress(touchScreen, menu, browserActions.onMenuOpen),
            modifier = Modifier.folderMenuPress(menu, browserActions.onMenuOpen).then(dragSource),
        )
        FolderContextMenu(
            state = menu,
            folderId = row.folderId,
            name = row.name,
            isRoot = row.isRoot,
            itemCount = itemCount,
            actions = actions,
            browserActions = browserActions,
        )
    }
}

/**
 * ツリーの 1 行。字下げ、開閉ボタン、フォルダのアイコン、名前を並べる。開閉ボタンは、ボタンの説明をツールチップに出す。
 *
 * @param highlighted 強調するか。メインの画面では開いているフォルダ、移動先を選ぶダイアログでは選んだ移動先
 * @param onOpen 行を押したとき
 * @param onToggle 開閉ボタンを押したとき
 * @param modifier 行に付ける、押す操作の扱い
 * @param enabled 行を押せるか。偽なら、アイコンと名前を薄くする。開閉ボタンは押せる
 * @param dropTarget ドラッグして移すものを、この行のフォルダの中へ移すか。そうなら、[highlighted] と違う色で強調する
 * @param onLongClick 行を長押ししたとき。null なら長押しを扱わない
 */
@Composable
internal fun FolderTreeRowView(
    row: FolderTreeRow,
    highlighted: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    dropTarget: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val background = when {
        dropTarget -> MaterialTheme.colorScheme.primaryContainer
        highlighted -> MaterialTheme.colorScheme.secondaryContainer
        else -> Color.Transparent
    }
    val contentColor = LocalContentColor.current.let { if (enabled) it else it.copy(alpha = DISABLED_CONTENT_ALPHA) }
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(TREE_ROW_HEIGHT)
                .background(background)
                .clickableWithLongClick(enabled = enabled, onClick = onOpen, onLongClick = onLongClick)
                .padding(start = TREE_INDENT * row.depth, end = TREE_ROW_END_PADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (row.hasChildren) {
                TooltipIconButton(
                    imageVector = if (row.expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (row.expanded) COLLAPSE_DESCRIPTION else EXPAND_DESCRIPTION,
                    onClick = onToggle,
                    modifier = Modifier.size(TREE_TOGGLE_SIZE),
                )
            } else {
                Spacer(Modifier.width(TREE_TOGGLE_SIZE))
            }
            Icon(
                imageVector = if (row.isRoot) Icons.Filled.Home else FolderIcon,
                contentDescription = null,
                modifier = Modifier.size(TREE_ICON_SIZE),
            )
            Spacer(Modifier.width(TREE_ICON_GAP))
            Text(
                text = if (row.isRoot) ROOT_FOLDER_LABEL else row.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
