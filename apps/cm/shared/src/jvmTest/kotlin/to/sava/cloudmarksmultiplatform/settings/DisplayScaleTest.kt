package to.sava.cloudmarksmultiplatform.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DisplayScaleTest {
    /** 百分率 [percent] の段。 */
    private fun scale(percent: Int): DisplayScale = checkNotNull(DisplayScale.ofPercent(percent))

    // 段は 80・90・100・110・125・150・175・200% の小さい順で、既定は 100%。倍率は百分率を 100 で割ったもの
    @Test
    fun stepsAndDefault() {
        assertEquals(listOf(80, 90, 100, 110, 125, 150, 175, 200), DisplayScale.STEPS.map { it.percent })
        assertEquals(100, DisplayScale.DEFAULT.percent)
        assertEquals(1.25f, scale(125).factor)
    }

    // 1 段大きく・小さくすると、隣の段になる。いちばん大きい段とさらに大きく、いちばん小さい段とさらに小さくはそのまま
    @Test
    fun largerAndSmallerMoveOneStepWithinRange() {
        val larger = mapOf(80 to 90, 100 to 110, 110 to 125, 175 to 200, 200 to 200)
        val smaller = mapOf(200 to 175, 125 to 110, 100 to 90, 90 to 80, 80 to 80)

        assertEquals(larger, larger.keys.associateWith { scale(it).larger().percent })
        assertEquals(smaller, smaller.keys.associateWith { scale(it).smaller().percent })
    }

    // 段に無い百分率は、段にならない
    @Test
    fun percentOutsideStepsIsNotAScale() {
        listOf(0, 79, 95, 120, 201).forEach { assertNull(DisplayScale.ofPercent(it), "$it") }
    }
}
