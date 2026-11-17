package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.sava.cloudmarksmultiplatform.settings.DisplayScale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** 画素を比べるときに許す、浮動小数点の誤差。 */
private const val PIXEL_TOLERANCE = 1e-3f

/** 百分率 [percent] の表示の大きさ。 */
private fun scale(percent: Int): DisplayScale = checkNotNull(DisplayScale.ofPercent(percent))

/** 文字の拡大が非線形のテストの密度。sp と dp の換算は、1 sp を 2 dp にしてから 1 dp を足す。 */
private class NonLinearFontDensity(override val density: Float) : Density {
    override val fontScale: Float = 2f

    override fun Dp.toSp(): TextUnit = ((value - 1f) / 2f).sp

    override fun TextUnit.toDp(): Dp = Dp(value * 2f + 1f)
}

class ScaledDensityTest {
    private val base = NonLinearFontDensity(density = 2f)

    // 100% なら、元の密度をそのまま使う
    @Test
    fun defaultScaleKeepsDensity() {
        assertSame(base, scaledDensityOf(base, DisplayScale.DEFAULT))
    }

    // dp は、どの段でも、元の密度での画素に倍率を掛けた画素で描く
    @Test
    fun dpIsScaledByFactor() {
        DisplayScale.STEPS.forEach { step ->
            val scaled = scaledDensityOf(base, step)

            assertEquals(with(base) { 10.dp.toPx() } * step.factor, with(scaled) { 10.dp.toPx() }, PIXEL_TOLERANCE, "$step")
        }
    }

    // sp は、元の密度の換算（非線形）で dp にしてから、倍率を掛けた画素で描く。sp と dp の換算と文字の倍率は、元の密度と同じ
    @Test
    fun spUsesBaseConversionThenFactor() {
        val scaled = scaledDensityOf(base, scale(125))

        assertEquals(with(base) { 14.sp.toPx() } * scale(125).factor, with(scaled) { 14.sp.toPx() }, PIXEL_TOLERANCE)
        assertEquals(with(base) { 14.sp.toDp() }, with(scaled) { 14.sp.toDp() })
        assertEquals(with(base) { 29.dp.toSp() }, with(scaled) { 29.dp.toSp() })
        assertEquals(base.fontScale, scaled.fontScale)
    }

    // 画素から dp への換算は、倍率で割る
    @Test
    fun pixelsToDpDividesByFactor() {
        val scaled = scaledDensityOf(base, scale(200))

        assertEquals(with(base) { 40f.toDp() } / scale(200).factor, with(scaled) { 40f.toDp() })
    }

    // 拡大縮小した密度をもう一度拡大縮小しても、倍率を重ねて掛けず、元の密度から作り直す
    @Test
    fun rescalingDoesNotCompound() {
        val once = scaledDensityOf(base, scale(125))

        assertEquals(once, scaledDensityOf(once, scale(125)))
        assertEquals(with(base) { 10.dp.toPx() } * scale(150).factor, with(scaledDensityOf(once, scale(150))) { 10.dp.toPx() }, PIXEL_TOLERANCE)
        assertSame(base, scaledDensityOf(once, DisplayScale.DEFAULT))
    }

    // ポップアップの中で密度が元の密度に戻されていても（Android のポップアップ）、中身は画面全体と同じ倍率の密度で描く
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun popupContentRescalesResetDensity() = runComposeUiTest {
        var original: Float? = null
        var inside: Float? = null
        setContent {
            val unscaled = LocalDensity.current
            original = unscaled.density
            ScaledDensity(scale(125)) {
                CompositionLocalProvider(LocalDensity provides unscaled) {
                    ScaledPopupContent { inside = LocalDensity.current.density }
                }
            }
        }
        waitForIdle()

        assertEquals(checkNotNull(original) * scale(125).factor, checkNotNull(inside), PIXEL_TOLERANCE)
    }

    // ポップアップの中で画面全体の密度を受け継いでいれば（デスクトップのポップアップ）、中身の密度にもう一度倍率を掛けない
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun popupContentKeepsInheritedScale() = runComposeUiTest {
        var original: Float? = null
        var inside: Float? = null
        setContent {
            original = LocalDensity.current.density
            ScaledDensity(scale(125)) {
                ScaledPopupContent { inside = LocalDensity.current.density }
            }
        }
        waitForIdle()

        assertEquals(checkNotNull(original) * scale(125).factor, checkNotNull(inside), PIXEL_TOLERANCE)
    }
}
