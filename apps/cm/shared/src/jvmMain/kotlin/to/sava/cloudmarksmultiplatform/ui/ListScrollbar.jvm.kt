package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance

/** 明るいテーマで、マウスを載せていないときの、つまみの濃さ。Compose の既定の見た目（[LocalScrollbarStyle] の初期値）と同じ。 */
private const val LIGHT_THUMB_UNHOVER_ALPHA = 0.12f

/** 明るいテーマで、マウスを載せているときの、つまみの濃さ。Compose の既定の見た目と同じ。 */
private const val LIGHT_THUMB_HOVER_ALPHA = 0.5f

/** 暗いテーマで、マウスを載せていないときの、つまみの濃さ。明るい色は暗い背景で沈んで見えるので、明るいテーマより濃くする。 */
private const val DARK_THUMB_UNHOVER_ALPHA = 0.4f

/** 暗いテーマで、マウスを載せているときの、つまみの濃さ。 */
private const val DARK_THUMB_HOVER_ALPHA = 0.75f

/** 背景の明るさ（[luminance]）がこれより低いテーマを、暗いテーマとみなす。 */
private const val DARK_SURFACE_LUMINANCE = 0.5f

@Composable
internal actual fun ListVerticalScrollbar(listState: LazyListState, modifier: Modifier) {
    VerticalScrollbar(adapter = rememberScrollbarAdapter(listState), modifier = modifier, style = themedScrollbarStyle())
}

@Composable
internal actual fun ListVerticalScrollbar(scrollState: ScrollState, modifier: Modifier) {
    VerticalScrollbar(adapter = rememberScrollbarAdapter(scrollState), modifier = modifier, style = themedScrollbarStyle())
}

/**
 * つまみを、テーマの前景の色（[androidx.compose.material3.ColorScheme.onSurface]）で描くスクロールバーの見た目。
 * Compose の既定は黒で描くので、暗いテーマの背景では見えない。
 */
@Composable
private fun themedScrollbarStyle(): ScrollbarStyle {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < DARK_SURFACE_LUMINANCE
    return LocalScrollbarStyle.current.copy(
        unhoverColor = colors.onSurface.copy(alpha = if (dark) DARK_THUMB_UNHOVER_ALPHA else LIGHT_THUMB_UNHOVER_ALPHA),
        hoverColor = colors.onSurface.copy(alpha = if (dark) DARK_THUMB_HOVER_ALPHA else LIGHT_THUMB_HOVER_ALPHA),
    )
}
