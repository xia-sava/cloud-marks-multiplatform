package to.sava.cloudmarksmultiplatform.host

import to.sava.cloudmarksmultiplatform.operations.AddBookmarks
import to.sava.cloudmarksmultiplatform.operations.AddFolder
import to.sava.cloudmarksmultiplatform.operations.NewItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Instant

private val ADDED: Instant = Instant.parse("2026-11-16T12:34:56.789Z")

class HostRequestTest {
    private fun parse(json: String): HostRequestParseResult = parseHostRequest(json.encodeToByteArray())

    private fun validRequest(json: String): HostRequest = assertIs<HostRequestParseResult.Valid>(parse(json), json).request

    // 見本の「フォルダを追加」を読むと、名前と、空のタイトルを含むタブの並びが、見本の順に入る
    @Test
    fun addFolderContractIsParsed() {
        val parsed = parseHostRequest(contractBytes("add-folder.json"))

        assertEquals(
            HostRequestParseResult.Valid(
                HostRequest.AddFolder(
                    name = "2026-11-16 21:30:15",
                    tabs = listOf(
                        HostTab("https://example.com/articles/1", "記事のタイトル"),
                        HostTab("https://example.org/", ""),
                    ),
                ),
            ),
            parsed,
        )
    }

    // 見本の「ブックマークを追加」を読むと、タブの並びが入る
    @Test
    fun addBookmarksContractIsParsed() {
        val parsed = parseHostRequest(contractBytes("add-bookmarks.json"))

        assertEquals(
            HostRequestParseResult.Valid(HostRequest.AddBookmarks(listOf(HostTab("https://example.net/page", "名前の欄で書き換えたタイトル")))),
            parsed,
        )
    }

    // 見本の「フォルダを追加」は、フォルダの ID を先に、項目の ID をタブの順に振った、フォルダとして追加する操作になる
    @Test
    fun addFolderContractBecomesAddFolder() {
        val ids = SequentialIds()

        val operation = validRequest(contractBytes("add-folder.json").decodeToString()).toOperation(ADDED, ids::next)

        assertEquals(
            AddFolder(
                folderId = hostId(1),
                name = "2026-11-16 21:30:15",
                items = listOf(
                    NewItem(hostId(2), "https://example.com/articles/1", "記事のタイトル"),
                    NewItem(hostId(3), "https://example.org/", ""),
                ),
                added = ADDED,
            ),
            operation,
        )
    }

    // 見本の「ブックマークを追加」は、項目の ID を振った、ブックマークとして追加する操作になる
    @Test
    fun addBookmarksContractBecomesAddBookmarks() {
        val ids = SequentialIds()

        val operation = validRequest(contractBytes("add-bookmarks.json").decodeToString()).toOperation(ADDED, ids::next)

        assertEquals(
            AddBookmarks(items = listOf(NewItem(hostId(1), "https://example.net/page", "名前の欄で書き換えたタイトル")), added = ADDED),
            operation,
        )
    }

    // 検証の規則に合わない要求は、どれも取り決めに合わないとする。理由には、要求の中の値を含めない
    @Test
    fun requestsAgainstRulesAreInvalid() {
        INVALID_REQUESTS.forEach { (name, json) ->
            val invalid = assertIs<HostRequestParseResult.Invalid>(parse(json), name)

            assertFalse(invalid.reason.contains("example.com"), "$name: ${invalid.reason}")
        }
    }

    // UTF-8 として読めない本体は、取り決めに合わないとする
    @Test
    fun invalidUtf8IsInvalid() {
        val body = """{"protocol":1,"type":"addBookmarks","tabs":[{"url":"https://example.com/","title":"""".encodeToByteArray() +
            byteArrayOf(0xC3.toByte(), 0x28) + "\"}]}".encodeToByteArray()

        assertIs<HostRequestParseResult.Invalid>(parseHostRequest(body))
    }

    // 表に無い欄は、要求にもタブにもあってよく、無視する
    @Test
    fun unknownFieldsAreIgnored() {
        val request = validRequest(
            """{"protocol":1,"type":"addFolder","name":"n","extra":{"a":[1]},"tabs":[{"url":"u","title":"t","favIconUrl":"x"}]}""",
        )

        assertEquals(HostRequest.AddFolder("n", listOf(HostTab("u", "t"))), request)
    }

    // ブックマークを追加する要求の name は、文字列でも、空でも、文字列でなくても無視する
    @Test
    fun addBookmarksIgnoresName() {
        listOf("\"n\"", "\"\"", "null", "1").forEach { name ->
            val request = validRequest("""{"protocol":1,"type":"addBookmarks","name":$name,"tabs":[{"url":"u","title":""}]}""")

            assertEquals(HostRequest.AddBookmarks(listOf(HostTab("u", ""))), request, name)
        }
    }

    // 空白だけの名前と URL は空ではないので、受け付ける。整形した JSON も受け付ける
    @Test
    fun blankButNonEmptyValuesAreAccepted() {
        val request = validRequest(
            """
            {
              "protocol": 1,
              "type": "addFolder",
              "name": " ",
              "tabs": [ { "url": " ", "title": " " } ]
            }
            """.trimIndent(),
        )

        assertEquals(HostRequest.AddFolder(" ", listOf(HostTab(" ", " "))), request)
    }
}
