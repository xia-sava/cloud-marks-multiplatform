package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** 長い中身の行の数。 */
private const val LONG_ROW_COUNT = 200

/** 見えている範囲に収まる、短い中身の行の数。 */
private const val SHORT_ROW_COUNT = 3

/** 行の高さ。テストの画面の密度は 1 なので、画素と同じ。 */
private val ROW_HEIGHT = 40.dp

/** 押す位置の、窓の右端からの距離（画素）。つまみの指を受ける範囲（右端から 48dp）の中で、つまみを描く位置の上。 */
private const val RIGHT_EDGE_INSET = 8f

/** 色を見る画素の、窓の右端からの距離（画素）。つまみを描く範囲（右端から 4dp から 10dp）の真ん中。 */
private const val THUMB_CENTER_INSET = 7

/** 中身の背景の色。 */
private val BACKGROUND = Color.White

/** つまみの指を受ける範囲より左の、窓の右端からの距離（画素）。 */
private const val LEFT_OF_SCROLLBAR_INSET = 60f

/** 中身をスクロールさせるために、中身の真ん中をなぞる長さ（画素）。 */
private const val SWIPE_DISTANCE = 100f

/** なぞる指を動かすのにかける時間（ミリ秒）。 */
private const val SWIPE_MILLIS = 100L

/** なぞったあと、離すまで指を止めておく時間（ミリ秒）。指を離したときの速さを 0 にし、中身を慣性で動かさない。 */
private const val HOLD_BEFORE_RELEASE_MILLIS = 200L

/**
 * 先頭から [SWIPE_DISTANCE] ほどスクロールしたときの、つまみの上の、窓の上端からの距離（画素）。つまみは上端の近くにある。
 * 行の境目（行の高さの倍数）からずらす。
 */
private const val THUMB_NEAR_TOP_Y = 30f

/** 窓の下端からの、つまみが無いところの距離（画素）。つまみが上端の近くにあるとき。 */
private const val BOTTOM_INSET = 20f

/** リストを慣性で動かすために、素早くなぞる長さ（画素）。 */
private const val FLING_DISTANCE = 300f

/** リストを慣性で動かすために、なぞるのにかける時間（ミリ秒）。 */
private const val FLING_SWIPE_MILLIS = 50L

/** つまみに触ってから、リストが止まったままかを確かめるまで待つ時間（ミリ秒）。慣性で動き続けるなら動く長さ。 */
private const val FLING_WAIT_MILLIS = 500L

/** 時計を進めるときの、1 フレームの長さ（ミリ秒）。 */
private const val FRAME_MILLIS = 16L

/** 操作のあと、描き終えるまで進めるフレームの数。つまみを消し始めるまでの時間より、ずっと短い。 */
private const val SETTLING_FRAMES = 4

/** つまみを消すまで待つ時間（ミリ秒）。消し始めるまでの時間と、薄くする時間より長い。 */
private const val UNTIL_HIDDEN_MILLIS = TOUCH_SCROLLBAR_HIDE_DELAY_MILLIS * 2

/** 押された行の番号を、押された順に覚える。 */
private class Taps {
    val rows = mutableListOf<Int>()
}

/**
 * 行を並べてスクロールさせる中身と、それに重ねるスクロールバー。
 *
 * @property draggable スクロールバーのつまみを掴めるか
 */
private enum class RowsKind(val draggable: Boolean) {
    /** [LazyColumn] のリストと、[LazyListState] を受けるスクロールバー。 */
    LAZY_LIST(draggable = true),

    /** [verticalScroll] で縦にスクロールする [Column] と、[ScrollState] を受けるスクロールバー。 */
    SCROLL_STATE(draggable = false),
}

/** つまみを掴めるスクロールバーを重ねる中身。 */
private val DRAGGABLE_KINDS = RowsKind.entries.filter { it.draggable }

/** つまみを掴めないスクロールバーを重ねる中身。 */
private val NON_DRAGGABLE_KINDS = RowsKind.entries.filterNot { it.draggable }

/**
 * テストの画面に出した、行を並べた中身のスクロールの様子。
 *
 * @property state スクロールしているか、前と後ろへスクロールできるか
 * @property metrics スクロールバーが使う、スクロールの様子
 * @property firstVisibleRow 見えている範囲の上端にある行の番号
 * @property position スクロールの位置。止まったままかを比べるのに使う
 */
private class ScrolledRows(
    val state: ScrollableState,
    val metrics: () -> ScrollMetrics<*>?,
    val firstVisibleRow: () -> Int,
    val position: () -> Any,
)

