package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import to.sava.cloudmarksmultiplatform.browse.BrowseState
import to.sava.cloudmarksmultiplatform.browse.MoveSubject
import to.sava.cloudmarksmultiplatform.sync.FolderReadResult
import kotlin.math.roundToInt

/** ドラッグ中の札の、カーソルからの右への距離。 */
private val DRAG_LABEL_OFFSET_X = 16.dp

/** ドラッグ中の札の、カーソルからの下への距離。 */
private val DRAG_LABEL_OFFSET_Y = 16.dp

/** ドラッグ中の札の幅の上限。長い文は省略記号で切る。 */
private val DRAG_LABEL_MAX_WIDTH = 280.dp

/** ドラッグ中の札の不透明度。札の下の行が透けて見えるようにする。 */
private const val DRAG_LABEL_ALPHA = 0.85f

/** ドラッグ中の札の影。 */
private val DRAG_LABEL_SHADOW = 4.dp

private val DRAG_LABEL_HORIZONTAL_PADDING = 12.dp
private val DRAG_LABEL_VERTICAL_PADDING = 6.dp

/**
 * ドラッグして移している [subject] の札の文。項目が 2 つ以上なら [draggedItemsLabel]、1 つならそのタイトル、フォルダならその名前
 * （どちらも S3 の値をそのまま）。項目が読んである項目に無いか、フォルダがツリーに無いか、項目が無ければ null。
 */
internal fun moveDragLabelOf(subject: MoveSubject, browse: BrowseState): String? =
    when (subject) {
        is MoveSubject.Items -> when (val count = subject.itemIds.size) {
            0 -> null
            1 -> (browse.folderReads[subject.folderId] as? FolderReadResult.Loaded)
                ?.items
                ?.firstOrNull { it.id == subject.itemIds.single() }
                ?.title
            else -> draggedItemsLabel(count)
        }
        is MoveSubject.Folder -> browse.tree?.state?.folders?.get(subject.folderId)?.name
    }

/**
 * 窓いっぱいの枠（大きさ [frameSize]）の中に置く、大きさ [labelSize] のドラッグ中の札の左上の位置。カーソルの位置 [pointer]（枠の座標）から
 * [gap] だけ右下へずらす。札が枠の右端か下端からはみ出すなら、はみ出さないところまで左か上へ戻す。札が枠より大きければ、左端か上端に揃える。
 */
internal fun dragLabelPosition(pointer: Offset, gap: IntOffset, labelSize: IntSize, frameSize: IntSize): IntOffset =
    IntOffset(
        x = (pointer.x.roundToInt() + gap.x).coerceAtMost(frameSize.width - labelSize.width).coerceAtLeast(0),
        y = (pointer.y.roundToInt() + gap.y).coerceAtMost(frameSize.height - labelSize.height).coerceAtLeast(0),
    )

/**
 * ドラッグしているあいだ、カーソルの右下に、移しているものの札（[moveDragLabelOf]）を出す。札は 1 行で、長ければ省略記号で切る。
 * 窓の右端と下端では、札が切れないよう窓の中に収める（[dragLabelPosition]）。
 * 画面の一番上に重ねて描くため、メインの画面の窓いっぱいの枠の、最後の子として置く。ドラッグしていなければ何も描かない。
 *
 * カーソルの位置は札を置くとき（[offset] の中）に読み、カーソルを動かしたときは札を描き直さずに置き直すだけにする。
 */
@Composable
internal fun MoveDragLabel(state: MoveDragState, browse: BrowseState, modifier: Modifier = Modifier) {
    val frame = remember { WindowLayout() }
    var labelSize by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier.fillMaxSize().onGloballyPositioned { frame.coordinates = it }) {
        val label = state.subject?.let { moveDragLabelOf(it, browse) } ?: return@Box
        Surface(
            modifier = Modifier
                .offset {
                    val gap = IntOffset(DRAG_LABEL_OFFSET_X.roundToPx(), DRAG_LABEL_OFFSET_Y.roundToPx())
                    frame.attached?.let { coordinates ->
                        state.pointer?.let { dragLabelPosition(coordinates.windowToLocal(it), gap, labelSize, coordinates.size) }
                    } ?: IntOffset.Zero
                }
                .onSizeChanged { labelSize = it }
                .widthIn(max = DRAG_LABEL_MAX_WIDTH)
                .alpha(DRAG_LABEL_ALPHA),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = DRAG_LABEL_SHADOW,
        ) {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = DRAG_LABEL_HORIZONTAL_PADDING, vertical = DRAG_LABEL_VERTICAL_PADDING),
            )
        }
    }
}
