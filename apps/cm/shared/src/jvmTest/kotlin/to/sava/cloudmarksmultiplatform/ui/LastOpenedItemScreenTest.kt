package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performMouseInput
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.app.ControllerFixture
import to.sava.cloudmarksmultiplatform.app.LastOpenedItemFile
import to.sava.cloudmarksmultiplatform.sync.ROOT_ID
import to.sava.cloudmarksmultiplatform.sync.fileId
import to.sava.cloudmarksmultiplatform.sync.item
import to.sava.cloudmarksmultiplatform.sync.replaceItemsAsOtherWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** ルートフォルダの項目を 3 つにする、起動の前の用意。 */
private val threeRootItems: (ControllerFixture) -> Unit = {
    it.store.replaceItemsAsOtherWriter(ROOT_ID, fileId(30), (1..3).map { n -> item(n) })
}

@OptIn(ExperimentalTestApi::class)
class LastOpenedItemScreenTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // 項目をクリックして開くと、その行を、選んでいない行とも選んでいる行とも違う色で強調する。
    // 最後に開いた項目を選ぶと、選んでいる行と同じ色にする
    @Test
    fun openedItemRowIsHighlightedApartFromSelection() = runCloudMarksScreenTest(temporaryFolder, arrange = threeRootItems) { fixture ->
        val plain = itemRowBackground(1)

        itemRow(2).performMouseInput { click() }
        clickItemRowWithKey(3, Key.CtrlLeft)
        moveMouseToBlank()

        val lastOpened = itemRowBackground(2)
        val selected = itemRowBackground(3)
        assertEquals(item(2).id, fixture.state.lastOpenedItemId)
        assertEquals(plain, itemRowBackground(1))
        assertNotEquals(plain, lastOpened)
        assertNotEquals(plain, selected)
        assertNotEquals(selected, lastOpened)

        clickItemRowWithKey(2, Key.CtrlLeft)
        moveMouseToBlank()

        assertEquals(selected, itemRowBackground(2))
    }

    // 記録にある最後に開いた項目は、起動したときから強調する。別の項目を開くと、強調はその行へ移る
    @Test
    fun recordedItemIsHighlightedAndMovesOnOpen() = runCloudMarksScreenTest(
        temporaryFolder,
        arrange = { fixture ->
            threeRootItems(fixture)
            LastOpenedItemFile(fixture.appFiles.lastOpenedItemFile) {}.write(item(2).id)
        },
    ) {
        val plain = itemRowBackground(1)
        val lastOpened = itemRowBackground(2)
        assertNotEquals(plain, lastOpened)

        itemRow(3).performMouseInput { click() }
        moveMouseToBlank()

        assertEquals(plain, itemRowBackground(2))
        assertEquals(lastOpened, itemRowBackground(3))
    }
}
