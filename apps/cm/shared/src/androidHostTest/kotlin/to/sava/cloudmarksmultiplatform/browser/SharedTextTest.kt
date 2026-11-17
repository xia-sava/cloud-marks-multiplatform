package to.sava.cloudmarksmultiplatform.browser

import kotlin.test.Test
import kotlin.test.assertEquals

/** 共有する項目の URL。 */
private const val URL = "https://example.com/a?b=c"

class SharedTextTest {
    // 共有するときは、URL を本文にし、タイトルを件名にする
    @Test
    fun urlIsTextAndTitleIsSubject() {
        assertEquals(SharedText(text = URL, subject = "見本の記事"), sharedTextOf(URL, "見本の記事"))
    }

    // タイトルが空なら、件名を付けない
    @Test
    fun emptyTitleHasNoSubject() {
        assertEquals(SharedText(text = URL, subject = null), sharedTextOf(URL, ""))
    }
}
