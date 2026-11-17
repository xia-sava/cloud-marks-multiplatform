package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * アイコンのボタン。ボタンの説明 [contentDescription] と同じ文を、ツールチップにも出す（[WithTooltip]）。
 *
 * @param modifier ボタンに付ける修飾
 * @param enabled 押せるか
 */
@Composable
internal fun TooltipIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    WithTooltip(contentDescription) {
        IconButton(onClick = onClick, modifier = modifier, enabled = enabled) {
            Icon(imageVector = imageVector, contentDescription = contentDescription)
        }
    }
}

/**
 * [content] に、[text] を出すツールチップを付ける。ツールチップは、デスクトップではマウスを載せたとき、タッチでは長押ししたときに、
 * [content] の下に出す。[content] が押せなくても出す。ツールチップは、画面全体と同じ表示の大きさで描く（[ScaledPopupContent]）。
 */
@Composable
internal fun WithTooltip(text: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { ScaledPopupContent { PlainTooltip { Text(text) } } },
        state = rememberTooltipState(),
        content = content,
    )
}
