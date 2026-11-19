package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.foundation.ScrollState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** スクロールできる長さ（画素）。 */
private const val MAX_VALUE = 3_600

/** 見えている範囲の高さ（画素）。 */
private const val VIEWPORT_SIZE = 400

/** スクロールバーの長さ（画素）。 */
private const val TRACK_PX = 800f

/** つまみの長さの下限（画素）。 */
private const val MIN_THUMB_PX = 48f

/** 比べるときに許す差。 */
private const val TOLERANCE = 0.001f

/** 先頭から [value] だけスクロールした、スクロールできる長さが [maxValue] の中身の様子。 */
private fun metrics(value: Int, maxValue: Int = MAX_VALUE): ScrollStateMetrics = ScrollStateMetrics(maxValue, VIEWPORT_SIZE, value)

class ScrollStateMetricsTest {
    // 中身全体の高さは、スクロールできる長さと見えている範囲の高さの和。スクロールした長さは ScrollState.value
    @Test
    fun lengthsComeFromScrollState() {
        val metrics = metrics(value = MAX_VALUE / 2)

        assertEquals((MAX_VALUE + VIEWPORT_SIZE).toFloat(), metrics.contentPx)
        assertEquals(VIEWPORT_SIZE.toFloat(), metrics.viewportPx)
        assertEquals((MAX_VALUE / 2).toFloat(), metrics.scrolledPx)
        assertEquals(MAX_VALUE.toFloat(), metrics.maxScrollPx)
    }

    // スクロールした割合と、つまみの長さ（見えている範囲の、中身全体に対する割合）を、その長さから求める
    @Test
    fun fractionAndThumbLengthFollowLengths() {
        assertEquals(0.25f, metrics(value = MAX_VALUE / 4).fraction(canScrollBackward = true, canScrollForward = true), TOLERANCE)
        // 中身 4000 画素のうち 400 画素が見えるので、長さは 800 × 0.1
        assertEquals(80f, metrics(value = 0).thumbLengthPx(TRACK_PX, MIN_THUMB_PX), TOLERANCE)
    }

    // 割合の位置は、割合をスクロールできる長さに掛けて丸めたもの。割合は 0 から 1 のあいだに収める
    @Test
    fun positionAtScalesMaxValue() {
        assertEquals(0, metrics(value = 0).positionAt(0f))
        assertEquals(1_800, metrics(value = 0).positionAt(0.5f))
        // 3600 × 0.1001 = 360.36
        assertEquals(360, metrics(value = 0).positionAt(0.1001f))
        assertEquals(MAX_VALUE, metrics(value = 0).positionAt(1f))
        assertEquals(MAX_VALUE, metrics(value = 0).positionAt(1.5f))
        assertEquals(0, metrics(value = 0).positionAt(-0.1f))
    }

    // 中身が見えている範囲に収まれば、割合は 0、つまみはスクロールバーいっぱい、どの割合の位置も先頭
    @Test
    fun contentThatFitsHasNoScroll() {
        val fits = metrics(value = 0, maxValue = 0)

        assertEquals(0f, fits.fraction(canScrollBackward = true, canScrollForward = true))
        assertEquals(TRACK_PX, fits.thumbLengthPx(TRACK_PX, MIN_THUMB_PX))
        assertEquals(0, fits.positionAt(1f))
    }

    // 中身をまだ測っていない ScrollState には、スクロールの様子が無い
    @Test
    fun unmeasuredScrollStateHasNoMetrics() {
        assertNull(ScrollState(initial = 0).scrollMetrics())
    }
}
