package to.sava.cloudmarksmultiplatform.favicon

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/** 項目の URL のオリジンの favicon の状態。 */
sealed interface Favicon {
    /** キャッシュを読んでいる、または取りに行っている。 */
    data object Loading : Favicon

    /** 取れた。 */
    data class Found(val image: ImageBitmap) : Favicon

    /** 無い。http・https 以外の URL も、すぐこれになる。 */
    data object Missing : Favicon
}

/** 項目の URL から、そのオリジンの favicon の状態を返す。 */
interface FaviconSource {
    /** [url] のオリジンの favicon の状態。同じオリジンには同じ StateFlow を返す。呼んだときにキャッシュが無いか古ければ取りに行き始める。 */
    fun faviconOf(url: String): StateFlow<Favicon>
}

/** 何も取りに行かず、どの URL にも [Favicon.Missing] を返す。画面のテストと、favicon を出さない場合の既定に使う。 */
object NoFavicons : FaviconSource {
    override fun faviconOf(url: String): StateFlow<Favicon> = NO_FAVICON
}

/** favicon の無い状態。替わらない。 */
internal val NO_FAVICON: StateFlow<Favicon> = MutableStateFlow(Favicon.Missing).asStateFlow()

/** 読んだ favicon の長辺の上限（画素）。これより大きい画像は、縦横比を保って縮める。 */
internal const val MAX_FAVICON_SIZE = 64

/** 読む前の画像の、一辺の目安（画素）。画素数の上限 [MAX_FAVICON_SOURCE_PIXELS] を、この正方形の画素数にする。 */
private const val MAX_FAVICON_SOURCE_SIDE = 4096

/** 読む前の画像の画素数の上限。4096×4096。これを超える寸法の画像は、読まずに読めないものとする。 */
internal const val MAX_FAVICON_SOURCE_PIXELS: Long = MAX_FAVICON_SOURCE_SIDE.toLong() * MAX_FAVICON_SOURCE_SIDE

/**
 * [bytes] を画像として読む。長辺が [MAX_FAVICON_SIZE] を超える画像は、縦横比を保って長辺を [MAX_FAVICON_SIZE] に縮める
 * （[fittedFaviconSize]）。読めなければ（SVG など、プラットフォームが読めない形式や、壊れたもの、画素数が
 * [MAX_FAVICON_SOURCE_PIXELS] を超えるもの）null。デスクトップは Skia、Android は `BitmapFactory` で読む。
 */
internal expect fun decodeFavicon(bytes: ByteArray): ImageBitmap?

/**
 * 幅 [width]、高さ [height] の画像を、長辺が [MAX_FAVICON_SIZE] に収まるよう縦横比を保って縮めたときの、幅と高さ。
 * 長辺が [MAX_FAVICON_SIZE] 以下なら、そのまま。短辺は 1 画素より小さくしない。
 */
internal fun fittedFaviconSize(width: Int, height: Int): Pair<Int, Int> {
    val longer = maxOf(width, height)
    if (longer <= MAX_FAVICON_SIZE) return width to height
    val scale = MAX_FAVICON_SIZE.toDouble() / longer
    return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
}