/** 番号 [n] の行。押されると [taps] に番号を足す。 */
@Composable
private fun TestRow(n: Int, taps: Taps) {
    Box(Modifier.fillMaxWidth().height(ROW_HEIGHT).clickable { taps.rows += n }) { Text("row-$n") }
}

/** 窓いっぱいの、背景が [BACKGROUND] の枠に [content] を描く。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.setRowsContent(content: @Composable BoxScope.() -> Unit) =
    setContent { Box(Modifier.fillMaxSize().background(BACKGROUND), content = content) }

/** [kind] の中身に [rowCount] 行を並べ、右端に [TouchListScrollbar] を重ねた画面を出す。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.showRows(kind: RowsKind, rowCount: Int, taps: Taps): ScrolledRows =
    when (kind) {
        RowsKind.LAZY_LIST -> LazyListState().let { listState ->
            setRowsContent {
                LazyColumn(Modifier.fillMaxSize(), state = listState) { items(rowCount) { n -> TestRow(n, taps) } }
                TouchListScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
            ScrolledRows(
                state = listState,
                metrics = { listState.layoutInfo.scrollMetrics() },
                firstVisibleRow = { listState.firstVisibleItemIndex },
                position = { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset },
            )
        }
        RowsKind.SCROLL_STATE -> ScrollState(initial = 0).let { scrollState ->
            setRowsContent {
                Column(Modifier.fillMaxSize().verticalScroll(scrollState)) { repeat(rowCount) { n -> TestRow(n, taps) } }
                TouchListScrollbar(scrollState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
            ScrolledRows(
                state = scrollState,
                metrics = { scrollState.scrollMetrics() },
                firstVisibleRow = { (scrollState.value / ROW_HEIGHT.value).toInt() },
                position = { scrollState.value },
            )
        }
    }

/**
 * [kind] の中身に [rowCount] 行を並べ、[TouchListScrollbar] を重ねた画面を出して、[block] を走らせる。行は、押されると [Taps] に番号を足す。
 * 描き終えてから、時計を止める（つまみを消すまでの時間を、テストが進める）。
 */
@OptIn(ExperimentalTestApi::class)
private fun runScrollbarTest(kind: RowsKind, rowCount: Int, block: ComposeUiTest.(ScrolledRows, Taps) -> Unit) =
    runComposeUiTest {
        val taps = Taps()
        val rows = showRows(kind, rowCount, taps)
        waitForIdle()
        mainClock.autoAdvance = false
        block(rows, taps)
    }

/**
 * [kinds] の中身のそれぞれで [runScrollbarTest] を走らせる。どれかで確かめたことが違っても（assert と check の失敗）、残りの中身も
 * 走らせ、違った中身ごとに、その中身と違ったことを並べて伝える。
 */
@OptIn(ExperimentalTestApi::class)
private fun runScrollbarTests(
    kinds: List<RowsKind> = RowsKind.entries,
    rowCount: Int = LONG_ROW_COUNT,
    block: ComposeUiTest.(ScrolledRows, Taps) -> Unit,
) {
    val failures = kinds.mapNotNull { kind ->
        try {
            runScrollbarTest(kind, rowCount, block)
            null
        } catch (failure: AssertionError) {
            kind to failure
        } catch (failure: IllegalStateException) {
            kind to failure
        }
    }
    if (failures.isEmpty()) return
    throw AssertionError(failures.joinToString("\n") { (kind, failure) -> "$kind: $failure" }, failures.first().second).apply {
        failures.drop(1).forEach { (_, failure) -> addSuppressed(failure) }
    }
}

