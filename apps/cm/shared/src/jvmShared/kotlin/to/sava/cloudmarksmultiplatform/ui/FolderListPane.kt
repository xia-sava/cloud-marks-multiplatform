package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
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
import to.sava.cloudmarksmultiplatform.browse.listDrop
import to.sava.cloudmarksmultiplatform.browse.selectionModeOf
import to.sava.cloudmarksmultiplatform.operations.FolderNode
import to.sava.cloudmarksmultiplatform.operations.ItemRef
import to.sava.cloudmarksmultiplatform.storage.Item
import to.sava.cloudmarksmultiplatform.sync.FolderReadResult

private val LIST_HORIZONTAL_PADDING = 16.dp
private val LIST_ROW_VERTICAL_PADDING = 8.dp
private val LIST_ICON_GAP = 12.dp

/** 行の左に置くアイコン（フォルダの行のアイコンと、項目の行の favicon を置く場所）の大きさ。タイトルの始まりを行どうしで揃える。 */
private val LIST_ICON_SIZE = 24.dp
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
 * 子のフォルダの行には、子孫のフォルダの項目まで足した数（[BrowseState.itemCountWithDescendantsOf]）を出す。
 * 溜めた削除の項目（[BrowseState.pendingDeletes]）は、項目の行にも数にも出さない。
 * 項目は 1 行に、タイトルとその右の URL を並べ、折り返さずに、行ごとに横へずらして見られるようにする。
 * 項目の行の左には、favicon（[ItemFavicon]）を描く。
 * 選んでいる項目の行と、最後に開いた項目（[lastOpenedItemId]）の行は、それぞれ違う色で強調する。最後に開いた項目を選んでいれば、
 * 選んでいる行の色にする。
 * 右端に縦のスクロールバー（[ListVerticalScrollbar]）を出す。
 * 読み込み中・壊れている・空を見せる。
 *
 * 項目を押したときの振る舞いは [itemClickOf] で決める。マウスでは、項目をクリックすると既定のブラウザで開き（選んでいる項目は変えない）、
 * Ctrl を押しながらで選ぶ項目を足す・外し、Shift を押しながらで範囲を選ぶ。項目の上では、ポインターを指の形にする。
 * タッチでは、選んでいる項目があるあいだは、押しても長押ししても足す・外す。選んでいる項目が無ければ、タッチの画面（[LocalTouchScreen]）では、
 * 押すと既定のブラウザで開き、長押しすると、押した位置にその項目の長押しのメニュー（[ItemLongPressMenu]。メニューの操作は
 * 長押しした項目について行い、「選択」でその項目だけを選んで選ぶ状態に入る）を出す。タッチの画面でなければ、押すとその項目だけを選び、
 * 長押しで足す（選ぶ状態に入る）。タッチの画面では、キーボードや画面の読み上げの操作での押下も、タッチの押下と同じに扱う。
 * 項目を右クリックすると、選んでいなければその項目だけを選んでから、項目のメニュー（[ItemContextMenu]）を出す。
 * 子のフォルダの行を右クリックすると、フォルダのメニュー（[FolderContextMenu]）を出す。タッチの画面では、子のフォルダの行を長押ししても出す。
 * リストに焦点があるときに Delete キーを押すと、選んでいる項目を消し、Esc キーを押すと、選ぶのをやめる。行の無いところを
 * マウスでクリックしても、選ぶのをやめる。
 * [dragsToMove] のプラットフォームでは、項目と子のフォルダをドラッグして移せる（項目は [draggedItems]。掴んだ項目を選んでいなければ、
 * その項目だけを選んでから移す）。ツリーのフォルダへ落とせるほか、このリストの行へも落とせる（[listDrop]）。項目は項目の行どうしの
 * あいだへ並べ替え、子のフォルダの行に落とすとその中へ移す。子のフォルダは、子のフォルダの行の上下の端で並べ替え、真ん中でその中へ移す。
 * ドラッグしているあいだは、中へ移すならその行を強調し、並べ替えるなら挿入する行の境目に線を引く。ポインターがリストの上下の端にあれば、
 * その向きへスクロールし続ける（[moveDropTargets]）。ドロワーが開いているあいだ（[BrowseState.drawerOpen]）は、ドロワーに覆われた
 * このリストへは落とせない。
 * [revealItemId] を渡したら、その項目の行が全部見える位置までスクロールし、リストに焦点を移す（[RevealItem]）。
 *
 * @param lastOpenedItemId 最後に開いた項目の ID（[to.sava.cloudmarksmultiplatform.app.AppState.lastOpenedItemId]）。
 * 開いているフォルダの項目に無ければ、どの行も強調しない。覚えていなければ null
 * @param listState スクロール位置。フォルダを替えたら、呼ぶ側が新しいものを渡す
 * @param actions データを変える操作の入口
 * @param browserActions 項目をブラウザで開く操作の入口
 * @param shareActions 項目をほかのアプリで開く操作と共有する操作の入口。できないプラットフォームでは null
 * @param onOpenFolder 子のフォルダを押したときに、そのフォルダを開く
 * @param onSelectItem 項目を押したときに、その項目を、渡した選び方で選ぶ
 * @param onClearSelection 選ぶのをやめる
 * @param revealItemId 見える位置までスクロールする、開いているフォルダの項目の ID。検索の結果から、項目が入っているフォルダを開いたときに渡す。
 * 無ければ null
 * @param onItemRevealed [revealItemId] の項目の行までスクロールし、リストに焦点を移し終えたとき（行が無かったときも）に呼ぶ
 */
