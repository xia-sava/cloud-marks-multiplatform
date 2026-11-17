package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 長いリストの行の数。 */
private const val LONG_ROW_COUNT = 200

/** 見えている範囲に収まる、短いリストの行の数。 */
private const val SHORT_ROW_COUNT = 3

/** 行の高さ。テストの画面の密度は 1 なので、画素と同じ。 */
private val ROW_HEIGHT = 40.dp

/** 押す位置の、窓の右端からの距離（画素）。つまみの指を受ける範囲（右端から 48dp）の中で、つまみを描く位置の上。 */
private const val RIGHT_EDGE_INSET = 8f

/** つまみの指を受ける範囲より左の、窓の右端からの距離（画素）。 */
private const val LEFT_OF_SCROLLBAR_INSET = 60f

/** リストをスクロールさせるために、リストの真ん中をなぞる長さ（画素）。 */
private const val SWIPE_DISTANCE = 100f

/** なぞる指を動かすのにかける時間（ミリ秒）。 */
private const val SWIPE_MILLIS = 100L

/** なぞったあと、離すまで指を止めておく時間（ミリ秒）。指を離したときの速さを 0 にし、リストを慣性で動かさない。 */
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
 * [rowCount] 行のリストに [TouchListScrollbar] を重ねた画面を出して、[block] を走らせる。行は、押されると [Taps] に番号を足す。
 * 描き終えてから、時計を止める（つまみを消すまでの時間を、テストが進める）。
 */
