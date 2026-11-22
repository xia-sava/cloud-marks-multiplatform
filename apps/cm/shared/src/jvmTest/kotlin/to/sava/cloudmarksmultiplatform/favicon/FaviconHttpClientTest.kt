package to.sava.cloudmarksmultiplatform.favicon

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.plugin
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 接続を確立するまで待つ上限。 */
private const val EXPECTED_CONNECT_TIMEOUT_MILLIS = 5_000L

/** 受信が止まったまま待つ上限。 */
private const val EXPECTED_STALL_TIMEOUT_MILLIS = 5_000L

/** エンジンへ渡す手前で止める要求の宛先。止め損ねても名前を引けないよう、`.invalid` を使う。 */
private const val STOPPED_REQUEST_URL = "https://example.invalid/favicon.ico"

/** 要求をエンジンへ渡す手前で止めたこと。 */
private class RequestStoppedBeforeEngine : RuntimeException("request stopped before the engine")

class FaviconHttpClientTest {
    private fun faviconClient(engine: MockEngine): HttpClient = HttpClient(engine) { configureFaviconClient() }

    /** [timeouts] が、接続の確立と受信が止まったままのものだけを打ち切ること。 */
    private fun assertFaviconTimeouts(timeouts: HttpTimeoutConfig?) {
        val applied = assertNotNull(timeouts, "the request carried no timeouts")
        assertEquals(EXPECTED_CONNECT_TIMEOUT_MILLIS, applied.connectTimeoutMillis)
        assertEquals(EXPECTED_STALL_TIMEOUT_MILLIS, applied.socketTimeoutMillis)
        assertNull(applied.requestTimeoutMillis)
    }

    // 要求には、接続の確立（5 秒）と受信が止まったまま（5 秒）の打ち切りが付く。全体の時間は取る側が縛る
    @Test
    fun limitsConnectAndStall() = runTest {
        var timeouts: HttpTimeoutConfig? = null
        val engine = MockEngine { request ->
            timeouts = request.getCapabilityOrNull(HttpTimeoutCapability)
            respond(content = "", status = HttpStatusCode.OK)
        }

        faviconClient(engine).get(EXAMPLE_FAVICON_ICO)

        assertFaviconTimeouts(timeouts)
    }

    // 作ったクライアント（CIO）も同じ設定を通る。要求はエンジンへ渡す手前で止めるので、通信しない
    @Test
    fun createdClientIsConfiguredForFavicons() = runTest {
        createFaviconHttpClient().use { client ->
            var timeouts: HttpTimeoutConfig? = null
            client.plugin(HttpSend).intercept { request ->
                timeouts = request.getCapabilityOrNull(HttpTimeoutCapability)
                throw RequestStoppedBeforeEngine()
            }

            assertFailsWith<RequestStoppedBeforeEngine> { client.get(STOPPED_REQUEST_URL) }

            assertFaviconTimeouts(timeouts)
        }
    }

    // 転送は、https から http へのものも追い、転送先の応答を返す
    @Test
    fun followsRedirects() = runTest {
        val engine = MockEngine { request ->
            when (request.url.toString()) {
                EXAMPLE_FAVICON_ICO -> respond(
                    content = "",
                    status = HttpStatusCode.MovedPermanently,
                    headers = headersOf(HttpHeaders.Location, "http://static.example.com/favicon.ico"),
                )
                else -> respond(content = "", status = HttpStatusCode.OK)
            }
        }

        val response = faviconClient(engine).get(EXAMPLE_FAVICON_ICO)

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf(EXAMPLE_FAVICON_ICO, "http://static.example.com/favicon.ico"), engine.requestHistory.map { it.url.toString() })
    }
}
