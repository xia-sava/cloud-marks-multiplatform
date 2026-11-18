package to.sava.cloudmarksmultiplatform.sync

import kotlinx.coroutines.test.runTest
import to.sava.cloudmarksmultiplatform.operations.toTreeState
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.PutFault
import to.sava.cloudmarksmultiplatform.remote.PutCondition
import to.sava.cloudmarksmultiplatform.storage.Item
import to.sava.cloudmarksmultiplatform.storage.ManifestDecodeResult
import to.sava.cloudmarksmultiplatform.storage.decodeManifestGzip
import to.sava.cloudmarksmultiplatform.storage.truncatedToMilliseconds
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** [WritePlanner] が断った理由。 */
private const val REASON = "断った"

/** ルートフォルダの項目に 1 つ足した並び。 */
private val ROOT_WITH_ADDED = ROOT_ITEMS + item(9)

/**
 * 呼ばれるたびに、[plans] を先頭から 1 つずつ使って書く中身を決める [WritePlanner]。受けたマニフェストを、呼ばれた順に記録する。
 */
private class ScriptedPlanner<R>(vararg plans: (ManifestRead.Usable) -> WritePlan<R>) : WritePlanner<R> {
    private val remaining = ArrayDeque(plans.toList())
    val received = mutableListOf<ManifestRead.Usable>()

    override suspend fun plan(manifest: ManifestRead.Usable, rereadManifest: suspend () -> ManifestRead): WritePlan<R> {
        received += manifest
        return remaining.removeFirst()(manifest)
    }
}

/**
 * [read] のマニフェストをもとに、ルートフォルダの項目を [ROOT_WITH_ADDED] にする書く中身。
 * ヘッダーの版は [version] と [readableBy]、書いたあとのツリーで読み込んであるものとする項目は [loadedItems]。
 */
private fun addToRoot(
    read: ManifestRead.Usable,
    version: Int = read.manifest.header.version,
    readableBy: Int = read.manifest.header.readableBy,
    loadedItems: Map<String, List<Item>> = mapOf(ROOT_ID to ROOT_WITH_ADDED),
): WritePlan.Change =
    WritePlan.Change(
        baseETag = read.eTag,
        version = version,
        readableBy = readableBy,
        files = mapOf(ROOT_ID to itemsFileBody(ROOT_ID, ROOT_WITH_ADDED)),
        foldersOf = { newFileIds ->
            read.manifest.folders.map { entry ->
                newFileIds[entry.id]?.let { fileId -> entry.copy(items = fileId, count = ROOT_WITH_ADDED.size) } ?: entry
            }
        },
        loadedItems = loadedItems,
        results = emptyList(),
    )

/** このフィクスチャの偽のストレージ、ローカルキャッシュ、時計、待ちを使い、[planner] で書く中身を決める書き込みの手順。 */
private fun <R> SyncFixture.procedure(planner: WritePlanner<R>): WriteProcedure<R> =
    WriteProcedure(
        store = store,
        cache = cache,
        reader = TreeReader(store, cache),
        updatedBy = UPDATED_BY,
        clock = FixedClock,
        timeSource = timeSource,
        wait = { duration ->
            waits += duration
            onWait(duration)
        },
        planner = planner,
    )

private fun WriteProcedureResult<*>.finished(): WriteResult = assertIs<WriteProcedureResult.Finished>(this).result

/**
 * 書き込みの手順（[WriteProcedure]）と、手順 2 で書く中身を決める [WritePlanner] との受け渡し。
 * 応答ごとの扱いと成功の判定は、操作の書き込みを通して [OperationWriteTest] で確かめる。
 */
class WriteProcedureTest {
    // 最初に決めた中身で [WritePlanner] が断った：何も PUT せずに、断った理由で返す
    @Test
    fun rejectedPlanIsReturnedWithoutPuts() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        val planner = ScriptedPlanner<String>({ WritePlan.Rejected(REASON) })

        val result = fixture.procedure(planner).run()

