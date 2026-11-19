package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.settings.SettingsForm
import to.sava.cloudmarksmultiplatform.update.AppVersion
import to.sava.cloudmarksmultiplatform.update.FakeAppUpdater
import to.sava.cloudmarksmultiplatform.update.UpdateState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 設定の画面の、1 行の欄の題。シークレットの欄は伏せ字にするので含めない。 */
private val PLAIN_FIELD_LABELS = listOf("リージョン", "バケット名", "プレフィックス", "アクセスキー ID")

/** 設定の画面の、シークレットの欄の題。 */
private const val SECRET_FIELD_LABEL = "シークレットアクセスキー"

/** 改行の後ろに続ける文字。罫線のように、貼り付けたときに付いてくるもの。 */
private const val AFTER_LINE_BREAK = "─────"

/** 設定の画面の、題が [label] の入力欄。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.settingsField(label: String): SemanticsNodeInteraction = onNode(hasSetTextAction() and hasText(label))

/** 欄 [label] に入れる値。欄ごとに違う。 */
private fun valueFor(label: String): String = "value-${(PLAIN_FIELD_LABELS + SECRET_FIELD_LABEL).indexOf(label)}"

@OptIn(ExperimentalTestApi::class)
class SettingsScreenTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // 設定の 1 行の欄は、入れた改行（\n でも \r でも）とその後ろを受け付けない。欄に見える値と、保存に使う入力の値は同じ。
    // 貼り付けのように、改行を含む文をまとめて入れても、欄の値をまとめて置き換えても同じ
    @Test
    fun settingsFieldsDropLineBreakAndRest() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        onNodeWithContentDescription(SETTINGS_DESCRIPTION).performClick()
        waitForIdle()

        (PLAIN_FIELD_LABELS + SECRET_FIELD_LABEL).forEachIndexed { index, label ->
            val field = settingsField(label).performScrollTo()
            val lineBreak = if (index % 2 == 0) "\n" else "\r"
            field.performTextClearance()
            field.performTextInput(valueFor(label) + lineBreak + AFTER_LINE_BREAK)
            waitForIdle()
        }

        val expected = SettingsForm(
            region = valueFor("リージョン"),
            bucket = valueFor("バケット名"),
            prefix = valueFor("プレフィックス"),
            accessKeyId = valueFor("アクセスキー ID"),
            secret = valueFor(SECRET_FIELD_LABEL),
        )
        assertEquals(expected, fixture.state.settings.form)
        PLAIN_FIELD_LABELS.forEach { label -> settingsField(label).assert(hasText(valueFor(label))) }

        settingsField("リージョン").performTextReplacement("ap-northeast-1\r\n$AFTER_LINE_BREAK")
        waitForIdle()

        assertEquals("ap-northeast-1", fixture.state.settings.form.region)
        settingsField("リージョン").assert(hasText("ap-northeast-1"))
    }

    // アプリの更新を渡さなければ、設定の画面に「アプリの更新」の節を出さない
    @Test
    fun noUpdateSectionWithoutUpdater() = runCloudMarksScreenTest(temporaryFolder) {
        onNodeWithContentDescription(SETTINGS_DESCRIPTION).performClick()
        waitForIdle()

        onNodeWithText(REVISIONS_TITLE).performScrollTo().assertIsDisplayed()
        onNodeWithText(UPDATE_SECTION_TITLE).assertDoesNotExist()
        onNodeWithText(CHECK_UPDATE_LABEL).assertDoesNotExist()
    }

    // アプリの更新を渡せば、設定の画面の末尾の、以前の状態に戻す説明より下に「アプリの更新」の節を出す。メインの画面には出さない
    @Test
    fun updateSectionIsLastOnSettingsScreen() = runCloudMarksScreenTest(
        temporaryFolder,
        updater = FakeAppUpdater(UpdateState(AppVersion(10, "1.0.0", "1.0.0", distributed = true))),
    ) {
        onNodeWithText(UPDATE_SECTION_TITLE).assertDoesNotExist()
        onNodeWithContentDescription(SETTINGS_DESCRIPTION).performClick()
        waitForIdle()

        onNodeWithText(CHECK_UPDATE_LABEL).performScrollTo().assertIsDisplayed()
        val revisionsBottom = onNodeWithText(REVISIONS_DESCRIPTION).fetchSemanticsNode().boundsInRoot.bottom
        val sectionTop = onNodeWithText(UPDATE_SECTION_TITLE).fetchSemanticsNode().boundsInRoot.top
        val checkTop = onNodeWithText(CHECK_UPDATE_LABEL).fetchSemanticsNode().boundsInRoot.top
        assertTrue(revisionsBottom < sectionTop, "$revisionsBottom < $sectionTop")
        assertTrue(sectionTop < checkTop, "$sectionTop < $checkTop")
    }
}
