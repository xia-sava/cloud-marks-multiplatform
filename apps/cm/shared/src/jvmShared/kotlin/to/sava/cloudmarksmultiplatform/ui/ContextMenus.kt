package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import to.sava.cloudmarksmultiplatform.browse.MoveSubject
import to.sava.cloudmarksmultiplatform.browser.Browser

/**
 * 要素が押されたときの様子。
 *
 * @property position 要素の中での、押された位置
 * @property type 押したポインターの種類（マウス・タッチなど）
 * @property secondary 副ボタン（マウスの右ボタン）で押されたか
 * @property ctrlPressed Ctrl を押していたか
 * @property shiftPressed Shift を押していたか
 * @property pressedAt 押した時刻（ポインターのイベントの uptime のミリ秒）
 */
internal data class PointerPress(
    val position: Offset,
    val type: PointerType,
    val secondary: Boolean,
    val ctrlPressed: Boolean,
    val shiftPressed: Boolean,
    val pressedAt: Long,
)

/**
 * この要素が押されるたびに、押されたときの様子を [onPress] に渡す。クリックとドラッグの扱いより先に受け取る。
 * 副ボタンでの押下は消費し、クリックとドラッグの扱いに渡さない。
 */
@Composable
internal fun Modifier.onPointerPress(onPress: (PointerPress) -> Unit): Modifier {
    val currentOnPress by rememberUpdatedState(onPress)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Press) continue
                val change = event.changes.firstOrNull() ?: continue
                val secondary = event.buttons.isSecondaryPressed
                if (secondary) event.changes.forEach { it.consume() }
                currentOnPress(
                    PointerPress(
                        position = change.position,
                        type = change.type,
                        secondary = secondary,
                        ctrlPressed = event.keyboardModifiers.isCtrlPressed,
                        shiftPressed = event.keyboardModifiers.isShiftPressed,
                        pressedAt = change.uptimeMillis,
                    ),
                )
            }
        }
    }
}

/**
 * 右クリックのメニューを出しているかと、出す位置。閉じるあいだも、最後に出した位置を持ち続ける
 * （閉じるときの見た目の変化を、出したときと同じ位置で描くため）。
 */
@Stable
internal class ContextMenuState {
    /** メニューを出しているか。 */
    var expanded: Boolean by mutableStateOf(false)
        private set

    /** 要素の中での、最後にメニューを出した位置。 */
    var position: Offset by mutableStateOf(Offset.Zero)
        private set

    /** 要素の中での、最後に押された位置（[recordPress]）。描き直しには使わないので、状態にしない。 */
    private var lastPress: Offset = Offset.Zero

    /** 要素の中での [position] の位置に、メニューを出す。 */
    fun open(position: Offset) {
        this.position = position
        expanded = true
    }

    /** 要素の中での、押された位置 [position] を覚える。長押しでメニューを出すとき（[openAtLastPress]）に使う。 */
    fun recordPress(position: Offset) {
        lastPress = position
    }

    /** 最後に押された位置（[recordPress]）に、メニューを出す。 */
    fun openAtLastPress() {
        open(lastPress)
    }

    /** メニューを閉じる。位置は残す。 */
    fun close() {
        expanded = false
    }
}

/** 要素ごとの、右クリックのメニューの状態。 */
@Composable
internal fun rememberContextMenuState(): ContextMenuState = remember { ContextMenuState() }

/**
 * 項目とフォルダの、右クリック（タッチの画面では長押しでも）のメニュー。AppBar の、その他のオプションのメニューにも使う。
 * メニューを出す要素の中に置く。メニューは要素の下端の、[ContextMenuState.open] に渡した位置の横に出す。
 * メニューの外を押すなどして、項目を選ばずに閉じたら、[state] を閉じる。メニューの行は、画面全体と同じ表示の大きさで描く
 * （[ScaledPopupContent]）。
 */
@Composable
internal fun ContextMenu(state: ContextMenuState, content: @Composable ColumnScope.() -> Unit) {
    val x = with(LocalDensity.current) { state.position.x.toDp() }
    DropdownMenu(
        expanded = state.expanded,
        onDismissRequest = state::close,
        offset = DpOffset(x, 0.dp),
    ) {
        ScaledPopupContent { content() }
    }
}

/**
 * 項目のメニューの中身。押した項目の操作を呼ぶ。メニューを閉じるのは呼ぶ側。
 * 右クリックの項目のメニューと、タッチの画面の、項目の行を長押ししたときの項目のメニュー（[ItemLongPressMenuItems]）に使う。
 *
 * @param enabled 押せるか。偽なら、どの項目も押せない
 * @param onRename 項目のタイトルを変える
 * @param onMove 項目を移す。右クリックのメニューでは選んでいる項目、長押しのメニューでは長押しした項目
 * @param onDelete 項目を消す。右クリックのメニューでは選んでいる項目、長押しのメニューでは長押しした項目
 */
@Composable
internal fun ItemMenuItems(enabled: Boolean, onRename: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit) {
    MenuItem(RENAME_ITEM_LABEL, enabled, onRename)
    MenuItem(MOVE_LABEL, enabled, onMove)
    MenuItem(DELETE_LABEL, enabled, onDelete)
}

