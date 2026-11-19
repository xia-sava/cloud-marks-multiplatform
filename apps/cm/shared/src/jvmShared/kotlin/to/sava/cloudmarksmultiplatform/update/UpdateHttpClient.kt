package to.sava.cloudmarksmultiplatform.update

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeout

/** 接続を確立するまで待つ上限。 */
private const val UPDATE_CONNECT_TIMEOUT_MILLIS = 15_000L

/** 受信が止まったまま待つ上限。 */
private const val UPDATE_STALL_TIMEOUT_MILLIS = 60_000L

/**
 * アプリの更新で使う HTTP クライアントの設定。接続の確立と、受信が止まったままのものを打ち切り、https から http への転送は追わない。
 * 配布物は数十 MB あるので、全体にかかる時間は縛らない。https のままの転送は追う（GitHub Releases の配布物は別のホストへ転送される）。
 */
internal fun HttpClientConfig<*>.configureUpdateClient() {
    install(HttpTimeout) {
        connectTimeoutMillis = UPDATE_CONNECT_TIMEOUT_MILLIS
        socketTimeoutMillis = UPDATE_STALL_TIMEOUT_MILLIS
    }
    install(HttpRedirect) {
        allowHttpsDowngrade = false
    }
}

/**
 * アプリの更新で使う HTTP クライアントを作る。エンジンはデスクトップも Android も CIO。設定は [configureUpdateClient]。
 * 使い終えたら閉じる。
 */
internal fun createUpdateHttpClient(): HttpClient = HttpClient(CIO) { configureUpdateClient() }
