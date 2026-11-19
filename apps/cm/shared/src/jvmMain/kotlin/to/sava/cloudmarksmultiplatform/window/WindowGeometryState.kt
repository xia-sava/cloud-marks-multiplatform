package to.sava.cloudmarksmultiplatform.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit

/** ウィンドウの位置と大きさが替わってから、記録に書くまでの待ち時間（ミリ秒）。動かしている途中や大きさを変えている途中の値を書かないための間。 */
internal const val WINDOW_GEOMETRY_SAVE_DELAY_MILLIS = 500L

/**
 * 記録した位置と大きさ [geometry] で開くウィンドウの状態。最大化していたなら、最大化して開く。
 *
 * [geometry] が無ければ、[defaultSize] の大きさで画面の中央に開く。
 * 記録した位置が [screens] のどれにも重ならないときも、中央に開く。ディスプレイを外したり解像度を変えたりすると前の位置が画面の外になり、
 * そのまま開くと見えないところにウィンドウが出る。大きさは利用者が決めたものなので、記録したものを使う。
 *
 * 中央に開くときは、幅と高さを、開く先の画面の使える範囲 [openingScreenArea] 以内に切り詰める（[sizeWithin]）。
 * 使える範囲より大きいまま中央に寄せると、ウィンドウの上端が画面の上に出て、タイトルバーをつかめない。
 *
 * @param screens つないでいる画面の範囲（AWT の座標）
 * @param openingScreenArea ウィンドウを開く画面の、タスクバーなどを除いた使える範囲（AWT の座標）
 */
@Composable
fun rememberGeometryWindowState(
    geometry: WindowGeometry?,
    defaultSize: DpSize,
    screens: () -> List<Rectangle> = ::screenBounds,
    openingScreenArea: () -> Rectangle = ::defaultScreenUsableArea,
): WindowState {
    val (position, size) = remember(geometry) {
        val recordedSize = geometry?.let { DpSize(it.width.dp, it.height.dp) } ?: defaultSize
        geometry
            ?.takeIf { isOnAnyScreen(Rectangle(it.x, it.y, it.width, it.height), screens()) }
            ?.let { WindowPosition(it.x.dp, it.y.dp) to recordedSize }
            ?: (WindowPosition(Alignment.Center) to sizeWithin(recordedSize, openingScreenArea()))
    }
    return rememberWindowState(
        position = position,
        size = size,
        placement = if (geometry?.maximized == true) WindowPlacement.Maximized else WindowPlacement.Floating,
    )
}

/** 大きさ [size] の幅と高さを、それぞれ [area] の幅と高さ以内に切り詰める。収まる幅と高さはそのまま。 */
internal fun sizeWithin(size: DpSize, area: Rectangle): DpSize =
    DpSize(size.width.coerceAtMost(area.width.dp), size.height.coerceAtMost(area.height.dp))

/**
 * いまのウィンドウの位置と大きさを、記録に書く形で。位置か大きさがまだ決まっていない（中央に寄せて開くウィンドウが、まだ画面に出ていない）
 * あいだは null。
 */
fun windowGeometryOf(state: WindowState): WindowGeometry? {
    val position = state.position as? WindowPosition.Absolute ?: return null
    val size = state.size.takeIf { it.isSpecified } ?: return null
    return WindowGeometry(
        x = position.x.value.toInt(),
        y = position.y.value.toInt(),
        width = size.width.value.toInt(),
        height = size.height.value.toInt(),
        maximized = state.placement == WindowPlacement.Maximized,
    )
}

/**
 * ウィンドウ [state] の位置と大きさが替わるたびに、[file] へ書く。動かしている途中や大きさを変えている途中は値が続けて替わるので、
 * [WINDOW_GEOMETRY_SAVE_DELAY_MILLIS] のあいだ替わらなくなってから書く。取り消されるまで返らない。
 *
 * 待ち時間のうちに取り消されると、最後の位置と大きさは書かない。終了するときは、呼ぶ側が [windowGeometryOf] の値を
 * [WindowGeometryFile.write] で書く。
 *
 * @param ioDispatcher [file] へ書くディスパッチャ
 */
@OptIn(FlowPreview::class)
suspend fun saveWindowGeometryChanges(
    state: WindowState,
    file: WindowGeometryFile,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    snapshotFlow { windowGeometryOf(state) }
        .filterNotNull()
        .debounce(WINDOW_GEOMETRY_SAVE_DELAY_MILLIS)
        .collect { geometry -> withContext(ioDispatcher) { file.write(geometry) } }
}

/** [bounds] が、[screens] のどれかに少しでも重なるか。少しでも重なっていれば、つかんで動かせる。 */
internal fun isOnAnyScreen(bounds: Rectangle, screens: List<Rectangle>): Boolean = screens.any { it.intersects(bounds) }

/** つないでいるすべての画面の範囲（AWT の座標）。 */
internal fun screenBounds(): List<Rectangle> =
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds }

/**
 * メインディスプレイ（AWT の既定の画面）の、タスクバーなどを除いた使える範囲（AWT の座標）。アプリの最初のウィンドウは
 * メインディスプレイに開き、中央に寄せるときはこの範囲の中央に置く。
 */
internal fun defaultScreenUsableArea(): Rectangle {
    val configuration = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
    return configuration.bounds.let { bounds ->
        Rectangle(
            bounds.x + insets.left,
            bounds.y + insets.top,
            bounds.width - insets.left - insets.right,
            bounds.height - insets.top - insets.bottom,
        )
    }
}
