package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.app.ControllerFixture
import to.sava.cloudmarksmultiplatform.app.Screen
import to.sava.cloudmarksmultiplatform.app.WriteOutcome
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.GetFault
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.ListFault
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.PutFault
import to.sava.cloudmarksmultiplatform.storage.encodeManifestGzip
import to.sava.cloudmarksmultiplatform.storage.manifestKey
import to.sava.cloudmarksmultiplatform.sync.MAX_RESENDS
import to.sava.cloudmarksmultiplatform.sync.ROOT_FILE_ID
import to.sava.cloudmarksmultiplatform.sync.ROOT_ID
import to.sava.cloudmarksmultiplatform.sync.ROOT_ITEMS
import to.sava.cloudmarksmultiplatform.sync.RevisionFailure
import to.sava.cloudmarksmultiplatform.sync.SyncFailure
import to.sava.cloudmarksmultiplatform.sync.counts
import to.sava.cloudmarksmultiplatform.sync.fileId
import to.sava.cloudmarksmultiplatform.sync.initialManifest
import to.sava.cloudmarksmultiplatform.sync.item
import to.sava.cloudmarksmultiplatform.sync.replaceItemsAsOtherWriter
import to.sava.cloudmarksmultiplatform.sync.seed
import to.sava.cloudmarksmultiplatform.sync.storedManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** ほかの書き手がルートフォルダの項目を替える時刻。起動の前の用意で置くマニフェストより新しい。 */
private val LATER = Instant.parse("2026-11-17T14:08:00Z")

/** [LATER] よりあとに、ほかの書き手が書く時刻。 */
private val LATEST = Instant.parse("2026-11-18T09:30:00Z")

/** ほかの書き手が [LATER] に置く、ルートフォルダの項目。 */
private val LATER_ROOT_ITEMS = ROOT_ITEMS + item(9)

/**
 * 起動の前の用意。[LATER] に、ほかの書き手としてルートフォルダの項目を [LATER_ROOT_ITEMS] に替える。
 * いまのマニフェストは [LATER] のもので、戻せる時点は、起動の前に置いた [initialManifest] の 1 つ。
 */
private val laterRootItems: (ControllerFixture) -> Unit = { fixture ->
    fixture.store.serverTime = LATER
    fixture.store.replaceItemsAsOtherWriter(ROOT_ID, fileId(30), LATER_ROOT_ITEMS)
}

/** マニフェストのバージョンのうち、いまのもの以外の `LastModified` の表記（[formatRevisionTime]）。新しい順。 */
private fun ControllerFixture.revisionTimes(): List<String> =
    store.versionsOf(manifestKey(store.prefix)).drop(1).map { formatRevisionTime(it.lastModified) }

