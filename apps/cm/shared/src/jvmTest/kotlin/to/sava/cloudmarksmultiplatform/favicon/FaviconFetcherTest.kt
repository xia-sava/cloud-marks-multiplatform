package to.sava.cloudmarksmultiplatform.favicon

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLParserException
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.channels.UnresolvedAddressException
import java.security.cert.CertificateException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** 1 KiB のバイト数。 */
private const val KIBIBYTE = 1024

/** 1 つの取り先が応答を返すまでに、1 つのオリジンにかける時間の上限（10 秒）より長くかかる時間。 */
private val LONGER_THAN_TIME_LIMIT = 20.seconds

/** 前に取った画像の `ETag`。 */
private const val PREVIOUS_ETAG = "\"v1\""

/** 前に取った画像の `Last-Modified`。 */
private const val PREVIOUS_LAST_MODIFIED = "Mon, 01 Jan 2024 00:00:00 GMT"

/** 前に取った画像の URL。 */
private const val PREVIOUS_IMAGE_URL = "https://example.com/static/icon.png"

// currentTime が ExperimentalCoroutinesApi であるため。
@OptIn(ExperimentalCoroutinesApi::class)
class FaviconFetcherTest {
    /** [web] から、テストの時間で動くクライアントで、[origin] の favicon を取る。 */
    private suspend fun TestScope.fetch(
        web: FakeWeb,
        origin: FaviconOrigin = EXAMPLE_ORIGIN,
        revalidation: Revalidation? = null,
    ): FetchedFavicon =
        FaviconFetcher(web.client(StandardTestDispatcher(testScheduler)), ::decodeTestImage).fetch(origin, revalidation)

    // /favicon.ico が画像なら、それを使い、ほかの取り先には要求を出さない
    @Test
    fun usesFaviconIcoFirst() = runTest {
        val web = FakeWeb().apply { image(EXAMPLE_FAVICON_ICO, "ico") }

        val fetched = assertIs<FetchedFavicon.Found>(fetch(web))

        assertSame(testImage("ico"), fetched.image)
        assertEquals(EXAMPLE_FAVICON_ICO, fetched.url)
        assertContentEquals(testImageBytes("ico"), fetched.bytes)
        assertEquals(listOf(EXAMPLE_FAVICON_ICO), web.requestedUrls)
    }

    // /favicon.ico が取れなければ HTML の <link> へ進む。相対の href は、転送後の最終の URL を基準に解決する
    @Test
    fun resolvesLinkAgainstFinalUrl() = runTest {
        val web = FakeWeb().apply {
            redirect(EXAMPLE_ROOT, "https://www.example.com/home/")
            html("https://www.example.com/home/", """<head><link rel="shortcut icon" href="img/icon.png"></head>""")
            image("https://www.example.com/home/img/icon.png", "link")
        }

        val fetched = assertIs<FetchedFavicon.Found>(fetch(web))

        assertSame(testImage("link"), fetched.image)
        assertEquals("https://www.example.com/home/img/icon.png", fetched.url)
        assertEquals(
            listOf(EXAMPLE_FAVICON_ICO, EXAMPLE_ROOT, "https://www.example.com/home/", "https://www.example.com/home/img/icon.png"),
            web.requestedUrls,
        )
    }

    // 画像として読めないもの（SVG など）と、空の本体は飛ばし、文書の順に次の <link> を試す
    @Test
    fun skipsUndecodableAndEmptyImages() = runTest {
        val web = FakeWeb().apply {
            on(EXAMPLE_FAVICON_ICO) { respond(content = "", status = HttpStatusCode.OK) }
            html(
                EXAMPLE_ROOT,
                """<link rel="icon" href="/icon.svg"><link rel="icon" href="/icon.png"><link rel="icon" href="/later.png">""",
            )
            on("https://example.com/icon.svg") { respond(content = "<svg/>", status = HttpStatusCode.OK) }
            image("https://example.com/icon.png", "png")
            image("https://example.com/later.png", "later")
        }

        val fetched = assertIs<FetchedFavicon.Found>(fetch(web))

        assertSame(testImage("png"), fetched.image)
        assertEquals(
            listOf(EXAMPLE_FAVICON_ICO, EXAMPLE_ROOT, "https://example.com/icon.svg", "https://example.com/icon.png"),
            web.requestedUrls,
        )
    }

