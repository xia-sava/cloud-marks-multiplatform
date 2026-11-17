package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.runtime.Composable
import to.sava.cloudmarksmultiplatform.app.AppState
import to.sava.cloudmarksmultiplatform.app.CloudMarksController
import to.sava.cloudmarksmultiplatform.app.Screen

/** システムの戻る操作ですること。 */
internal enum class SystemBack {
    /** 選ぶ状態をやめる。 */
    CLEAR_SELECTION,

    /** AppBar の戻るボタンと同じく戻る（[AppState.back]）。 */
    BACK,
}

/**
 * 状態 [state] で、システムの戻る操作ですること。次の順に、最初に当たるものだけを行う。どれにも当たらなければ null
 * （戻る操作を受けず、プラットフォームの既定の動きに任せる）。ダイアログとメニューは、それぞれが戻る操作で閉じるので、ここでは扱わない。
 *
 * 1. メインの画面で項目を選んでいれば、選ぶ状態をやめる
 * 2. AppBar の戻るボタンを押せる（[AppState.canGoBack]）なら、同じく戻る。設定の画面では、メインの画面へ戻れて保存の最中でなければ、
 * メインの画面へ戻る。メインの画面では、ドロワーが開いていれば閉じ、そうでなければ前に開いていたフォルダへ戻る
 */
internal fun systemBackOf(state: AppState): SystemBack? =
    when {
        state.screen == Screen.MAIN && state.browse.selectedItemIds.isNotEmpty() -> SystemBack.CLEAR_SELECTION
        state.canGoBack -> SystemBack.BACK
        else -> null
    }

/** システムの戻る操作ですること [action] を行う。 */
internal fun CloudMarksController.perform(action: SystemBack) {
    when (action) {
        SystemBack.CLEAR_SELECTION -> clearSelection()
        SystemBack.BACK -> back()
    }
}

/**
 * システムの戻る操作を受ける。[enabled] のあいだ、戻る操作で [onBack] を呼び、プラットフォームの既定の動きをさせない。
 * Android だけが受け、デスクトップは何もしない。
 */
@Composable
internal expect fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit)
