package to.sava.cloudmarksmultiplatform.remote

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.time.Instant

/** 書き込みの手順のテストが前提にする、偽のストレージの条件付きの読み書きが S3 と同じであること。 */
class FakeObjectStoreTest {
    private val key = "cm/data/manifest.jsonl.gz"
    private val body = byteArrayOf(1, 2, 3)

    // If-Match：ETag が一致すれば書いて新しい ETag を返し、違えば 412、オブジェクトが無ければ 404
    @Test
    fun ifMatchWritesOnlyOnMatchingETag() = runTest {
        val store = FakeObjectStore()
        val eTag = store.overwrite(key, body)

        val written = assertIs<PutObjectResult.Success>(store.put(key, byteArrayOf(4), "application/gzip", PutCondition.IfMatch(eTag)))
        val stale = store.put(key, byteArrayOf(5), "application/gzip", PutCondition.IfMatch(eTag))
        val missing = store.put("other", byteArrayOf(6), "application/gzip", PutCondition.IfMatch(eTag))

        assertNotEquals(eTag, written.eTag)
        assertEquals(PutObjectResult.PreconditionFailed, stale)
        assertEquals(PutObjectResult.NotFound, missing)
        assertContentEquals(byteArrayOf(4), store.objectAt(key)?.body)
    }

    // If-None-Match: *：オブジェクトが無ければ書き、あれば 412
    @Test
    fun ifNoneMatchAnyWritesOnlyWhenAbsent() = runTest {
        val store = FakeObjectStore()

        assertIs<PutObjectResult.Success>(store.put(key, body, "application/gzip", PutCondition.IfNoneMatchAny))
        assertEquals(PutObjectResult.PreconditionFailed, store.put(key, byteArrayOf(9), "application/gzip", PutCondition.IfNoneMatchAny))
        assertContentEquals(body, store.objectAt(key)?.body)
    }

    // If-None-Match の GET：ETag が一致すれば 304、違えば中身と ETag、無ければ 404
    @Test
    fun conditionalGet() = runTest {
        val store = FakeObjectStore()
        val eTag = store.overwrite(key, body)

        assertEquals(GetObjectResult.NotModified, store.get(key, ifNoneMatch = eTag))
        val found = assertIs<GetObjectResult.Found>(store.get(key, ifNoneMatch = "\"other\""))
        assertEquals(eTag, found.eTag)
        assertContentEquals(body, found.body)
        assertEquals(GetObjectResult.NotFound, store.get("other"))
    }

    // つながらないあいだは、GET・PUT・一覧・DELETE のどれも結果が不明を返し、何も書き換えない。リクエストは記録する。
    // つながるようになれば、ふつうに扱う
    @Test
    fun unreachableStoreAnswersUnknownWithoutChanges() = runTest {
        val store = FakeObjectStore()
        val eTag = store.overwrite(key, body)
        store.unreachable = true

        assertIs<GetObjectResult.Unknown>(store.get(key))
        assertIs<PutObjectResult.Unknown>(store.put(key, byteArrayOf(4), "application/gzip", PutCondition.IfMatch(eTag)))
        assertIs<ListObjectsResult.Unknown>(store.list("cm/data/"))
        assertIs<DeleteObjectResult.Unknown>(store.delete(key))
        assertContentEquals(body, store.objectAt(key)?.body)
        assertEquals(4, store.requests.size)

        store.unreachable = false

        assertIs<GetObjectResult.Found>(store.get(key))
    }

    // 遅れて届く PUT は、次の PUT より先に、届いた時点の条件で扱われる
    @Test
    fun latePutArrivesBeforeTheNextPut() = runTest {
        val store = FakeObjectStore()
        val eTag = store.overwrite(key, body)
        store.onNextPut(FakeObjectStore.PutFault.ArriveLate)

        val late = store.put(key, byteArrayOf(4), "application/gzip", PutCondition.IfMatch(eTag))
        assertContentEquals(body, store.objectAt(key)?.body)
        val next = store.put(key, byteArrayOf(5), "application/gzip", PutCondition.IfMatch(eTag))

        assertIs<PutObjectResult.Unknown>(late)
        assertEquals(PutObjectResult.PreconditionFailed, next)
        assertContentEquals(byteArrayOf(4), store.objectAt(key)?.body)
    }

