package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.sync.ROOT_ID
import to.sava.cloudmarksmultiplatform.update.AppVersion
import to.sava.cloudmarksmultiplatform.update.FakeAppUpdater
import to.sava.cloudmarksmultiplatform.update.UpdateCheck
import to.sava.cloudmarksmultiplatform.update.UpdateCheckFailure
import to.sava.cloudmarksmultiplatform.update.UpdateInstall
import to.sava.cloudmarksmultiplatform.update.UpdateInstallFailure
import to.sava.cloudmarksmultiplatform.update.UpdateState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 配布版のアプリ。 */
private val DISTRIBUTED = AppVersion(versionCode = 10, versionName = "1.0.0", displayName = "1.0.0", distributed = true)

/** 開発版のアプリ。 */
private val DEV = AppVersion(versionCode = 1, versionName = "0.0.0", displayName = "0.0.0 (dev)", distributed = false)

/** 確認で分かった新しいバージョン。 */
private val AVAILABLE = UpdateCheck.Available(versionName = "2.0.0", sha256 = "d".repeat(64))

/** 確認の失敗ごとの、画面に出す文。 */
private val CHECK_FAILURE_TEXTS = mapOf(
    UpdateCheckFailure.UNREACHABLE to "更新情報を取得できませんでした。",
    UpdateCheckFailure.SIGNATURE_UNVERIFIED to "更新情報の署名を確認できませんでした。",
    UpdateCheckFailure.UNREADABLE to "更新情報を読み取れませんでした。",
)

/** インストーラへ渡すまでの失敗ごとの、画面に出す文。 */
private val INSTALL_FAILURE_TEXTS = mapOf(
    UpdateInstallFailure.DOWNLOAD_FAILED to "更新をダウンロードできませんでした。",
    UpdateInstallFailure.VERIFICATION_FAILED to "ダウンロードしたファイルの確認に失敗しました。",
    UpdateInstallFailure.HAND_OVER_FAILED to "更新をインストールできませんでした。",
)

