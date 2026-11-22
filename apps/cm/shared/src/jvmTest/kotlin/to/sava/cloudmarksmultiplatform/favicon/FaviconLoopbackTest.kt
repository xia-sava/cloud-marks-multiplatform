package to.sava.cloudmarksmultiplatform.favicon

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.measureTime

/** 待ち受けるアドレス。外へはつながない。 */
private val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")

/** 待ち受けの、受け付けを待たせておく接続の数。 */
private const val BACKLOG = 8

/** 待ち受けのスレッドが終わるのを待つ上限（ミリ秒）。 */
private const val SERVER_STOP_MILLIS = 5_000L

/** HTTP でない応答。 */
private const val NOT_HTTP_RESPONSE = "this is not http\r\n\r\n"

/** 本体の無い `404` の応答。 */
private const val NOT_FOUND_RESPONSE = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"

/**
 * 127.0.0.1 だけで待ち受け、受けた接続ごとに、要求の最初の行を [requestLines] に足してから [response] を返して閉じるサーバー。
 */
private class LoopbackServer(private val response: String) : AutoCloseable {
    private val server = ServerSocket(0, BACKLOG, LOOPBACK)

    /** 待ち受けているポート。 */
    val port: Int = server.localPort

    /** 受けた要求の最初の行。受けた順。 */
    val requestLines: MutableList<String> = CopyOnWriteArrayList()

    private val acceptor = thread(isDaemon = true) {
        while (true) {
            val socket = try {
                server.accept()
            } catch (e: SocketException) {
                // 閉じたので、待ち受けをやめる
                break
            }
            socket.use { answer(it) }
        }
    }

    /** 要求のヘッダーを読み終えてから、[response] を返す。 */
    private fun answer(socket: Socket) {
        val reader = socket.getInputStream().bufferedReader()
        generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList().firstOrNull()?.let(requestLines::add)
        socket.getOutputStream().apply {
            write(response.encodeToByteArray())
            flush()
        }
    }

    override fun close() {
        server.close()
        acceptor.join(SERVER_STOP_MILLIS)
    }
}

/**
 * 本物の CIO のクライアント。127.0.0.1 でない宛先への要求は、エンジンへ渡す前に [AssertionError] で止める。取る側は例外（`Exception`）を
 * 応答なしとして受けるが、`Error` は受けないので、テストが失敗する。外へは決してつながない。
 */
private fun loopbackOnlyClient(): HttpClient =
    createFaviconHttpClient().apply {
        plugin(HttpSend).intercept { request ->
            if (request.url.host != LOOPBACK.hostAddress) throw AssertionError("a request to a non-loopback host: ${request.url.host}")
            execute(request)
        }
    }

/**
 * 本物の CIO のクライアントで、ループバックのサーバーから favicon を取る。オリジンのホストは IP アドレスなので、オリジンの外の取り先へは
 * 問い合わせない（問い合わせようとすれば [loopbackOnlyClient] が止める）。
 */
class FaviconLoopbackTest {
    /** `http://127.0.0.1:<port>` の favicon を、本物の CIO のクライアントで取り、結果とかかった時間を返す。 */
    private fun fetchFromLoopback(port: Int): Pair<FetchedFavicon, Duration> =
        loopbackOnlyClient().use { client ->
            var fetched: FetchedFavicon? = null
            val elapsed = measureTime {
                fetched = runBlocking { FaviconFetcher(client, ::decodeTestImage).fetch(FaviconOrigin("http", "127.0.0.1", port)) }
            }
            checkNotNull(fetched) to elapsed
        }

    /** 本物の CIO のクライアントで [url] を GET したときに投げる例外。 */
    private fun failureOfGet(url: String): Throwable =
        loopbackOnlyClient().use { client -> assertFails { runBlocking { client.get(url) } } }

    // このテストのクライアントは、ループバックでない宛先への要求を、エンジンへ渡す前に止める。止め損ねても名前を引けないよう、`.invalid` を使う
    @Test
    fun clientRefusesNonLoopbackHost() {
        loopbackOnlyClient().use { client ->
            assertFailsWith<AssertionError> { runBlocking { client.get("http://guard.invalid/favicon.ico") } }
        }
    }

    // 閉じたポートへは、CIO が ConnectException を投げる。取るときは投げずに応答なしとし、時間の上限を待たずにオフラインとみなす
    @Test
    fun closedPortIsUnreachable() {
        val port = ServerSocket(0, BACKLOG, LOOPBACK).use { it.localPort }

        assertIs<ConnectException>(failureOfGet("http://127.0.0.1:$port/favicon.ico"))
        val (fetched, elapsed) = fetchFromLoopback(port)

        assertEquals(FetchedFavicon.Unreachable, fetched)
        assertTrue(elapsed < FETCH_TIME_LIMIT, elapsed.toString())
    }

    // HTTP として読めない応答には、CIO が IOException でない IllegalStateException（ParserException）を投げる。取るときは投げずに
    // 応答なしとし、時間の上限を待たずにオフラインとみなす。HTML は要求しない
    @Test
    fun nonHttpResponseIsNoResponse() {
        LoopbackServer(NOT_HTTP_RESPONSE).use { server ->
            val failure = failureOfGet("http://127.0.0.1:${server.port}/favicon.ico")
            assertFalse(failure is IOException, failure.toString())
            assertIs<IllegalStateException>(failure)
            server.requestLines.clear()

            val (fetched, elapsed) = fetchFromLoopback(server.port)

            assertEquals(FetchedFavicon.Unreachable, fetched)
            assertTrue(elapsed < FETCH_TIME_LIMIT, elapsed.toString())
            assertEquals(listOf("GET /favicon.ico HTTP/1.1"), server.requestLines)
        }
    }

    // HTTP の応答（404）が得られたら、HTML へ進み、取れなければ無しとする
    @Test
    fun notFoundIsResponse() {
        LoopbackServer(NOT_FOUND_RESPONSE).use { server ->
            val (fetched, _) = fetchFromLoopback(server.port)

            assertEquals(FetchedFavicon.Missing, fetched)
            assertEquals(listOf("GET /favicon.ico HTTP/1.1", "GET / HTTP/1.1"), server.requestLines)
        }
    }
}