    // 一覧：プレフィックスに合うキーだけを、ページに分けて最後まで返す。ページごとにリクエストし、時刻は一覧の応答の時刻
    @Test
    fun listReadsAllPagesOfMatchingKeys() = runTest {
        val store = FakeObjectStore().apply { listPageSize = 2 }
        val old = Instant.parse("2026-11-10T00:00:00Z")
        (1..5).forEach { store.overwrite("a/$it", body, lastModified = old) }
        store.overwrite("b/1", body)

        val listed = assertIs<ListObjectsResult.Listed>(store.list("a/"))

        assertEquals((1..5).map { ListedObject("a/$it", old) }, listed.objects)
        assertEquals(store.serverTime, listed.serverTime)
        assertEquals(3, store.requests.count { it is FakeObjectStore.Request.ListPage })
    }

    // 一覧：ちょうどページの大きさで終わるときも、取りこぼさず、空のページで止まる
    @Test
    fun listEndsAfterExactlyFullLastPage() = runTest {
        val store = FakeObjectStore().apply { listPageSize = 2 }
        (1..4).forEach { store.overwrite("a/$it", body) }

        val listed = assertIs<ListObjectsResult.Listed>(store.list("a/"))

        assertEquals(listOf("a/1", "a/2", "a/3", "a/4"), listed.objects.map { it.key })
        assertEquals(2, store.requests.count { it is FakeObjectStore.Request.ListPage })
    }

    // 一覧：差し込んだ失敗は、一覧全体の失敗として返る（403 と結果が不明のもの）
    @Test
    fun listReturnsInjectedFailures() = runTest {
        val store = FakeObjectStore().apply { overwrite("a/1", body) }

        store.onNextList(FakeObjectStore.ListFault.Forbidden)
        assertEquals(ListObjectsResult.Forbidden, store.list("a/"))
        store.onNextList(FakeObjectStore.ListFault.ServerError)
        assertIs<ListObjectsResult.Unknown>(store.list("a/"))
    }

    // DELETE：消す。無いキーの削除も成功にする
    @Test
    fun deleteRemovesObjectAndTreatsMissingKeyAsSuccess() = runTest {
        val store = FakeObjectStore()
        store.overwrite(key, body)

        assertEquals(DeleteObjectResult.Success, store.delete(key))
        assertEquals(DeleteObjectResult.Success, store.delete(key))
        assertEquals(emptySet(), store.keys)
    }

    private fun listedVersions(result: ListVersionsResult): List<ListedVersion> =
        assertIs<ListVersionsResult.Listed>(result).versions

    // バージョン：置く（PUT と overwrite）たびにバージョンが増え、消す（DELETE と remove）たびに削除マーカーが増える。
    // 一覧は、バージョンが先、削除マーカーが後で、それぞれ新しい順。いちばん新しいものだけがいまのバージョン
    @Test
    fun everyWriteAndDeleteAddsAVersion() = runTest {
        val store = FakeObjectStore()
        val eTag = store.overwrite(key, byteArrayOf(1))
        assertIs<PutObjectResult.Success>(store.put(key, byteArrayOf(2), "application/gzip", PutCondition.IfMatch(eTag)))
        store.delete(key)
        store.remove(key)

        val versions = listedVersions(store.listVersions(key))

        assertEquals(listOf(false, false, true, true), versions.map { it.isDeleteMarker })
        assertEquals(listOf(false, false, true, false), versions.map { it.isLatest })
        assertEquals(4, versions.map { it.versionId }.distinct().size)
        val history = store.versionsOf(key)
        assertEquals(
            history.filter { it.stored != null }.map { it.versionId } + history.filter { it.stored == null }.map { it.versionId },
            versions.map { it.versionId },
        )
    }

