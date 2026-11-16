package to.sava.cloudmarksmultiplatform.logging

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

private val NOW: Instant = Instant.parse("2026-11-16T12:34:56.789Z")

private object FixedClock : Clock {
    override fun now(): Instant = NOW
}

class FileLogTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** まだ無いディレクトリ。 */
    private val directory: Path by lazy { temporaryFolder.root.toPath().resolve("logs") }

    /** [message] を書いたときの 1 行。 */
    private fun line(message: String): String = "$NOW $message\n"

    private fun currentText(): String = directory.resolve("cloudmarks.log").readText()

    private fun previousText(): String = directory.resolve("cloudmarks.log.1").readText()

    // 書いた順に、日時と文の 1 行ずつを追記する。ディレクトリが無ければ作る
    @Test
    fun appendsLinesInOrder() {
        val log = FileLog(directory, clock = FixedClock)

        log.write("first")
        log.write("second")

        assertEquals(line("first") + line("second"), currentText())
        assertEquals(listOf("cloudmarks.log"), directory.listDirectoryEntries().map { it.name })
    }

    // 追記すると上限を超えるときは、いまのファイルを前のログのファイルへ替えてから書く。
    // もう一度超えると前のログのファイルを置き換え、残すのは 1 世代だけ
    @Test
    fun rotatesKeepingOneGeneration() {
        val maxBytes = line("aaaa").length * 2L
        val log = FileLog(directory, maxBytes = maxBytes, clock = FixedClock)

        log.write("aaaa")
        log.write("bbbb")
        assertEquals(line("aaaa") + line("bbbb"), currentText())

        log.write("cccc")
        assertEquals(line("cccc"), currentText())
        assertEquals(line("aaaa") + line("bbbb"), previousText())

        log.write("dddd")
        log.write("eeee")
        assertEquals(line("eeee"), currentText())
        assertEquals(line("cccc") + line("dddd"), previousText())
        assertEquals(listOf("cloudmarks.log", "cloudmarks.log.1"), directory.listDirectoryEntries().map { it.name }.sorted())
    }

    // 1 行だけで上限を超えても、空のファイルは切り替えずにそのまま書く
    @Test
    fun writesLongLineIntoEmptyFile() {
        val log = FileLog(directory, maxBytes = 1, clock = FixedClock)

        log.write("longer than the limit")

        assertEquals(line("longer than the limit"), currentText())
        assertEquals(listOf("cloudmarks.log"), directory.listDirectoryEntries().map { it.name })
    }
}
