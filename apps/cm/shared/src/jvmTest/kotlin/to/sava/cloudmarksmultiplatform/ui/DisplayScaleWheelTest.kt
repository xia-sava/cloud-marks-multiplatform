package to.sava.cloudmarksmultiplatform.ui

import to.sava.cloudmarksmultiplatform.settings.DisplayScale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 百分率 [percent] の表示の大きさ。 */
private fun scale(percent: Int): DisplayScale = checkNotNull(DisplayScale.ofPercent(percent))

class DisplayScaleWheelTest {
    // Ctrl を押しながら上へ回すと 1 段大きく、下へ回すと 1 段小さくする。回した量が大きくても細かくても 1 段
    @Test
    fun ctrlWheelStepsOneScale() {
        val cases = listOf(
            -1f to 150,
            -10f to 150,
            -0.1f to 150,
            1f to 110,
            10f to 110,
            0.1f to 110,
        )
        cases.forEach { (scrollY, percent) ->
            assertEquals(scale(percent), displayScaleByWheel(scale(125), ctrlPressed = true, scrollY), "$scrollY")
        }
    }

    // いちばん大きい段から上へ、いちばん小さい段から下へ回しても、その段のまま（表示の大きさの操作として受ける）
    @Test
    fun ctrlWheelAtEndStaysAtEnd() {
        assertEquals(DisplayScale.STEPS.last(), displayScaleByWheel(DisplayScale.STEPS.last(), ctrlPressed = true, -1f))
        assertEquals(DisplayScale.STEPS.first(), displayScaleByWheel(DisplayScale.STEPS.first(), ctrlPressed = true, 1f))
    }

    // Ctrl を押していないホイールと、縦に回していないホイールは、表示の大きさの操作ではない
    @Test
    fun otherWheelsAreNotScaleOperations() {
        assertNull(displayScaleByWheel(scale(125), ctrlPressed = false, -1f))
        assertNull(displayScaleByWheel(scale(125), ctrlPressed = false, 1f))
        assertNull(displayScaleByWheel(scale(125), ctrlPressed = true, 0f))
    }
}