    // オリジンから取れなければ、はてなの https、はてなの http、Google の順に試す。クエリの値は URL エンコードする
    @Test
    fun fallsBackToExternalServicesInOrder() = runTest {
        val cases = listOf(EXAMPLE_HATENA_HTTPS, EXAMPLE_HATENA_HTTP, EXAMPLE_GOOGLE)
        cases.forEachIndexed { index, source ->
            val web = FakeWeb().apply { image(source, "external") }

            val fetched = assertIs<FetchedFavicon.Found>(fetch(web), source)

            assertSame(testImage("external"), fetched.image, source)
            assertEquals(EXAMPLE_ALL_SOURCES.take(index + 3), web.requestedUrls, source)
        }
    }

    // 既定でないポートは、はてなの取り先に渡すサイトの URL に含め、Google の取り先には含めない
    @Test
    fun externalServicesCarryPort() = runTest {
        val web = FakeWeb()

        fetch(web, FaviconOrigin("http", "example.com", 8080))

        assertEquals(
            listOf(
                "http://example.com:8080/favicon.ico",
                "http://example.com:8080/",
                "https://cdn-ak.favicon.st-hatena.com/?url=https%3A%2F%2Fexample.com%3A8080",
                "https://cdn-ak.favicon.st-hatena.com/?url=http%3A%2F%2Fexample.com%3A8080",
                EXAMPLE_GOOGLE,
            ),
            web.requestedUrls,
        )
    }

    // どの取り先も応答を返したが取れなければ、無し（Missing）にする
    @Test
    fun missingWhenEverySourceResponds() = runTest {
        val web = FakeWeb()

        assertEquals(FetchedFavicon.Missing, fetch(web))
        assertEquals(EXAMPLE_ALL_SOURCES, web.requestedUrls)
    }

    // どの取り先からも応答が得られなければ（つながらない・名前を引けない）、オフラインとみなす（Unreachable）。
    // /favicon.ico から応答が得られないので、同じオリジンの HTML は要求しない
    @Test
    fun unreachableWhenNoSourceResponds() = runTest {
        listOf<Reply>(connectionRefused, failingWith { UnresolvedAddressException() }).forEach { failure ->
            val web = FakeWeb(otherwise = failure)

            assertEquals(FetchedFavicon.Unreachable, fetch(web))
            assertEquals(EXAMPLE_SOURCES_WITHOUT_HTML, web.requestedUrls)
        }
    }

    // 要求の例外は種類を問わず（証明書の検証の失敗、HTTP として読めない応答、TLS の途中の失敗、読めない転送先など）、
    // その取り先から応答が得られなかったものとして、次の取り先へ進む
    @Test
    fun anyRequestFailureMovesToNextSource() = runTest {
        listOf<() -> Exception>(
            { CertificateException("untrusted") },
            { IllegalStateException("handshake") },
            { NumberFormatException("bad chunk size") },
            { URLParserException("bad location", IllegalArgumentException()) },
            { IllegalArgumentException("unix socket") },
        ).forEach { failure ->
            val web = FakeWeb().apply {
                on(EXAMPLE_FAVICON_ICO, failingWith(failure))
                on(EXAMPLE_HATENA_HTTPS, failingWith(failure))
                image(EXAMPLE_GOOGLE, "google")
            }

            val fetched = assertIs<FetchedFavicon.Found>(fetch(web), failure().toString())

            assertSame(testImage("google"), fetched.image)
            assertEquals(EXAMPLE_SOURCES_WITHOUT_HTML, web.requestedUrls, failure().toString())
        }
    }

