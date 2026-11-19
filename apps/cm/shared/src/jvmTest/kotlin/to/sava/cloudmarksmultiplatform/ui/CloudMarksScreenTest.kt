package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseInjectionScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.rightClick
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.app.WriteOutcome
import to.sava.cloudmarksmultiplatform.browse.SelectionMode
import to.sava.cloudmarksmultiplatform.browser.CHROME
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.GetFault
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.PutFault
import to.sava.cloudmarksmultiplatform.sync.FOLDER_B
import to.sava.cloudmarksmultiplatform.sync.ROOT_ID
import to.sava.cloudmarksmultiplatform.sync.ROOT_ITEMS
import to.sava.cloudmarksmultiplatform.sync.SyncFailure
import to.sava.cloudmarksmultiplatform.sync.initialManifest
import to.sava.cloudmarksmultiplatform.sync.seed
import to.sava.cloudmarksmultiplatform.sync.storedManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 知っているバージョンより新しい、読むだけにするマニフェストの `version`。 */
private const val NEWER_VERSION = 2

/** 項目の行を押したあと、行の外（下の行のさらに下）へポインターを動かす距離（画素）。項目の行の高さの 2 倍より長い。 */
private const val OUTSIDE_ROW_DISTANCE = 120f

/**
 * クリックのあいだに、マウスをわずかに動かす量（画素。テストの画面の密度は 1 なので dp と同じ）。
 * ドラッグを始める閾値（[MOVE_DRAG_THRESHOLD]）より短く、Compose のデスクトップのマウスの既定の閾値（0.125dp）よりは長い。
 */
private val JITTER = Offset(3f, 2f)

/** 要素の真ん中を、押してから離すまでに [JITTER] だけ動かして、マウスの主ボタンでクリックする。 */
@OptIn(ExperimentalTestApi::class)
private fun MouseInjectionScope.clickWithJitter() {
    moveTo(center)
    press()
    moveBy(JITTER)
    release()
}

