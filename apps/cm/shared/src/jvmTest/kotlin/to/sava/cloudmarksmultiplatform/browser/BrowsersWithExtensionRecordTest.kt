package to.sava.cloudmarksmultiplatform.browser

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val CHROME_EXE = """C:\Program Files\Google\Chrome\Application\chrome.exe"""

class BrowsersWithExtensionRecordTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val file: Path by lazy { temporaryFolder.root.toPath().resolve("state").resolve("browsers-with-extension.json") }

    private fun place(bytes: ByteArray) {
        Files.createDirectories(file.parent)
        Files.write(file, bytes)
    }

    // 記録が無ければ、どのブラウザも載っていない記録として読める
    @Test
    fun missingRecordReadsEmpty() {
        assertEquals(BrowsersWithExtensionReadResult.Read(emptyList()), readBrowsersWithExtension(file))
    }

    // 実行ファイルのパスを小文字にそろえて読む。ホストが小文字で書いていなくても、大文字と小文字によらず比べられる。知らない欄は無視する
    @Test
    fun pathsAreReadLowercased() {
        place("""{"browsers":["C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe","c:\\firefox\\firefox.exe"],"other":1}""".encodeToByteArray())

        val read = assertIs<BrowsersWithExtensionReadResult.Read>(readBrowsersWithExtension(file))

        assertEquals(listOf(normalizedExecutable(CHROME_EXE), "c:\\firefox\\firefox.exe"), read.browsers)
        assertEquals("c:\\program files\\google\\chrome\\application\\chrome.exe", normalizedExecutable(CHROME_EXE))
    }

    // 記録の形でないもの（JSON でない・オブジェクトでない・browsers が無いか配列でない・文字列でない要素がある・UTF-8 でない）は、壊れている
    @Test
    fun brokenRecordIsBroken() {
        val cases = listOf(
            "壊れた".encodeToByteArray(),
            "[]".encodeToByteArray(),
            "{}".encodeToByteArray(),
            """{"browsers":"c:\\a.exe"}""".encodeToByteArray(),
            """{"browsers":["c:\\a.exe",1]}""".encodeToByteArray(),
            byteArrayOf(0xC3.toByte(), 0x28),
        )
        cases.forEachIndexed { index, bytes ->
            place(bytes)

            assertEquals(BrowsersWithExtensionReadResult.Broken, readBrowsersWithExtension(file), "$index")
        }
    }

    // 読めない（記録の場所がディレクトリ）ときは、読めなかったとする
    @Test
    fun unreadableRecordFails() {
        Files.createDirectories(file)

        assertIs<BrowsersWithExtensionReadResult.Failed>(readBrowsersWithExtension(file))
    }

    // 書き足すと、親のディレクトリごと作り、小文字にそろえて末尾に足す。既に載っていれば（大文字と小文字によらず）書かずに false
    @Test
    fun appendAddsOnlyNewBrowsers() {
        assertTrue(appendBrowserWithExtension(file, emptyList(), CHROME_EXE))
        val afterFirst = assertIs<BrowsersWithExtensionReadResult.Read>(readBrowsersWithExtension(file)).browsers

        assertFalse(appendBrowserWithExtension(file, afterFirst, CHROME_EXE.uppercase()))
        assertTrue(appendBrowserWithExtension(file, afterFirst, "C:\\Firefox\\firefox.exe"))

        assertEquals(
            """{"browsers":["c:\\program files\\google\\chrome\\application\\chrome.exe","c:\\firefox\\firefox.exe"]}""",
            Files.readString(file),
        )
    }
}
