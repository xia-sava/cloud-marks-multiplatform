package to.sava.cloudmarksmultiplatform.ui

import to.sava.cloudmarksmultiplatform.browse.BrowseState
import to.sava.cloudmarksmultiplatform.browse.MoveDestination
import to.sava.cloudmarksmultiplatform.browse.MoveSubject
import to.sava.cloudmarksmultiplatform.browse.selectedItemsSubject

/**
 * AppBar・リスト・ツリーから始める、データを変える操作の入口。
 *
 * @property canWrite 操作を受け付けるか（[to.sava.cloudmarksmultiplatform.app.AppState.canWrite]）。偽なら、ボタンとメニューの項目を
 * 押せなくし、Delete キーとドラッグを受け付けない
 * @property dragState ドラッグして移す最中の状態
 * @property onOpenDialog 操作のダイアログを開く
 * @property onDeleteSelectedItems 選んでいる項目を消す
 * @property onMove 移すものを、移す先へ移す。移せなければ何もしない
 */
internal class OperationActions(
    val canWrite: Boolean,
    val dragState: MoveDragState,
    val onOpenDialog: (OperationDialog) -> Unit,
    val onDeleteSelectedItems: () -> Unit,
    val onMove: (MoveSubject, MoveDestination) -> Unit,
) {
    /** [browse] で開いているフォルダの、選んでいる項目の移動先を選ぶダイアログを開く。開いているフォルダが無ければ何もしない。 */
    fun openMoveDialog(browse: BrowseState) {
        browse.selectedItemsSubject?.let { onOpenDialog(OperationDialog.Move(it)) }
    }
}
