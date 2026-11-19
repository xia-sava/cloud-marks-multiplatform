package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntSize
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.update.AppVersion
import to.sava.cloudmarksmultiplatform.update.FakeAppUpdater
import to.sava.cloudmarksmultiplatform.update.UpdateState
import kotlin.test.Test
import kotlin.test.assertEquals

/** 配布版のアプリ。 */
private val DISTRIBUTED = AppVersion(versionCode = 10, versionName = "1.0.0", displayName = "1.0.0", distributed = true)

/** 前の起動で適用できなかったバージョンの、メインの画面に出す文。 */
private const val APPLY_FAILED_TEXT = "バージョン 2.0.0 への更新を適用できませんでした。設定の画面の「アプリの更新」からやり直せます。"

/** 狭い画面の区分になる、縦長の窓の大きさ。 */
private val PORTRAIT = IntSize(400, 760)

@OptIn(ExperimentalTestApi::class)
class UpdateApplyFailedBannerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // 前の起動で適用できなかったら、メインの画面にそのことを伝え、「閉じる」はアプリの更新へ閉じる操作を渡す
    @Test
    fun showsApplyFailureOnMainScreenAndClosesIt() {
        val updater = FakeAppUpdater(UpdateState(DISTRIBUTED, applyFailedVersionName = "2.0.0"))
        runCloudMarksScreenTest(temporaryFolder, updater = updater) {
            onNodeWithText(APPLY_FAILED_TEXT).assertIsDisplayed()
            onNodeWithText(CLOSE_LABEL).performClick()
            waitForIdle()

            assertEquals(1, updater.dismissals)
        }
    }

    // 閉じて伝えることが無くなったら、帯を出さない
    @Test
    fun hidesApplyFailureOnceDismissed() {
        val updater = FakeAppUpdater(UpdateState(DISTRIBUTED, applyFailedVersionName = "2.0.0"))
        runCloudMarksScreenTest(temporaryFolder, updater = updater) {
            updater.state.value = UpdateState(DISTRIBUTED)
            waitForIdle()

            onNodeWithText(APPLY_FAILED_TEXT).assertDoesNotExist()
        }
    }

    // 帯はメインの画面にだけ出し、設定の画面には出さない
    @Test
    fun applyFailureIsNotShownOnSettingsScreen() {
        val updater = FakeAppUpdater(UpdateState(DISTRIBUTED, applyFailedVersionName = "2.0.0"))
        runCloudMarksScreenTest(temporaryFolder, updater = updater) {
            onNodeWithContentDescription(SETTINGS_DESCRIPTION).performClick()
            waitForIdle()

            onNodeWithText(APPLY_FAILED_TEXT).assertDoesNotExist()
        }
    }

    // 狭い画面のメインの画面にも出す
    @Test
    fun showsApplyFailureOnNarrowMainScreen() {
        val updater = FakeAppUpdater(UpdateState(DISTRIBUTED, applyFailedVersionName = "2.0.0"))
        runCloudMarksScreenTest(temporaryFolder, updater = updater, containerSize = mutableStateOf(PORTRAIT)) {
            onNodeWithText(APPLY_FAILED_TEXT).assertIsDisplayed()
        }
    }
}
