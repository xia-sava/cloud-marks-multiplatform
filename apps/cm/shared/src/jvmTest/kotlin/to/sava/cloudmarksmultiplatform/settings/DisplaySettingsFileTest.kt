package to.sava.cloudmarksmultiplatform.settings

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DisplaySettingsFileTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** まだ無いディレクトリの下の、表示の設定のファイル。 */
    private val directory: Path by lazy { temporaryFolder.root.toPath().resolve("CloudMarks") }
    private val path: Path by lazy { directory.resolve("display.json") }
    private val logs = mutableListOf<String>()

    private fun file(): DisplaySettingsFile = DisplaySettingsFile(path) { logs += it }

    /** [text] を、表示の設定のファイルとしてそのまま置く。 */
    private fun placeRaw(text: String) {
        Files.createDirectories(directory)
        Files.writeString(path, text)
    }

    // 書いた表示の大きさを読み直すと、同じ大きさが返る。親のディレクトリが無ければ作り、一時ファイルを残さない
    @Test
    fun writtenScaleReadsBack() {
        DisplayScale.STEPS.forEach { scale ->
            file().write(scale)

            assertEquals(scale, file().read(), "${scale.percent}")
        }
        assertEquals(listOf("display.json"), directory.listDirectoryEntries().map { it.name })
        assertEquals(emptyList(), logs)
    }

    // ファイルには、表示の大きさを百分率で書く
    @Test
    fun scaleIsWrittenAsPercent() {
        file().write(checkNotNull(DisplayScale.ofPercent(125)))

        assertEquals("""{"scale":125}""", Files.readString(path))
    }

    // ファイルが無ければ、既定の大きさ。ログには書かない
    @Test
    fun missingFileIsDefault() {
        assertEquals(DisplayScale.DEFAULT, file().read())
        assertEquals(emptyList(), logs)
    }

    // 読めないファイル（JSON でない、値が無い、型が違う、段に無い値）は、既定の大きさにし、読めなかったことをログに書く
    @Test
    fun unreadableFileIsDefaultAndLogged() {
        val cases = listOf("not json", "{}", """{"scale":"large"}""", """{"scale":120}""")
        cases.forEach { text ->
            logs.clear()
            placeRaw(text)

            assertEquals(DisplayScale.DEFAULT, file().read(), text)
            assertEquals(1, logs.size, text)
        }
    }

    // 知らない欄は無視して読む
    @Test
    fun unknownKeysAreIgnored() {
        placeRaw("""{"scale":150,"other":true}""")

        assertEquals(DisplayScale.ofPercent(150), file().read())
    }

    // 書けなければ（置き換え先が、中身のあるディレクトリ）、例外を投げずにログに書く
    @Test
    fun writeFailureIsLogged() {
        Files.createDirectories(path.resolve("child"))

        file().write(DisplayScale.DEFAULT)

        assertEquals(1, logs.size)
        assertTrue(logs.single().startsWith("表示の設定を書けなかった"))
    }
}
