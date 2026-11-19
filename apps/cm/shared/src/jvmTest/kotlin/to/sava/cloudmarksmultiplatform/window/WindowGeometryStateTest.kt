package to.sava.cloudmarksmultiplatform.window

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 記録が無いときの、ウィンドウの大きさ。 */
private val DEFAULT_SIZE = DpSize(800.dp, 600.dp)

/** 位置を変えてから大きさを変えるまでの時間（ミリ秒）。記録に書くまでの待ち時間より短く、2 つを合わせると待ち時間より長い。 */
private const val CHANGE_INTERVAL_MILLIS = 300L

/** 1 枚だけつないでいる画面。 */
private val ONE_SCREEN = listOf(Rectangle(0, 0, 1920, 1080))

/** 1 枚目の右に、2 枚目をつないでいる画面。 */
private val TWO_SCREENS = ONE_SCREEN + Rectangle(1920, 0, 2560, 1440)

/** [ONE_SCREEN] の、下端のタスクバー（高さ 40）を除いた使える範囲。 */
private val ONE_SCREEN_USABLE_AREA = Rectangle(0, 0, 1920, 1040)

/** 最大化していない、ウィンドウの位置と大きさ。 */
private fun geometry(x: Int, y: Int, maximized: Boolean = false) =
    WindowGeometry(x = x, y = y, width = 1000, height = 720, maximized = maximized)

