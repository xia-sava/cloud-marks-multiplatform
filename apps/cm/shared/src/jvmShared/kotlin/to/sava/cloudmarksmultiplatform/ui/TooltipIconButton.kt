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
 * アイコンのボタン。ボタンの説明 [contentDescription] と同じ文を、ツールチップにも出す。
 * ツールチップは、デスクトップではマウスを載せたとき、タッチでは長押ししたときに、ボタンの下に出す。
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
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(contentDescription) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, modifier = modifier, enabled = enabled) {
            Icon(imageVector = imageVector, contentDescription = contentDescription)
        }
    }
}
