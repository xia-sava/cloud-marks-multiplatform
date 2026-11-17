package to.sava.cloudmarksmultiplatform.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.browse.SelectionMode
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.Request
import to.sava.cloudmarksmultiplatform.storage.manifestKey
import to.sava.cloudmarksmultiplatform.sync.A_ITEMS
import to.sava.cloudmarksmultiplatform.sync.FOLDER_A
import to.sava.cloudmarksmultiplatform.sync.FOLDER_B
import to.sava.cloudmarksmultiplatform.sync.FolderReadResult
import to.sava.cloudmarksmultiplatform.sync.ROOT_ID
import to.sava.cloudmarksmultiplatform.sync.addFolderAsOtherWriter
import to.sava.cloudmarksmultiplatform.sync.folderId
import to.sava.cloudmarksmultiplatform.sync.gets
import to.sava.cloudmarksmultiplatform.sync.seed
import to.sava.cloudmarksmultiplatform.sync.storedManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** ほかの書き手が足すフォルダ。 */
private val OTHER_FOLDER = folderId(7)

// runCurrent が ExperimentalCoroutinesApi であるため。
@OptIn(ExperimentalCoroutinesApi::class)
class CloudMarksControllerStorageChangeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** マニフェストと保存済みの設定を置いて起動したフィクスチャ。 */
    private fun TestScope.started(): ControllerFixture {
        val fixture = ControllerFixture(this, temporaryFolder)
        fixture.store.seed()
        fixture.storeSettings()
        fixture.controller.start()
        runCurrent()
        return fixture
    }

    // 知らせを受けると、読み直すボタンと同じ読み直しが走る。マニフェストを条件付きで GET して新しいツリーに替え、
    // 開いているノード・開いているフォルダ・選んでいる項目を保つ
    @Test
    fun changeNotificationRereadsManifestAndKeepsBrowseState() = runTest {
        val fixture = started()
        fixture.controller.openFolder(FOLDER_A)
        runCurrent()
        fixture.controller.selectItem(A_ITEMS.first().id, SelectionMode.SINGLE)
        fixture.controller.toggleExpanded(FOLDER_B)
        val eTag = assertNotNull(fixture.state.browse.tree).eTag
        val expandedBefore = fixture.state.browse.expandedFolders
        fixture.store.addFolderAsOtherWriter(OTHER_FOLDER)
        val requestsBefore = fixture.store.requests.size

        fixture.storageChanges.emit(Unit)
        runCurrent()

        val browse = fixture.state.browse
        assertEquals(
            listOf(Request.Get(manifestKey(fixture.store.prefix), ifNoneMatch = eTag)),
            fixture.store.requests.drop(requestsBefore),
        )
        assertEquals(fixture.store.storedManifest(), browse.tree?.manifest)
        assertEquals(FOLDER_A, browse.currentFolderId)
        assertEquals(expandedBefore, browse.expandedFolders)
        assertEquals(setOf(A_ITEMS.first().id), browse.selectedItemIds)
        assertEquals(FolderReadResult.Loaded(A_ITEMS), browse.currentItems)
        assertFalse(browse.loading)
    }

    // マニフェストを読んでいる最中に来た知らせでは、何も起きない（読み直しを重ねない）
    @Test
    fun changeNotificationWhileLoadingIsDropped() = runTest {
        val fixture = started()
        val manifestGetsBefore = fixture.store.gets(fixture.isManifest).size
        val gate = fixture.stopNextGet(fixture.isManifest)
        fixture.controller.reload()
        runCurrent()
        assertTrue(fixture.state.browse.loading)

        fixture.storageChanges.emit(Unit)
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(manifestGetsBefore + 1, fixture.store.gets(fixture.isManifest).size)
        assertFalse(fixture.state.browse.loading)
    }

    // 操作を書いている最中に来た知らせでは、何も起きない（書き込みの手順 1 の GET のほかに、マニフェストを GET しない）
    @Test
    fun changeNotificationWhileWritingIsDropped() = runTest {
        val fixture = started()
        val manifestGetsBefore = fixture.store.gets(fixture.isManifest).size
        val gate = fixture.stopNextGet(fixture.isManifest)
        fixture.controller.createFolder(ROOT_ID, "C")
        runCurrent()
        assertTrue(fixture.state.write.writing)

        fixture.storageChanges.emit(Unit)
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(manifestGetsBefore + 1, fixture.store.gets(fixture.isManifest).size)
        assertEquals(WriteOutcome.Written, fixture.state.write.outcome)
        assertFalse(fixture.state.write.writing)
    }

    // 設定が無いときに来た知らせでは、何も起きない（接続も作らない）
    @Test
    fun changeNotificationWithoutSettingsDoesNothing() = runTest {
        val fixture = ControllerFixture(this, temporaryFolder)
        fixture.controller.start()
        runCurrent()

        fixture.storageChanges.emit(Unit)
        runCurrent()

        assertEquals(Screen.SETTINGS, fixture.state.screen)
        assertEquals(emptyList(), fixture.connected)
        assertEquals(emptyList(), fixture.store.requests)
    }

    // 設定の画面を開いているあいだに来た知らせでは、何も起きない（メインの画面でないときは読み直さない）
    @Test
    fun changeNotificationOnSettingsScreenIsDropped() = runTest {
        val fixture = started()
        fixture.controller.openSettings()
        assertEquals(Screen.SETTINGS, fixture.state.screen)
        val requestsBefore = fixture.store.requests.size

        fixture.storageChanges.emit(Unit)
        runCurrent()

        assertEquals(requestsBefore, fixture.store.requests.size)
        assertFalse(fixture.state.browse.loading)
    }

    // 知らせを集めるのは start から close までで、close で取り消す
    @Test
    fun changesAreCollectedFromStartUntilClose() = runTest {
        val fixture = ControllerFixture(this, temporaryFolder)
        fixture.store.seed()
        fixture.storeSettings()
        assertEquals(0, fixture.storageChanges.subscriptionCount.value)

        fixture.controller.start()
        runCurrent()
        assertEquals(1, fixture.storageChanges.subscriptionCount.value)

        fixture.controller.close()
        runCurrent()
        assertEquals(0, fixture.storageChanges.subscriptionCount.value)
    }
}
