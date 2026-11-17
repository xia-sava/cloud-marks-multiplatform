package to.sava.cloudmarksmultiplatform.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebUrlTest {
    // http と https の URL は、大文字と小文字を区別せずに、既定のブラウザで開く種類とする
    @Test
    fun httpAndHttpsAreWebUrls() {
        listOf("http://example.com/", "https://example.com/a?b#c", "HTTPS://example.com/", "Http://example.com").forEach { url ->
            assertTrue(isWebUrl(url), url)
        }
    }

    // ほかの種類、種類の無いもの、種類の前に文字があるもの、種類の形でないものは、既定のブラウザで開く種類としない
    @Test
    fun othersAreNotWebUrls() {
        listOf(
            "ftp://example.com/",
            "javascript:alert(1)",
            "intent://example.com#Intent;end",
            "file:///etc/hosts",
            "httpsx://example.com/",
            "example.com",
            " https://example.com/",
            "-https://example.com/",
            "",
        ).forEach { url ->
            assertFalse(isWebUrl(url), url)
        }
    }

    // 既定のブラウザで開けなかったときは、理由を 1 行だけログに書き、詳細の無い失敗を返す
    @Test
    fun defaultBrowserFailureLogsReason() {
        val logs = mutableListOf<String>()

        val result = defaultBrowserFailed(logs::add, NOT_WEB_URL_REASON)

        assertEquals(BrowserOpenResult.LaunchFailed(detail = null), result)
        assertEquals(listOf("既定のブラウザで開けなかった: URL の種類が http・https でない"), logs)
    }
}
