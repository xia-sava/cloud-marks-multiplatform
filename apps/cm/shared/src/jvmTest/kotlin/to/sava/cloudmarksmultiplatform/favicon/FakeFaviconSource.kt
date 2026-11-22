package to.sava.cloudmarksmultiplatform.favicon

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 画面とコントローラのテストに使う、偽の [FaviconSource]。URL ごとの状態を [set] で決め、決めていない URL には [default] を返す。
 * [close] を呼んだことを [closed] に、[faviconOf] を呼んだ回数を [callsOf] に残す。
 */
internal class FakeFaviconSource(private val default: Favicon = Favicon.Loading) : FaviconSource, AutoCloseable {
    private val flows = mutableMapOf<String, MutableStateFlow<Favicon>>()
    private val calls = mutableMapOf<String, Int>()

    /** [close] を呼んだか。 */
    var closed = false
        private set

    /** [url] の favicon の状態を [favicon] にする。画面が読んでいれば、画面も替わる。 */
    fun set(url: String, favicon: Favicon) {
        flowOf(url).value = favicon
    }

    /** [url] について [faviconOf] を呼んだ回数。 */
    fun callsOf(url: String): Int = calls[url] ?: 0

    override fun faviconOf(url: String): StateFlow<Favicon> {
        calls[url] = callsOf(url) + 1
        return flowOf(url)
    }

    override fun close() {
        closed = true
    }

    private fun flowOf(url: String): MutableStateFlow<Favicon> = flows.getOrPut(url) { MutableStateFlow(default) }
}
