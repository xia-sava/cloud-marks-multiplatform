package to.sava.cloudmarksmultiplatform.favicon

import androidx.compose.ui.graphics.ImageBitmap
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ClosedByteChannelException
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.net.ConnectException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** 差し替えたデコード（[decodeTestImage]）が画像として読む、バイト列の頭。 */
private const val TEST_IMAGE_PREFIX = "image:"

/** テストの画像。名前ごとに 1 つ。 */
private val testImages = ConcurrentHashMap<String, ImageBitmap>()

/** 差し替えたデコード（[decodeTestImage]）が、名前 [name] の画像として読むバイト列。 */
internal fun testImageBytes(name: String): ByteArray = "$TEST_IMAGE_PREFIX$name".encodeToByteArray()

/** 名前 [name] の画像。同じ名前なら同じものを返す。 */
internal fun testImage(name: String): ImageBitmap = testImages.getOrPut(name) { ImageBitmap(1, 1) }

/** [testImageBytes] のバイト列だけを画像として読み、ほかは読めないとする、差し替えたデコード。 */
internal fun decodeTestImage(bytes: ByteArray): ImageBitmap? =
    bytes.decodeToString().takeIf { it.startsWith(TEST_IMAGE_PREFIX) }?.removePrefix(TEST_IMAGE_PREFIX)?.let(::testImage)

/** 偽のサイトの、1 つの URL への応答。 */
internal typealias Reply = suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData

/** `404` を返す応答。 */
internal val notFound: Reply = { respond(content = "", status = HttpStatusCode.NotFound) }

/** つながらない（応答が得られない）こと。 */
internal val connectionRefused: Reply = { throw ConnectException("connection refused") }

/** 例外 [failure] で、応答を返さないこと。 */
internal fun failingWith(failure: () -> Exception): Reply = { throw failure() }

/**
 * 頭が [head] で、そのあと [filler] をいつまでも書き続ける本体の、成功（`200`）の応答。書くのは [scope] で走らせる。
 * 読む側が本体を閉じたら、書くのをやめる。
 */
internal fun endlessBody(scope: CoroutineScope, head: ByteArray, filler: ByteArray): Reply = {
    val body = ByteChannel(autoFlush = true)
    scope.launch {
        try {
            body.writeFully(head)
            while (true) body.writeFully(filler)
        } catch (e: ClosedByteChannelException) {
            // 読む側が本体を閉じた
        }
    }
    respond(content = body, status = HttpStatusCode.OK)
}

/**
 * URL ごとに応答を決めた、偽のサイトの集まり。本物のサイトにはつながない。
 * URL（クエリを含む全体）ごとに [on] などで決めた応答を返し、決めていない URL には [otherwise] を返す。
 */
internal class FakeWeb(private val otherwise: Reply = notFound) {
    private val replies = ConcurrentHashMap<String, Reply>()

    /** 受けた要求。受けた順。 */
    val requests: MutableList<HttpRequestData> = CopyOnWriteArrayList()

    /** 受けた要求の URL。受けた順。 */
    val requestedUrls: List<String> get() = requests.map { it.url.toString() }

    /** [url] への応答を [reply] にする。 */
    fun on(url: String, reply: Reply) {
        replies[url] = reply
    }

    /** [url] に、名前 [name] の画像のバイト列を、ヘッダー [headers] を付けて返す。 */
    fun image(url: String, name: String, headers: Headers = headersOf()) =
        on(url) { respond(content = testImageBytes(name), status = HttpStatusCode.OK, headers = headers) }

    /** [url] に、HTML の文書 [html] を返す。 */
    fun html(url: String, html: String) =
        on(url) {
            respond(content = html, status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()))
        }

    /** [url] を [location] へ転送する。 */
    fun redirect(url: String, location: String) =
        on(url) { respond(content = "", status = HttpStatusCode.Found, headers = headersOf(HttpHeaders.Location, location)) }

    /** [dispatcher] で動く偽のエンジン。テストの時間で動かすため、テストのディスパッチャを渡す。 */
    fun engine(dispatcher: CoroutineDispatcher): MockEngine =
        MockEngine(
            MockEngineConfig().apply {
                this.dispatcher = dispatcher
                addHandler { request ->
                    requests += request
                    (replies[request.url.toString()] ?: otherwise)(request)
                }
            },
        )

    /** [engine] を使う、favicon を取る設定（[configureFaviconClient]）のクライアント。 */
    fun client(dispatcher: CoroutineDispatcher): HttpClient = HttpClient(engine(dispatcher)) { configureFaviconClient() }
}

/** 取りに行くオリジン。 */
internal val EXAMPLE_ORIGIN = FaviconOrigin("https", "example.com", null)

/** [EXAMPLE_ORIGIN] の `/favicon.ico`。 */
internal const val EXAMPLE_FAVICON_ICO = "https://example.com/favicon.ico"

/** [EXAMPLE_ORIGIN] の HTML。 */
internal const val EXAMPLE_ROOT = "https://example.com/"

/** [EXAMPLE_ORIGIN] の、はてなの https の取り先。 */
internal const val EXAMPLE_HATENA_HTTPS = "https://cdn-ak.favicon.st-hatena.com/?url=https%3A%2F%2Fexample.com"

/** [EXAMPLE_ORIGIN] の、はてなの http の取り先。 */
internal const val EXAMPLE_HATENA_HTTP = "https://cdn-ak.favicon.st-hatena.com/?url=http%3A%2F%2Fexample.com"

/** [EXAMPLE_ORIGIN] の、Google の取り先。 */
internal const val EXAMPLE_GOOGLE = "https://www.google.com/s2/favicons?domain=example.com&sz=64"

/** [EXAMPLE_ORIGIN] で、どこからも取れないときに試す URL。試す順。 */
internal val EXAMPLE_ALL_SOURCES =
    listOf(EXAMPLE_FAVICON_ICO, EXAMPLE_ROOT, EXAMPLE_HATENA_HTTPS, EXAMPLE_HATENA_HTTP, EXAMPLE_GOOGLE)

/** [EXAMPLE_ORIGIN] で、`/favicon.ico` から応答が得られないときに試す URL。HTML は飛ばす。試す順。 */
internal val EXAMPLE_SOURCES_WITHOUT_HTML = EXAMPLE_ALL_SOURCES - EXAMPLE_ROOT