/** 設定の画面の、文が [text] の要素。画面の下のほうにあるので、見えるところまでスクロールする。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.updateNode(text: String) = onNodeWithText(text).performScrollTo()

@OptIn(ExperimentalTestApi::class)
class UpdateSectionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /**
     * 状態が [initial] の偽のアプリの更新と、更新をインストーラへ渡したあとに呼ぶ関数 [onReadyToExit] を渡して起動し、
     * 設定の画面を開いてから [block] を走らせる。
     */
    private fun runUpdateSectionTest(
        initial: UpdateState,
        onReadyToExit: () -> Unit = {},
        block: suspend ComposeUiTest.(FakeAppUpdater) -> Unit,
    ) {
        val updater = FakeAppUpdater(initial)
        runCloudMarksScreenTest(temporaryFolder, updater = updater, onReadyToExit = onReadyToExit) {
            onNodeWithContentDescription(SETTINGS_DESCRIPTION).performClick()
            waitForIdle()
            block(updater)
        }
    }

    // 見出しと動いているバージョンを出し、確認のボタンを押せる。配布版では開発版の注記を出さず、確認するまではダウンロードのボタンも出さない
    @Test
    fun showsTitleAndRunningVersion() = runUpdateSectionTest(UpdateState(DISTRIBUTED)) {
        updateNode("アプリの更新").assertIsDisplayed()
        updateNode("現在のバージョンは 1.0.0 です。").assertIsDisplayed()
        updateNode("更新を確認").assertIsEnabled()
        onNodeWithText("開発版では、更新をインストールできません。").assertDoesNotExist()
        onNodeWithText("ダウンロード").assertDoesNotExist()
    }

    // 確認のボタンを押すと、アプリの更新へ確認を渡す
    @Test
    fun checkButtonHandsCheckOver() = runUpdateSectionTest(UpdateState(DISTRIBUTED)) { updater ->
        updateNode("更新を確認").performClick()
        waitForIdle()

        assertEquals(1, updater.checks)
    }

    // 確認している最中は、確認のボタンを押せず、確認していることを伝える
    @Test
    fun checkingShowsNoticeAndDisablesCheck() = runUpdateSectionTest(UpdateState(DISTRIBUTED, check = UpdateCheck.Checking)) {
        updateNode("確認しています…").assertIsDisplayed()
        updateNode("更新を確認").assertIsNotEnabled()
    }

    // 最新なら、そのことを伝える
    @Test
    fun upToDateShowsNotice() = runUpdateSectionTest(UpdateState(DISTRIBUTED, check = UpdateCheck.UpToDate)) {
        updateNode("最新のバージョンです。").assertIsDisplayed()
        onNodeWithText("ダウンロード").assertDoesNotExist()
    }

    // 新しいバージョンがあれば、そのバージョンを伝え、ダウンロードのボタンを押すとアプリの更新へ渡す
    @Test
    fun availableOffersDownload() = runUpdateSectionTest(UpdateState(DISTRIBUTED, check = AVAILABLE)) { updater ->
        updateNode("新しいバージョン 2.0.0 があります。").assertIsDisplayed()
        updateNode("ダウンロード").assertIsEnabled().performClick()
        waitForIdle()

        assertEquals(1, updater.downloads)
    }

    // 確認できなかった理由は、承認した文だけを出し、詳細の行を出さない
    @Test
    fun checkFailuresShowOwnTextsWithoutDetail() = runUpdateSectionTest(UpdateState(DISTRIBUTED)) { updater ->
        CHECK_FAILURE_TEXTS.forEach { (failure, text) ->
            updater.state.value = UpdateState(DISTRIBUTED, check = UpdateCheck.Failed(failure))
            waitForIdle()

            updateNode(text).assertIsDisplayed()
            onAllNodesWithText(DETAIL_LABEL, substring = true).assertCountEquals(0)
            updateNode("更新を確認").assertIsEnabled()
        }
    }

    // 開発版は、確認のボタンを押せて結果も出すが、ダウンロードのボタンは押せず、インストールできないことを伝える
    @Test
    fun devBuildChecksButCannotDownload() = runUpdateSectionTest(UpdateState(DEV, check = AVAILABLE)) { updater ->
        updateNode("現在のバージョンは 0.0.0 (dev) です。").assertIsDisplayed()
        updateNode("開発版では、更新をインストールできません。").assertIsDisplayed()
        updateNode("新しいバージョン 2.0.0 があります。").assertIsDisplayed()
        updateNode("ダウンロード").assertIsNotEnabled()
        updateNode("更新を確認").assertIsEnabled().performClick()
        waitForIdle()

        assertEquals(1, updater.checks)
    }

    // ダウンロードしている最中は、そのことと進み具合のバーを出し、数字は出さない。確認とダウンロードのボタンは押させない
    @Test
    fun downloadingShowsProgressBarWithoutNumbers() = runUpdateSectionTest(
        UpdateState(DISTRIBUTED, check = AVAILABLE, install = UpdateInstall.Downloading(receivedBytes = 37, totalBytes = 74)),
    ) {
        updateNode("ダウンロードしています…").assertIsDisplayed()
        onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assertRangeInfoEquals(ProgressBarRangeInfo(0.5f, 0f..1f))
        onAllNodesWithText("37", substring = true).assertCountEquals(0)
        onNodeWithText("ダウンロード").assertDoesNotExist()
        updateNode("更新を確認").assertIsNotEnabled()
    }

    // 全体の大きさが分からなければ、進み具合のバーを終わりの見えない形で動かす
    @Test
    fun downloadingWithUnknownTotalIsIndeterminate() = runUpdateSectionTest(
        UpdateState(DISTRIBUTED, check = AVAILABLE, install = UpdateInstall.Downloading(receivedBytes = 50, totalBytes = 0)),
    ) {
        onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assertRangeInfoEquals(ProgressBarRangeInfo.Indeterminate)
    }

    // ダウンロードしたファイルを確かめている最中は、そのことを伝える
    @Test
    fun verifyingShowsNotice() = runUpdateSectionTest(UpdateState(DISTRIBUTED, check = AVAILABLE, install = UpdateInstall.Verifying)) {
        updateNode("ダウンロードしたファイルを確認しています…").assertIsDisplayed()
        updateNode("更新を確認").assertIsNotEnabled()
    }

    // 返事を待つ段階では、いったん終了することを伝え、インストールのボタンとキャンセルのボタンを、それぞれアプリの更新へ渡す
    @Test
    fun readyToApplyAsksBeforeInstalling() = runUpdateSectionTest(
        UpdateState(DISTRIBUTED, check = AVAILABLE, install = UpdateInstall.ReadyToApply),
    ) { updater ->
        updateNode("インストールすると Cloud Marks をいったん終了し、更新が終わったら起動し直します。").assertIsDisplayed()
        updateNode("今すぐインストール").assertIsEnabled().performClick()
        updateNode("キャンセル").performClick()
        waitForIdle()

        assertEquals(1, updater.applies)
        assertEquals(1, updater.cancels)
        onNodeWithText("ダウンロード").assertDoesNotExist()
    }

    // 「今すぐインストール」は、画面に渡された、アプリを終了する関数をアプリの更新へ渡す
    @Test
    fun applyHandsOnReadyToExitOver() {
        var exits = 0
        runUpdateSectionTest(
            UpdateState(DISTRIBUTED, check = AVAILABLE, install = UpdateInstall.ReadyToApply),
            onReadyToExit = { exits += 1 },
        ) { updater ->
            updateNode("今すぐインストール").performClick()
            waitForIdle()
            checkNotNull(updater.onReadyToExitPassed).invoke()

            assertEquals(1, exits)
        }
    }

    // データを変える操作を書いている最中は「今すぐインストール」を押せない（キャンセルは押せる）。書き終えたら押せる
    @Test
    fun applyIsDisabledWhileWriting() {
        val updater = FakeAppUpdater(UpdateState(DISTRIBUTED, check = AVAILABLE, install = UpdateInstall.ReadyToApply))
        runCloudMarksScreenTest(temporaryFolder, updater = updater) { fixture ->
            val gate = fixture.stopNextGet(fixture.isManifest)
            runOnUiThread { fixture.controller.createFolder(ROOT_ID, "C") }
            waitForIdle()
            assertTrue(fixture.state.write.writing)
            onNodeWithContentDescription(SETTINGS_DESCRIPTION).performClick()
            waitForIdle()

            updateNode("今すぐインストール").assertIsNotEnabled()
            updateNode("キャンセル").assertIsEnabled()
            gate.complete(Unit)
            waitForIdle()

            assertFalse(fixture.state.write.writing)
            updateNode("今すぐインストール").assertIsEnabled()
        }
    }

    // 返事を待つ段階を通らない状態では、いったん終了することを伝えない
    @Test
    fun restartNoticeOnlyWhileReadyToApply() = runUpdateSectionTest(UpdateState(DISTRIBUTED, check = AVAILABLE)) { updater ->
        listOf(UpdateInstall.Downloading(0, 0), UpdateInstall.Verifying, UpdateInstall.Launching).forEach { stage ->
            updater.state.value = UpdateState(DISTRIBUTED, check = AVAILABLE, install = stage)
            waitForIdle()

            onNodeWithText(RESTART_TO_APPLY_NOTICE).assertDoesNotExist()
            onNodeWithText("今すぐインストール").assertDoesNotExist()
        }
    }

    // インストーラへ渡したら、インストールを始めることを伝える
    @Test
    fun launchingShowsNotice() = runUpdateSectionTest(UpdateState(DISTRIBUTED, check = AVAILABLE, install = UpdateInstall.Launching)) {
        updateNode("インストールを始めます。").assertIsDisplayed()
    }

    // 止まった理由は承認した文だけを出し、詳細の行を出さない。閉じるボタンは、アプリの更新へやめる操作を渡す
    @Test
    fun installFailuresShowOwnTextsAndClose() = runUpdateSectionTest(UpdateState(DISTRIBUTED, check = AVAILABLE)) { updater ->
        INSTALL_FAILURE_TEXTS.entries.forEachIndexed { index, (failure, text) ->
            updater.state.value = UpdateState(DISTRIBUTED, check = AVAILABLE, install = UpdateInstall.Failed(failure))
            waitForIdle()

            updateNode(text).assertIsDisplayed()
            onAllNodesWithText(DETAIL_LABEL, substring = true).assertCountEquals(0)
            updateNode("閉じる").performClick()
            waitForIdle()

            assertEquals(index + 1, updater.cancels)
        }
    }

    // 止まったあとは、確認し直すこともダウンロードし直すこともできる
    @Test
    fun failedInstallOffersCheckAndDownloadAgain() = runUpdateSectionTest(
        UpdateState(DISTRIBUTED, check = AVAILABLE, install = UpdateInstall.Failed(UpdateInstallFailure.DOWNLOAD_FAILED)),
    ) {
        updateNode("更新を確認").assertIsEnabled()
        updateNode("ダウンロード").assertIsEnabled()
    }
}
