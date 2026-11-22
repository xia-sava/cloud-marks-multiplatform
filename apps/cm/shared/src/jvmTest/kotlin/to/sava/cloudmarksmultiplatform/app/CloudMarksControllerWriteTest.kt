package to.sava.cloudmarksmultiplatform.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.browse.MoveDestination
import to.sava.cloudmarksmultiplatform.browse.MoveSubject
import to.sava.cloudmarksmultiplatform.browse.SelectionMode
import to.sava.cloudmarksmultiplatform.operations.InsertItems
import to.sava.cloudmarksmultiplatform.operations.NotAppliedReason
import to.sava.cloudmarksmultiplatform.operations.OperationStatus
import to.sava.cloudmarksmultiplatform.operations.TreeState
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.GetFault
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.PutFault
import to.sava.cloudmarksmultiplatform.settings.SETTINGS
import to.sava.cloudmarksmultiplatform.settings.SettingsForm
import to.sava.cloudmarksmultiplatform.storage.FolderEntry
import to.sava.cloudmarksmultiplatform.storage.Item
import to.sava.cloudmarksmultiplatform.storage.Manifest
import to.sava.cloudmarksmultiplatform.sync.A_ITEMS
import to.sava.cloudmarksmultiplatform.sync.FOLDER_A
import to.sava.cloudmarksmultiplatform.sync.FOLDER_B
import to.sava.cloudmarksmultiplatform.sync.FolderReadResult
import to.sava.cloudmarksmultiplatform.sync.MAX_RESENDS
import to.sava.cloudmarksmultiplatform.sync.ROOT_ID
import to.sava.cloudmarksmultiplatform.sync.ROOT_ITEMS
import to.sava.cloudmarksmultiplatform.sync.SyncFailure
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A の子のフォルダ。フォルダの移動が新しい親の子の末尾に置くことを確かめるのに使う。 */
private val CHILD_OF_A = folderId(9)

/** 元に戻す位置を確かめるための、ルートフォルダの 5 つの項目。 */
private val FIVE_ITEMS = (1..5).map { item(it) }

/** [initialManifest] の A に、項目を持たない子のフォルダ [CHILD_OF_A] を足したもの。ルートフォルダ → A（→ A1）、B。 */
private fun manifestWithChildOfA(): Manifest =
    initialManifest().let { manifest ->
        manifest.copy(folders = manifest.folders.take(2) + FolderEntry(CHILD_OF_A, FOLDER_A, "A1", null, 0) + manifest.folders.drop(2))
    }

/** コントローラが読んであるツリー。 */
private val ControllerFixture.tree: TreeState get() = assertNotNull(state.browse.tree).state

/** フォルダ [folderId] の子のフォルダの名前。マニフェストの行の順。 */
private fun ControllerFixture.childNames(folderId: String): List<String> =
    tree.folders.getValue(folderId).children.map { tree.folders.getValue(it).name }

/** S3 に置いてあるマニフェストが指す、フォルダ [folderId] の項目。 */
private fun ControllerFixture.storedItems(folderId: String): List<Item> = store.storedItems(store.storedManifest(), folderId)

