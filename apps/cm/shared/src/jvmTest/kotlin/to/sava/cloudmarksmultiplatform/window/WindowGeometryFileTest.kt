package to.sava.cloudmarksmultiplatform.window

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 最大化していない、ウィンドウの位置と大きさ。 */
private fun geometry(x: Int = 100, y: Int = 50, width: Int = 1000, height: Int = 720, maximized: Boolean = false) =
    WindowGeometry(x = x, y = y, width = width, height = height, maximized = maximized)

class WindowGeometryFileTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** まだ無いディレクトリの下の、ウィンドウの位置と大きさの記録のファイル。 */
    private val directory: Path by lazy { temporaryFolder.root.toPath().resolve("state") }
    private val path: Path by lazy { directory.resolve("window.json") }
    private val logs = mutableListOf<String>()

    private fun file(): WindowGeometryFile = WindowGeometryFile(path) { logs += it }

    /** [text] を、記録のファイルとしてそのまま置く。 */
    private fun placeRaw(text: String) {
        Files.createDirectories(directory)
        Files.writeString(path, text)
    }

    // 書いた位置と大きさを読み直すと、同じ位置と大きさが返る。書き直すと新しい値に替わる。親のディレクトリが無ければ作り、一時ファイルを残さない。
    // 画面の左や上の外の、負の位置もそのまま書いて読む
    @Test
    fun writtenGeometryReadsBack() {
        file().write(geometry())
        assertEquals(geometry(), file().read())

        file().write(geometry(x = -1200, y = -30, width = 640, height = 480))
        assertEquals(geometry(x = -1200, y = -30, width = 640, height = 480), file().read())

        assertEquals(listOf("window.json"), directory.listDirectoryEntries().map { it.name })
        assertEquals(emptyList(), logs)
    }

    // ファイルには、位置と大きさを x・y・width・height の欄に、最大化しているかを maximized の欄に書く
    @Test
    fun geometryIsWrittenAsJson() {
        file().write(geometry())

        assertEquals("""{"x":100,"y":50,"width":1000,"height":720,"maximized":false}""", Files.readString(path))
    }

    // 最大化しているときは、位置と大きさを書き換えずに、最大化を解いたときに戻る値として記録にあるものを残す。最大化を解けば、また書く
    @Test
    fun maximizedKeepsRecordedBounds() {
        file().write(geometry())

        file().write(geometry(x = 0, y = 0, width = 2560, height = 1400, maximized = true))
        assertEquals(geometry(maximized = true), file().read())

        file().write(geometry(x = 300, y = 200))
        assertEquals(geometry(x = 300, y = 200), file().read())
        assertEquals(emptyList(), logs)
    }

    // 最大化しているときに記録が無ければ、何も書かない
    @Test
    fun maximizedWithoutRecordWritesNothing() {
        file().write(geometry(x = 0, y = 0, width = 2560, height = 1400, maximized = true))

        assertTrue(Files.notExists(path))
        assertEquals(emptyList(), logs)
    }

    // ファイルが無ければ、覚えていないもの（null）とする。ログには書かない
    @Test
    fun missingFileIsNull() {
        assertNull(file().read())
        assertEquals(emptyList(), logs)
    }

    // maximized の欄が無ければ、最大化していないものとする。ログには書かない
    @Test
    fun missingMaximizedIsFloating() {
        placeRaw("""{"x":100,"y":50,"width":1000,"height":720}""")

        assertEquals(geometry(), file().read())
        assertEquals(emptyList(), logs)
    }

    // 記録の形でなければ（JSON でない・オブジェクトでない・位置か大きさの欄が無いか整数でない・maximized が真偽値でない・幅か高さが 0 以下）、
    // 覚えていないものとし、ログに書く
    @Test
    fun brokenRecordIsNullAndLogged() {
        val cases = listOf(
            "not json",
            "[]",
            "{}",
            """{"x":100,"y":50,"width":1000}""",
            """{"x":"left","y":50,"width":1000,"height":720}""",
            """{"x":100.5,"y":50,"width":1000,"height":720}""",
            """{"x":100,"y":null,"width":1000,"height":720}""",
            """{"x":100,"y":50,"width":1000,"height":720,"maximized":"yes"}""",
            """{"x":100,"y":50,"width":0,"height":720}""",
            """{"x":100,"y":50,"width":1000,"height":-1}""",
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
        placeRaw("""{"x":100,"y":50,"width":1000,"height":720,"maximized":true,"other":1}""")

        assertEquals(geometry(maximized = true), file().read())
        assertEquals(emptyList(), logs)
    }

    // 読めなければ（記録の場所がディレクトリ）、例外を投げずに覚えていないものとし、ログに書く
    @Test
    fun readFailureIsNullAndLogged() {
        Files.createDirectories(path)

        assertNull(file().read())
        assertEquals(1, logs.size)
        assertTrue(logs.single().startsWith("ウィンドウの位置と大きさの記録を読めなかった"))
    }

    // 書けなければ（置き換え先が、中身のあるディレクトリ）、例外を投げずにログに書く
    @Test
    fun writeFailureIsLogged() {
        Files.createDirectories(path.resolve("child"))

        file().write(geometry())

        assertEquals(1, logs.size)
        assertTrue(logs.single().startsWith("ウィンドウの位置と大きさの記録を書けなかった"))
    }
}
