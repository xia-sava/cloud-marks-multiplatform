package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.rightClick
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.app.Screen
import to.sava.cloudmarksmultiplatform.settings.DeviceSettingsFile
import to.sava.cloudmarksmultiplatform.settings.DisplayScale
import kotlin.io.path.exists
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 大きさを比べるときに許す、丸めの差（画素）。 */
private const val ROUNDING_TOLERANCE = 1f

/** 百分率 [percent] の表示の大きさ。 */
private fun scale(percent: Int): DisplayScale = checkNotNull(DisplayScale.ofPercent(percent))

/** マウスを根の座標の [position] へ動かし、ホイールを [delta] だけ回す（負なら上へ、正なら下へ）。[withCtrl] なら Ctrl を押しながら。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.wheel(position: Offset, delta: Float, withCtrl: Boolean) {
    if (withCtrl) onRoot().performKeyInput { keyDown(Key.CtrlLeft) }
    onRoot().performMouseInput {
        moveTo(position)
        scroll(delta)
    }
    if (withCtrl) onRoot().performKeyInput { keyUp(Key.CtrlLeft) }
    waitForIdle()
}

/** AppBar の戻るボタンの幅（画素）。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.backButtonWidth(): Float =
    onNodeWithContentDescription(BACK_DESCRIPTION).fetchSemanticsNode().size.width.toFloat()

/** 設定の画面で、表示の大きさの欄（いまの段は [current]）を押して段の一覧を開き、段 [row] の行の高さ（画素）を測る。一覧は開いたまま。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.displayScaleMenuRowHeight(current: DisplayScale, row: DisplayScale): Float {
    onNodeWithText(displayScaleLabel(current)).performScrollTo().performClick()
    return onNodeWithText(displayScaleLabel(row)).fetchSemanticsNode().size.height.toFloat()
}

/** フォルダ A のメニューから新しいフォルダのダイアログを開き、[CANCEL_LABEL] のボタンの高さ（画素）を測って、ダイアログを閉じる。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.newFolderDialogCancelHeight(): Float {
    treeRow("A").performMouseInput { rightClick() }
    onNodeWithText(NEW_FOLDER_LABEL).performClick()
    val height = onNodeWithText(CANCEL_LABEL).fetchSemanticsNode().size.height.toFloat()
    onNodeWithText(CANCEL_LABEL).performClick()
    waitForIdle()
    return height
}

@OptIn(ExperimentalTestApi::class)
class DisplayScaleScreenTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // 設定の画面で表示の大きさを選ぶと、保存の操作をせずにすぐ画面全体が拡大され、端末ごとの設定のファイルに残る
    @Test
    fun choosingScaleAppliesAndIsStored() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        onNodeWithContentDescription(SETTINGS_DESCRIPTION).performClick()
        val widthBefore = backButtonWidth()

        onNodeWithText(displayScaleLabel(DisplayScale.DEFAULT)).performScrollTo().performClick()
        onNodeWithText(displayScaleLabel(scale(125))).performClick()
        waitForIdle()

        assertEquals(scale(125), fixture.state.displayScale)
        assertEquals(Screen.SETTINGS, fixture.state.screen)
        val widthAfter = backButtonWidth()
        assertTrue(abs(widthAfter - widthBefore * scale(125).factor) <= ROUNDING_TOLERANCE, "$widthBefore → $widthAfter")
        onNodeWithText(displayScaleLabel(scale(125))).assertExists()
        assertEquals(scale(125), DeviceSettingsFile(fixture.appFiles.deviceSettingsFile) {}.read().displayScale)
    }

    // 段の一覧（ポップアップ）の行も、画面全体と同じ倍率で大きくなり、倍率を重ねて掛けない
    @Test
    fun popupMenuRowsScaleOnce() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        onNodeWithContentDescription(SETTINGS_DESCRIPTION).performClick()
        val heightBefore = displayScaleMenuRowHeight(current = DisplayScale.DEFAULT, row = scale(90))
        onNodeWithText(displayScaleLabel(scale(125))).performClick()
        waitForIdle()
        assertEquals(scale(125), fixture.state.displayScale)

        val heightAfter = displayScaleMenuRowHeight(current = scale(125), row = scale(90))

        assertTrue(abs(heightAfter - heightBefore * scale(125).factor) <= ROUNDING_TOLERANCE, "$heightBefore → $heightAfter")
    }

    // ダイアログの中身も、画面全体と同じ倍率で大きくなり、倍率を重ねて掛けない
    @Test
    fun dialogContentScalesOnce() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        val heightBefore = newFolderDialogCancelHeight()
        val position = itemRow(1).spot(RowSpot.MIDDLE)
        repeat(2) { wheel(position, -1f, withCtrl = true) }
        assertEquals(scale(125), fixture.state.displayScale)

        val heightAfter = newFolderDialogCancelHeight()

        assertTrue(abs(heightAfter - heightBefore * scale(125).factor) <= ROUNDING_TOLERANCE, "$heightBefore → $heightAfter")
    }

    // Ctrl を押しながらホイールを上へ回すと 1 段大きく、下へ回すと 1 段小さくし、画面全体の大きさが替わって、端末ごとの設定のファイルに残る。
    // 回した量が大きくても細かくても、1 回のホイールのイベントで 1 段
    @Test
    fun ctrlWheelChangesScaleOneStepPerEvent() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        val widthBefore = backButtonWidth()
        val position = itemRow(1).spot(RowSpot.MIDDLE)
        val steps = listOf(
            -1f to 110,
            -5f to 125,
            0.1f to 110,
            1f to 100,
            1f to 90,
        )

        steps.forEach { (delta, percent) ->
            wheel(position, delta, withCtrl = true)
            assertEquals(scale(percent), fixture.state.displayScale, "$delta")
        }

        val widthAfter = backButtonWidth()
        assertTrue(abs(widthAfter - widthBefore * scale(90).factor) <= ROUNDING_TOLERANCE, "$widthBefore → $widthAfter")
        assertEquals(scale(90), DeviceSettingsFile(fixture.appFiles.deviceSettingsFile) {}.read().displayScale)
    }

    // Ctrl を押しながらのホイールは、リストのスクロールに渡さない。上へ回して（先頭にあるのでスクロールしない）1 段大きくし、
    // 下へ回して 1 段小さくして元の大きさに戻すと、リストの行は元の位置のまま
    @Test
    fun ctrlWheelDoesNotScrollList() = runCloudMarksScreenTest(temporaryFolder, arrange = longRootItems) { fixture ->
        val position = itemRow(3).spot(RowSpot.MIDDLE)
        val topBefore = assertNotNull(itemRowTop(1))

        wheel(position, -1f, withCtrl = true)
        wheel(position, 1f, withCtrl = true)

        assertEquals(DisplayScale.DEFAULT, fixture.state.displayScale)
        assertEquals(topBefore, itemRowTop(1))
    }

    // Ctrl を押していないホイールは、リストをスクロールし、表示の大きさを替えない（端末ごとの設定のファイルも書かない）
    @Test
    fun plainWheelScrollsListWithoutChangingScale() = runCloudMarksScreenTest(temporaryFolder, arrange = longRootItems) { fixture ->
        val position = itemRow(3).spot(RowSpot.MIDDLE)
        val topBefore = assertNotNull(itemRowTop(1))

        wheel(position, 1f, withCtrl = false)

        assertEquals(DisplayScale.DEFAULT, fixture.state.displayScale)
        assertFalse(fixture.appFiles.deviceSettingsFile.exists())
        val topAfter = itemRowTop(1)
        assertTrue(topAfter == null || topAfter < topBefore, "$topBefore → $topAfter")
    }
}
