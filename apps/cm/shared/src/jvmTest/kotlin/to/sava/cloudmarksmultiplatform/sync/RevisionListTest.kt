package to.sava.cloudmarksmultiplatform.sync

import kotlinx.coroutines.test.runTest
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.ListFault
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.Request
import to.sava.cloudmarksmultiplatform.remote.ListVersionsResult
import to.sava.cloudmarksmultiplatform.remote.ListedVersion
import to.sava.cloudmarksmultiplatform.remote.ObjectStore
import to.sava.cloudmarksmultiplatform.storage.encodeManifestGzip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

private val T1 = Instant.parse("2026-11-10T00:00:00Z")
private val T2 = Instant.parse("2026-11-11T00:00:00Z")
private val T3 = Instant.parse("2026-11-12T00:00:00Z")
private val T4 = Instant.parse("2026-11-13T00:00:00Z")

/** 戻せる時点の一覧（[StorageSync.listRevisions]）。 */
class RevisionListTest {
    /** マニフェストのバージョンを、`LastModified` を [lastModified] にして 1 つ置き、その versionId を返す。 */
    private fun SyncFixture.putManifestVersion(lastModified: Instant): String {
        store.overwrite(manifestKey, encodeManifestGzip(initialManifest()), lastModified)
        return store.versionsOf(manifestKey).first().versionId
    }

    // いまのバージョンと削除マーカーを除き、LastModified の新しい順に返す
    @Test
    fun listsOlderVersionsNewestFirst() = runTest {
        val fixture = SyncFixture()
        val first = fixture.putManifestVersion(T1)
        val second = fixture.putManifestVersion(T2)
        fixture.store.serverTime = T3
        fixture.store.remove(fixture.manifestKey)
        fixture.putManifestVersion(T4)

        val result = fixture.sync.listRevisions()

        assertEquals(RevisionListResult.Listed(listOf(Revision(second, T2), Revision(first, T1))), result)
    }

    // 並びは S3 の返した順ではなく LastModified の新しい順。LastModified が同じものは、S3 の返した順（新しく置いた順）のまま
    @Test
    fun sortsByLastModifiedAndKeepsOrderOfTies() = runTest {
        val fixture = SyncFixture()
        val first = fixture.putManifestVersion(T2)
        val second = fixture.putManifestVersion(T1)
        val third = fixture.putManifestVersion(T1)
        fixture.putManifestVersion(T3)

        val result = fixture.sync.listRevisions()

        assertEquals(RevisionListResult.Listed(listOf(Revision(first, T2), Revision(third, T1), Revision(second, T1))), result)
    }

    // いまのバージョンしか無ければ、空
    @Test
    fun emptyWhenOnlyCurrentVersionExists() = runTest {
        val fixture = SyncFixture().apply { store.seed() }

        assertEquals(RevisionListResult.Listed(emptyList()), fixture.sync.listRevisions())
    }

    // バージョニングを有効にしていないバケットでは、いまのオブジェクトしか返らないので、空
    @Test
    fun emptyWithoutVersioning() = runTest {
        val fixture = SyncFixture()
        fixture.store.versioning = false
        fixture.store.overwrite(fixture.manifestKey, encodeManifestGzip(initialManifest()), T1)
        fixture.store.overwrite(fixture.manifestKey, encodeManifestGzip(initialManifest()), T2)

        assertEquals(RevisionListResult.Listed(emptyList()), fixture.sync.listRevisions())
    }

    // マニフェストのキーで一覧し、ページングで最後まで読む：ページの大きさ 2 で 5 つのバージョン（3 ページ）
    @Test
    fun readsEveryPageOfTheManifestVersions() = runTest {
        val fixture = SyncFixture()
        fixture.store.listPageSize = 2
        val times = (1..5).map { Instant.parse("2026-11-0${it}T00:00:00Z") }
        val versionIds = times.map { fixture.putManifestVersion(it) }

        val result = fixture.sync.listRevisions()

        val expected = versionIds.zip(times).dropLast(1).map { (versionId, time) -> Revision(versionId, time) }.reversed()
        assertEquals(RevisionListResult.Listed(expected), result)
        val pages = fixture.store.requests.filterIsInstance<Request.ListVersionsPage>()
        assertEquals(3, pages.size)
        assertEquals(setOf(fixture.manifestKey), pages.map { it.key }.toSet())
    }

    // 一覧が 403 なら権限が無い失敗、結果が不明（5xx）ならつながらない失敗
    @Test
    fun listFailuresAreSyncFailures() = runTest {
        val cases = listOf(
            ListFault.Forbidden to SyncFailure.Forbidden,
            ListFault.ServerError to SyncFailure.Unreachable("HTTP 500"),
        )

        cases.forEach { (fault, expected) ->
            val fixture = SyncFixture().apply { store.seed() }
            fixture.store.onNextListVersions(fault)

            assertEquals(RevisionListResult.Failed(expected), fixture.sync.listRevisions(), "$fault")
        }
    }

    // 同じ versionId のバージョンが重なって返っても、1 つにする
    @Test
    fun duplicateVersionIdsAreListedOnce() = runTest {
        val fake = FakeObjectStore()
        val older = ListedVersion("older", T1, isLatest = false, isDeleteMarker = false)
        val newer = ListedVersion("newer", T2, isLatest = false, isDeleteMarker = false)
        val store = object : ObjectStore by fake {
            override suspend fun listVersions(key: String): ListVersionsResult =
                ListVersionsResult.Listed(listOf(newer, older, newer))
        }

        val result = RevisionReader(store).list()

        assertEquals(RevisionListResult.Listed(listOf(Revision("newer", T2), Revision("older", T1))), result)
    }
}