    // この取得が取り消されていなければ、ktor の中から来た CancellationException も、応答が得られなかったものとして次へ進む
    @Test
    fun foreignCancellationMovesToNextSource() = runTest {
        val web = FakeWeb().apply {
            on(EXAMPLE_FAVICON_ICO, failingWith { CancellationException("body channel was cancelled") })
            image(EXAMPLE_HATENA_HTTPS, "hatena")
        }

        assertSame(testImage("hatena"), assertIs<FetchedFavicon.Found>(fetch(web)).image)
        assertEquals(listOf(EXAMPLE_FAVICON_ICO, EXAMPLE_HATENA_HTTPS), web.requestedUrls)
    }

    // 画像として読むところ（decode）の例外は、取り先の失敗にせず、そのまま投げる
    @Test
    fun decodeFailurePropagates() = runTest {
        val web = FakeWeb().apply { image(EXAMPLE_FAVICON_ICO, "ico") }
        val fetcher = FaviconFetcher(web.client(StandardTestDispatcher(testScheduler))) { throw IllegalStateException("decoder broke") }

        assertFailsWith<IllegalStateException> { fetcher.fetch(EXAMPLE_ORIGIN) }
    }

    // /favicon.ico がステータスを返したあとで本体が途中で切れても、応答は得られたものとして HTML へ進む
    @Test
    fun failureAfterStatusCountsAsResponse() = runTest {
        val web = FakeWeb().apply {
            on(EXAMPLE_FAVICON_ICO) {
                val body = ByteChannel(autoFlush = true)
                body.cancel(IOException("connection reset"))
                respond(content = body, status = HttpStatusCode.OK)
            }
            html(EXAMPLE_ROOT, """<link rel="icon" href="/icon.png">""")
            image("https://example.com/icon.png", "link")
        }

        assertSame(testImage("link"), assertIs<FetchedFavicon.Found>(fetch(web)).image)
        assertEquals(listOf(EXAMPLE_FAVICON_ICO, EXAMPLE_ROOT, "https://example.com/icon.png"), web.requestedUrls)
    }

    // ホストをオリジンの外へ渡さない名前（localhost、端末や手元のネットワークの名前、ドットの無い名前、IP アドレス）は、
    // サイトから取れなくても、はてなと Google へ問い合わせない
    @Test
    fun localHostsAreNotSentToExternalServices() = runTest {
        listOf(
            FaviconOrigin("http", "localhost", 8080),
            FaviconOrigin("http", "printer.local", null),
            FaviconOrigin("https", "intranet", null),
            FaviconOrigin("http", "192.168.1.10", null),
            FaviconOrigin("http", "[::1]", null),
            FaviconOrigin("https", "8.8.8.8", null),
        ).forEach { origin ->
            val web = FakeWeb()

            assertEquals(FetchedFavicon.Missing, fetch(web, origin), "$origin")
            assertEquals(listOf("$origin/favicon.ico", "$origin/"), web.requestedUrls, "$origin")
        }
    }

    // ホストをオリジンの外へ渡さないときは、サイトから応答が得られなければオフラインとみなす
    @Test
    fun localHostWithoutResponseIsUnreachable() = runTest {
        val web = FakeWeb(otherwise = connectionRefused)
        val origin = FaviconOrigin("http", "nas.lan", null)

        assertEquals(FetchedFavicon.Unreachable, fetch(web, origin))
        assertEquals(listOf("http://nas.lan/favicon.ico"), web.requestedUrls)
    }

