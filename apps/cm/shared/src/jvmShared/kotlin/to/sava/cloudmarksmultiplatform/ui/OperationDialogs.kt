package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import to.sava.cloudmarksmultiplatform.app.AppState
import to.sava.cloudmarksmultiplatform.app.CloudMarksController
import to.sava.cloudmarksmultiplatform.browse.BrowseState
import to.sava.cloudmarksmultiplatform.browse.MoveDestination
import to.sava.cloudmarksmultiplatform.browse.MoveSubject
import to.sava.cloudmarksmultiplatform.browse.canMoveTo
import to.sava.cloudmarksmultiplatform.browse.flattenFolderTree
import to.sava.cloudmarksmultiplatform.browse.withToggled

/** 移動先を選ぶダイアログの、ツリーの高さ。 */
private val MOVE_DESTINATION_TREE_HEIGHT = 360.dp

/** データを変える操作の、ダイアログ。 */
internal sealed interface OperationDialog {
    /** 開いているフォルダの項目 [itemId] のタイトルを変える。[title] は変える前のタイトル。 */
    data class RenameItem(val itemId: String, val title: String) : OperationDialog

    /** フォルダ [parentId] の子のフォルダの末尾に、新しいフォルダを作る。 */
    data class NewFolder(val parentId: String) : OperationDialog

    /** フォルダ [folderId] の名前を変える。[name] は変える前の名前。 */
    data class RenameFolder(val folderId: String, val name: String) : OperationDialog

    /** フォルダ [folderId] を消す前に確かめる。[name] はそのフォルダの名前。 */
    data class DeleteFolder(val folderId: String, val name: String) : OperationDialog

    /** [subject] の移動先を選ぶ。 */
    data class Move(val subject: MoveSubject) : OperationDialog
}

/**
 * [dialog] を出し、確定したら [controller] の操作を呼ぶ。確定したときと閉じたときに [onClose] を呼ぶ。
 * 操作を受け付けないあいだ（[AppState.canWrite] が偽）は、確定のボタンを押せない。
 */
@Composable
internal fun OperationDialogHost(
    dialog: OperationDialog,
    state: AppState,
    controller: CloudMarksController,
    onClose: () -> Unit,
) {
    val canWrite = state.canWrite
    when (dialog) {
        is OperationDialog.RenameItem -> NameDialog(
            title = RENAME_ITEM_LABEL,
            fieldLabel = TITLE_FIELD_LABEL,
            confirmLabel = CHANGE_LABEL,
            initialValue = dialog.title,
            allowsEmpty = true,
            canWrite = canWrite,
            onConfirm = { title ->
                controller.renameItem(dialog.itemId, title)
                onClose()
            },
            onDismiss = onClose,
        )
        is OperationDialog.NewFolder -> NameDialog(
            title = NEW_FOLDER_LABEL,
            fieldLabel = NAME_FIELD_LABEL,
            confirmLabel = CREATE_LABEL,
            initialValue = "",
            allowsEmpty = false,
            canWrite = canWrite,
            onConfirm = { name ->
                controller.createFolder(dialog.parentId, name)
                onClose()
            },
            onDismiss = onClose,
        )
        is OperationDialog.RenameFolder -> NameDialog(
            title = RENAME_FOLDER_LABEL,
            fieldLabel = NAME_FIELD_LABEL,
            confirmLabel = CHANGE_LABEL,
            initialValue = dialog.name,
            allowsEmpty = false,
            canWrite = canWrite,
            onConfirm = { name ->
                controller.renameFolder(dialog.folderId, name)
                onClose()
            },
            onDismiss = onClose,
        )
        is OperationDialog.DeleteFolder -> DeleteFolderDialog(
            name = dialog.name,
            canWrite = canWrite,
            onConfirm = {
                controller.deleteFolder(dialog.folderId)
                onClose()
            },
            onDismiss = onClose,
        )
        is OperationDialog.Move -> MoveDestinationDialog(
            subject = dialog.subject,
            browse = state.browse,
            canWrite = canWrite,
            onConfirm = { destinationId ->
                controller.moveTo(dialog.subject, MoveDestination.Into(destinationId))
                onClose()
            },
            onDismiss = onClose,
        )
    }
}

