package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.app.ControllerFixture
import to.sava.cloudmarksmultiplatform.browser.FakeBrowserOpener
import to.sava.cloudmarksmultiplatform.browser.FakeItemSharer
import to.sava.cloudmarksmultiplatform.storage.Item
import to.sava.cloudmarksmultiplatform.sync.FolderReadResult
import to.sava.cloudmarksmultiplatform.sync.ROOT_ID
import to.sava.cloudmarksmultiplatform.sync.fileId
import to.sava.cloudmarksmultiplatform.sync.item
import to.sava.cloudmarksmultiplatform.sync.replaceItemsAsOtherWriter
import to.sava.cloudmarksmultiplatform.sync.seed
import to.sava.cloudmarksmultiplatform.sync.storedItems
import to.sava.cloudmarksmultiplatform.sync.storedManifest
import kotlin.coroutines.ContinuationInterceptor

/** 画面のテストで、起動して開いているフォルダの項目が出るまで待つ時間の上限。実際の時間で数える。 */
private const val STARTUP_TIMEOUT_MILLIS = 5_000L

/** ドラッグを始めるために、押した位置から動かす量。ドラッグを始める閾値（[MOVE_DRAG_THRESHOLD]）より長い。 */
internal val BEYOND_THRESHOLD = Offset(MOVE_DRAG_THRESHOLD.value * 2, 0f)

/**
 * 行の上の端・下の端に落とすときの、行の上端・下端からの距離（画素。テストの画面の密度は 1 なので dp と同じ）。
 * ツリーの行の高さの 1/4 より短い。
 */
private const val EDGE_INSET = 2f

/**
 * 偽のストレージに [to.sava.cloudmarksmultiplatform.sync.initialManifest] のツリーを置き、保存済みの設定で起動したコントローラの
 * 画面を出して、[block] を走らせる。ルートフォルダの項目を読み終えてから [block] を始める。[arrange] は起動の前に走らせる。
 *
 * 画面は窓を出さずに描く。テストの画面の大きさ（1024 × 768、密度 1）は広い画面の区分なので、ツリーの枠とリストを並べて出す。
 * コントローラの処理は、画面と同じディスパッチャで走らせる。そのため、画面のテストが待つとき（waitForIdle など）は、
 * 偽のストレージへの読み書きが終わるまで待つ（偽のストレージは時間を使わない）。止めた読み書きは、止めているあいだは待たない。
 *
 * @param browserOpener コントローラが項目をブラウザで開く仕組み。既定では、まとめて開けるプラットフォームとして振る舞う
 * @param itemSharer コントローラが項目をほかのアプリで開く仕組みと共有する仕組み。既定の null では、持たないプラットフォームとして振る舞う
 * @param touchScreen 画面を、タッチの画面の振る舞い（[LocalTouchScreen]）で動かすか。偽なら、プラットフォームの値（デスクトップの振る舞い）のまま
 * 動かす
 * @param containerSize 窓の大きさ（[SizedWindow]）。値を替えると、端末を回したときと同じく、描く範囲と窓の幅の区分が替わる。
 * null なら、テストの画面の大きさのまま描く
 */
@OptIn(ExperimentalTestApi::class)
internal fun runCloudMarksScreenTest(
    temporaryFolder: TemporaryFolder,
    arrange: (ControllerFixture) -> Unit = {},
    browserOpener: FakeBrowserOpener = FakeBrowserOpener(),
    itemSharer: FakeItemSharer? = null,
    touchScreen: Boolean = false,
    containerSize: State<IntSize>? = null,
    block: suspend ComposeUiTest.(ControllerFixture) -> Unit,
) = runComposeUiTest {
    var created: ControllerFixture? = null
    setContent {
        val scope = rememberCoroutineScope()
        val fixture = remember {
            val dispatcher = scope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
            ControllerFixture(scope, dispatcher, temporaryFolder, browserOpener = browserOpener, itemSharer = itemSharer).also {
                it.store.seed()
                it.storeSettings()
                arrange(it)
                created = it
            }
        }
        DisposableEffect(fixture) {
            fixture.controller.start()
            onDispose { fixture.controller.close() }
        }
        CompositionLocalProvider(LocalTouchScreen provides (touchScreen || usesTouchScreen)) {
            if (containerSize == null) {
                CloudMarksApp(fixture.controller)
            } else {
                SizedWindow(containerSize) { CloudMarksApp(fixture.controller) }
            }
        }
    }
    val fixture = checkNotNull(created)
    waitUntil(timeoutMillis = STARTUP_TIMEOUT_MILLIS) { fixture.state.browse.currentItems is FolderReadResult.Loaded }
    waitForIdle()
    block(fixture)
}