@Composable
internal fun FolderListPane(
    browse: BrowseState,
    lastOpenedItemId: String?,
    listState: LazyListState,
    actions: OperationActions,
    browserActions: BrowserActions,
    shareActions: ItemShareActions?,
    onOpenFolder: (String) -> Unit,
    onSelectItem: (String, SelectionMode) -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier,
    revealItemId: String? = null,
    onItemRevealed: () -> Unit = {},
) {
    if (browse.tree == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            browse.failure?.let { StatusText(describeSyncFailure(it)) } ?: CircularProgressIndicator()
        }
        return
    }
    val children = browse.currentChildren
    val items = browse.currentItems
    val selectedItemIds = browse.selectedItemIds
    val focusRequester = remember { FocusRequester() }
    val revealIndex = revealItemId?.let { itemId ->
        (items as? FolderReadResult.Loaded)?.items
            ?.indexOfFirst { it.id == itemId }
            ?.takeIf { it >= 0 }
            ?.let { children.size + it }
    }
    RevealItem(revealItemId, revealIndex, listState, focusRequester, onItemRevealed)
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
                    count = browse.itemCountWithDescendantsOf(folder.id),
                    directCount = browse.itemCountOf(folder.id),
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
                        // 開いているフォルダの項目を読めていれば、開いているフォルダがある
                        browse.currentFolderId?.let { folderId ->
                            items(items.items, key = { ITEM_KEY_PREFIX + it.id }) { item ->
                                val ref = ItemRef(folderId, item.id)
                                FolderItemRow(
                                    ref = ref,
                                    item = item,
                                    browse = browse,
                                    lastOpened = item.id == lastOpenedItemId,
                                    line = markOf(DropRow.ListItem(item.id))?.line,
                                    actions = actions,
                                    browserActions = browserActions,
                                    shareActions = shareActions,
                                    onClick = { click ->
                                        focusRequester.requestFocus()
                                        when (click) {
                                            ItemClick.Open -> browserActions.onOpenItem(ref)
                                            is ItemClick.Select -> onSelectItem(item.id, click.mode)
                                            ItemClick.Menu -> Unit
                                        }
                                    },
                                    onSelectItem = onSelectItem,
                                )
                            }
                        }
                    }
                is FolderReadResult.Broken -> item(STATUS_KEY) { StatusText(describeBrokenFolder(items.problem)) }
                is FolderReadResult.Failed -> item(STATUS_KEY) { StatusText(describeSyncFailure(items.failure)) }
            }
        }
        ListVerticalScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/**
 * [itemId] が替わって null でなければ、その項目の行（リストの [index] 番目の行。無ければ null）が全部見える位置までリストをスクロールし、
 * リストに焦点を移して（[focusRequester]。選んでいる項目を Delete キーで消し、Esc キーで選ぶのをやめられるようにする）から、
 * [onRevealed] を呼ぶ。行が既に全部見えていれば動かさない。行が無ければ、スクロールせずに焦点だけを移す。
 * 新しく出したリストのレイアウトが済んでから、行の位置を調べる。
 */
