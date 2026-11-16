package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import to.sava.cloudmarksmultiplatform.browse.BrowseState
import to.sava.cloudmarksmultiplatform.operations.FolderNode
import to.sava.cloudmarksmultiplatform.storage.Item
import to.sava.cloudmarksmultiplatform.sync.FolderReadResult

private val LIST_HORIZONTAL_PADDING = 16.dp
private val LIST_ROW_VERTICAL_PADDING = 8.dp
private val LIST_ICON_GAP = 12.dp
private val LIST_STATUS_PADDING = 24.dp

/** 項目の行の、タイトルと URL のあいだの間隔。 */
private val ITEM_URL_GAP = 12.dp

/** リストの、子のフォルダの行のキーの頭。項目の ID と重ならないようにする。 */
private const val FOLDER_KEY_PREFIX = "folder:"

/** リストの、項目の行のキーの頭。 */
private const val ITEM_KEY_PREFIX = "item:"

/** リストの、読み込み中・壊れている・空などを見せる行のキー。 */
private const val STATUS_KEY = "status"

/**
 * 開いているフォルダのリスト。先頭に子のフォルダ、続いて項目を並べる。
 * 項目は 1 行に、タイトルとその右の URL を並べ、折り返さずに、行ごとに横へずらして見られるようにする。
 * デスクトップでは、右端に縦のスクロールバーを出す。
 * 読み込み中・壊れている・空を見せる。
 *
 * @param listState スクロール位置。フォルダを替えたら、呼ぶ側が新しいものを渡す
 * @param onOpenFolder 子のフォルダを押したときに、そのフォルダを開く
 * @param onSelectItem 項目を押したときに、その項目を選ぶ
 */
@Composable
internal fun FolderListPane(
    browse: BrowseState,
    listState: LazyListState,
    onOpenFolder: (String) -> Unit,
    onSelectItem: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (browse.tree == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            browse.failure?.let { StatusText(describeSyncFailure(it)) } ?: CircularProgressIndicator()
        }
        return
    }
    val children = browse.currentChildren
    val items = browse.currentItems
    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(children, key = { FOLDER_KEY_PREFIX + it.id }) { folder ->
                ChildFolderRow(folder, onOpen = { onOpenFolder(folder.id) })
            }
            when (items) {
                // 項目ファイルが無かった（Missing）フォルダは、マニフェストを読み直してから読むので、読んでいる最中と同じに見せる
                null, FolderReadResult.Missing -> item(STATUS_KEY) {
                    Box(Modifier.fillMaxWidth().padding(LIST_STATUS_PADDING), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is FolderReadResult.Loaded ->
                    if (items.items.isEmpty() && children.isEmpty()) {
                        item(STATUS_KEY) { StatusText(UiMessage(EMPTY_FOLDER_NOTICE)) }
                    } else {
                        items(items.items, key = { ITEM_KEY_PREFIX + it.id }) { item ->
                            ItemRow(item, selected = item.id == browse.selectedItemId, onSelect = { onSelectItem(item.id) })
                        }
                    }
                is FolderReadResult.Broken -> item(STATUS_KEY) { StatusText(describeBrokenFolder(items.problem)) }
                is FolderReadResult.Failed -> item(STATUS_KEY) { StatusText(describeSyncFailure(items.failure)) }
            }
        }
        ListVerticalScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun ChildFolderRow(folder: FolderNode, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = LIST_HORIZONTAL_PADDING, vertical = LIST_ROW_VERTICAL_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = FolderIcon, contentDescription = null)
        Spacer(Modifier.width(LIST_ICON_GAP))
        ScrollableLine(folder.name, MaterialTheme.typography.bodyLarge, Modifier.weight(1f))
        Spacer(Modifier.width(LIST_ICON_GAP))
        Text(
            text = "${folder.count} 件",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider()
}

@Composable
private fun ItemRow(item: Item, selected: Boolean, onSelect: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(horizontal = LIST_HORIZONTAL_PADDING, vertical = LIST_ROW_VERTICAL_PADDING),
    ) {
        // タイトルと URL は 1 つの横の並びとして、一緒にずらす
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                softWrap = false,
                maxLines = 1,
                modifier = Modifier.alignByBaseline(),
            )
            Spacer(Modifier.width(ITEM_URL_GAP))
            Text(
                text = item.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                softWrap = false,
                maxLines = 1,
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
    HorizontalDivider()
}

/**
 * 折り返さない 1 行の文。はみ出した部分は、横へずらして見る（Android は横のスワイプ、デスクトップは横のスクロール）。
 */
@Composable
private fun ScrollableLine(text: String, style: TextStyle, modifier: Modifier, color: Color = Color.Unspecified) {
    Text(
        text = text,
        style = style,
        color = color,
        softWrap = false,
        maxLines = 1,
        modifier = modifier.horizontalScroll(rememberScrollState()),
    )
}

@Composable
private fun StatusText(message: UiMessage) {
    MessageText(
        message = message,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(LIST_STATUS_PADDING),
    )
}
