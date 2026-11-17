package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerType
import to.sava.cloudmarksmultiplatform.browse.SelectionMode
import kotlin.test.Test
import kotlin.test.assertEquals

/** [type] のポインターで、修飾キーを [ctrl]・[shift] のとおりに押して押下した様子。 */
private fun press(type: PointerType, ctrl: Boolean = false, shift: Boolean = false): PointerPress =
    PointerPress(position = Offset.Zero, type = type, secondary = false, ctrlPressed = ctrl, shiftPressed = shift)

class ItemSelectionModeTest {
    // マウスでは、長押しでもふつうのクリックと同じに、修飾キーから決める。選んでいる項目があってもなくても同じ
    @Test
    fun mouseFollowsModifierKeysEvenOnLongPress() {
        listOf(false, true).forEach { longPress ->
            listOf(false, true).forEach { hasSelection ->
                val name = "長押し: $longPress, 選んでいる項目: $hasSelection"
                assertEquals(SelectionMode.SINGLE, itemSelectionModeOf(press(PointerType.Mouse), longPress, hasSelection), name)
                assertEquals(
                    SelectionMode.TOGGLE,
                    itemSelectionModeOf(press(PointerType.Mouse, ctrl = true), longPress, hasSelection),
                    name,
                )
                assertEquals(
                    SelectionMode.RANGE,
                    itemSelectionModeOf(press(PointerType.Mouse, shift = true), longPress, hasSelection),
                    name,
                )
            }
        }
    }

    // タッチでは、選んでいる項目が無ければ、押下はその項目だけを選び、長押しは足す（選ぶ状態に入る）
    @Test
    fun touchWithoutSelectionTogglesOnlyOnLongPress() {
        assertEquals(SelectionMode.SINGLE, itemSelectionModeOf(press(PointerType.Touch), longPress = false, hasSelection = false))
        assertEquals(SelectionMode.TOGGLE, itemSelectionModeOf(press(PointerType.Touch), longPress = true, hasSelection = false))
    }

    // タッチでは、選んでいる項目があるあいだは、押下も長押しも足す・外す
    @Test
    fun touchWithSelectionToggles() {
        assertEquals(SelectionMode.TOGGLE, itemSelectionModeOf(press(PointerType.Touch), longPress = false, hasSelection = true))
        assertEquals(SelectionMode.TOGGLE, itemSelectionModeOf(press(PointerType.Touch), longPress = true, hasSelection = true))
    }

    // ポインターで押していないとき（キーボードなど）は、修飾キーを押していないマウスのクリックと同じに、その項目だけを選ぶ
    @Test
    fun pressWithoutPointerSelectsSingle() {
        listOf(false, true).forEach { hasSelection ->
            assertEquals(SelectionMode.SINGLE, itemSelectionModeOf(null, longPress = false, hasSelection), "$hasSelection")
        }
    }
}
