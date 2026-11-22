package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import to.sava.cloudmarksmultiplatform.favicon.Favicon
import to.sava.cloudmarksmultiplatform.favicon.FaviconSource
import to.sava.cloudmarksmultiplatform.favicon.NoFavicons
import kotlin.time.Duration.Companion.minutes

/** 項目の行に描く favicon の一辺。 */
private val ITEM_FAVICON_SIZE = 16.dp

/** 取れた favicon の画像（[ItemFavicon]）の、テストの目印。 */
internal const val FAVICON_IMAGE_TAG = "faviconImage"

/** favicon が無い項目の地球儀のアイコン（[ItemFavicon]）の、テストの目印。 */
internal const val FAVICON_GLOBE_TAG = "faviconGlobe"

/** favicon が無い（[Favicon.Missing]）あいだ、favicon を返す仕組みに [ItemFavicon] が状態を問い直す間隔。 */
internal val MISSING_FAVICON_RECHECK_INTERVAL = 1.minutes

/**
 * 項目の行に描く favicon を返す仕組み。既定は、何も取りに行かない [NoFavicons]。[CloudMarksApp] が、コントローラの
 * [to.sava.cloudmarksmultiplatform.app.CloudMarksController.favicons] を渡す。
 */
internal val LocalFaviconSource = staticCompositionLocalOf<FaviconSource> { NoFavicons }

/**
 * 項目の URL [url] の favicon を [ITEM_FAVICON_SIZE] で描く。状態は [LocalFaviconSource] から読む。
 * 取れていれば（[Favicon.Found]）その画像を縮めて、無ければ（[Favicon.Missing]）地球儀のアイコンを、描く。
 * キャッシュを読んでいる、または取りに行っているあいだ（[Favicon.Loading]）は何も描かない。
 * 飾りなので、読み上げの説明は付けない。
 *
 * 無いあいだは、[MISSING_FAVICON_RECHECK_INTERVAL] ごとに [FaviconSource.faviconOf] を呼び直す。ネットワークにつながっていないあいだに
 * 無いとなった favicon を、つながったあとで取り直させるため。取り直すかどうかは、favicon を返す仕組みが決める。
 */
@Composable
internal fun ItemFavicon(url: String) {
    val source = LocalFaviconSource.current
    val flow = remember(source, url) { source.faviconOf(url) }
    // 返す StateFlow が替わったら、前の状態を描かずに、替わった StateFlow の状態から読み始める
    key(flow) {
        val favicon by flow.collectAsState()
        when (val current = favicon) {
            Favicon.Loading -> Unit
            is Favicon.Found -> Image(
                bitmap = current.image,
                contentDescription = null,
                filterQuality = FilterQuality.Medium,
                modifier = Modifier.size(ITEM_FAVICON_SIZE).testTag(FAVICON_IMAGE_TAG),
            )
            Favicon.Missing -> {
                Icon(
                    imageVector = GlobeIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(ITEM_FAVICON_SIZE).testTag(FAVICON_GLOBE_TAG),
                )
                LaunchedEffect(source, url) {
                    while (true) {
                        delay(MISSING_FAVICON_RECHECK_INTERVAL)
                        source.faviconOf(url)
                    }
                }
            }
        }
    }
}
