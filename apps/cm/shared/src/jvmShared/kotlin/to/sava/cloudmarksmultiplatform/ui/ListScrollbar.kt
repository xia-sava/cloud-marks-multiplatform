package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * [listState] のリストに添える、縦のスクロールバー。デスクトップだけが描き、Android は何も描かない。
 *
 * リストと同じ高さの枠の右端に重ねて置く。[modifier] で位置と高さを決める。
 */
@Composable
internal expect fun ListVerticalScrollbar(listState: LazyListState, modifier: Modifier)