/** 時計を [SETTLING_FRAMES] フレームだけ進める。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.settle() = mainClock.advanceTimeBy(FRAME_MILLIS * SETTLING_FRAMES)

/** 窓の上の [position] を、タッチで押す。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.tapAt(position: Offset) {
    onRoot().performTouchInput { click(position) }
    settle()
}

/** 窓の右端の、上端から [y] の位置（つまみを描く位置の上）。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.rightEdgeAt(y: Float): Offset = Offset(onRoot().fetchSemanticsNode().size.width - RIGHT_EDGE_INSET, y)

/** 窓の右端の、上端から [y] の、つまみを描く位置の画素の色。つまみを描いていなければ [BACKGROUND]。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.thumbPixelAt(y: Float): Color {
    val pixels = onRoot().captureToImage().toPixelMap()
    return pixels[pixels.width - THUMB_CENTER_INSET, y.roundToInt()]
}

/** いまのスクロールの様子で描く、つまみの真ん中の、窓の上端からの距離（画素）。テストの画面の密度は 1 なので、dp と画素は同じ。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.thumbCenterY(rows: ScrolledRows): Float {
    val metrics = checkNotNull(rows.metrics())
    val trackPx = onRoot().fetchSemanticsNode().size.height.toFloat()
    val lengthPx = metrics.thumbLengthPx(trackPx, TOUCH_SCROLLBAR_MIN_THUMB_LENGTH.value)
    val fraction = metrics.fraction(rows.state.canScrollBackward, rows.state.canScrollForward)
    return thumbTopPx(fraction, trackPx, lengthPx) + lengthPx / 2
}

/** 窓の下端からの [inset] の位置の y。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.fromBottom(inset: Float): Float = onRoot().fetchSemanticsNode().size.height - inset

/** 中身の真ん中を、上へ [SWIPE_DISTANCE] だけなぞってスクロールさせ、指を止めてから離す。つまみが出る。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.swipeContentUp() {
    onRoot().performTouchInput {
        down(center)
        moveBy(Offset(0f, -SWIPE_DISTANCE), delayMillis = SWIPE_MILLIS)
        advanceEventTime(HOLD_BEFORE_RELEASE_MILLIS)
        up()
    }
    settle()
}

/** 窓の右端の、上端から [fromY] のところを押し、[toY] まで縦に動かして離す。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.dragRightEdge(fromY: Float, toY: Float) {
    val from = rightEdgeAt(fromY)
    onRoot().performTouchInput {
        down(from)
        moveTo(Offset(from.x, toY))
        up()
    }
    settle()
}

/**
 * [LazyListState] のリストに重ねる、つまみを掴めるスクロールバーと、[ScrollState] で縦にスクロールする中身に重ねる、つまみを掴めない
 * スクロールバー。出し入れはどちらも同じに振る舞う。
 */
@OptIn(ExperimentalTestApi::class)
class TouchListScrollbarTest {
    // スクロールしていないあいだはつまみを出さず、右端を押しても、下の中身の行が受ける
    @Test
    fun hiddenScrollbarLetsRowsReceiveTaps() = runScrollbarTests { rows, taps ->
        tapAt(rightEdgeAt(THUMB_NEAR_TOP_Y))

        assertEquals(listOf(0), taps.rows)
        assertEquals(0, rows.firstVisibleRow())
    }

    // スクロールしていないあいだはつまみを描かず、スクロールすると描き、スクロールをやめてしばらくたつと消す
    @Test
    fun thumbShowsWhileScrollingAndHidesAfterDelay() = runScrollbarTests { _, _ ->
        assertEquals(BACKGROUND, thumbPixelAt(THUMB_NEAR_TOP_Y))

        swipeContentUp()
        assertNotEquals(BACKGROUND, thumbPixelAt(THUMB_NEAR_TOP_Y))

        mainClock.advanceTimeBy(UNTIL_HIDDEN_MILLIS)
        assertEquals(BACKGROUND, thumbPixelAt(THUMB_NEAR_TOP_Y))
    }

    // 行が見えている範囲に収まる中身には、なぞってもつまみを描かず、右端の押下は行が受ける
    @Test
    fun shortContentHasNoScrollbar() = runScrollbarTests(rowCount = SHORT_ROW_COUNT) { _, taps ->
        swipeContentUp()
        assertEquals(BACKGROUND, thumbPixelAt(THUMB_NEAR_TOP_Y))

        tapAt(rightEdgeAt(THUMB_NEAR_TOP_Y))

        assertEquals(listOf(0), taps.rows)
    }

    // スクロールすると出るつまみを掴んで下端まで動かすと、リストを末尾までスクロールする。行は押されない
    @Test
    fun draggingThumbToBottomScrollsToEnd() = runScrollbarTests(DRAGGABLE_KINDS) { rows, taps ->
        swipeContentUp()
        assertTrue(rows.state.canScrollBackward)
        assertTrue(rows.state.canScrollForward)

        dragRightEdge(fromY = THUMB_NEAR_TOP_Y, toY = fromBottom(0f))

        assertFalse(rows.state.canScrollForward)
        assertEquals(emptyList(), taps.rows)
    }

    // つまみを真ん中まで動かすと、リストの真ん中あたりまでスクロールする。そこから上端まで動かすと、先頭へ戻る
    @Test
    fun draggingThumbFollowsFinger() = runScrollbarTests(DRAGGABLE_KINDS) { rows, _ ->
        swipeContentUp()
        val middle = onRoot().fetchSemanticsNode().size.height / 2f

        dragRightEdge(fromY = THUMB_NEAR_TOP_Y, toY = THUMB_NEAR_TOP_Y + middle)
        val index = rows.firstVisibleRow()
        assertTrue(index in LONG_ROW_COUNT / 4..LONG_ROW_COUNT * 3 / 4, "$index")

        dragRightEdge(fromY = THUMB_NEAR_TOP_Y + middle, toY = 0f)
        assertEquals(0, rows.firstVisibleRow())
        assertFalse(rows.state.canScrollBackward)
    }