    // 前の画像をオリジンの外の取り先から取っていたなら、問い合わせずに /favicon.ico から取り直す。外の取り先へは、順番が来たら条件なしで要求する
    @Test
    fun externalSourceIsNotRevalidated() = runTest {
        listOf(EXAMPLE_HATENA_HTTPS, EXAMPLE_GOOGLE).forEach { previous ->
            val web = FakeWeb().apply { image(previous, "again") }

            val fetched = assertIs<FetchedFavicon.Found>(fetch(web, revalidation = Revalidation(previous, PREVIOUS_ETAG, PREVIOUS_LAST_MODIFIED)))

            assertSame(testImage("again"), fetched.image, previous)
            assertEquals(EXAMPLE_FAVICON_ICO, web.requestedUrls.first(), previous)
            assertTrue(web.requests.none { it.headers[HttpHeaders.IfNoneMatch] != null }, previous)
        }
    }

    // いつまでも書き続ける画像の本体は、上限の 1 MiB を超えたところで読むのをやめ、取れなかったものとして次へ進む
    @Test
    fun stopsReadingEndlessImage() = runTest {
        val web = FakeWeb().apply {
            on(EXAMPLE_FAVICON_ICO, endlessBody(backgroundScope, testImageBytes("endless"), ByteArray(KIBIBYTE)))
            image(EXAMPLE_GOOGLE, "google")
        }

        assertSame(testImage("google"), assertIs<FetchedFavicon.Found>(fetch(web)).image)
    }

    // HTML は先頭から 512 KiB までだけ読む。上限より前の <link> は拾い、後ろの <link> は拾わない
    @Test
    fun readsHtmlUpToLimit() = runTest {
        val padding = " ".repeat(MAX_HTML_BYTES)
        listOf(
            """<link rel="icon" href="/early.png">$padding<link rel="icon" href="/late.png">""" to "https://example.com/early.png",
            """$padding<link rel="icon" href="/late.png">""" to null,
        ).forEach { (html, expected) ->
            val web = FakeWeb().apply {
                html(EXAMPLE_ROOT, html)
                image("https://example.com/early.png", "early")
                image("https://example.com/late.png", "late")
            }

            fetch(web)

            assertEquals(expected, web.requestedUrls.getOrNull(2)?.takeIf { it.endsWith(".png") }, expected.toString())
            assertTrue("https://example.com/late.png" !in web.requestedUrls)
        }
    }

    // いつまでも書き続ける HTML の本体も、上限まで読んで <link> を拾う
    @Test
    fun readsEndlessHtmlUpToLimit() = runTest {
        val web = FakeWeb().apply {
            on(EXAMPLE_ROOT, endlessBody(backgroundScope, """<link rel="icon" href="/icon.png">""".encodeToByteArray(), " ".repeat(KIBIBYTE).encodeToByteArray()))
            image("https://example.com/icon.png", "link")
        }

        assertSame(testImage("link"), assertIs<FetchedFavicon.Found>(fetch(web)).image)
    }

    // 1 つでも応答が得られれば、ほかがつながらなくても無し（Missing）にする
    @Test
    fun missingWhenSomeSourceResponds() = runTest {
        val web = FakeWeb(otherwise = connectionRefused).apply { on(EXAMPLE_GOOGLE, notFound) }

        assertEquals(FetchedFavicon.Missing, fetch(web))
    }

    // 画像の応答は 1 MiB までだけ受け取り、超えたら（大きさを言っていてもいなくても）取れなかったものとして次へ進む
    @Test
    fun skipsOversizeImages() = runTest {
        val oversize = testImageBytes("big") + ByteArray(MAX_IMAGE_BYTES)
        listOf(true, false).forEach { announcesLength ->
            val web = FakeWeb().apply {
                on(EXAMPLE_FAVICON_ICO) {
                    respond(
                        content = ByteReadChannel(oversize),
                        status = HttpStatusCode.OK,
                        headers = if (announcesLength) headersOf(HttpHeaders.ContentLength, oversize.size.toString()) else headersOf(),
                    )
                }
                image(EXAMPLE_GOOGLE, "small")
            }

            val fetched = assertIs<FetchedFavicon.Found>(fetch(web), "announcesLength=$announcesLength")

            assertSame(testImage("small"), fetched.image, "announcesLength=$announcesLength")
        }
    }

