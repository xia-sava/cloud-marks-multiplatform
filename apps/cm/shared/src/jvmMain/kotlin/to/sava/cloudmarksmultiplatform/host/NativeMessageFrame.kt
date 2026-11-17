package to.sava.cloudmarksmultiplatform.host

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** メッセージの前に置く、長さの大きさ（バイト数）。 */
private const val LENGTH_BYTES = Int.SIZE_BYTES

/** 1 MiB のバイト数。 */
private const val MEBIBYTE = 1024 * 1024

/**
 * 受け付けるメッセージの長さ（バイト数）の上限。Chrome が拡張からホストへ送るメッセージの上限（64 MiB）と同じ。
 * 長さがこれを超えていれば、本体を読まない。
 */
internal const val MAX_MESSAGE_BYTES = 64 * MEBIBYTE

/** メッセージを 1 つ読んだ結果。 */
internal sealed interface FrameReadResult {
    /** 読めた。[body] は長さに続く本体で、まだ解釈していない。 */
    class Message(val body: ByteArray) : FrameReadResult

    /** 長さ [length] が [MAX_MESSAGE_BYTES] を超えていたので、本体を読まなかった。 */
    data class TooLarge(val length: Long) : FrameReadResult

    /** 長さか本体の途中で、入力が終わった。何も届かなかったときも含む。 */
    data object Truncated : FrameReadResult
}

/**
 * [input] からメッセージを 1 つ読む。メッセージは 4 バイトの長さ（リトルエンディアンの符号なし整数）と、それに続くその長さの本体。
 * 読み込みはブロックする。
 *
 * @throws java.io.IOException 読めなかった
 */
internal fun readMessage(input: InputStream): FrameReadResult {
    val header = input.readNBytes(LENGTH_BYTES)
    if (header.size < LENGTH_BYTES) return FrameReadResult.Truncated
    val length = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int.toUInt().toLong()
    if (length > MAX_MESSAGE_BYTES) return FrameReadResult.TooLarge(length)
    return input.readNBytes(length.toInt()).let { body ->
        if (body.size < length) FrameReadResult.Truncated else FrameReadResult.Message(body)
    }
}

/**
 * [output] へ、[body] を本体とするメッセージを 1 つ書き、flush する。メッセージの形は [readMessage] と同じ。
 *
 * @throws java.io.IOException 書けなかった
 */
internal fun writeMessage(output: OutputStream, body: ByteArray) {
    val header = ByteBuffer.allocate(LENGTH_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(body.size).array()
    output.write(header)
    output.write(body)
    output.flush()
}
