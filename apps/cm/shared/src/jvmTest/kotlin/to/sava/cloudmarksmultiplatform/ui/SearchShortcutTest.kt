package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [key] を、修飾キーを引数のとおりに押して、[type] の向きに操作したキーの操作。 */
@OptIn(InternalComposeUiApi::class)
private fun keyEvent(
    key: Key,
    type: KeyEventType = KeyEventType.KeyDown,
    ctrl: Boolean = false,
    alt: Boolean = false,
    shift: Boolean = false,
    meta: Boolean = false,
): KeyEvent = KeyEvent(key, type, isCtrlPressed = ctrl, isMetaPressed = meta, isAltPressed = alt, isShiftPressed = shift)

class SearchShortcutTest {
    // Ctrl を押しながら F を押し下げたときだけ、検索を開くキーの操作にする
    @Test
    fun ctrlFKeyDownIsSearchShortcut() {
        assertTrue(isSearchShortcut(keyEvent(Key.F, ctrl = true)))
    }

    // F を離したとき、Ctrl を押していない F、F でないキーは、検索を開くキーの操作にしない
    @Test
    fun otherKeysAreNotSearchShortcut() {
        val events = listOf(
            keyEvent(Key.F, type = KeyEventType.KeyUp, ctrl = true),
            keyEvent(Key.F),
            keyEvent(Key.G, ctrl = true),
        )

        events.forEach { assertFalse(isSearchShortcut(it), "$it") }
    }

    // Ctrl と一緒に Alt・Shift・Meta のどれかも押していれば、検索を開くキーの操作にしない
    @Test
    fun extraModifiersAreNotSearchShortcut() {
        val events = listOf(
            keyEvent(Key.F, ctrl = true, alt = true),
            keyEvent(Key.F, ctrl = true, shift = true),
            keyEvent(Key.F, ctrl = true, meta = true),
        )

        events.forEach { assertFalse(isSearchShortcut(it), "$it") }
    }
}
