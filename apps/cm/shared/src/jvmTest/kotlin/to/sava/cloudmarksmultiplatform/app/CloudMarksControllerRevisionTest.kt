package to.sava.cloudmarksmultiplatform.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.browse.SelectionMode
import to.sava.cloudmarksmultiplatform.operations.ItemRef
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.GetFault
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.ListFault
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.PutFault
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.Request
import to.sava.cloudmarksmultiplatform.remote.UNREACHABLE_DETAIL
import to.sava.cloudmarksmultiplatform.settings.SETTINGS
import to.sava.cloudmarksmultiplatform.settings.SettingsForm
import to.sava.cloudmarksmultiplatform.storage.Item
import to.sava.cloudmarksmultiplatform.storage.ManifestDecodeResult
import to.sava.cloudmarksmultiplatform.storage.decodeManifestGzip
import to.sava.cloudmarksmultiplatform.storage.encodeManifestGzip
import to.sava.cloudmarksmultiplatform.storage.manifestKey
import to.sava.cloudmarksmultiplatform.sync.FOLDER_A
import to.sava.cloudmarksmultiplatform.sync.FolderReadResult
import to.sava.cloudmarksmultiplatform.sync.LoadedRevision
import to.sava.cloudmarksmultiplatform.sync.MAX_RESENDS
import to.sava.cloudmarksmultiplatform.sync.ROOT_FILE_ID
import to.sava.cloudmarksmultiplatform.sync.ROOT_ID
import to.sava.cloudmarksmultiplatform.sync.ROOT_ITEMS
import to.sava.cloudmarksmultiplatform.sync.Revision
import to.sava.cloudmarksmultiplatform.sync.RevisionFailure
import to.sava.cloudmarksmultiplatform.sync.SyncFailure
import to.sava.cloudmarksmultiplatform.sync.addFolderAsOtherWriter
import to.sava.cloudmarksmultiplatform.sync.fileId
import to.sava.cloudmarksmultiplatform.sync.folderId
import to.sava.cloudmarksmultiplatform.sync.gets
import to.sava.cloudmarksmultiplatform.sync.initialManifest
import to.sava.cloudmarksmultiplatform.sync.item
import to.sava.cloudmarksmultiplatform.sync.puts
import to.sava.cloudmarksmultiplatform.sync.replaceItemsAsOtherWriter
import to.sava.cloudmarksmultiplatform.sync.seed
import to.sava.cloudmarksmultiplatform.sync.storedItems
import to.sava.cloudmarksmultiplatform.sync.storedManifest
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** [initialManifest] を置く時刻。戻せる時点になる。 */
private val T1 = Instant.parse("2026-11-10T00:00:00Z")

/** ほかの書き手がルートフォルダの項目を替える時刻。 */
private val T2 = Instant.parse("2026-11-11T00:00:00Z")

/** そのあとにほかの書き手が書く時刻。 */
private val T3 = Instant.parse("2026-11-12T00:00:00Z")

/** さらにそのあとにほかの書き手が書く時刻。 */
private val T4 = Instant.parse("2026-11-13T00:00:00Z")

/** ほかの書き手が [T2] に置く、ルートフォルダの項目ファイルの ID と項目。 */
private val LATER_ROOT_FILE_ID = fileId(30)
private val LATER_ROOT_ITEMS = ROOT_ITEMS + item(9)

/** ほかの書き手が足すフォルダ。 */
private val OTHER_FOLDER = folderId(7)

/** S3 につながらないときの失敗。 */
private val UNREACHABLE = SyncFailure.Unreachable(UNREACHABLE_DETAIL)

/** ルートフォルダの項目 0 を指す、溜めた削除の対象。 */
private val ROOT_TARGET = ItemRef(ROOT_ID, ROOT_ITEMS[0].id)

private val ControllerFixture.manifestKey: String get() = manifestKey(store.prefix)

