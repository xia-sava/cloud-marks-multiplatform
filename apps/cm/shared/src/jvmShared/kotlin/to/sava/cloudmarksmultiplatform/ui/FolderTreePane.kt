package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.sava.cloudmarksmultiplatform.browse.BrowseState
import to.sava.cloudmarksmultiplatform.browse.FolderTreeRow
import to.sava.cloudmarksmultiplatform.browse.flattenFolderTree

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

/**
 * フォルダのツリー。マニフェストだけで描き、開いているノードの子だけを平らにして並べる。
 * ルートフォルダは最上段に、家のアイコンと「トップ」で出す。開いているフォルダの行を強調し、開いているフォルダが替わったら、
 * その行が見える位置までスクロールする。
 *
 * @param listState スクロール位置
 * @param onOpen 行を押したときに、そのフォルダを開く
 * @param onToggle 開閉ボタンを押したときに、そのノードを開く、または閉じる
 */
@Composable
internal fun FolderTreePane(
    browse: BrowseState,
    listState: LazyListState,
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
    LazyColumn(modifier, state = listState) {
        items(rows, key = { it.folderId }) { row ->
            FolderTreeRowView(
                row = row,
                current = row.folderId == browse.currentFolderId,
                onOpen = { onOpen(row.folderId) },
                onToggle = { onToggle(row.folderId) },
            )
        }
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

/** キーが [key] の行が、表示の範囲に全部収まっているか。 */
private fun LazyListState.isFullyVisible(key: String?): Boolean {
    val info = layoutInfo
    val row = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return false
    return row.offset >= info.viewportStartOffset && row.offset + row.size <= info.viewportEndOffset
}

@Composable
private fun FolderTreeRowView(row: FolderTreeRow, current: Boolean, onOpen: () -> Unit, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TREE_ROW_HEIGHT)
            .background(if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onOpen)
            .padding(start = TREE_INDENT * row.depth, end = TREE_ROW_END_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.hasChildren) {
            IconButton(onClick = onToggle, modifier = Modifier.size(TREE_TOGGLE_SIZE)) {
                Icon(
                    imageVector = if (row.expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (row.expanded) "折りたたみ" else "展開",
                )
            }
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
