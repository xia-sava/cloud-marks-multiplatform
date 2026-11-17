package to.sava.cloudmarksmultiplatform.host

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val CHROME = """C:\Program Files\Google\Chrome\Application\chrome.exe"""
private const val FIREFOX = """C:\Program Files\Mozilla Firefox\firefox.exe"""

class BrowsersWithExtensionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val logs = mutableListOf<String>()

    /** まだ無い、記録のファイルの場所。親のディレクトリも無い。 */
    private fun recordFile(): Path = temporaryFolder.newFolder().toPath().resolve("state").resolve("browsers-with-extension.json")

    private fun record(file: Path, executable: String) = recordBrowserWithExtension(file, executable, logs::add)

    private fun Path.text(): String = Files.readString(this)

    // 配布物では、自分（CloudMarks.exe）と同じ名前の祖先と cmd.exe を飛ばして、最初の該当する祖先の実行ファイルを返す
    @Test
    fun launcherAndCommandInterpreterAreSkipped() {
        val ancestors = sequenceOf("""C:\App\CloudMarks.exe""", """C:\Windows\System32\cmd.exe""", CHROME, """C:\Windows\explorer.exe""")

        assertEquals(CHROME, findBrowserExecutable(ancestors, """C:\App\CloudMarks.exe"""))
    }

    // 開発用のバッチでは、自分が java.exe になり、別の場所の java.exe の祖先も名前の一致で飛ばす
    @Test
    fun javaAncestorsAreSkippedWhenOwnIsJava() {
        val ancestors = sequenceOf("""C:\Program Files\Java\bin\java.exe""", """C:\Windows\System32\cmd.exe""", FIREFOX)

        assertEquals(FIREFOX, findBrowserExecutable(ancestors, """C:\Users\x\jdk\bin\java.exe"""))
    }

    // 名前は大文字小文字によらず比べ、パスの区切りは \ と / のどちらでもよい。自分と違う名前の祖先は飛ばさない
    @Test
    fun namesAreComparedIgnoringCaseAndSeparator() {
        val ancestors = sequenceOf("C:/App/CLOUDMARKS.EXE", """C:\Windows\System32\CMD.EXE""", "C:/Program Files/Java/bin/java.exe", CHROME)

        assertEquals("C:/Program Files/Java/bin/java.exe", findBrowserExecutable(ancestors, """C:\app\cloudmarks.exe"""))
    }

    // 自分の実行ファイルが分からなくても、cmd.exe は飛ばす
    @Test
    fun unknownOwnExecutableStillSkipsCommandInterpreter() {
        assertEquals(CHROME, findBrowserExecutable(sequenceOf("""C:\Windows\System32\cmd.exe""", CHROME), null))
    }

    // 祖先が仲介のプロセスだけなら、見つからない
    @Test
    fun onlyRelayAncestorsFindNothing() {
        val own = """C:\App\CloudMarks.exe"""

        assertEquals(null, findBrowserExecutable(sequenceOf(own, """C:\Windows\System32\cmd.exe"""), own))
        assertEquals(null, findBrowserExecutable(emptySequence(), own))
    }

    // 該当する祖先より前に、実行ファイルの分からない祖先があれば、それより上は見ずに、見つからないとする
    @Test
    fun unknownExecutableStopsTheSearch() {
        assertEquals(null, findBrowserExecutable(sequenceOf("""C:\Windows\System32\cmd.exe""", null, CHROME), null))
    }

    // 該当する祖先が先に見つかれば、その後ろの実行ファイルの分からない祖先は見ない
    @Test
    fun ancestorsAfterTheBrowserAreNotVisited() {
        val visited = mutableListOf<String?>()
        val ancestors = sequenceOf(CHROME, null, "x").onEach { visited += it }

        assertEquals(CHROME, findBrowserExecutable(ancestors, null))
        assertEquals(listOf<String?>(CHROME), visited)
    }

    // 記録が無ければ、親のディレクトリごと作り、実行ファイルのパスを小文字にして書く
    @Test
    fun recordCreatesFileWithLowercasedPath() {
        val file = recordFile()

        record(file, CHROME)

        assertEquals("""{"browsers":["c:\\program files\\google\\chrome\\application\\chrome.exe"]}""", file.text())
        assertEquals(emptyList(), logs)
    }

    // 別のブラウザは書き足し、既にあるものは、大文字小文字が違っても足さない
    @Test
    fun recordAppendsOnlyNewBrowsers() {
        val file = recordFile()

        record(file, CHROME)
        record(file, FIREFOX)
        record(file, CHROME.uppercase())
        record(file, CHROME)

        assertEquals(
            """{"browsers":["c:\\program files\\google\\chrome\\application\\chrome.exe",""" +
                """"c:\\program files\\mozilla firefox\\firefox.exe"]}""",
            file.text(),
        )
        assertEquals(emptyList(), logs)
    }

    // 既にあるものを足そうとしたときは、ファイルを書き換えない
    @Test
    fun recordDoesNotRewriteWhenAlreadyKnown() {
        val file = recordFile()
        Files.createDirectories(file.parent)
        val original = """{ "browsers": [ "c:\\a\\chrome.exe" ] }"""
        Files.writeString(file, original)

        record(file, """C:\A\Chrome.exe""")

        assertEquals(original, file.text())
    }

    // 壊れた記録は、空として読んで書き直し、ログに書く。壊れ方は、JSON でない・オブジェクトでない・browsers が無い・
    // browsers が配列でない・文字列でない要素がある・UTF-8 でない
    @Test
    fun brokenRecordIsRewritten() {
        val cases = listOf(
            "JSON でない".encodeToByteArray(),
            "[]".encodeToByteArray(),
            """{"other":1}""".encodeToByteArray(),
            """{"browsers":"c:\\a.exe"}""".encodeToByteArray(),
            """{"browsers":["c:\\a.exe",1]}""".encodeToByteArray(),
            byteArrayOf(0xC3.toByte(), 0x28),
        )

        cases.forEachIndexed { index, content ->
            val file = recordFile().also { Files.createDirectories(it.parent) }
            Files.write(file, content)
            logs.clear()

            record(file, CHROME)

            assertEquals("""{"browsers":["c:\\program files\\google\\chrome\\application\\chrome.exe"]}""", file.text(), "$index")
            assertEquals(1, logs.size, "$index")
            assertTrue(logs.single().startsWith(HOST_LOG_PREFIX), "$index")
        }
    }

    // 読めない（記録の場所がディレクトリ）ときは、何も書き換えず、ログに書くだけで例外にしない
    @Test
    fun unreadableRecordIsLoggedAndLeftAlone() {
        val file = recordFile().also { Files.createDirectories(it) }

        record(file, CHROME)

        assertTrue(Files.isDirectory(file))
        assertEquals(1, logs.size)
    }

    // 書けない（親のディレクトリを置くべき場所にファイルがある）ときは、ログに書くだけで例外にしない
    @Test
    fun unwritableRecordIsLogged() {
        val file = recordFile()
        Files.writeString(file.parent, "")

        record(file, CHROME)

        assertEquals(1, logs.size)
        assertFalse(logs.single().contains(CHROME), logs.single())
    }

    // 実際のプロセスの祖先をたどっても、例外にならない（ホストを起動した親の実行ファイルは、環境によって分かるとは限らない）
    @Test
    fun realAncestorsCanBeWalked() {
        processAncestorExecutables().toList()
    }
}