/** マニフェストの、`LastModified` が [time] のバージョンを、戻せる時点として返す。 */
private fun ControllerFixture.revisionAt(time: Instant): Revision =
    store.versionsOf(manifestKey).single { it.lastModified == time }.let { Revision(it.versionId, it.lastModified) }

/** S3 に置いてあるマニフェストが指す、フォルダ [folderId] の項目。 */
private fun ControllerFixture.storedItems(folderId: String): List<Item> = store.storedItems(store.storedManifest(), folderId)

/** マニフェストの PUT の数。 */
private val ControllerFixture.manifestPuts: Int get() = store.puts(isManifest).size

/** 接続先 SETTINGS の、溜めた削除の記録のファイルに書いてある対象。 */
private fun ControllerFixture.recordedPendingDeletes(): Set<ItemRef> =
    PendingDeletesFile(pendingDeletesFile(appFiles.stateDirectory, SETTINGS.bucket, SETTINGS.prefix)) {}.read()

/** 接続先 SETTINGS の、溜めた削除の記録のファイルがあるか。 */
private fun ControllerFixture.hasPendingDeletesRecord(): Boolean =
    pendingDeletesFile(appFiles.stateDirectory, SETTINGS.bucket, SETTINGS.prefix).exists()

/** 開いているフォルダの項目 [item] を選んで消す。 */
private fun ControllerFixture.deleteItem(item: Item) {
    controller.selectItem(item.id, SelectionMode.SINGLE)
    controller.deleteSelectedItems()
}

/**
 * マニフェストのバージョンを、中身を [body] にして [T3] に 1 つ置き、[T4] にいまのマニフェストを置き直す。
 * 戻せる時点 [T3] の中身が [body] になり、いまのマニフェストの中身は替わらない。
 */
private fun ControllerFixture.insertManifestVersionAtT3(body: ByteArray) {
    val current = store.storedManifest()
    store.serverTime = T3
    store.overwrite(manifestKey, body)
    store.serverTime = T4
    store.overwrite(manifestKey, encodeManifestGzip(current))
}

/**
 * 次のバージョンの一覧のページを止める。返したものを完了させると進む。止めているあいだに取り消されたら、[ControllerFixture.events] に
 * [CANCELLED] を記録する。
 */
private fun ControllerFixture.stopNextListVersions(): CompletableDeferred<Unit> {
    val gate = CompletableDeferred<Unit>()
    store.onNextListVersions(
        ListFault.RunBefore {
            try {
                gate.await()
            } catch (cancellation: CancellationException) {
                events += CANCELLED
                throw cancellation
            }
        },
    )
    return gate
}

/** 「not gzip」を置いたマニフェストを読んだときの問題。読んだ結果から取り出す。 */
private fun corruptManifestProblem() =
    assertIs<ManifestDecodeResult.Corrupt>(decodeManifestGzip("not gzip".toByteArray())).problem