// runCurrent が ExperimentalCoroutinesApi であるため。
@OptIn(ExperimentalCoroutinesApi::class)
class CloudMarksControllerWriteTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** [manifestWithChildOfA] を置いて保存済みの設定で起動し、ルートフォルダを開いたフィクスチャ。[arrange] は起動の前に走らせる。 */
    private fun TestScope.started(arrange: (ControllerFixture) -> Unit = {}): ControllerFixture {
        val fixture = ControllerFixture(this, temporaryFolder)
        fixture.store.seed(manifestWithChildOfA())
        fixture.storeSettings()
        arrange(fixture)
        fixture.controller.start()
        runCurrent()
        return fixture
    }

    /** 場合の名前、コントローラへの操作、そのあとの確かめ方。 */
    private data class OperationCase(
        val name: String,
        val act: (CloudMarksController) -> Unit,
        val check: (ControllerFixture) -> Unit,
    )

    // 操作ごとに、正しい操作として S3 に書き、書いたあとのツリーに替える。結果は「書けた」で、書いている最中ではなくなる
    @Test
    fun operationsAreWrittenAndTreeIsReplaced() = runTest {
        val cases = listOf(
            OperationCase("選んでいる項目の削除", act = { controller ->
                controller.selectItem(ROOT_ITEMS[0].id, SelectionMode.SINGLE)
                controller.deleteSelectedItems()
            }) { fixture ->
                assertEquals(listOf(ROOT_ITEMS[1]), fixture.storedItems(ROOT_ID))
                assertEquals(FolderReadResult.Loaded(listOf(ROOT_ITEMS[1])), fixture.state.browse.currentItems)
                // 消した項目は選択から外れる
                assertEquals(emptySet(), fixture.state.browse.selectedItemIds)
            },
            OperationCase("選んでいる項目の、移動先の末尾への移動", act = { controller ->
                controller.selectItem(ROOT_ITEMS[0].id, SelectionMode.SINGLE)
                controller.selectItem(ROOT_ITEMS[1].id, SelectionMode.RANGE)
                controller.moveSelectedItems(FOLDER_A)
            }) { fixture ->
                assertEquals(A_ITEMS + ROOT_ITEMS, fixture.storedItems(FOLDER_A))
                assertEquals(emptyList(), fixture.storedItems(ROOT_ID))
            },
            OperationCase("項目のタイトルの変更", act = { it.renameItem(rootItemRef(1), "renamed") }) { fixture ->
                assertEquals(listOf(ROOT_ITEMS[0], ROOT_ITEMS[1].copy(title = "renamed")), fixture.storedItems(ROOT_ID))
            },
            OperationCase("項目のタイトルは空にできる", act = { it.renameItem(rootItemRef(1), "") }) { fixture ->
                assertEquals(listOf(ROOT_ITEMS[0], ROOT_ITEMS[1].copy(title = "")), fixture.storedItems(ROOT_ID))
            },
            OperationCase("フォルダの作成は、親の子のフォルダの末尾", act = { it.createFolder(ROOT_ID, "C") }) { fixture ->
                assertEquals(listOf("A", "B", "C"), fixture.childNames(ROOT_ID))
                assertEquals(ROOT_ID, fixture.tree.folders.getValue(fixture.tree.folders.getValue(ROOT_ID).children.last()).parent)
            },
            OperationCase("フォルダの名前の変更", act = { it.renameFolder(FOLDER_A, "A'") }) { fixture ->
                assertEquals("A'", fixture.tree.folders.getValue(FOLDER_A).name)
            },
            OperationCase("フォルダの移動は、新しい親の子のフォルダの末尾", act = { it.moveFolder(FOLDER_B, FOLDER_A) }) { fixture ->
                assertEquals(listOf(CHILD_OF_A, FOLDER_B), fixture.tree.folders.getValue(FOLDER_A).children)
                assertEquals(listOf(FOLDER_A), fixture.tree.folders.getValue(ROOT_ID).children)
            },
            OperationCase("フォルダの削除は子孫ごと", act = { it.deleteFolder(FOLDER_A) }) { fixture ->
                assertEquals(setOf(ROOT_ID, FOLDER_B), fixture.tree.folders.keys)
                // フォルダの削除は元に戻せない
                assertNull(fixture.state.write.undoableDelete)
            },
        )
        cases.forEach { (name, act, check) ->
            val fixture = started()

            act(fixture.controller)
            runCurrent()

            assertEquals(fixture.store.storedManifest(), fixture.state.browse.tree?.manifest, name)
            assertEquals(WriteOutcome.Written, fixture.state.write.outcome, name)
            assertFalse(fixture.state.write.writing, name)
            check(fixture)
        }
    }

    // 書けたら、マニフェストも開いているフォルダの項目ファイルも GET し直さずに、開いているフォルダの項目を出す
    // （書き込みが項目を読まなかったフォルダも出す）
    @Test
    fun successShowsOpenFolderWithoutRereadingManifest() = runTest {
        val fixture = started()
        val manifestGetsBefore = fixture.store.gets(fixture.isManifest).size
        val isRootItemsFile: (String) -> Boolean = { key -> key.contains("/$ROOT_ID/") }
        val rootItemsGetsBefore = fixture.store.gets(isRootItemsFile).size
        assertTrue(rootItemsGetsBefore > 0)

        fixture.controller.createFolder(ROOT_ID, "C")
        runCurrent()

        // 書き込みの手順 1 の GET だけ
        assertEquals(manifestGetsBefore + 1, fixture.store.gets(fixture.isManifest).size)
        assertEquals(rootItemsGetsBefore, fixture.store.gets(isRootItemsFile).size)
        assertEquals(ROOT_ID, fixture.state.browse.currentFolderId)
        assertEquals(FolderReadResult.Loaded(ROOT_ITEMS), fixture.state.browse.currentItems)
    }

    // 項目の削除を元に戻すと、消した項目を元の位置へ挿入する（続けて消した項目・先頭・末尾・すべて）。
    // 消したあとは消した数を出し、元に戻したあとは、もう戻せない
    @Test
    fun undoRestoresDeletedItemsToTheirPositions() = runTest {
        val cases: List<Pair<List<Pair<Int, SelectionMode>>, List<Int>>> = listOf(
            // 2 と 3 を続けて、5 を末尾で
            listOf(2 to SelectionMode.SINGLE, 3 to SelectionMode.RANGE, 5 to SelectionMode.TOGGLE) to listOf(1, 4),
            // 先頭の 1 だけ
            listOf(1 to SelectionMode.SINGLE) to listOf(2, 3, 4, 5),
            // すべて
            listOf(1 to SelectionMode.SINGLE, 5 to SelectionMode.RANGE) to emptyList(),
        )
        cases.forEach { (clicks, remaining) ->
            val fixture = started { it.store.replaceItemsAsOtherWriter(ROOT_ID, fileId(30), FIVE_ITEMS) }
            clicks.forEach { (n, mode) -> fixture.controller.selectItem(item(n).id, mode) }
            fixture.controller.deleteSelectedItems()
            runCurrent()
            assertEquals(remaining.map { item(it) }, fixture.storedItems(ROOT_ID), "$clicks")
            assertEquals(FIVE_ITEMS.size - remaining.size, fixture.state.write.undoableDelete?.count, "$clicks")

            fixture.controller.undoDelete()
            runCurrent()

            assertEquals(FIVE_ITEMS, fixture.storedItems(ROOT_ID), "$clicks")
            assertEquals(FolderReadResult.Loaded(FIVE_ITEMS), fixture.state.browse.currentItems, "$clicks")
            assertEquals(WriteOutcome.Written, fixture.state.write.outcome, "$clicks")
            assertNull(fixture.state.write.undoableDelete, "$clicks")
        }
    }

    // 元に戻す挿入は、続けて消した項目を 1 つにまとめ、消す前の並びですぐ後ろに残る項目の前を位置にする。
    // 後ろに残る項目が無ければ位置を指定しない（末尾）
    @Test
    fun restoringInsertionsGroupConsecutiveDeletions() {
        fun insert(numbers: List<Int>, before: Int?) = InsertItems(ROOT_ID, numbers.map { item(it) }, before?.let { item(it).id })
        val cases: List<Pair<Set<Int>, List<InsertItems>>> = listOf(
            setOf(2, 3, 5) to listOf(insert(listOf(2, 3), before = 4), insert(listOf(5), before = null)),
            setOf(1, 3) to listOf(insert(listOf(1), before = 2), insert(listOf(3), before = 4)),
            setOf(4, 5) to listOf(insert(listOf(4, 5), before = null)),
            (1..5).toSet() to listOf(insert((1..5).toList(), before = null)),
        )
        cases.forEach { (deleted, expected) ->
            assertEquals(expected, restoringInsertions(ROOT_ID, FIVE_ITEMS, deleted.map { item(it).id }.toSet()), "$deleted")
        }
    }

    // 項目を消したあと、ほかの操作を書くか、元に戻すお知らせを閉じると、もう戻せない（元に戻そうとしても何も書かない）
    @Test
    fun undoIsLostAfterAnotherOperationOrDismissal() = runTest {
        val cases: List<Pair<String, (CloudMarksController) -> Unit>> = listOf(
            "ほかの操作を書いた" to { it.createFolder(ROOT_ID, "C") },
            "お知らせを閉じた" to { it.dismissUndo() },
        )
        cases.forEach { (name, loseUndo) ->
            val fixture = started()
            fixture.controller.selectItem(ROOT_ITEMS[0].id, SelectionMode.SINGLE)
            fixture.controller.deleteSelectedItems()
            runCurrent()
            assertNotNull(fixture.state.write.undoableDelete, name)
            loseUndo(fixture.controller)
            runCurrent()
            val requestsBefore = fixture.store.requests.size

            fixture.controller.undoDelete()
            runCurrent()

            assertNull(fixture.state.write.undoableDelete, name)
            assertEquals(requestsBefore, fixture.store.requests.size, name)
            assertEquals(listOf(ROOT_ITEMS[1]), fixture.storedItems(ROOT_ID), name)
        }
    }

    // 項目を指定して消すと、選んでいる項目ではなく、指定した項目だけを消し、選んでいる項目は変えない。元に戻すと元の位置へ戻す
    @Test
    fun deleteItemsDeletesGivenItemsAndKeepsSelection() = runTest {
        val fixture = started { it.store.replaceItemsAsOtherWriter(ROOT_ID, fileId(30), FIVE_ITEMS) }
        fixture.controller.selectItem(item(1).id, SelectionMode.SINGLE)

        fixture.controller.deleteItems(setOf(item(2).id, item(4).id))
        runCurrent()

        assertEquals(listOf(item(1), item(3), item(5)), fixture.storedItems(ROOT_ID))
        assertEquals(setOf(item(1).id), fixture.state.browse.selectedItemIds)
        assertEquals(2, fixture.state.write.undoableDelete?.count)

        fixture.controller.undoDelete()
        runCurrent()

        assertEquals(FIVE_ITEMS, fixture.storedItems(ROOT_ID))
    }

    // 開いているフォルダの読めた項目に無い ID だけを指定して消そうとしても、何も書かない
    @Test
    fun deleteItemsIgnoresUnknownIds() = runTest {
        val fixture = started()
        val requestsBefore = fixture.store.requests.size

        fixture.controller.deleteItems(setOf(item(99).id))
        runCurrent()

        assertEquals(requestsBefore, fixture.store.requests.size)
        assertEquals(ROOT_ITEMS, fixture.storedItems(ROOT_ID))
        assertNull(fixture.state.write.undoableDelete)
    }

    // 結果のお知らせを閉じると結果を消し、元に戻せる削除は残す
    @Test
    fun dismissingOutcomeKeepsUndo() = runTest {
        val fixture = started()
        fixture.controller.selectItem(ROOT_ITEMS[0].id, SelectionMode.SINGLE)
        fixture.controller.deleteSelectedItems()
        runCurrent()

        fixture.controller.dismissWriteOutcome()

        assertNull(fixture.state.write.outcome)
        assertEquals(1, fixture.state.write.undoableDelete?.count)
    }

    // 書いている最中は、書いている最中の状態にし、ほかの操作も読み直しも受け付けない。書き終えたら受け付ける
    @Test
    fun whileWritingOtherOperationsAndReloadAreRefused() = runTest {
        val fixture = started()
        val gate = fixture.stopNextGet(fixture.isManifest)
        fixture.controller.createFolder(ROOT_ID, "C")
        runCurrent()
        val requestsBefore = fixture.store.requests.size

        fixture.controller.createFolder(ROOT_ID, "D")
        fixture.controller.renameFolder(FOLDER_A, "A'")
        fixture.controller.deleteFolder(FOLDER_B)
        fixture.controller.reload()
        runCurrent()

        assertTrue(fixture.state.write.writing)
        assertFalse(fixture.state.canWrite)
        assertFalse(fixture.state.canReload)
        assertEquals(requestsBefore, fixture.store.requests.size)

        gate.complete(Unit)
        runCurrent()

        assertFalse(fixture.state.write.writing)
        assertTrue(fixture.state.canWrite)
        assertTrue(fixture.state.canReload)
        assertEquals(listOf("A", "B", "C"), fixture.childNames(ROOT_ID))
    }

    // 適用できなかった操作があれば、書けたうえで、その操作と理由を結果に出す（新しい親が自分の子孫、ルートフォルダの名前の変更）
    @Test
    fun notAppliedOperationsAreReported() = runTest {
        val cases: List<Triple<String, (CloudMarksController) -> Unit, NotAppliedReason>> = listOf(
            Triple("新しい親が自分の子孫", { it.moveFolder(FOLDER_A, CHILD_OF_A) }, NotAppliedReason.NewParentIsSelfOrDescendant),
            Triple("ルートフォルダの名前の変更", { it.renameFolder(ROOT_ID, "root") }, NotAppliedReason.RootNotAllowed),
        )
        cases.forEach { (name, act, reason) ->
            val fixture = started()
            val manifestBefore = fixture.store.storedManifest()

            act(fixture.controller)
            runCurrent()

            val outcome = assertIs<WriteOutcome.NotApplied>(fixture.state.write.outcome, name)
            assertEquals(listOf(OperationStatus.NotApplied(reason)), outcome.results.map { it.status }, name)
            assertEquals(manifestBefore, fixture.store.storedManifest(), name)
            assertFalse(fixture.state.write.writing, name)
        }
    }

    // 書けたかどうかを確かめられなければ、同じ操作の並び（同じ ID）でもう一度だけ書く。先の書き込みが届いていれば、
    // やり直しでは適用済みになり、フォルダは 1 つだけできる
    @Test
    fun unconfirmedWriteIsRetriedOnceWithSameOperations() = runTest {
        val fixture = started()
        fixture.store.onNextPut(
            PutFault.WriteThenUnknown {
                repeat(MAX_RESENDS + 1) { fixture.store.onNextGet(GetFault.ServerError, key = fixture.isManifest) }
            },
            key = fixture.isManifest,
        )

        fixture.controller.createFolder(ROOT_ID, "C")
        runCurrent()

        assertEquals(WriteOutcome.Written, fixture.state.write.outcome)
        assertEquals(listOf("A", "B", "C"), fixture.childNames(ROOT_ID))
        assertEquals(fixture.store.storedManifest(), fixture.state.browse.tree?.manifest)
        assertEquals(1, fixture.store.puts(fixture.isManifest).size)
    }

    // やり直しも書けなければ、確かめられないとして結果に出す。やり直しは 1 回だけで、ツリーは書く前のまま
    @Test
    fun retryThatDoesNotSucceedIsUnconfirmed() = runTest {
        val fixture = started()
        val treeBefore = fixture.state.browse.tree
        fixture.store.onNextPut(
            PutFault.WriteThenUnknown {
                repeat(MAX_RESENDS + 1) { fixture.store.onNextGet(GetFault.ServerError, key = fixture.isManifest) }
                // やり直しの手順 1
                fixture.store.onNextGet(GetFault.Forbidden, key = fixture.isManifest)
            },
            key = fixture.isManifest,
        )
        val manifestGetsBefore = fixture.store.gets(fixture.isManifest).size

        fixture.controller.createFolder(ROOT_ID, "C")
        runCurrent()

        assertEquals(WriteOutcome.Unconfirmed(SyncFailure.Forbidden), fixture.state.write.outcome)
        assertEquals(treeBefore, fixture.state.browse.tree)
        assertFalse(fixture.state.write.writing)
        // 最初の書き込みの手順 1、成功の判定の読み直しとその再送、やり直しの手順 1
        assertEquals(manifestGetsBefore + 1 + (MAX_RESENDS + 1) + 1, fixture.store.gets(fixture.isManifest).size)
    }

    // 書けなければ、失敗の種類を結果に出し、ツリーは書く前のまま
    @Test
    fun failedWriteKeepsTree() = runTest {
        val fixture = started()
        val treeBefore = fixture.state.browse.tree
        fixture.store.onNextPut(PutFault.Forbidden, key = fixture.isManifest)

        fixture.controller.createFolder(ROOT_ID, "C")
        runCurrent()

        assertEquals(WriteOutcome.Failed(SyncFailure.Forbidden), fixture.state.write.outcome)
        assertEquals(treeBefore, fixture.state.browse.tree)
        assertFalse(fixture.state.write.writing)
    }

    // 元に戻す書き込みが書けなかったか確かめられなかったときは、同じ削除をもう一度戻せる。もう一度戻すと元の並びになり、
    // 先の書き込みが届いていても（確かめられなかった場合）、項目は二重にならない
    @Test
    fun undoCanBeRetriedAfterFailedOrUnconfirmedUndo() = runTest {
        val cases: List<Triple<String, (ControllerFixture) -> Unit, WriteOutcome>> = listOf(
            Triple(
                "書けなかった",
                { it.store.onNextPut(PutFault.Forbidden, key = it.isManifest) },
                WriteOutcome.Failed(SyncFailure.Forbidden),
            ),
            Triple(
                "確かめられなかった",
                { fixture ->
                    fixture.store.onNextPut(
                        PutFault.WriteThenUnknown {
                            repeat(MAX_RESENDS + 1) { fixture.store.onNextGet(GetFault.ServerError, key = fixture.isManifest) }
                            // やり直しの手順 1
                            fixture.store.onNextGet(GetFault.Forbidden, key = fixture.isManifest)
                        },
                        key = fixture.isManifest,
                    )
                },
                WriteOutcome.Unconfirmed(SyncFailure.Forbidden),
            ),
        )
        cases.forEach { (name, breakNextWrite, expectedOutcome) ->
            val fixture = started()
            fixture.controller.selectItem(ROOT_ITEMS[0].id, SelectionMode.SINGLE)
            fixture.controller.deleteSelectedItems()
            runCurrent()
            val undoable = assertNotNull(fixture.state.write.undoableDelete, name)
            breakNextWrite(fixture)

            fixture.controller.undoDelete()
            runCurrent()

            assertEquals(expectedOutcome, fixture.state.write.outcome, name)
            assertEquals(undoable, fixture.state.write.undoableDelete, name)
            assertFalse(fixture.state.write.writing, name)

            fixture.controller.undoDelete()
            runCurrent()

            assertEquals(ROOT_ITEMS, fixture.storedItems(ROOT_ID), name)
            assertEquals(FolderReadResult.Loaded(ROOT_ITEMS), fixture.state.browse.currentItems, name)
            assertNull(fixture.state.write.undoableDelete, name)
        }
    }

    // 空の名前のフォルダの作成と名前の変更は、操作の適用が断る。書き換えずに、適用しなかった理由を結果に出す。
    // 空にできるのは項目のタイトルだけで、フォルダの名前は空にできない
    @Test
    fun emptyFolderNameIsNotApplied() = runTest {
        val cases: List<Pair<String, (CloudMarksController) -> Unit>> = listOf(
            "フォルダの作成" to { it.createFolder(ROOT_ID, "") },
            "フォルダの名前の変更" to { it.renameFolder(FOLDER_A, "") },
        )
        cases.forEach { (name, act) ->
            val fixture = started()
            val manifestBefore = fixture.store.storedManifest()

            act(fixture.controller)
            runCurrent()

            val outcome = assertIs<WriteOutcome.NotApplied>(fixture.state.write.outcome, name)
            assertEquals(listOf(OperationStatus.NotApplied(NotAppliedReason.EmptyName)), outcome.results.map { it.status }, name)
            assertEquals(manifestBefore, fixture.store.storedManifest(), name)
            assertFalse(fixture.state.write.writing, name)
        }
    }

    // 書いている最中に設定を保存し直すと、書いている最中の状態を捨てる。書き込みの取り消しは前の接続を閉じるより先に起き、
    // 止めた GET が進めるようになっても、前の接続の結果は状態に入らない
    @Test
    fun savingSettingsWhileWritingDiscardsWrite() = runTest {
        val fixture = started()
        val gate = fixture.stopNextGet(fixture.isManifest)
        fixture.controller.createFolder(ROOT_ID, "C")
        runCurrent()
        assertTrue(fixture.state.write.writing)

        fixture.controller.openSettings()
        fixture.controller.editSettings(SettingsForm.of(SETTINGS))
        fixture.controller.saveSettings()
        runCurrent()

        assertFalse(fixture.state.write.writing)
        assertEquals(listOf(CANCELLED, CLOSED), fixture.events)

        gate.complete(Unit)
        runCurrent()

        assertEquals(WriteState(), fixture.state.write)
        assertEquals(listOf("A", "B"), fixture.childNames(ROOT_ID))
        assertEquals(emptyList(), fixture.store.puts(fixture.isManifest))
        assertEquals(fixture.store.storedManifest(), fixture.state.browse.tree?.manifest)
    }

    // マニフェストを読み直している最中は、操作を受け付けない。リクエストを出さず、書いている最中にもならない
    @Test
    fun operationIsRefusedWhileRereadingManifest() = runTest {
        val fixture = started()
        val gate = fixture.stopNextGet(fixture.isManifest)
        fixture.controller.reload()
        runCurrent()
        assertTrue(fixture.state.browse.loading)
        val requestsBefore = fixture.store.requests.size

        fixture.controller.createFolder(ROOT_ID, "C")
        runCurrent()

        assertEquals(requestsBefore, fixture.store.requests.size)
        assertFalse(fixture.state.write.writing)

        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf("A", "B"), fixture.childNames(ROOT_ID))
        assertNull(fixture.state.write.outcome)
    }

    // 書けたあと、選んでいた項目と範囲の起点を残し、開いているフォルダの項目は読み込み中にならずに出し続ける
    @Test
    fun successKeepsSelectionAndOpenFolderItems() = runTest {
        val fixture = started()
        fixture.controller.selectItem(ROOT_ITEMS[1].id, SelectionMode.SINGLE)
        val seenItems = mutableListOf<FolderReadResult?>()
        backgroundScope.launch(Dispatchers.Unconfined) {
            fixture.controller.state.collect { seenItems += it.browse.currentItems }
        }

        fixture.controller.createFolder(ROOT_ID, "C")
        runCurrent()

        assertEquals(WriteOutcome.Written, fixture.state.write.outcome)
        assertEquals(setOf(ROOT_ITEMS[1].id), fixture.state.browse.selectedItemIds)
        assertEquals(ROOT_ITEMS[1].id, fixture.state.browse.selectionAnchorId)
        assertEquals(emptySet(), fixture.state.browse.loadingFolders)
        assertTrue(seenItems.isNotEmpty())
        assertEquals(setOf<FolderReadResult?>(FolderReadResult.Loaded(ROOT_ITEMS)), seenItems.toSet())
    }

    // 項目を別のフォルダへ移したあと、移動先の項目は、書いたあとの新しい項目ファイルのものになる（前に読んだ古い項目ではない）。
    // 開いているフォルダの項目は移した分だけ減り、選択は空になる
    @Test
    fun movingItemsShowsNewItemsOfDestination() = runTest {
        val fixture = started()
        fixture.controller.openFolder(FOLDER_B)
        runCurrent()
        assertEquals(FolderReadResult.Loaded(emptyList()), fixture.state.browse.folderReads[FOLDER_B])
        fixture.controller.openFolder(FOLDER_A)
        runCurrent()
        fixture.controller.selectItem(A_ITEMS[0].id, SelectionMode.SINGLE)

        fixture.controller.moveSelectedItems(FOLDER_B)
        runCurrent()

        assertEquals(WriteOutcome.Written, fixture.state.write.outcome)
        assertEquals(FolderReadResult.Loaded(A_ITEMS), fixture.state.browse.folderReads[FOLDER_B])
        assertEquals(FolderReadResult.Loaded(emptyList()), fixture.state.browse.currentItems)
        assertEquals(emptySet(), fixture.state.browse.selectedItemIds)
        assertEquals(A_ITEMS, fixture.storedItems(FOLDER_B))
    }

    // 選んだ順が表示の並びと逆でも、項目は表示の並びの順に移る（SINGLE で 2 つ目、TOGGLE で 1 つ目を選ぶ）
    @Test
    fun moveFollowsDisplayOrderNotSelectionOrder() = runTest {
        val fixture = started()
        fixture.controller.selectItem(ROOT_ITEMS[1].id, SelectionMode.SINGLE)
        fixture.controller.selectItem(ROOT_ITEMS[0].id, SelectionMode.TOGGLE)

        fixture.controller.moveSelectedItems(FOLDER_A)
        runCurrent()

        assertEquals(A_ITEMS + ROOT_ITEMS, fixture.storedItems(FOLDER_A))
    }

    // 移すものを渡して移すと、その時点で選んでいる項目ではなく、渡した項目を、渡した順に移動先の末尾へ移す
    @Test
    fun moveToMovesGivenItemsInGivenOrder() = runTest {
        val fixture = started { it.store.replaceItemsAsOtherWriter(ROOT_ID, fileId(30), FIVE_ITEMS) }
        fixture.controller.selectItem(item(1).id, SelectionMode.SINGLE)

        fixture.controller.moveTo(MoveSubject.Items(ROOT_ID, listOf(item(5).id, item(2).id)), MoveDestination.Into(FOLDER_A))
        runCurrent()

        assertEquals(WriteOutcome.Written, fixture.state.write.outcome)
        assertEquals(A_ITEMS + listOf(item(5), item(2)), fixture.storedItems(FOLDER_A))
        assertEquals(listOf(item(1), item(3), item(4)), fixture.storedItems(ROOT_ID))
    }

    // フォルダを渡して移すと、子孫ごと移動先の子のフォルダの末尾へ移す
    @Test
    fun moveToMovesFolderToEndOfDestination() = runTest {
        val fixture = started()

        fixture.controller.moveTo(MoveSubject.Folder(FOLDER_B), MoveDestination.Into(FOLDER_A))
        runCurrent()

        assertEquals(WriteOutcome.Written, fixture.state.write.outcome)
        assertEquals(listOf(CHILD_OF_A, FOLDER_B), fixture.tree.folders.getValue(FOLDER_A).children)
    }

    // 移動先にできないフォルダへ移そうとしても、何も書かない（項目のあるフォルダ、フォルダ自身・子孫・いまの親、ツリーに無いフォルダ）
    @Test
    fun moveToRefusesFoldersThatCannotBeDestinations() = runTest {
        val rootItem = MoveSubject.Items(ROOT_ID, listOf(ROOT_ITEMS[0].id))
        val cases = listOf(
            "項目のあるフォルダ" to (rootItem to ROOT_ID),
            "フォルダ自身" to (MoveSubject.Folder(FOLDER_A) to FOLDER_A),
            "フォルダの子孫" to (MoveSubject.Folder(FOLDER_A) to CHILD_OF_A),
            "フォルダのいまの親" to (MoveSubject.Folder(CHILD_OF_A) to FOLDER_A),
            "ツリーに無いフォルダ" to (rootItem to folderId(99)),
        )
        cases.forEach { (name, case) ->
            val (subject, destinationId) = case
            val fixture = started()
            val requestsBefore = fixture.store.requests.size

            fixture.controller.moveTo(subject, MoveDestination.Into(destinationId))
            runCurrent()

            assertEquals(requestsBefore, fixture.store.requests.size, name)
            assertFalse(fixture.state.write.writing, name)
            assertNull(fixture.state.write.outcome, name)
        }
    }

    // 位置を指定して移すと、同じフォルダの中の項目と、同じ親の中のフォルダを並べ替える（末尾への並べ替えも含む）。
    // ほかのフォルダの位置へも移す
    @Test
    fun moveToPositionReordersAndPlaces() = runTest {
        val cases = listOf(
            OperationCase(
                "項目を、同じフォルダの項目の前へ",
                act = { it.moveTo(MoveSubject.Items(ROOT_ID, listOf(item(1).id)), MoveDestination.At(ROOT_ID, item(4).id)) },
            ) { fixture ->
                assertEquals(listOf(2, 3, 1, 4, 5).map { item(it) }, fixture.storedItems(ROOT_ID))
            },
            OperationCase(
                "項目を、同じフォルダの末尾へ",
                act = { it.moveTo(MoveSubject.Items(ROOT_ID, listOf(item(2).id, item(4).id)), MoveDestination.At(ROOT_ID, null)) },
            ) { fixture ->
                assertEquals(listOf(1, 3, 5, 2, 4).map { item(it) }, fixture.storedItems(ROOT_ID))
            },
            OperationCase(
                "項目を、ほかのフォルダの項目の前へ",
                act = { it.moveTo(MoveSubject.Items(ROOT_ID, listOf(item(5).id)), MoveDestination.At(FOLDER_A, A_ITEMS[0].id)) },
            ) { fixture ->
                assertEquals(listOf(item(5)) + A_ITEMS, fixture.storedItems(FOLDER_A))
                assertEquals((1..4).map { item(it) }, fixture.storedItems(ROOT_ID))
            },
            OperationCase(
                "フォルダを、同じ親のフォルダの前へ",
                act = { it.moveTo(MoveSubject.Folder(FOLDER_B), MoveDestination.At(ROOT_ID, FOLDER_A)) },
            ) { fixture ->
                assertEquals(listOf(FOLDER_B, FOLDER_A), fixture.tree.folders.getValue(ROOT_ID).children)
            },
            OperationCase(
                "フォルダを、同じ親の末尾へ",
                act = { it.moveTo(MoveSubject.Folder(FOLDER_A), MoveDestination.At(ROOT_ID, null)) },
            ) { fixture ->
                assertEquals(listOf(FOLDER_B, FOLDER_A), fixture.tree.folders.getValue(ROOT_ID).children)
            },
            OperationCase(
                "フォルダを、ほかのフォルダの子の前へ",
                act = { it.moveTo(MoveSubject.Folder(FOLDER_B), MoveDestination.At(FOLDER_A, CHILD_OF_A)) },
            ) { fixture ->
                assertEquals(listOf(FOLDER_B, CHILD_OF_A), fixture.tree.folders.getValue(FOLDER_A).children)
                assertEquals(listOf(FOLDER_A), fixture.tree.folders.getValue(ROOT_ID).children)
            },
        )
        cases.forEach { (name, act, check) ->
            val fixture = started { it.store.replaceItemsAsOtherWriter(ROOT_ID, fileId(30), FIVE_ITEMS) }

            act(fixture.controller)
            runCurrent()

            assertEquals(WriteOutcome.Written, fixture.state.write.outcome, name)
            assertEquals(fixture.store.storedManifest(), fixture.state.browse.tree?.manifest, name)
            check(fixture)
        }
    }

    // 位置を指定した移動でも、並びが変わらないもの（自分のすぐ前・すぐ後ろ、末尾のものを末尾へ）、ルートフォルダ、
    // 自身と子孫の中、ツリーに無いフォルダへは、何も書かない
    @Test
    fun moveToPositionRefusesUnchangedOrderAndInvalidPlaces() = runTest {
        val cases = listOf(
            "項目を、すぐ後ろの項目の前へ" to (MoveSubject.Items(ROOT_ID, listOf(item(2).id)) to MoveDestination.At(ROOT_ID, item(3).id)),
            "末尾の項目を末尾へ" to (MoveSubject.Items(ROOT_ID, listOf(item(5).id)) to MoveDestination.At(ROOT_ID, null)),
            "フォルダを、すぐ後ろのフォルダの前へ" to (MoveSubject.Folder(FOLDER_A) to MoveDestination.At(ROOT_ID, FOLDER_B)),
            "末尾のフォルダを末尾へ" to (MoveSubject.Folder(FOLDER_B) to MoveDestination.At(ROOT_ID, null)),
            "ルートフォルダ" to (MoveSubject.Folder(ROOT_ID) to MoveDestination.At(FOLDER_A, null)),
            "フォルダを、その子孫の中へ" to (MoveSubject.Folder(FOLDER_A) to MoveDestination.At(CHILD_OF_A, null)),
            "ツリーに無いフォルダ" to (MoveSubject.Folder(FOLDER_B) to MoveDestination.At(folderId(99), null)),
        )
        cases.forEach { (name, case) ->
            val (subject, destination) = case
            val fixture = started { it.store.replaceItemsAsOtherWriter(ROOT_ID, fileId(30), FIVE_ITEMS) }
            val requestsBefore = fixture.store.requests.size

            fixture.controller.moveTo(subject, destination)
            runCurrent()

            assertEquals(requestsBefore, fixture.store.requests.size, name)
            assertFalse(fixture.state.write.writing, name)
            assertNull(fixture.state.write.outcome, name)
        }
    }

    // 操作を受け付けないあいだ（マニフェストを読み直している最中）は、元に戻すことを受け付けずに false を返し、何も書かず、
    // 元に戻せる削除を残す。受け付けるようになれば、元に戻せて true を返す
    @Test
    fun undoIsRefusedWhileNotWritable() = runTest {
        val fixture = started()
        fixture.controller.selectItem(ROOT_ITEMS[0].id, SelectionMode.SINGLE)
        fixture.controller.deleteSelectedItems()
        runCurrent()
        val undoable = assertNotNull(fixture.state.write.undoableDelete)
        val gate = fixture.stopNextGet(fixture.isManifest)
        fixture.controller.reload()
        runCurrent()
        val requestsBefore = fixture.store.requests.size

        assertFalse(fixture.controller.undoDelete())
        runCurrent()

        assertEquals(requestsBefore, fixture.store.requests.size)
        assertEquals(undoable, fixture.state.write.undoableDelete)
        assertFalse(fixture.state.write.writing)

        gate.complete(Unit)
        runCurrent()
        assertTrue(fixture.controller.undoDelete())
        runCurrent()

        assertEquals(ROOT_ITEMS, fixture.storedItems(ROOT_ID))
        assertNull(fixture.state.write.undoableDelete)
    }

    // 直前の操作の結果は、マニフェストを読み直せたら消す。読み直しに失敗したら残す
    @Test
    fun successfulRereadClearsOutcome() = runTest {
        val fixture = started()
        fixture.store.onNextPut(PutFault.Forbidden, key = fixture.isManifest)
        fixture.controller.createFolder(ROOT_ID, "C")
        runCurrent()
        val failed = WriteOutcome.Failed(SyncFailure.Forbidden)
        assertEquals(failed, fixture.state.write.outcome)

        fixture.store.onNextGet(GetFault.Forbidden, key = fixture.isManifest)
        fixture.controller.reload()
        runCurrent()
        assertEquals(failed, fixture.state.write.outcome)

        fixture.controller.reload()
        runCurrent()
        assertNull(fixture.state.write.outcome)
    }
}
