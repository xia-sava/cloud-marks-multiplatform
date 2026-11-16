package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** 詳細の行の頭に付ける語。 */
internal const val DETAIL_LABEL = "詳細: "

/** 詳細の行の文字の不透明度。本文と同じ色を、これだけ薄くして出す。 */
private const val DETAIL_ALPHA = 0.7f

/**
 * [UiMessage] の表示。本文を出し、詳細があればその下の行に、小さく薄い文字で `詳細: <詳細>` と出す。
 * 帯・設定の画面・リストのどこでも、これで出す。
 *
 * @param color 本文の色。詳細はこの色を薄くして出すので、帯の背景の上でも読める
 */
@Composable
internal fun MessageText(message: UiMessage, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(text = message.text, color = color, style = MaterialTheme.typography.bodyMedium)
        message.detail?.let { detail ->
            Text(
                text = DETAIL_LABEL + detail,
                color = color.copy(alpha = DETAIL_ALPHA),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
