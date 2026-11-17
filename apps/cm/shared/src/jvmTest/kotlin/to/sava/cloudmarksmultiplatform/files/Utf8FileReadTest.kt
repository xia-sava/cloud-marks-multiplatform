package to.sava.cloudmarksmultiplatform.files

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class Utf8FileReadTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val path: Path by lazy { temporaryFolder.root.toPath().resolve("record.json") }

    // UTF-8 の中身を、そのまま文字列として読む
    @Test
    fun readsUtf8Text() {
        val text = """{"name":"空のフォルダ"}""" + "\n"
        Files.write(path, text.encodeToByteArray())

        assertEquals(text, readUtf8File(path))
    }

    // ファイルが無ければ NoSuchFileException を投げる
    @Test
    fun missingFileThrowsNoSuchFile() {
        assertFailsWith<NoSuchFileException> { readUtf8File(path) }
    }

    // UTF-8 として読めなければ、IOException の一種の CharacterCodingException を投げる
    @Test
    fun invalidUtf8ThrowsIoException() {
        Files.write(path, byteArrayOf(0x7b, 0xff.toByte(), 0x7d))

        val failure = assertFailsWith<CharacterCodingException> { readUtf8File(path) }
        assertIs<IOException>(failure)
    }
}
