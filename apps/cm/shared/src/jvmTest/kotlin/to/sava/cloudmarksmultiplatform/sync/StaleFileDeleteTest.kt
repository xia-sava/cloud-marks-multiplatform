package to.sava.cloudmarksmultiplatform.sync

import kotlinx.coroutines.test.runTest
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.DeleteFault
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.GetFault
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.ListFault
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.Request
import to.sava.cloudmarksmultiplatform.storage.Manifest
import to.sava.cloudmarksmultiplatform.storage.encodeManifestGzip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

private val BODY = byteArrayOf(1, 2, 3)

/** 猶予を十分に過ぎた経過。 */
private val LONG_AGO = 48.hours

private fun success(
    deleted: Int = 0,
    failed: Int = 0,
    lastFailure: String? = null,
    untouched: Int = 0,
    withinGrace: Int = 0,
) = DeleteStaleFilesResult.Success(deleted, failed, lastFailure, untouched, withinGrace)

/** 古いファイルの削除（`docs/storage-format.md` の「古いファイルの削除」）。 */
class StaleFileDeleteTest {
    /** [age] だけ前に最終更新されたフォルダのファイルを、フォルダ [folderId] の下に置いて、そのキーを返す。 */
    private fun SyncFixture.putFile(folderId: String, fileId: String, age: Duration): String =
        itemsFileKey(folderId, fileId).also { key -> store.overwrite(key, BODY, lastModified = store.serverTime - age) }

    private fun SyncFixture.replaceManifest(manifest: Manifest) {
        store.overwrite(manifestKey, encodeManifestGzip(manifest))
    }

    private fun SyncFixture.exists(key: String): Boolean = store.objectAt(key) != null

    private fun SyncFixture.deleteRequests(): List<Request.Delete> = store.requests.filterIsInstance<Request.Delete>()

    /** [initialManifest] と、それが指す 2 つのフォルダのファイル（根と A）を、[age] だけ前に置く。 */
    private fun SyncFixture.seedReferenced(age: Duration): List<String> {
        store.seed()
        return listOf(putFile(ROOT_ID, ROOT_FILE_ID, age), putFile(FOLDER_A, A_FILE_ID, age))
    }

    // 目録が参照しているファイルは、猶予を過ぎていても消さない
    @Test
    fun keepsReferencedFilesEvenWhenOld() = runTest {
        val fixture = SyncFixture()
        val referenced = fixture.seedReferenced(LONG_AGO)

        val result = fixture.sync.deleteStaleFiles()

        assertEquals(success(), result)
        assertTrue(referenced.all { fixture.exists(it) })
        assertTrue(fixture.deleteRequests().isEmpty())
    }

    // 古いファイルの削除は手元のキャッシュを使わない。手元のフォルダのファイルも消さない
    @Test
    fun doesNotTouchCacheItemsFiles() = runTest {
        val fixture = SyncFixture()
        fixture.seedReferenced(LONG_AGO)

        fixture.sync.deleteStaleFiles()

        assertEquals(emptyList(), fixture.cache.retained)
    }

    // 目録に参照されておらず、猶予を過ぎたファイルは消す。参照されているファイルは残す
    @Test
    fun deletesUnreferencedFilesPastGrace() = runTest {
        val fixture = SyncFixture()
        val referenced = fixture.seedReferenced(LONG_AGO)
        val stale = fixture.putFile(FOLDER_A, fileId(9), 24.hours + 1.seconds)

        val result = fixture.sync.deleteStaleFiles()

        assertEquals(success(deleted = 1), result)
        assertTrue(!fixture.exists(stale))
        assertTrue(referenced.all { fixture.exists(it) })
        assertEquals(listOf(Request.Delete(stale)), fixture.deleteRequests())
    }