/**
 * タッチの画面の、項目を選んでいるあいだの AppBar の、その他のオプションのメニューの中身。押した項目の操作を呼ぶ。メニューを閉じるのは呼ぶ側。
 * 項目をほかのアプリで開くことと共有することができるプラットフォーム（[sharesItems]）なら、ほかのアプリで開く行と共有の行を先に並べる
 * （[ShareMenuItems]）。
 *
 * @param sharesItems ほかのアプリで開く行と共有の行を出すか
 * @param canWrite タイトルを変更する行を押せるか。偽なら押せない
 * @param onOpenInOtherApp 選んでいる項目を、ほかのアプリで開く
 * @param onShare 選んでいる項目を共有する
 * @param onRename 選んでいる項目のタイトルを変える
 */
@Composable
internal fun MoreOptionsMenuItems(
    sharesItems: Boolean,
    canWrite: Boolean,
    onOpenInOtherApp: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
) {
    ShareMenuItems(sharesItems, onOpenInOtherApp, onShare)
    MenuItem(RENAME_ITEM_LABEL, canWrite, onRename)
}

/**
 * タッチの画面の、選ぶ状態でないときに項目の行を長押しして出す、項目のメニューの中身。長押しした項目について、押した項目の操作を呼ぶ。
 * メニューを閉じるのは呼ぶ側。
 * 先頭に、項目を既定のブラウザで開く行を並べる。項目をほかのアプリで開くことと共有することができるプラットフォーム（[sharesItems]）なら、
 * 続けてほかのアプリで開く行と共有の行を並べ（[ShareMenuItems]）、続けて右クリックの項目のメニューと同じ行（[ItemMenuItems]）を、
 * 最後に選ぶ状態に入る行を並べる。
 *
 * @param sharesItems ほかのアプリで開く行と共有の行を出すか
 * @param canWrite タイトルを変更・移動・削除の行を押せるか。偽なら押せない
 * @param onOpen 項目を、既定のブラウザで開く
 * @param onOpenInOtherApp 項目を、ほかのアプリで開く
 * @param onShare 項目を共有する
 * @param onRename 項目のタイトルを変える
 * @param onMove 項目を移す
 * @param onDelete 項目を消す
 * @param onSelect 項目だけを選び、選ぶ状態に入る
 */
@Composable
internal fun ItemLongPressMenuItems(
    sharesItems: Boolean,
    canWrite: Boolean,
    onOpen: () -> Unit,
    onOpenInOtherApp: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit,
) {
    MenuItem(OPEN_LABEL, enabled = true, onOpen)
    ShareMenuItems(sharesItems, onOpenInOtherApp, onShare)
    ItemMenuItems(canWrite, onRename, onMove, onDelete)
    MenuItem(SELECT_LABEL, enabled = true, onSelect)
}

/**
 * 項目をほかのアプリで開く行と共有の行。いつでも押せる。[sharesItems] が偽なら出さない。押した項目の操作を呼ぶ。メニューを閉じるのは呼ぶ側。
 *
 * @param onOpenInOtherApp 項目を、ほかのアプリで開く
 * @param onShare 項目を共有する
 */
@Composable
private fun ShareMenuItems(sharesItems: Boolean, onOpenInOtherApp: () -> Unit, onShare: () -> Unit) {
    if (!sharesItems) return
    MenuItem(OPEN_IN_OTHER_APP_LABEL, enabled = true, onOpenInOtherApp)
    MenuItem(SHARE_LABEL, enabled = true, onShare)
}

/**
 * フォルダのメニューの中身。押した項目の操作を呼ぶ。メニューを閉じるのは呼ぶ側。
 * ルートフォルダは名前の変更・移動・削除ができないので、[NEW_FOLDER_LABEL] だけを出す。
 *
 * @param enabled 押せるか。偽なら、どの項目も押せない
 * @param onNewFolder フォルダの子のフォルダの末尾に、新しいフォルダを作る
 */
@Composable
internal fun FolderMenuItems(
    isRoot: Boolean,
    enabled: Boolean,
    onNewFolder: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    MenuItem(NEW_FOLDER_LABEL, enabled, onNewFolder)
    if (isRoot) return
    MenuItem(RENAME_FOLDER_LABEL, enabled, onRename)
    MenuItem(MOVE_LABEL, enabled, onMove)
    MenuItem(DELETE_LABEL, enabled, onDelete)
}

/**
 * 押された位置を [menu] に覚え（タッチの画面の長押しでメニューを出すとき [folderMenuOnLongPress] に使う）、副ボタンで押されたら、
 * [onMenuOpen] を呼んでから、その位置に [menu] を出す。ツリーの行とリストの子のフォルダの行の、フォルダのメニューに使う。
 */
@Composable
internal fun Modifier.folderMenuPress(menu: ContextMenuState, onMenuOpen: () -> Unit): Modifier =
    onPointerPress { press ->
        menu.recordPress(press.position)
        if (!press.secondary) return@onPointerPress
        onMenuOpen()
        menu.open(press.position)
    }

