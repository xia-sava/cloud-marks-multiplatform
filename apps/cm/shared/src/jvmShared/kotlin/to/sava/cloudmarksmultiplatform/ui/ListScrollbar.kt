package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.ScrollableState
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

/** [ScrollState.maxValue] の、まだ中身を測っていないことを表す値。 */
private const val UNMEASURED_MAX_VALUE = Int.MAX_VALUE

/**
 * [listState] のリストに添える、縦のスクロールバー。デスクトップはマウスで操作するスクロールバーを、Android はタッチの画面の
 * スクロールバー（[TouchListScrollbar]）を描く。
 *
 * リストと同じ高さの枠の右端に重ねて置く。[modifier] で位置と高さを決める。
 */
@Composable
internal expect fun ListVerticalScrollbar(listState: LazyListState, modifier: Modifier)

/**
 * [scrollState] で縦にスクロールする中身に添える、縦のスクロールバー。[LazyListState] のリストのスクロールバーと同じく、
 * デスクトップはマウスで操作するスクロールバーを、Android はタッチの画面のスクロールバー（[TouchListScrollbar]）を描く。
 * Android のスクロールバーは、つまみを掴めず、スクロールの位置を示すだけにする。
 *
 * 中身を見せる枠と同じ高さの枠の右端に重ねて置く。[modifier] で位置と高さを決める。
 */
@Composable
internal expect fun ListVerticalScrollbar(scrollState: ScrollState, modifier: Modifier)

/**
 * 縦にスクロールするものの、スクロールの様子。スクロールバーのつまみの長さと位置を、ここから求める。
 *
 * @param P スクロールの位置の表し方（[positionAt]）
 */
internal interface ScrollMetrics<out P> {
    /** 中身全体の高さ（画素）。 */
    val contentPx: Float

    /** 見えている範囲の高さ（画素）。 */
    val viewportPx: Float

    /** 中身の上端から、見えている範囲の上端までの長さ（画素）。 */
    val scrolledPx: Float

    /** スクロールできる長さ（画素）。中身が見えている範囲に収まれば 0。 */
    val maxScrollPx: Float get() = (contentPx - viewportPx).coerceAtLeast(0f)

    /**
     * スクロールした割合。0 が先頭、1 が末尾。前へスクロールできなければ（[canScrollBackward] が偽）0、後ろへスクロールできなければ
     * （[canScrollForward] が偽）1 にし、リストの行の高さの見積もり（[ListScrollMetrics]）の違いで、端にいるのに端からずれることを防ぐ。
     */
    fun fraction(canScrollBackward: Boolean, canScrollForward: Boolean): Float =
        when {
            !canScrollBackward -> 0f
            !canScrollForward -> 1f
            maxScrollPx <= 0f -> 0f
            else -> (scrolledPx / maxScrollPx).coerceIn(0f, 1f)
        }

    /** スクロールした割合を [fraction] にするときの、スクロールの位置。 */
    fun positionAt(fraction: Float): P

    /**
     * 長さが [trackPx] のスクロールバーの、つまみの長さ（画素）。見えている範囲の、中身全体に対する割合にし、[minLengthPx] より短くしない。
     * [trackPx] より長くしない。
     */
    fun thumbLengthPx(trackPx: Float, minLengthPx: Float): Float {
        val proportional = if (contentPx > 0f) trackPx * viewportPx / contentPx else trackPx
        return proportional.coerceIn(minOf(minLengthPx, trackPx), trackPx)
    }
}

/**
 * 縦のリストの、スクロールの様子の見積もり。行の高さは、見えている行の高さの平均（[averageItemPx]）とみなす。
 * スクロールの位置は、見えている範囲の先頭に置く行と、その行の上端から見えている範囲の上端までの長さ（画素）で表す。
 *
 * @property itemCount 行の数
 * @property averageItemPx 行の高さの平均（画素）
 * @property viewportPx 見えている範囲の高さ（画素）
 * @property scrolledPx 先頭の行の上端から、見えている範囲の上端までの長さ（画素）
 */
internal data class ListScrollMetrics(
    val itemCount: Int,
    val averageItemPx: Float,
    override val viewportPx: Float,
    override val scrolledPx: Float,
) : ScrollMetrics<Pair<Int, Int>> {
    /** 全部の行の高さ（画素）。 */
    override val contentPx: Float get() = itemCount * averageItemPx

    /**
     * スクロールした割合を [fraction] にするときの、見えている範囲の先頭に置く行と、その行の上端から見えている範囲の上端までの長さ（画素）。
     * [fraction] が 1 以上なら最後の行を先頭に置き、リストが末尾に詰めて見せるようにする。行が無ければ、先頭の行の上端。
     */
    override fun positionAt(fraction: Float): Pair<Int, Int> {
        if (itemCount == 0 || averageItemPx <= 0f) return 0 to 0
        if (fraction >= 1f) return itemCount - 1 to 0
        val target = fraction.coerceAtLeast(0f) * maxScrollPx
        val index = (target / averageItemPx).toInt().coerceIn(0, itemCount - 1)
        return index to (target - index * averageItemPx).roundToInt().coerceAtLeast(0)
    }
}

