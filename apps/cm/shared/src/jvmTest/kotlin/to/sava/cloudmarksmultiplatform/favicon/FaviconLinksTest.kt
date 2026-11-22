package to.sava.cloudmarksmultiplatform.favicon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/** HTML を取った URL。 */
private const val DOCUMENT_URL = "https://example.com/blog/entry/"

/** 後戻りの多い正規表現なら返らなくなる、繰り返しの数。 */
private const val HUGE_COUNT = 20_000

/** 後戻りしない読み方なら、十分に余裕のある時間。 */
private val QUICK_ENOUGH = 2.seconds

/** 1 つのタグとして見る長さの上限（8 KiB）。 */
private const val TAG_LENGTH_LIMIT = 8 * 1024

/** 失敗したときに出す、入力の頭の長さ。 */
private const val PREVIEW_LENGTH = 40

class FaviconLinksTest {
    private fun links(html: String, documentUrl: String = DOCUMENT_URL): List<String> = iconLinksOf(html, documentUrl)

    // rel のトークンに icon があるものを拾う。大文字と小文字は区別せず、icon でないトークン（apple-touch-icon など）だけのものは拾わない
    @Test
    fun picksLinksWhoseRelHasIconToken() {
        listOf(
            """<link rel="icon" href="/a.png">""" to listOf("https://example.com/a.png"),
            """<link rel="shortcut icon" href="/a.ico">""" to listOf("https://example.com/a.ico"),
            """<LINK REL="ICON" HREF="/a.png">""" to listOf("https://example.com/a.png"),
            """<link rel="  Shortcut	Icon " href="/a.png">""" to listOf("https://example.com/a.png"),
            """<link rel="apple-touch-icon" href="/a.png">""" to emptyList(),
            """<link rel="mask-icon" href="/a.svg">""" to emptyList(),
            """<link rel="stylesheet" href="/a.css">""" to emptyList(),
            """<link href="/a.png">""" to emptyList(),
        ).forEach { (html, expected) -> assertEquals(expected, links(html), html) }
    }

    // href は、HTML を取った URL を基準に解決する。相対・絶対パス・スキーム相対・絶対 URL のどれも読む
    @Test
    fun resolvesHrefAgainstDocumentUrl() {
        listOf(
            "icon.png" to "https://example.com/blog/entry/icon.png",
            "../icon.png" to "https://example.com/blog/icon.png",
            "/icon.png" to "https://example.com/icon.png",
            "//cdn.example.net/icon.png" to "https://cdn.example.net/icon.png",
            "http://other.example/icon.png" to "http://other.example/icon.png",
            "/icon.png?v=2" to "https://example.com/icon.png?v=2",
        ).forEach { (href, expected) -> assertEquals(listOf(expected), links("""<link rel="icon" href="$href">"""), href) }
    }

    // パスの無い URL を基準にしても、オリジンの直下として解決する
    @Test
    fun resolvesAgainstUrlWithoutPath() {
        assertEquals(listOf("https://example.com/icon.png"), links("""<link rel="icon" href="icon.png">""", "https://example.com"))
    }

    // http・https に解決されない href（data: など）、空の href、URL として正しくない href は飛ばす
    @Test
    fun skipsNonHttpHrefs() {
        val html = """
            <link rel="icon" href="data:image/png;base64,iVBORw0KGgo=">
            <link rel="icon" href="javascript:void(0)">
            <link rel="icon" href="">
            <link rel="icon" href="ftp://example.com/icon.png">
            <link rel="icon" href="/bad path|.png">
            <link rel="icon" href="/good.png">
        """.trimIndent()

        assertEquals(listOf("https://example.com/good.png"), links(html))
    }

    // 候補は文書の順に並べ、同じ URL は最初の 1 つだけにする
    @Test
    fun keepsDocumentOrderWithoutDuplicates() {
        val html = """
            <link rel="icon" sizes="32x32" href="/b.png">
            <link rel="stylesheet" href="/style.css">
            <link rel="icon" sizes="16x16" href="/a.png">
            <link rel="shortcut icon" href="https://example.com/b.png">
        """.trimIndent()

        assertEquals(listOf("https://example.com/b.png", "https://example.com/a.png"), links(html))
    }