        assertEquals(REASON, assertIs<WriteProcedureResult.Rejected<String>>(result).reason)
        assertEquals(emptyList(), fixture.store.puts { true })
    }

    // 1 回目に決めた中身でマニフェストの PUT の結果が不明になり（書き込まれていない）、再適用で決め直すときに [WritePlanner] が断った：
    // 届いたかどうかが分からないマニフェストの PUT が残っているので、断った理由ではなく、書けたかどうかを確かめられなかったとして返す。
    // 失敗の種類は、その PUT の結果が不明だったことによる
    @Test
    fun rejectionWithUnconfirmedManifestPutIsUnconfirmed() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.store.onNextPut(PutFault.UnknownWithoutWrite, key = fixture.isManifest)
        val planner = ScriptedPlanner<String>({ addToRoot(it) }, { WritePlan.Rejected(REASON) })

        val result = fixture.procedure(planner).run()

        assertIs<SyncFailure.Unreachable>(assertIs<WriteResult.Unconfirmed>(result.finished()).failure)
        assertEquals(2, planner.received.size)
        assertEquals(1, fixture.store.puts(fixture.isManifest).size)
        assertEquals(initialManifest(), fixture.store.storedManifest())
    }

    // 1 回目に決めた中身でマニフェストの PUT が 412 になり（ほかの書き手が先に書き換えた）、再適用で決め直すときに [WritePlanner] が断った：
    // マニフェストの ETag が替わり、先の PUT はもう届かないので、断った理由で返す。決め直すときは、書き換えられたマニフェストを受ける
    @Test
    fun rejectionAfterAnotherWriterIsRejected() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        val othersFolder = folderId(9)
        fixture.store.onNextPut(PutFault.RunBefore { fixture.store.addFolderAsOtherWriter(othersFolder) }, key = fixture.isManifest)
        val planner = ScriptedPlanner<String>({ addToRoot(it) }, { WritePlan.Rejected(REASON) })

        val result = fixture.procedure(planner).run()

        assertEquals(REASON, assertIs<WriteProcedureResult.Rejected<String>>(result).reason)
        assertEquals(2, planner.received.size)
        assertTrue(planner.received.last().manifest.folders.any { it.id == othersFolder })
        assertEquals(1, fixture.store.puts(fixture.isManifest).size)
    }

    // 1 回目に決めた中身でマニフェストの PUT の結果が不明になり（書き込まれていない）、再適用で決め直したときに失敗した：
    // 届いたかどうかが分からないマニフェストの PUT が残っているので、その失敗を、書けたかどうかを確かめられなかったとして返す
    @Test
    fun failedPlanWithUnconfirmedManifestPutIsUnconfirmed() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        fixture.store.onNextPut(PutFault.UnknownWithoutWrite, key = fixture.isManifest)
        val planner = ScriptedPlanner<String>({ addToRoot(it) }, { WritePlan.Failed(SyncFailure.Forbidden) })

        val result = fixture.procedure(planner).run()

        assertEquals(WriteResult.Unconfirmed(SyncFailure.Forbidden), result.finished())
    }

    // 書かないと決めた：PUT を 1 回も送らずに、決めたときのツリーと結果で成功とする
    @Test
    fun unchangedPlanDoesNotPut() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        val planner = ScriptedPlanner<String>({ read ->
            WritePlan.Unchanged(LoadedTree(read.manifest, read.eTag, read.manifest.toTreeState(), emptyMap()), emptyList())
        })

        val result = fixture.procedure(planner).run()

        val success = assertIs<WriteResult.Success>(result.finished())
        assertEquals(initialManifest(), success.tree.manifest)
        assertEquals(fixture.store.storedManifestETag(), success.tree.eTag)
        assertEquals(emptyList(), fixture.store.puts { true })
    }

    // 書くマニフェストのヘッダーの version と readableBy は、読んだマニフェストのものではなく、決めた中身のもの。
    // commit は新しく振り、updatedAt は時計の現在をミリ秒に切り詰めたもの、updatedBy は書き手のもの
    @Test
    fun headerTakesVersionAndReadableByFromPlan() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        val planner = ScriptedPlanner<String>({ addToRoot(it, version = 2, readableBy = 1) })

        val result = fixture.procedure(planner).run()

        val success = assertIs<WriteResult.Success>(result.finished())
        val sent = fixture.store.puts(fixture.isManifest).single()
        val header = assertIs<ManifestDecodeResult.ReadOnly>(decodeManifestGzip(sent.body)).manifest.header
        assertEquals(2 to 1, header.version to header.readableBy)
        assertNotEquals(INITIAL_COMMIT, header.commit)
        assertEquals(NOW.truncatedToMilliseconds(), header.updatedAt)
        assertEquals(UPDATED_BY, header.updatedBy)
        assertEquals(header, success.tree.manifest.header)
    }

    // 決めた中身の項目ファイルを、そのままの中身で If-None-Match: * を付けて置き、置いたファイルの ID から作ったフォルダの行で
    // マニフェストを書く
    @Test
    fun placesPlannedFilesAndPointsManifestAtThem() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        lateinit var change: WritePlan.Change
        val planner = ScriptedPlanner<String>({ read -> addToRoot(read).also { change = it } })

        assertIs<WriteResult.Success>(fixture.procedure(planner).run().finished())

        val filePut = fixture.store.puts(fixture.isItemsFileOf(ROOT_ID)).single()
        assertEquals(PutCondition.IfNoneMatchAny, filePut.condition)
        assertContentEquals(change.files.getValue(ROOT_ID), filePut.body)
        val stored = fixture.store.storedManifest()
        assertEquals(change.foldersOf(mapOf(ROOT_ID to fileIdOfKey(filePut.key))), stored.folders)
        assertEquals(ROOT_WITH_ADDED, fixture.store.storedItems(stored, ROOT_ID))
    }

    // 手順 3 の最初の PUT から 1 時間を測る起点は、[WritePlanner] が書く中身を決めたあと、最初の PUT の直前。
    // 中身を決めるあいだに 61 分たっても、手順 1 へ戻らずにマニフェストを送る
    @Test
    fun hourLimitStartsAfterPlanning() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        val planner = ScriptedPlanner<String>({ read ->
            fixture.timeSource += 61.minutes
            addToRoot(read)
        })

        val result = fixture.procedure(planner).run()

        assertIs<WriteResult.Success>(result.finished())
        assertEquals(1, planner.received.size)
        assertEquals(1, fixture.store.puts(fixture.isItemsFileOf(ROOT_ID)).size)
        assertEquals(1, fixture.store.puts(fixture.isManifest).size)
    }

    // マニフェストの PUT の If-Match は、決めた中身の baseETag。中身を決めるあいだにマニフェストを読み直したなら、
    // 手順 1 で読んだものではなく、読み直したものの ETag を使える
    @Test
    fun manifestPutIsConditionedOnBaseETag() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        val stepOneETag = fixture.store.storedManifestETag()
        var rereadETag: String? = null
        val planner = WritePlanner<String> { _, rereadManifest ->
            fixture.store.addFolderAsOtherWriter(folderId(9))
            val reread = assertIs<ManifestRead.Usable>(rereadManifest())
            rereadETag = reread.eTag
            addToRoot(reread)
        }

        assertIs<WriteResult.Success>(fixture.procedure(planner).run().finished())

        val condition = fixture.store.puts(fixture.isManifest).single().condition
        assertEquals(PutCondition.IfMatch(assertNotNull(rereadETag)), condition)
        assertNotEquals(PutCondition.IfMatch(stepOneETag), condition)
    }

    // 書けたら、書いたマニフェストのツリーに、決めた中身の loadedItems を読み込んだものとして返す。
    // 項目ファイルを置いていないフォルダの項目も含み、項目を持たないフォルダは空として入る
    @Test
    fun writtenTreeHasLoadedItems() = runTest {
        val fixture = SyncFixture().apply { store.seed() }
        val loaded = mapOf(ROOT_ID to ROOT_WITH_ADDED, FOLDER_A to A_ITEMS)
        val planner = ScriptedPlanner<String>({ addToRoot(it, loadedItems = loaded) })

        val result = fixture.procedure(planner).run()

        val success = assertIs<WriteResult.Success>(result.finished())
        assertEquals(fixture.store.storedManifest(), success.tree.manifest)
        assertEquals(fixture.store.storedManifestETag(), success.tree.eTag)
        assertEquals(loaded + (FOLDER_B to emptyList()), success.tree.state.items)
        assertEquals(emptyMap(), success.tree.brokenFolders)
    }
}
