package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import to.sava.cloudmarksmultiplatform.browse.DropLine
import to.sava.cloudmarksmultiplatform.browse.MoveDestination
import to.sava.cloudmarksmultiplatform.browse.MoveDrop
import to.sava.cloudmarksmultiplatform.browse.MoveSubject
import to.sava.cloudmarksmultiplatform.browse.RowEdge

/**
 * ドラッグして移すことを始めるまでに、押した位置からポインターを動かす距離。これより短い動きは、クリックのうちとする。
 * Compose のデスクトップのマウスの既定の閾値はごく短く、クリックのあいだにわずかに動いただけでもドラッグになるため、ここで決める。
 */
internal val MOVE_DRAG_THRESHOLD = 8.dp

/**
 * 押してから動かしたポインターで、ドラッグして移すことを始めるか。マウスの主ボタンで押していて、操作を受け付けていて、
 * 押した位置から閾値を越えて動かしたときに始める。
 *
 * @param pointerType 押しているポインターの種類
 * @param primaryPressed 主ボタンを押しているか
 * @param canWrite 操作を受け付けるか
 * @param movement 押した位置から動かした量
 * @param thresholdPx [MOVE_DRAG_THRESHOLD] を画素に直したもの
 */
internal fun startsMoveDrag(
    pointerType: PointerType,
    primaryPressed: Boolean,
    canWrite: Boolean,
    movement: Offset,
    thresholdPx: Float,
): Boolean = pointerType == PointerType.Mouse && primaryPressed && canWrite && movement.getDistance() > thresholdPx

/** 落とす位置の線の太さ。 */
private val DROP_LINE_THICKNESS = 2.dp

/** ドラッグしているあいだ、ツリーやリストの枠を自動でスクロールさせる、枠の上下の端の帯の幅。 */
private val AUTO_SCROLL_EDGE = 48.dp

/** ドラッグしているあいだの、ツリーやリストの枠の自動のスクロールの速さ。1 秒あたりにスクロールする距離。 */
private val AUTO_SCROLL_DISTANCE_PER_SECOND = 480.dp

/** フレームの時刻（ナノ秒）を秒に直すときの、割る数。 */
private const val NANOS_PER_SECOND = 1_000_000_000f

/**
 * 見えている行のうち、ポインターの下にあるもの。
 *
 * @property key 行のキー
 * @property y 行の上端からの、ポインターの位置
 * @property height 行の高さ
 */
internal data class RowHit(val key: Any, val y: Float, val height: Float)

/**
 * 枠の中での位置 [position] の下にある、見えている行と、その行の中での位置。枠の外か、行の無いところなら null。
 */
internal fun LazyListLayoutInfo.rowAt(position: Offset): RowHit? {
    if (!containsInViewport(position)) return null
    val mainAxis = mainAxisOf(position)
    val row = visibleItemsInfo.firstOrNull { mainAxis >= it.offset && mainAxis < it.offset + it.size } ?: return null
    return RowHit(row.key, mainAxis - row.offset, row.size.toFloat())
}

/**
 * 枠の中での位置 [position] が、最後の行より下の、行の無いところか。最後の行が見えていて、その下端より下にあるとき。
 * 枠の外か、行の上なら偽。
 */
internal fun LazyListLayoutInfo.isBelowLastRow(position: Offset): Boolean {
    if (!containsInViewport(position)) return false
    val last = visibleItemsInfo.lastOrNull()?.takeIf { it.index == totalItemsCount - 1 } ?: return false
    return mainAxisOf(position) >= last.offset + last.size
}

private fun LazyListLayoutInfo.containsInViewport(position: Offset): Boolean =
    position.x >= 0f && position.y >= 0f && position.x < viewportSize.width && position.y < viewportSize.height

/** 枠の中での位置 [position] の、行の並びの向きの位置。行の位置は、枠の先頭の余白の後ろから数える。viewportStartOffset は、その余白の幅を負にしたもの。 */
private fun LazyListLayoutInfo.mainAxisOf(position: Offset): Float = position.y + viewportStartOffset

/** ドラッグしているあいだに、ツリーやリストの枠を自動でスクロールさせる向き。 */
internal enum class AutoScrollDirection(val sign: Float) {
    /** 上へ。前の行を見せる。 */
    UP(-1f),

    /** 下へ。後ろの行を見せる。 */
    DOWN(1f),
}

/**
 * 大きさ [viewportSize] の枠の中での位置 [position] にポインターがあるときに、枠を自動でスクロールさせる向き。
 * 枠の上端から [edgePx] の帯の中なら上へ、下端から [edgePx] の帯の中なら下へ。枠の外か、どちらの帯にも無ければ null。
 */