    // 猶予の判定：最終更新から 24 時間以内（ちょうど 24 時間を含む）は消さず、24 時間を過ぎたら消す
    @Test
    fun graceBoundaryIsMoreThan24Hours() = runTest {
        val cases = listOf(
            1.hours to false,
            23.hours to false,
            24.hours to false,
            24.hours + 1.seconds to true,
            LONG_AGO to true,
        )

        cases.forEach { (age, deleted) ->
            val fixture = SyncFixture()
            fixture.seedReferenced(LONG_AGO)
            val key = fixture.putFile(FOLDER_B, fileId(9), age)

            val result = fixture.sync.deleteStaleFiles()

            assertEquals(if (deleted) success(deleted = 1) else success(withinGrace = 1), result, "age=$age")
            assertEquals(!deleted, fixture.exists(key), "age=$age")
        }
    }

    // 経過は、一覧の応答の時刻とオブジェクトの最終更新の差で測る。応答の時刻が進めば、同じオブジェクトでも猶予を過ぎる
    @Test
    fun ageIsMeasuredAgainstListResponseTime() = runTest {
        val fixture = SyncFixture()
        fixture.seedReferenced(LONG_AGO)
        val key = fixture.putFile(FOLDER_B, fileId(9), 1.hours)

        assertEquals(success(withinGrace = 1), fixture.sync.deleteStaleFiles())
        fixture.store.serverTime += 24.hours

        assertEquals(success(deleted = 1), fixture.sync.deleteStaleFiles())
        assertTrue(!fixture.exists(key))
    }

    // 形に合わないキーには触らない（folders/ の直下、UUID でない、拡張子が違う、階層が違う、大文字の UUID など）
    @Test
    fun leavesKeysThatDoNotMatchTheShape() = runTest {
        val fixture = SyncFixture()
        fixture.seedReferenced(LONG_AGO)
        val folders = "${fixture.store.prefix}/folders"
        val folder = folderId(5)
        val file = fileId(9)
        val misshapen = listOf(
            "$folders/",
            "$folders/$file.jsonl.gz",
            "$folders/not-a-uuid/$file.jsonl.gz",
            "$folders/$folder/not-a-uuid.jsonl.gz",
            "$folders/${folder.uppercase().replace('0', 'A')}/$file.jsonl.gz",
            "$folders/$folder/$file.jsonl",
            "$folders/$folder/$file.JSONL.GZ",
            "$folders/$folder/$file.jsonl.gz.bak",
            "$folders/$folder/$file.jsonl.gz/",
            "$folders/$folder/$file",
            "$folders/$folder/$file/$file.jsonl.gz",
            "$folders/$folder/$folder/$file.jsonl.gz",
        )
        misshapen.forEach { fixture.store.overwrite(it, BODY, lastModified = fixture.store.serverTime - LONG_AGO) }
        // 一覧のプレフィックスの外（folders/ でなく folders-old/）は、一覧にも出ない
        val outside = "${fixture.store.prefix}/folders-old/$folder/$file.jsonl.gz"
        fixture.store.overwrite(outside, BODY, lastModified = fixture.store.serverTime - LONG_AGO)

        val result = fixture.sync.deleteStaleFiles()

        assertEquals(success(untouched = misshapen.size), result)
        assertTrue(misshapen.all { fixture.exists(it) })
        assertTrue(fixture.exists(outside))
        assertTrue(fixture.deleteRequests().isEmpty())
    }

    // フォルダの ID が別の組：ファイルの ID は参照されているが、別のフォルダの下にあるものは消す
    @Test
    fun deletesFileWhoseIdIsReferencedButUnderAnotherFolder() = runTest {
        val fixture = SyncFixture()
        val referenced = fixture.seedReferenced(LONG_AGO)
        val misplaced = fixture.putFile(FOLDER_B, A_FILE_ID, LONG_AGO)

        val result = fixture.sync.deleteStaleFiles()

        assertEquals(success(deleted = 1), result)
        assertTrue(!fixture.exists(misplaced))
        assertTrue(referenced.all { fixture.exists(it) })
    }