/** 設定の画面を開く。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.openSettingsScreen() {
    onNodeWithContentDescription(SETTINGS_DESCRIPTION).performClick()
    waitForIdle()
}

/** 設定の画面を開き、以前の状態に戻す画面を開くボタンを押して、一覧を読み終えるまで待つ。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.openRevisionsScreen() {
    openSettingsScreen()
    onNodeWithText(REVISIONS_TITLE).performScrollTo().performClick()
    waitForIdle()
}

/** 以前の状態に戻す画面を開き、最も古い時点の行を押して、確認を出すまで待つ。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.openOldestRevision(fixture: ControllerFixture) {
    openRevisionsScreen()
    onNodeWithText(fixture.revisionTimes().last()).performClick()
    waitForIdle()
}

@OptIn(ExperimentalTestApi::class)
class RevisionsScreenTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // 設定の画面の末尾に、以前の状態に戻す画面を開くボタンと説明がある。押すと、題が以前の状態に戻す画面のものになり、戻せる時点を
    // 新しい順に日時で並べる
    @Test
    fun settingsScreenOpensRevisionList() = runCloudMarksScreenTest(
        temporaryFolder,
        arrange = { fixture ->
            laterRootItems(fixture)
            fixture.store.serverTime = LATEST
            fixture.store.replaceItemsAsOtherWriter(ROOT_ID, fileId(31), ROOT_ITEMS)
        },
    ) { fixture ->
        openSettingsScreen()
        onNodeWithText(REVISIONS_TITLE).performScrollTo().assertIsEnabled()
        onNodeWithText(REVISIONS_DESCRIPTION).performScrollTo().assertIsDisplayed()

        onNodeWithText(REVISIONS_TITLE).performClick()
        waitForIdle()

        assertEquals(Screen.REVISIONS, fixture.state.screen)
        // 設定の画面のボタンは無くなり、AppBar の題だけになる
        onNodeWithText(REVISIONS_TITLE).assertIsDisplayed()
        val (newer, older) = fixture.revisionTimes()
        assertEquals(formatRevisionTime(LATER), newer)
        val newerTop = onNodeWithText(newer).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        val olderTop = onNodeWithText(older).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        assertTrue(newerTop < olderTop)
    }

    // 読むだけのツリーでは、以前の状態に戻す画面を開くボタンを押せない
    @Test
    fun restoreButtonIsDisabledForReadOnlyTree() = runCloudMarksScreenTest(
        temporaryFolder,
        arrange = { it.store.seed(initialManifest(version = 2)) },
    ) {
        openSettingsScreen()

        onNodeWithText(REVISIONS_TITLE).performScrollTo().assertIsNotEnabled()
    }

    // 戻せる時点が無ければ、そのことを出す
    @Test
    fun emptyListShowsNotice() = runCloudMarksScreenTest(temporaryFolder) {
        openRevisionsScreen()

        onNodeWithText(NO_REVISIONS_NOTICE).assertIsDisplayed()
    }

    // 一覧を読めなければ、読めなかったことの文に S3 の失敗の文を続けて出す
    @Test
    fun unreadableListShowsFailure() = runCloudMarksScreenTest(temporaryFolder, arrange = laterRootItems) { fixture ->
        fixture.store.onNextListVersions(ListFault.Forbidden)

        openRevisionsScreen()

        onNodeWithText(describeRevisionListFailure(SyncFailure.Forbidden).text).assertIsDisplayed()
    }

    // 行を押すと、確認を出す。題、戻す時点の日時を入れた本文、その時点といまのフォルダと項目の数、その時点のデータを保存したアプリ。
    // キャンセルで閉じる
    @Test
    fun confirmationShowsTimeCountsAndSavingApp() = runCloudMarksScreenTest(temporaryFolder, arrange = laterRootItems) { fixture ->
        openOldestRevision(fixture)

        onNodeWithText(RESTORE_CONFIRMATION_TITLE).assertIsDisplayed()
        onNodeWithText(restoreConfirmation(fixture.revisionTimes().last())).assertIsDisplayed()
        onNodeWithText(revisionCountsLine(initialManifest().counts())).assertIsDisplayed()
        onNodeWithText(currentCountsLine(fixture.store.storedManifest().counts())).assertIsDisplayed()
        onNodeWithText(assertNotNull(savedByLine("android"))).assertIsDisplayed()

        onNodeWithText(CANCEL_LABEL).performClick()
        waitForIdle()

        onNodeWithText(RESTORE_CONFIRMATION_TITLE).assertDoesNotExist()
        assertNull(fixture.state.revisions.selection)
    }

    // その時点のマニフェストの updatedBy が知らない値なら、保存したアプリの行を出さない
    @Test
    fun unknownSavingAppHasNoLine() = runCloudMarksScreenTest(
        temporaryFolder,
        arrange = { fixture ->
            val manifest = initialManifest()
            fixture.store.serverTime = LATER
            fixture.store.overwrite(
                manifestKey(fixture.store.prefix),
                encodeManifestGzip(manifest.copy(header = manifest.header.copy(updatedBy = "cli"))),
            )
            fixture.store.serverTime = LATEST
            fixture.store.replaceItemsAsOtherWriter(ROOT_ID, fileId(30), LATER_ROOT_ITEMS)
        },
    ) { fixture ->
        openRevisionsScreen()
        onNodeWithText(formatRevisionTime(LATER)).performClick()
        waitForIdle()

        onNodeWithText(restoreConfirmation(formatRevisionTime(LATER))).assertIsDisplayed()
        onNodeWithText(revisionCountsLine(initialManifest().counts())).assertIsDisplayed()
        listOf("android", "desktop", "host").map { assertNotNull(savedByLine(it)) }.forEach { line ->
            onNodeWithText(line).assertDoesNotExist()
        }
        assertEquals(Screen.REVISIONS, fixture.state.screen)
    }

    // 「戻す」を押すと、メインの画面へ移って戻す時点のツリーを出し、戻した時点の日時を入れたお知らせを出す
    @Test
    fun restoreShowsRestoredTreeAndNotice() = runCloudMarksScreenTest(temporaryFolder, arrange = laterRootItems) { fixture ->
        openOldestRevision(fixture)
        val time = fixture.revisionTimes().last()

        onNodeWithText(RESTORE_LABEL).performClick()
        waitForIdle()

        assertEquals(Screen.MAIN, fixture.state.screen)
        ROOT_ITEMS.forEach { onNodeWithText(it.title).assertIsDisplayed() }
        onNodeWithText(item(9).title).assertDoesNotExist()
        onNodeWithText(restoredNotice(time)).assertIsDisplayed()
    }

    // 戻している最中は、戻していることの帯を出し、確認を閉じ、AppBar の戻るボタンを押せない。戻し終えたらメインの画面へ移る
    @Test
    fun restoringShowsBannerAndDisablesBack() = runCloudMarksScreenTest(temporaryFolder, arrange = laterRootItems) { fixture ->
        openOldestRevision(fixture)
        val gate = fixture.stopNextGet(fixture.isManifest)

        onNodeWithText(RESTORE_LABEL).performClick()
        waitForIdle()

        onNodeWithText(RESTORING_NOTICE).assertIsDisplayed()
        onNodeWithText(RESTORE_CONFIRMATION_TITLE).assertDoesNotExist()
        onNodeWithContentDescription(BACK_DESCRIPTION).assertIsNotEnabled()
        gate.complete(Unit)
        waitForIdle()
        assertEquals(Screen.MAIN, fixture.state.screen)
        onNodeWithText(RESTORING_NOTICE).assertDoesNotExist()
    }

    // 戻せなければ（この時点のデータの一部が残っていない）、一覧に残ってそのことの帯を出す。帯は「閉じる」で消える
    @Test
    fun restoreFailureShowsBanner() = runCloudMarksScreenTest(
        temporaryFolder,
        arrange = { fixture ->
            laterRootItems(fixture)
            val key = fixture.itemsKey(ROOT_ID, ROOT_FILE_ID)
            fixture.store.remove(key)
            fixture.store.expireNoncurrentVersions(key)
        },
    ) { fixture ->
        openOldestRevision(fixture)

        onNodeWithText(RESTORE_LABEL).performClick()
        waitForIdle()

        val banner = describeRestoreFailure(RevisionFailure.Incomplete).text
        onNodeWithText(banner).assertIsDisplayed()
        onNodeWithText(fixture.revisionTimes().last()).assertIsDisplayed()
        onNodeWithText(CLOSE_LABEL).performClick()
        waitForIdle()
        onNodeWithText(banner).assertDoesNotExist()
    }

    // 選んだ時点を読めなければ、一覧に残ってそのことの帯を出す
    @Test
    fun readFailureShowsBanner() = runCloudMarksScreenTest(temporaryFolder, arrange = laterRootItems) { fixture ->
        openRevisionsScreen()
        fixture.store.onNextGet(GetFault.Forbidden, key = fixture.isManifest)

        onNodeWithText(fixture.revisionTimes().last()).performClick()
        waitForIdle()

        onNodeWithText(describeRevisionReadFailure(RevisionFailure.Sync(SyncFailure.Forbidden)).text).assertIsDisplayed()
        onNodeWithText(RESTORE_CONFIRMATION_TITLE).assertDoesNotExist()
    }

    // 戻せたかどうかを確かめられなければ、メインの画面へ移り、操作を確かめられなかったときと同じ帯を出す
    @Test
    fun unconfirmedRestoreShowsWriteOutcomeBanner() = runCloudMarksScreenTest(temporaryFolder, arrange = laterRootItems) { fixture ->
        openOldestRevision(fixture)
        fixture.store.onNextPut(
            PutFault.WriteThenUnknown {
                repeat(MAX_RESENDS + 1) { fixture.store.onNextGet(GetFault.ServerError, key = fixture.isManifest) }
            },
            key = fixture.isManifest,
        )

        onNodeWithText(RESTORE_LABEL).performClick()
        waitForIdle()

        assertEquals(Screen.MAIN, fixture.state.screen)
        val outcome = assertIs<WriteOutcome.Unconfirmed>(fixture.state.write.outcome)
        onNodeWithText(assertNotNull(describeWriteOutcome(outcome)).text).assertIsDisplayed()
    }
}
