package to.sava.cloudmarksmultiplatform.settings

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

/** 既定と違うタブの読み込み方。 */
private val TAB_LOADING = TabLoading(loadAtOnce = 5, loadAhead = 40)

class DeviceSettingsFileTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** まだ無いディレクトリの下の、端末ごとの設定のファイル。 */
    private val directory: Path by lazy { temporaryFolder.root.toPath().resolve("CloudMarks") }
    private val path: Path by lazy { directory.resolve("device.json") }
    private val logs = mutableListOf<String>()

    private fun file(): DeviceSettingsFile = DeviceSettingsFile(path) { logs += it }

    /** [text] を、端末ごとの設定のファイルとしてそのまま置く。 */
    private fun placeRaw(text: String) {
        Files.createDirectories(directory)
        Files.writeString(path, text)
    }

    // 書いた端末ごとの設定を読み直すと、同じ設定が返る。親のディレクトリが無ければ作り、一時ファイルを残さない
    @Test
    fun writtenSettingsReadBack() {
        DisplayScale.STEPS.forEach { scale ->
            val settings = DeviceSettings(displayScale = scale, tabLoading = TAB_LOADING)
            file().write(settings)

            assertEquals(settings, file().read(), "${scale.percent}")
        }
        assertEquals(listOf("device.json"), directory.listDirectoryEntries().map { it.name })
        assertEquals(emptyList(), logs)
    }

    // 先に読み込むタブの数の 0（すべてのタブを順に読み込む）は、そのまま書き、そのまま読む。ログには書かない
    @Test
    fun zeroLoadAheadIsKept() {
        val settings = DeviceSettings(tabLoading = TabLoading(loadAtOnce = 2, loadAhead = 0))
        file().write(settings)

        assertEquals(settings, file().read())
        assertEquals(emptyList(), logs)
    }

    // ファイルには、表示の大きさを百分率で、タブの読み込み方を 2 つの数で書く。既定の値も書く
    @Test
    fun settingsAreWrittenAsNumbers() {
        file().write(DeviceSettings(displayScale = checkNotNull(DisplayScale.ofPercent(125))))

        assertEquals("""{"scale":125,"loadAtOnce":3,"loadAhead":20}""", Files.readString(path))
    }

    // ファイルが無ければ、既定の設定。ログには書かない
    @Test
    fun missingFileIsDefault() {
        assertEquals(DeviceSettings(), file().read())
        assertEquals(emptyList(), logs)
    }

    // 無い欄は既定の値にする。ログには書かない
    @Test
    fun missingFieldsAreDefault() {
        val cases = listOf(
            "{}" to DeviceSettings(),
            """{"scale":150}""" to DeviceSettings(displayScale = checkNotNull(DisplayScale.ofPercent(150))),
            """{"loadAtOnce":5,"loadAhead":40}""" to DeviceSettings(tabLoading = TAB_LOADING),
        )
        cases.forEach { (text, expected) ->
            placeRaw(text)

            assertEquals(expected, file().read(), text)
        }
        assertEquals(emptyList(), logs)
    }

    // JSON でないか、型の違う値があれば、すべて既定の設定にし、読めなかったことをログに書く
    @Test
    fun unreadableFileIsDefaultAndLogged() {
        val cases = listOf("not json", """{"scale":"large"}""", """{"scale":125,"loadAtOnce":"many"}""", "[]")
        cases.forEach { text ->
            logs.clear()
            placeRaw(text)

            assertEquals(DeviceSettings(), file().read(), text)
            assertEquals(1, logs.size, text)
        }
    }

    // 段に無い表示の大きさと、下限（同時に読み込むタブの数は 1、先に読み込むタブの数は 0）未満の数は、その欄だけ既定の値にし、欄ごとにログに書く
    @Test
    fun invalidValuesAreDefaultPerField() {
        placeRaw("""{"scale":120,"loadAtOnce":0,"loadAhead":40}""")

        assertEquals(DeviceSettings(tabLoading = TabLoading(loadAhead = 40)), file().read())
        assertEquals(2, logs.size)

        logs.clear()
        placeRaw("""{"scale":150,"loadAtOnce":5,"loadAhead":-1}""")

        assertEquals(
            DeviceSettings(displayScale = checkNotNull(DisplayScale.ofPercent(150)), tabLoading = TabLoading(loadAtOnce = 5)),
            file().read(),
        )
        assertEquals(1, logs.size)
    }

    // 知らない欄は無視して読む
    @Test
    fun unknownKeysAreIgnored() {
        placeRaw("""{"scale":150,"other":true}""")

        assertEquals(DisplayScale.ofPercent(150), file().read().displayScale)
        assertEquals(emptyList(), logs)
    }

    // 書けなければ（置き換え先が、中身のあるディレクトリ）、例外を投げずにログに書く
    @Test
    fun writeFailureIsLogged() {
        Files.createDirectories(path.resolve("child"))

        file().write(DeviceSettings())

        assertEquals(1, logs.size)
        assertTrue(logs.single().startsWith("端末ごとの設定を書けなかった"))
    }
}

class TabCountTest {
    // 前後の空白を除いた 1 以上の整数だけを、同時に読み込むタブの数として読む
    @Test
    fun parsesPositiveIntegers() {
        assertEquals(1, parseTabCount("1"))
        assertEquals(25, parseTabCount(" 25 "))
        listOf("", " ", "0", "-3", "1.5", "abc", "3個", "99999999999").forEach { text ->
            assertNull(parseTabCount(text), text)
        }
    }

    // 前後の空白を除いた 0 以上の整数だけを、先に読み込むタブの数として読む
    @Test
    fun parsesNonNegativeIntegersForLoadAhead() {
        assertEquals(0, parseLoadAhead("0"))
        assertEquals(1, parseLoadAhead("1"))
        assertEquals(25, parseLoadAhead(" 25 "))
        listOf("", " ", "-1", "-3", "1.5", "abc", "3個", "99999999999").forEach { text ->
            assertNull(parseLoadAhead(text), text)
        }
    }
}
