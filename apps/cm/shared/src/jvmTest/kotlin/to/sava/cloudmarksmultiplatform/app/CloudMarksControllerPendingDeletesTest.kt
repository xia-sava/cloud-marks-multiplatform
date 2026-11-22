package to.sava.cloudmarksmultiplatform.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.browse.SelectionMode
import to.sava.cloudmarksmultiplatform.browser.CHROME
import to.sava.cloudmarksmultiplatform.operations.ItemRef
import to.sava.cloudmarksmultiplatform.operations.toTreeState
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.GetFault
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.PutFault
import to.sava.cloudmarksmultiplatform.remote.UNREACHABLE_DETAIL
import to.sava.cloudmarksmultiplatform.settings.ConnectionSettings
import to.sava.cloudmarksmultiplatform.settings.SETTINGS
import to.sava.cloudmarksmultiplatform.settings.SettingsForm
import to.sava.cloudmarksmultiplatform.storage.Item
import to.sava.cloudmarksmultiplatform.storage.Manifest
import to.sava.cloudmarksmultiplatform.storage.ManifestDecodeResult
import to.sava.cloudmarksmultiplatform.storage.StorageProblem
import to.sava.cloudmarksmultiplatform.storage.decodeManifestGzip
import to.sava.cloudmarksmultiplatform.storage.encodeManifestGzip
import to.sava.cloudmarksmultiplatform.storage.manifestKey
import to.sava.cloudmarksmultiplatform.storage.newId
import to.sava.cloudmarksmultiplatform.sync.A_ITEMS
import to.sava.cloudmarksmultiplatform.sync.FOLDER_A
import to.sava.cloudmarksmultiplatform.sync.FOLDER_B
import to.sava.cloudmarksmultiplatform.sync.FolderReadResult
import to.sava.cloudmarksmultiplatform.sync.LoadedTree
import to.sava.cloudmarksmultiplatform.sync.MAX_RESENDS
import to.sava.cloudmarksmultiplatform.sync.ROOT_ID
import to.sava.cloudmarksmultiplatform.sync.ROOT_ITEMS
import to.sava.cloudmarksmultiplatform.sync.SyncFailure
import to.sava.cloudmarksmultiplatform.sync.WriteResult
import to.sava.cloudmarksmultiplatform.sync.fileId
import to.sava.cloudmarksmultiplatform.sync.initialManifest
import to.sava.cloudmarksmultiplatform.sync.item
import to.sava.cloudmarksmultiplatform.sync.puts
import to.sava.cloudmarksmultiplatform.sync.replaceItemsAsOtherWriter
import to.sava.cloudmarksmultiplatform.sync.seed
import to.sava.cloudmarksmultiplatform.sync.storedItems
import to.sava.cloudmarksmultiplatform.sync.storedManifest
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 接続先を替えるときの、別のバケット。 */
private const val OTHER_BUCKET = "other-bucket"

/** S3 につながらないときの失敗。 */
private val UNREACHABLE = SyncFailure.Unreachable(UNREACHABLE_DETAIL)

/** 接続先 [settings] の、溜めた削除の記録のファイル。 */
private fun ControllerFixture.pendingDeletesPath(settings: ConnectionSettings = SETTINGS): Path =
    pendingDeletesFile(appFiles.stateDirectory, settings.bucket, settings.prefix)

/** 接続先 SETTINGS の溜めた削除の記録に書いてある対象。 */
private fun ControllerFixture.recordedPendingDeletes(): Set<ItemRef> = PendingDeletesFile(pendingDeletesPath()) {}.read()

/** S3 に置いてあるマニフェストが指す、フォルダ [folderId] の項目。 */
private fun ControllerFixture.storedItems(folderId: String): List<Item> = store.storedItems(store.storedManifest(), folderId)

/** S3 に置いてあるマニフェストの、ルートフォルダの子のフォルダの名前。 */
private fun ControllerFixture.storedRootChildNames(): List<String> =
    store.storedManifest().folders.filter { it.parent == ROOT_ID }.map { it.name }

/** マニフェストの PUT の数。 */
private val ControllerFixture.manifestPuts: Int get() = store.puts(isManifest).size

/** 開いているフォルダの項目 [item] を選んで消す。 */
private fun ControllerFixture.deleteItem(item: Item) {
    controller.selectItem(item.id, SelectionMode.SINGLE)
    controller.deleteSelectedItems()
}

/** ほかの書き手として、フォルダ [folderId] をマニフェストから除く。子のフォルダを持たないフォルダに使う。マニフェストの `commit` と ETag が替わる。 */
private fun FakeObjectStore.deleteFolderAsOtherWriter(folderId: String) {
    val current = storedManifest()
    val updated = Manifest(
        header = current.header.copy(commit = newId(), updatedBy = "android"),
        folders = current.folders.filter { it.id != folderId },
    )
    overwrite(manifestKey(prefix), encodeManifestGzip(updated))
}