internal fun autoScrollDirectionOf(position: Offset, viewportSize: IntSize, edgePx: Float): AutoScrollDirection? =
    when {
        position.x < 0f || position.y < 0f || position.x >= viewportSize.width || position.y >= viewportSize.height -> null
        position.y < edgePx -> AutoScrollDirection.UP
        position.y >= viewportSize.height - edgePx -> AutoScrollDirection.DOWN
        else -> null
    }

/** ドラッグして移すものを落とす先になる枠。 */
internal fun interface MoveDropTargets {
    /** 窓の座標で [pointer] の位置へ [subject] を落とすときの、落とす先。枠の外か、落とせなければ null。 */
    fun dropAt(pointer: Offset, subject: MoveSubject): MoveDrop?
}

/**
 * リストの項目と、ツリーとリストのフォルダを、ドラッグして移す最中の状態。メインの画面で 1 つ持ち、ドラッグの元（[moveDragSource]）と、
 * ドラッグしているポインターを追うメインの画面の枠（[moveDragTracker]）と、落とす先になるツリーとリストの枠（[moveDropTargets]）と、
 * ドラッグ中の札が共有する。
 *
 * ドラッグは掴んだ行で始めるが、始めたあとのポインターはメインの画面の枠が追い、その位置をここに置く。落とす先の枠は、その位置から
 * ポインターの下の落とす先を求める。位置は窓の座標で持つ。
 */
@Stable
internal class MoveDragState {
    /** ドラッグして移しているもの。ドラッグしていなければ null。 */
    var subject: MoveSubject? by mutableStateOf(null)
        private set

    /** ドラッグしているポインターの、窓の座標での位置。ドラッグしていなければ null。 */
    var pointer: Offset? by mutableStateOf(null)
        private set

    /** ドラッグしているポインター。ドラッグしていなければ null。ポインターの処理だけが読むので、描画の更新には使わない。 */
    var pointerId: PointerId? = null
        private set

    /** 落とす先になる枠。 */
    private val dropTargets = mutableStateListOf<MoveDropTargets>()

    /**
     * ドラッグしているポインターの下の落とす先。ドラッグしていないか、落とせるところになければ null。
     * 落とす先になる枠のうち、最初に落とす先を返したものを使う。
     * ポインターを動かさずにツリーやリストがスクロールしたときも、そのときの行から求め直す。
     */
    val drop: MoveDrop? by derivedStateOf {
        val subject = subject ?: return@derivedStateOf null
        val pointer = pointer ?: return@derivedStateOf null
        dropTargets.firstNotNullOfOrNull { it.dropAt(pointer, subject) }
    }

    /** [subject] のドラッグを、ポインター [pointerId] で、窓の座標で [pointer] の位置から始める。 */
    fun start(subject: MoveSubject, pointerId: PointerId, pointer: Offset) {
        this.subject = subject
        this.pointerId = pointerId
        this.pointer = pointer
    }

    /** ドラッグしているポインターが、窓の座標で [pointer] の位置へ動いた。ドラッグしていなければ何もしない。 */
    fun moveTo(pointer: Offset) {
        if (subject == null) return
        this.pointer = pointer
    }

    /**
     * ドラッグを終える。ポインターが落とせるところにあれば、移しているものと落とす先を返す。
     * ドラッグしていないか、落とせるところになければ null。
     */
    fun finish(): Pair<MoveSubject, MoveDestination>? {
        val dropped = subject?.let { subject -> drop?.let { subject to it.destination } }
        cancel()
        return dropped
    }

    /** ドラッグを、どこにも落とさずに終える。 */
    fun cancel() {
        subject = null
        pointerId = null
        pointer = null
    }

    /** [targets] を、落とす先になる枠に加える。 */
    fun addDropTargets(targets: MoveDropTargets) {
        dropTargets += targets
    }

    /** [targets] を、落とす先になる枠から外す。 */
    fun removeDropTargets(targets: MoveDropTargets) {
        dropTargets.remove(targets)
    }
}

/** 位置を窓の座標に直すために、要素の位置を持つ。 */
internal class WindowLayout {
    var coordinates: LayoutCoordinates? = null

    /** まだ置かれていないか、外されていれば null。 */
    val attached: LayoutCoordinates? get() = coordinates?.takeIf { it.isAttached }
}

