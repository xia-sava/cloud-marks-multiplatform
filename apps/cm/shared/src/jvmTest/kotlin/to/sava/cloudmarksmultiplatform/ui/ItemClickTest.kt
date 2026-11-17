package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerType
import to.sava.cloudmarksmultiplatform.browse.SelectionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ダブルクリックを決める時間（ミリ秒）。 */
private const val DOUBLE_CLICK_TIMEOUT = 300L

/** [type] のポインターで、修飾キーを [ctrl]・[shift] のとおりに押して押下した様子。 */
private fun press(type: PointerType, ctrl: Boolean = false, shift: Boolean = false): PointerPress =
    PointerPress(position = Offset.Zero, type = type, secondary = false, ctrlPressed = ctrl, shiftPressed = shift)

class ItemClickTest {
    // マウスでは、修飾キーを押していなければ開き、Ctrl で足す・外し、Shift（Ctrl と一緒でも）で範囲を選ぶ。
    // 長押しでも、選んでいる項目があってもなくても同じ
    @Test
    fun mouseOpensOrSelectsByModifierKeys() {
        val cases = listOf(
            press(PointerType.Mouse) to ItemClick.Open,
            press(PointerType.Mouse, ctrl = true) to ItemClick.Select(SelectionMode.TOGGLE),
            press(PointerType.Mouse, shift = true) to ItemClick.Select(SelectionMode.RANGE),
            press(PointerType.Mouse, ctrl = true, shift = true) to ItemClick.Select(SelectionMode.RANGE),
        )
        cases.forEach { (press, expected) ->
            listOf(false, true).forEach { longPress ->
                listOf(false, true).forEach { hasSelection ->
                    val name = "$press, 長押し: $longPress, 選んでいる項目: $hasSelection"
                    assertEquals(expected, itemClickOf(press, longPress, hasSelection), name)
                }
            }
        }
    }

    // タッチでは、選んでいる項目が無ければ、押下はその項目だけを選び、長押しは足す（選ぶ状態に入る）。開かない
    @Test
    fun touchWithoutSelectionTogglesOnlyOnLongPress() {
        assertEquals(ItemClick.Select(SelectionMode.SINGLE), itemClickOf(press(PointerType.Touch), longPress = false, hasSelection = false))
        assertEquals(ItemClick.Select(SelectionMode.TOGGLE), itemClickOf(press(PointerType.Touch), longPress = true, hasSelection = false))
    }

    // タッチでは、選んでいる項目があるあいだは、押下も長押しも足す・外す
    @Test
    fun touchWithSelectionToggles() {
        assertEquals(ItemClick.Select(SelectionMode.TOGGLE), itemClickOf(press(PointerType.Touch), longPress = false, hasSelection = true))
        assertEquals(ItemClick.Select(SelectionMode.TOGGLE), itemClickOf(press(PointerType.Touch), longPress = true, hasSelection = true))
    }

    // 開いたクリックを離してから、決めた時間より前に押したクリックは開かない。その時間がたってから押したものは開く
    @Test
    fun repeatedOpenWithinTimeoutIsDropped() {
        val filter = RepeatedOpenFilter(timeoutMillis = DOUBLE_CLICK_TIMEOUT)

        assertTrue(filter.accepts(pressedAt = 1_000, releasedAt = 1_050))
        assertFalse(filter.accepts(pressedAt = 1_050 + DOUBLE_CLICK_TIMEOUT - 1, releasedAt = 1_400))
        assertTrue(filter.accepts(pressedAt = 1_400 + DOUBLE_CLICK_TIMEOUT, releasedAt = 1_800))
    }

    // 捨てたクリックも離した時刻を覚えるので、続けて何度押しても、間が空くまでは開かない
    @Test
    fun rapidClicksOpenOnce() {
        val filter = RepeatedOpenFilter(timeoutMillis = DOUBLE_CLICK_TIMEOUT)
        val clicks = (0 until 5).map { n -> n * DOUBLE_CLICK_TIMEOUT / 2 }

        val opened = clicks.map { at -> filter.accepts(pressedAt = at, releasedAt = at + 10) }

        assertEquals(listOf(true, false, false, false, false), opened)
    }

    // ポインターで押していないとき（キーボードなど）は、その項目だけを選ぶ
    @Test
    fun pressWithoutPointerSelectsSingle() {
        listOf(false, true).forEach { hasSelection ->
            assertEquals(ItemClick.Select(SelectionMode.SINGLE), itemClickOf(null, longPress = false, hasSelection), "$hasSelection")
        }
    }
}
