package to.sava.cloudmarksmultiplatform.ui

import to.sava.cloudmarksmultiplatform.app.AppState
import to.sava.cloudmarksmultiplatform.app.RevisionListState
import to.sava.cloudmarksmultiplatform.app.RevisionsScreenState
import to.sava.cloudmarksmultiplatform.app.Screen
import to.sava.cloudmarksmultiplatform.app.SettingsScreenState
import to.sava.cloudmarksmultiplatform.app.SettingsStatus
import to.sava.cloudmarksmultiplatform.app.WriteState
import to.sava.cloudmarksmultiplatform.browse.BrowseState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** 選んでいる項目。 */
private const val SELECTED_ITEM = "item"

/** 開いているフォルダ。 */
private const val CURRENT_FOLDER = "current"

/** 前に開いていたフォルダ。 */
private const val PREVIOUS_FOLDER = "previous"

/** メインの画面で、[browse] の状態。 */
private fun mainWith(browse: BrowseState): AppState = AppState(screen = Screen.MAIN, browse = browse)

/** 設定の画面で、[settings] の状態。メインの画面の側には、選んでいる項目と、開いたドロワーと、前に開いていたフォルダがある。 */
private fun settingsWith(settings: SettingsScreenState): AppState =
    AppState(
        screen = Screen.SETTINGS,
        settings = settings,
        browse = BrowseState(selectedItemIds = setOf(SELECTED_ITEM), drawerOpen = true, history = listOf(PREVIOUS_FOLDER)),
    )

class SystemBackTest {
    // メインの画面で項目を選んでいれば、ドロワーが開いていても、前に開いていたフォルダがあっても、まず選ぶ状態をやめる
    @Test
    fun selectionIsClearedFirst() {
        val cases = listOf(
            BrowseState(selectedItemIds = setOf(SELECTED_ITEM)),
            BrowseState(selectedItemIds = setOf(SELECTED_ITEM), drawerOpen = true, history = listOf(PREVIOUS_FOLDER)),
        )

        cases.forEach { browse -> assertEquals(SystemBack.CLEAR_SELECTION, systemBackOf(mainWith(browse)), "$browse") }
    }

    // メインの画面で、選んでいる項目が無くドロワーが開いていれば、AppBar の戻るボタンと同じく戻り、それはドロワーを閉じるだけになる。
    // 前に開いていたフォルダがあっても、開いているフォルダは替えない
    @Test
    fun backClosesDrawerBeforeGoingBack() {
        val cases = listOf(
            BrowseState(currentFolderId = CURRENT_FOLDER, drawerOpen = true),
            BrowseState(currentFolderId = CURRENT_FOLDER, drawerOpen = true, history = listOf(PREVIOUS_FOLDER)),
        )

        cases.forEach { browse ->
            val state = mainWith(browse)
            assertEquals(SystemBack.BACK, systemBackOf(state), "$browse")

            val back = state.back().browse
            assertFalse(back.drawerOpen, "$browse")
            assertEquals(CURRENT_FOLDER, back.currentFolderId, "$browse")
            assertEquals(browse.history, back.history, "$browse")
        }
    }

    // メインの画面で、選んでいる項目が無くドロワーも閉じていれば、前に開いていたフォルダがあるときだけ、そのフォルダへ戻る
    @Test
    fun mainScreenGoesBackOnlyWithHistory() {
        val withHistory = mainWith(BrowseState(currentFolderId = CURRENT_FOLDER, history = listOf(PREVIOUS_FOLDER)))
        assertEquals(SystemBack.BACK, systemBackOf(withHistory))
        assertEquals(PREVIOUS_FOLDER, withHistory.back().browse.currentFolderId)

        assertNull(systemBackOf(mainWith(BrowseState(currentFolderId = CURRENT_FOLDER))))
    }

    // 設定の画面では、メインの画面の側の選んでいる項目とドロワーによらず、AppBar の戻るボタンを押せるとき（メインの画面へ戻れて、
    // 保存の最中でないとき）だけ戻り、メインの画面へ戻る
    @Test
    fun settingsScreenGoesBackOnlyWhenBackButtonIsEnabled() {
        val returnable = settingsWith(SettingsScreenState(canReturn = true))
        assertEquals(SystemBack.BACK, systemBackOf(returnable))
        assertEquals(Screen.MAIN, returnable.back().screen)

        val cases = listOf(
            SettingsScreenState(canReturn = false),
            SettingsScreenState(canReturn = true, status = SettingsStatus.Saving),
            SettingsScreenState(canReturn = true, status = SettingsStatus.CreatingManifest),
        )
        cases.forEach { settings -> assertNull(systemBackOf(settingsWith(settings)), "$settings") }
    }

    // 以前の状態に戻す画面では、メインの画面の側の選んでいる項目とドロワーによらず、戻している最中でなければ戻り、設定の画面へ戻って
    // その画面の状態を初めの値にする。戻している最中は、戻る操作を受けない
    @Test
    fun revisionsScreenGoesBackToSettingsUnlessRestoring() {
        val revisions = AppState(
            screen = Screen.REVISIONS,
            settings = SettingsScreenState(canReturn = true),
            browse = BrowseState(selectedItemIds = setOf(SELECTED_ITEM), drawerOpen = true, history = listOf(PREVIOUS_FOLDER)),
            revisions = RevisionsScreenState(list = RevisionListState.Listed(emptyList())),
        )
        assertEquals(SystemBack.BACK, systemBackOf(revisions))
        val back = revisions.back()
        assertEquals(Screen.SETTINGS, back.screen)
        assertEquals(RevisionsScreenState(), back.revisions)
        assertEquals(revisions.browse, back.browse)

        val restoring = revisions.copy(write = WriteState(writing = true))
        assertFalse(restoring.canGoBack)
        assertNull(systemBackOf(restoring))
        assertEquals(restoring, restoring.back())
    }

    // 起動して設定を読んでいるあいだは、戻る操作を受けない
    @Test
    fun startingScreenDoesNothing() {
        assertNull(systemBackOf(AppState(screen = Screen.STARTING, browse = BrowseState(history = listOf(PREVIOUS_FOLDER)))))
    }
}
