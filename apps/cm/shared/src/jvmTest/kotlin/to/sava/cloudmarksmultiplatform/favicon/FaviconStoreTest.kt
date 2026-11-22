package to.sava.cloudmarksmultiplatform.favicon

import androidx.compose.ui.graphics.ImageBitmap
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.app.TestClock
import java.nio.file.Files
import java.nio.file.Path
import java.security.cert.CertificateException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** 端末の時計が返す、いまの時刻。 */
private val NOW: Instant = Instant.parse("2026-11-16T12:00:00Z")

/** 項目の URL。オリジンは [EXAMPLE_ORIGIN]。 */
private const val ITEM_URL = "https://example.com/articles/1?ref=top#section"

/** [ITEM_URL] と同じオリジンの、別の項目の URL。 */
private const val SAME_ORIGIN_ITEM_URL = "HTTPS://Example.COM:443/articles/2"

/** 前に取った画像の `ETag`。 */
private const val PREVIOUS_ETAG = "\"v1\""

/** 前に取った画像の `Last-Modified`。 */
private const val PREVIOUS_LAST_MODIFIED = "Mon, 01 Jan 2024 00:00:00 GMT"

/** 前に取った画像の URL。 */
private const val PREVIOUS_IMAGE_URL = "https://example.com/static/icon.png"

/** 同時に取りに行くオリジンの数の上限（4）を超える数。 */
private const val MORE_THAN_CONCURRENT_FETCHES = 6

/** 前に取った、[PREVIOUS_IMAGE_URL] の画像 `previous` のキャッシュ。期限は [expiresAt]。 */
private fun previousImage(expiresAt: Instant, eTag: String? = PREVIOUS_ETAG, lastModified: String? = PREVIOUS_LAST_MODIFIED) =
    CachedFavicon(
        fetchedAt = NOW - FOUND_LIFETIME,
        expiresAt = expiresAt,
        image = CachedImage(PREVIOUS_IMAGE_URL, eTag, lastModified, testImageBytes("previous")),
    )

/**
 * 一時ディレクトリのキャッシュと、偽のサイト [web] と、時計 [clock] を使うストア。取得は [scope] の `backgroundScope` で、
 * テストのディスパッチャで走らせるので、`runCurrent` で進める。
 */
private class StoreFixture(
    scope: TestScope,
    val cacheDirectory: Path,
    val web: FakeWeb,
    val clock: TestClock = TestClock(NOW),
    decode: (ByteArray) -> ImageBitmap? = ::decodeTestImage,
) {
    val logs: MutableList<String> = CopyOnWriteArrayList()

    private val dispatcher = StandardTestDispatcher(scope.testScheduler)

    val client: HttpClient = web.client(dispatcher)

    val store = FaviconStore(cacheDirectory, client, scope.backgroundScope, logs::add, clock, dispatcher, decode)

    /** キャッシュのファイルを読む口。ストアとは別に作る。 */
    val cache = FaviconCache(cacheDirectory) {}
}

