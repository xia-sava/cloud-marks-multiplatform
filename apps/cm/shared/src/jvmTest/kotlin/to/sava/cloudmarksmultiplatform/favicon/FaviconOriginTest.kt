package to.sava.cloudmarksmultiplatform.favicon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FaviconOriginTest {
    // オリジンは種類・ホスト・ポートの組。種類とホストは小文字にし、既定のポートは省く。パス・クエリ・フラグメント・ユーザー情報は含めない
    @Test
    fun normalizesOrigin() {
        listOf(
            "https://example.com/" to "https://example.com",
            "https://example.com" to "https://example.com",
            "HTTPS://Example.COM/Path?Q=1#F" to "https://example.com",
            "https://example.com:443/" to "https://example.com",
            "http://example.com:80/" to "http://example.com",
            "http://example.com:443/" to "http://example.com:443",
            "https://example.com:8443/a" to "https://example.com:8443",
            "https://user:secret@example.com/" to "https://example.com",
            "http://[::1]:8080/" to "http://[::1]:8080",
            "  https://example.com/  " to "https://example.com",
            "https://example.com?query" to "https://example.com",
            "https://example.com#fragment" to "https://example.com",
        ).forEach { (url, origin) -> assertEquals(origin, faviconOriginOf(url)?.toString(), url) }
    }

    // オリジンの外（パス・クエリ）に URL として正しくない文字があっても、オリジンは決まる
    @Test
    fun ignoresInvalidCharactersAfterAuthority() {
        assertEquals(
            FaviconOrigin("https", "example.com", null),
            faviconOriginOf("https://example.com/search?q=a b|c{d}"),
        )
    }

    // 種類が http・https でない URL、ホストの無い URL、解釈できない URL にはオリジンが無い
    @Test
    fun rejectsNonWebUrls() {
        listOf(
            "ftp://example.com/",
            "file:///C:/index.html",
            "javascript:alert(1)",
            "mailto:someone@example.com",
            "about:blank",
            "https:///path",
            "https://",
            "http:example.com",
            "example.com",
            "https://exa mple.com/",
            "https://example.com:99999/",
            "https://example.com:0/",
            "not a url",
            "",
        ).forEach { url -> assertNull(faviconOriginOf(url), url) }
    }

    // localhost、端末や手元のネットワークの名前、ドットの無い名前、IP アドレス（公開のものも）は、オリジンの外の取り先へ渡さない。
    // ほかの名前は渡す。名前の末尾のドットは無いものとして見る
    @Test
    fun decidesWhetherHostGoesToExternalServices() {
        listOf(
            "https://example.com/" to true,
            "https://www.example.co.jp/" to true,
            "https://example.com./" to true,
            "https://local.example.com/" to true,
            "https://192.168.example/" to true,
            "http://localhost:8080/" to false,
            "http://app.localhost/" to false,
            "http://printer.local/" to false,
            "http://service.internal/" to false,
            "http://nas.lan/" to false,
            "http://router.home.arpa/" to false,
            "http://app.test/" to false,
            "http://server.localdomain/" to false,
            "http://nas.home/" to false,
            "http://wiki.corp/" to false,
            "https://example.test.com/" to true,
            "http://intranet/" to false,
            "http://intranet./" to false,
            "http://192.168.1.10/" to false,
            "https://8.8.8.8/" to false,
            "http://[::1]:8080/" to false,
            "https://[2001:4860:4860::8888]/" to false,
        ).forEach { (url, allowed) ->
            assertEquals(allowed, assertNotNull(faviconOriginOf(url), url).allowsExternalServices, url)
        }
    }

    // authority は、既定でないポートだけを持つ
    @Test
    fun authorityCarriesOnlyNonDefaultPort() {
        assertEquals("example.com", FaviconOrigin("https", "example.com", null).authority)
        assertEquals("example.com:8080", FaviconOrigin("http", "example.com", 8080).authority)
    }
}