    // 画像の応答がちょうど 1 MiB なら受け取る
    @Test
    fun acceptsImageOfLimitSize() = runTest {
        val prefix = testImageBytes("limit")
        val exact = prefix + ByteArray(MAX_IMAGE_BYTES - prefix.size) { 'x'.code.toByte() }
        val web = FakeWeb().apply { on(EXAMPLE_FAVICON_ICO) { respond(content = exact, status = HttpStatusCode.OK) } }
        val fetcher = FaviconFetcher(web.client(StandardTestDispatcher(testScheduler))) { bytes ->
            testImage("limit").takeIf { bytes.size == MAX_IMAGE_BYTES }
        }

        val fetched = assertIs<FetchedFavicon.Found>(fetcher.fetch(EXAMPLE_ORIGIN))

        assertEquals(MAX_IMAGE_BYTES, fetched.bytes.size)
    }

    // 1 つのオリジンにかける時間は 10 秒まで。超えたら残りを打ち切り、応答が 1 つも無ければオフラインとみなす
    @Test
    fun stopsAtTimeLimitWithoutResponse() = runTest {
        val web = FakeWeb(otherwise = {
            delay(LONGER_THAN_TIME_LIMIT)
            respond(content = "", status = HttpStatusCode.NotFound)
        })

        assertEquals(FetchedFavicon.Unreachable, fetch(web))
        assertEquals(FETCH_TIME_LIMIT.inWholeMilliseconds, currentTime)
        assertEquals(listOf(EXAMPLE_FAVICON_ICO), web.requestedUrls)
    }

    // 時間切れになる前に応答が得られていれば、無し（Missing）にする
    @Test
    fun stopsAtTimeLimitAfterResponse() = runTest {
        val web = FakeWeb(otherwise = {
            delay(LONGER_THAN_TIME_LIMIT)
            respond(content = "", status = HttpStatusCode.NotFound)
        }).apply { on(EXAMPLE_FAVICON_ICO, notFound) }

        assertEquals(FetchedFavicon.Missing, fetch(web))
        assertEquals(FETCH_TIME_LIMIT.inWholeMilliseconds, currentTime)
    }

    // 前に取った画像があれば、まずその URL へ、ETag と Last-Modified を付けて問い合わせる。304 なら変わっていない
    @Test
    fun revalidatesPreviousImageFirst() = runTest {
        val web = FakeWeb().apply {
            on(PREVIOUS_IMAGE_URL) { respond(content = "", status = HttpStatusCode.NotModified, headers = headersOf(HttpHeaders.ETag, "\"v2\"")) }
        }

        val fetched = fetch(web, revalidation = Revalidation(PREVIOUS_IMAGE_URL, PREVIOUS_ETAG, PREVIOUS_LAST_MODIFIED))

        assertEquals(FetchedFavicon.NotModified(CacheHeaders(eTag = "\"v2\"")), fetched)
        val request = web.requests.single()
        assertEquals(PREVIOUS_IMAGE_URL, request.url.toString())
        assertEquals(PREVIOUS_ETAG, request.headers[HttpHeaders.IfNoneMatch])
        assertEquals(PREVIOUS_LAST_MODIFIED, request.headers[HttpHeaders.IfModifiedSince])
    }

    // 前の画像の応答に ETag も Last-Modified も無ければ、条件を付けずにその URL を取り直す
    @Test
    fun revalidatesWithoutValidators() = runTest {
        val web = FakeWeb().apply { image(PREVIOUS_IMAGE_URL, "previous") }

        val fetched = assertIs<FetchedFavicon.Found>(fetch(web, revalidation = Revalidation(PREVIOUS_IMAGE_URL, null, null)))

        assertEquals(PREVIOUS_IMAGE_URL, fetched.url)
        val request = web.requests.single()
        assertNull(request.headers[HttpHeaders.IfNoneMatch])
        assertNull(request.headers[HttpHeaders.IfModifiedSince])
    }

