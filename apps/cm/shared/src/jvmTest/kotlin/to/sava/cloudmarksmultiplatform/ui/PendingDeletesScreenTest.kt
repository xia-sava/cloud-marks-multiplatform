package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.app.ControllerFixture
import to.sava.cloudmarksmultiplatform.browse.SelectionMode
import to.sava.cloudmarksmultiplatform.storage.Item
import to.sava.cloudmarksmultiplatform.sync.A_ITEMS
import to.sava.cloudmarksmultiplatform.sync.FOLDER_A
import to.sava.cloudmarksmultiplatform.sync.ROOT_ID
import to.sava.cloudmarksmultiplatform.sync.ROOT_ITEMS
import kotlin.test.Test
import kotlin.test.assertEquals

/** S3 につながらないあいだに、開いているフォルダの項目 [item] を選んで消す。消したあと、つながらないままにしておく。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.deleteWhileUnreachable(fixture: ControllerFixture, item: Item) {
    fixture.store.unreachable = true
    runOnUiThread {
        fixture.controller.selectItem(item.id, SelectionMode.SINGLE)
        fixture.controller.deleteSelectedItems()
    }
    waitForIdle()
}

@OptIn(ExperimentalTestApi::class)
class PendingDeletesScreenTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // つながらないあいだに項目を消すと、リストから消し、溜めた数の帯と、溜めたことのお知らせを「元に戻す」付きで出す。
    // ふつうの削除のお知らせと、書けなかったことの帯は出さない
    @Test
    fun pendingDeleteShowsBannerAndNotice() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        deleteWhileUnreachable(fixture, ROOT_ITEMS[0])

        onNodeWithText(pendingDeletesNotice(1)).assertIsDisplayed()
        onNodeWithText(pendingDeletedItemsNotice(1)).assertIsDisplayed()
        onNodeWithText(UNDO_LABEL).assertIsDisplayed()
        onNodeWithText(deletedItemsNotice(1)).assertDoesNotExist()
        onNodeWithText(ROOT_ITEMS[0].title).assertDoesNotExist()
        onNodeWithText(ROOT_ITEMS[1].title).assertIsDisplayed()
        onNodeWithText(CLOSE_LABEL).assertDoesNotExist()
    }

    // 帯の数は、溜めた項目の数。続けて溜めると増える
    @Test
    fun bannerCountsPendingItems() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        deleteWhileUnreachable(fixture, ROOT_ITEMS[0])
        deleteWhileUnreachable(fixture, ROOT_ITEMS[1])

        onNodeWithText(pendingDeletesNotice(2)).assertIsDisplayed()
        onNodeWithText(pendingDeletesNotice(1)).assertDoesNotExist()
    }

    // お知らせの「元に戻す」を押すと、溜めた削除から外し、帯を消して、項目をまた出す。S3 には何も送らない
    @Test
    fun undoInNoticeRestoresPendingItem() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        deleteWhileUnreachable(fixture, ROOT_ITEMS[0])
        val requestsBefore = fixture.store.requests.size

        onNodeWithText(UNDO_LABEL).performClick()
        waitForIdle()

        onNodeWithText(pendingDeletesNotice(1)).assertDoesNotExist()
        onNodeWithText(pendingDeletedItemsNotice(1)).assertDoesNotExist()
        onNodeWithText(ROOT_ITEMS[0].title).assertIsDisplayed()
        assertEquals(requestsBefore, fixture.store.requests.size)
    }

    // 溜めた削除の項目は、子のフォルダの行の数からも除く
    @Test
    fun childFolderCountExcludesPendingItems() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        runOnUiThread { fixture.controller.openFolder(FOLDER_A) }
        waitForIdle()
        runOnUiThread { fixture.controller.openFolder(ROOT_ID) }
        waitForIdle()
        onNodeWithText("${A_ITEMS.size} 件").assertIsDisplayed()
        runOnUiThread { fixture.controller.openFolder(FOLDER_A) }
        waitForIdle()

        deleteWhileUnreachable(fixture, A_ITEMS[0])
        runOnUiThread { fixture.controller.openFolder(ROOT_ID) }
        waitForIdle()

        // A と B の行
        onAllNodesWithText("0 件").assertCountEquals(2)
    }

    // つながってから読み直すと、溜めた削除を書き、帯を消す
    @Test
    fun bannerDisappearsAfterPendingDeletesAreWritten() = runCloudMarksScreenTest(temporaryFolder) { fixture ->
        deleteWhileUnreachable(fixture, ROOT_ITEMS[0])
        onNodeWithText(pendingDeletesNotice(1)).assertIsDisplayed()
        fixture.store.unreachable = false

        onNodeWithContentDescription(RELOAD_DESCRIPTION).performClick()
        waitForIdle()

        onNodeWithText(pendingDeletesNotice(1)).assertDoesNotExist()
        assertEquals(listOf(ROOT_ITEMS[1]), fixture.storedItems(ROOT_ID))
        onNodeWithText(ROOT_ITEMS[0].title).assertDoesNotExist()
    }
}