/**
 * [ScrollState] で縦にスクロールする中身の、スクロールの様子。スクロールの位置は [ScrollState.value] で表す。
 *
 * @property maxValue スクロールできる長さ（画素。[ScrollState.maxValue]）
 * @property viewportSize 見えている範囲の高さ（画素。[ScrollState.viewportSize]）
 * @property value 中身の上端から、見えている範囲の上端までの長さ（画素。[ScrollState.value]）
 */
internal data class ScrollStateMetrics(val maxValue: Int, val viewportSize: Int, val value: Int) : ScrollMetrics<Int> {
    override val contentPx: Float get() = maxValue.toFloat() + viewportSize

    override val viewportPx: Float get() = viewportSize.toFloat()

    override val scrolledPx: Float get() = value.toFloat()

    /** スクロールした割合を [fraction] にするときの [ScrollState.value]。割合は 0 から 1 のあいだに収める。 */
    override fun positionAt(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * maxScrollPx).roundToInt()
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

/** [this] で縦にスクロールする中身の、スクロールの様子。まだ中身を測っていなければ null。 */
internal fun ScrollState.scrollMetrics(): ScrollStateMetrics? =
    ScrollStateMetrics(maxValue = maxValue, viewportSize = viewportSize, value = value).takeIf { it.maxValue != UNMEASURED_MAX_VALUE }

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
 * 見積もり（リストの行の高さの見積もりなど）が変わっても、つまみの長さが変わったり、つまみが指からずれたりしないようにする。
 *
 * @param P スクロールの位置の表し方（[ScrollMetrics.positionAt]）
 */
@Stable
internal class TouchScrollbarThumb<P : Any> {
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
    private var drawnMetrics: ScrollMetrics<P>? = null

    /** 触り始めたときの見積もり。 */
    private var touchedMetrics: ScrollMetrics<P>? = null

