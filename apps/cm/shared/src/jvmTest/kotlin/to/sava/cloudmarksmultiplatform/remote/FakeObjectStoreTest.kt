package to.sava.cloudmarksmultiplatform.remote

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.time.Instant

/** 書き込みの手順の試験が頼る、偽のストレージの条件付きの読み書きが S3 と同じであること。 */
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

    // 一覧：プレフィックスに合うキーだけを、ページに分けて最後まで返す。ページごとに要求し、時刻は一覧の応答の時刻
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

    // 一覧：差し込んだ失敗は、一覧全体の失敗として返る（403 と結果が分からないもの）
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
}
