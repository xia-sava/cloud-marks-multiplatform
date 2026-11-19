package to.sava.cloudmarksmultiplatform.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** スクロールバーの長さ（画素）。 */
private const val TRACK_PX = 800f

/** つまみの長さの下限（画素）。 */
private const val MIN_LENGTH_PX = 48f

/** 行の高さ（画素）。 */
private const val ITEM_PX = 40f

/** 見えている範囲の高さ（画素）。 */
private const val VIEWPORT_PX = 400f

/** 比べるときに許す差。 */
private const val TOLERANCE = 0.001f

/** [itemCount] 行のリストを、スクロールできる長さの [fraction] の割合だけスクロールしたときの見積もり。 */
private fun metrics(itemCount: Int, fraction: Float): ListScrollMetrics {
    val maxScrollPx = itemCount * ITEM_PX - VIEWPORT_PX
    return ListScrollMetrics(itemCount, ITEM_PX, VIEWPORT_PX, scrolledPx = maxScrollPx * fraction)
}

/** スクロールバーの長さ [TRACK_PX]、どちらへもスクロールできるときの、[this] のつまみ。 */
private fun <P : Any> TouchScrollbarThumb<P>.geometryOf(metrics: ScrollMetrics<P>?): ScrollbarThumbGeometry =
    geometry(metrics, TRACK_PX, MIN_LENGTH_PX, canScrollBackward = true, canScrollForward = true)

class TouchScrollbarThumbTest {
    // 触っていなければ、つまみの長さは見えている範囲の割合、位置はスクロールした割合から求める。見積もりが無ければスクロールバーいっぱい
    @Test
    fun geometryFollowsMetricsWhileNotTouched() {
        val thumb = TouchScrollbarThumb<Pair<Int, Int>>()

        // 100 行（4000 画素）のうち 400 画素が見えるので、長さは 800 × 0.1 = 80。半分スクロールしていれば、上端は (800 - 80) / 2
        assertEquals(ScrollbarThumbGeometry(topPx = 360f, lengthPx = 80f), thumb.geometryOf(metrics(itemCount = 100, fraction = 0.5f)))
        // 200 行なら、長さは 40 だが、下限の 48 にする
        assertEquals(MIN_LENGTH_PX, thumb.geometryOf(metrics(itemCount = 200, fraction = 0f)).lengthPx)
        assertEquals(ScrollbarThumbGeometry(topPx = 0f, lengthPx = TRACK_PX), thumb.geometryOf(null))
    }

    // 触っているあいだは、見積もりが変わっても、触り始めたときのつまみの長さと位置を保つ。離すと、また見積もりから求める
    @Test
    fun geometryKeepsLengthWhileTouched() {
        val thumb = TouchScrollbarThumb<Pair<Int, Int>>()
        val before = thumb.geometryOf(metrics(itemCount = 100, fraction = 0.5f))

        thumb.touch()

        assertEquals(before, thumb.geometryOf(metrics(itemCount = 50, fraction = 0.9f)))
        thumb.release()
        // 50 行（2000 画素）のうち 400 画素が見えるので、長さは 800 × 0.2
        assertEquals(160f, thumb.geometryOf(metrics(itemCount = 50, fraction = 0.9f)).lengthPx, TOLERANCE)
    }

    // 指を動かすと、触り始めたときの長さのまま、つまみを動かした分だけ動かし（スクロールバーの中に収める）、その位置の割合の
    // スクロールの位置を、触り始めたときの見積もりで返す
    @Test
    fun moveByMovesThumbAndReturnsPosition() {
        val thumb = TouchScrollbarThumb<Pair<Int, Int>>()
        thumb.geometryOf(metrics(itemCount = 100, fraction = 0f))
        thumb.touch()

        // 長さ 80 のつまみが動ける範囲は 720。上端を 360 にすると割合は 0.5 で、3600 × 0.5 = 1800 は 45 行目の上端
        assertEquals(45 to 0, thumb.moveBy(360f))
        assertEquals(ScrollbarThumbGeometry(topPx = 360f, lengthPx = 80f), thumb.geometryOf(metrics(itemCount = 300, fraction = 0.1f)))

        assertEquals(99 to 0, thumb.moveBy(TRACK_PX))
        assertEquals(720f, thumb.geometryOf(null).topPx)
        assertEquals(0 to 0, thumb.moveBy(-TRACK_PX * 2))
        assertEquals(0f, thumb.geometryOf(null).topPx)
    }

    // 触り始めたときに見積もりが無ければ（見えている行が無ければ）、指を動かしてもスクロールの位置を返さない
    @Test
    fun moveByWithoutMetricsReturnsNull() {
        val thumb = TouchScrollbarThumb<Pair<Int, Int>>()
        thumb.geometryOf(null)
        thumb.touch()

        assertNull(thumb.moveBy(100f))
    }
}