    // 問い合わせが 200 で画像として読めれば、それを新しい画像にする
    @Test
    fun revalidationReplacesWithNewImage() = runTest {
        val web = FakeWeb().apply { image(PREVIOUS_IMAGE_URL, "new", headersOf(HttpHeaders.ETag, "\"v2\"")) }

        val fetched = assertIs<FetchedFavicon.Found>(fetch(web, revalidation = Revalidation(PREVIOUS_IMAGE_URL, PREVIOUS_ETAG, null)))

        assertSame(testImage("new"), fetched.image)
        assertEquals("\"v2\"", fetched.headers.eTag)
        assertEquals(1, web.requests.size)
    }

    // 問い合わせが 404 なら、/favicon.ico からふつうの順に取り直す
    @Test
    fun revalidationFailureFallsBackToOrder() = runTest {
        val web = FakeWeb().apply { image(EXAMPLE_FAVICON_ICO, "ico") }

        val fetched = assertIs<FetchedFavicon.Found>(fetch(web, revalidation = Revalidation(PREVIOUS_IMAGE_URL, PREVIOUS_ETAG, null)))

        assertSame(testImage("ico"), fetched.image)
        assertEquals(listOf(PREVIOUS_IMAGE_URL, EXAMPLE_FAVICON_ICO), web.requestedUrls)
        assertNull(web.requests[1].headers[HttpHeaders.IfNoneMatch])
    }

    // 問い合わせた URL がふつうの順の取り先と同じなら、それは 2 度は試さない
    @Test
    fun doesNotRetryRevalidatedUrl() = runTest {
        val web = FakeWeb()

        fetch(web, revalidation = Revalidation(EXAMPLE_FAVICON_ICO, PREVIOUS_ETAG, null))

        assertEquals(EXAMPLE_ALL_SOURCES, web.requestedUrls)
    }

    // 問い合わせていないときの 304 は、取れなかったものとして次へ進む
    @Test
    fun notModifiedWithoutRevalidationIsSkipped() = runTest {
        val web = FakeWeb().apply {
            on(EXAMPLE_FAVICON_ICO) { respond(content = "", status = HttpStatusCode.NotModified) }
            image(EXAMPLE_GOOGLE, "google")
        }

        assertSame(testImage("google"), assertIs<FetchedFavicon.Found>(fetch(web)).image)
    }

    // 画像の応答の、キャッシュの期限と、変わったかを問い合わせることに使うヘッダーを返す
    @Test
    fun returnsCacheHeaders() = runTest {
        val headers = headersOf(
            HttpHeaders.CacheControl to listOf("public", "max-age=60"),
            HttpHeaders.Expires to listOf("Thu, 01 Jan 2026 00:00:00 GMT"),
            HttpHeaders.Date to listOf("Wed, 31 Dec 2025 00:00:00 GMT"),
            HttpHeaders.ETag to listOf(PREVIOUS_ETAG),
            HttpHeaders.LastModified to listOf(PREVIOUS_LAST_MODIFIED),
        )
        val web = FakeWeb().apply { image(EXAMPLE_FAVICON_ICO, "ico", headers) }

        val fetched = assertIs<FetchedFavicon.Found>(fetch(web))

        assertEquals(
            CacheHeaders(
                cacheControl = listOf("public", "max-age=60"),
                expires = "Thu, 01 Jan 2026 00:00:00 GMT",
                date = "Wed, 31 Dec 2025 00:00:00 GMT",
                eTag = PREVIOUS_ETAG,
                lastModified = PREVIOUS_LAST_MODIFIED,
            ),
            fetched.headers,
        )
    }
}
