package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** タッチの画面のスクロールバーの、つまみの、指を受ける幅。 */
private val TOUCH_SCROLLBAR_TARGET_WIDTH = 48.dp

/** タッチの画面のスクロールバーの、つまみの描く幅。 */
private val TOUCH_SCROLLBAR_THUMB_WIDTH = 6.dp

/** タッチの画面のスクロールバーの、つまみと枠の右端とのあいだ。 */
private val TOUCH_SCROLLBAR_END_PADDING = 4.dp

/** タッチの画面のスクロールバーの、つまみの長さの下限。指で掴める長さ。 */
internal val TOUCH_SCROLLBAR_MIN_THUMB_LENGTH = 48.dp

/** スクロールもつまみに触ることもやめてから、タッチの画面のスクロールバーを消し始めるまでの時間（ミリ秒）。 */
internal const val TOUCH_SCROLLBAR_HIDE_DELAY_MILLIS = 1_500L

/** タッチの画面のスクロールバーが、出るときと消えるときに、透明度を変えるのにかける時間（ミリ秒）。 */
private const val TOUCH_SCROLLBAR_FADE_MILLIS = 300

/** 消したタッチの画面のスクロールバーの、つまみの不透明度。 */
private const val HIDDEN_ALPHA = 0f

/** 出しているタッチの画面のスクロールバーの、つまみの不透明度。 */
private const val SHOWN_ALPHA = 1f

/**
 * [listState] のリストに添える、縦のスクロールバー。デスクトップはマウスで操作するスクロールバーを、Android はタッチの画面の
 * スクロールバー（[TouchListScrollbar]）を描く。
 *
 * リストと同じ高さの枠の右端に重ねて置く。[modifier] で位置と高さを決める。
 */
@Composable
internal expect fun ListVerticalScrollbar(listState: LazyListState, modifier: Modifier)

/**
 * 縦のリストの、スクロールの様子の見積もり。行の高さは、見えている行の高さの平均（[averageItemPx]）とみなす。
 *
 * @property itemCount 行の数
 * @property averageItemPx 行の高さの平均（画素）
 * @property viewportPx 見えている範囲の高さ（画素）
 * @property scrolledPx 先頭の行の上端から、見えている範囲の上端までの長さ（画素）
 */
internal data class ListScrollMetrics(val itemCount: Int, val averageItemPx: Float, val viewportPx: Float, val scrolledPx: Float) {
    /** 全部の行の高さ（画素）。 */
    val contentPx: Float get() = itemCount * averageItemPx

    /** スクロールできる長さ（画素）。行が見えている範囲に収まれば 0。 */
    val maxScrollPx: Float get() = (contentPx - viewportPx).coerceAtLeast(0f)

    /**
     * スクロールした割合。0 が先頭、1 が末尾。前へスクロールできなければ（[canScrollBackward] が偽）0、後ろへスクロールできなければ
     * （[canScrollForward] が偽）1 にし、行の高さの見積もりの違いで、端にいるのに端からずれることを防ぐ。
     */
    fun fraction(canScrollBackward: Boolean, canScrollForward: Boolean): Float =
        when {
            !canScrollBackward -> 0f
            !canScrollForward -> 1f
            maxScrollPx <= 0f -> 0f
            else -> (scrolledPx / maxScrollPx).coerceIn(0f, 1f)
        }

    /**
     * スクロールした割合を [fraction] にするときの、見えている範囲の先頭に置く行と、その行の上端から見えている範囲の上端までの長さ（画素）。
     * [fraction] が 1 以上なら最後の行を先頭に置き、リストが末尾に詰めて見せるようにする。行が無ければ、先頭の行の上端。
     */
    fun positionAt(fraction: Float): Pair<Int, Int> {
        if (itemCount == 0 || averageItemPx <= 0f) return 0 to 0
        if (fraction >= 1f) return itemCount - 1 to 0
        val target = fraction.coerceAtLeast(0f) * maxScrollPx
        val index = (target / averageItemPx).toInt().coerceIn(0, itemCount - 1)
        return index to (target - index * averageItemPx).roundToInt().coerceAtLeast(0)
    }

    /**
     * 長さが [trackPx] のスクロールバーの、つまみの長さ（画素）。見えている範囲の、全部の行に対する割合にし、[minLengthPx] より短くしない。
     * [trackPx] より長くしない。
     */
    fun thumbLengthPx(trackPx: Float, minLengthPx: Float): Float {
        val proportional = if (contentPx > 0f) trackPx * viewportPx / contentPx else trackPx
        return proportional.coerceIn(minOf(minLengthPx, trackPx), trackPx)
    }
}