// runCurrent が ExperimentalCoroutinesApi であるため。
@OptIn(ExperimentalCoroutinesApi::class)
class CloudMarksControllerRevisionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /**
     * [T1] に [initialManifest] を置き、[T2] にほかの書き手としてルートフォルダの項目を [LATER_ROOT_ITEMS] に替えてから、保存済みの設定で
     * 起動したフィクスチャ。いまのマニフェストは [T2] のもので、戻せる時点は [T1] のもの。[arrange] は起動の前に走らせる。
     */
    private fun TestScope.started(arrange: (ControllerFixture) -> Unit = {}): ControllerFixture {
        val fixture = ControllerFixture(this, temporaryFolder)
        fixture.store.serverTime = T1
        fixture.store.seed()
        fixture.store.serverTime = T2
        fixture.store.replaceItemsAsOtherWriter(ROOT_ID, LATER_ROOT_FILE_ID, LATER_ROOT_ITEMS)
        fixture.storeSettings()
        arrange(fixture)
        fixture.controller.start()
        runCurrent()
        return fixture
    }

    /** 設定の画面から以前の状態に戻す画面を開き、一覧を読み終えるまで進める。 */
    private fun TestScope.openRevisionsScreen(fixture: ControllerFixture) {
        fixture.controller.openSettings()
        fixture.controller.openRevisions()
        runCurrent()
    }

    /** 以前の状態に戻す画面を開いて時点 [time] を選び、戻すかを確かめる状態まで進める。読んだ時点を返す。 */
    private fun TestScope.confirm(fixture: ControllerFixture, time: Instant = T1): LoadedRevision {
        openRevisionsScreen(fixture)
        fixture.controller.selectRevision(fixture.revisionAt(time))
        runCurrent()
        return assertIs<RevisionSelection.Confirming>(fixture.state.revisions.selection).loaded
    }

    /** 起動したあと、S3 につながらないあいだにルートフォルダの項目 0 を消して溜め、またつながるようにしたフィクスチャ。 */
    private fun TestScope.startedWithPendingDelete(): ControllerFixture =
        started().apply {
            store.unreachable = true
            deleteItem(ROOT_ITEMS[0])
            runCurrent()
            store.unreachable = false
            assertEquals(setOf(ROOT_TARGET), state.browse.pendingDeletes)
        }

    // 設定の画面から開くと、以前の状態に戻す画面に移り、一覧を読んでいる状態（画面の状態の初めの値）から始める。読めたら、
    // いまのバージョンを除いて新しい順に並べる
    @Test
    fun openingListsRevisionsNewestFirst() = runTest {
        val fixture = started { fixture ->
            fixture.store.serverTime = T3
            fixture.store.addFolderAsOtherWriter(OTHER_FOLDER)
        }
        fixture.controller.openSettings()

        fixture.controller.openRevisions()

        assertEquals(Screen.REVISIONS, fixture.state.screen)
        assertEquals(RevisionsScreenState(), fixture.state.revisions)
        runCurrent()
        assertEquals(
            RevisionListState.Listed(listOf(fixture.revisionAt(T2), fixture.revisionAt(T1))),
            fixture.state.revisions.list,
        )
    }

    // 戻せる時点が無ければ空の一覧、一覧を読めなければ（権限が無い・つながらない）その失敗
    @Test
    fun emptyOrUnreadableList() = runTest {
        val cases: List<Triple<String, (ControllerFixture) -> Unit, RevisionListState>> = listOf(
            Triple(
                "いまのバージョンしか無い",
                { it.store.expireNoncurrentVersions(it.manifestKey) },
                RevisionListState.Listed(emptyList()),
            ),
            Triple("権限が無い", { it.store.onNextListVersions(ListFault.Forbidden) }, RevisionListState.Failed(SyncFailure.Forbidden)),
            Triple("つながらない", { it.store.unreachable = true }, RevisionListState.Failed(UNREACHABLE)),
        )
        cases.forEach { (name, arrange, expected) ->
            val fixture = started()
            arrange(fixture)

            openRevisionsScreen(fixture)

            assertEquals(expected, fixture.state.revisions.list, name)
            assertEquals(Screen.REVISIONS, fixture.state.screen, name)
        }
    }

    // 以前の状態に戻すことを受け付けないとき（設定の画面でない・設定の保存の最中・読むだけのツリー・ツリーが無い・操作を書いている最中・
    // マニフェストを読み直している最中）は開かず、一覧も読まない
    @Test
    fun notOpenedWhenRestoreIsUnavailable() = runTest {
        /** 場合の名前、起動の前の用意、起動したあとに開こうとする操作。 */
        data class UnavailableCase(
            val name: String,
            val arrange: (ControllerFixture) -> Unit,
            val act: (ControllerFixture) -> Unit,
        )
        val openFromSettings: (ControllerFixture) -> Unit = { fixture ->
            fixture.controller.openSettings()
            fixture.controller.openRevisions()
        }
        val cases = listOf(
            UnavailableCase("メインの画面", arrange = {}) { it.controller.openRevisions() },
            UnavailableCase("設定の保存の最中", arrange = {}) { fixture ->
                fixture.controller.openSettings()
                fixture.controller.saveSettings()
                fixture.controller.openRevisions()
            },
            UnavailableCase(
                "読むだけのツリー",
                arrange = { it.store.overwrite(it.manifestKey, encodeManifestGzip(initialManifest(version = 2))) },
                act = openFromSettings,
            ),
            UnavailableCase("ツリーが無い", arrange = { it.store.unreachable = true }, act = openFromSettings),
            UnavailableCase("操作を書いている最中", arrange = {}) { fixture ->
                fixture.stopNextGet(fixture.isManifest)
                fixture.deleteItem(ROOT_ITEMS[0])
                assertTrue(fixture.state.write.writing)
                openFromSettings(fixture)
            },
            UnavailableCase("マニフェストを読み直している最中", arrange = {}) { fixture ->
                fixture.stopNextGet(fixture.isManifest)
                fixture.controller.reload()
                assertTrue(fixture.state.browse.loading)
                openFromSettings(fixture)
            },
        )
        cases.forEach { (name, arrange, act) ->
            val fixture = started(arrange)

            act(fixture)

            assertNotEquals(Screen.REVISIONS, fixture.state.screen, name)
            runCurrent()
            assertNotEquals(Screen.REVISIONS, fixture.state.screen, name)
            assertEquals(RevisionsScreenState(), fixture.state.revisions, name)
            assertEquals(emptyList(), fixture.store.requests.filterIsInstance<Request.ListVersionsPage>(), name)
        }
    }

    // 時点を選ぶと、その時点のマニフェストを読んでいる状態にし、読めたら戻すかを確かめる状態にする
    @Test
    fun selectingRevisionLoadsItForConfirmation() = runTest {
        val fixture = started()
        openRevisionsScreen(fixture)
        val revision = fixture.revisionAt(T1)

        fixture.controller.selectRevision(revision)

        assertEquals(RevisionSelection.Loading(revision), fixture.state.revisions.selection)
        runCurrent()
        assertEquals(
            RevisionSelection.Confirming(LoadedRevision(revision, initialManifest())),
            fixture.state.revisions.selection,
        )
        assertEquals(
            listOf(Request.GetVersion(fixture.manifestKey, revision.versionId)),
            fixture.store.requests.filterIsInstance<Request.GetVersion>(),
        )
    }

    // 確かめるのをやめると、選んでいない状態に戻る。選んだ時点を読んでいる最中にやめようとしても、何もしない
    @Test
    fun dismissingClosesOnlyConfirmation() = runTest {
        val fixture = started()
        openRevisionsScreen(fixture)
        val revision = fixture.revisionAt(T1)
        val gate = fixture.stopNextGet(fixture.isManifest)
        fixture.controller.selectRevision(revision)
        runCurrent()

        fixture.controller.dismissRevisionSelection()

        assertEquals(RevisionSelection.Loading(revision), fixture.state.revisions.selection)
        gate.complete(Unit)
        runCurrent()
        assertIs<RevisionSelection.Confirming>(fixture.state.revisions.selection)

        fixture.controller.dismissRevisionSelection()

        assertNull(fixture.state.revisions.selection)
    }

    // 選んだ時点を読めなければ（権限が無い・壊れている・形式が新しい）、選んでいない状態に戻してそのことを伝える。
    // 次の時点を選ぶと、伝えたことを消す
    @Test
    fun unreadableRevisionIsNoticed() = runTest {
        /** 場合の名前、起動の前の用意、時点を選ぶ前の用意、選ぶ時点、読めなかった理由。 */
        data class ReadCase(
            val name: String,
            val arrange: (ControllerFixture) -> Unit,
            val beforeSelect: (ControllerFixture) -> Unit,
            val time: Instant,
            val failure: RevisionFailure,
        )
        val cases = listOf(
            ReadCase(
                "権限が無い",
                arrange = {},
                beforeSelect = { it.store.onNextGet(GetFault.Forbidden, key = it.isManifest) },
                time = T1,
                failure = RevisionFailure.Sync(SyncFailure.Forbidden),
            ),
            ReadCase(
                "壊れている",
                arrange = { it.insertManifestVersionAtT3("not gzip".toByteArray()) },
                beforeSelect = {},
                time = T3,
                failure = RevisionFailure.Corrupt(corruptManifestProblem()),
            ),
            ReadCase(
                "形式が新しい",
                arrange = { it.insertManifestVersionAtT3(encodeManifestGzip(initialManifest(version = 2, readableBy = 2))) },
                beforeSelect = {},
                time = T3,
                failure = RevisionFailure.UpdateRequired,
            ),
        )
        cases.forEach { case ->
            val fixture = started(case.arrange)
            openRevisionsScreen(fixture)
            case.beforeSelect(fixture)

            fixture.controller.selectRevision(fixture.revisionAt(case.time))
            runCurrent()

            assertNull(fixture.state.revisions.selection, case.name)
            assertEquals(RevisionNotice.ReadFailed(case.failure), fixture.state.revisions.notice, case.name)

            fixture.controller.selectRevision(fixture.revisionAt(T1))

            assertNull(fixture.state.revisions.notice, case.name)
            runCurrent()
            assertIs<RevisionSelection.Confirming>(fixture.state.revisions.selection, case.name)
        }
    }

    // 選んだ時点を読んでいる最中に戻ると、設定の画面へ戻って画面の状態を初めの値にし、読み込みを取り消す。読み終えても結果は入れない。
    // 設定の画面の入力は、開く前に入れたまま残す
    @Test
    fun backWhileReadingRevisionDiscardsResult() = runTest {
        val fixture = started()
        val edited = SettingsForm.of(SETTINGS.copy(region = "us-east-1"))
        fixture.controller.openSettings()
        fixture.controller.editSettings(edited)
        openRevisionsScreen(fixture)
        val gate = fixture.stopNextGet(fixture.isManifest)
        fixture.controller.selectRevision(fixture.revisionAt(T1))
        runCurrent()

        fixture.controller.back()
        runCurrent()

        assertEquals(Screen.SETTINGS, fixture.state.screen)
        assertEquals(edited, fixture.state.settings.form)
        assertEquals(RevisionsScreenState(), fixture.state.revisions)
        assertEquals(listOf(CANCELLED), fixture.events)
        gate.complete(Unit)
        runCurrent()
        assertEquals(Screen.SETTINGS, fixture.state.screen)
        assertEquals(RevisionsScreenState(), fixture.state.revisions)
    }

    // 一覧を読んでいる最中に戻ると、読み込みを取り消す。開き直すと、一覧を読んでいる状態から始めて、読み直した一覧を入れる
    @Test
    fun backWhileListingThenReopeningListsAgain() = runTest {
        val fixture = started()
        val gate = fixture.stopNextListVersions()
        fixture.controller.openSettings()
        fixture.controller.openRevisions()
        runCurrent()
        assertEquals(RevisionListState.Loading, fixture.state.revisions.list)

        fixture.controller.back()
        runCurrent()

        assertEquals(Screen.SETTINGS, fixture.state.screen)
        assertEquals(listOf(CANCELLED), fixture.events)
        fixture.store.serverTime = T3
        fixture.store.addFolderAsOtherWriter(OTHER_FOLDER)

        fixture.controller.openRevisions()

        assertEquals(RevisionsScreenState(), fixture.state.revisions)
        runCurrent()
        assertEquals(
            RevisionListState.Listed(listOf(fixture.revisionAt(T2), fixture.revisionAt(T1))),
            fixture.state.revisions.list,
        )
        gate.complete(Unit)
        runCurrent()
        assertEquals(2, fixture.store.requests.filterIsInstance<Request.ListVersionsPage>().size)
    }

    // 戻すと、書いている最中にしてから戻し、戻せたらメインの画面へ移って、戻す時点のツリーに替える。結果は戻せたこと（お知らせ）で、
    // 前の結果と元に戻せる削除は消す。以前の状態に戻す画面の状態は初めの値にし、設定の画面の入力は保存済みの設定から作り直す
    @Test
    fun restoringReturnsToMainWithRestoredTree() = runTest {
        val fixture = started()
        fixture.deleteItem(ROOT_ITEMS[0])
        runCurrent()
        assertNotNull(fixture.state.write.undoableDelete)
        fixture.controller.openSettings()
        fixture.controller.editSettings(SettingsForm.of(SETTINGS.copy(region = "us-east-1")))
        val loaded = confirm(fixture)

        fixture.controller.restoreSelectedRevision()

        assertEquals(RevisionSelection.Restoring(loaded), fixture.state.revisions.selection)
        assertEquals(WriteState(writing = true), fixture.state.write)
        runCurrent()
        assertEquals(Screen.MAIN, fixture.state.screen)
        assertEquals(fixture.store.storedManifest(), fixture.state.browse.tree?.manifest)
        assertEquals(ROOT_ITEMS, fixture.storedItems(ROOT_ID))
        assertEquals(FolderReadResult.Loaded(ROOT_ITEMS), fixture.state.browse.currentItems)
        assertEquals(WriteState(outcome = WriteOutcome.Restored(T1)), fixture.state.write)
        assertEquals(RevisionsScreenState(), fixture.state.revisions)
        assertEquals(
            SettingsScreenState(form = SettingsForm.of(SETTINGS), hasStoredSettings = true, canReturn = true),
            fixture.state.settings,
        )
    }

    // 戻したら開いていたフォルダが無くなるときは、ルートフォルダを開く。履歴とツリーで開いているノードからは無くなったフォルダを除き、
    // 選んでいた項目を外す
    @Test
    fun restoringWithoutOpenFolderOpensRoot() = runTest {
        val fixture = started { fixture ->
            fixture.store.serverTime = T3
            fixture.store.addFolderAsOtherWriter(OTHER_FOLDER)
            fixture.store.replaceItemsAsOtherWriter(OTHER_FOLDER, fileId(40), listOf(item(8)))
        }
        fixture.controller.openFolder(FOLDER_A)
        runCurrent()
        fixture.controller.openFolder(OTHER_FOLDER)
        runCurrent()
        fixture.controller.selectItem(item(8).id, SelectionMode.SINGLE)
        assertEquals(setOf(item(8).id), fixture.state.browse.selectedItemIds)
        confirm(fixture)

        fixture.controller.restoreSelectedRevision()
        runCurrent()

        val browse = fixture.state.browse
        assertEquals(ROOT_ID, browse.currentFolderId)
        assertEquals(listOf(ROOT_ID, FOLDER_A), browse.history)
        assertFalse(OTHER_FOLDER in browse.expandedFolders)
        assertEquals(emptySet(), browse.selectedItemIds)
        assertEquals(FolderReadResult.Loaded(ROOT_ITEMS), browse.currentItems)
    }

    // 溜めた削除があれば、戻す前に書く。溜めた削除を空にして記録のファイルを消し、そのあと戻すので、溜めた削除で消した項目も
    // 戻す時点のとおりに戻る
    @Test
    fun pendingDeletesAreWrittenBeforeRestoring() = runTest {
        val fixture = startedWithPendingDelete()
        confirm(fixture)
        val manifestPutsBefore = fixture.manifestPuts

        fixture.controller.restoreSelectedRevision()
        runCurrent()

        assertEquals(ROOT_ITEMS, fixture.storedItems(ROOT_ID))
        assertEquals(2, fixture.manifestPuts - manifestPutsBefore)
        assertEquals(emptySet(), fixture.state.browse.pendingDeletes)
        assertFalse(fixture.hasPendingDeletesRecord())
        assertEquals(WriteOutcome.Restored(T1), fixture.state.write.outcome)
        assertEquals(fixture.store.storedManifest(), fixture.state.browse.tree?.manifest)
        assertEquals(FolderReadResult.Loaded(ROOT_ITEMS), fixture.state.browse.currentItems)
    }

    // 溜めた削除を書けなければ（つながらない）、戻さない。溜めた削除は溜めたままにし、以前の状態に戻す画面に残って、戻せなかったことを伝える
    @Test
    fun restoreIsNotWrittenWhenPendingDeletesFail() = runTest {
        val fixture = startedWithPendingDelete()
        confirm(fixture)
        fixture.store.unreachable = true
        val requestsBefore = fixture.store.requests.size

        fixture.controller.restoreSelectedRevision()
        runCurrent()

        assertEquals(Screen.REVISIONS, fixture.state.screen)
        assertNull(fixture.state.revisions.selection)
        assertEquals(RevisionNotice.RestoreFailed(RevisionFailure.Sync(UNREACHABLE)), fixture.state.revisions.notice)
        assertEquals(WriteState(), fixture.state.write)
        assertEquals(setOf(ROOT_TARGET), fixture.state.browse.pendingDeletes)
        assertEquals(setOf(ROOT_TARGET), fixture.recordedPendingDeletes())
        assertEquals(emptyList(), fixture.store.requests.drop(requestsBefore).filterIsInstance<Request.Put>())
        fixture.store.unreachable = false
        assertEquals(LATER_ROOT_ITEMS, fixture.storedItems(ROOT_ID))
    }

    // 戻している最中は、ほかの操作も読み直しも戻る操作も受け付けず、S3 から読み直すきっかけの知らせでも読み直さない。
    // 確かめるのをやめることも、もう一度戻すことも受け付けない
    @Test
    fun nothingElseIsAcceptedWhileRestoring() = runTest {
        val fixture = started()
        val loaded = confirm(fixture)
        val gate = fixture.stopNextGet(fixture.isManifest)
        fixture.controller.restoreSelectedRevision()
        runCurrent()
        val manifestGetsBefore = fixture.store.gets(fixture.isManifest).size

        assertFalse(fixture.state.canWrite)
        assertFalse(fixture.state.canReload)
        assertFalse(fixture.state.canGoBack)
        assertFalse(fixture.state.canRestore)
        fixture.controller.back()
        fixture.controller.dismissRevisionSelection()
        fixture.controller.restoreSelectedRevision()
        fixture.controller.reload()
        fixture.storageChanges.emit(Unit)
        runCurrent()

        assertEquals(Screen.REVISIONS, fixture.state.screen)
        assertEquals(RevisionSelection.Restoring(loaded), fixture.state.revisions.selection)
        assertEquals(manifestGetsBefore, fixture.store.gets(fixture.isManifest).size)
        assertEquals(emptyList(), fixture.events)
        gate.complete(Unit)
        runCurrent()
        assertEquals(Screen.MAIN, fixture.state.screen)
        assertEquals(WriteOutcome.Restored(T1), fixture.state.write.outcome)
        assertEquals(1, fixture.manifestPuts)
    }

    // 戻せなければ（この時点のデータの一部が残っていない・権限が無い）、以前の状態に戻す画面に残って選んでいない状態にし、そのことを伝える。
    // 書いている最中ではなくなり、また戻せる。伝えたことは閉じられる
    @Test
    fun failedRestoreStaysOnRevisions() = runTest {
        val cases: List<Triple<String, (ControllerFixture) -> Unit, RevisionFailure>> = listOf(
            Triple(
                "この時点のデータの一部が残っていない",
                { fixture ->
                    val key = fixture.itemsKey(ROOT_ID, ROOT_FILE_ID)
                    fixture.store.remove(key)
                    fixture.store.expireNoncurrentVersions(key)
                },
                RevisionFailure.Incomplete,
            ),
            Triple(
                "権限が無い",
                { it.store.onNextPut(PutFault.Forbidden, key = it.isManifest) },
                RevisionFailure.Sync(SyncFailure.Forbidden),
            ),
        )
        cases.forEach { (name, breakRestore, expected) ->
            val fixture = started()
            confirm(fixture)
            val list = fixture.state.revisions.list
            breakRestore(fixture)

            fixture.controller.restoreSelectedRevision()
            runCurrent()

            assertEquals(Screen.REVISIONS, fixture.state.screen, name)
            assertEquals(RevisionsScreenState(list, notice = RevisionNotice.RestoreFailed(expected)), fixture.state.revisions, name)
            assertEquals(WriteState(), fixture.state.write, name)
            assertTrue(fixture.state.canRestore, name)
            assertEquals(LATER_ROOT_ITEMS, fixture.storedItems(ROOT_ID), name)

            fixture.controller.dismissRevisionNotice()

            assertNull(fixture.state.revisions.notice, name)
        }
    }

    // 戻せたかどうかを確かめられなければ（戻す書き込みでも、その前の溜めた削除の書き込みでも）、メインの画面へ移り、操作を確かめられなかった
    // ときと同じ結果に出す。ツリーは戻す前のまま。以前の状態に戻す画面の状態は初めの値にし、設定の画面の入力は保存済みの設定から作り直す
    @Test
    fun unconfirmedRestoreReturnsToMain() = runTest {
        /** 場合の名前、用意したフィクスチャ、書き込みを確かめられなくする用意、そのあとの確かめ方。 */
        data class UnconfirmedCase(
            val name: String,
            val start: TestScope.() -> ControllerFixture,
            val breakWrite: (ControllerFixture) -> Unit,
            val check: (ControllerFixture) -> Unit,
        )
        val cases = listOf(
            UnconfirmedCase(
                "戻す書き込み",
                start = { started() },
                breakWrite = { fixture ->
                    fixture.store.onNextPut(
                        PutFault.WriteThenUnknown {
                            repeat(MAX_RESENDS + 1) { fixture.store.onNextGet(GetFault.ServerError, key = fixture.isManifest) }
                        },
                        key = fixture.isManifest,
                    )
                },
            ) { fixture -> assertIs<SyncFailure.Unreachable>(assertIs<WriteOutcome.Unconfirmed>(fixture.state.write.outcome).failure) },
            UnconfirmedCase(
                "溜めた削除の書き込み",
                start = { startedWithPendingDelete() },
                breakWrite = { fixture ->
                    fixture.store.onNextPut(
                        PutFault.WriteThenUnknown {
                            repeat(MAX_RESENDS + 1) { fixture.store.onNextGet(GetFault.ServerError, key = fixture.isManifest) }
                            fixture.store.onNextGet(GetFault.Forbidden, key = fixture.isManifest)
                        },
                        key = fixture.isManifest,
                    )
                },
            ) { fixture ->
                assertEquals(WriteOutcome.Unconfirmed(SyncFailure.Forbidden), fixture.state.write.outcome)
                assertEquals(setOf(ROOT_TARGET), fixture.state.browse.pendingDeletes)
                // 溜めた削除の PUT だけで、戻す書き込みは送らない
                assertEquals(1, fixture.manifestPuts)
            },
        )
        cases.forEach { case ->
            val fixture = case.start(this)
            fixture.controller.openSettings()
            fixture.controller.editSettings(SettingsForm.of(SETTINGS.copy(region = "us-east-1")))
            confirm(fixture)
            val treeBefore = fixture.state.browse.tree
            case.breakWrite(fixture)

            fixture.controller.restoreSelectedRevision()
            runCurrent()

            assertEquals(Screen.MAIN, fixture.state.screen, case.name)
            assertFalse(fixture.state.write.writing, case.name)
            assertEquals(treeBefore, fixture.state.browse.tree, case.name)
            assertEquals(RevisionsScreenState(), fixture.state.revisions, case.name)
            assertEquals(SettingsForm.of(SETTINGS), fixture.state.settings.form, case.name)
            case.check(fixture)
        }
    }
}
