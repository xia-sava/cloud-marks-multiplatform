package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.window.core.layout.WindowSizeClass
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import to.sava.cloudmarksmultiplatform.app.AppState
import to.sava.cloudmarksmultiplatform.app.CloudMarksController
import to.sava.cloudmarksmultiplatform.app.Screen
import to.sava.cloudmarksmultiplatform.browse.BrowseState

/** 広い画面での、ツリーの枠の幅の初め。 */
private val DEFAULT_TREE_PANE_WIDTH = 280.dp

/** ツリーの枠の幅の下限。 */
private val MIN_TREE_PANE_WIDTH = 160.dp

/** ツリーの枠の幅の上限の、画面の幅に対する割合。 */
private const val MAX_TREE_PANE_FRACTION = 0.6f

/** ツリーとリストの境目の、ドラッグを受ける幅。 */
private val PANE_DIVIDER_HANDLE_WIDTH = 8.dp

private val BANNER_PADDING = 12.dp

/** AppBar の、ナビゲーションのアイコンの左の余白。Material3 の `TopAppBar` の値と同じ。 */
private val APP_BAR_HORIZONTAL_PADDING = 4.dp

/**
 * メインの画面の見た目のうち、設定の画面を開いて戻っても、窓の幅の区分が替わっても保つもの。
 * メインの画面を出していないあいだも残すため、[CloudMarksApp] で持つ。
 *
 * @property treePaneWidth 広い画面での、ツリーの枠の幅。窓の幅に合わせて詰める前の値
 * @property treeListState ツリーのスクロール位置。広い画面のツリーの枠と、狭い画面のドロワーで同じものを使う
 * @property itemListState 開いているフォルダのリストのスクロール位置
 */
private data class MainPaneState(
    val treePaneWidth: MutableState<Dp>,
    val treeListState: LazyListState,
    val itemListState: LazyListState,
)

/**
 * Cloud Marks の画面。[controller] の状態を描き、利用者の操作を [controller] へ渡す。
 *
 * [controller] は呼ぶ側が `createCloudMarksController` で組み立て、[CloudMarksController.start] で動かし始め、
 * 使い終えたら [CloudMarksController.close] で閉じる。
 */
@Composable
fun CloudMarksApp(controller: CloudMarksController) {
    val state by controller.state.collectAsState()
    val panes = MainPaneState(
        treePaneWidth = remember { mutableStateOf(DEFAULT_TREE_PANE_WIDTH) },
        treeListState = rememberLazyListState(),
        // フォルダを替えたら、リストを先頭から見せる
        itemListState = remember(state.browse.currentFolderId) { LazyListState() },
    )
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            CloudMarksScreen(state, controller, panes)
        }
    }
}

@Composable
private fun CloudMarksScreen(state: AppState, controller: CloudMarksController, panes: MainPaneState) {
    val wide = currentWindowAdaptiveInfoV2().windowSizeClass
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    LaunchedEffect(wide) { controller.setWideLayout(wide) }

    if (state.screen == Screen.MAIN && !wide) {
        NarrowMainScreen(state, controller, panes)
        return
    }
    Column(Modifier.fillMaxSize()) {
        CloudMarksTopBar(state, controller)
        when (state.screen) {
            Screen.STARTING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            Screen.SETTINGS -> SettingsScreen(
                settings = state.settings,
                onEdit = controller::editSettings,
                onSave = controller::saveSettings,
                onCreateManifest = controller::createManifest,
            )
            Screen.MAIN -> {
                BrowseBanners(state.browse)
                WideMainBody(state.browse, controller, panes)
            }
        }
    }
}

/**
 * 狭い画面のメインの画面。ツリーはドロワーにし、ハンバーガーで開く。画面の端からのスワイプでは開かない
 * （閉じているあいだはジェスチャーを受けない）。開いたドロワーは、スワイプや外側を押す操作で閉じられる。
 */
@Composable
private fun NarrowMainScreen(state: AppState, controller: CloudMarksController, panes: MainPaneState) {
    val browse = state.browse
    val drawerState = rememberDrawerState(if (browse.drawerOpen) DrawerValue.Open else DrawerValue.Closed)
    LaunchedEffect(browse.drawerOpen) {
        if (browse.drawerOpen) drawerState.open() else drawerState.close()
    }
    // 画面の操作（スワイプ、外側を押す、Esc）でドロワーが閉じたら、状態の側も閉じる
    LaunchedEffect(drawerState) {
        snapshotFlow { drawerState.currentValue }
            .drop(1)
            .filter { it == DrawerValue.Closed }
            .collect { controller.closeDrawer() }
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet {
                DrawerTopRow(onClose = controller::closeDrawer)
                FolderTreePane(
                    browse = browse,
                    listState = panes.treeListState,
                    onOpen = controller::openFolder,
                    onToggle = controller::toggleExpanded,
                )
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            CloudMarksTopBar(state, controller)
            BrowseBanners(browse)
            FolderListPane(
                browse = browse,
                listState = panes.itemListState,
                onOpenFolder = controller::openFolder,
                onSelectItem = controller::selectItem,
            )
        }
    }
}