/**
 * この要素を、ドラッグして移すものの元にする。[dragsToMove] が偽のプラットフォームでは何もしない。
 *
 * マウスの主ボタンで押し、押した位置から [MOVE_DRAG_THRESHOLD] を越えて動かしたら、ドラッグを始める（[startsMoveDrag]）。
 * 越えるまではポインターの変化を消費しないので、クリックと選ぶことは妨げない。[canWrite] が偽のあいだは始めない。
 * ドラッグを始めるときに [onDragStart] を呼び、返ったものを移す。null が返れば始めない。
 *
 * 始めたあとは、メインの画面の枠（[moveDragTracker]）がポインターを離すまで追い、落とす。そのため、ツリーやリストがスクロールして
 * この要素が無くなっても、ドラッグは続く。
 */
@Composable
internal fun Modifier.moveDragSource(
    state: MoveDragState,
    canWrite: Boolean,
    onDragStart: () -> MoveSubject?,
): Modifier {
    if (!dragsToMove) return this
    val currentCanWrite by rememberUpdatedState(canWrite)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val layout = remember { WindowLayout() }
    return onGloballyPositioned { layout.coordinates = it }
        .pointerInput(state) {
            val thresholdPx = MOVE_DRAG_THRESHOLD.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val start = awaitMoveDragStart(down, thresholdPx) { currentCanWrite } ?: return@awaitEachGesture
                val subject = currentOnDragStart() ?: return@awaitEachGesture
                val coordinates = layout.attached ?: return@awaitEachGesture
                start.consume()
                state.start(subject, down.id, coordinates.localToWindow(start.position))
            }
        }
}

/**
 * 押した [down] のポインターを、ドラッグを始めるか、始めずに終わるまで追う。始めるときのポインターの変化を返す。
 * 始めるまでは何も消費しない。始めずに離されたか、ほかの要素が変化を消費したら null。
 */
private suspend fun AwaitPointerEventScope.awaitMoveDragStart(
    down: PointerInputChange,
    thresholdPx: Float,
    canWrite: () -> Boolean,
): PointerInputChange? {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: return null
        if (!change.pressed || change.isConsumed) return null
        val starts = startsMoveDrag(
            pointerType = change.type,
            primaryPressed = event.buttons.isPrimaryPressed,
            canWrite = canWrite(),
            movement = change.position - down.position,
            thresholdPx = thresholdPx,
        )
        if (starts) return change
    }
}

/**
 * ドラッグの元（[moveDragSource]）が始めたドラッグのポインターを、離されるまで追う。この修飾は、メインの画面の窓いっぱいの枠に付ける。
 * [dragsToMove] が偽のプラットフォームでは何もしない。
 *
 * ドラッグしているあいだのポインターの変化は、子より先に受けて（[PointerEventPass.Initial]）すべて消費し、位置を [state] に渡す。
 * そのため、掴んだ行もほかの要素も、その押下でクリックもスクロールもしない（押下はクリックにならない）。
 * ポインターが落とせるところ（[MoveDragState.drop]）で離されたら、移しているものと落とす先を [onDrop] に渡す。
 * 書く前にもう一度移せるかを確かめるのは、[onDrop] の側。ドラッグしているポインターが無くなったら、どこにも落とさずに終える。
 */
@Composable
internal fun Modifier.moveDragTracker(state: MoveDragState, onDrop: (MoveSubject, MoveDestination) -> Unit): Modifier {
    if (!dragsToMove) return this
    val currentOnDrop by rememberUpdatedState(onDrop)
    val layout = remember { WindowLayout() }
    return onGloballyPositioned { layout.coordinates = it }
        .pointerInput(state) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pointerId = state.pointerId ?: continue
                    val change = event.changes.firstOrNull { it.id == pointerId }
                    if (change == null) {
                        state.cancel()
                        continue
                    }
                    change.consume()
                    layout.attached?.let { state.moveTo(it.localToWindow(change.position)) }
                    if (!change.pressed) state.finish()?.let { (subject, destination) -> currentOnDrop(subject, destination) }
                }
            }
        }
}

