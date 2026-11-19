package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** テストの画面（高さ 768 画素）に収まる、中身の高さ。 */
private val FITTING_CONTENT_HEIGHT = 200.dp

/** テストの画面に収まらない、中身の高さ。 */
private val OVERFLOWING_CONTENT_HEIGHT = 2_000.dp

/** 色を見る画素の、窓の右端からの距離（画素）。デスクトップのスクロールバー（幅 8 画素）の真ん中。 */
private const val SCROLLBAR_CENTER_INSET = 4

/** 色を見る画素の、窓の上端からの距離（画素）。先頭にいるときの、つまみの上。 */
private const val THUMB_SAMPLE_Y = 10

/** 窓の右端の、つまみを描く位置の画素の色と、中身の背景の色。 */
private data class ScrollbarSample(val thumb: Color, val background: Color)

/**
 * テーマ [colorScheme] のもとで、高さ [contentHeight] の、[ScrollState] で縦にスクロールする中身に [ListVerticalScrollbar] を
 * 重ねて窓いっぱいに出し、右端の、つまみを描く位置の画素の色を返す。中身の背景はテーマの [ColorScheme.surface]。
 */
@OptIn(ExperimentalTestApi::class)
private fun scrollbarSampleOver(contentHeight: Dp, colorScheme: ColorScheme = lightColorScheme()): ScrollbarSample {
    var thumb: Color? = null
    runComposeUiTest {
        val scrollState = ScrollState(initial = 0)
        setContent {
            MaterialTheme(colorScheme = colorScheme) {
                Box(Modifier.fillMaxSize().background(colorScheme.surface)) {
                    Box(Modifier.fillMaxSize().verticalScroll(scrollState)) { Spacer(Modifier.fillMaxWidth().height(contentHeight)) }
                    ListVerticalScrollbar(scrollState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }
        waitForIdle()
        val pixels = onRoot().captureToImage().toPixelMap()
        thumb = pixels[pixels.width - SCROLLBAR_CENTER_INSET, THUMB_SAMPLE_Y]
    }
    return ScrollbarSample(checkNotNull(thumb), colorScheme.surface)
}

/** デスクトップの、[ScrollState] で縦にスクロールする中身の [ListVerticalScrollbar]。 */
class ListVerticalScrollbarTest {
    // 中身が窓に収まらなければ、右端につまみを描く
    @Test
    fun overflowingContentShowsThumb() {
        val sample = scrollbarSampleOver(OVERFLOWING_CONTENT_HEIGHT)
        assertNotEquals(sample.background, sample.thumb)
    }

    // 中身が窓に収まれば、つまみを描かない
    @Test
    fun fittingContentShowsNoThumb() {
        val sample = scrollbarSampleOver(FITTING_CONTENT_HEIGHT)
        assertEquals(sample.background, sample.thumb)
    }

    // 明るいテーマでは、つまみを背景より暗く描く
    @Test
    fun thumbIsDarkerThanBackgroundInLightTheme() {
        val sample = scrollbarSampleOver(OVERFLOWING_CONTENT_HEIGHT, lightColorScheme())
        assertTrue(sample.thumb.luminance() < sample.background.luminance(), "つまみ ${sample.thumb}、背景 ${sample.background}")
    }

    // 暗いテーマでは、つまみを背景より明るく描く（黒で描くと、暗い背景に紛れて見えない）
    @Test
    fun thumbIsLighterThanBackgroundInDarkTheme() {
        val sample = scrollbarSampleOver(OVERFLOWING_CONTENT_HEIGHT, darkColorScheme())
        assertTrue(sample.thumb.luminance() > sample.background.luminance(), "つまみ ${sample.thumb}、背景 ${sample.background}")
    }
}