    // 目録に無いフォルダの ID の下のファイルは、目録のどのフォルダとも組にならないので消す
    @Test
    fun deletesFileUnderFolderMissingFromManifest() = runTest {
        val fixture = SyncFixture()
        val referenced = fixture.seedReferenced(LONG_AGO)
        val orphan = fixture.putFile(folderId(77), fileId(9), LONG_AGO)

        val result = fixture.sync.deleteStaleFiles()

        assertEquals(success(deleted = 1), result)
        assertTrue(!fixture.exists(orphan))
        assertTrue(referenced.all { fixture.exists(it) })
    }

    // ページングで最後まで読む：ページの大きさ 2 で 7 キー（4 ページ）。最後のページのキーまで判定して消す
    @Test
    fun readsEveryPageOfTheListing() = runTest {
        val fixture = SyncFixture()
        fixture.store.listPageSize = 2
        val referenced = fixture.seedReferenced(LONG_AGO)
        val stale = (11..15).map { fixture.putFile(FOLDER_B, fileId(it), LONG_AGO) }

        val result = fixture.sync.deleteStaleFiles()

        assertEquals(success(deleted = 5), result)
        assertTrue(stale.none { fixture.exists(it) })
        assertTrue(referenced.all { fixture.exists(it) })
        assertEquals(4, fixture.store.requests.count { it is Request.ListPage })
        assertEquals(stale.sorted(), fixture.deleteRequests().map { it.key }.sorted())
    }

    // 目録が読み書きできるものでないとき（読むだけ・更新が要る・壊れている・無い・GET の失敗）は、一覧も読まず、何も消さない
    @Test
    fun deletesNothingUnlessManifestIsReadable() = runTest {
        val cases: List<Pair<(SyncFixture) -> Unit, SyncFailure>> = listOf(
            { f: SyncFixture -> f.replaceManifest(initialManifest(version = 2, readableBy = 1)) } to
                SyncFailure.ReadOnly(version = 2, readableBy = 1),
            { f: SyncFixture -> f.replaceManifest(initialManifest(version = 2, readableBy = 2)) } to
                SyncFailure.UpdateRequired(version = 2, readableBy = 2),
            { f: SyncFixture -> f.store.remove(f.manifestKey) } to SyncFailure.ManifestNotFound,
            { f: SyncFixture -> f.store.onNextGet(GetFault.ServerError, f.isManifest) } to SyncFailure.Unreachable("HTTP 500"),
        )

        cases.forEach { (arrange, expected) ->
            val fixture = SyncFixture()
            fixture.seedReferenced(LONG_AGO)
            val stale = fixture.putFile(FOLDER_B, fileId(9), LONG_AGO)
            arrange(fixture)

            val result = fixture.sync.deleteStaleFiles()

            assertEquals(DeleteStaleFilesResult.Failed(expected), result, "$expected")
            assertTrue(fixture.exists(stale), "$expected")
            assertTrue(fixture.deleteRequests().isEmpty(), "$expected")
            assertTrue(fixture.store.requests.none { it is Request.ListPage }, "$expected")
        }
    }

    // 目録が壊れているとき（gzip でない）は、壊れているとして返し、何も消さない
    @Test
    fun deletesNothingWhenManifestIsCorrupt() = runTest {
        val fixture = SyncFixture()
        fixture.seedReferenced(LONG_AGO)
        val stale = fixture.putFile(FOLDER_B, fileId(9), LONG_AGO)
        fixture.store.overwrite(fixture.manifestKey, "not gzip".toByteArray())

        val result = fixture.sync.deleteStaleFiles()

        assertIs<SyncFailure.ManifestCorrupt>(assertIs<DeleteStaleFilesResult.Failed>(result).failure)
        assertTrue(fixture.exists(stale))
        assertTrue(fixture.deleteRequests().isEmpty())
    }