    // バージョンの一覧：ページごとに、バージョンを先、削除マーカーを後にする。続きの位置は、並べ替える前のページの最後のエントリ
    @Test
    fun listVersionsOrdersEachPageVersionsBeforeDeleteMarkers() = runTest {
        val store = FakeObjectStore().apply { listPageSize = 3 }
        store.overwrite(key, byteArrayOf(1))
        store.overwrite(key, byteArrayOf(2))
        store.remove(key)
        store.overwrite(key, byteArrayOf(3))
        store.remove(key)
        val (latestMarker, third, middleMarker, second, first) = store.versionsOf(key)

        val versions = listedVersions(store.listVersions(key))

        assertEquals(
            listOf(third, latestMarker, middleMarker, second, first).map { it.versionId },
            versions.map { it.versionId },
        )
        val markers = store.requests.filterIsInstance<FakeObjectStore.Request.ListVersionsPage>().map { it.versionIdMarker }
        assertEquals(listOf(null, middleMarker.versionId), markers)
    }

    // バージョンの一覧：続きの位置のバージョンが履歴から無くなっていたら、先頭から読み直さずエラーにする
    @Test
    fun listVersionsFailsWhenMarkerVersionVanished() = runTest {
        val store = FakeObjectStore().apply { listPageSize = 2 }
        (1..4).forEach { store.overwrite(key, byteArrayOf(it.toByte())) }
        // 1 ページ目は何もせず読み、2 ページ目を読む前に古いバージョンを消す
        store.onNextListVersions(FakeObjectStore.ListFault.RunBefore { })
        store.onNextListVersions(FakeObjectStore.ListFault.RunBefore { store.expireNoncurrentVersions(key) })

        assertFailsWith<IllegalStateException> { store.listVersions(key) }
    }

    // 一覧：先に走らせる処理を差し込めて、その処理のあとでふつうに読む
    @Test
    fun listRunsInjectedActionBeforeReading() = runTest {
        val store = FakeObjectStore().apply { overwrite("a/1", body) }
        store.onNextList(FakeObjectStore.ListFault.RunBefore { store.overwrite("a/2", body) })

        val listed = assertIs<ListObjectsResult.Listed>(store.list("a/"))

        assertEquals(listOf("a/1", "a/2"), listed.objects.map { it.key })
    }

    // バージョンの一覧：キーが一致するものだけ。プレフィックスだけが一致する別のキーは含めない
    @Test
    fun listVersionsReturnsOnlyTheExactKey() = runTest {
        val store = FakeObjectStore()
        store.overwrite(key, body)
        store.overwrite("$key.bak", body)

        assertEquals(1, listedVersions(store.listVersions(key)).size)
    }

    // バージョンの一覧：履歴の無いキーは空
    @Test
    fun listVersionsOfUnknownKeyIsEmpty() = runTest {
        assertEquals(emptyList(), listedVersions(FakeObjectStore().listVersions(key)))
    }

    // バージョンの一覧：時刻は置いたときのもの。同じ時刻でも、新しく置いた順に返す
    @Test
    fun listVersionsKeepsPlacementOrderForEqualTimes() = runTest {
        val store = FakeObjectStore()
        val time = Instant.parse("2026-11-10T00:00:00Z")
        val later = Instant.parse("2026-11-12T00:00:00Z")
        store.overwrite(key, byteArrayOf(1), lastModified = time)
        store.overwrite(key, byteArrayOf(2), lastModified = time)
        store.overwrite(key, byteArrayOf(3), lastModified = later)

        val versions = listedVersions(store.listVersions(key))

        assertEquals(listOf(later, time, time), versions.map { it.lastModified })
        assertEquals(store.versionsOf(key).map { it.versionId }, versions.map { it.versionId })
        assertContentEquals(byteArrayOf(2), store.versionsOf(key)[1].stored?.body)
    }