    /**
     * 長さが [trackPx] のスクロールバーに描く、つまみの位置と長さ。
     *
     * 触っていなければ、スクロールの様子の見積もり [metrics] から求め（長さは [ScrollMetrics.thumbLengthPx]、位置はスクロールした割合）、
     * 触り始めるときの元として覚える。見積もりが無ければ、スクロールバーいっぱいにする。
     * 触っているあいだは、触り始めたときの長さのまま、指の動きに合わせた位置にする。
     *
     * @param minLengthPx つまみの長さの下限（画素）
     */
    fun geometry(
        metrics: ScrollMetrics<P>?,
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
     * （[ScrollMetrics.positionAt]）を返す。触り始めたときに見積もりが無ければ null。
     */
    fun moveBy(dyPx: Float): P? {
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
 * 出し方と、つまみを掴んで動かす操作は [TouchScrollbar] のとおり。行が見えている範囲に収まるリストには出さない。
 */
@Composable
internal fun TouchListScrollbar(listState: LazyListState, modifier: Modifier) {
    val adapter = remember(listState) { LazyListTouchScrollbarAdapter(listState) }
    TouchScrollbar(adapter, drag = adapter, modifier = modifier)
}

/**
 * タッチの画面の、[scrollState] で縦にスクロールする中身の縦のスクロールバー。中身を見せる枠と同じ高さの枠の右端に重ねて置く
 * （[modifier] で位置と高さを決める）。
 *
 * つまみの長さは、見えている範囲の、中身全体に対する割合にする（[ScrollStateMetrics]）。出し方は、[LazyListState] のリストの
 * スクロールバーと同じく [TouchScrollbar] のとおり。中身が見えている範囲に収まるときは出さない。
 * つまみは掴めず、スクロールの位置を示すだけにする。押下を受けず、つまみの上の押下も下の中身が受ける。中身の部品の右端への押下を、
 * つまみが取らないようにするため。
 */
@Composable
internal fun TouchListScrollbar(scrollState: ScrollState, modifier: Modifier) {
    val adapter = remember(scrollState) { ScrollStateTouchScrollbarAdapter(scrollState) }
    TouchScrollbar(adapter, drag = null, modifier = modifier)
}

/**
 * タッチの画面のスクロールバー（[TouchScrollbar]）が、縦にスクロールするものの様子を読む入口。
 *
 * @param P スクロールの位置の表し方（[ScrollMetrics.positionAt]）
 */
private interface TouchScrollbarAdapter<P : Any> {
    /** スクロールしているか、前と後ろへスクロールできるか。 */
    val state: ScrollableState

    /** いまのスクロールの様子。測れなければ null。 */
    fun metrics(): ScrollMetrics<P>?
}

/**
 * タッチの画面のスクロールバー（[TouchScrollbar]）のつまみを掴んで動かすときに、スクロールさせる入口。
 *
 * @param P スクロールの位置の表し方（[ScrollMetrics.positionAt]）
 */
private interface TouchScrollbarDrag<P : Any> {
    /** 流れているスクロール（慣性で動いているものなど）を取り消す。 */
    fun stopScroll()

    /** [position] へ、すぐにスクロールする。 */
    fun scrollTo(position: P)
}

/**
 * [listState] のリストの、タッチの画面のスクロールバーの入口。様子を読むほか、つまみを掴んで動かすときにスクロールさせる。
 * 位置は、先頭に置く行とその行の上端からの長さ（[ListScrollMetrics]）。
 */
private class LazyListTouchScrollbarAdapter(private val listState: LazyListState) :
    TouchScrollbarAdapter<Pair<Int, Int>>,
    TouchScrollbarDrag<Pair<Int, Int>> {
    override val state: ScrollableState get() = listState

    override fun metrics(): ListScrollMetrics? = listState.layoutInfo.scrollMetrics()

    // いまの位置へのスクロールを求め、流れているスクロールを取り消す
    override fun stopScroll() = listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)

    override fun scrollTo(position: Pair<Int, Int>) = listState.requestScrollToItem(position.first, position.second)
}

/** [scrollState] で縦にスクロールする中身の様子を、タッチの画面のスクロールバーが読む入口（[ScrollStateMetrics]）。 */
private class ScrollStateTouchScrollbarAdapter(private val scrollState: ScrollState) : TouchScrollbarAdapter<Int> {
    override val state: ScrollableState get() = scrollState

    override fun metrics(): ScrollStateMetrics? = scrollState.scrollMetrics()
}

/**
 * タッチの画面の縦のスクロールバー。縦にスクロールするものの様子を [adapter] から読む。
 *
 * つまみの長さは、見えている範囲の、中身全体に対する割合にする（[ScrollMetrics.thumbLengthPx]）。
 * 指で掴めるよう、[TOUCH_SCROLLBAR_MIN_THUMB_LENGTH] より短くしない。スクロールしているあいだと、つまみに触っているあいだだけ出し、
 * どちらもやめてから [TOUCH_SCROLLBAR_HIDE_DELAY_MILLIS] たつと、薄くして消す。前にも後ろにもスクロールできなければ出さない。
 *
 * [drag] があれば、つまみを掴める。出しているあいだは、つまみを掴んで縦に動かすと、つまみの位置の割合まで [drag] でスクロールする。
 * つまみに触ると、慣性で動いているスクロールを止める。触っているあいだは、つまみの長さを変えない（[TouchScrollbarThumb]）。
 * 指を受けるのは、つまみの上の幅 [TOUCH_SCROLLBAR_TARGET_WIDTH] の範囲だけで、つまみの無いところと、消しているあいだのつまみの上の
 * 押下は、下の中身が受ける。
 * [drag] が無ければ、つまみは掴めず、位置を示すだけにする。押下を一切受けず、つまみの上の押下も下の中身が受ける。
 *
 * @param drag つまみを掴んで動かすときに、スクロールさせる入口。null なら、つまみを掴めない
 */
@Composable
private fun <P : Any> TouchScrollbar(adapter: TouchScrollbarAdapter<P>, drag: TouchScrollbarDrag<P>?, modifier: Modifier) {
    val state = adapter.state
    val thumb = remember(adapter) { TouchScrollbarThumb<P>() }
    val active = state.isScrollInProgress || thumb.touching
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
    if (!state.canScrollBackward && !state.canScrollForward && !thumb.touching) return
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Layout(
        content = {
            Box(
                Modifier
                    .then(if (shown && drag != null) Modifier.thumbDrag(drag, thumb) else Modifier)
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
            metrics = adapter.metrics(),
            trackPx = constraints.maxHeight.toFloat(),
            minLengthPx = TOUCH_SCROLLBAR_MIN_THUMB_LENGTH.toPx(),
            canScrollBackward = state.canScrollBackward,
            canScrollForward = state.canScrollForward,
        )
        val width = constraints.maxWidth
        val placeable = measurables.single().measure(Constraints.fixed(width, geometry.lengthPx.roundToInt()))
        layout(width, constraints.maxHeight) { placeable.place(0, geometry.topPx.roundToInt()) }
    }
}

/**
 * つまみを掴んで動かす操作。押すと、慣性で動いていれば止めてから（[TouchScrollbarDrag.stopScroll]）、つまみに触り始め
 * （[TouchScrollbarThumb.touch]）、指が縦に動くたびに、つまみを動かしてその位置へスクロールし（[TouchScrollbarDrag.scrollTo]）、
 * 離すか取り消されたら触るのをやめる。押下と動きは消費し、下の中身には渡さない。
 */
private fun <P : Any> Modifier.thumbDrag(scrollDrag: TouchScrollbarDrag<P>, thumb: TouchScrollbarThumb<P>): Modifier =
    pointerInput(scrollDrag, thumb) {
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            scrollDrag.stopScroll()
            thumb.touch()
            try {
                drag(down.id) { change ->
                    val dy = change.positionChange().y
                    change.consume()
                    thumb.moveBy(dy)?.let(scrollDrag::scrollTo)
                }
            } finally {
                thumb.release()
            }
        }
    }
