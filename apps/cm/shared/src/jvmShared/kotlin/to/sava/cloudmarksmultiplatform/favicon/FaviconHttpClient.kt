package to.sava.cloudmarksmultiplatform.favicon

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeout

/** 接続を確立するまで待つ上限。 */
private const val FAVICON_CONNECT_TIMEOUT_MILLIS = 5_000L

/** 受信が止まったまま待つ上限。 */
private const val FAVICON_STALL_TIMEOUT_MILLIS = 5_000L

/**
 * favicon を取る HTTP クライアントの設定。接続の確立と、受信が止まったままのものを打ち切り、転送は追う。
 * 1 つのオリジンにかける時間の全体は、取る側（[FaviconFetcher]）が縛る。
 */
internal fun HttpClientConfig<*>.configureFaviconClient() {
    install(HttpTimeout) {
        connectTimeoutMillis = FAVICON_CONNECT_TIMEOUT_MILLIS
        socketTimeoutMillis = FAVICON_STALL_TIMEOUT_MILLIS
    }
    install(HttpRedirect) {
        allowHttpsDowngrade = true
    }
}

/** favicon を取る HTTP クライアントを作る。エンジンはデスクトップも Android も CIO。設定は [configureFaviconClient]。使い終えたら閉じる。 */
internal fun createFaviconHttpClient(): HttpClient = HttpClient(CIO) { configureFaviconClient() }