/**
 * ドロワーの上端の行。AppBar と同じ高さで、AppBar のハンバーガーと同じ位置・大きさに、ドロワーを閉じるボタンを置く。
 * ドロワーが開いているあいだ、ハンバーガーは覆われて押せないため、閉じる操作をその位置でできるようにする。
 */
@Composable
private fun DrawerTopRow(onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TopAppBarDefaults.TopAppBarExpandedHeight)
            .padding(start = APP_BAR_HORIZONTAL_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "フォルダの一覧を閉じる")
        }
    }
}

/** 広い画面のメインの画面の本体。左にツリーの枠、右にリスト。境目をドラッグしてツリーの枠の幅を変える。 */
@Composable
private fun WideMainBody(browse: BrowseState, controller: CloudMarksController, panes: MainPaneState) {
    var paneWidth by panes.treePaneWidth
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxPaneWidth = max(MIN_TREE_PANE_WIDTH, maxWidth * MAX_TREE_PANE_FRACTION)
        Row(Modifier.fillMaxSize()) {
            if (browse.treePaneVisible) {
                FolderTreePane(
                    browse = browse,
                    listState = panes.treeListState,
                    onOpen = controller::openFolder,
                    onToggle = controller::toggleExpanded,
                    modifier = Modifier.width(paneWidth.coerceIn(MIN_TREE_PANE_WIDTH, maxPaneWidth)).fillMaxHeight(),
                )
                PaneDivider(onDrag = { delta ->
                    paneWidth = (paneWidth.coerceIn(MIN_TREE_PANE_WIDTH, maxPaneWidth) + delta)
                        .coerceIn(MIN_TREE_PANE_WIDTH, maxPaneWidth)
                })
            }
            FolderListPane(
                browse = browse,
                listState = panes.itemListState,
                onOpenFolder = controller::openFolder,
                onSelectItem = controller::selectItem,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

/** ツリーとリストの境目。横にドラッグすると、動かした長さを [onDrag] に渡す。 */
@Composable
private fun PaneDivider(onDrag: (Dp) -> Unit) {
    val density = LocalDensity.current
    val currentOnDrag by rememberUpdatedState(onDrag)
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(PANE_DIVIDER_HANDLE_WIDTH)
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta -> currentOnDrag(with(density) { delta.toDp() }) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        VerticalDivider()
    }
}

/** マニフェストを読んでいる最中のインジケーターと、読むだけにしていること・読み直しに失敗したことの知らせ。 */
@Composable
private fun BrowseBanners(browse: BrowseState) {
    if (browse.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (browse.tree == null) return
    if (browse.readOnly) Banner(UiMessage(READ_ONLY_NOTICE))
    browse.failure?.let { Banner(describeSyncFailure(it)) }
}

@Composable
private fun Banner(message: UiMessage) {
    MessageText(
        message = message,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(BANNER_PADDING),
    )
}

/** 最上部の AppBar。左にハンバーガーと戻るボタン、右に読み直すボタンと歯車。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudMarksTopBar(state: AppState, controller: CloudMarksController) {
    val onMain = state.screen == Screen.MAIN
    TopAppBar(
        title = { Text(titleOf(state), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            Row {
                IconButton(onClick = controller::toggleTreePane, enabled = onMain) {
                    Icon(Icons.Filled.Menu, contentDescription = "フォルダの一覧")
                }
                IconButton(onClick = controller::back, enabled = state.canGoBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                }
            }
        },
        actions = {
            IconButton(onClick = controller::reload, enabled = state.canReload) {
                Icon(Icons.Filled.Refresh, contentDescription = "再読み込み")
            }
            IconButton(onClick = controller::openSettings, enabled = onMain) {
                Icon(Icons.Filled.Settings, contentDescription = "設定")
            }
        },
    )
}

/** AppBar の題。メインの画面では開いているフォルダの名前、設定の画面では「設定」。 */
private fun titleOf(state: AppState): String =
    when (state.screen) {
        Screen.STARTING -> APP_TITLE
        Screen.SETTINGS -> SETTINGS_TITLE
        Screen.MAIN -> state.browse.currentFolder?.let { folder ->
            if (folder.parent == null) ROOT_FOLDER_LABEL else folder.name
        } ?: APP_TITLE
    }
