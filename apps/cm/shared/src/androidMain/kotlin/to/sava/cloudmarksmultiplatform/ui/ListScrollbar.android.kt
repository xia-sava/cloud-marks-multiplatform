package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun ListVerticalScrollbar(listState: LazyListState, modifier: Modifier) {
    TouchListScrollbar(listState, modifier)
}
