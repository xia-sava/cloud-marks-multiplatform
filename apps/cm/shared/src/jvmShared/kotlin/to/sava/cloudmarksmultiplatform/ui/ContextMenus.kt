package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * 要素が押されたときの様子。
 *
 * @property position 要素の中での、押された位置
 * @property type 押したポインターの種類（マウス・タッチなど）
 * @property secondary 副ボタン（マウスの右ボタン）で押されたか
 * @property ctrlPressed Ctrl を押していたか
 * @property shiftPressed Shift を押していたか
 */
internal data class PointerPress(
    val position: Offset,
    val type: PointerType,
    val secondary: Boolean,
    val ctrlPressed: Boolean,
    val shiftPressed: Boolean,
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

    /** 要素の中での、最後に右クリックした位置。 */
    var position: Offset by mutableStateOf(Offset.Zero)
        private set

    /** 要素の中での [position] の位置に、メニューを出す。 */
    fun open(position: Offset) {
        this.position = position
        expanded = true
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
 * 項目とフォルダの、右クリックのメニュー。メニューを出す要素の中に置く。メニューは要素の下端の、右クリックした位置の横に出す。
 * メニューの外を押すなどして、項目を選ばずに閉じたら、[state] を閉じる。
 */
@Composable
internal fun ContextMenu(state: ContextMenuState, content: @Composable ColumnScope.() -> Unit) {
    val x = with(LocalDensity.current) { state.position.x.toDp() }
    DropdownMenu(
        expanded = state.expanded,
        onDismissRequest = state::close,
        offset = DpOffset(x, 0.dp),
        content = content,
    )
}

/**
 * 項目のメニューの中身。押した項目の操作を呼ぶ。メニューを閉じるのは呼ぶ側。
 *
 * @param enabled 押せるか。偽なら、どの項目も押せない
 * @param onRename 押した項目のタイトルを変える
 * @param onMove 選んでいる項目を移す
 * @param onDelete 選んでいる項目を消す
 */
@Composable
internal fun ItemMenuItems(enabled: Boolean, onRename: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit) {
    MenuItem(RENAME_ITEM_LABEL, enabled, onRename)
    MenuItem(MOVE_LABEL, enabled, onMove)
    MenuItem(DELETE_LABEL, enabled, onDelete)
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

@Composable
private fun MenuItem(label: String, enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick, enabled = enabled)
}
