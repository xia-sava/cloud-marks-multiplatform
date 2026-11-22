package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import to.sava.cloudmarksmultiplatform.app.AppState
import to.sava.cloudmarksmultiplatform.app.CloudMarksController
import to.sava.cloudmarksmultiplatform.app.Screen

/**
 * デスクトップの、項目の検索を開くキーの操作（Ctrl+F。[isSearchShortcut]）。
 *
 * デスクトップのアプリは、ウィンドウで受けたキーの操作を、焦点のある要素より先に [onKeyEvent] へ渡し（`Window` の `onPreviewKeyEvent`）、
 * 同じものを [CloudMarksApp] に渡す。ウィンドウで受けるので、焦点がどこにあっても、何にも焦点が無くても効く。
 * 画面の中の要素がキーの操作を受けるのは、焦点がその要素か子孫にあるときだけなので、画面の中では受けない。
 *
 * 検索を開けるとき（[canOpenSearch]）に押されたら、検索を開き（[CloudMarksController.openSearch]。既に開いていれば開いたまま）、
 * AppBar の検索の入力欄に焦点を移す。
 */
@Stable
class SearchShortcut(private val controller: CloudMarksController) {
    /** 検索の入力欄に焦点を移すよう頼んだ回数。替わるたびに、出している入力欄が焦点を取る。 */
    internal var focusRequests: Int by mutableIntStateOf(0)
        private set

    /**
     * [event] が検索を開くキーの操作（[isSearchShortcut]）で、検索を開けるとき（[canOpenSearch]）なら、検索を開いて入力欄に焦点を移し、
     * true を返す（キーの操作を、焦点のある要素に渡さない）。そうでなければ何もせず false を返す。
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isSearchShortcut(event) || !controller.state.value.canOpenSearch) return false
        controller.openSearch()
        focusRequests++
        return true
    }
}

/** [event] が、検索を開くキーの操作か。Ctrl を押しながら F を押したとき。Alt・Shift・Meta も押していれば違う。 */
internal fun isSearchShortcut(event: KeyEvent): Boolean =
    event.type == KeyEventType.KeyDown && event.key == Key.F &&
        event.isCtrlPressed && !event.isAltPressed && !event.isShiftPressed && !event.isMetaPressed

/** 検索を開けるか。メインの画面で、ツリーを読めているとき。AppBar の検索を開くボタンも、このときだけ押せる。 */
internal val AppState.canOpenSearch: Boolean get() = screen == Screen.MAIN && browse.tree != null