    // 属性の順、引用符の種類（二重・一重・なし）、自己終了のタグ、値の中の > を問わずに読む
    @Test
    fun readsAttributeVariants() {
        listOf(
            """<link href="/a.png" rel="icon">""",
            """<link rel='icon' href='/a.png'>""",
            """<link rel=icon href=/a.png>""",
            """<link rel="icon" href="/a.png"/>""",
            """<link
                 rel="icon"
                 href="/a.png" >""",
            """<link type="image/png" title="a>b" rel="icon" href="/a.png">""",
        ).forEach { html -> assertEquals(listOf("https://example.com/a.png"), links(html), html) }
    }

    // 同じ名前の属性が重なれば、最初のものを使う
    @Test
    fun usesFirstOfDuplicateAttributes() {
        assertEquals(listOf("https://example.com/first.png"), links("""<link rel="icon" href="/first.png" href="/second.png">"""))
    }

    // href の文字参照を戻してから解決する
    @Test
    fun decodesCharacterReferences() {
        listOf(
            "/icon.png?a=1&amp;b=2" to "https://example.com/icon.png?a=1&b=2",
            "/icon&#46;png" to "https://example.com/icon.png",
            "/icon&#x2E;png" to "https://example.com/icon.png",
            "/icon.png?a=1&unknown;" to "https://example.com/icon.png?a=1&unknown;",
        ).forEach { (href, expected) -> assertEquals(listOf(expected), links("""<link rel="icon" href="$href">"""), href) }
    }

    // コメントの中の <link> と、<link> でないタグ（<linkx> など）は拾わない
    @Test
    fun ignoresCommentsAndOtherTags() {
        val html = """
            <!-- <link rel="icon" href="/commented.png"> -->
            <linkage rel="icon" href="/linkage.png">
            <link rel="icon" href="/real.png">
        """.trimIndent()

        assertEquals(listOf("https://example.com/real.png"), links(html))
    }

    // 2 万個の属性を持つタグ（8 KiB を超えるので読まない）、閉じない <link、> の無いまま並ぶ多数の <link、閉じない <!-- の多い入力でも、
    // すぐ返る
    @Test
    fun handlesPathologicalInputQuickly() {
        val manyAttributes = (1..HUGE_COUNT).joinToString(" ") { """a$it="v"""" }
        listOf(
            """<link $manyAttributes rel="icon" href="/a.png">""" to emptyList(),
            """<link $manyAttributes""" to emptyList(),
            """<link ${"x='y ".repeat(HUGE_COUNT)}""" to emptyList(),
            "<link ".repeat(HUGE_COUNT) to emptyList(),
            "<link ".repeat(HUGE_COUNT) + """<link rel="icon" href="/a.png">""" to listOf("https://example.com/a.png"),
            "<!--".repeat(HUGE_COUNT) + """<link rel="icon" href="/a.png">""" to emptyList(),
            "<!-- -->".repeat(HUGE_COUNT) + """<link rel="icon" href="/a.png">""" to listOf("https://example.com/a.png"),
        ).forEach { (html, expected) ->
            var found: List<String>? = null
            val elapsed = measureTime { found = links(html) }

            assertEquals(expected, found, html.take(PREVIEW_LENGTH))
            assertTrue(elapsed < QUICK_ENOUGH, "${html.take(PREVIEW_LENGTH)}: $elapsed")
        }
    }

    // 1 つのタグとして見るのは 8 KiB まで。それより短いタグは読み、長いタグは閉じていないものとして読まない
    @Test
    fun readsTagsUpToLengthLimit() {
        assertEquals(listOf("https://example.com/a.png"), links(iconTagOfLength(TAG_LENGTH_LIMIT)))
        assertEquals(emptyList(), links(iconTagOfLength(TAG_LENGTH_LIMIT + 1)))
    }

    /** `/a.png` を指す、長さがちょうど [length] 文字の `<link>` の開始タグ。長さは、属性の値を埋めて合わせる。 */
    private fun iconTagOfLength(length: Int): String {
        val head = """<link rel="icon" href="/a.png" data-x=""""
        val tail = """">"""
        return head + "x".repeat(length - head.length - tail.length) + tail
    }

    // 文書の URL を解釈できなければ、候補は無い
    @Test
    fun noLinksForUnparsableDocumentUrl() {
        assertEquals(emptyList(), links("""<link rel="icon" href="/a.png">""", "not a url with spaces"))
    }
}
