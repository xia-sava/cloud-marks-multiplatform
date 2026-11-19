package to.sava.cloudmarksmultiplatform.update

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
private const val EXPECTED_CONNECT_TIMEOUT_MILLIS = 15_000L

/** 受信が止まったまま待つ上限。 */
private const val EXPECTED_STALL_TIMEOUT_MILLIS = 60_000L

/** 取りに行く配布物の URL。 */
private const val RELEASE_URL = "https://example.com/cloudmarks-setup.exe"

/** エンジンへ渡す手前で止める要求の宛先。止め損ねても名前を引けないよう、`.invalid` を使う。 */
private const val STOPPED_REQUEST_URL = "https://example.invalid/cloudmarks-setup.exe"

/** 要求をエンジンへ渡す手前で止めたこと。 */
private class RequestStoppedBeforeEngine : RuntimeException("request stopped before the engine")

class UpdateHttpClientTest {
    private fun updateClient(engine: MockEngine): HttpClient = HttpClient(engine) { configureUpdateClient() }

    /** [timeouts] が、接続の確立と受信が止まったままのものだけを打ち切ること。 */
    private fun assertUpdateTimeouts(timeouts: HttpTimeoutConfig?) {
        val applied = assertNotNull(timeouts, "the request carried no timeouts")
        assertEquals(EXPECTED_CONNECT_TIMEOUT_MILLIS, applied.connectTimeoutMillis)
        assertEquals(EXPECTED_STALL_TIMEOUT_MILLIS, applied.socketTimeoutMillis)
        assertNull(applied.requestTimeoutMillis)
    }

    // 要求には、接続の確立（15 秒）と受信が止まったまま（60 秒）の打ち切りが付き、全体にかかる時間は縛らない
    @Test
    fun limitsConnectAndStallButNotWholeRequest() = runTest {
        var timeouts: HttpTimeoutConfig? = null
        val engine = MockEngine { request ->
            timeouts = request.getCapabilityOrNull(HttpTimeoutCapability)
            respond(content = "", status = HttpStatusCode.OK)
        }

        updateClient(engine).get(RELEASE_URL)

        assertUpdateTimeouts(timeouts)
    }

    // 作ったクライアント（CIO）も同じ設定を通る。要求はエンジンへ渡す手前で止めるので、通信しない
    @Test
    fun createdClientIsConfiguredForUpdates() = runTest {
        createUpdateHttpClient().use { client ->
            var timeouts: HttpTimeoutConfig? = null
            client.plugin(HttpSend).intercept { request ->
                timeouts = request.getCapabilityOrNull(HttpTimeoutCapability)
                throw RequestStoppedBeforeEngine()
            }

            assertFailsWith<RequestStoppedBeforeEngine> { client.get(STOPPED_REQUEST_URL) }

            assertUpdateTimeouts(timeouts)
        }
    }

    // https のままの転送は追い、転送先の応答を返す
    @Test
    fun followsHttpsRedirect() = runTest {
        val engine = MockEngine { request ->
            if (request.url.host == "example.com") {
                respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "https://objects.example.com/cloudmarks-setup.exe"),
                )
            } else {
                respond(content = "", status = HttpStatusCode.OK)
            }
        }

        val response = updateClient(engine).get(RELEASE_URL)

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(2, engine.requestHistory.size)
    }

    // https から http への転送は追わず、転送先へ要求を出さない
    @Test
    fun refusesHttpsDowngradeRedirect() = runTest {
        val engine = MockEngine {
            respond(
                content = "",
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, "http://objects.example.com/cloudmarks-setup.exe"),
            )
        }

        val response = updateClient(engine).get(RELEASE_URL)

        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals(1, engine.requestHistory.size)
    }
}
