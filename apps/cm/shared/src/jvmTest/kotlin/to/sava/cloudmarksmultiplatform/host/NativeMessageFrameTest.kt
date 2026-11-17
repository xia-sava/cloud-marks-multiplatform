package to.sava.cloudmarksmultiplatform.host

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NativeMessageFrameTest {
    /** 長さ [length] を持つ 4 バイトの長さの欄（リトルエンディアン）。 */
    private fun lengthHeader(length: Long): ByteArray =
        ByteArray(Int.SIZE_BYTES) { index -> (length shr (Byte.SIZE_BITS * index)).toByte() }

    // 長さはリトルエンディアンで読む。256 バイトの長さを、ビッグエンディアンの 65536 と読み違えない
    @Test
    fun lengthIsLittleEndian() {
        val body = ByteArray(256) { it.toByte() }
        val input = ByteArrayInputStream(byteArrayOf(0, 1, 0, 0) + body)

        val message = assertIs<FrameReadResult.Message>(readMessage(input))

        assertContentEquals(body, message.body)
    }

    // 書くときも、長さをリトルエンディアンで本体の前に付ける
    @Test
    fun writtenLengthIsLittleEndian() {
        val output = ByteArrayOutputStream()

        writeMessage(output, ByteArray(258))

        assertContentEquals(byteArrayOf(2, 1, 0, 0), output.toByteArray().copyOf(Int.SIZE_BYTES))
        assertEquals(Int.SIZE_BYTES + 258, output.size())
    }

    // 書いたメッセージを読むと、同じ本体が返る。続けて書いた 2 つも、1 つずつ分けて読める
    @Test
    fun writtenMessagesReadBack() {
        val output = ByteArrayOutputStream()
        writeMessage(output, "{\"ok\":true}".encodeToByteArray())
        writeMessage(output, "日本語".encodeToByteArray())
        val input = ByteArrayInputStream(output.toByteArray())

        assertEquals("{\"ok\":true}", assertIs<FrameReadResult.Message>(readMessage(input)).body.decodeToString())
        assertEquals("日本語", assertIs<FrameReadResult.Message>(readMessage(input)).body.decodeToString())
    }

    // 長さが 0 なら、空の本体のメッセージになる
    @Test
    fun zeroLengthIsEmptyMessage() {
        val message = assertIs<FrameReadResult.Message>(readMessage(ByteArrayInputStream(lengthHeader(0))))

        assertEquals(0, message.body.size)
    }

    // 長さが上限を超えれば、本体を読まずに上限超えとする。長さは符号なしの整数として読む
    @Test
    fun lengthOverLimitIsTooLargeWithoutReadingBody() {
        listOf(MAX_MESSAGE_BYTES + 1L, 0xFFFF_FFFFL).forEach { length ->
            val input = ByteArrayInputStream(lengthHeader(length) + ByteArray(16))

            assertEquals(FrameReadResult.TooLarge(length), readMessage(input), length.toString())
            assertEquals(16, input.available(), "本体を読んでいない: $length")
        }
    }

    // 長さがちょうど上限なら、上限超えにしない（本体が足りないので、途中で切れたものになる）
    @Test
    fun lengthAtLimitIsAccepted() {
        val input = ByteArrayInputStream(lengthHeader(MAX_MESSAGE_BYTES.toLong()) + ByteArray(16))

        assertEquals(FrameReadResult.Truncated, readMessage(input))
    }

    // 何も届かない、長さの途中で終わる、本体の途中で終わる入力は、途中で切れたものとする
    @Test
    fun truncatedInputIsTruncated() {
        val cases = listOf(
            "空" to ByteArray(0),
            "長さの途中" to byteArrayOf(5, 0),
            "本体の途中" to lengthHeader(5) + "abc".encodeToByteArray(),
        )

        cases.forEach { (name, bytes) ->
            assertEquals(FrameReadResult.Truncated, readMessage(ByteArrayInputStream(bytes)), name)
        }
    }
}
