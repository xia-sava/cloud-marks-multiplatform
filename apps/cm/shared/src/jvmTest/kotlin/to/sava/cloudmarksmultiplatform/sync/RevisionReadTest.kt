package to.sava.cloudmarksmultiplatform.sync

import kotlinx.coroutines.test.runTest
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.GetFault
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.Request
import to.sava.cloudmarksmultiplatform.storage.encodeManifestGzip
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** 戻せる時点のマニフェストの読み込み（[StorageSync.readRevision]）。 */
class RevisionReadTest {
    /** [initialManifest] を置いてから、ほかの書き手としてマニフェストを替え、[initialManifest] のバージョンを返す。 */
    private fun SyncFixture.seedWithOlderRevision(): Revision {
        store.seed()
        val revision = currentManifestRevision()
        store.addFolderAsOtherWriter(folderId(9))
        return revision
    }

    /** マニフェストのバージョンを [body] で 1 つ置いてから、ほかの書き手としてマニフェストを替え、[body] のバージョンを返す。 */
    private fun SyncFixture.olderRevisionOf(body: ByteArray): Revision {
        store.overwrite(manifestKey, body)
        val revision = currentManifestRevision()
        store.overwrite(manifestKey, encodeManifestGzip(initialManifest()))
        return revision
    }

    // そのバージョンを versionId を付けて GET して読む。フォルダと項目の数、保存したクライアントの種類が取れる
    @Test
    fun readsManifestOfTheVersion() = runTest {
        val fixture = SyncFixture()
        val revision = fixture.seedWithOlderRevision()
        val requestsBefore = fixture.store.requests.size

        val result = fixture.sync.readRevision(revision)

        val loaded = assertIs<RevisionReadResult.Loaded>(result).revision
        assertEquals(LoadedRevision(revision, initialManifest()), loaded)
        assertEquals(ManifestCounts(folders = 2, items = ROOT_ITEMS.size + A_ITEMS.size), loaded.manifest.counts())
        assertEquals("android", loaded.manifest.header.updatedBy)
        assertEquals(
            listOf(Request.GetVersion(fixture.manifestKey, revision.versionId)),
            fixture.store.requests.drop(requestsBefore),
        )
    }

    // そのバージョンのマニフェストが壊れている：壊れているとして返す
    @Test
    fun corruptVersionIsCorrupt() = runTest {
        val fixture = SyncFixture()
        val revision = fixture.olderRevisionOf("not gzip".toByteArray())

        val result = fixture.sync.readRevision(revision)

        assertIs<RevisionFailure.Corrupt>(assertIs<RevisionReadResult.Failed>(result).failure)
    }

    // そのバージョンの readableBy が新しい、または version だけが新しい（読むだけにするもの）：どちらも更新が要るとして返す
    @Test
    fun newerFormatIsUpdateRequired() = runTest {
        listOf(2 to 2, 2 to 1).forEach { (version, readableBy) ->
            val fixture = SyncFixture()
            val revision = fixture.olderRevisionOf(encodeManifestGzip(initialManifest(version, readableBy)))

            val result = fixture.sync.readRevision(revision)

            assertEquals(RevisionReadResult.Failed(RevisionFailure.UpdateRequired), result, "version=$version readableBy=$readableBy")
        }
    }

    // そのバージョンがライフサイクルで消えている（404）：残っていないとして返す
    @Test
    fun expiredVersionIsIncomplete() = runTest {
        val fixture = SyncFixture()
        val revision = fixture.seedWithOlderRevision()
        fixture.store.expireNoncurrentVersions(fixture.manifestKey)

        val result = fixture.sync.readRevision(revision)

        assertEquals(RevisionReadResult.Failed(RevisionFailure.Incomplete), result)
    }

    // GET が 403 なら権限が無い失敗、結果が不明（5xx）ならつながらない失敗として返す
    @Test
    fun getFailuresAreSyncFailures() = runTest {
        val cases = listOf(
            GetFault.Forbidden to SyncFailure.Forbidden,
            GetFault.ServerError to SyncFailure.Unreachable("HTTP 500"),
        )

        cases.forEach { (fault, expected) ->
            val fixture = SyncFixture()
            val revision = fixture.seedWithOlderRevision()
            fixture.store.onNextGet(fault, key = fixture.isManifest)

            val result = fixture.sync.readRevision(revision)

            assertEquals(RevisionReadResult.Failed(RevisionFailure.Sync(expected)), result, "$fault")
        }
    }

    // 読んだ古いバージョンのマニフェストを、ローカルキャッシュに持たない。ローカルキャッシュのマニフェストも項目ファイルも替わらない
    @Test
    fun doesNotTouchTheCache() = runTest {
        val fixture = SyncFixture()
        val revision = fixture.seedWithOlderRevision()
        fixture.sync.read { setOf(ROOT_ID, FOLDER_A) }
        val cached = assertNotNull(fixture.cache.manifest())
        val retainedBefore = fixture.cache.retained.toList()

        assertIs<RevisionReadResult.Loaded>(fixture.sync.readRevision(revision))

        val after = assertNotNull(fixture.cache.manifest())
        assertEquals(cached.eTag, after.eTag)
        assertContentEquals(cached.body, after.body)
        assertEquals(retainedBefore, fixture.cache.retained)
        assertNotNull(fixture.cache.itemsFile(fixture.itemsFileKey(ROOT_ID, ROOT_FILE_ID)))
    }
}