/** 長さが [trackPx] のスクロールバーの、長さが [thumbLengthPx] のつまみを、スクロールした割合 [fraction] の位置に置くときの上端（画素）。 */
internal fun thumbTopPx(fraction: Float, trackPx: Float, thumbLengthPx: Float): Float =
    fraction.coerceIn(0f, 1f) * (trackPx - thumbLengthPx).coerceAtLeast(0f)

/** 長さが [trackPx] のスクロールバーの、長さが [thumbLengthPx] のつまみの上端が [topPx] のときの、スクロールした割合。 */
internal fun fractionAtThumbTop(topPx: Float, trackPx: Float, thumbLengthPx: Float): Float {
    val range = trackPx - thumbLengthPx
    return if (range > 0f) (topPx / range).coerceIn(0f, 1f) else 0f
}

/** [this] のリストの、スクロールの様子の見積もり。見えている行が無ければ null。 */
internal fun LazyListLayoutInfo.scrollMetrics(): ListScrollMetrics? {
    val visible = visibleItemsInfo
    val first = visible.firstOrNull() ?: return null
    val averageItemPx = visible.sumOf { it.size + mainAxisItemSpacing }.toFloat() / visible.size
    return ListScrollMetrics(
        itemCount = totalItemsCount,
        averageItemPx = averageItemPx,
        viewportPx = (viewportEndOffset - viewportStartOffset).toFloat(),
        scrolledPx = first.index * averageItemPx - first.offset,
    )
}

/**
 * つまみの位置と長さ。
 *
 * @property topPx つまみの上端の、スクロールバーの上端からの距離（画素）
 * @property lengthPx つまみの長さ（画素）
 */
internal data class ScrollbarThumbGeometry(val topPx: Float, val lengthPx: Float)

/**
 * タッチの画面のスクロールバーの、つまみに触っている様子と、最後に描いたつまみ。
 * 触っているあいだは、触り始めたときのつまみの長さと見積もりを使い続け、指の動きからスクロールの位置を求める。指が動くあいだに
 * 行の高さの見積もりが変わっても、つまみの長さが変わったり、つまみが指からずれたりしないようにする。
 */
@Stable
internal class TouchScrollbarThumb {
    /** つまみに触っているか。 */
    var touching: Boolean by mutableStateOf(false)
        private set

    /** 触っているあいだの、つまみの上端（画素）。指の動きに合わせる。 */
    private var touchedTopPx: Float by mutableFloatStateOf(0f)

    /**
     * 最後に、触っていないときに描いたつまみと、そのときのスクロールバーの長さ（画素）と見積もり。触り始めるときの元にする。
     * 描き直しには使わないので、状態にしない。
     */
    private var drawn = ScrollbarThumbGeometry(topPx = 0f, lengthPx = 0f)
    private var drawnTrackPx = 0f
    private var drawnMetrics: ListScrollMetrics? = null

    /** 触り始めたときの見積もり。 */
    private var touchedMetrics: ListScrollMetrics? = null

    /**
     * 長さが [trackPx] のスクロールバーに描く、つまみの位置と長さ。
     *
     * 触っていなければ、リストの見積もり [metrics] から求め（長さは [ListScrollMetrics.thumbLengthPx]、位置はスクロールした割合）、
     * 触り始めるときの元として覚える。見積もりが無ければ、スクロールバーいっぱいにする。
     * 触っているあいだは、触り始めたときの長さのまま、指の動きに合わせた位置にする。
     *
     * @param minLengthPx つまみの長さの下限（画素）
     */
    fun geometry(
        metrics: ListScrollMetrics?,
        trackPx: Float,
        minLengthPx: Float,
        canScrollBackward: Boolean,
        canScrollForward: Boolean,
    ): ScrollbarThumbGeometry {
        if (touching) return ScrollbarThumbGeometry(touchedTopPx, drawn.lengthPx)
        val lengthPx = metrics?.thumbLengthPx(trackPx, minLengthPx) ?: trackPx
        val topPx = metrics?.let { thumbTopPx(it.fraction(canScrollBackward, canScrollForward), trackPx, lengthPx) } ?: 0f
        return ScrollbarThumbGeometry(topPx, lengthPx).also {
            drawn = it
            drawnTrackPx = trackPx
            drawnMetrics = metrics
        }
    }

    /** つまみに触り始める。最後に描いたつまみと見積もりを、指の動きを測る元にする。 */
    fun touch() {
        touching = true
        touchedTopPx = drawn.topPx
        touchedMetrics = drawnMetrics
    }

