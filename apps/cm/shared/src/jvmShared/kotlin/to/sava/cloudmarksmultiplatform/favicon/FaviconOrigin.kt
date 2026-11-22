package to.sava.cloudmarksmultiplatform.favicon

import java.net.URI
import java.net.URISyntaxException

/** favicon を取りに行く URL の種類と、その既定のポート。 */
private val DEFAULT_PORTS = mapOf("http" to 80, "https" to 443)

/** ポートとして使える番号。 */
private val PORT_RANGE = 1..65_535

/** [URI.getPort] の、ポートが書かれていないことを表す値。 */
private const val NO_PORT = -1

/**
 * URL の先頭の、種類（scheme）と、`//` に続く authority の形。authority より後ろ（パス・クエリ・フラグメント）は見ないので、
 * そこに URL として正しくない文字があっても、オリジンは決まる。
 */
private val SCHEME_AND_AUTHORITY = Regex("""^([A-Za-z][A-Za-z0-9+.\-]*)://([^/?#\\]*)""")

/**
 * favicon のキーにするオリジン。種類、ホスト、ポートの組。
 *
 * @property scheme 種類。小文字の `http` か `https`
 * @property host 小文字のホスト。IPv6 のアドレスは `[` と `]` で囲む
 * @property port 既定でないポート。既定のポートなら null
 */
internal data class FaviconOrigin(val scheme: String, val host: String, val port: Int?) {
    /** ホストと、既定でなければポート（`example.com:8080`）。 */
    val authority: String get() = port?.let { "$host:$it" } ?: host

    /**
     * ホストを、オリジンの外の取り先（はてな・Google）へ渡してよいか。次のホストは、端末の中か手元のネットワークの中を指しうるので渡さない。
     * - `localhost` と、[LOCAL_NAME_SUFFIXES] のどれかで終わる名前
     * - ドットを含まない名前
     * - IP アドレスで書いたホスト（IPv4 も IPv6 も。公開のアドレスでも渡さない）
     *
     * 名前の末尾のドット（`example.com.`）は無いものとして見る。
     */
    val allowsExternalServices: Boolean
        get() {
            if (host.startsWith(IPV6_OPENING)) return false
            val name = host.removeSuffix(".")
            return '.' in name &&
                !name.all { it.isDigit() || it == '.' } &&
                LOCAL_NAME_SUFFIXES.none { name.endsWith(it) }
        }

    /** `https://example.com:8080` の形。キャッシュのファイルの名前も、これから決める。 */
    override fun toString(): String = "$scheme://$authority"
}

/** オリジンの外の取り先へ渡さない名前の末尾。端末の中か、手元のネットワークの中の名前に使う。 */
private val LOCAL_NAME_SUFFIXES =
    listOf(".localhost", ".local", ".internal", ".lan", ".home.arpa", ".test", ".localdomain", ".home", ".corp")

/** IPv6 のアドレスで書いたホストの頭。[java.net.URI.getHost] は IPv6 のアドレスを `[` と `]` で囲んで返す。 */
private const val IPV6_OPENING = "["

/**
 * [url] のオリジン。種類とホストは小文字にし、既定のポートは省く。ユーザー情報（`user@`）は含めない。
 * 種類が `http`・`https` でないか、ホストが無いか、解釈できなければ null。
 */
internal fun faviconOriginOf(url: String): FaviconOrigin? {
    val match = SCHEME_AND_AUTHORITY.find(url.trim()) ?: return null
    val scheme = match.groupValues[1].lowercase()
    val defaultPort = DEFAULT_PORTS[scheme] ?: return null
    val uri = try {
        URI("$scheme://${match.groupValues[2]}")
    } catch (e: URISyntaxException) {
        return null
    }
    val host = uri.host?.takeIf { it.isNotEmpty() }?.lowercase() ?: return null
    val port = uri.port.takeIf { it != NO_PORT && it != defaultPort }
    if (port != null && port !in PORT_RANGE) return null
    return FaviconOrigin(scheme, host, port)
}