    // 目録は手元のキャッシュを使わず、条件を付けずに GET する。キャッシュは替えない。
    // 手元の目録が古く、新しい目録だけが指すファイルを、古い目録を信じて消さない
    @Test
    fun readsManifestWithoutUsingTheCache() = runTest {
        val fixture = SyncFixture()
        fixture.seedReferenced(LONG_AGO)
        fixture.sync.read()
        val cachedETag = assertNotNull(fixture.cache.manifest()).eTag
        val newFileId = fileId(9)
        val newFile = fixture.putFile(FOLDER_A, newFileId, LONG_AGO)
        val oldFile = fixture.itemsFileKey(FOLDER_A, A_FILE_ID)
        val updated = initialManifest().let { manifest ->
            Manifest(
                header = manifest.header.copy(commit = fileId(8)),
                folders = manifest.folders.map { if (it.id == FOLDER_A) it.copy(items = newFileId) else it },
            )
        }
        fixture.store.overwrite(fixture.manifestKey, encodeManifestGzip(updated))
        val requestsBefore = fixture.store.requests.size

        val result = fixture.sync.deleteStaleFiles()

        assertEquals(success(deleted = 1), result)
        assertTrue(fixture.exists(newFile))
        assertTrue(!fixture.exists(oldFile))
        assertEquals(Request.Get(fixture.manifestKey, ifNoneMatch = null), fixture.store.requests[requestsBefore])
        assertEquals(cachedETag, fixture.cache.manifest()?.eTag)
    }

    // 一覧が 403：何も消さず、権限が無い失敗として返す
    @Test
    fun deletesNothingWhenListIsForbidden() = runTest {
        val fixture = SyncFixture()
        fixture.seedReferenced(LONG_AGO)
        val stale = fixture.putFile(FOLDER_B, fileId(9), LONG_AGO)
        fixture.store.onNextList(ListFault.Forbidden)

        val result = fixture.sync.deleteStaleFiles()

        assertEquals(DeleteStaleFilesResult.Failed(SyncFailure.Forbidden), result)
        assertTrue(fixture.exists(stale))
        assertTrue(fixture.deleteRequests().isEmpty())
    }

    // 一覧の結果が分からない（5xx）：何も消さず、つながらない失敗として返す
    @Test
    fun deletesNothingWhenListIsUnknown() = runTest {
        val fixture = SyncFixture()
        fixture.seedReferenced(LONG_AGO)
        val stale = fixture.putFile(FOLDER_B, fileId(9), LONG_AGO)
        fixture.store.onNextList(ListFault.ServerError)

        val result = fixture.sync.deleteStaleFiles()

        assertEquals(DeleteStaleFilesResult.Failed(SyncFailure.Unreachable("HTTP 500")), result)
        assertTrue(fixture.exists(stale))
        assertTrue(fixture.deleteRequests().isEmpty())
    }

    // 1 つの削除が失敗しても残りは続ける。消した数と消せなかった数が合い、最後の失敗の説明を返す
    @Test
    fun continuesAfterIndividualDeleteFailures() = runTest {
        val fixture = SyncFixture()
        fixture.seedReferenced(LONG_AGO)
        val (first, second, third, fourth) = (21..24).map { fixture.putFile(FOLDER_B, fileId(it), LONG_AGO) }
        fixture.store.onNextDelete(DeleteFault.Forbidden) { it == first }
        fixture.store.onNextDelete(DeleteFault.ServerError) { it == third }

        val result = fixture.sync.deleteStaleFiles()

        assertEquals(success(deleted = 2, failed = 2, lastFailure = "HTTP 500"), result)
        assertTrue(fixture.exists(first))
        assertTrue(!fixture.exists(second))
        assertTrue(fixture.exists(third))
        assertTrue(!fixture.exists(fourth))
        assertEquals(listOf(first, second, third, fourth), fixture.deleteRequests().map { it.key })
    }

    // 失敗が無ければ、最後の失敗の説明は無い
    @Test
    fun hasNoLastFailureWhenEveryDeleteSucceeds() = runTest {
        val fixture = SyncFixture()
        fixture.seedReferenced(LONG_AGO)
        fixture.putFile(FOLDER_B, fileId(9), LONG_AGO)

        val result = assertIs<DeleteStaleFilesResult.Success>(fixture.sync.deleteStaleFiles())

        assertNull(result.lastFailure)
        assertEquals(0, result.failed)
    }
}