/** フォルダ A の 2 つの項目。項目を読んでいないフォルダを開くときに、溜めた削除の項目を除いても項目が残るようにする。 */
private val TWO_A_ITEMS = listOf(item(3), item(4))

/** [TWO_A_ITEMS] を置く項目ファイルの ID。 */
private val TWO_A_ITEMS_FILE_ID = fileId(31)

// runCurrent が ExperimentalCoroutinesApi であるため。
@OptIn(ExperimentalCoroutinesApi::class)
class CloudMarksControllerPendingDeletesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** マニフェストと保存済みの設定を置いて起動し、ルートフォルダを開いたフィクスチャ。[directory] を渡すと、そのファイルを使う。 */
    private fun TestScope.started(directory: Path? = null, arrange: (ControllerFixture) -> Unit = {}): ControllerFixture {
        val fixture = directory?.let { ControllerFixture(this, temporaryFolder, directory = it) } ?: ControllerFixture(this, temporaryFolder)
        fixture.store.seed()
        fixture.storeSettings()
        arrange(fixture)
        fixture.controller.start()
        runCurrent()
        return fixture
    }

    /** 起動したあと、S3 につながらないあいだにルートフォルダの項目 0 を消して溜めたフィクスチャ。つながらないままにしておく。 */
    private fun TestScope.startedWithPendingDelete(): ControllerFixture =
        started().apply {
            store.unreachable = true
            deleteItem(ROOT_ITEMS[0])
            runCurrent()
        }

    // 書き込みの結果の種類ごとに、S3 につながらないための失敗か（溜めるか）を決める。つながらない失敗は、書けなかったときも、
    // 確かめられなかったときも当たる。ほかの失敗と書けたときは当たらない
    @Test
    fun unreachableIsDecidedByFailureKind() {
        val manifest = initialManifest()
        val success = WriteResult.Success(LoadedTree(manifest, "\"etag\"", manifest.toTreeState(), emptyMap()), emptyList())
        val cases: List<Pair<WriteResult, Boolean>> = listOf(
            WriteResult.Failed(UNREACHABLE) to true,
            WriteResult.Unconfirmed(UNREACHABLE) to true,
            WriteResult.Failed(SyncFailure.Forbidden) to false,
            WriteResult.Failed(SyncFailure.ReadOnly(2, 1)) to false,
            WriteResult.Failed(SyncFailure.FolderCorrupt(emptyMap())) to false,
            WriteResult.Unconfirmed(SyncFailure.Busy) to false,
            success to false,
        )
        cases.forEach { (result, expected) -> assertEquals(expected, isUnreachable(result), "$result") }
    }

    // 項目の削除が S3 につながらないために書けなければ（マニフェストの GET が届かない・項目ファイルの PUT の結果が上限まで不明・
    // マニフェストの PUT が届いたあとに確かめられない）、その削除を溜める。リストから消して見せ、記録のファイルに書き、結果は出さない。
    // 溜めた削除として元に戻せるようにする
    @Test
    fun unreachableDeleteIsPended() = runTest {
        val cases: List<Pair<String, (ControllerFixture) -> Unit>> = listOf(
            "マニフェストの GET が届かない" to { fixture -> fixture.store.unreachable = true },
            "項目ファイルの PUT の結果が上限まで不明" to { fixture ->
                repeat(MAX_RESENDS + 1) {
                    fixture.store.onNextPut(PutFault.UnknownWithoutWrite) { key -> key.contains("/$ROOT_ID/") }
                }
            },
            "マニフェストの PUT が届いたあとに確かめられない" to { fixture ->
                fixture.store.onNextPut(PutFault.WriteThenUnknown { fixture.store.unreachable = true }, key = fixture.isManifest)
            },
        )
        cases.forEach { (name, breakWrite) ->
            val fixture = started()
            breakWrite(fixture)

            fixture.deleteItem(ROOT_ITEMS[0])
            runCurrent()

            val expected = setOf(rootItemRef(0))
            assertEquals(expected, fixture.state.browse.pendingDeletes, name)
            assertEquals(FolderReadResult.Loaded(listOf(ROOT_ITEMS[1])), fixture.state.browse.currentItems, name)
            assertEquals(emptySet(), fixture.state.browse.selectedItemIds, name)
            assertNull(fixture.state.write.outcome, name)
            assertFalse(fixture.state.write.writing, name)
            val undoable = assertNotNull(fixture.state.write.undoableDelete, name)
            assertTrue(undoable.pending, name)
            assertEquals(expected, undoable.pendingTargets, name)
            assertEquals(1, undoable.count, name)
            assertEquals(expected, fixture.recordedPendingDeletes(), name)
            assertTrue(fixture.logs.any { it.startsWith("S3 につながらないため、項目の削除を溜めた") }, name)
        }
    }

    // 権限が無い・データが壊れている・形式が新しい・確かめられなかったがつながらないためではない、のときは溜めず、ほかの操作を
    // 書けなかったときと同じく結果に出す。ツリーと項目はそのまま見せ、記録のファイルも作らない
    @Test
    fun otherDeleteFailuresAreNotPended() = runTest {
        val cases: List<Triple<String, (ControllerFixture) -> Unit, WriteOutcome>> = listOf(
            Triple(
                "権限が無い",
                { it.store.onNextPut(PutFault.Forbidden, key = it.isManifest) },
                WriteOutcome.Failed(SyncFailure.Forbidden),
            ),
            Triple(
                "形式が新しい",
                { it.store.overwrite(manifestKey(it.store.prefix), encodeManifestGzip(initialManifest(version = 2))) },
                WriteOutcome.Failed(SyncFailure.ReadOnly(2, 1)),
            ),
            Triple(
                "データが壊れている",
                { it.store.overwrite(manifestKey(it.store.prefix), "not gzip".toByteArray()) },
                WriteOutcome.Failed(SyncFailure.ManifestCorrupt(corruptManifestProblem())),
            ),
            Triple(
                "確かめられず、やり直しで権限が無い",
                { fixture ->
                    fixture.store.onNextPut(
                        PutFault.WriteThenUnknown {
                            repeat(MAX_RESENDS + 1) { fixture.store.onNextGet(GetFault.ServerError, key = fixture.isManifest) }
                            fixture.store.onNextGet(GetFault.Forbidden, key = fixture.isManifest)
                        },
                        key = fixture.isManifest,
                    )
                },
                WriteOutcome.Unconfirmed(SyncFailure.Forbidden),
            ),
        )
        cases.forEach { (name, breakWrite, expectedOutcome) ->
            val fixture = started()
            breakWrite(fixture)

            fixture.deleteItem(ROOT_ITEMS[0])
            runCurrent()

            assertEquals(emptySet(), fixture.state.browse.pendingDeletes, name)
            assertEquals(expectedOutcome, fixture.state.write.outcome, name)
            assertNull(fixture.state.write.undoableDelete, name)
            assertEquals(FolderReadResult.Loaded(ROOT_ITEMS), fixture.state.browse.currentItems, name)
            assertFalse(fixture.pendingDeletesPath().exists(), name)
        }
    }

    // 項目の削除のほかの操作は、S3 につながらなくても溜めず、書けなかったとして結果に出す
    @Test
    fun otherOperationsAreNotPended() = runTest {
        val cases: List<Pair<String, (CloudMarksController) -> Unit>> = listOf(
            "フォルダの削除" to { it.deleteFolder(FOLDER_A) },
            "フォルダの移動" to { it.moveFolder(FOLDER_B, FOLDER_A) },
            "フォルダの名前の変更" to { it.renameFolder(FOLDER_A, "A'") },
            "フォルダの作成" to { it.createFolder(ROOT_ID, "C") },
            "項目の移動" to { it.moveItems(ROOT_ID, listOf(ROOT_ITEMS[0].id), FOLDER_A) },
            "項目のタイトルの変更" to { it.renameItem(rootItemRef(0), "renamed") },
        )
        cases.forEach { (name, act) ->
            val fixture = started()
            fixture.store.unreachable = true

            act(fixture.controller)
            runCurrent()

            assertEquals(WriteOutcome.Failed(UNREACHABLE), fixture.state.write.outcome, name)
            assertEquals(emptySet(), fixture.state.browse.pendingDeletes, name)
            assertFalse(fixture.pendingDeletesPath().exists(), name)
        }
    }

    // 溜めた削除を元に戻すと、その回に溜めた対象だけを溜めた削除から外し、記録のファイルを書き直して、項目をまた見せる。S3 には何も送らない。
    // 溜めた削除が空になれば、記録のファイルを消す
    @Test
    fun undoOfPendingDeleteRemovesOnlyThatRoundWithoutWriting() = runTest {
        val fixture = startedWithPendingDelete()
        fixture.deleteItem(ROOT_ITEMS[1])
        runCurrent()
        assertEquals(setOf(rootItemRef(0), rootItemRef(1)), fixture.state.browse.pendingDeletes)
        assertEquals(setOf(rootItemRef(1)), fixture.state.write.undoableDelete?.pendingTargets)
        val requestsBefore = fixture.store.requests.size

        assertTrue(fixture.controller.undoDelete())
        runCurrent()

        assertEquals(setOf(rootItemRef(0)), fixture.state.browse.pendingDeletes)
        assertEquals(setOf(rootItemRef(0)), fixture.recordedPendingDeletes())
        assertEquals(FolderReadResult.Loaded(listOf(ROOT_ITEMS[1])), fixture.state.browse.currentItems)
        assertNull(fixture.state.write.undoableDelete)
        assertEquals(requestsBefore, fixture.store.requests.size)

        // 1 回目の削除は、もう元に戻すお知らせが無い。溜めた削除を外すのは、元に戻す削除の対象だけ
        assertFalse(fixture.controller.undoDelete())
    }

    // 溜めた削除を 1 回だけ元に戻すと、溜めた削除は空になり、記録のファイルを消して、項目をすべて見せる
    @Test
    fun undoingOnlyPendingDeleteDeletesRecord() = runTest {
        val fixture = startedWithPendingDelete()

        assertTrue(fixture.controller.undoDelete())
        runCurrent()

        assertEquals(emptySet(), fixture.state.browse.pendingDeletes)
        assertFalse(fixture.pendingDeletesPath().exists())
        assertEquals(FolderReadResult.Loaded(ROOT_ITEMS), fixture.state.browse.currentItems)
    }

    // 操作を受け付けないあいだ（マニフェストを読み直している最中）は、溜めた削除を元に戻すことを受け付けず、false を返して溜めたままにする
    @Test
    fun undoOfPendingDeleteIsRefusedWhileNotWritable() = runTest {
        val fixture = startedWithPendingDelete()
        fixture.store.unreachable = false
        val gate = fixture.stopNextGet(fixture.isManifest)
        fixture.controller.reload()
        runCurrent()

        assertFalse(fixture.controller.undoDelete())
        runCurrent()

        assertEquals(setOf(rootItemRef(0)), fixture.state.browse.pendingDeletes)
        assertTrue(assertNotNull(fixture.state.write.undoableDelete).pending)
        gate.complete(Unit)
        runCurrent()
    }

    // 溜めた削除は次の起動でも読む。つながらなければ溜めたまま見せ、S3 には書かない。つながれば、起動して読めたあとに書く
    @Test
    fun pendingDeletesAreReadOnNextStart() = runTest {
        val first = startedWithPendingDelete()

        val offline = started(directory = first.directory) { it.store.unreachable = true }
        assertEquals(setOf(rootItemRef(0)), offline.state.browse.pendingDeletes)
        assertEquals(0, offline.manifestPuts)

        val online = started(directory = first.directory)
        assertEquals(listOf(ROOT_ITEMS[1]), online.storedItems(ROOT_ID))
        assertEquals(emptySet(), online.state.browse.pendingDeletes)
        assertFalse(online.pendingDeletesPath().exists())
        assertEquals(FolderReadResult.Loaded(listOf(ROOT_ITEMS[1])), online.state.browse.currentItems)
        assertNull(online.state.write.outcome)
        assertFalse(online.state.write.writing)
    }

    // 読み直すボタンと、S3 から読み直すきっかけの知らせ（Android のネットワークの復帰を含む）で読み直せたら、溜めた削除を書く。
    // いくつのフォルダにまたがっても、マニフェストの書き換え 1 回にまとめる。書けたら溜めた削除を空にし、記録のファイルを消す
    @Test
    fun pendingDeletesAreAppliedAfterReread() = runTest {
        val cases: List<Pair<String, suspend (ControllerFixture) -> Unit>> = listOf(
            "読み直すボタン" to { it.controller.reload() },
            "読み直すきっかけの知らせ" to { it.storageChanges.emit(Unit) },
        )
        cases.forEach { (name, trigger) ->
            val fixture = started()
            // つながっているうちに A の項目を読んでおく
            fixture.controller.openFolder(FOLDER_A)
            runCurrent()
            fixture.controller.back()
            runCurrent()
            fixture.store.unreachable = true
            fixture.deleteItem(ROOT_ITEMS[0])
            runCurrent()
            fixture.controller.openFolder(FOLDER_A)
            fixture.deleteItem(A_ITEMS[0])
            runCurrent()
            fixture.controller.back()
            runCurrent()
            assertEquals(setOf(rootItemRef(0), ItemRef(FOLDER_A, A_ITEMS[0].id)), fixture.state.browse.pendingDeletes, name)
            fixture.store.unreachable = false

            trigger(fixture)
            runCurrent()

            assertEquals(listOf(ROOT_ITEMS[1]), fixture.storedItems(ROOT_ID), name)
            assertEquals(emptyList(), fixture.storedItems(FOLDER_A), name)
            assertEquals(1, fixture.manifestPuts, name)
            assertEquals(emptySet(), fixture.state.browse.pendingDeletes, name)
            assertFalse(fixture.pendingDeletesPath().exists(), name)
            assertEquals(fixture.store.storedManifest(), fixture.state.browse.tree?.manifest, name)
            assertEquals(FolderReadResult.Loaded(listOf(ROOT_ITEMS[1])), fixture.state.browse.currentItems, name)
            assertFalse(fixture.state.write.writing, name)
            assertNull(fixture.state.write.outcome, name)
        }
    }

    // 書き込みが届いていたのに確かめられなかったために溜めた削除は、あとで書くと適用済みになる（マニフェストを書き換えずに、溜めた削除を空にする）
    @Test
    fun pendingDeleteThatAlreadyArrivedIsAlreadyApplied() = runTest {
        val fixture = started()
        fixture.store.onNextPut(PutFault.WriteThenUnknown { fixture.store.unreachable = true }, key = fixture.isManifest)
        fixture.deleteItem(ROOT_ITEMS[0])
        runCurrent()
        assertEquals(setOf(rootItemRef(0)), fixture.state.browse.pendingDeletes)
        assertEquals(listOf(ROOT_ITEMS[1]), fixture.storedItems(ROOT_ID))
        fixture.store.unreachable = false

        fixture.controller.reload()
        runCurrent()

        assertEquals(1, fixture.manifestPuts)
        assertEquals(listOf(ROOT_ITEMS[1]), fixture.storedItems(ROOT_ID))
        assertEquals(emptySet(), fixture.state.browse.pendingDeletes)
        assertFalse(fixture.pendingDeletesPath().exists())
    }

    // 読み直せなければ（つながらない）、溜めた削除は書かずに溜めたままにする。読み直しの失敗は、溜めた削除が無いときと同じく出し、
    // 操作の結果は出さない
    @Test
    fun failedRereadKeepsPendingDeletes() = runTest {
        val fixture = startedWithPendingDelete()
        val putsBefore = fixture.store.puts { true }.size

        fixture.controller.reload()
        runCurrent()

        assertEquals(putsBefore, fixture.store.puts { true }.size)
        assertEquals(setOf(rootItemRef(0)), fixture.state.browse.pendingDeletes)
        assertEquals(UNREACHABLE, fixture.state.browse.failure)
        assertNull(fixture.state.write.outcome)
    }

    // 読み直せたあとに溜めた削除を書くとき、つながらなければ溜めたままにし、結果は出さない。ほかの理由で書けなければ溜めたままにし、
    // ほかの操作を書けなかったときと同じく結果に出す
    @Test
    fun failedFlushKeepsPendingDeletes() = runTest {
        val cases: List<Triple<String, (ControllerFixture) -> Unit, WriteOutcome?>> = listOf(
            Triple(
                "つながらない",
                { fixture ->
                    // 読み直しの GET と、溜めた削除を書く手順 1 の GET までは届く。手順 1 の GET を受けたところでつながらなくなり、
                    // 続く項目ファイルの PUT から届かない
                    fixture.store.onNextGet(GetFault.RunBefore {}, key = fixture.isManifest)
                    fixture.store.onNextGet(GetFault.RunBefore { fixture.store.unreachable = true }, key = fixture.isManifest)
                },
                null,
            ),
            Triple(
                "権限が無い",
                { it.store.onNextPut(PutFault.Forbidden, key = it.isManifest) },
                WriteOutcome.Failed(SyncFailure.Forbidden),
            ),
        )
        cases.forEach { (name, breakFlush, expectedOutcome) ->
            val fixture = startedWithPendingDelete()
            fixture.store.unreachable = false
            breakFlush(fixture)

            fixture.controller.reload()
            runCurrent()

            assertEquals(setOf(rootItemRef(0)), fixture.state.browse.pendingDeletes, name)
            assertEquals(setOf(rootItemRef(0)), fixture.recordedPendingDeletes(), name)
            assertEquals(expectedOutcome, fixture.state.write.outcome, name)
            assertNull(fixture.state.browse.failure, name)
            assertFalse(fixture.state.write.writing, name)
            assertEquals(ROOT_ITEMS, fixture.storedItems(ROOT_ID), name)
            assertTrue(fixture.logs.any { it.startsWith("溜めた削除を書けなかった") }, name)
        }
    }

    // ほかの操作を書く前に、溜めた削除を先に書く。書けたら続けてその操作を書く
    @Test
    fun pendingDeletesAreWrittenBeforeOtherOperation() = runTest {
        val fixture = startedWithPendingDelete()
        fixture.store.unreachable = false

        fixture.controller.createFolder(ROOT_ID, "C")
        runCurrent()

        assertEquals(listOf(ROOT_ITEMS[1]), fixture.storedItems(ROOT_ID))
        assertEquals(listOf("A", "B", "C"), fixture.storedRootChildNames())
        assertEquals(2, fixture.manifestPuts)
        assertEquals(emptySet(), fixture.state.browse.pendingDeletes)
        assertFalse(fixture.pendingDeletesPath().exists())
        assertEquals(WriteOutcome.Written, fixture.state.write.outcome)
        assertEquals(fixture.store.storedManifest(), fixture.state.browse.tree?.manifest)
    }

    // ほかの操作を書く前の溜めた削除が書けなければ、その操作は書かない。溜めた削除は溜めたままにし、その失敗を操作の結果に出す
    // （つながらなければ、つながらない失敗）
    @Test
    fun operationIsNotWrittenWhenPendingDeletesFail() = runTest {
        /**
         * 場合の名前、溜めた削除を書けなくする用意、操作の結果、溜めた削除を書くときに送るマニフェストの PUT の数と、
         * ルートフォルダの項目ファイルの PUT の数（溜めた削除を書こうとしたことを示す）。
         */
        data class FailureCase(
            val name: String,
            val arrange: (ControllerFixture) -> Unit,
            val outcome: WriteOutcome,
            val manifestPuts: Int,
            val rootItemsPuts: Int,
        )
        val cases = listOf(
            FailureCase("つながらない", arrange = {}, WriteOutcome.Failed(UNREACHABLE), manifestPuts = 0, rootItemsPuts = 0),
            FailureCase(
                "権限が無い",
                arrange = { fixture ->
                    fixture.store.unreachable = false
                    fixture.store.onNextPut(PutFault.Forbidden, key = fixture.isManifest)
                },
                WriteOutcome.Failed(SyncFailure.Forbidden),
                manifestPuts = 1,
                rootItemsPuts = 1,
            ),
        )
        cases.forEach { (name, arrange, expectedOutcome, expectedManifestPuts, expectedRootItemsPuts) ->
            val fixture = startedWithPendingDelete()
            arrange(fixture)
            val isRootItemsFile: (String) -> Boolean = { key -> key.contains("/$ROOT_ID/") }
            val manifestPutsBefore = fixture.manifestPuts
            val rootItemsPutsBefore = fixture.store.puts(isRootItemsFile).size

            fixture.controller.createFolder(ROOT_ID, "C")
            runCurrent()

            assertEquals(expectedOutcome, fixture.state.write.outcome, name)
            assertEquals(listOf("A", "B"), fixture.storedRootChildNames(), name)
            // 操作のマニフェストの PUT は送らない
            assertEquals(expectedManifestPuts, fixture.manifestPuts - manifestPutsBefore, name)
            assertEquals(expectedRootItemsPuts, fixture.store.puts(isRootItemsFile).size - rootItemsPutsBefore, name)
            assertEquals(setOf(rootItemRef(0)), fixture.state.browse.pendingDeletes, name)
            assertEquals(setOf(rootItemRef(0)), fixture.recordedPendingDeletes(), name)
            assertFalse(fixture.state.write.writing, name)
        }
    }

    // 項目の削除の前に溜めた削除を書いて、つながらないため以外（権限が無い）で書けなければ、その削除は書かず、溜めもしない。
    // 溜めた削除はそのまま残し、失敗を削除の結果に出す
    @Test
    fun deleteIsNotPendedWhenPendingDeletesFailOtherwise() = runTest {
        val fixture = startedWithPendingDelete()
        fixture.store.unreachable = false
        fixture.store.onNextPut(PutFault.Forbidden, key = fixture.isManifest)

        fixture.deleteItem(ROOT_ITEMS[1])
        runCurrent()

        assertEquals(WriteOutcome.Failed(SyncFailure.Forbidden), fixture.state.write.outcome)
        assertEquals(setOf(rootItemRef(0)), fixture.state.browse.pendingDeletes)
        assertEquals(setOf(rootItemRef(0)), fixture.recordedPendingDeletes())
        assertNull(fixture.state.write.undoableDelete)
        assertEquals(FolderReadResult.Loaded(listOf(ROOT_ITEMS[1])), fixture.state.browse.currentItems)
        assertEquals(ROOT_ITEMS, fixture.storedItems(ROOT_ID))
        // 溜めた削除を書いた 1 回だけ
        assertEquals(1, fixture.manifestPuts)
    }

    // 項目の削除の前に溜めた削除を書けて、その削除だけがつながらないために書けなければ、新しい対象だけを溜める。
    // ツリーは溜めた削除を書いたあとのものになる
    @Test
    fun deleteAfterWrittenPendingDeletesPendsOnlyNewTargets() = runTest {
        val fixture = startedWithPendingDelete()
        fixture.store.unreachable = false
        // 溜めた削除を書く手順 1 の GET は届く。削除を書く手順 1 の GET を受けたところでつながらなくなり、続くマニフェストの PUT から届かない
        fixture.store.onNextGet(GetFault.RunBefore {}, key = fixture.isManifest)
        fixture.store.onNextGet(GetFault.RunBefore { fixture.store.unreachable = true }, key = fixture.isManifest)

        fixture.deleteItem(ROOT_ITEMS[1])
        runCurrent()

        assertEquals(listOf(ROOT_ITEMS[1]), fixture.storedItems(ROOT_ID))
        assertEquals(fixture.store.storedManifest(), fixture.state.browse.tree?.manifest)
        assertEquals(setOf(rootItemRef(1)), fixture.state.browse.pendingDeletes)
        assertEquals(setOf(rootItemRef(1)), fixture.recordedPendingDeletes())
        assertEquals(setOf(rootItemRef(1)), fixture.state.write.undoableDelete?.pendingTargets)
        assertNull(fixture.state.write.outcome)
        assertEquals(FolderReadResult.Loaded(emptyList()), fixture.state.browse.currentItems)
    }

    // つながらないあいだに溜めたあと、ほかの書き手が S3 を書き換えていても、読み直したあとに溜めた削除を書く。対象がもう無ければ
    // 何もしない（項目はフォルダの ID と項目の ID の組で指すので、ほかのフォルダへ移されていた項目は消さない）。どの場合も結果は出さず、
    // 溜めた削除を空にして記録のファイルを消す。対象がすべて無ければ、マニフェストを書き換えない
    @Test
    fun pendingDeletesAfterOtherWriterChanges() = runTest {
        /** 場合の名前、溜める項目（フォルダの ID と項目）、ほかの書き手の書き換え、読み直したあとのマニフェストの PUT の数、そのあとの確かめ方。 */
        data class OtherWriterCase(
            val name: String,
            val pend: List<Pair<String, Item>>,
            val change: (FakeObjectStore) -> Unit,
            val manifestPuts: Int,
            val check: (ControllerFixture) -> Unit = {},
        )
        val cases = listOf(
            OtherWriterCase(
                "同じ項目が消されていた",
                pend = listOf(ROOT_ID to ROOT_ITEMS[0]),
                change = { it.replaceItemsAsOtherWriter(ROOT_ID, fileId(30), listOf(ROOT_ITEMS[1])) },
                manifestPuts = 0,
            ),
            OtherWriterCase(
                "別のフォルダへ移されていた",
                pend = listOf(ROOT_ID to ROOT_ITEMS[0]),
                change = { store ->
                    store.replaceItemsAsOtherWriter(ROOT_ID, fileId(30), listOf(ROOT_ITEMS[1]))
                    store.replaceItemsAsOtherWriter(FOLDER_A, fileId(31), A_ITEMS + ROOT_ITEMS[0])
                },
                manifestPuts = 0,
            ) { fixture -> assertEquals(A_ITEMS + ROOT_ITEMS[0], fixture.storedItems(FOLDER_A)) },
            OtherWriterCase(
                "フォルダごと消されていた",
                pend = listOf(FOLDER_A to A_ITEMS[0]),
                change = { it.deleteFolderAsOtherWriter(FOLDER_A) },
                manifestPuts = 0,
            ),
            OtherWriterCase(
                "一部だけ無い",
                pend = listOf(ROOT_ID to ROOT_ITEMS[0], FOLDER_A to A_ITEMS[0]),
                change = { it.replaceItemsAsOtherWriter(ROOT_ID, fileId(30), listOf(ROOT_ITEMS[1])) },
                manifestPuts = 1,
            ) { fixture ->
                assertEquals(listOf(ROOT_ITEMS[1]), fixture.storedItems(ROOT_ID))
                assertEquals(emptyList(), fixture.storedItems(FOLDER_A))
            },
        )
        cases.forEach { case ->
            val fixture = started()
            // つながっているうちに A の項目を読んでおく
            fixture.controller.openFolder(FOLDER_A)
            runCurrent()
            fixture.controller.openFolder(ROOT_ID)
            runCurrent()
            fixture.store.unreachable = true
            case.pend.forEach { (folderId, item) ->
                fixture.controller.openFolder(folderId)
                fixture.deleteItem(item)
                runCurrent()
            }
            fixture.controller.openFolder(ROOT_ID)
            runCurrent()
            val pending = case.pend.map { (folderId, item) -> ItemRef(folderId, item.id) }.toSet()
            assertEquals(pending, fixture.state.browse.pendingDeletes, case.name)
            fixture.store.unreachable = false
            case.change(fixture.store)
            val manifestPutsBefore = fixture.manifestPuts

            fixture.controller.reload()
            runCurrent()

            assertNull(fixture.state.write.outcome, case.name)
            assertEquals(emptySet(), fixture.state.browse.pendingDeletes, case.name)
            assertFalse(fixture.pendingDeletesPath().exists(), case.name)
            assertEquals(case.manifestPuts, fixture.manifestPuts - manifestPutsBefore, case.name)
            assertEquals(fixture.store.storedManifest(), fixture.state.browse.tree?.manifest, case.name)
            case.check(fixture)
        }
    }

    // 溜めた削除を書けたあとは、その削除を元に戻すと、項目の挿入を S3 に書いて戻す
    @Test
    fun undoAfterPendingDeleteIsWrittenRestoresOnStorage() = runTest {
        val fixture = startedWithPendingDelete()
        fixture.store.unreachable = false
        fixture.controller.reload()
        runCurrent()
        val undoable = assertNotNull(fixture.state.write.undoableDelete)
        assertFalse(undoable.pending)
        assertEquals(listOf(ROOT_ITEMS[1]), fixture.storedItems(ROOT_ID))

        assertTrue(fixture.controller.undoDelete())
        runCurrent()

        assertEquals(ROOT_ITEMS, fixture.storedItems(ROOT_ID))
        assertEquals(FolderReadResult.Loaded(ROOT_ITEMS), fixture.state.browse.currentItems)
        assertNull(fixture.state.write.undoableDelete)
    }

    // 同じ接続先で設定を保存し直すと、溜めた削除を読み直して残し、読めたあとに書く
    @Test
    fun savingSameTargetKeepsAndAppliesPendingDeletes() = runTest {
        val fixture = startedWithPendingDelete()
        fixture.store.unreachable = false

        fixture.controller.openSettings()
        fixture.controller.editSettings(SettingsForm.of(SETTINGS.copy(region = "us-east-1")))
        fixture.controller.saveSettings()
        runCurrent()

        assertEquals(Screen.MAIN, fixture.state.screen)
        assertEquals(listOf(ROOT_ITEMS[1]), fixture.storedItems(ROOT_ID))
        assertEquals(emptySet(), fixture.state.browse.pendingDeletes)
        assertFalse(fixture.pendingDeletesPath().exists())
    }

    // 接続先を変えて保存したら、前の接続先の溜めた削除を捨て（記録のファイルを消し、S3 には書かない）、新しい接続先の溜めた削除の記録を読む
    @Test
    fun changingTargetDiscardsPreviousPendingDeletes() = runTest {
        val fixture = startedWithPendingDelete()
        fixture.store.unreachable = false
        val other = SETTINGS.copy(bucket = OTHER_BUCKET)
        val otherStore = fixture.addStore(OTHER_BUCKET).apply {
            unreachable = true
            seed()
        }
        PendingDeletesFile(fixture.pendingDeletesPath(other)) {}.write(setOf(rootItemRef(1)))

        fixture.controller.openSettings()
        fixture.controller.editSettings(SettingsForm.of(other))
        fixture.controller.saveSettings()
        runCurrent()

        assertFalse(fixture.pendingDeletesPath().exists())
        assertEquals(ROOT_ITEMS, fixture.storedItems(ROOT_ID))
        assertEquals(setOf(rootItemRef(1)), fixture.state.browse.pendingDeletes)
        assertNull(fixture.state.write.undoableDelete)
        assertEquals(0, otherStore.puts { true }.size)
    }

    // フォルダの項目をブラウザで開くときは、溜めた削除の項目を開かない。すべて溜めた削除なら、何も開かない
    @Test
    fun openingFolderItemsSkipsPendingItems() = runTest {
        val fixture = startedWithPendingDelete()

        fixture.controller.openFolderItems(ROOT_ID, CHROME)
        runCurrent()
        assertEquals(listOf(CHROME.name to listOf(ROOT_ITEMS[1].url)), fixture.browserOpener.openedUrls)

        fixture.deleteItem(ROOT_ITEMS[1])
        runCurrent()
        fixture.controller.openFolderItems(ROOT_ID, CHROME)
        runCurrent()
        assertEquals(1, fixture.browserOpener.openedUrls.size)
    }

    // 項目を読んでいないフォルダの項目をブラウザで開くときも、その項目ファイルが 404 を返してマニフェストを読み直したあとに開くときも、
    // 溜めた削除の項目を開かない
    @Test
    fun openingUnloadedFolderItemsSkipsPendingItems() = runTest {
        val pendingTarget = ItemRef(FOLDER_A, TWO_A_ITEMS[0].id)
        val cases: List<Triple<String, (ControllerFixture) -> Unit, List<Item>>> = listOf(
            Triple("項目を読んでいないフォルダ", {}, listOf(TWO_A_ITEMS[1])),
            Triple(
                "項目ファイルが 404 を返したあと",
                { fixture ->
                    fixture.store.replaceItemsAsOtherWriter(FOLDER_A, fileId(32), listOf(TWO_A_ITEMS[0], item(8)))
                    fixture.store.remove(fixture.itemsKey(FOLDER_A, TWO_A_ITEMS_FILE_ID))
                },
                listOf(item(8)),
            ),
        )
        cases.forEach { (name, arrange, expectedOpened) ->
            val fixture = started { fixture ->
                fixture.store.replaceItemsAsOtherWriter(FOLDER_A, TWO_A_ITEMS_FILE_ID, TWO_A_ITEMS)
                PendingDeletesFile(fixture.pendingDeletesPath()) {}.write(setOf(pendingTarget))
                // 起動して読んだあと、溜めた削除を書く手順 1 の GET が権限が無いとして返り、溜めたままになる
                fixture.store.onNextGet(GetFault.RunBefore {}, key = fixture.isManifest)
                fixture.store.onNextGet(GetFault.Forbidden, key = fixture.isManifest)
            }
            assertEquals(setOf(pendingTarget), fixture.state.browse.pendingDeletes, name)
            assertNull(fixture.state.browse.folderReads[FOLDER_A], name)
            arrange(fixture)

            fixture.controller.openFolderItems(FOLDER_A, CHROME)
            runCurrent()

            assertEquals(listOf(CHROME.name to expectedOpened.map { it.url }), fixture.browserOpener.openedUrls, name)
        }
    }
}

/** 「not gzip」を置いたマニフェストを読んだときの問題。読んだ結果から取り出す。 */
private fun corruptManifestProblem(): StorageProblem =
    assertIs<ManifestDecodeResult.Corrupt>(decodeManifestGzip("not gzip".toByteArray())).problem