@OptIn(ExperimentalTestApi::class)
private fun runScrollbarTest(rowCount: Int = LONG_ROW_COUNT, block: ComposeUiTest.(LazyListState, Taps) -> Unit) =
    runComposeUiTest {
        val listState = LazyListState()
        val taps = Taps()
        setContent {
            Box(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(rowCount) { n ->
                        Box(Modifier.fillMaxWidth().height(ROW_HEIGHT).clickable { taps.rows += n }) { Text("row-$n") }
                    }
                }
                TouchListScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
        waitForIdle()
        mainClock.autoAdvance = false
        block(listState, taps)
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

/** いまのリストの見積もりで描く、つまみの真ん中の、窓の上端からの距離（画素）。テストの画面の密度は 1 なので、dp と画素は同じ。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.thumbCenterY(listState: LazyListState): Float {
    val metrics = checkNotNull(listState.layoutInfo.scrollMetrics())
    val trackPx = onRoot().fetchSemanticsNode().size.height.toFloat()
    val lengthPx = metrics.thumbLengthPx(trackPx, TOUCH_SCROLLBAR_MIN_THUMB_LENGTH.value)
    val fraction = metrics.fraction(listState.canScrollBackward, listState.canScrollForward)
    return thumbTopPx(fraction, trackPx, lengthPx) + lengthPx / 2
}

/** 窓の下端からの [inset] の位置の y。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.fromBottom(inset: Float): Float = onRoot().fetchSemanticsNode().size.height - inset

/** リストの真ん中を、上へ [SWIPE_DISTANCE] だけなぞってスクロールさせ、指を止めてから離す。つまみが出る。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.swipeListUp() {
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

@OptIn(ExperimentalTestApi::class)
class TouchListScrollbarTest {
    // スクロールしていないあいだはつまみを出さず、右端を押しても、下のリストの行が受ける
    @Test
    fun hiddenScrollbarLetsRowsReceiveTaps() = runScrollbarTest { listState, taps ->
        tapAt(rightEdgeAt(THUMB_NEAR_TOP_Y))

        assertEquals(listOf(0), taps.rows)
        assertEquals(0, listState.firstVisibleItemIndex)
    }

    // スクロールすると出るつまみを掴んで下端まで動かすと、リストを末尾までスクロールする。行は押されない
    @Test
    fun draggingThumbToBottomScrollsToEnd() = runScrollbarTest { listState, taps ->
        swipeListUp()
        assertTrue(listState.canScrollBackward)
        assertTrue(listState.canScrollForward)

        dragRightEdge(fromY = THUMB_NEAR_TOP_Y, toY = fromBottom(0f))

        assertFalse(listState.canScrollForward)
        assertEquals(emptyList(), taps.rows)
    }

    // つまみを真ん中まで動かすと、リストの真ん中あたりまでスクロールする。そこから上端まで動かすと、先頭へ戻る
    @Test
    fun draggingThumbFollowsFinger() = runScrollbarTest { listState, _ ->
        swipeListUp()
        val middle = onRoot().fetchSemanticsNode().size.height / 2f

        dragRightEdge(fromY = THUMB_NEAR_TOP_Y, toY = THUMB_NEAR_TOP_Y + middle)
        val index = listState.firstVisibleItemIndex
        assertTrue(index in LONG_ROW_COUNT / 4..LONG_ROW_COUNT * 3 / 4, "$index")

        dragRightEdge(fromY = THUMB_NEAR_TOP_Y + middle, toY = 0f)
        assertEquals(0, listState.firstVisibleItemIndex)
        assertFalse(listState.canScrollBackward)
    }

    // つまみを出しているあいだも、つまみの無いところの右端と、つまみより左の押下は、下のリストの行が受ける
    @Test
    fun tapsOutsideThumbReachRowsWhileShown() = runScrollbarTest { listState, taps ->
        swipeListUp()
        val first = listState.firstVisibleItemIndex

        tapAt(rightEdgeAt(fromBottom(BOTTOM_INSET)))
        tapAt(Offset(onRoot().fetchSemanticsNode().size.width - LEFT_OF_SCROLLBAR_INSET, THUMB_NEAR_TOP_Y))
        tapAt(rightEdgeAt(THUMB_NEAR_TOP_Y))

        assertEquals(first, listState.firstVisibleItemIndex)
        assertEquals(2, taps.rows.size, "${taps.rows}")
        assertTrue(taps.rows[0] > taps.rows[1], "${taps.rows}")
    }

    // スクロールをやめてしばらくたつと、つまみを消し、つまみのあった位置の押下は、下のリストの行が受ける
    @Test
    fun thumbHidesAfterDelay() = runScrollbarTest { listState, taps ->
        swipeListUp()
        val first = listState.firstVisibleItemIndex

        mainClock.advanceTimeBy(UNTIL_HIDDEN_MILLIS)
        tapAt(rightEdgeAt(THUMB_NEAR_TOP_Y))

        assertEquals(1, taps.rows.size, "${taps.rows}")
        assertEquals(first, listState.firstVisibleItemIndex)
    }

    // つまみに触っているあいだは、しばらくたっても消さず、そのまま動かせる
    @Test
    fun thumbStaysWhileTouched() = runScrollbarTest { listState, _ ->
        swipeListUp()
        val from = rightEdgeAt(THUMB_NEAR_TOP_Y)
        val bottom = fromBottom(0f)

        onRoot().performTouchInput { down(from) }
        mainClock.advanceTimeBy(UNTIL_HIDDEN_MILLIS)
        onRoot().performTouchInput {
            moveTo(Offset(from.x, bottom))
            up()
        }
        settle()

        assertFalse(listState.canScrollForward)
    }

    // リストが慣性で動いているあいだにつまみに触ると、リストを止める。指を動かさなければ、そのあとも動かない
    @Test
    fun touchingThumbStopsFling() = runScrollbarTest { listState, _ ->
        onRoot().performTouchInput { swipe(center, center - Offset(0f, FLING_DISTANCE), durationMillis = FLING_SWIPE_MILLIS) }
        settle()
        assertTrue(listState.isScrollInProgress, "なぞったあとも慣性で動いている")

        onRoot().performTouchInput { down(rightEdgeAt(thumbCenterY(listState))) }
        settle()
        val stopped = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        mainClock.advanceTimeBy(FLING_WAIT_MILLIS)

        assertFalse(listState.isScrollInProgress)
        assertEquals(stopped, listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset)
        onRoot().performTouchInput { up() }
    }

    // 行が見えている範囲に収まるリストには、なぞってもつまみを出さず、右端の押下は行が受ける
    @Test
    fun shortListHasNoScrollbar() = runScrollbarTest(rowCount = SHORT_ROW_COUNT) { _, taps ->
        swipeListUp()

        tapAt(rightEdgeAt(THUMB_NEAR_TOP_Y))

        assertEquals(listOf(0), taps.rows)
    }
}
