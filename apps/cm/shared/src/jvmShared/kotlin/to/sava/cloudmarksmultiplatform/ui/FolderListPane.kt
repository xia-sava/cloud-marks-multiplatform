package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
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
 * 項目を押したときの振る舞いは [itemClickOf] で決める。マウスでは、項目をクリックすると既定のブラウザで開き（選んでいる項目は変えない）、
 * Ctrl を押しながらで選ぶ項目を足す・外し、Shift を押しながらで範囲を選ぶ。項目の上では、ポインターを指の形にする。
 * タッチでは、長押しで足す・外し（選ぶ状態に入る）、選んでいる項目があるあいだは、押しても足す・外す。選んでいる項目が無ければ、
 * 押すとその項目だけを選ぶ。
 * 項目を右クリックすると、選んでいなければその項目だけを選んでから、項目のメニュー（ブラウザで開く行と [ItemMenuItems]）を出す。
 * 子のフォルダの行を右クリックすると、フォルダのメニュー（[FolderContextMenu]）を出す。
 * リストに焦点があるときに Delete キーを押すと、選んでいる項目を消し、Esc キーを押すと、選ぶのをやめる。行の無いところを
 * マウスでクリックしても、選ぶのをやめる。
 * [dragsToMove] のプラットフォームでは、項目と子のフォルダをドラッグして移せる（項目は [draggedItems]。掴んだ項目を選んでいなければ、
 * その項目だけを選んでから移す）。ツリーのフォルダへ落とせるほか、このリストの行へも落とせる（[listDrop]）。項目は項目の行どうしの
 * あいだへ並べ替え、子のフォルダの行に落とすとその中へ移す。子のフォルダは、子のフォルダの行の上下の端で並べ替え、真ん中でその中へ移す。
 * ドラッグしているあいだは、中へ移すならその行を強調し、並べ替えるなら挿入する行の境目に線を引く。ポインターがリストの上下の端にあれば、
 * その向きへスクロールし続ける（[moveDropTargets]）。ドロワーが開いているあいだ（[BrowseState.drawerOpen]）は、ドロワーに覆われた
 * このリストへは落とせない。
 *
 * @param listState スクロール位置。フォルダを替えたら、呼ぶ側が新しいものを渡す
 * @param actions データを変える操作の入口
 * @param browserActions 項目をブラウザで開く操作の入口
 * @param onOpenFolder 子のフォルダを押したときに、そのフォルダを開く
 * @param onSelectItem 項目を押したときに、その項目を、渡した選び方で選ぶ
 * @param onClearSelection 選ぶのをやめる
 */
