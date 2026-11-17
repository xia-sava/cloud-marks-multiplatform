package to.sava.cloudmarksmultiplatform.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.app.ControllerFixture
import to.sava.cloudmarksmultiplatform.app.Screen
import to.sava.cloudmarksmultiplatform.browse.SelectionMode
import to.sava.cloudmarksmultiplatform.sync.A_ITEMS
import to.sava.cloudmarksmultiplatform.sync.FOLDER_A
import to.sava.cloudmarksmultiplatform.sync.ROOT_ID
import to.sava.cloudmarksmultiplatform.sync.seed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 戻る操作を続けて行う回数の上限。することが無くなるまでの回数より多い。 */
private const val MAX_BACKS = 10

/**
 * メインの画面の状態のうち、戻る操作で替わるもの。
 *
 * @property selected 選んでいる項目
 * @property drawerOpen ドロワーが開いているか
 * @property folderId 開いているフォルダ
 */
private data class BackView(val selected: Set<String>, val drawerOpen: Boolean, val folderId: String?)

/** [fixture] のいまの、戻る操作で替わる状態。 */
private fun backViewOf(fixture: ControllerFixture): BackView =
    fixture.state.browse.let { BackView(it.selectedItemIds, it.drawerOpen, it.currentFolderId) }

// runCurrent が ExperimentalCoroutinesApi であるため。
@OptIn(ExperimentalCoroutinesApi::class)
class SystemBackControllerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** マニフェストを置き、[storesSettings] なら保存済みの設定も置いて起動したフィクスチャ。 */
    private fun TestScope.started(storesSettings: Boolean = true): ControllerFixture {
        val fixture = ControllerFixture(this, temporaryFolder)
        fixture.store.seed()
        if (storesSettings) fixture.storeSettings()
        fixture.controller.start()
        runCurrent()
        return fixture
    }

    /**
     * [fixture] で、することが無くなるまで、システムの戻る操作ですること（[systemBackOf]）を行う。1 回ごとの、戻る操作で替わる状態を返す。
     * [MAX_BACKS] 回を越えては行わない。
     */
    private fun TestScope.backUntilNothingToDo(fixture: ControllerFixture): List<BackView> =
        buildList {
            repeat(MAX_BACKS) {
                val action = systemBackOf(fixture.state) ?: return@buildList
                fixture.controller.perform(action)
                runCurrent()
                add(backViewOf(fixture))
            }
        }

    // 狭い画面で、項目を選び、ドロワーを開き、前に開いていたフォルダがあれば、戻る操作のたびに、選ぶのをやめる → ドロワーを閉じる →
    // 前のフォルダへ戻る、の順に替わり、そのあとはすることが無い
    @Test
    fun backStepsThroughSelectionDrawerAndHistory() = runTest {
        val fixture = started()
        fixture.controller.setWideLayout(false)
        fixture.controller.openFolder(FOLDER_A)
        runCurrent()
        fixture.controller.toggleTreePane()
        fixture.controller.selectItem(A_ITEMS[0].id, SelectionMode.TOGGLE)
        assertEquals(BackView(setOf(A_ITEMS[0].id), drawerOpen = true, folderId = FOLDER_A), backViewOf(fixture))

        val steps = backUntilNothingToDo(fixture)

        assertEquals(
            listOf(
                BackView(emptySet(), drawerOpen = true, folderId = FOLDER_A),
                BackView(emptySet(), drawerOpen = false, folderId = FOLDER_A),
                BackView(emptySet(), drawerOpen = false, folderId = ROOT_ID),
            ),
            steps,
        )
        assertNull(systemBackOf(fixture.state))
    }

    // 設定の画面から戻る操作をすると、AppBar の戻るボタンと同じくメインの画面へ戻る。そのあとはすることが無い
    @Test
    fun backFromSettingsReturnsToMain() = runTest {
        val fixture = started()
        fixture.controller.openSettings()
        assertEquals(Screen.SETTINGS, fixture.state.screen)

        fixture.controller.perform(checkNotNull(systemBackOf(fixture.state)))
        runCurrent()

        assertEquals(Screen.MAIN, fixture.state.screen)
        assertNull(systemBackOf(fixture.state))
    }

    // 設定がまだ無く、設定の画面しか出せないあいだは、戻る操作を受けない
    @Test
    fun firstSettingsScreenDoesNotTakeBack() = runTest {
        val fixture = started(storesSettings = false)

        assertEquals(Screen.SETTINGS, fixture.state.screen)
        assertNull(systemBackOf(fixture.state))
    }
}