@OptIn(ExperimentalTestApi::class)
class CloudMarksScreenTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // 書き込みの結果の帯は、右端の「閉じる」を押すと消える
    @Test
    fun closeButtonHidesWriteOutcomeBanner() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        fixture.store.onNextPut(PutFault.Forbidden, key = fixture.isManifest)
        runOnUiThread { fixture.controller.createFolder(ROOT_ID, "C") }
        val banner = assertNotNull(describeWriteOutcome(WriteOutcome.Failed(SyncFailure.Forbidden))).text
        onNodeWithText(banner).assertIsDisplayed()

        onNodeWithText(CLOSE_LABEL).performClick()

        onNodeWithText(banner).assertDoesNotExist()
    }

    // マニフェストの読み直しに失敗したことの帯も、右端の「閉じる」を押すと消える
    @Test
    fun closeButtonHidesReadFailureBanner() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        fixture.store.onNextGet(GetFault.Forbidden, key = fixture.isManifest)
        onNodeWithContentDescription(RELOAD_DESCRIPTION).performClick()
        val banner = describeSyncFailure(SyncFailure.Forbidden).text
        onNodeWithText(banner).assertIsDisplayed()

        onNodeWithText(CLOSE_LABEL).performClick()

        onNodeWithText(banner).assertDoesNotExist()
    }

    // 新しいフォルダのダイアログは、名前が空のあいだは「作成」を押せず、欄の下に名前を入れるよう添える。名前を入れれば押せる
    @Test
    fun newFolderDialogRequiresName() = runCloudMarksScreenTest(temporaryFolder) {
        treeRow("A").performMouseInput { rightClick() }
        onNodeWithText(NEW_FOLDER_LABEL).performClick()
        onNodeWithText(CREATE_LABEL).assertIsNotEnabled()
        onNodeWithText(EMPTY_NAME_NOTICE).assertIsDisplayed()

        onNode(hasSetTextAction()).performTextInput("C")

        onNodeWithText(CREATE_LABEL).assertIsEnabled()
        onNodeWithText(EMPTY_NAME_NOTICE).assertDoesNotExist()
    }

    // 項目のタイトルの変更は、タイトルを空にしても「変更」を押せ、名前を入れるよう添えない
    @Test
    fun renameItemDialogAllowsEmptyTitle() = runCloudMarksScreenTest(temporaryFolder) {
        onNodeWithText(ROOT_ITEMS[0].title).performMouseInput { rightClick() }
        onNodeWithText(RENAME_ITEM_LABEL).performClick()

        onNode(hasSetTextAction()).performTextClearance()

        onNodeWithText(CHANGE_LABEL).assertIsEnabled()
        onNodeWithText(EMPTY_NAME_NOTICE).assertDoesNotExist()
    }

    // マウスを押してから離すまでにわずかに動いても（ドラッグを始める閾値まで）、クリックとして扱う。
    // 項目は既定のブラウザで開き、Ctrl を押しながらなら選ぶ項目を足し（ドラッグになると、掴んだ項目だけを選び直す）、ツリーのフォルダは開く
    @Test
    fun slightMouseMoveStillClicks() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        val openedFirst = listOf(CHROME.name to listOf(ROOT_ITEMS[0].url))
        onNodeWithText(ROOT_ITEMS[0].title).performMouseInput { clickWithJitter() }
        waitForIdle()
        assertEquals(openedFirst, fixture.browserOpener.openedUrls)

        onRoot().performKeyInput { keyDown(Key.CtrlLeft) }
        onNodeWithText(ROOT_ITEMS[0].title).performMouseInput { clickWithJitter() }
        onNodeWithText(ROOT_ITEMS[1].title).performMouseInput { clickWithJitter() }
        onRoot().performKeyInput { keyUp(Key.CtrlLeft) }

        assertEquals(ROOT_ITEMS.map { it.id }.toSet(), fixture.state.browse.selectedItemIds)

        treeRow("B").performMouseInput { clickWithJitter() }
        waitForIdle()

        assertEquals(FOLDER_B, fixture.state.browse.currentFolderId)
        assertEquals(openedFirst, fixture.browserOpener.openedUrls)
    }

    // 押してから閾値を越えて動かして離すと、ドラッグを始めない読むだけのツリーでも、項目を開かない。動かさずに離せば開く
    @Test
    fun movingBeyondThresholdDoesNotOpen() = runCloudMarksScreenTest(
        temporaryFolder,
        arrange = { it.store.seed(initialManifest(version = NEWER_VERSION)) },
    ) { fixture ->
        assertTrue(fixture.state.browse.readOnly)

        onNodeWithText(ROOT_ITEMS[0].title).performMouseInput {
            moveTo(center)
            press()
            moveBy(BEYOND_THRESHOLD)
            release()
        }
        waitForIdle()
        assertEquals(emptyList(), fixture.browserOpener.openedUrls)

        onNodeWithText(ROOT_ITEMS[0].title).performMouseInput { click() }
        waitForIdle()
        assertEquals(listOf(CHROME.name to listOf(ROOT_ITEMS[0].url)), fixture.browserOpener.openedUrls)
        assertEquals(emptySet(), fixture.state.browse.selectedItemIds)
    }

    // マウスで押して、行の外へ動かして離した（クリックにならなかった）あとでも、キーボードで項目を押すと、その項目だけを選ぶ
    @Test
    fun keyboardPressAfterCancelledMousePressSelects() = runCloudMarksScreenTest(
        temporaryFolder,
        arrange = { it.store.seed(initialManifest(version = NEWER_VERSION)) },
    ) { fixture ->
        onNodeWithText(ROOT_ITEMS[0].title).performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(0f, OUTSIDE_ROW_DISTANCE))
            release()
        }
        waitForIdle()

        onNodeWithText(ROOT_ITEMS[0].title).requestFocus()
        onNodeWithText(ROOT_ITEMS[0].title).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals(setOf(ROOT_ITEMS[0].id), fixture.state.browse.selectedItemIds)
        assertEquals(emptyList(), fixture.browserOpener.openedUrls)
    }

    // マウスで項目を押し、閾値を越えて動かしてからツリーのフォルダの上で離すと、そのフォルダの項目の末尾へ移す
    @Test
    fun draggingItemOntoTreeFolderMovesIt() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        dragWithMouse(from = onNodeWithText(ROOT_ITEMS[0].title).spot(RowSpot.MIDDLE), to = treeRow("B").spot(RowSpot.MIDDLE))

        assertEquals(listOf(ROOT_ITEMS[0]), fixture.storedItems(FOLDER_B))
        assertEquals(listOf(ROOT_ITEMS[1]), fixture.storedItems(ROOT_ID))
    }

    // フォルダの削除は、名前を入れた確認の文を出し、「削除」を押すと、子孫ごと消す
    @Test
    fun deleteFolderAsksBeforeDeleting() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        treeRow("A").performMouseInput { rightClick() }
        onNodeWithText(DELETE_LABEL).performClick()

        onNodeWithText(DELETE_FOLDER_TITLE).assertIsDisplayed()
        onNodeWithText(deleteFolderConfirmation("A")).assertIsDisplayed()
        onNodeWithText(DELETE_LABEL).performClick()
        waitForIdle()

        assertEquals(setOf(ROOT_ID, FOLDER_B), fixture.state.browse.tree?.state?.folders?.keys)
        assertEquals(setOf(ROOT_ID, FOLDER_B), fixture.store.storedManifest().folders.map { it.id }.toSet())
    }

    // 「元に戻す」を押しても受け付けられなければ（マニフェストを読み直している最中）、同じお知らせを出し直す。受け付けられれば戻す
    @Test
    fun undoNoticeIsShownAgainWhenUndoIsRefused() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        runOnUiThread {
            fixture.controller.selectItem(ROOT_ITEMS[0].id, SelectionMode.SINGLE)
            fixture.controller.deleteSelectedItems()
        }
        val notice = deletedItemsNotice(1)
        onNodeWithText(notice).assertIsDisplayed()
        val gate = fixture.stopNextGet(fixture.isManifest)
        onNodeWithContentDescription(RELOAD_DESCRIPTION).performClick()

        onNodeWithText(UNDO_LABEL).performClick()

        onNodeWithText(notice).assertIsDisplayed()
        assertEquals(listOf(ROOT_ITEMS[1]), fixture.storedItems(ROOT_ID))

        gate.complete(Unit)
        waitForIdle()
        onNodeWithText(UNDO_LABEL).performClick()
        waitForIdle()

        assertEquals(ROOT_ITEMS, fixture.storedItems(ROOT_ID))
        onNodeWithText(notice).assertDoesNotExist()
    }

    // 元に戻すお知らせは、設定の画面では出さず、メインの画面へ戻るとまた出す
    @Test
    fun undoNoticeIsShownOnlyOnMainScreen() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        runOnUiThread {
            fixture.controller.selectItem(ROOT_ITEMS[0].id, SelectionMode.SINGLE)
            fixture.controller.deleteSelectedItems()
        }
        val notice = deletedItemsNotice(1)
        onNodeWithText(notice).assertIsDisplayed()

        onNodeWithContentDescription(SETTINGS_DESCRIPTION).performClick()
        onNodeWithText(notice).assertDoesNotExist()

        onNodeWithContentDescription(BACK_DESCRIPTION).performClick()
        onNodeWithText(notice).assertIsDisplayed()
    }
}
