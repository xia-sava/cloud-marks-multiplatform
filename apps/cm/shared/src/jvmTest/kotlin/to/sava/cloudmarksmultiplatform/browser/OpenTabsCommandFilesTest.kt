package to.sava.cloudmarksmultiplatform.browser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.findContracts
import to.sava.cloudmarksmultiplatform.settings.TabLoading
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.SecureRandom
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** 古いコマンドのファイルを決めるときの、いまの時刻。 */
private val NOW = Instant.parse("2026-11-17T12:00:00Z")

/** 小文字の 16 進で 32 文字。 */
private val TOKEN_PATTERN = Regex("^[0-9a-f]{32}$")

/** 見本の `response-command.json` の `command`。 */
private fun contractCommand(): JsonObject =
    Json.parseToJsonElement(findContracts("native-messaging").resolve("response-command.json").readText()).jsonObject.getValue("command").jsonObject

/** [directory] に、最後に書き換えたのが [NOW] の [age] 前のファイル [name] を置く。 */
private fun placeAged(directory: Path, name: String, age: Duration): Path =
    directory.resolve(name).also { file ->
        Files.writeString(file, "{}")
        Files.setLastModifiedTime(file, FileTime.fromMillis((NOW - age).toEpochMilliseconds()))
    }

class OpenTabsCommandFilesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val logs = mutableListOf<String>()

    // コマンドのファイルの中身は、見本の response-command.json の command と同じ形
    @Test
    fun encodedCommandMatchesContract() {
        val contract = contractCommand()
        val tabs = contract.getValue("tabs").jsonArray.map { tab ->
            BrowserTab(url = tab.jsonObject.getValue("url").jsonPrimitive.content, title = tab.jsonObject.getValue("title").jsonPrimitive.content)
        }
        val tabLoading = TabLoading(
            loadAtOnce = contract.getValue("loadAtOnce").jsonPrimitive.content.toInt(),
            loadAhead = contract.getValue("loadAhead").jsonPrimitive.content.toInt(),
        )

        val encoded = Json.parseToJsonElement(OpenTabsCommand(tabs, tabLoading).encode().decodeToString())

        assertEquals(contract, encoded)
    }

    // 書いたコマンドのファイルの中身は、ホストが読む関数で、書いたコマンドとして読める
    @Test
    fun writtenCommandParsesBack() {
        val directory = temporaryFolder.root.toPath().resolve("commands")
        val token = newCommandToken(SecureRandom())
        val command = OpenTabsCommand(listOf(BrowserTab("https://example.com/", "例"), BrowserTab("about:blank", "")), TabLoading(2, 5))

        writeCommandFile(directory, token, command)

        assertEquals(OpenTabsCommandParseResult.Valid(command), parseOpenTabsCommand(commandFileOf(directory, token).readBytes()))
    }

    // トークンは、乱数を小文字の 16 進にした 32 文字で（ホストが受け付ける形に合い）、作るたびに違う。呼び出しの URL はそのトークンを持つ
    @Test
    fun tokenIsLowercaseHex() {
        val random = SecureRandom()
        val tokens = List(2) { newCommandToken(random) }

        tokens.forEach {
            assertTrue(TOKEN_PATTERN.matches(it), it)
            assertTrue(COMMAND_TOKEN_FORMAT.matches(it), it)
        }
        assertNotEquals(tokens[0], tokens[1])
        assertEquals("https://cmm.invalid/knock?token=${tokens[0]}", knockUrl(tokens[0]))
    }

    // コマンドのファイルは、コマンドのディレクトリの <トークン>.json。ディレクトリが無ければ作り、一時ファイルを残さない
    @Test
    fun commandFileIsNamedByToken() {
        val directory = temporaryFolder.root.toPath().resolve("commands")
        val token = newCommandToken(SecureRandom())

        writeCommandFile(directory, token, OpenTabsCommand(listOf(BrowserTab("https://example.com/", "")), TabLoading()))

        assertEquals(listOf("$token.json"), directory.listDirectoryEntries().map { it.name })
        assertEquals(directory.resolve("$token.json"), commandFileOf(directory, token))
    }

    // 1 時間より前に書き換えた .json のファイルだけを消す。新しいファイルと、.json でないファイルは残す
    @Test
    fun staleCommandFilesAreDeleted() {
        val directory = temporaryFolder.newFolder("commands").toPath()
        val stale = placeAged(directory, "a.json", 61.minutes)
        val fresh = placeAged(directory, "b.json", 59.minutes)
        val temporary = placeAged(directory, "c.json.123.tmp", 61.minutes)
        val other = placeAged(directory, "d.txt", 61.minutes)

        deleteStaleCommandFiles(directory, NOW, logs::add)

        assertEquals(listOf(false, true, true, true), listOf(stale, fresh, temporary, other).map { it.exists() })
        assertEquals(emptyList(), logs)
    }

    // コマンドのディレクトリが無ければ、何もせず、ログにも書かない
    @Test
    fun missingCommandDirectoryIsIgnored() {
        deleteStaleCommandFiles(temporaryFolder.root.toPath().resolve("none"), NOW, logs::add)

        assertEquals(emptyList(), logs)
    }
}