    /**
     * 指が [dyPx] だけ縦に動いたことを受け、つまみを動かす（スクロールバーの中に収める）。つまみの位置に合わせたスクロールの位置
     * （[ListScrollMetrics.positionAt]）を返す。触り始めたときに見積もりが無ければ null。
     */
    fun moveBy(dyPx: Float): Pair<Int, Int>? {
        val metrics = touchedMetrics ?: return null
        touchedTopPx = (touchedTopPx + dyPx).coerceIn(0f, (drawnTrackPx - drawn.lengthPx).coerceAtLeast(0f))
        return metrics.positionAt(fractionAtThumbTop(touchedTopPx, drawnTrackPx, drawn.lengthPx))
    }

    /** つまみから指を離す。 */
    fun release() {
        touching = false
        touchedMetrics = null
    }
}

/**
 * タッチの画面の、[listState] のリストの縦のスクロールバー。リストと同じ高さの枠の右端に重ねて置く（[modifier] で位置と高さを決める）。
 *
 * つまみの長さは、見えている範囲の、全部の行に対する割合にする（行の高さは、見えている行の平均で見積もる。[ListScrollMetrics]）。
 * 指で掴めるよう、[TOUCH_SCROLLBAR_MIN_THUMB_LENGTH] より短くしない。リストがスクロールしているあいだと、つまみに触っているあいだだけ出し、
 * どちらもやめてから [TOUCH_SCROLLBAR_HIDE_DELAY_MILLIS] たつと、薄くして消す。行が見えている範囲に収まるリストには出さない。
 *
 * 出しているあいだは、つまみを掴んで縦に動かすと、つまみの位置の割合までリストをスクロールする。つまみに触ると、慣性で動いている
 * リストを止める。触っているあいだは、つまみの長さを変えない（[TouchScrollbarThumb]）。指を受けるのは、つまみの上の
 * 幅 [TOUCH_SCROLLBAR_TARGET_WIDTH] の範囲だけで、つまみの無いところと、消しているあいだのつまみの上の押下は、下のリストの行が受ける。
 */
@Composable
internal fun TouchListScrollbar(listState: LazyListState, modifier: Modifier) {
    val thumb = remember(listState) { TouchScrollbarThumb() }
    val active = listState.isScrollInProgress || thumb.touching
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (active) {
            shown = true
            return@LaunchedEffect
        }
        delay(TOUCH_SCROLLBAR_HIDE_DELAY_MILLIS)
        shown = false
    }
    val alpha by animateFloatAsState(
        targetValue = if (shown) SHOWN_ALPHA else HIDDEN_ALPHA,
        animationSpec = tween(TOUCH_SCROLLBAR_FADE_MILLIS),
        label = "scrollbar alpha",
    )
    if (!listState.canScrollBackward && !listState.canScrollForward && !thumb.touching) return
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Layout(
        content = {
            Box(
                Modifier
                    .then(if (shown) Modifier.thumbDrag(listState, thumb) else Modifier)
                    .graphicsLayer { this.alpha = alpha },
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = TOUCH_SCROLLBAR_END_PADDING)
                        .width(TOUCH_SCROLLBAR_THUMB_WIDTH)
                        .fillMaxHeight()
                        .background(color, RoundedCornerShape(TOUCH_SCROLLBAR_THUMB_WIDTH / 2)),
                )
            }
        },
        modifier = modifier.width(TOUCH_SCROLLBAR_TARGET_WIDTH),
    ) { measurables, constraints ->
        val geometry = thumb.geometry(
            metrics = listState.layoutInfo.scrollMetrics(),
            trackPx = constraints.maxHeight.toFloat(),
            minLengthPx = TOUCH_SCROLLBAR_MIN_THUMB_LENGTH.toPx(),
            canScrollBackward = listState.canScrollBackward,
            canScrollForward = listState.canScrollForward,
        )
        val width = constraints.maxWidth
        val placeable = measurables.single().measure(Constraints.fixed(width, geometry.lengthPx.roundToInt()))
        layout(width, constraints.maxHeight) { placeable.place(0, geometry.topPx.roundToInt()) }
    }
}

/**
 * つまみを掴んで動かす操作。押すと、リストが慣性で動いていれば止めてから、つまみに触り始め（[TouchScrollbarThumb.touch]）、
 * 指が縦に動くたびに、つまみを動かして [listState] をその位置へスクロールし、離すか取り消されたら触るのをやめる。
 * 押下と動きは消費し、下のリストには渡さない。
 */
private fun Modifier.thumbDrag(listState: LazyListState, thumb: TouchScrollbarThumb): Modifier =
    pointerInput(listState, thumb) {
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            // いまの位置へのスクロールを求め、流れているスクロールを取り消す
            listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
            thumb.touch()
            try {
                drag(down.id) { change ->
                    val dy = change.positionChange().y
                    change.consume()
                    thumb.moveBy(dy)?.let { (index, offset) -> listState.requestScrollToItem(index, offset) }
                }
            } finally {
                thumb.release()
            }
        }
    }
