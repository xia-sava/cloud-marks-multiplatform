package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import to.sava.cloudmarksmultiplatform.browse.BrowseState
import to.sava.cloudmarksmultiplatform.browse.DropLine
import to.sava.cloudmarksmultiplatform.browse.DropRow
import to.sava.cloudmarksmultiplatform.browse.MoveDrop
import to.sava.cloudmarksmultiplatform.browse.MoveSubject
import to.sava.cloudmarksmultiplatform.browse.SelectionMode
import to.sava.cloudmarksmultiplatform.browse.draggedItems
import to.sava.cloudmarksmultiplatform.browse.itemCountWithDescendants
import to.sava.cloudmarksmultiplatform.browse.listDrop
import to.sava.cloudmarksmultiplatform.browse.selectionModeOf
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
 * 子のフォルダの行には、子孫のフォルダの項目まで足した数（[itemCountWithDescendants]）を出す。
 * 項目は 1 行に、タイトルとその右の URL を並べ、折り返さずに、行ごとに横へずらして見られるようにする。
 * デスクトップでは、右端に縦のスクロールバーを出す。
 * 読み込み中・壊れている・空を見せる。
 *
 * 選び方は [itemSelectionModeOf] で決める。マウスでは、項目を押すとその項目だけを、Ctrl を押しながらで足す・外す、
 * Shift を押しながらで範囲を選ぶ。長押ししても、ふつうのクリックと同じ。タッチでは、長押しで足す・外し（選ぶ状態に入る）、
 * 選んでいる項目があるあいだは、押しても足す・外す。
 * 項目を右クリックすると、選んでいなければその項目だけを選んでから、項目のメニュー（[ItemMenuItems]）を出す。
 * リストに焦点があるときに Delete キーを押すと、選んでいる項目を消す。
 * [dragsToMove] のプラットフォームでは、項目と子のフォルダをドラッグして移せる（項目は [draggedItems]。掴んだ項目を選んでいなければ、
 * その項目だけを選んでから移す）。ツリーのフォルダへ落とせるほか、このリストの行へも落とせる（[listDrop]）。項目は項目の行どうしの
 * あいだへ並べ替え、子のフォルダの行に落とすとその中へ移す。子のフォルダは、子のフォルダの行の上下の端で並べ替え、真ん中でその中へ移す。
 * ドラッグしているあいだは、中へ移すならその行を強調し、並べ替えるなら挿入する行の境目に線を引く。ポインターがリストの上下の端にあれば、
 * その向きへスクロールし続ける（[moveDropTargets]）。ドロワーが開いているあいだ（[BrowseState.drawerOpen]）は、ドロワーに覆われた
 * このリストへは落とせない。
 *
 * @param listState スクロール位置。フォルダを替えたら、呼ぶ側が新しいものを渡す
 * @param actions データを変える操作の入口
 * @param onOpenFolder 子のフォルダを押したときに、そのフォルダを開く
 * @param onSelectItem 項目を押したときに、その項目を、渡した選び方で選ぶ
 */
@Composable
internal fun FolderListPane(
    browse: BrowseState,
    listState: LazyListState,
    actions: OperationActions,
    onOpenFolder: (String) -> Unit,
    onSelectItem: (String, SelectionMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (browse.tree == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            browse.failure?.let { StatusText(describeSyncFailure(it)) } ?: CircularProgressIndicator()
        }
        return
    }
    val tree = browse.tree.state
    val children = browse.currentChildren
    val items = browse.currentItems
    val selectedItemIds = browse.selectedItemIds
    val focusRequester = remember { FocusRequester() }
    val drag = actions.dragState
    val dropTargets = Modifier.moveDropTargets(drag, listState, enabled = !browse.drawerOpen) { hit, subject ->
        dropRowOf(hit.key)?.let { row -> browse.listDrop(row, hit.y, hit.height, subject) }
    }
    val markOf = { row: DropRow -> drag.drop?.takeIf { actions.canWrite && it.row == row } }
    Box(
        modifier
            .onKeyEvent { event ->
                val delete = event.type == KeyEventType.KeyDown && event.key == Key.Delete
                if (delete && actions.canWrite && selectedItemIds.isNotEmpty()) {
                    actions.onDeleteSelectedItems()
                    true
                } else {
                    false
                }
            }
            .focusRequester(focusRequester)
            .focusable(),
    ) {
        LazyColumn(Modifier.fillMaxSize().then(dropTargets), state = listState) {
            items(children, key = { FOLDER_KEY_PREFIX + it.id }) { folder ->
                ChildFolderRow(
                    folder = folder,
                    count = tree.itemCountWithDescendants(folder.id),
                    mark = markOf(DropRow.ListFolder(folder.id)),
                    actions = actions,
                    onOpen = { onOpenFolder(folder.id) },
                )
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
                            ItemRow(
                                item = item,
                                selected = item.id in selectedItemIds,
                                hasSelection = selectedItemIds.isNotEmpty(),
                                line = markOf(DropRow.ListItem(item.id))?.line,
                                actions = actions,
                                onSelect = { mode ->
                                    focusRequester.requestFocus()
                                    onSelectItem(item.id, mode)
                                },
                                onDragStart = {
                                    browse.draggedItems(item.id)?.also {
                                        if (item.id !in selectedItemIds) onSelectItem(item.id, SelectionMode.SINGLE)
                                    }
                                },
                                onMoveSelected = { actions.openMoveDialog(browse) },
                            )
                        }
                    }
                is FolderReadResult.Broken -> item(STATUS_KEY) { StatusText(describeBrokenFolder(items.problem)) }
                is FolderReadResult.Failed -> item(STATUS_KEY) { StatusText(describeSyncFailure(items.failure)) }
            }
        }
        ListVerticalScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/** リストの行のキー [key] の、落とす先として強調するか線を引く行。子のフォルダの行と項目の行のほか（読み込み中などを見せる行）は null。 */