// runCurrent が ExperimentalCoroutinesApi であるため。
@OptIn(ExperimentalCoroutinesApi::class)
class FaviconStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val cacheDirectory: Path by lazy { temporaryFolder.root.toPath().resolve("cache") }

    private fun TestScope.fixture(web: FakeWeb = FakeWeb(), clock: TestClock = TestClock(NOW)) =
        StoreFixture(this, cacheDirectory, web, clock)

    // http・https でない URL と、ホストの無い URL、解釈できない URL は、取りに行かずにすぐ Missing にする
    @Test
    fun nonWebUrlIsMissingWithoutRequest() = runTest {
        val fixture = fixture()
        listOf("ftp://example.com/file", "javascript:alert(1)", "about:blank", "file:///C:/index.html", "https:///path", "not a url", "")
            .forEach { url ->
                assertEquals(Favicon.Missing, fixture.store.faviconOf(url).value, url)
            }
        runCurrent()

        assertEquals(emptyList(), fixture.web.requests)
    }

    // 初めて faviconOf を呼んだオリジンは、取りに行っているあいだ Loading で、取れたら Found にし、キャッシュに書く
    @Test
    fun fetchesAndCaches() = runTest {
        val fixture = fixture(FakeWeb().apply { image(EXAMPLE_FAVICON_ICO, "ico") })

        val state = fixture.store.faviconOf(ITEM_URL)
        assertEquals(Favicon.Loading, state.value)
        runCurrent()

        assertEquals(Favicon.Found(testImage("ico")), state.value)
        val cached = assertNotNull(fixture.cache.read(EXAMPLE_ORIGIN))
        assertEquals(NOW, cached.fetchedAt)
        assertEquals(NOW + FOUND_LIFETIME, cached.expiresAt)
        assertEquals(EXAMPLE_FAVICON_ICO, cached.image?.url)
        assertContentEquals(testImageBytes("ico"), cached.image?.bytes)
    }

    // 古くないキャッシュがあれば、取りに行かずにその画像を出す（次のプロセスでも）
    @Test
    fun usesFreshCacheWithoutRequest() = runTest {
        fixture(FakeWeb().apply { image(EXAMPLE_FAVICON_ICO, "ico") }).store.faviconOf(ITEM_URL)
        runCurrent()
        val next = fixture(clock = TestClock(NOW + FOUND_LIFETIME - 1.milliseconds))

        val state = next.store.faviconOf(ITEM_URL)
        runCurrent()

        assertEquals(Favicon.Found(testImage("ico")), state.value)
        assertEquals(emptyList(), next.web.requests)
    }

    // 同じオリジンには同じ StateFlow を返し、取りに行っている最中に faviconOf を呼んでも、重ねて取りに行かない
    @Test
    fun doesNotFetchSameOriginTwice() = runTest {
        val gate = CompletableDeferred<Unit>()
        val web = FakeWeb().apply {
            on(EXAMPLE_FAVICON_ICO) {
                gate.await()
                respond(content = testImageBytes("ico"), status = HttpStatusCode.OK)
            }
        }
        val fixture = fixture(web)

        val first = fixture.store.faviconOf(ITEM_URL)
        runCurrent()
        val second = fixture.store.faviconOf(SAME_ORIGIN_ITEM_URL)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        val third = fixture.store.faviconOf(ITEM_URL)
        runCurrent()

        assertSame(first, second)
        assertSame(first, third)
        assertEquals(listOf(EXAMPLE_FAVICON_ICO), web.requestedUrls)
        assertEquals(Favicon.Found(testImage("ico")), first.value)
    }

    // 同時に取りに行くのは 4 オリジンまで。残りは、先のものが終わってから取りに行く
    @Test
    fun limitsConcurrentFetches() = runTest {
        val gate = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val web = FakeWeb(otherwise = {
            maxActive.accumulateAndGet(active.incrementAndGet()) { previous, current -> maxOf(previous, current) }
            try {
                gate.await()
            } finally {
                active.decrementAndGet()
            }
            respond(content = "", status = HttpStatusCode.NotFound)
        })
        val fixture = fixture(web)
        val hosts = (1..MORE_THAN_CONCURRENT_FETCHES).map { "site$it.example" }

        val states = hosts.map { fixture.store.faviconOf("https://$it/") }
        runCurrent()

        assertEquals(4, active.get())
        gate.complete(Unit)
        runCurrent()
        assertEquals(4, maxActive.get())
        assertTrue(states.all { it.value == Favicon.Missing }, states.map { it.value }.toString())
        assertEquals(hosts.map { "https://$it/favicon.ico" }.toSet(), web.requestedUrls.filter { it.endsWith("/favicon.ico") }.toSet())
    }

    // どの取り先からも応答が得られなければ、キャッシュに書かず Missing にし、5 分たつまでは取り直さない。たってから faviconOf を呼ぶと取り直す
    @Test
    fun offlineDoesNotCacheAndRetriesLater() = runTest {
        val web = FakeWeb(otherwise = connectionRefused)
        val fixture = fixture(web)

        val state = fixture.store.faviconOf(ITEM_URL)
        runCurrent()
        val requested = web.requests.size
        assertEquals(Favicon.Missing, state.value)
        assertFalse(fixture.cache.fileOf(EXAMPLE_ORIGIN).exists())

        fixture.clock.current = NOW + RETRY_AFTER_FAILURE - 1.milliseconds
        fixture.store.faviconOf(ITEM_URL)
        runCurrent()
        assertEquals(requested, web.requests.size)

        web.image(EXAMPLE_FAVICON_ICO, "ico")
        fixture.clock.current = NOW + RETRY_AFTER_FAILURE
        fixture.store.faviconOf(ITEM_URL)
        runCurrent()
        assertEquals(Favicon.Found(testImage("ico")), state.value)
        assertEquals(emptyList(), fixture.logs)
    }

    // 要求の例外は種類を問わず、その取り先から応答が得られなかったものとして次の取り先へ進み、ログに書かない
    @Test
    fun requestFailuresAreNotLogged() = runTest {
        listOf<() -> Exception>(
            { CertificateException("untrusted") },
            { IllegalStateException("handshake") },
            { NumberFormatException("bad chunk size") },
        ).forEach { failure ->
            val web = FakeWeb(otherwise = failingWith(failure)).apply { image(EXAMPLE_GOOGLE, "google") }
            val fixture = StoreFixture(this, temporaryFolder.newFolder().toPath(), web)

            val state = fixture.store.faviconOf(ITEM_URL)
            runCurrent()

            assertEquals(Favicon.Found(testImage("google")), state.value, failure().toString())
            assertEquals(emptyList(), fixture.logs, failure().toString())
        }
    }

    // 1 つのオリジンにかける時間の上限（10 秒）は、取りに行き始めてから数え、ほかのオリジンが終わるのを待つ時間を含まない
    @Test
    fun timeLimitExcludesWaitingForTurn() = runTest {
        val slowHosts = (1..4).map { "slow$it.example" }
        val web = FakeWeb(otherwise = {
            delay(FETCH_TIME_LIMIT * 2)
            respond(content = "", status = HttpStatusCode.NotFound)
        }).apply {
            on("https://late.example/favicon.ico") {
                delay(FETCH_TIME_LIMIT - 1.seconds)
                respond(content = testImageBytes("late"), status = HttpStatusCode.OK)
            }
        }
        val fixture = fixture(web)
        val slow = slowHosts.map { fixture.store.faviconOf("https://$it/") }
        val late = fixture.store.faviconOf("https://late.example/")
        runCurrent()

        advanceTimeBy(FETCH_TIME_LIMIT * 2)
        runCurrent()

        assertTrue(slow.all { it.value == Favicon.Missing }, slow.map { it.value }.toString())
        assertEquals(Favicon.Found(testImage("late")), late.value)
    }

    // キャッシュの画像をオリジンの外の取り先から取っていたなら、古くなったときは問い合わせずに /favicon.ico から取り直す
    @Test
    fun externalSourceImageIsRefetchedInOrder() = runTest {
        val web = FakeWeb().apply { image(EXAMPLE_FAVICON_ICO, "site") }
        val fixture = fixture(web)
        val fromHatena = CachedImage(EXAMPLE_HATENA_HTTPS, PREVIOUS_ETAG, PREVIOUS_LAST_MODIFIED, testImageBytes("previous"))
        fixture.cache.write(EXAMPLE_ORIGIN, CachedFavicon(NOW - FOUND_LIFETIME, NOW, fromHatena))

        val state = fixture.store.faviconOf(ITEM_URL)
        runCurrent()

        assertEquals(listOf(EXAMPLE_FAVICON_ICO), web.requestedUrls)
        assertNull(web.requests.single().headers[HttpHeaders.IfNoneMatch])
        assertEquals(Favicon.Found(testImage("site")), state.value)
        assertEquals(EXAMPLE_FAVICON_ICO, fixture.cache.read(EXAMPLE_ORIGIN)?.image?.url)
    }

    // 304 のとき、キャッシュのファイルの画像を取った URL が問い合わせた URL と違えば、ファイルを書き直さない
    @Test
    fun notModifiedDoesNotRewriteOtherImage() = runTest {
        val gate = CompletableDeferred<Unit>()
        val web = FakeWeb().apply {
            on(PREVIOUS_IMAGE_URL) {
                gate.await()
                respond(content = "", status = HttpStatusCode.NotModified)
            }
        }
        val fixture = fixture(web)
        fixture.cache.write(EXAMPLE_ORIGIN, previousImage(expiresAt = NOW))
        fixture.store.faviconOf(ITEM_URL)
        runCurrent()
        val other = CachedImage("https://example.com/other.png", null, null, testImageBytes("other"))
        fixture.cache.write(EXAMPLE_ORIGIN, CachedFavicon(NOW, NOW + FOUND_LIFETIME, other))
        val written = Files.readAllBytes(fixture.cache.fileOf(EXAMPLE_ORIGIN))

        gate.complete(Unit)
        runCurrent()

        assertContentEquals(written, Files.readAllBytes(fixture.cache.fileOf(EXAMPLE_ORIGIN)))
    }

    // 304 のとき、キャッシュのファイルが無くなっていれば書き直さず、画像はそのまま出す
    @Test
    fun notModifiedWithoutCacheFileKeepsImage() = runTest {
        val gate = CompletableDeferred<Unit>()
        val web = FakeWeb().apply {
            on(PREVIOUS_IMAGE_URL) {
                gate.await()
                respond(content = "", status = HttpStatusCode.NotModified)
            }
        }
        val fixture = fixture(web)
        fixture.cache.write(EXAMPLE_ORIGIN, previousImage(expiresAt = NOW))
        val state = fixture.store.faviconOf(ITEM_URL)
        runCurrent()

        Files.delete(fixture.cache.fileOf(EXAMPLE_ORIGIN))
        gate.complete(Unit)
        runCurrent()

        assertEquals(Favicon.Found(testImage("previous")), state.value)
        assertFalse(fixture.cache.fileOf(EXAMPLE_ORIGIN).exists())
        assertEquals(emptyList(), fixture.logs)
    }

    // オフラインで古いキャッシュがあれば、その画像を出したままにし、キャッシュを書き換えない
    @Test
    fun offlineKeepsStaleCache() = runTest {
        val fixture = fixture(FakeWeb(otherwise = connectionRefused))
        val stale = previousImage(expiresAt = NOW - 1.hours)
        fixture.cache.write(EXAMPLE_ORIGIN, stale)
        val written = Files.readAllBytes(fixture.cache.fileOf(EXAMPLE_ORIGIN))

        val state = fixture.store.faviconOf(ITEM_URL)
        runCurrent()

        assertEquals(Favicon.Found(testImage("previous")), state.value)
        assertContentEquals(written, Files.readAllBytes(fixture.cache.fileOf(EXAMPLE_ORIGIN)))
        assertTrue(fixture.web.requests.isNotEmpty())
    }

    // 応答は得られたが取れなかったら、無しとしてキャッシュに書く。無しは 1 日たつまで取り直さない
    @Test
    fun missingIsCachedForOneDay() = runTest {
        val first = fixture()
        val state = first.store.faviconOf(ITEM_URL)
        runCurrent()

        assertEquals(Favicon.Missing, state.value)
        val cached = assertNotNull(first.cache.read(EXAMPLE_ORIGIN))
        assertNull(cached.image)
        assertEquals(NOW + MISSING_LIFETIME, cached.expiresAt)

        val withinDay = fixture(clock = TestClock(NOW + MISSING_LIFETIME - 1.milliseconds))
        assertEquals(Favicon.Missing, withinDay.store.faviconOf(ITEM_URL).also { runCurrent() }.value)
        assertEquals(emptyList(), withinDay.web.requests)

        val afterDay = fixture(FakeWeb().apply { image(EXAMPLE_FAVICON_ICO, "ico") }, TestClock(NOW + MISSING_LIFETIME))
        val refetched = afterDay.store.faviconOf(ITEM_URL)
        runCurrent()
        assertEquals(Favicon.Found(testImage("ico")), refetched.value)
    }

    // 取れたものは 30 日たつまで取り直さず、たったら次に faviconOf を呼んだときに取り直す
    @Test
    fun foundIsCachedForThirtyDays() = runTest {
        val fixture = fixture(FakeWeb().apply { image(EXAMPLE_FAVICON_ICO, "ico") })
        fixture.store.faviconOf(ITEM_URL)
        runCurrent()

        fixture.clock.current = NOW + FOUND_LIFETIME - 1.milliseconds
        fixture.store.faviconOf(ITEM_URL)
        runCurrent()
        assertEquals(1, fixture.web.requests.size)

        fixture.clock.current = NOW + FOUND_LIFETIME
        fixture.store.faviconOf(ITEM_URL)
        runCurrent()
        assertEquals(2, fixture.web.requests.size)
    }

    // 画像の応答の max-age が 30 日より長ければ、その長さだけ取り直さない
    @Test
    fun longerMaxAgeExtendsExpiry() = runTest {
        val sixtyDays = 60.days
        val web = FakeWeb().apply {
            image(EXAMPLE_FAVICON_ICO, "ico", headersOf(HttpHeaders.CacheControl, "max-age=${sixtyDays.inWholeSeconds}"))
        }
        val fixture = fixture(web)
        fixture.store.faviconOf(ITEM_URL)
        runCurrent()

        assertEquals(NOW + sixtyDays, fixture.cache.read(EXAMPLE_ORIGIN)?.expiresAt)
        fixture.clock.current = NOW + sixtyDays - 1.milliseconds
        fixture.store.faviconOf(ITEM_URL)
        runCurrent()
        assertEquals(1, web.requests.size)
    }

    // 古いキャッシュがあるあいだは、取り直しが済むまで古い画像を出し、取り直したら新しい画像に替える
    @Test
    fun showsStaleImageWhileRefetching() = runTest {
        val gate = CompletableDeferred<Unit>()
        val web = FakeWeb().apply {
            on(PREVIOUS_IMAGE_URL) {
                gate.await()
                respond(content = testImageBytes("new"), status = HttpStatusCode.OK)
            }
        }
        val fixture = fixture(web)
        fixture.cache.write(EXAMPLE_ORIGIN, previousImage(expiresAt = NOW))

        val state = fixture.store.faviconOf(ITEM_URL)
        runCurrent()
        assertEquals(Favicon.Found(testImage("previous")), state.value)

        gate.complete(Unit)
        runCurrent()
        assertEquals(Favicon.Found(testImage("new")), state.value)
        val cached = assertNotNull(fixture.cache.read(EXAMPLE_ORIGIN))
        assertContentEquals(testImageBytes("new"), cached.image?.bytes)
        assertEquals(NOW, cached.fetchedAt)
    }

    // 取り直しで取れなかったら、古い画像を無しで置き換える
    @Test
    fun staleImageIsReplacedByMissing() = runTest {
        val fixture = fixture()
        fixture.cache.write(EXAMPLE_ORIGIN, previousImage(expiresAt = NOW))

        val state = fixture.store.faviconOf(ITEM_URL)
        runCurrent()

        assertEquals(Favicon.Missing, state.value)
        assertNull(fixture.cache.read(EXAMPLE_ORIGIN)?.image)
    }

    // 古い画像を取り直すときは、前に取った URL へ ETag と Last-Modified を付けて問い合わせる。304 なら画像はそのままにし、
    // 期限を延ばして書き直す。304 で来た ETag に差し替え、来なかった Last-Modified は前のままにする
    @Test
    fun notModifiedExtendsExpiry() = runTest {
        val web = FakeWeb().apply {
            on(PREVIOUS_IMAGE_URL) {
                respond(
                    content = "",
                    status = HttpStatusCode.NotModified,
                    headers = headersOf(HttpHeaders.ETag to listOf("\"v2\""), HttpHeaders.CacheControl to listOf("max-age=${60.days.inWholeSeconds}")),
                )
            }
        }
        val fixture = fixture(web)
        fixture.cache.write(EXAMPLE_ORIGIN, previousImage(expiresAt = NOW))

        val state = fixture.store.faviconOf(ITEM_URL)
        runCurrent()

        assertEquals(Favicon.Found(testImage("previous")), state.value)
        val request = web.requests.single()
        assertEquals(PREVIOUS_ETAG, request.headers[HttpHeaders.IfNoneMatch])
        assertEquals(PREVIOUS_LAST_MODIFIED, request.headers[HttpHeaders.IfModifiedSince])
        val cached = assertNotNull(fixture.cache.read(EXAMPLE_ORIGIN))
        assertEquals(NOW, cached.fetchedAt)
        assertEquals(NOW + 60.days, cached.expiresAt)
        assertEquals("\"v2\"", cached.image?.eTag)
        assertEquals(PREVIOUS_LAST_MODIFIED, cached.image?.lastModified)
        assertEquals(PREVIOUS_IMAGE_URL, cached.image?.url)
        assertContentEquals(testImageBytes("previous"), cached.image?.bytes)
    }

    // 304 の応答に期限の指定が無ければ、期限は問い合わせた時刻から 30 日にする
    @Test
    fun notModifiedWithoutLifetimeExtendsThirtyDays() = runTest {
        val web = FakeWeb().apply { on(PREVIOUS_IMAGE_URL) { respond(content = "", status = HttpStatusCode.NotModified) } }
        val fixture = fixture(web)
        fixture.cache.write(EXAMPLE_ORIGIN, previousImage(expiresAt = NOW))

        fixture.store.faviconOf(ITEM_URL)
        runCurrent()

        val cached = assertNotNull(fixture.cache.read(EXAMPLE_ORIGIN))
        assertEquals(NOW + FOUND_LIFETIME, cached.expiresAt)
        assertEquals(PREVIOUS_ETAG, cached.image?.eTag)
    }

    // 問い合わせが 404 なら、ふつうの順（/favicon.ico から）で取り直す
    @Test
    fun failedRevalidationRefetchesInOrder() = runTest {
        val web = FakeWeb().apply { image(EXAMPLE_FAVICON_ICO, "ico") }
        val fixture = fixture(web)
        fixture.cache.write(EXAMPLE_ORIGIN, previousImage(expiresAt = NOW))

        val state = fixture.store.faviconOf(ITEM_URL)
        runCurrent()

        assertEquals(listOf(PREVIOUS_IMAGE_URL, EXAMPLE_FAVICON_ICO), web.requestedUrls)
        assertEquals(Favicon.Found(testImage("ico")), state.value)
        assertEquals(EXAMPLE_FAVICON_ICO, fixture.cache.read(EXAMPLE_ORIGIN)?.image?.url)
    }

    // 形の崩れたキャッシュのファイルは、無いものとして取り直し、形が崩れていたことをログに書く
    @Test
    fun brokenCacheFileIsIgnored() = runTest {
        val fixture = fixture(FakeWeb().apply { image(EXAMPLE_FAVICON_ICO, "ico") })
        fixture.cache.fileOf(EXAMPLE_ORIGIN).also { Files.createDirectories(it.parent) }.let { Files.write(it, byteArrayOf(1, 2, 3)) }

        val state = fixture.store.faviconOf(ITEM_URL)
        runCurrent()

        assertEquals(Favicon.Found(testImage("ico")), state.value)
        assertEquals(1, fixture.logs.size)
        assertTrue("形が崩れている" in fixture.logs.single(), fixture.logs.toString())
    }

    // キャッシュの画像を画像として読めなければ、古くなくても、無いものとして取り直す
    @Test
    fun undecodableCachedImageIsRefetched() = runTest {
        val fixture = fixture(FakeWeb().apply { image(EXAMPLE_FAVICON_ICO, "ico") })
        val unreadable = CachedImage(EXAMPLE_FAVICON_ICO, PREVIOUS_ETAG, null, "not an image".encodeToByteArray())
        fixture.cache.write(EXAMPLE_ORIGIN, CachedFavicon(NOW, NOW + FOUND_LIFETIME, unreadable))

        val state = fixture.store.faviconOf(ITEM_URL)
        runCurrent()

        assertEquals(Favicon.Found(testImage("ico")), state.value)
        assertNull(fixture.web.requests.single().headers[HttpHeaders.IfNoneMatch])
    }

    // 取得が予期しない例外（画像を読むところの不具合）で止まったら、例外の種類をログに書き、Missing にする。5 分たつまでは取り直さず、
    // たってから faviconOf を呼ぶと取り直す
    @Test
    fun unexpectedFailureRetriesLater() = runTest {
        val web = FakeWeb().apply { image(EXAMPLE_FAVICON_ICO, "ico") }
        val fixture = StoreFixture(this, cacheDirectory, web, decode = { throw IllegalStateException("decoder broke") })

        val state = fixture.store.faviconOf(ITEM_URL)
        runCurrent()
        fixture.clock.current = NOW + RETRY_AFTER_FAILURE - 1.milliseconds
        fixture.store.faviconOf(ITEM_URL)
        runCurrent()

        assertEquals(Favicon.Missing, state.value)
        assertEquals(1, web.requests.size)
        assertEquals(listOf("favicon の取得が、予期しない例外で止まった: IllegalStateException"), fixture.logs)
        assertFalse(fixture.cache.fileOf(EXAMPLE_ORIGIN).exists())

        fixture.clock.current = NOW + RETRY_AFTER_FAILURE
        fixture.store.faviconOf(ITEM_URL)
        runCurrent()
        assertEquals(2, web.requests.size)
    }

    // close で取得を取り消し、HTTP クライアントを閉じる
    @Test
    fun closeCancelsFetchAndClosesClient() = runTest {
        val gate = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val web = FakeWeb(otherwise = {
            try {
                gate.await()
            } catch (cancellation: CancellationException) {
                cancelled.complete(Unit)
                throw cancellation
            }
            respond(content = "", status = HttpStatusCode.NotFound)
        })
        val fixture = fixture(web)
        val state = fixture.store.faviconOf(ITEM_URL)
        runCurrent()

        fixture.store.close()
        runCurrent()

        assertTrue(cancelled.isCompleted)
        assertFalse(fixture.client.isActive)
        assertEquals(Favicon.Loading, state.value)
        assertFalse(fixture.cache.fileOf(EXAMPLE_ORIGIN).exists())
    }
}
