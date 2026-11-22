package to.sava.cloudmarksmultiplatform.favicon

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect

/** 縮めて描くときの、画素の拾い方。大きく縮めても荒れないよう、ミップマップを線形に補間する。 */
private val SCALING = FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR)

/**
 * Skia の [Codec] で寸法を見て、元の大きさで画素まで読み、長辺が [MAX_FAVICON_SIZE] を超えていれば縮めて描く。
 * 形式が分からない、途中で切れている、壊れている、画素数が [MAX_FAVICON_SOURCE_PIXELS] を超える、画素を置く場所を取れない、
 * Skia が読むか描くところで失敗したときは null。
 */
internal actual fun decodeFavicon(bytes: ByteArray): ImageBitmap? =
    try {
        decodeFullSize(bytes)?.let(::fitted)
    } catch (e: RuntimeException) {
        // Skia は、読めない画像（形式が分からない・途中で切れている・壊れている）と内部の誤りを、RuntimeException で知らせる
        null
    }

/**
 * [bytes] を、元の大きさのまま画素まで読む。画素数が [MAX_FAVICON_SOURCE_PIXELS] を超えるか、画素を置く場所を取れなければ null。
 * 読めなければ [Codec] の例外を投げる。
 */
private fun decodeFullSize(bytes: ByteArray): Bitmap? {
    Data.makeFromBytes(bytes).use { data ->
        Codec.makeFromData(data).use { codec ->
            if (codec.width.toLong() * codec.height > MAX_FAVICON_SOURCE_PIXELS) return null
            return filledBitmap(codec.width, codec.height) { codec.readPixels(it) }
        }
    }
}

/** 元の大きさで読んだ [decoded] を、長辺が [MAX_FAVICON_SIZE] に収まる画像にする。[decoded] は、縮めたら閉じる。 */
private fun fitted(decoded: Bitmap): ImageBitmap? {
    val (width, height) = fittedFaviconSize(decoded.width, decoded.height)
    // 書き換えない印を付け、Image にするときに画素を写さないようにする
    decoded.setImmutable()
    if (width == decoded.width && height == decoded.height) return decoded.asComposeImageBitmap()
    return decoded.use { source ->
        Image.makeFromBitmap(source).use { image ->
            filledBitmap(width, height) { target ->
                Canvas(target).use { canvas ->
                    canvas.drawImageRect(
                        image,
                        Rect.makeWH(source.width.toFloat(), source.height.toFloat()),
                        Rect.makeWH(width.toFloat(), height.toFloat()),
                        SCALING,
                        paint = null,
                        strict = true,
                    )
                }
            }
        }
    }?.setImmutable()?.asComposeImageBitmap()
}

/**
 * 幅 [width]、高さ [height] の画素を置く場所を取り、[fill] で画素を書いたもの。場所を取れなければ null。
 * [fill] が投げたら、取った場所を閉じてから投げ直す。
 */
private inline fun filledBitmap(width: Int, height: Int, fill: (Bitmap) -> Unit): Bitmap? {
    val bitmap = Bitmap()
    var filled = false
    try {
        if (bitmap.allocPixels(ImageInfo.makeN32Premul(width, height))) {
            fill(bitmap)
            filled = true
        }
    } finally {
        if (!filled) bitmap.close()
    }
    return bitmap.takeIf { filled }
}
