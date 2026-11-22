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
    PointerPress(position = Offset.Zero, type = type, secondary = false, ctrlPressed = ctrl, shiftPressed = shift, pressedAt = 0L)

class ItemClickTest {
    // マウスでは、修飾キーを押していなければ開き、Ctrl で足す・外し、Shift（Ctrl と一緒でも）で範囲を選ぶ。
    // 長押しでも、選んでいる項目があってもなくても、タッチの画面の振る舞いで動かしていても同じ
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
                    listOf(false, true).forEach { touchScreen ->
                        val name = "$press, 長押し: $longPress, 選んでいる項目: $hasSelection, タッチの画面: $touchScreen"
                        assertEquals(expected, itemClickOf(press, longPress, hasSelection, touchScreen), name)
                    }
                }
            }
        }
    }

    // タッチの画面の振る舞いで動かしていなければ、タッチで選んでいる項目が無いとき、押下はその項目だけを選び、
    // 長押しは足す（選ぶ状態に入る）。開かない
    @Test
    fun touchWithoutSelectionTogglesOnlyOnLongPress() {
        assertEquals(
            ItemClick.Select(SelectionMode.SINGLE),
            itemClickOf(press(PointerType.Touch), longPress = false, hasSelection = false, touchScreen = false),
        )
        assertEquals(
            ItemClick.Select(SelectionMode.TOGGLE),
            itemClickOf(press(PointerType.Touch), longPress = true, hasSelection = false, touchScreen = false),
        )
    }

    // タッチの画面の振る舞いで動かしていれば、タッチで選んでいる項目が無いとき、押下は開き、長押しは項目のメニューを出す（選ぶ状態には入らない）。
    // ペンでも同じ
    @Test
    fun touchScreenTapOpensAndLongPressShowsMenuWithoutSelection() {
        listOf(PointerType.Touch, PointerType.Stylus).forEach { type ->
            assertEquals(ItemClick.Open, itemClickOf(press(type), longPress = false, hasSelection = false, touchScreen = true), "$type")
            assertEquals(ItemClick.Menu, itemClickOf(press(type), longPress = true, hasSelection = false, touchScreen = true), "$type")
        }
    }

    // タッチでは、選んでいる項目があるあいだは、押下も長押しも足す・外す。タッチの画面の振る舞いで動かしていても同じ
    @Test
    fun touchWithSelectionToggles() {
        listOf(false, true).forEach { touchScreen ->
            listOf(false, true).forEach { longPress ->
                assertEquals(
                    ItemClick.Select(SelectionMode.TOGGLE),
                    itemClickOf(press(PointerType.Touch), longPress, hasSelection = true, touchScreen),
                    "長押し: $longPress, タッチの画面: $touchScreen",
                )
            }
        }
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

    // タッチの画面の振る舞いで動かしていなければ、ポインターで押していないとき（キーボードなど）は、その項目だけを選ぶ
    @Test
    fun pressWithoutPointerSelectsSingle() {
        listOf(false, true).forEach { hasSelection ->
            assertEquals(
                ItemClick.Select(SelectionMode.SINGLE),
                itemClickOf(null, longPress = false, hasSelection, touchScreen = false),
                "選んでいる項目: $hasSelection",
            )
        }
    }

    // 検索の結果の行は、マウスでは修飾キーを押していても、長押しでも、タッチの画面の振る舞いで動かしていても開く。選ばない
    @Test
    fun searchResultMouseAlwaysOpens() {
        val presses = listOf(
            press(PointerType.Mouse),
            press(PointerType.Mouse, ctrl = true),
            press(PointerType.Mouse, shift = true),
            press(PointerType.Mouse, ctrl = true, shift = true),
        )
        presses.forEach { press ->
            listOf(false, true).forEach { longPress ->
                listOf(false, true).forEach { touchScreen ->
                    val name = "$press, 長押し: $longPress, タッチの画面: $touchScreen"
                    assertEquals(ItemClick.Open, searchResultClickOf(press, longPress, touchScreen), name)
                }
            }
        }
    }

    // 検索の結果の行は、タッチの画面の振る舞いで動かしていれば、マウスでない押下（ポインターで押していないときを含む）の長押しで
    // 項目のメニューを出し、押下で開く。ペンでも同じ
    @Test
    fun searchResultTouchScreenLongPressShowsMenu() {
        listOf(press(PointerType.Touch), press(PointerType.Stylus), null).forEach { press ->
            assertEquals(ItemClick.Open, searchResultClickOf(press, longPress = false, touchScreen = true), "$press")
            assertEquals(ItemClick.Menu, searchResultClickOf(press, longPress = true, touchScreen = true), "$press")
        }
    }

    // 検索の結果の行は、タッチの画面の振る舞いで動かしていなければ、タッチの押下も長押しも、ポインターで押していないときも開く
    @Test
    fun searchResultWithoutTouchScreenOpens() {
        listOf(press(PointerType.Touch), null).forEach { press ->
            listOf(false, true).forEach { longPress ->
                assertEquals(ItemClick.Open, searchResultClickOf(press, longPress, touchScreen = false), "$press, 長押し: $longPress")
            }
        }
    }

    // タッチの画面の振る舞いで動かしていれば、ポインターで押していないとき（画面の読み上げの操作やキーボードなど）も、タッチと同じに扱う。
    // 選んでいる項目が無ければ押下は開き、長押しは項目のメニューを出す。選んでいる項目があるあいだは、押下も長押しも足す・外す
    @Test
    fun touchScreenPressWithoutPointerActsLikeTouch() {
        val cases = listOf(
            Pair(false, false) to ItemClick.Open,
            Pair(true, false) to ItemClick.Menu,
            Pair(false, true) to ItemClick.Select(SelectionMode.TOGGLE),
            Pair(true, true) to ItemClick.Select(SelectionMode.TOGGLE),
        )

        cases.forEach { (input, expected) ->
            val (longPress, hasSelection) = input
            assertEquals(
                expected,
                itemClickOf(null, longPress, hasSelection, touchScreen = true),
                "長押し: $longPress, 選んでいる項目: $hasSelection",
            )
        }
    }
}