    // バージョンの一覧：ページに分けて最後まで読む。ページごとにリクエストし、続きは直前のバージョンの ID から読む
    @Test
    fun listVersionsReadsAllPages() = runTest {
        val store = FakeObjectStore().apply { listPageSize = 2 }
        (1..5).forEach { store.overwrite(key, byteArrayOf(it.toByte())) }

        val versions = listedVersions(store.listVersions(key))

        assertEquals(store.versionsOf(key).map { it.versionId }, versions.map { it.versionId })
        val markers = store.requests.filterIsInstance<FakeObjectStore.Request.ListVersionsPage>().map { it.versionIdMarker }
        assertEquals(3, markers.size)
        assertEquals(null, markers[0])
        assertEquals(versions[1].versionId, markers[1])
        assertEquals(versions[3].versionId, markers[2])
    }

    // バージョンの一覧：ちょうどページの大きさで終わるときも、取りこぼさず、余分なページを読まない
    @Test
    fun listVersionsEndsAfterExactlyFullLastPage() = runTest {
        val store = FakeObjectStore().apply { listPageSize = 2 }
        (1..4).forEach { store.overwrite(key, byteArrayOf(it.toByte())) }

        assertEquals(4, listedVersions(store.listVersions(key)).size)
        assertEquals(2, store.requests.count { it is FakeObjectStore.Request.ListVersionsPage })
    }

    // バージョンの GET：古いバージョンの中身を返す。無いバージョンは 404。リクエストは versionId 付きで記録する
    @Test
    fun getVersionReturnsOldBodies() = runTest {
        val store = FakeObjectStore()
        store.overwrite(key, byteArrayOf(1))
        store.overwrite(key, byteArrayOf(2))
        val (current, old) = store.versionsOf(key)

        val oldFound = assertIs<GetObjectResult.Found>(store.getVersion(key, old.versionId))
        val currentFound = assertIs<GetObjectResult.Found>(store.getVersion(key, current.versionId))

        assertContentEquals(byteArrayOf(1), oldFound.body)
        assertEquals(old.stored?.eTag, oldFound.eTag)
        assertContentEquals(byteArrayOf(2), currentFound.body)
        assertEquals(GetObjectResult.NotFound, store.getVersion(key, "no-such-version"))
        assertEquals(GetObjectResult.NotFound, store.getVersion("other", old.versionId))
        assertEquals(
            listOf(FakeObjectStore.Request.GetVersion(key, old.versionId)),
            store.requests.filterIsInstance<FakeObjectStore.Request.GetVersion>().take(1),
        )
    }

    // バージョンの GET：削除マーカーのバージョンは失敗（S3 の 405）。消したあとも古いバージョンは読める
    @Test
    fun getVersionOfDeleteMarkerFailsAndOldVersionsSurviveDelete() = runTest {
        val store = FakeObjectStore()
        store.overwrite(key, byteArrayOf(1))
        store.delete(key)
        val (marker, old) = store.versionsOf(key)

        assertIs<GetObjectResult.Failed>(store.getVersion(key, marker.versionId))
        assertContentEquals(byteArrayOf(1), assertIs<GetObjectResult.Found>(store.getVersion(key, old.versionId)).body)
        assertEquals(GetObjectResult.NotFound, store.get(key))
    }

    // バージョンの GET：差し込んだ事象（403、5xx、先に走らせる処理）に従い、つながらないあいだは結果が不明を返す
    @Test
    fun getVersionFollowsGetFaultsAndUnreachable() = runTest {
        val store = FakeObjectStore()
        store.overwrite(key, body)
        val versionId = store.versionsOf(key).single().versionId
        var ran = false

        store.onNextGet(FakeObjectStore.GetFault.Forbidden)
        assertEquals(GetObjectResult.Forbidden, store.getVersion(key, versionId))
        store.onNextGet(FakeObjectStore.GetFault.ServerError)
        assertIs<GetObjectResult.Unknown>(store.getVersion(key, versionId))
        store.onNextGet(FakeObjectStore.GetFault.RunBefore { ran = true })
        assertIs<GetObjectResult.Found>(store.getVersion(key, versionId))
        assertEquals(true, ran)
        store.unreachable = true
        assertIs<GetObjectResult.Unknown>(store.getVersion(key, versionId))
    }