/**
 * 記録した位置と大きさ [geometry] と、つないでいる画面 [screens] と、ウィンドウを開く画面の使える範囲 [openingScreenArea] で作った、
 * ウィンドウの状態。
 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.restored(
    geometry: WindowGeometry?,
    screens: List<Rectangle> = ONE_SCREEN,
    openingScreenArea: Rectangle = ONE_SCREEN_USABLE_AREA,
): WindowState {
    lateinit var state: WindowState
    setContent { state = rememberGeometryWindowState(geometry, DEFAULT_SIZE, { screens }, { openingScreenArea }) }
    waitForIdle()
    return state
}

@OptIn(ExperimentalTestApi::class)
class WindowGeometryStateTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // 記録した位置と大きさで開く
    @Test
    fun opensWhereItWasLeft() = runComposeUiTest {
        val state = restored(geometry(x = 100, y = 50))

        assertEquals(WindowPosition(100.dp, 50.dp), state.position)
        assertEquals(DpSize(1000.dp, 720.dp), state.size)
        assertEquals(WindowPlacement.Floating, state.placement)
    }

    // 記録が無ければ、既定の大きさで画面の中央に開く
    @Test
    fun opensInTheMiddleWithoutRecord() = runComposeUiTest {
        val state = restored(geometry = null)

        assertEquals(WindowPosition(Alignment.Center), state.position)
        assertEquals(DEFAULT_SIZE, state.size)
        assertEquals(WindowPlacement.Floating, state.placement)
    }

    // 記録した位置がどの画面にも重ならなければ（ディスプレイを外したあと）、画面の中央に開く。大きさは記録したものを使う
    @Test
    fun opensOffScreenWindowInTheMiddle() = runComposeUiTest {
        val state = restored(geometry(x = 3000, y = 200))

        assertEquals(WindowPosition(Alignment.Center), state.position)
        assertEquals(DpSize(1000.dp, 720.dp), state.size)
    }

    // 画面の外にあったウィンドウを中央に開くとき、記録した大きさが開く先の画面の使える範囲より大きければ、その範囲に切り詰める
    @Test
    fun opensOversizedOffScreenWindowWithinScreen() = runComposeUiTest {
        val state = restored(WindowGeometry(x = 3000, y = 200, width = 2400, height = 1300, maximized = false))

        assertEquals(WindowPosition(Alignment.Center), state.position)
        assertEquals(DpSize(1920.dp, 1040.dp), state.size)
    }

    // 記録が無くて中央に開くときも、既定の大きさが開く先の画面の使える範囲より大きければ、その範囲に切り詰める
    @Test
    fun opensDefaultSizeWithinSmallScreen() = runComposeUiTest {
        val state = restored(geometry = null, openingScreenArea = Rectangle(0, 0, 1024, 560))

        assertEquals(WindowPosition(Alignment.Center), state.position)
        assertEquals(DpSize(800.dp, 560.dp), state.size)
    }

    // 幅と高さを、それぞれ使える範囲の幅と高さ以内に切り詰める。収まる幅と高さはそのまま
    @Test
    fun sizeIsClampedPerSide() {
        val cases = listOf(
            "収まる" to (DpSize(1000.dp, 720.dp) to DpSize(1000.dp, 720.dp)),
            "ちょうど同じ" to (DpSize(1920.dp, 1040.dp) to DpSize(1920.dp, 1040.dp)),
            "幅だけはみ出す" to (DpSize(2400.dp, 720.dp) to DpSize(1920.dp, 720.dp)),
            "高さだけはみ出す" to (DpSize(1000.dp, 1300.dp) to DpSize(1000.dp, 1040.dp)),
            "両方はみ出す" to (DpSize(2400.dp, 1300.dp) to DpSize(1920.dp, 1040.dp)),
        )
        cases.forEach { (name, sizes) ->
            val (size, expected) = sizes

            assertEquals(expected, sizeWithin(size, ONE_SCREEN_USABLE_AREA), name)
        }
    }

    // 2 枚目の画面の上の位置は、その画面をつないでいれば、そのまま戻す
    @Test
    fun opensOnSecondScreenWhereItWasLeft() = runComposeUiTest {
        val state = restored(geometry(x = 2200, y = 300), screens = TWO_SCREENS)

        assertEquals(WindowPosition(2200.dp, 300.dp), state.position)
    }

    // 最大化していたなら、最大化して開く。最大化を解いたときの位置と大きさは、記録したもの
    @Test
    fun opensMaximizedIfItWas() = runComposeUiTest {
        val state = restored(geometry(x = 100, y = 50, maximized = true))

        assertEquals(WindowPlacement.Maximized, state.placement)
        assertEquals(WindowPosition(100.dp, 50.dp), state.position)
        assertEquals(DpSize(1000.dp, 720.dp), state.size)
    }

    // 画面に少しでも重なっていれば画面の上とし、どの画面にも重ならないときだけ画面の外とする
    @Test
    fun onAnyScreenIfOverlapping() {
        assertTrue(isOnAnyScreen(Rectangle(100, 50, 1000, 720), ONE_SCREEN), "画面の中")
        assertTrue(isOnAnyScreen(Rectangle(1800, 900, 1000, 720), ONE_SCREEN), "右下の端が重なる")
        assertTrue(isOnAnyScreen(Rectangle(-900, -600, 1000, 720), ONE_SCREEN), "左上の端が重なる")
        assertTrue(isOnAnyScreen(Rectangle(2200, 300, 1000, 720), TWO_SCREENS), "2 枚目の画面の中")
        assertFalse(isOnAnyScreen(Rectangle(3000, 200, 1000, 720), ONE_SCREEN), "右の外")
        assertFalse(isOnAnyScreen(Rectangle(-1000, 100, 1000, 720), ONE_SCREEN), "左の外で、端が接するだけ")
    }

    // 位置が決まっていない（中央に寄せて開くウィンドウが、まだ画面に出ていない）あいだは、記録に書く形にしない。
    // 決まっていれば、最大化しているかも添える
    @Test
    fun geometryOnlyWhenPositioned() {
        assertNull(windowGeometryOf(WindowState(position = WindowPosition(Alignment.Center), size = DpSize(1000.dp, 720.dp))))
        assertNull(windowGeometryOf(WindowState(position = WindowPosition.PlatformDefault, size = DpSize(1000.dp, 720.dp))))

        val floating = WindowState(position = WindowPosition(100.dp, 50.dp), size = DpSize(1000.dp, 720.dp))
        assertEquals(geometry(x = 100, y = 50), windowGeometryOf(floating))
        val maximized = WindowState(
            placement = WindowPlacement.Maximized,
            position = WindowPosition(100.dp, 50.dp),
            size = DpSize(1000.dp, 720.dp),
        )
        assertEquals(geometry(x = 100, y = 50, maximized = true), windowGeometryOf(maximized))
    }

    // 位置や大きさが替わっても、すぐには書かない。替わるたびに待ち直し、最後に替わってから待ち時間のあいだ替わらなくなってから、
    // 最後の値だけを書く。最初に替わってから待ち時間が過ぎても、そのあいだにまた替わっていれば書かない
    // runCurrent と advanceTimeBy が ExperimentalCoroutinesApi であるため。
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun changesAreWrittenAfterTheySettle() = runTest {
        val file = WindowGeometryFile(temporaryFolder.root.toPath().resolve("window.json")) {}
        val state = WindowState(position = WindowPosition(100.dp, 50.dp), size = DpSize(1000.dp, 720.dp))
        val saving = launch { saveWindowGeometryChanges(state, file, StandardTestDispatcher(testScheduler)) }
        runCurrent()

        state.position = WindowPosition(200.dp, 60.dp)
        Snapshot.sendApplyNotifications()
        runCurrent()
        advanceTimeBy(CHANGE_INTERVAL_MILLIS)
        runCurrent()
        state.size = DpSize(1200.dp, 800.dp)
        Snapshot.sendApplyNotifications()
        runCurrent()
        advanceTimeBy(WINDOW_GEOMETRY_SAVE_DELAY_MILLIS - 1)
        runCurrent()

        assertNull(file.read(), "最後に替わってから、待ち時間のうち")

        advanceTimeBy(2)
        runCurrent()

        assertEquals(WindowGeometry(x = 200, y = 60, width = 1200, height = 800, maximized = false), file.read())
        saving.cancel()
    }
}