/**
 * 名前かタイトルを入れるダイアログ。初めは [initialValue] を入れ、全体を選んだ状態で入力欄に焦点を当てる。
 * 入力欄で確定のキー（Enter）を押しても、確定のボタンと同じに確定する。
 *
 * @param allowsEmpty 空でも確定できるか。偽なら、空のあいだは確定のボタンを押せなくし、欄の下に [EMPTY_NAME_NOTICE] を添える
 * @param canWrite 操作を受け付けるか。偽なら、確定のボタンを押せない
 * @param onConfirm 確定したときに、入れた値を渡す
 */
@Composable
private fun NameDialog(
    title: String,
    fieldLabel: String,
    confirmLabel: String,
    initialValue: String,
    allowsEmpty: Boolean,
    canWrite: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(initialValue, TextRange(0, initialValue.length))) }
    val empty = !allowsEmpty && value.text.isEmpty()
    val canConfirm = canWrite && !empty
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(fieldLabel) },
                supportingText = EMPTY_NAME_NOTICE.takeIf { empty }?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canConfirm) onConfirm(value.text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text) }, enabled = canConfirm) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(CANCEL_LABEL) }
        },
    )
}

/**
 * フォルダ [name] の削除の確認。フォルダの削除は元に戻せないので、確かめてから消す。
 *
 * @param canWrite 操作を受け付けるか。偽なら、確定のボタンを押せない
 */
@Composable
private fun DeleteFolderDialog(name: String, canWrite: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(DELETE_FOLDER_TITLE) },
        text = { Text(deleteFolderConfirmation(name)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = canWrite,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(DELETE_LABEL)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(CANCEL_LABEL) }
        },
    )
}

/**
 * [subject] の移動先を選ぶダイアログ。メインの画面と同じ形のツリーを出し、行を押すとそのフォルダを移動先に選ぶ。
 * 移動先にできないフォルダ（[canMoveTo]。項目を移すときの、項目のあるフォルダ。フォルダを移すときの、そのフォルダ自身と子孫と、
 * いまの親）の行は押せない。
 * ツリーで開いているノードは、初めはメインの画面のツリーと同じにし、ダイアログの中で開閉してもメインの画面には映さない。
 *
 * @param canWrite 操作を受け付けるか。偽なら、確定のボタンを押せない
 * @param onConfirm 確定したときに、移動先のフォルダの ID を渡す
 */
@Composable
private fun MoveDestinationDialog(
    subject: MoveSubject,
    browse: BrowseState,
    canWrite: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val tree = browse.tree?.state ?: return
    var expanded by remember { mutableStateOf(browse.expandedFolders) }
    var chosenId by remember { mutableStateOf<String?>(null) }
    val destinationId = chosenId?.takeIf { subject.canMoveTo(tree, it) }
    val rows = remember(tree, expanded) { flattenFolderTree(tree, expanded) }
    val listState = rememberLazyListState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(MOVE_DESTINATION_TITLE) },
        text = {
            FolderTree(rows, listState, Modifier.fillMaxWidth().height(MOVE_DESTINATION_TREE_HEIGHT)) { row ->
                FolderTreeRowView(
                    row = row,
                    highlighted = row.folderId == destinationId,
                    enabled = subject.canMoveTo(tree, row.folderId),
                    onOpen = { chosenId = row.folderId },
                    onToggle = { expanded = expanded.withToggled(row.folderId) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { destinationId?.let(onConfirm) }, enabled = canWrite && destinationId != null) {
                Text(MOVE_LABEL)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(CANCEL_LABEL) }
        },
    )
}
