package to.sava.cloudmarksmultiplatform.favicon

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** テストの画像の幅（画素）。 */
private const val IMAGE_WIDTH = 3

/** テストの画像の高さ（画素）。 */
private const val IMAGE_HEIGHT = 2

/** ICO の、ファイルのヘッダーの大きさ（バイト数）。 */
private const val ICO_HEADER_BYTES = 6

/** ICO の、1 つの画像の項目の大きさ（バイト数）。 */
private const val ICO_ENTRY_BYTES = 16

/** 縮める前の、大きな画像の幅（画素）。 */
private const val LARGE_WIDTH = 512

/** 縮める前の、大きな画像の高さ（画素）。 */
private const val LARGE_HEIGHT = 256

/** 幅 [width]、高さ [height] の PNG。圧縮で小さくならないよう、画素ごとに色を変える。 */
private fun pngBytes(width: Int = IMAGE_WIDTH, height: Int = IMAGE_HEIGHT): ByteArray =
    ByteArrayOutputStream().also { output ->
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, (x * PIXEL_SPREAD + y) or OPAQUE)
        ImageIO.write(image, "png", output)
    }.toByteArray()

/** PNG のファイルの頭。 */
private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

/** PNG の IHDR の、1 画素のビット数（8）・色の種類（グレー）・圧縮・フィルタ・インターレースの方式。 */
private val GRAY_PNG_FORMAT = byteArrayOf(8, 0, 0, 0, 0)

/**
 * 幅 [width]、高さ [height] の、真っ黒なグレーの PNG。画素数の上限の前後を確かめるのに使う。圧縮するとごく小さくなるので、大きな寸法でも
 * 作るのが速い。
 */
private fun blackPng(width: Int, height: Int): ByteArray {
    // 各行は、フィルタの種類（0）のあとに画素が並ぶ
    val rows = ByteArray(height * (width + 1))
    val compressed = ByteArrayOutputStream().also { output -> DeflaterOutputStream(output).use { it.write(rows) } }.toByteArray()
    val header = ByteBuffer.allocate(Int.SIZE_BYTES * 2 + GRAY_PNG_FORMAT.size).putInt(width).putInt(height).put(GRAY_PNG_FORMAT).array()
    return ByteArrayOutputStream().apply {
        write(PNG_SIGNATURE)
        writePngChunk("IHDR", header)
        writePngChunk("IDAT", compressed)
        writePngChunk("IEND", ByteArray(0))
    }.toByteArray()
}

/** PNG のチャンク（長さ・種類・中身・CRC）を書く。 */
private fun ByteArrayOutputStream.writePngChunk(type: String, data: ByteArray) {
    val typeBytes = type.encodeToByteArray()
    write(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(data.size).array())
    write(typeBytes)
    write(data)
    write(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(CRC32().apply { update(typeBytes); update(data) }.value.toInt()).array())
}

/** 画素数の上限（4096×4096）の一辺。 */
private const val LIMIT_SIDE = 4096

/** 画素ごとに色を変えるときの、隣どうしの差。 */
private const val PIXEL_SPREAD = 7919

/** 不透明にする、ARGB の頭のビット。 */
private const val OPAQUE = 0xFF000000.toInt()

/** [png] を 1 つの画像として収めた ICO。ICO は、中の画像を PNG のまま持てる。 */
private fun icoBytes(png: ByteArray): ByteArray =
    ByteBuffer.allocate(ICO_HEADER_BYTES + ICO_ENTRY_BYTES + png.size)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort(0) // 予約
        .putShort(1) // 種類（アイコン）
        .putShort(1) // 画像の数
        .put(IMAGE_WIDTH.toByte())
        .put(IMAGE_HEIGHT.toByte())
        .put(0) // 色の数
        .put(0) // 予約
        .putShort(1) // 色の面
        .putShort(32) // 1 画素のビット数
        .putInt(png.size)
        .putInt(ICO_HEADER_BYTES + ICO_ENTRY_BYTES)
        .put(png)
        .array()

class FaviconDecodeTest {
    // デスクトップでは、PNG を画像として読む
    @Test
    fun decodesPng() {
        val image = assertNotNull(decodeFavicon(pngBytes()))

        assertEquals(IMAGE_WIDTH, image.width)
        assertEquals(IMAGE_HEIGHT, image.height)
    }

    // デスクトップでは、/favicon.ico の形式（ICO）も画像として読む
    @Test
    fun decodesIco() {
        val image = assertNotNull(decodeFavicon(icoBytes(pngBytes())))

        assertEquals(IMAGE_WIDTH, image.width)
        assertEquals(IMAGE_HEIGHT, image.height)
    }

    // 長辺が 64 画素を超える画像は、縦横比を保って長辺 64 画素に縮める
    @Test
    fun scalesDownLargeImage() {
        val image = assertNotNull(decodeFavicon(pngBytes(LARGE_WIDTH, LARGE_HEIGHT)))

        assertEquals(MAX_FAVICON_SIZE, image.width)
        assertEquals(MAX_FAVICON_SIZE * LARGE_HEIGHT / LARGE_WIDTH, image.height)
    }

    // 長辺がちょうど 64 画素の画像は、そのままの大きさで読む
    @Test
    fun keepsImageOfLimitSize() {
        val image = assertNotNull(decodeFavicon(pngBytes(MAX_FAVICON_SIZE, MAX_FAVICON_SIZE)))

        assertEquals(MAX_FAVICON_SIZE, image.width)
        assertEquals(MAX_FAVICON_SIZE, image.height)
    }

    // 画素数が上限（4096×4096）ちょうどの画像は読んで縮め、上限を超える寸法の画像は読まずに読めないとする
    @Test
    fun rejectsImageOverPixelLimit() {
        assertEquals(LIMIT_SIDE.toLong() * LIMIT_SIDE, MAX_FAVICON_SOURCE_PIXELS)

        val atLimit = assertNotNull(decodeFavicon(blackPng(LIMIT_SIDE, LIMIT_SIDE)))
        assertEquals(MAX_FAVICON_SIZE, atLimit.width)
        assertEquals(MAX_FAVICON_SIZE, atLimit.height)

        assertNull(decodeFavicon(blackPng(LIMIT_SIDE + 1, LIMIT_SIDE)))
    }

    // 途中で切れた画像は、空の絵として読まず、読めないとする
    @Test
    fun rejectsTruncatedImage() {
        val png = pngBytes(LARGE_WIDTH, LARGE_HEIGHT)

        assertNull(decodeFavicon(png.copyOf(png.size / 2)))
    }

    // 縮めたときの大きさは、長辺を 64 画素にし、縦横比を保つ。短辺は 1 画素より小さくしない。長辺が 64 画素以下ならそのまま
    @Test
    fun fitsSizeWithinLimit() {
        listOf(
            (16 to 16) to (16 to 16),
            (64 to 32) to (64 to 32),
            (512 to 256) to (64 to 32),
            (256 to 512) to (32 to 64),
            (100 to 100) to (64 to 64),
            (1000 to 3) to (64 to 1),
            (65 to 64) to (64 to 63),
        ).forEach { (size, fitted) -> assertEquals(fitted, fittedFaviconSize(size.first, size.second), size.toString()) }
    }

    // 画像でないバイト列（SVG を含む）と、空のバイト列は読めない
    @Test
    fun rejectsNonImages() {
        listOf(
            "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".encodeToByteArray(),
            "<html></html>".encodeToByteArray(),
            byteArrayOf(1, 2, 3, 4),
            ByteArray(0),
        ).forEach { bytes -> assertNull(decodeFavicon(bytes), bytes.decodeToString()) }
    }
}