@Composable
internal fun FolderListPane(
    browse: BrowseState,
    listState: LazyListState,
    actions: OperationActions,
    browserActions: BrowserActions,
    onOpenFolder: (String) -> Unit,
    onSelectItem: (String, SelectionMode) -> Unit,
    onClearSelection: () -> Unit,
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
                if (event.type != KeyEventType.KeyDown || selectedItemIds.isEmpty()) return@onKeyEvent false
                when {
                    event.key == Key.Delete && actions.canWrite -> {
                        actions.onDeleteSelectedItems()
                        true
                    }
                    event.key == Key.Escape -> {
                        onClearSelection()
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(focusRequester)
            .focusable(),
    ) {
        LazyColumn(
            Modifier.fillMaxSize().then(dropTargets).onBlankMouseClick(listState) { if (selectedItemIds.isNotEmpty()) onClearSelection() },
            state = listState,
        ) {
            items(children, key = { FOLDER_KEY_PREFIX + it.id }) { folder ->
                ChildFolderRow(
                    folder = folder,
                    count = tree.itemCountWithDescendants(folder.id),
                    mark = markOf(DropRow.ListFolder(folder.id)),
                    actions = actions,
                    browserActions = browserActions,
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
                            val selected = item.id in selectedItemIds
                            ItemRow(
                                item = item,
                                selected = selected,
                                hasSelection = selectedItemIds.isNotEmpty(),
                                menuItemCount = if (selected) selectedItemIds.size else 1,
                                line = markOf(DropRow.ListItem(item.id))?.line,
                                actions = actions,
                                browserActions = browserActions,
                                onClick = { click ->
                                    focusRequester.requestFocus()
                                    when (click) {
                                        ItemClick.Open -> browserActions.onOpenItem(item.id)
                                        is ItemClick.Select -> onSelectItem(item.id, click.mode)
                                    }
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
 * 子のフォルダの行。押すとそのフォルダを開き、右クリックでフォルダのメニューを出し、ドラッグして移す元になる（フォルダとして）。
 *
 * @param count 子孫のフォルダの項目まで足した、項目の数
 * @param mark ドラッグして移すものの落とす先のうち、この行を見せ方に使うもの。中へ移すなら行を強調し、並べ替えるならその端に線を引く。
 * 無ければ null
 */
@Composable
private fun ChildFolderRow(
    folder: FolderNode,
    count: Int,
    mark: MoveDrop?,
    actions: OperationActions,
    browserActions: BrowserActions,
    onOpen: () -> Unit,
) {
    val dropTarget = mark != null && mark.line == null
    val menu = rememberContextMenuState()
    Column(Modifier.dropLine(mark?.line)) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (dropTarget) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .onPointerPress { press ->
                        if (!press.secondary) return@onPointerPress
                        browserActions.onMenuOpen()
                        menu.open(press.position)
                    }
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
            FolderContextMenu(
                state = menu,
                folderId = folder.id,
                name = folder.name,
                isRoot = false,
                itemCount = folder.count,
                actions = actions,
                browserActions = browserActions,
            )
        }
        HorizontalDivider()
    }
}

/** 項目の行を押したときにすること。 */
internal sealed interface ItemClick {
    /** その項目を、既定のブラウザで開く。選んでいる項目は変えない。 */
    data object Open : ItemClick

    /** その項目を、[mode] の選び方で選ぶ。 */
    data class Select(val mode: SelectionMode) : ItemClick
}

/**
 * 項目の行を押したときにすること。
 *
 * マウスでは、長押しかどうかによらず、押したときの修飾キーから決める。Ctrl か Shift を押していれば、その選び方（[selectionModeOf]）で選び、
 * どちらも押していなければ開く。
 * ポインターで押していないとき（[press] が null。キーボードで押したときなど）は、その項目だけを選ぶ。
 * タッチ（マウスでないポインター。ペンを含む）では、長押しと、選んでいる項目があるあいだの押下で [SelectionMode.TOGGLE]、
 * 選んでいる項目が無いときの押下で [SelectionMode.SINGLE] で選ぶ。
 *
 * @param press 押したときの様子。ポインターで押していなければ null
 * @param longPress 長押しか
 * @param hasSelection 選んでいる項目があるか
 */
internal fun itemClickOf(press: PointerPress?, longPress: Boolean, hasSelection: Boolean): ItemClick =
    when {
        press != null && press.type == PointerType.Mouse ->
            if (press.ctrlPressed || press.shiftPressed) {
                ItemClick.Select(selectionModeOf(press.ctrlPressed, press.shiftPressed))
            } else {
                ItemClick.Open
            }
        press == null -> ItemClick.Select(SelectionMode.SINGLE)
        longPress || hasSelection -> ItemClick.Select(SelectionMode.TOGGLE)
        else -> ItemClick.Select(SelectionMode.SINGLE)
    }

/**
 * 項目の行が、直前にタッチ（マウスでないポインター）で押されたときの様子。クリックで使ったときに空にする。
 * マウスの押下は覚えない（クリックの扱いに渡らないので、覚えると使われずに残る）。
 */
private class LastPress {
    var press: PointerPress? = null

    /** 覚えている様子を返し、空にする。 */
    fun take(): PointerPress? = press.also { press = null }
}

/**
 * 項目の行をマウスで開いたクリックを覚え、ダブルクリックの 2 回目で同じ項目をもう一度開かないようにする。
 *
 * @param timeoutMillis 開いたクリックを離してから、次に押すまでの時間がこれより短ければ、ダブルクリックの 2 回目とする
 */
internal class RepeatedOpenFilter(private val timeoutMillis: Long) {
    /** 直前に開くクリックを離した時刻（ポインターのイベントの uptime のミリ秒）。まだ無ければ null。 */
    private var lastReleasedAt: Long? = null

    /**
     * 押した時刻 [pressedAt]・離した時刻 [releasedAt] のクリックで、項目を開くか。直前に開くクリックを離してから [timeoutMillis] より前に
     * 押したものは開かない。開かないときも、このクリックを離した時刻を覚える（続けて何度押しても、間が空くまでは開かない）。
     */
    fun accepts(pressedAt: Long, releasedAt: Long): Boolean {
        val repeated = lastReleasedAt?.let { pressedAt - it < timeoutMillis } == true
        lastReleasedAt = releasedAt
        return !repeated
    }
}

/**
 * 項目の行。押す・長押し・右クリックで開くか選び（[itemClickOf]）、右クリックで項目のメニューを出し、ドラッグして移す元になる。
 *
 * マウスのクリックは [onMouseClick] で受け、押してから [MOVE_DRAG_THRESHOLD] を越えて動かさずに離したときだけにする。
 * ダブルクリックしても、開くのは 1 回だけにする（[RepeatedOpenFilter]。時間はダブルクリックを決める時間）。
 * マウスの押下は [onMouseClick] が消費するので、クリックの扱い（[combinedClickable]）はタッチとキーボードの押下だけを受ける。
 *
 * @param hasSelection 選んでいる項目があるか
 * @param menuItemCount 項目のメニューで開く項目の数。この項目を選んでいれば選んでいる項目の数、そうでなければ 1
 * @param line ドラッグして移すものを並べ替える位置の、この行に引く線。無ければ null
 * @param onClick 項目を押したときにすることを受け取る
 * @param onDragStart ドラッグを始めるときに、移す項目を決める。null なら始めない
 * @param onMoveSelected 選んでいる項目の、移動先を選ぶダイアログを開く
 */
@Composable
private fun ItemRow(
    item: Item,
    selected: Boolean,
    hasSelection: Boolean,
    menuItemCount: Int,
    line: DropLine?,
    actions: OperationActions,
    browserActions: BrowserActions,
    onClick: (ItemClick) -> Unit,
    onDragStart: () -> MoveSubject?,
    onMoveSelected: () -> Unit,
) {
    val menu = rememberContextMenuState()
    val lastPress = remember { LastPress() }
    val doubleClickTimeoutMillis = LocalViewConfiguration.current.doubleTapTimeoutMillis
    val openFilter = remember(doubleClickTimeoutMillis) { RepeatedOpenFilter(doubleClickTimeoutMillis) }
    Column(Modifier.dropLine(line)) {
        Box {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .onPointerPress { press ->
                        if (!press.secondary) {
                            if (press.type != PointerType.Mouse) lastPress.press = press
                            return@onPointerPress
                        }
                        // 選ばれていない項目を右クリックしたら、先にその項目だけを選ぶ
                        if (!selected) onClick(ItemClick.Select(SelectionMode.SINGLE))
                        browserActions.onMenuOpen()
                        menu.open(press.position)
                    }
                    .onMouseClick { click ->
                        val itemClick = itemClickOf(click.press, longPress = false, hasSelection)
                        val repeatedOpen = itemClick == ItemClick.Open && !openFilter.accepts(click.pressedAt, click.releasedAt)
                        if (!repeatedOpen) onClick(itemClick)
                    }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .moveDragSource(state = actions.dragState, canWrite = actions.canWrite, onDragStart = onDragStart)
                    .combinedClickable(
                        onClick = { onClick(itemClickOf(lastPress.take(), longPress = false, hasSelection)) },
                        onLongClick = { onClick(itemClickOf(lastPress.take(), longPress = true, hasSelection)) },
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
                OpenInBrowserMenuItems(browserActions.browsers, menuItemCount) { browser ->
                    menu.close()
                    browserActions.onOpenSelectedItems(browser)
                }
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
 * マウスのクリック。
 *
 * @property press 押したときの様子
 * @property pressedAt 押した時刻（ポインターのイベントの uptime のミリ秒）
 * @property releasedAt 離した時刻（同じく）
 */
private class MouseClick(val press: PointerPress, val pressedAt: Long, val releasedAt: Long)

/**
 * この要素をマウスの主ボタンで押し、押した位置から [MOVE_DRAG_THRESHOLD] を越えて動かさずに離したら、そのクリックを [onClick] に渡す。
 * 押していた時間の長さは問わない。一度越えて動かしたら、戻してから離しても渡さない。ほかの要素が変化を消費したとき
 * （ドラッグを始めたときなど）も渡さない。
 *
 * クリックとドラッグの扱いより先に受け取る（[PointerEventPass.Initial]）。マウスの主ボタンの押下は消費し、クリックの扱い
 * （押下が消費されていれば始めない）には渡さない。ドラッグの元（[moveDragSource]）は、消費されていても押下を受け取る。
 * 押下のあとの変化は消費しない。
 */
@Composable
private fun Modifier.onMouseClick(onClick: (MouseClick) -> Unit): Modifier {
    val currentOnClick by rememberUpdatedState(onClick)
    return pointerInput(Unit) {
        val thresholdPx = MOVE_DRAG_THRESHOLD.toPx()
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val down = event.changes.firstOrNull() ?: continue
                val primaryMousePress = event.type == PointerEventType.Press && down.type == PointerType.Mouse &&
                    event.buttons.isPrimaryPressed && !event.buttons.isSecondaryPressed
                if (!primaryMousePress) continue
                down.consume()
                val press = PointerPress(
                    position = down.position,
                    type = down.type,
                    secondary = false,
                    ctrlPressed = event.keyboardModifiers.isCtrlPressed,
                    shiftPressed = event.keyboardModifiers.isShiftPressed,
                )
                releaseWithoutMoving(down, thresholdPx)?.let { up ->
                    currentOnClick(MouseClick(press, pressedAt = down.uptimeMillis, releasedAt = up.uptimeMillis))
                }
            }
        }
    }
}

/**
 * 押した [down] のポインターを、離されるか、クリックでなくなるまで追う。押した位置から [thresholdPx] を越えて動かさずに、
 * ほかの要素に消費されずに、副ボタンを押し足さずに離されたら、離したときの変化を返す。そうでなければ null。
 */
private suspend fun AwaitPointerEventScope.releaseWithoutMoving(down: PointerInputChange, thresholdPx: Float): PointerInputChange? {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == down.id } ?: return null
        if (change.isConsumed || (change.position - down.position).getDistance() > thresholdPx) return null
        if (!change.pressed) return change
        if (event.buttons.isSecondaryPressed) return null
    }
}

/**
 * このリストの枠の、行の無いところのマウスの主ボタンのクリックで、[onClick] を呼ぶ。
 * 押した位置が子のフォルダか項目の行（行と行の境目の線を含む。[rowAt] と [dropRowOf]）なら呼ばない。
 * 子の要素が押下を消費していても呼ばない。タッチの押下では呼ばない。
 *
 * @param listState このリストのスクロール位置。押した位置の下の行を求めるのに使う
 */
@Composable
private fun Modifier.onBlankMouseClick(listState: LazyListState, onClick: () -> Unit): Modifier {
    val currentOnClick by rememberUpdatedState(onClick)
    return pointerInput(listState) {
        awaitEachGesture {
            val down = awaitFirstDown()
            if (down.type != PointerType.Mouse || !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
            if (listState.layoutInfo.rowAt(down.position)?.let { dropRowOf(it.key) } != null) return@awaitEachGesture
            if (waitForUpOrCancellation() != null) currentOnClick()
        }
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