/**
 * フォルダの行の長押しですること。タッチの画面（[touchScreen]）なら、[onMenuOpen] を呼んでから、最後に押された位置
 * （[folderMenuPress]）に [menu] を出す。タッチの画面でなければ null で、長押しを扱わない。
 */
internal fun folderMenuOnLongPress(touchScreen: Boolean, menu: ContextMenuState, onMenuOpen: () -> Unit): (() -> Unit)? =
    if (touchScreen) {
        {
            onMenuOpen()
            menu.openAtLastPress()
        }
    } else {
        null
    }

/**
 * 押すと [onClick] を呼ぶ。[onLongClick] があれば、長押しで [onLongClick] を呼ぶ。[onLongClick] が null なら長押しを扱わず、
 * [clickable] と同じにする。
 *
 * @param enabled 押せるか
 */
internal fun Modifier.clickableWithLongClick(enabled: Boolean = true, onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier =
    if (onLongClick == null) {
        clickable(enabled = enabled, onClick = onClick)
    } else {
        combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
    }

/**
 * フォルダ [folderId] の右クリック（タッチの画面では長押しでも）のメニュー。ツリーの行とリストの子のフォルダの行が、行の中に置く。
 * ブラウザで開く行（[OpenInBrowserMenuItems]。そのフォルダの直下の項目を開く）と、フォルダのメニューの中身（[FolderMenuItems]）を並べる。
 *
 * @param name フォルダの名前
 * @param isRoot ルートフォルダか
 * @param itemCount フォルダの直下の項目の数（溜めた削除の項目は数えない。[to.sava.cloudmarksmultiplatform.browse.BrowseState.itemCountOf]）
 */
@Composable
internal fun FolderContextMenu(
    state: ContextMenuState,
    folderId: String,
    name: String,
    isRoot: Boolean,
    itemCount: Int,
    actions: OperationActions,
    browserActions: BrowserActions,
) {
    ContextMenu(state) {
        OpenInBrowserMenuItems(browserActions.browsers, itemCount) { browser ->
            state.close()
            browserActions.onOpenFolderItems(folderId, browser)
        }
        val open = { dialog: OperationDialog ->
            state.close()
            actions.onOpenDialog(dialog)
        }
        FolderMenuItems(
            isRoot = isRoot,
            enabled = actions.canWrite,
            onNewFolder = { open(OperationDialog.NewFolder(folderId)) },
            onRename = { open(OperationDialog.RenameFolder(folderId, name)) },
            onMove = { open(OperationDialog.Move(MoveSubject.Folder(folderId))) },
            onDelete = { open(OperationDialog.DeleteFolder(folderId, name)) },
        )
    }
}

/** [itemCount] 個の項目を開くとき、[browser] では拡張機能が要るのに、入っていると分かっていないか。 */
private fun extensionRequired(browser: Browser, itemCount: Int): Boolean = itemCount > 1 && !browser.opensMultipleItems

/**
 * メニューに並べるブラウザの順。押せる行を先に、そのなかで既定のブラウザを先頭に、残りを名前の順に並べる。
 * まとめて開けないため押せない行（[extensionRequired]）は、最後に名前の順に並べる。
 */
internal fun browsersInMenuOrder(browsers: List<Browser>, itemCount: Int): List<Browser> =
    browsers.sortedWith(
        compareBy<Browser> { extensionRequired(it, itemCount) }
            .thenBy { !it.isDefault }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
    )

/**
 * 項目とフォルダのメニューの、ブラウザで開く行。[browsersInMenuOrder] の順に 1 行ずつ「<ブラウザの名前> で開く」を並べ、
 * 既定のブラウザの行を太字にする。行があれば、その下に区切りの線を引く。押した行のブラウザを [onOpen] に渡す。メニューを閉じるのは呼ぶ側。
 *
 * 開く項目が無ければ、どの行も押せない。2 つ以上なら、まとめて開けないブラウザ（[Browser.opensMultipleItems] が偽）の行を押せなくし、
 * 押せない理由（[EXTENSION_REQUIRED_NOTICE]）をツールチップに出す。
 *
 * @param itemCount 開く項目の数
 */
@Composable
internal fun OpenInBrowserMenuItems(browsers: List<Browser>, itemCount: Int, onOpen: (Browser) -> Unit) {
    if (browsers.isEmpty()) return
    browsersInMenuOrder(browsers, itemCount).forEach { browser ->
        val extensionRequired = extensionRequired(browser, itemCount)
        val item = @Composable {
            DropdownMenuItem(
                text = {
                    Text(openInBrowserLabel(browser.name), fontWeight = if (browser.isDefault) FontWeight.Bold else null)
                },
                onClick = { onOpen(browser) },
                enabled = itemCount > 0 && !extensionRequired,
            )
        }
        if (extensionRequired) WithTooltip(EXTENSION_REQUIRED_NOTICE, item) else item()
    }
    HorizontalDivider()
}

@Composable
private fun MenuItem(label: String, enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick, enabled = enabled)
}
