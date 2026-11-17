package to.sava.cloudmarksmultiplatform.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** 行の数。 */
private const val ITEM_COUNT = 100

/** 行の高さ（画素）。 */
private const val ITEM_PX = 40f

/** 見えている範囲の高さ（画素）。10 行ぶん。 */
private const val VIEWPORT_PX = 400f

/** 全部の行の高さから、見えている範囲を引いた、スクロールできる長さ（画素）。 */
private const val MAX_SCROLL_PX = ITEM_COUNT * ITEM_PX - VIEWPORT_PX

/** つまみの長さの下限（画素）。 */
private const val MIN_THUMB_PX = 48f

/** 比べるときに許す差。 */
private const val TOLERANCE = 0.001f

/** 先頭から [scrolledPx] だけスクロールした、[ITEM_COUNT] 行のリストの見積もり。 */
private fun metrics(scrolledPx: Float, itemCount: Int = ITEM_COUNT): ListScrollMetrics =
    ListScrollMetrics(itemCount = itemCount, averageItemPx = ITEM_PX, viewportPx = VIEWPORT_PX, scrolledPx = scrolledPx)

class ListScrollMetricsTest {
    // スクロールした割合は、スクロールした長さの、スクロールできる長さに対する割合
    @Test
    fun fractionIsScrolledOverMaxScroll() {
        assertEquals(0.5f, metrics(MAX_SCROLL_PX / 2).fraction(canScrollBackward = true, canScrollForward = true), TOLERANCE)
        assertEquals(0.25f, metrics(MAX_SCROLL_PX / 4).fraction(canScrollBackward = true, canScrollForward = true), TOLERANCE)
    }

    // 前へスクロールできなければ 0、後ろへスクロールできなければ 1 にする。見積もりが端からずれていても、端に置く
    @Test
    fun fractionSnapsToEndsWhenListCannotScroll() {
        assertEquals(0f, metrics(MAX_SCROLL_PX / 2).fraction(canScrollBackward = false, canScrollForward = true))
        assertEquals(1f, metrics(MAX_SCROLL_PX / 2).fraction(canScrollBackward = true, canScrollForward = false))
    }

    // 見積もりがスクロールできる長さを越えても、割合は 0 から 1 のあいだに収める。行が見えている範囲に収まれば 0
    @Test
    fun fractionStaysInRange() {
        assertEquals(1f, metrics(MAX_SCROLL_PX * 2).fraction(canScrollBackward = true, canScrollForward = true))
        assertEquals(0f, metrics(-ITEM_PX).fraction(canScrollBackward = true, canScrollForward = true))
        assertEquals(0f, metrics(0f, itemCount = 5).fraction(canScrollBackward = true, canScrollForward = true))
    }

    // 割合の位置は、スクロールする長さを行の高さで割った行と、その行の中での長さ。0 なら先頭の行の上端
    @Test
    fun positionAtSplitsTargetIntoItemAndOffset() {
        assertEquals(0 to 0, metrics(0f).positionAt(0f))
        // 3600 × 0.5 = 1800 は、45 行目の上端
        assertEquals(45 to 0, metrics(0f).positionAt(0.5f))
        // 3600 × 0.1 = 360 は、9 行目の上端から 0
        assertEquals(9 to 0, metrics(0f).positionAt(0.1f))
        // 3600 × 0.105 = 378 は、9 行目の上端から 18
        assertEquals(9 to 18, metrics(0f).positionAt(0.105f))
    }

    // 割合が 1 以上なら、最後の行を先頭に置く（リストが末尾に詰めて見せる）。行が無ければ、先頭の行の上端
    @Test
    fun positionAtEndAndWithoutItems() {
        assertEquals(ITEM_COUNT - 1 to 0, metrics(0f).positionAt(1f))
        assertEquals(0 to 0, metrics(0f, itemCount = 0).positionAt(0.5f))
    }

    // つまみの長さは、見えている範囲の、全部の行に対する割合。下限より短くせず、スクロールバーより長くしない
    @Test
    fun thumbLengthIsProportionalWithinBounds() {
        val trackPx = 800f
        assertEquals(trackPx * VIEWPORT_PX / (ITEM_COUNT * ITEM_PX), metrics(0f).thumbLengthPx(trackPx, minLengthPx = 0f), TOLERANCE)
        assertEquals(MIN_THUMB_PX, metrics(0f, itemCount = 10_000).thumbLengthPx(trackPx, MIN_THUMB_PX))
        assertEquals(trackPx, metrics(0f, itemCount = 5).thumbLengthPx(trackPx, MIN_THUMB_PX))
        assertEquals(30f, metrics(0f, itemCount = 10_000).thumbLengthPx(trackPx = 30f, MIN_THUMB_PX))
    }

    // つまみの上端は、割合をつまみの動ける範囲に掛けたもの。上端から割合へは、その逆。動ける範囲が無ければ割合は 0
    @Test
    fun thumbTopAndFractionAreInverse() {
        val trackPx = 500f
        val lengthPx = 100f
        assertEquals(200f, thumbTopPx(0.5f, trackPx, lengthPx), TOLERANCE)
        assertEquals(0.5f, fractionAtThumbTop(200f, trackPx, lengthPx), TOLERANCE)
        assertEquals(1f, fractionAtThumbTop(trackPx, trackPx, lengthPx))
        assertEquals(0f, fractionAtThumbTop(-10f, trackPx, lengthPx))
        assertEquals(0f, fractionAtThumbTop(10f, trackPx, trackPx))
    }
}
