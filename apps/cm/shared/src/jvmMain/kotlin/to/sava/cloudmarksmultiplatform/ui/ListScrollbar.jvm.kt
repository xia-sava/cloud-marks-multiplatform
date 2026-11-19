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

/** マウスを載せていないときの、つまみの濃さ。Compose の既定の見た目（[LocalScrollbarStyle] の初期値）と同じ。 */
private const val THUMB_UNHOVER_ALPHA = 0.12f

/** マウスを載せているときの、つまみの濃さ。Compose の既定の見た目と同じ。 */
private const val THUMB_HOVER_ALPHA = 0.5f

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
    val color = MaterialTheme.colorScheme.onSurface
    return LocalScrollbarStyle.current.copy(
        unhoverColor = color.copy(alpha = THUMB_UNHOVER_ALPHA),
        hoverColor = color.copy(alpha = THUMB_HOVER_ALPHA),
    )
}