@Composable
private fun RevealItem(itemId: String?, index: Int?, listState: LazyListState, focusRequester: FocusRequester, onRevealed: () -> Unit) {
    val currentOnRevealed by rememberUpdatedState(onRevealed)
    LaunchedEffect(itemId) {
        if (itemId == null) return@LaunchedEffect
        withFrameNanos { }
        if (index != null && !listState.isFullyVisible(ITEM_KEY_PREFIX + itemId)) listState.scrollToItem(index)
        focusRequester.requestFocus()
        currentOnRevealed()
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
 * 子のフォルダの行。押すとそのフォルダを開き、右クリック（タッチの画面では長押しでも）でフォルダのメニューを出し、
 * ドラッグして移す元になる（フォルダとして）。
 *
 * @param count 子孫のフォルダの項目まで足した、項目の数。溜めた削除の項目は数えない
 * @param directCount フォルダの直下の項目の数。溜めた削除の項目は数えない。フォルダのメニューの、ブラウザで開く行に使う
 * @param mark ドラッグして移すものの落とす先のうち、この行を見せ方に使うもの。中へ移すなら行を強調し、並べ替えるならその端に線を引く。
 * 無ければ null
 */
@Composable
private fun ChildFolderRow(
    folder: FolderNode,
    count: Int,
    directCount: Int,
    mark: MoveDrop?,
    actions: OperationActions,
    browserActions: BrowserActions,
    onOpen: () -> Unit,
) {
    val dropTarget = mark != null && mark.line == null
    val menu = rememberContextMenuState()
    val touchScreen = LocalTouchScreen.current
    Column(Modifier.dropLine(mark?.line)) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (dropTarget) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .folderMenuPress(menu, browserActions.onMenuOpen)
                    .moveDragSource(
                        state = actions.dragState,
                        canWrite = actions.canWrite,
                        onDragStart = { MoveSubject.Folder(folder.id) },
                    )
                    .clickableWithLongClick(
                        onClick = onOpen,
                        onLongClick = folderMenuOnLongPress(touchScreen, menu, browserActions.onMenuOpen),
                    )
                    .padding(horizontal = LIST_HORIZONTAL_PADDING, vertical = LIST_ROW_VERTICAL_PADDING),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(imageVector = FolderIcon, contentDescription = null, modifier = Modifier.size(LIST_ICON_SIZE))
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
                itemCount = directCount,
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

    /** その項目の、長押しのメニュー（[ItemLongPressMenuItems]）を、押した位置に出す。選んでいる項目は変えない。 */
    data object Menu : ItemClick
}

/**
 * 項目の行を押したときにすること。
 *
 * マウスでは、長押しかどうかによらず、押したときの修飾キーから決める。Ctrl か Shift を押していれば、その選び方（[selectionModeOf]）で選び、
 * どちらも押していなければ開く。
 *
 * タッチの画面（[touchScreen]）では、マウスでない押下を、ポインターで押していないとき（[press] が null。画面の読み上げの操作や
 * キーボードで押したときなど）も含めて同じに扱う。選んでいる項目があるあいだは、押下も長押しも [SelectionMode.TOGGLE] で選ぶ。
 * 選んでいる項目が無ければ、押下で開き、長押しで項目のメニューを出す。
 *
 * タッチの画面でなければ、ポインターで押していないときは、その項目だけを選ぶ。タッチ（マウスでないポインター。ペンを含む）では、
 * 長押しと、選んでいる項目があるあいだの押下で [SelectionMode.TOGGLE]、選んでいる項目が無いときの押下で [SelectionMode.SINGLE] で選ぶ。
 *
 * @param press 押したときの様子。ポインターで押していなければ null
 * @param longPress 長押しか
 * @param hasSelection 選んでいる項目があるか
 * @param touchScreen 画面をタッチの画面の振る舞いで動かすか（[LocalTouchScreen]）
 */
internal fun itemClickOf(press: PointerPress?, longPress: Boolean, hasSelection: Boolean, touchScreen: Boolean): ItemClick =
    when {
        press != null && press.type == PointerType.Mouse ->
            if (press.ctrlPressed || press.shiftPressed) {
                ItemClick.Select(selectionModeOf(press.ctrlPressed, press.shiftPressed))
            } else {
                ItemClick.Open
            }
        touchScreen -> when {
            hasSelection -> ItemClick.Select(SelectionMode.TOGGLE)
            longPress -> ItemClick.Menu
            else -> ItemClick.Open
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
 * 項目の行をマウスかタッチで開いたクリックを覚え、ダブルクリック（ダブルタップ）の 2 回目で同じ項目をもう一度開かないようにする。
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
 * 開いているフォルダのリストの、項目 [ref] の行（[ItemRow]）。押す・長押し・右クリックで開くか選び（[itemClickOf]）、
 * 右クリックで項目のメニュー（[ItemContextMenu]）を出し、ドラッグして移す元になる。
 * 選ばれていない項目を右クリックしたら、先にその項目だけを選んでから、メニューを出す。メニューのブラウザで開く・移動・削除は
 * 選んでいる項目について、タイトルを変更は右クリックした項目について行う。
 * タッチの画面（[LocalTouchScreen]）では、選ぶ状態でないときに長押しすると、押した位置に、この項目の長押しのメニュー
 * （[ItemLongPressMenu]。「選択」でこの項目だけを選んで選ぶ状態に入る）を出す。
 * 選んでいる行と、最後に開いた項目の行は、それぞれ違う色で強調する。
 *
 * @param browse メインの画面の状態。選んでいる項目と、移す項目（ドラッグして移す項目 [draggedItems] と、移動先を選ぶダイアログ）に使う
 * @param lastOpened 最後に開いた項目か
 * @param line ドラッグして移すものを並べ替える位置の、この行に引く線。無ければ null
 * @param shareActions 項目をほかのアプリで開く操作と共有する操作の入口。できないプラットフォームでは null で、長押しのメニューに
 * それらの行を出さない
 * @param onClick 項目を押したときにすること（[ItemClick.Menu] を除く）を受け取る
 * @param onSelectItem ドラッグを始めるときに、掴んだ項目を選んでいなければ、その項目だけを選ぶ
 */
@Composable
private fun FolderItemRow(
    ref: ItemRef,
    item: Item,
    browse: BrowseState,
    lastOpened: Boolean,
    line: DropLine?,
    actions: OperationActions,
    browserActions: BrowserActions,
    shareActions: ItemShareActions?,
    onClick: (ItemClick) -> Unit,
    onSelectItem: (String, SelectionMode) -> Unit,
) {
    val selectedItemIds = browse.selectedItemIds
    val selected = item.id in selectedItemIds
    val hasSelection = selectedItemIds.isNotEmpty()
    val touchScreen = LocalTouchScreen.current
    ItemRow(
        item = item,
        selected = selected,
        lastOpened = lastOpened,
        clickOf = { press, longPress -> itemClickOf(press, longPress, hasSelection, touchScreen) },
        onClick = onClick,
        onMenuPress = {
            // 選ばれていない項目を右クリックしたら、先にその項目だけを選ぶ
            if (!selected) onClick(ItemClick.Select(SelectionMode.SINGLE))
            browserActions.onMenuOpen()
        },
        contextMenu = { state ->
            ItemContextMenu(
                state = state,
                browsers = browserActions.browsers,
                itemCount = if (selected) selectedItemIds.size else 1,
                canWrite = actions.canWrite,
                onOpenInBrowser = browserActions.onOpenSelectedItems,
                onRename = { actions.onOpenDialog(OperationDialog.RenameItem(ref, item.title)) },
                onMove = { actions.openMoveDialog(browse) },
                onDelete = actions.onDeleteSelectedItems,
            )
        },
        longPressMenu = { state ->
            ItemLongPressMenu(
                state = state,
                ref = ref,
                title = item.title,
                actions = actions,
                shareActions = shareActions,
                onOpen = { onClick(ItemClick.Open) },
                onSelect = { onClick(ItemClick.Select(SelectionMode.SINGLE)) },
            )
        },
        dragSource = Modifier.moveDragSource(
            state = actions.dragState,
            canWrite = actions.canWrite,
            onDragStart = {
                browse.draggedItems(item.id)?.also {
                    if (item.id !in selectedItemIds) onSelectItem(item.id, SelectionMode.SINGLE)
                }
            },
        ),
        line = line,
    )
}

/**
 * 項目の行。左に favicon（[ItemFavicon]）を置き、その右にタイトルとその右の URL を 1 行に並べ、折り返さずに、横へずらして見られるようにする。
 * favicon を置く場所はフォルダの行のアイコンと同じ幅で、タイトルの始まりをフォルダの名前の始まりと揃える。favicon は、ずらしても動かない。
 * [caption] があれば、タイトルの始まりに揃えて、その下に小さく 1 行で出す。
 * 開いているフォルダのリストの行（[FolderItemRow]）と、検索の結果の行（`SearchResultsPane.kt` の `SearchResultRow`）が使う。
 * 項目の上では、ポインターを指の形にする。
 *
 * 押したとき（マウスのクリック、タッチの押下と長押し、キーボードや画面の読み上げの操作での押下）にすることは [clickOf] で決め、
 * [ItemClick.Menu] なら、押した位置に長押しのメニュー（[longPressMenu]）を出し、そのほかは [onClick] に渡す。長押しのメニューは、
 * タッチの画面（[LocalTouchScreen]）でだけ置く。右クリックでは、[onMenuPress] を呼んでから、押した位置に右クリックのメニュー
 * （[contextMenu]）を出す。
 *
 * マウスのクリックは [onMouseClick] で受け、押してから [MOVE_DRAG_THRESHOLD] を越えて動かさずに離したときだけにする。
 * ダブルクリックしても、タッチの画面でダブルタップしても、開くのは 1 回だけにする（[RepeatedOpenFilter]。時間はダブルクリックを決める時間）。
 * タッチでは、1 回目を押してから 2 回目を押すまでの時間で決める。
 * マウスの押下は [onMouseClick] が消費するので、クリックの扱い（[combinedClickable]）はタッチとキーボードの押下だけを受ける。
 *
 * @param selected 選んでいるか。そうなら、行を強調する
 * @param lastOpened 最後に開いた項目か。そうなら、選んでいなければ、選んでいる行と違う色で控えめに強調する
 * @param clickOf 押したときの様子（ポインターで押していなければ null）と長押しかから、することを決める。マウスのクリックは、
 * 押していた時間によらず長押しでないとして渡す
 * @param onClick 押したときにすること（[ItemClick.Menu] を除く）を受け取る
 * @param onMenuPress 右クリックで押したときに、右クリックのメニューを出す前に呼ぶ
 * @param contextMenu 右クリックのメニュー。渡したメニューの状態で出す
 * @param longPressMenu タッチの画面の、長押しのメニュー。渡したメニューの状態で出す
 * @param dragSource ドラッグして移す元にする修飾（[moveDragSource]）。移す元にしなければ [Modifier]
 * @param line ドラッグして移すものを並べ替える位置の、この行に引く線。無ければ null
 * @param caption タイトルと URL の下に出す 1 行。無ければ null
 */
@Composable
internal fun ItemRow(
    item: Item,
    selected: Boolean,
    lastOpened: Boolean,
    clickOf: (press: PointerPress?, longPress: Boolean) -> ItemClick,
    onClick: (ItemClick) -> Unit,
    onMenuPress: () -> Unit,
    contextMenu: @Composable (ContextMenuState) -> Unit,
    longPressMenu: @Composable (ContextMenuState) -> Unit,
    dragSource: Modifier = Modifier,
    line: DropLine? = null,
    caption: String? = null,
) {
    val menuState = rememberContextMenuState()
    val longPressMenuState = rememberContextMenuState()
    val handleClick = { click: ItemClick -> if (click == ItemClick.Menu) longPressMenuState.openAtLastPress() else onClick(click) }
    val lastPress = remember { LastPress() }
    val touchScreen = LocalTouchScreen.current
    val doubleClickTimeoutMillis = LocalViewConfiguration.current.doubleTapTimeoutMillis
    val openFilter = remember(doubleClickTimeoutMillis) { RepeatedOpenFilter(doubleClickTimeoutMillis) }
    val background = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        lastOpened -> MaterialTheme.colorScheme.tertiaryContainer
        else -> Color.Transparent
    }
    Column(Modifier.dropLine(line)) {
        Box {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(background)
                    .onPointerPress { press ->
                        if (!press.secondary) {
                            longPressMenuState.recordPress(press.position)
                            if (press.type != PointerType.Mouse) lastPress.press = press
                            return@onPointerPress
                        }
                        onMenuPress()
                        menuState.open(press.position)
                    }
                    .onMouseClick { click ->
                        val itemClick = clickOf(click.press, false)
                        val repeatedOpen = itemClick == ItemClick.Open && !openFilter.accepts(click.press.pressedAt, click.releasedAt)
                        if (!repeatedOpen) handleClick(itemClick)
                    }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .then(dragSource)
                    .combinedClickable(
                        onClick = {
                            val press = lastPress.take()
                            val itemClick = clickOf(press, false)
                            // タッチの押下は離した時刻を持たないので、押した時刻を離した時刻の代わりにする
                            val repeatedOpen = itemClick == ItemClick.Open && press != null &&
                                !openFilter.accepts(press.pressedAt, press.pressedAt)
                            if (!repeatedOpen) handleClick(itemClick)
                        },
                        onLongClick = { handleClick(clickOf(lastPress.take(), true)) },
                    )
                    .padding(horizontal = LIST_HORIZONTAL_PADDING, vertical = LIST_ROW_VERTICAL_PADDING),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // favicon は、横へずらしても動かない
                        Box(Modifier.size(LIST_ICON_SIZE), contentAlignment = Alignment.Center) { ItemFavicon(item.url) }
                        Spacer(Modifier.width(LIST_ICON_GAP))
                        // タイトルと URL は 1 つの横の並びとして、一緒にずらす
                        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
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
                    caption?.let {
                        ScrollableLine(
                            it,
                            MaterialTheme.typography.bodySmall,
                            Modifier.fillMaxWidth().padding(start = LIST_ICON_SIZE + LIST_ICON_GAP),
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            contextMenu(menuState)
            if (touchScreen) longPressMenu(longPressMenuState)
        }
        HorizontalDivider()
    }
}

/**
 * マウスのクリック。
 *
 * @property press 押したときの様子。押した時刻を含む
 * @property releasedAt 離した時刻（ポインターのイベントの uptime のミリ秒）
 */
private class MouseClick(val press: PointerPress, val releasedAt: Long)

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
                    pressedAt = down.uptimeMillis,
                )
                releaseWithoutMoving(down, thresholdPx)?.let { up ->
                    currentOnClick(MouseClick(press, releasedAt = up.uptimeMillis))
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

/** リストの、読み込み中のほか（壊れている・空・読めなかったなど）を見せる文。フォルダのリストと、検索の結果と、戻せる時点の一覧が使う。 */
@Composable
internal fun StatusText(message: UiMessage) {
    MessageText(
        message = message,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(LIST_STATUS_PADDING),
    )
}
