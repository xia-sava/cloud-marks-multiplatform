package to.sava.cloudmarksmultiplatform.host

import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.browser.BrowserTab
import to.sava.cloudmarksmultiplatform.browser.OpenTabsCommand
import to.sava.cloudmarksmultiplatform.browser.OpenTabsCommandParseResult
import to.sava.cloudmarksmultiplatform.browser.parseOpenTabsCommand
import to.sava.cloudmarksmultiplatform.settings.TabLoading
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val VALID_COMMAND =
    """{"type":"openTabs","tabs":[{"url":"https://example.com/","title":"Example"}],"loadAtOnce":3,"loadAhead":20}"""

/** 場合の名前と、コマンドの形に合わないコマンドの本体。検証の規則（`docs/native-messaging.md` の「コマンドの形」）ごとに 1 つ以上。 */
private val INVALID_COMMANDS: List<Pair<String, String>> = listOf(
    "type が無い" to """{"tabs":[{"url":"u","title":"t"}],"loadAtOnce":3,"loadAhead":20}""",
    "type が知らない値" to """{"type":"openWindow","tabs":[{"url":"u","title":"t"}],"loadAtOnce":3,"loadAhead":20}""",
    "type が文字列でない" to """{"type":1,"tabs":[{"url":"u","title":"t"}],"loadAtOnce":3,"loadAhead":20}""",
    "tabs が無い" to """{"type":"openTabs","loadAtOnce":3,"loadAhead":20}""",
    "tabs が配列でない" to """{"type":"openTabs","tabs":"u","loadAtOnce":3,"loadAhead":20}""",
    "tabs が空" to """{"type":"openTabs","tabs":[],"loadAtOnce":3,"loadAhead":20}""",
    "タブがオブジェクトでない" to """{"type":"openTabs","tabs":["u"],"loadAtOnce":3,"loadAhead":20}""",
    "url が無い" to """{"type":"openTabs","tabs":[{"title":"t"}],"loadAtOnce":3,"loadAhead":20}""",
    "url が空" to """{"type":"openTabs","tabs":[{"url":"","title":"t"}],"loadAtOnce":3,"loadAhead":20}""",
    "url が文字列でない" to """{"type":"openTabs","tabs":[{"url":1,"title":"t"}],"loadAtOnce":3,"loadAhead":20}""",
    "title が無い" to """{"type":"openTabs","tabs":[{"url":"u"}],"loadAtOnce":3,"loadAhead":20}""",
    "title が文字列でない" to """{"type":"openTabs","tabs":[{"url":"u","title":null}],"loadAtOnce":3,"loadAhead":20}""",
    "loadAtOnce が無い" to """{"type":"openTabs","tabs":[{"url":"u","title":"t"}],"loadAhead":20}""",
    "loadAtOnce が 0" to """{"type":"openTabs","tabs":[{"url":"u","title":"t"}],"loadAtOnce":0,"loadAhead":20}""",
    "loadAtOnce が負" to """{"type":"openTabs","tabs":[{"url":"u","title":"t"}],"loadAtOnce":-1,"loadAhead":20}""",
    "loadAtOnce が小数" to """{"type":"openTabs","tabs":[{"url":"u","title":"t"}],"loadAtOnce":3.5,"loadAhead":20}""",
    "loadAtOnce が小数の字句" to """{"type":"openTabs","tabs":[{"url":"u","title":"t"}],"loadAtOnce":3.0,"loadAhead":20}""",
    "loadAtOnce が文字列" to """{"type":"openTabs","tabs":[{"url":"u","title":"t"}],"loadAtOnce":"3","loadAhead":20}""",
    "loadAhead が無い" to """{"type":"openTabs","tabs":[{"url":"u","title":"t"}],"loadAtOnce":3}""",
    "loadAhead が負" to """{"type":"openTabs","tabs":[{"url":"u","title":"t"}],"loadAtOnce":3,"loadAhead":-1}""",
    "loadAhead が小数" to """{"type":"openTabs","tabs":[{"url":"u","title":"t"}],"loadAtOnce":3,"loadAhead":1.5}""",
    "loadAhead が文字列" to """{"type":"openTabs","tabs":[{"url":"u","title":"t"}],"loadAtOnce":3,"loadAhead":"20"}""",
    "loadAhead が null" to """{"type":"openTabs","tabs":[{"url":"u","title":"t"}],"loadAtOnce":3,"loadAhead":null}""",
    "loadAhead が 32 ビットの整数に収まらない" to
        """{"type":"openTabs","tabs":[{"url":"u","title":"t"}],"loadAtOnce":3,"loadAhead":2147483648}""",
    "JSON として読めない" to """{"type":"openTabs",""",
    "JSON のオブジェクトでない" to """[]""",
)

class HostCommandTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val logs = mutableListOf<String>()

    private fun commandDirectory(): Path = temporaryFolder.newFolder().toPath()

    private fun parse(json: String): OpenTabsCommandParseResult = parseOpenTabsCommand(json.encodeToByteArray())

    // 見本の応答の command を読み、応答にすると、見本と JSON として同じになる
    @Test
    fun commandContractRoundTripsToContract() {
        val contract = jsonOf(contractBytes("response-command.json"))
        val command = assertIs<OpenTabsCommandParseResult.Valid>(
            parseOpenTabsCommand(contract.jsonObject.getValue("command").toString().encodeToByteArray()),
        ).command

        assertEquals(
            OpenTabsCommand(
                tabs = listOf(BrowserTab("https://example.com/", "Example"), BrowserTab("https://example.org/articles/1", "")),
                tabLoading = TabLoading(loadAtOnce = 3, loadAhead = 20),
            ),
            command,
        )
        assertEquals(contract, jsonOf(encodeHostResponse(HostResponse.CommandTaken(command))))
    }

    // loadAhead は 0（すべてのタブを順に読み込む）を受け付ける。loadAtOnce は 0 を受け付けない
    @Test
    fun zeroLoadAheadIsAccepted() {
        val parsed = parse("""{"type":"openTabs","tabs":[{"url":"u","title":"t"}],"loadAtOnce":1,"loadAhead":0}""")

        assertEquals(
            OpenTabsCommandParseResult.Valid(OpenTabsCommand(listOf(BrowserTab("u", "t")), TabLoading(loadAtOnce = 1, loadAhead = 0))),
            parsed,
        )
    }

    // コマンドの形の規則に合わないコマンドは、どれも取り決めに合わないとする。理由には、コマンドの中の値を含めない
    @Test
    fun commandsAgainstRulesAreInvalid() {
        INVALID_COMMANDS.forEach { (name, json) ->
            val invalid = assertIs<OpenTabsCommandParseResult.Invalid>(parse(json), name)

            assertFalse(invalid.reason.contains("openWindow"), "$name: ${invalid.reason}")
        }
    }

    // 表に無い欄はあってよく、無視する。空のタイトル・整形した JSON・32 ビットの整数のいちばん大きな値も受け付ける
    @Test
    fun unknownFieldsAreIgnoredAndLargeCountsAreAccepted() {
        val parsed = parse(
            """
            {
              "type": "openTabs",
              "extra": [1],
              "tabs": [ { "url": " ", "title": "", "favIconUrl": "x" } ],
              "loadAtOnce": 1,
              "loadAhead": 2147483647
            }
            """.trimIndent(),
        )

        assertEquals(
            OpenTabsCommandParseResult.Valid(OpenTabsCommand(listOf(BrowserTab(" ", "")), TabLoading(1, Int.MAX_VALUE))),
            parsed,
        )
    }

    // 合っているコマンドのファイルを受け取ると、中身がコマンドとして返り、ファイルが消える。ログには何も書かない
    @Test
    fun validCommandFileIsReturnedAndDeleted() {
        val directory = commandDirectory()
        val file = directory.resolve("$TOKEN.json").also { Files.writeString(it, VALID_COMMAND) }

        val command = takeCommandFile(directory, TOKEN, logs::add)

        assertEquals(
            OpenTabsCommand(listOf(BrowserTab("https://example.com/", "Example")), TabLoading(loadAtOnce = 3, loadAhead = 20)),
            command,
        )
        assertFalse(Files.exists(file))
        assertEquals(emptyList(), logs)
    }

    // ファイルが無い（ディレクトリごと無い場合を含む）ときは、null を返し、ログには何も書かない
    @Test
    fun missingFileIsNull() {
        assertEquals(null, takeCommandFile(commandDirectory(), TOKEN, logs::add))
        assertEquals(null, takeCommandFile(commandDirectory().resolve("none"), TOKEN, logs::add))
        assertEquals(emptyList(), logs)
    }

    // コマンドの形に合わないファイルは、null を返してログに書き、ファイルを消さない。ログに URL・タイトルを書かない
    @Test
    fun invalidCommandFileIsKeptAndLoggedWithoutValues() {
        INVALID_COMMANDS.forEach { (name, json) ->
            val directory = commandDirectory()
            val file = directory.resolve("$TOKEN.json")
            Files.writeString(file, json.replace("\"u\"", "\"https://secret.example/\"").replace("\"t\"", "\"秘密のタイトル\""))
            logs.clear()

            assertEquals(null, takeCommandFile(directory, TOKEN, logs::add), name)

            assertTrue(Files.exists(file), name)
            val log = logs.single()
            assertTrue(log.startsWith(HOST_LOG_PREFIX), name)
            assertFalse(log.contains("secret.example") || log.contains("秘密のタイトル"), "$name: $log")
        }
    }

    // UTF-8 として読めないファイルは、コマンドの形に合わないものとして、消さずに null を返す
    @Test
    fun invalidUtf8FileIsKept() {
        val directory = commandDirectory()
        val file = directory.resolve("$TOKEN.json")
        Files.write(file, VALID_COMMAND.encodeToByteArray() + byteArrayOf(0xC3.toByte(), 0x28))

        assertEquals(null, takeCommandFile(directory, TOKEN, logs::add))
        assertTrue(Files.exists(file))
        assertEquals(1, logs.size)
    }

    // 応答が 1 MB を超えるコマンドは、消さずに null を返してログに書く。ちょうど上限に収まるものは返す
    @Test
    fun oversizedResponseIsKept() {
        val directory = commandDirectory()
        val file = directory.resolve("$TOKEN.json")
        fun commandWithTitleOf(length: Int) =
            """{"type":"openTabs","tabs":[{"url":"u","title":"${"a".repeat(length)}"}],"loadAtOnce":3,"loadAhead":20}"""
        val overhead = encodeHostResponse(HostResponse.CommandTaken(OpenTabsCommand(listOf(BrowserTab("u", "")), TabLoading(3, 20)))).size

        Files.writeString(file, commandWithTitleOf(MAX_RESPONSE_BYTES - overhead + 1))
        assertEquals(null, takeCommandFile(directory, TOKEN, logs::add))
        assertTrue(Files.exists(file))
        assertEquals(1, logs.size)

        logs.clear()
        Files.writeString(file, commandWithTitleOf(MAX_RESPONSE_BYTES - overhead))
        assertEquals(MAX_RESPONSE_BYTES - overhead, takeCommandFile(directory, TOKEN, logs::add)?.tabs?.single()?.title?.length)
        assertFalse(Files.exists(file))
        assertEquals(emptyList(), logs)
    }

    // 読んだ後で、別のホストが先にファイルを消していたときは、コマンドを返さない（同じコマンドを 2 回返さない）。ログには何も書かない
    @Test
    fun commandTakenByAnotherHostIsNull() {
        val directory = commandDirectory()
        Files.writeString(directory.resolve("$TOKEN.json"), VALID_COMMAND)

        assertEquals(null, takeCommandFile(directory, TOKEN, logs::add, deleteIfExists = { false }))
        assertEquals(emptyList(), logs)
    }

    // ファイルを消せなかったときは、ログに書いて、コマンドは返す。ログにファイルの場所（トークンを含む）を書かない
    @Test
    fun deleteFailureIsLoggedAndCommandIsReturned() {
        val directory = commandDirectory()
        Files.writeString(directory.resolve("$TOKEN.json"), VALID_COMMAND)

        val command = takeCommandFile(directory, TOKEN, logs::add, deleteIfExists = { throw AccessDeniedException(it.toString()) })

        assertEquals(1, command?.tabs?.size)
        assertEquals(1, logs.size)
        assertFalse(logs.single().contains(TOKEN), logs.single())
    }

    // ファイルを読めない（ファイルの場所がディレクトリ）ときは、null を返してログに書き、何も消さない
    @Test
    fun unreadableFileIsNullAndLogged() {
        val directory = commandDirectory()
        val file = Files.createDirectory(directory.resolve("$TOKEN.json"))

        assertEquals(null, takeCommandFile(directory, TOKEN, logs::add))

        assertTrue(Files.exists(file))
        assertEquals(1, logs.size)
    }
}
