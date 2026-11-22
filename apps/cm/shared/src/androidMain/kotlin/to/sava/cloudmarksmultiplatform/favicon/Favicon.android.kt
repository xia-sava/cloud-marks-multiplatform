package to.sava.cloudmarksmultiplatform.favicon

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** `inSampleSize` の、間引かずに読む値。 */
private const val NO_SAMPLING = 1

/** `inSampleSize` を大きくしていくときの倍率。`BitmapFactory` は 2 の冪に切り下げて使う。 */
private const val SAMPLING_STEP = 2

/**
 * `inJustDecodeBounds` で寸法だけを見て、長辺が [MAX_FAVICON_SIZE] を下回らない範囲で `inSampleSize` で間引いて読む。
 * 読んだものの長辺が [MAX_FAVICON_SIZE] を超えていれば、`Bitmap.createScaledBitmap`（フィルタあり）で縮める。
 * 寸法が読めないか、画素数が [MAX_FAVICON_SOURCE_PIXELS] を超えれば、読まずに null。
 */
internal actual fun decodeFavicon(bytes: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    if (bounds.outWidth.toLong() * bounds.outHeight > MAX_FAVICON_SOURCE_PIXELS) return null
    val options = BitmapFactory.Options().apply { inSampleSize = faviconSampleSize(bounds.outWidth, bounds.outHeight) }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    val (width, height) = fittedFaviconSize(decoded.width, decoded.height)
    if (width == decoded.width && height == decoded.height) return decoded.asImageBitmap()
    return Bitmap.createScaledBitmap(decoded, width, height, true)
        .also { if (it !== decoded) decoded.recycle() }
        .asImageBitmap()
}

/**
 * 幅 [width]、高さ [height] の画像を読むときの `inSampleSize`。間引いたあとの長辺が [MAX_FAVICON_SIZE] を下回らない、
 * いちばん大きい 2 の冪。長辺が [MAX_FAVICON_SIZE] の 2 倍に満たなければ、間引かない（1）。
 */
internal fun faviconSampleSize(width: Int, height: Int): Int {
    val longer = maxOf(width, height)
    return generateSequence(NO_SAMPLING) { it * SAMPLING_STEP }
        .takeWhile { longer / it >= MAX_FAVICON_SIZE }
        .lastOrNull() ?: NO_SAMPLING
}
