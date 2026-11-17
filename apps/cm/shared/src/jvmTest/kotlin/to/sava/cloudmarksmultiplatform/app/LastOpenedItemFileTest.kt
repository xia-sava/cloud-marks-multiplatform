package to.sava.cloudmarksmultiplatform.app

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.sync.itemId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LastOpenedItemFileTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** まだ無いディレクトリの下の、最後に開いた項目の記録のファイル。 */
    private val directory: Path by lazy { temporaryFolder.root.toPath().resolve("state") }
    private val path: Path by lazy { directory.resolve("last-opened-item.json") }
    private val logs = mutableListOf<String>()

    private fun file(): LastOpenedItemFile = LastOpenedItemFile(path) { logs += it }

    /** [text] を、記録のファイルとしてそのまま置く。 */
    private fun placeRaw(text: String) {
        Files.createDirectories(directory)
        Files.writeString(path, text)
    }

    // 書いた ID を読み直すと、同じ ID が返る。書き直すと新しい ID に替わる。親のディレクトリが無ければ作り、一時ファイルを残さない
    @Test
    fun writtenIdReadsBack() {
        file().write(itemId(1))
        assertEquals(itemId(1), file().read())

        file().write(itemId(2))
        assertEquals(itemId(2), file().read())

        assertEquals(listOf("last-opened-item.json"), directory.listDirectoryEntries().map { it.name })
        assertEquals(emptyList(), logs)
    }

    // ファイルには、ID を itemId の欄に書く
    @Test
    fun idIsWrittenAsJson() {
        file().write(itemId(1))

        assertEquals("""{"itemId":"${itemId(1)}"}""", Files.readString(path))
    }

    // ファイルが無ければ、覚えていないもの（null）とする。ログには書かない
    @Test
    fun missingFileIsNull() {
        assertNull(file().read())
        assertEquals(emptyList(), logs)
    }

    // 記録の形でなければ（JSON でない・オブジェクトでない・itemId が無いか文字列でない・小文字の UUID の形でない）、覚えていないものとし、
    // ログに書く
    @Test
    fun brokenRecordIsNullAndLogged() {
        val cases = listOf(
            "not json",
            "[]",
            "{}",
            """{"itemId":1}""",
            """{"itemId":null}""",
            """{"itemId":"not-a-uuid"}""",
            """{"itemId":"ABCDEF00-0000-4000-8000-000000000001"}""",
        )
        cases.forEach { text ->
            logs.clear()
            placeRaw(text)

            assertNull(file().read(), text)
            assertEquals(1, logs.size, text)
        }
    }

    // 知らない欄は無視して読む
    @Test
    fun unknownKeysAreIgnored() {
        placeRaw("""{"itemId":"${itemId(1)}","other":true}""")

        assertEquals(itemId(1), file().read())
        assertEquals(emptyList(), logs)
    }

    // 読めなければ（記録の場所がディレクトリ）、例外を投げずに覚えていないものとし、ログに書く
    @Test
    fun readFailureIsNullAndLogged() {
        Files.createDirectories(path)

        assertNull(file().read())
        assertEquals(1, logs.size)
        assertTrue(logs.single().startsWith("最後に開いた項目の記録を読めなかった"))
    }

    // 書けなければ（置き換え先が、中身のあるディレクトリ）、例外を投げずにログに書く
    @Test
    fun writeFailureIsLogged() {
        Files.createDirectories(path.resolve("child"))

        file().write(itemId(1))

        assertEquals(1, logs.size)
        assertTrue(logs.single().startsWith("最後に開いた項目の記録を書けなかった"))
    }
}
