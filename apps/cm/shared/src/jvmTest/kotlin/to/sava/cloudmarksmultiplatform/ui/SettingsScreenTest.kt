package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.IntSize
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

/** 設定の画面が収まらない、低い窓の大きさ。幅は、入力欄を並べる幅の上限より広い。 */
private val SHORT_WINDOW = IntSize(1024, 400)

/** マウスで押す位置の、窓の右端からの距離（画素）。デスクトップのスクロールバー（幅 8 画素）の上。 */
private const val SCROLLBAR_INSET = 4f

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

    // 設定の画面が窓に収まらなければ、入力欄を並べる幅の外の、窓の右端にスクロールバーを出す。つまみをマウスで下端まで動かすと、
    // 画面の末尾までスクロールする
    @Test
    fun scrollbarAtWindowEdgeScrollsSettings() = runCloudMarksScreenTest(temporaryFolder, containerSize = mutableStateOf(SHORT_WINDOW)) {
        onNodeWithContentDescription(SETTINGS_DESCRIPTION).performClick()
        waitForIdle()
        onNodeWithText(REVISIONS_DESCRIPTION).assertIsNotDisplayed()
        // 先頭にいるときのつまみは、画面の上端から始まり、最初の欄の上端より下まで続く
        val thumbY = settingsField("リージョン").fetchSemanticsNode().boundsInRoot.top
        val scrollbarX = SHORT_WINDOW.width - SCROLLBAR_INSET

        onRoot().performMouseInput {
            moveTo(Offset(scrollbarX, thumbY))
            press()
            moveTo(Offset(scrollbarX, SHORT_WINDOW.height.toFloat()))
            release()
        }
        waitForIdle()

        onNodeWithText(REVISIONS_DESCRIPTION).assertIsDisplayed()
        settingsField("リージョン").assertIsNotDisplayed()
    }
}