/**
 * テストの画面の左上の、大きさが [size] の枠に [content] を描き、窓の大きさ（[LocalWindowInfo] の [WindowInfo.containerSize]）も [size] にする。
 * [size] を替えると、端末を回したときと同じく、描く範囲と画面の幅の区分が替わる。[size] はテストの画面の大きさに収める。
 */
@Composable
private fun SizedWindow(size: State<IntSize>, content: @Composable () -> Unit) {
    val window = LocalWindowInfo.current
    val windowInfo = remember(window, size) {
        object : WindowInfo by window {
            override val containerSize: IntSize get() = size.value
        }
    }
    val dpSize = with(LocalDensity.current) { size.value.toSize().toDpSize() }
    CompositionLocalProvider(LocalWindowInfo provides windowInfo) {
        Box(Modifier.size(dpSize)) { content() }
    }
}

/**
 * ツリーの、名前が [name] のフォルダの行。リストの子のフォルダの行にも同じ名前が出るが、ツリーの枠はリストより前に並ぶので、
 * 同じ名前の行のうち最初のものがツリーの行になる。
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.treeRow(name: String): SemanticsNodeInteraction = onAllNodesWithText(name)[0]

/** リストの、名前が [name] の子のフォルダの行。ツリーの行（[treeRow]）の次に並ぶ。 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.listFolderRow(name: String): SemanticsNodeInteraction = onAllNodesWithText(name)[1]

/** リストの、項目 [n]（[item]）の行。 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.itemRow(n: Int): SemanticsNodeInteraction = onNodeWithText(item(n).title)

/** 文が [text] の要素（メニューの行など）を、タッチで押し、描き終えるまで待つ。タッチの画面の操作は、タッチで押す。 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.tapText(text: String) {
    onNodeWithText(text).performTouchInput { click() }
    waitForIdle()
}

/** リストの項目 [n]（[item]）を、[key] を押しながら、マウスでクリックする。Ctrl で選ぶ項目を足す・外し、Shift で範囲を選ぶ。 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.clickItemRowWithKey(n: Int, key: Key) {
    onRoot().performKeyInput { keyDown(key) }
    itemRow(n).performMouseInput { click() }
    onRoot().performKeyInput { keyUp(key) }
}

/** リストの、項目 [n]（[item]）の行の上端（根の座標）。リストに無ければ（スクロールして外れていれば）null。 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.itemRowTop(n: Int): Float? =
    onAllNodesWithText(item(n).title).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.top

/** 長いリストの項目の数。項目の行は 40 画素ほどなので、テストの画面（高さ 768）に収まらない。 */
private const val LONG_LIST_SIZE = 40

/** テストの画面に収まらない、ルートフォルダの長いリストの項目。 */
internal val LONG_ROOT_ITEMS: List<Item> = (1..LONG_LIST_SIZE).map { item(it) }

/** ルートフォルダの項目を [LONG_ROOT_ITEMS] にする、起動の前の用意。 */
internal val longRootItems: (ControllerFixture) -> Unit = {
    it.store.replaceItemsAsOtherWriter(ROOT_ID, fileId(30), LONG_ROOT_ITEMS)
}

/** 行の中の、ポインターを置く帯。 */
internal enum class RowSpot { TOP_EDGE, MIDDLE, BOTTOM_EDGE }

/** 行 [this] の、帯 [spot] の中の位置。根の座標。上下の端は、行の上端・下端から [EDGE_INSET] の位置。 */
internal fun SemanticsNodeInteraction.spot(spot: RowSpot): Offset {
    val bounds = fetchSemanticsNode().boundsInRoot
    return when (spot) {
        RowSpot.TOP_EDGE -> Offset(bounds.center.x, bounds.top + EDGE_INSET)
        RowSpot.MIDDLE -> bounds.center
        RowSpot.BOTTOM_EDGE -> Offset(bounds.center.x, bounds.bottom - EDGE_INSET)
    }
}

/**
 * 根の座標の [from] をマウスの主ボタンで押し、閾値を越えて動かしてから、[to] へ動かして離す。書き終えるまで待つ。
 * 入力は [root] へ渡す。ポップアップの窓が重なって根が複数あるときは、窓の中身の根を渡す。
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.dragWithMouse(from: Offset, to: Offset, root: SemanticsNodeInteraction = onRoot()) {
    root.performMouseInput {
        moveTo(from)
        press()
        moveBy(BEYOND_THRESHOLD)
        moveTo(to)
        release()
    }
    waitForIdle()
}

/** S3 に置いてあるマニフェストが指す、フォルダ [folderId] の項目。 */
internal fun ControllerFixture.storedItems(folderId: String): List<Item> = store.storedItems(store.storedManifest(), folderId)

/** S3 に置いてあるマニフェストの、フォルダ [folderId] の子のフォルダの ID。マニフェストの行の順。 */
internal fun ControllerFixture.storedChildren(folderId: String): List<String> =
    store.storedManifest().folders.filter { it.parent == folderId }.map { it.id }