    // バージョンの一覧：差し込んだ事象（403、5xx、先に走らせる処理）に従い、つながらないあいだは結果が不明を返す
    @Test
    fun listVersionsFollowsFaultsAndUnreachable() = runTest {
        val store = FakeObjectStore().apply { overwrite(key, body) }
        var ran = false

        store.onNextListVersions(FakeObjectStore.ListFault.Forbidden)
        assertEquals(ListVersionsResult.Forbidden, store.listVersions(key))
        store.onNextListVersions(FakeObjectStore.ListFault.ServerError)
        assertIs<ListVersionsResult.Unknown>(store.listVersions(key))
        store.onNextListVersions(FakeObjectStore.ListFault.RunBefore { ran = true })
        assertEquals(1, listedVersions(store.listVersions(key)).size)
        assertEquals(true, ran)
        store.unreachable = true
        assertIs<ListVersionsResult.Unknown>(store.listVersions(key))
    }

    // バージョンの一覧：事象はキーに合うものにだけ使う。オブジェクトの一覧に差し込んだ事象とは別
    @Test
    fun listVersionsFaultsMatchKeyAndAreSeparateFromListFaults() = runTest {
        val store = FakeObjectStore().apply { overwrite(key, body) }

        store.onNextListVersions(FakeObjectStore.ListFault.Forbidden) { it == "other" }
        store.onNextList(FakeObjectStore.ListFault.Forbidden)

        assertEquals(1, listedVersions(store.listVersions(key)).size)
        assertEquals(ListObjectsResult.Forbidden, store.list("cm/"))
        assertEquals(ListVersionsResult.Forbidden, store.listVersions("other"))
    }

    // バージョニングを有効にしていない：履歴を持たず、いまのオブジェクトだけが versionId "null" で見える
    @Test
    fun withoutVersioningOnlyTheCurrentObjectIsVisibleAsNullVersion() = runTest {
        val store = FakeObjectStore().apply { versioning = false }
        store.overwrite(key, byteArrayOf(1))
        store.overwrite(key, byteArrayOf(2))

        val versions = listedVersions(store.listVersions(key))

        assertEquals(listOf("null"), versions.map { it.versionId })
        assertEquals(listOf(true), versions.map { it.isLatest })
        assertEquals(listOf(false), versions.map { it.isDeleteMarker })
        assertContentEquals(byteArrayOf(2), assertIs<GetObjectResult.Found>(store.getVersion(key, "null")).body)
        assertEquals(GetObjectResult.NotFound, store.getVersion(key, "version-1"))
        assertEquals(emptyList(), store.versionsOf(key))
    }

    // バージョニングを有効にしていない：消すと、一覧は空で "null" のバージョンも読めない（削除マーカーは付かない）
    @Test
    fun withoutVersioningDeleteLeavesNothing() = runTest {
        val store = FakeObjectStore().apply { versioning = false }
        store.overwrite(key, body)
        store.delete(key)

        assertEquals(emptyList(), listedVersions(store.listVersions(key)))
        assertEquals(GetObjectResult.NotFound, store.getVersion(key, "null"))
    }

    // ライフサイクルが古いバージョンを消したあと：いまのバージョンだけが残り、古いバージョンの GET は 404
    @Test
    fun expireNoncurrentVersionsKeepsOnlyTheCurrentVersion() = runTest {
        val store = FakeObjectStore()
        store.overwrite(key, byteArrayOf(1))
        store.overwrite(key, byteArrayOf(2))
        store.delete(key)
        val (marker, second, first) = store.versionsOf(key)

        store.expireNoncurrentVersions(key)

        assertEquals(listOf(marker.versionId), listedVersions(store.listVersions(key)).map { it.versionId })
        assertEquals(GetObjectResult.NotFound, store.getVersion(key, first.versionId))
        assertEquals(GetObjectResult.NotFound, store.getVersion(key, second.versionId))
    }
}