private fun dropRowOf(key: Any): DropRow? {
    val text = key as? String ?: return null
    return when {
        text.startsWith(FOLDER_KEY_PREFIX) -> DropRow.ListFolder(text.removePrefix(FOLDER_KEY_PREFIX))
        text.startsWith(ITEM_KEY_PREFIX) -> DropRow.ListItem(text.removePrefix(ITEM_KEY_PREFIX))
        else -> null
    }
}

/**
 * 子のフォルダの行。押すとそのフォルダを開き、ドラッグして移す元になる（フォルダとして）。
 *
 * @param count 子孫のフォルダの項目まで足した、項目の数
 * @param mark ドラッグして移すものの落とす先のうち、この行を見せ方に使うもの。中へ移すなら行を強調し、並べ替えるならその端に線を引く。
 * 無ければ null
 */
@Composable
private fun ChildFolderRow(folder: FolderNode, count: Int, mark: MoveDrop?, actions: OperationActions, onOpen: () -> Unit) {
    val dropTarget = mark != null && mark.line == null
    Column(Modifier.dropLine(mark?.line)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (dropTarget) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                .moveDragSource(
                    state = actions.dragState,
                    canWrite = actions.canWrite,
                    onDragStart = { MoveSubject.Folder(folder.id) },
                )
                .clickable(onClick = onOpen)
                .padding(horizontal = LIST_HORIZONTAL_PADDING, vertical = LIST_ROW_VERTICAL_PADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = FolderIcon, contentDescription = null)
            Spacer(Modifier.width(LIST_ICON_GAP))
            ScrollableLine(folder.name, MaterialTheme.typography.bodyLarge, Modifier.weight(1f))
            Spacer(Modifier.width(LIST_ICON_GAP))
            Text(
                text = "$count 件",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
    }
}

/**
 * 項目の行を押したときの選び方。
 *
 * マウスでは、長押しかどうかによらず、押したときの修飾キーから決める（[selectionModeOf]）。ポインターで押していないとき
 * （[press] が null。キーボードで押したときなど）も同じで、修飾キーは押していないとする。
 * タッチ（マウスでないポインター。ペンを含む）では、長押しと、選んでいる項目があるあいだの押下で [SelectionMode.TOGGLE]、
 * 選んでいる項目が無いときの押下で [SelectionMode.SINGLE]。
 *
 * @param press 押したときの様子。ポインターで押していなければ null
 * @param longPress 長押しか
 * @param hasSelection 選んでいる項目があるか
 */
internal fun itemSelectionModeOf(press: PointerPress?, longPress: Boolean, hasSelection: Boolean): SelectionMode =
    when {
        press == null || press.type == PointerType.Mouse -> selectionModeOf(press?.ctrlPressed == true, press?.shiftPressed == true)
        longPress || hasSelection -> SelectionMode.TOGGLE
        else -> SelectionMode.SINGLE
    }

/** 項目の行が、直前に主ボタンかタッチで押されたときの様子。クリックで使ったときと、ドラッグを始めたときに空にする。 */
private class LastPress {
    var press: PointerPress? = null

    /** 覚えている様子を返し、空にする。 */
    fun take(): PointerPress? = press.also { press = null }
}

/**
 * 項目の行。押す・長押し・右クリックで選び、右クリックで項目のメニューを出し、ドラッグして移す元になる。
 *
 * @param hasSelection 選んでいる項目があるか
 * @param line ドラッグして移すものを並べ替える位置の、この行に引く線。無ければ null
 * @param onSelect 項目を、渡した選び方で選ぶ
 * @param onDragStart ドラッグを始めるときに、移す項目を決める。null なら始めない
 * @param onMoveSelected 選んでいる項目の、移動先を選ぶダイアログを開く
 */
@Composable
private fun ItemRow(
    item: Item,
    selected: Boolean,
    hasSelection: Boolean,
    line: DropLine?,
    actions: OperationActions,
    onSelect: (SelectionMode) -> Unit,
    onDragStart: () -> MoveSubject?,
    onMoveSelected: () -> Unit,
) {
    val menu = rememberContextMenuState()
    val lastPress = remember { LastPress() }
    Column(Modifier.dropLine(line)) {
        Box {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .onPointerPress { press ->
                        if (!press.secondary) {
                            lastPress.press = press
                            return@onPointerPress
                        }
                        // 選ばれていない項目を右クリックしたら、先にその項目だけを選ぶ
                        if (!selected) onSelect(SelectionMode.SINGLE)
                        menu.open(press.position)
                    }
                    .moveDragSource(
                        state = actions.dragState,
                        canWrite = actions.canWrite,
                        onDragStart = {
                            // ドラッグにした押下はクリックにならないので、押したときの様子を捨てる
                            lastPress.take()
                            onDragStart()
                        },
                    )
                    .combinedClickable(
                        onClick = { onSelect(itemSelectionModeOf(lastPress.take(), longPress = false, hasSelection)) },
                        onLongClick = { onSelect(itemSelectionModeOf(lastPress.take(), longPress = true, hasSelection)) },
                    )
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
            ContextMenu(menu) {
                ItemMenuItems(
                    enabled = actions.canWrite,
                    onRename = {
                        menu.close()
                        actions.onOpenDialog(OperationDialog.RenameItem(item.id, item.title))
                    },
                    onMove = {
                        menu.close()
                        onMoveSelected()
                    },
                    onDelete = {
                        menu.close()
                        actions.onDeleteSelectedItems()
                    },
                )
            }
        }
        HorizontalDivider()
    }
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