/**
 * [listState] のリスト（ツリーか、開いているフォルダのリスト）の枠を、ドラッグして移すものを落とす先にする。この修飾は、リストの枠に付ける。
 * 枠の位置と [listState] の見えている行から、ポインターの下の行とその行の中での位置を求め（[rowAt]）、[dropAt] で落とす先にする。
 * 最後の行より下の行の無いところ（[isBelowLastRow]）では、[dropBelowRows] で落とす先にする。
 *
 * ドラッグしているあいだ、ポインターが枠の上下の端の帯（[AUTO_SCROLL_EDGE]）にあれば、その向きへ一定の速さでスクロールし続ける
 * （[autoScrollDirectionOf]）。帯から出るか、ドラッグが終わるか、その向きへスクロールできなくなったら止める。
 * 落とす先は、スクロールしたあとの行から求め直す。
 *
 * [enabled] が偽のあいだは、落とす先にも、自動でスクロールする枠にもならない。ほかの枠に覆われて見えないときに偽にする。
 * [dragsToMove] が偽のプラットフォームでは何もしない。
 *
 * @param dropAt ポインターの下の行へ、移すものを落とすときの落とす先。落とせなければ null
 * @param dropBelowRows 最後の行より下の、行の無いところへ、移すものを落とすときの落とす先。落とせなければ null。
 * 既定では、行の無いところは落とす先にしない
 */
@Composable
internal fun Modifier.moveDropTargets(
    state: MoveDragState,
    listState: LazyListState,
    enabled: Boolean = true,
    dropBelowRows: (MoveSubject) -> MoveDrop? = { null },
    dropAt: (RowHit, MoveSubject) -> MoveDrop?,
): Modifier {
    if (!dragsToMove) return this
    val currentEnabled by rememberUpdatedState(enabled)
    val currentDropAt by rememberUpdatedState(dropAt)
    val currentDropBelowRows by rememberUpdatedState(dropBelowRows)
    val currentDensity by rememberUpdatedState(LocalDensity.current)
    val layout = remember { WindowLayout() }
    val targets = remember(listState) {
        MoveDropTargets { pointer, subject ->
            if (!currentEnabled) return@MoveDropTargets null
            val position = layout.attached?.windowToLocal(pointer) ?: return@MoveDropTargets null
            val layoutInfo = listState.layoutInfo
            when {
                layoutInfo.isBelowLastRow(position) -> currentDropBelowRows(subject)
                else -> layoutInfo.rowAt(position)?.let { hit -> currentDropAt(hit, subject) }
            }
        }
    }
    DisposableEffect(state, targets) {
        state.addDropTargets(targets)
        onDispose { state.removeDropTargets(targets) }
    }
    LaunchedEffect(state, listState) {
        snapshotFlow {
            val pointer = state.pointer?.takeIf { currentEnabled } ?: return@snapshotFlow null
            val coordinates = layout.attached ?: return@snapshotFlow null
            val edgePx = with(currentDensity) { AUTO_SCROLL_EDGE.toPx() }
            autoScrollDirectionOf(coordinates.windowToLocal(pointer), listState.layoutInfo.viewportSize, edgePx)
        }.collectLatest { direction ->
            direction?.let { listState.autoScroll(it) { with(currentDensity) { AUTO_SCROLL_DISTANCE_PER_SECOND.toPx() } } }
        }
    }
    return onGloballyPositioned { layout.coordinates = it }
}

/**
 * [direction] へ、1 秒あたり [distancePerSecondPx] の画素の一定の速さで、フレームごとにスクロールし続ける。
 * その向きへスクロールできなくなったら終える。そうでなければ、取り消されるまで続ける。
 */
private suspend fun LazyListState.autoScroll(direction: AutoScrollDirection, distancePerSecondPx: () -> Float) {
    var previousFrame = withFrameNanos { it }
    while (canScrollToward(direction)) {
        val frame = withFrameNanos { it }
        scrollBy(direction.sign * distancePerSecondPx() * (frame - previousFrame) / NANOS_PER_SECOND)
        previousFrame = frame
    }
}

/** [direction] へスクロールできるか。 */
private fun LazyListState.canScrollToward(direction: AutoScrollDirection): Boolean =
    when (direction) {
        AutoScrollDirection.UP -> canScrollBackward
        AutoScrollDirection.DOWN -> canScrollForward
    }

/**
 * 落とす位置の線 [line] を、行の端に、主色で引く。線の左端は、線の深さ 1 つあたり [depthIndent] だけ字下げする。null なら引かない。
 */
@Composable
internal fun Modifier.dropLine(line: DropLine?, depthIndent: Dp = 0.dp): Modifier {
    if (line == null) return this
    val color = MaterialTheme.colorScheme.primary
    return drawWithContent {
        drawContent()
        val thickness = DROP_LINE_THICKNESS.toPx()
        val start = (depthIndent * line.depth).toPx().coerceAtMost(size.width)
        val top = if (line.edge == RowEdge.TOP) 0f else size.height - thickness
        drawRect(color = color, topLeft = Offset(start, top), size = Size(size.width - start, thickness))
    }
}