    // つまみを出しているあいだも、つまみの無いところの右端と、つまみより左の押下は、下のリストの行が受ける。つまみの上の押下はつまみが受ける
    @Test
    fun tapsOutsideThumbReachRowsWhileShown() = runScrollbarTests(DRAGGABLE_KINDS) { rows, taps ->
        swipeContentUp()
        val first = rows.firstVisibleRow()

        tapAt(rightEdgeAt(fromBottom(BOTTOM_INSET)))
        tapAt(Offset(onRoot().fetchSemanticsNode().size.width - LEFT_OF_SCROLLBAR_INSET, THUMB_NEAR_TOP_Y))
        tapAt(rightEdgeAt(THUMB_NEAR_TOP_Y))

        assertEquals(first, rows.firstVisibleRow())
        assertEquals(2, taps.rows.size, "${taps.rows}")
        assertTrue(taps.rows[0] > taps.rows[1], "${taps.rows}")
    }

    // スクロールをやめてしばらくたつと、つまみを消し、つまみのあった位置の押下は、下のリストの行が受ける
    @Test
    fun thumbHidesAfterDelay() = runScrollbarTests(DRAGGABLE_KINDS) { rows, taps ->
        swipeContentUp()
        val first = rows.firstVisibleRow()

        mainClock.advanceTimeBy(UNTIL_HIDDEN_MILLIS)
        tapAt(rightEdgeAt(THUMB_NEAR_TOP_Y))

        assertEquals(1, taps.rows.size, "${taps.rows}")
        assertEquals(first, rows.firstVisibleRow())
    }

    // つまみに触っているあいだは、しばらくたっても消さず、そのまま動かせる
    @Test
    fun thumbStaysWhileTouched() = runScrollbarTests(DRAGGABLE_KINDS) { rows, _ ->
        swipeContentUp()
        val from = rightEdgeAt(THUMB_NEAR_TOP_Y)
        val bottom = fromBottom(0f)

        onRoot().performTouchInput { down(from) }
        mainClock.advanceTimeBy(UNTIL_HIDDEN_MILLIS)
        onRoot().performTouchInput {
            moveTo(Offset(from.x, bottom))
            up()
        }
        settle()

        assertFalse(rows.state.canScrollForward)
    }

    // リストが慣性で動いているあいだにつまみに触ると、リストを止める。指を動かさなければ、そのあとも動かない
    @Test
    fun touchingThumbStopsFling() = runScrollbarTests(DRAGGABLE_KINDS) { rows, _ ->
        onRoot().performTouchInput { swipe(center, center - Offset(0f, FLING_DISTANCE), durationMillis = FLING_SWIPE_MILLIS) }
        settle()
        assertTrue(rows.state.isScrollInProgress, "なぞったあとも慣性で動いている")

        onRoot().performTouchInput { down(rightEdgeAt(thumbCenterY(rows))) }
        settle()
        val stopped = rows.position()
        mainClock.advanceTimeBy(FLING_WAIT_MILLIS)

        assertFalse(rows.state.isScrollInProgress)
        assertEquals(stopped, rows.position())
        onRoot().performTouchInput { up() }
    }

    // つまみを掴めないスクロールバーは、つまみを描いているあいだも、つまみの上の押下を受けず、下の中身の行が受ける
    @Test
    fun tapsOnNonDraggableThumbReachRows() = runScrollbarTests(NON_DRAGGABLE_KINDS) { rows, taps ->
        swipeContentUp()
        assertNotEquals(BACKGROUND, thumbPixelAt(THUMB_NEAR_TOP_Y))
        val first = rows.firstVisibleRow()

        tapAt(rightEdgeAt(THUMB_NEAR_TOP_Y))

        assertEquals(1, taps.rows.size, "${taps.rows}")
        assertEquals(first, rows.firstVisibleRow())
    }

    // つまみを掴めないスクロールバーは、つまみの上から下端までなぞっても、つまみの位置へはスクロールしない。なぞりは下の中身が受け、
    // 中身は先頭のほうへ動く
    @Test
    fun draggingNonDraggableThumbDoesNotScrollToThumb() = runScrollbarTests(NON_DRAGGABLE_KINDS) { rows, _ ->
        swipeContentUp()
        val first = rows.firstVisibleRow()

        dragRightEdge(fromY = THUMB_NEAR_TOP_Y, toY = fromBottom(0f))

        assertTrue(rows.state.canScrollForward)
        assertTrue(rows.firstVisibleRow() <= first, "${rows.firstVisibleRow()} <= $first")
    }
}
