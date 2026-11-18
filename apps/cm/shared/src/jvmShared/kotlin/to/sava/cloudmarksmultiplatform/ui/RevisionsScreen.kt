package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import to.sava.cloudmarksmultiplatform.app.RevisionListState
import to.sava.cloudmarksmultiplatform.app.RevisionNotice
import to.sava.cloudmarksmultiplatform.app.RevisionSelection
import to.sava.cloudmarksmultiplatform.app.RevisionsScreenState
import to.sava.cloudmarksmultiplatform.sync.LoadedRevision
import to.sava.cloudmarksmultiplatform.sync.ManifestCounts
import to.sava.cloudmarksmultiplatform.sync.Revision
import to.sava.cloudmarksmultiplatform.sync.counts

/** 戻せる時点の一覧の、行の左右の余白。 */
private val REVISION_ROW_HORIZONTAL_PADDING = 16.dp

/** 戻せる時点の一覧の、行の上下の余白。 */
private val REVISION_ROW_VERTICAL_PADDING = 12.dp

/** 以前の状態に戻す確認の、本文の行のあいだ。 */
private val CONFIRMATION_LINE_SPACING = 8.dp

/**
 * 以前の状態に戻す画面。上から、選んだ時点を読んでいる最中のインジケーター、戻している最中の帯（[RESTORING_NOTICE]。
 * 操作を書いている最中の帯と同じ [StatusBanner]）、伝えることの帯（[CLOSE_LABEL] を押すと [onDismissNotice] を呼ぶ）、
 * 戻せる時点の一覧を並べる。
 *
 * 一覧は、読んでいる最中は真ん中にインジケーターを出し、読めなければその理由を、戻せる時点が無ければそのことを出す。
 * 戻せる時点は、新しい順に 1 行ずつ、その日時（[formatRevisionTime]）だけを出す。行を押すと、その時点を [onSelect] に渡す。
 * 時点を選んでいるあいだ（読んでいる・確かめている・戻している）は、行を押せない。右端に縦のスクロールバー（[ListVerticalScrollbar]）を出す。
 *
 * 選んだ時点を読めたら、戻すかを確かめるダイアログ（[RestoreConfirmationDialog]）を出す。
 *
 * @param currentCounts いまのツリーのマニフェストのフォルダと項目の数。確認の本文に出す。ツリーが無ければ null
 * @param canRestore 以前の状態に戻すことを受け付けるか。偽なら、確認の確定のボタンを押せない
 * @param onSelect 戻せる時点の行を押したときに、その時点を渡す
 * @param onRestore 確認で、戻すと決めた
 * @param onDismissConfirmation 確認を閉じた
 * @param onDismissNotice 伝えることの帯を閉じた
 */
@Composable
internal fun RevisionsScreen(
    revisions: RevisionsScreenState,
    currentCounts: ManifestCounts?,
    canRestore: Boolean,
    onSelect: (Revision) -> Unit,
    onRestore: () -> Unit,
    onDismissConfirmation: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selection = revisions.selection
    Column(modifier.fillMaxSize()) {
        if (selection is RevisionSelection.Loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (selection is RevisionSelection.Restoring) StatusBanner(UiMessage(RESTORING_NOTICE))
        revisions.notice?.let { Banner(describeRevisionNotice(it), onDismiss = onDismissNotice) }
        RevisionList(revisions.list, selectable = selection == null, onSelect = onSelect)
    }
    if (selection is RevisionSelection.Confirming) {
        RestoreConfirmationDialog(
            loaded = selection.loaded,
            currentCounts = currentCounts,
            canRestore = canRestore,
            onConfirm = onRestore,
            onDismiss = onDismissConfirmation,
        )
    }
}

/** 以前の状態に戻す画面で伝えること [notice] を、利用者に見せる文にする。 */
private fun describeRevisionNotice(notice: RevisionNotice): UiMessage =
    when (notice) {
        is RevisionNotice.ReadFailed -> describeRevisionReadFailure(notice.failure)
        is RevisionNotice.RestoreFailed -> describeRestoreFailure(notice.failure)
    }

/**
 * 戻せる時点の一覧 [list]。読んでいる最中・読めなかった・空を見せる。
 *
 * @param selectable 行を押せるか
 */
@Composable
private fun RevisionList(list: RevisionListState, selectable: Boolean, onSelect: (Revision) -> Unit) {
    when (list) {
        RevisionListState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is RevisionListState.Failed -> StatusText(describeRevisionListFailure(list.failure))
        is RevisionListState.Listed ->
            if (list.revisions.isEmpty()) {
                StatusText(UiMessage(NO_REVISIONS_NOTICE))
            } else {
                RevisionRows(list.revisions, selectable, onSelect)
            }
    }
}

/**
 * 戻せる時点 [revisions] の行を、渡した順に並べる。行のキーは versionId。
 *
 * @param selectable 行を押せるか
 */
@Composable
private fun RevisionRows(revisions: List<Revision>, selectable: Boolean, onSelect: (Revision) -> Unit) {
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(revisions, key = { it.versionId }) { revision ->
                Column {
                    Text(
                        text = formatRevisionTime(revision.lastModified),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = selectable) { onSelect(revision) }
                            .padding(horizontal = REVISION_ROW_HORIZONTAL_PADDING, vertical = REVISION_ROW_VERTICAL_PADDING),
                    )
                    HorizontalDivider()
                }
            }
        }
        ListVerticalScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/**
 * 時点 [loaded] へ戻すかの確認。本文には、その時点の状態に戻すとそれより後の変更が取り消されること、その時点のフォルダと項目の数、
 * いまのフォルダと項目の数（[currentCounts] があれば）、その時点のデータを保存したアプリ（[savedByLine] が分かるときだけ）を並べる。
 * 確定のボタン（[RESTORE_LABEL]）は、フォルダの削除の確認と同じく誤りの色にする。
 * 中身は、画面全体と同じ表示の大きさで描く（[ScaledAlertDialog]）。
 *
 * @param canRestore 以前の状態に戻すことを受け付けるか。偽なら、確定のボタンを押せない
 */
@Composable
private fun RestoreConfirmationDialog(
    loaded: LoadedRevision,
    currentCounts: ManifestCounts?,
    canRestore: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(RESTORE_CONFIRMATION_TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(CONFIRMATION_LINE_SPACING)) {
                Text(restoreConfirmation(formatRevisionTime(loaded.revision.lastModified)))
                Text(revisionCountsLine(loaded.manifest.counts()))
                currentCounts?.let { Text(currentCountsLine(it)) }
                savedByLine(loaded.manifest.header.updatedBy)?.let { Text(it) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = canRestore,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(RESTORE_LABEL)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(CANCEL_LABEL) }
        },
    )
}
