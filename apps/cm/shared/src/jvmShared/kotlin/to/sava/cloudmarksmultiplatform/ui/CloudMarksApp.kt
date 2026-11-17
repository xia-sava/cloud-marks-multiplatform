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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
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
import to.sava.cloudmarksmultiplatform.app.UndoableDelete
import to.sava.cloudmarksmultiplatform.app.WriteState
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
 * 画面全体を、表示の大きさ（[AppState.displayScale]）で拡大縮小して描く（[ScaledDensity]。ダイアログ・メニュー・ツールチップの中身は
 * [ScaledPopupContent]）。メインの画面と設定の画面の上で、Ctrl を押しながらホイールを縦に回すと、表示の大きさを 1 段ずつ替える
 * （[displayScaleWheel]）。
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
    ScaledDensity(state.displayScale) {
        MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
            Surface(modifier = Modifier.fillMaxSize().displayScaleWheel(controller)) {
                CloudMarksScreen(state, controller, panes)
            }
        }
    }
}

/**
 * 画面の幅の区分に合わせてメインの画面を組み、データを変える操作のダイアログと、項目を消したあとのお知らせと、ドラッグ中の札を重ねる。
 * ダイアログとドラッグの状態は、窓の幅の区分が替わっても保つ。ドラッグを始めたあとのポインターは、窓いっぱいの枠で追う（[moveDragTracker]）。
 */
@Composable
private fun CloudMarksScreen(state: AppState, controller: CloudMarksController, panes: MainPaneState) {
    val wide = currentWindowAdaptiveInfoV2().windowSizeClass
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    LaunchedEffect(wide) { controller.setWideLayout(wide) }

    var dialog by remember { mutableStateOf<OperationDialog?>(null) }
    val dragState = remember { MoveDragState() }
    val snackbarHostState = remember { SnackbarHostState() }
    val canWrite = state.canWrite
    // 受け取るリストとツリーの行が、状態が替わるたびに描き直されないよう、受け付けるかどうかが替わったときだけ作り直す
    val operations = remember(canWrite, controller, dragState) {
        OperationActions(
            canWrite = canWrite,
            dragState = dragState,
            onOpenDialog = { dialog = it },
            onDeleteSelectedItems = controller::deleteSelectedItems,
            onMove = controller::moveTo,
        )
    }
    // 同じく、PC に入っているブラウザの並びが替わったときだけ作り直す
    val browserActions = remember(state.browsers, controller) {
        BrowserActions(
            browsers = state.browsers,
            onOpenItem = controller::openItem,
            onOpenSelectedItems = controller::openSelectedItems,
            onOpenFolderItems = controller::openFolderItems,
            onMenuOpen = controller::refreshBrowsers,
        )
    }
    UndoDeleteNotice(
        undoable = state.write.undoableDelete,
        visible = state.screen == Screen.MAIN,
        hostState = snackbarHostState,
        onUndo = controller::undoDelete,
        onDismiss = controller::dismissUndo,
    )
    Box(Modifier.fillMaxSize().moveDragTracker(dragState, onDrop = operations.onMove)) {
        if (state.screen == Screen.MAIN && !wide) {
            NarrowMainScreen(state, controller, panes, operations, browserActions)
        } else {
            Column(Modifier.fillMaxSize()) {
                CloudMarksTopBar(state, controller, operations)
                when (state.screen) {
                    Screen.STARTING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    Screen.SETTINGS -> SettingsScreen(
                        settings = state.settings,
                        displayScale = state.displayScale,
                        tabLoading = state.tabLoading.takeIf { state.opensMultipleItems },
                        onEdit = controller::editSettings,
                        onSave = controller::saveSettings,
                        onCreateManifest = controller::createManifest,
                        onDisplayScaleChange = controller::setDisplayScale,
                        onTabLoadingChange = controller::setTabLoading,
                    )
                    Screen.MAIN -> {
                        MainBanners(state, controller)
                        WideMainBody(state.browse, controller, panes, operations, browserActions)
                    }
                }
            }
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
        MoveDragLabel(dragState, state.browse)
    }
    dialog?.let { OperationDialogHost(it, state, controller, onClose = { dialog = null }) }
}

/**
 * [visible] のあいだ（メインの画面を出しているあいだ）、元に戻せる項目の削除 [undoable] があれば、画面の下にお知らせを出す。
 * [UNDO_LABEL] を押したら [onUndo] を呼び、受け付けられなければ（false が返れば）、同じお知らせを出し直す。
 * 押さずに閉じたら（時間切れを含む）[onDismiss] を呼ぶ。[undoable] が替わるか無くなるか、[visible] が偽になったら、
 * 出しているお知らせを閉じる。
 */
@Composable
private fun UndoDeleteNotice(
    undoable: UndoableDelete?,
    visible: Boolean,
    hostState: SnackbarHostState,
    onUndo: () -> Boolean,
    onDismiss: () -> Unit,
) {
    val currentOnUndo by rememberUpdatedState(onUndo)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(undoable, visible) {
        if (undoable == null || !visible) return@LaunchedEffect
        while (true) {
            val result = hostState.showSnackbar(
                message = deletedItemsNotice(undoable.count),
                actionLabel = UNDO_LABEL,
                duration = SnackbarDuration.Long,
            )
            when (result) {
                SnackbarResult.ActionPerformed -> if (currentOnUndo()) return@LaunchedEffect
                SnackbarResult.Dismissed -> {
                    currentOnDismiss()
                    return@LaunchedEffect
                }
            }
        }
    }
}

/**
 * 狭い画面のメインの画面。ツリーはドロワーにし、ハンバーガーで開く。画面の端からのスワイプでは開かない
 * （閉じているあいだはジェスチャーを受けない）。開いたドロワーは、スワイプや外側を押す操作で閉じられる。
 */
@Composable
private fun NarrowMainScreen(
    state: AppState,
    controller: CloudMarksController,
    panes: MainPaneState,
    operations: OperationActions,
    browserActions: BrowserActions,
) {
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
                    actions = operations,
                    browserActions = browserActions,
                    onOpen = controller::openFolder,
                    onToggle = controller::toggleExpanded,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            CloudMarksTopBar(state, controller, operations)
            MainBanners(state, controller)
            FolderListPane(
                browse = browse,
                listState = panes.itemListState,
                actions = operations,
                browserActions = browserActions,
                onOpenFolder = controller::openFolder,
                onSelectItem = controller::selectItem,
                onClearSelection = controller::clearSelection,
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
        TooltipIconButton(Icons.AutoMirrored.Filled.ArrowBack, CLOSE_DRAWER_DESCRIPTION, onClick = onClose)
    }
}

/** 広い画面のメインの画面の本体。左にツリーの枠、右にリスト。境目をドラッグしてツリーの枠の幅を変える。 */
@Composable
private fun WideMainBody(
    browse: BrowseState,
    controller: CloudMarksController,
    panes: MainPaneState,
    operations: OperationActions,
    browserActions: BrowserActions,
) {
    var paneWidth by panes.treePaneWidth
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxPaneWidth = max(MIN_TREE_PANE_WIDTH, maxWidth * MAX_TREE_PANE_FRACTION)
        Row(Modifier.fillMaxSize()) {
            if (browse.treePaneVisible) {
                FolderTreePane(
                    browse = browse,
                    listState = panes.treeListState,
                    actions = operations,
                    browserActions = browserActions,
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
                actions = operations,
                browserActions = browserActions,
                onOpenFolder = controller::openFolder,
                onSelectItem = controller::selectItem,
                onClearSelection = controller::clearSelection,
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

/**
 * メインの画面の、AppBar の下に並べる帯。マニフェストを読んだ様子（[BrowseBanners]）と、操作を書いた様子（[WriteBanner]）と、
 * 項目をブラウザで開こうとして伝えること（[AppState.browserNotice]。[CLOSE_LABEL] を押すと閉じる）。
 */
@Composable
private fun MainBanners(state: AppState, controller: CloudMarksController) {
    BrowseBanners(state.browse, onDismissFailure = controller::dismissReadFailure)
    WriteBanner(state.write, onDismissOutcome = controller::dismissWriteOutcome)
    state.browserNotice?.let { Banner(describeBrowserNotice(it), onDismiss = controller::dismissBrowserNotice) }
}

/**
 * マニフェストを読んでいる最中のインジケーターと、読むだけにしていること・読み直しに失敗したことを伝える帯。
 * 読み直しに失敗したことの帯は、[CLOSE_LABEL] を押すと [onDismissFailure] を呼ぶ。
 */
@Composable
private fun BrowseBanners(browse: BrowseState, onDismissFailure: () -> Unit) {
    if (browse.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (browse.tree == null) return
    if (browse.readOnly) Banner(UiMessage(READ_ONLY_NOTICE))
    browse.failure?.let { Banner(describeSyncFailure(it), onDismiss = onDismissFailure) }
}

/**
 * データを変える操作を書いている最中と、書いた結果を伝える帯。書けて伝えることが無ければ出さない。
 * 書いた結果の帯は、[CLOSE_LABEL] を押すと [onDismissOutcome] を呼ぶ。
 */
@Composable
private fun WriteBanner(write: WriteState, onDismissOutcome: () -> Unit) {
    if (write.writing) {
        Banner(
            message = UiMessage(WRITING_NOTICE),
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        return
    }
    write.outcome?.let(::describeWriteOutcome)?.let { Banner(it, onDismiss = onDismissOutcome) }
}

/**
 * メインの画面の帯。色を指定しなければ、誤りの色で出す。
 *
 * @param onDismiss 帯の右端の [CLOSE_LABEL] を押したとき。null なら、ボタンを置かない
 */
@Composable
private fun Banner(
    message: UiMessage,
    containerColor: Color = MaterialTheme.colorScheme.errorContainer,
    contentColor: Color = MaterialTheme.colorScheme.onErrorContainer,
    onDismiss: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(containerColor)
            .padding(BANNER_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MessageText(message = message, color = contentColor, modifier = Modifier.weight(1f))
        onDismiss?.let { dismiss ->
            TextButton(onClick = dismiss, colors = ButtonDefaults.textButtonColors(contentColor = contentColor)) {
                Text(CLOSE_LABEL)
            }
        }
    }
}

/**
 * 最上部の AppBar。左にハンバーガーと戻るボタン、右に読み直すボタンと歯車。
 * メインの画面で項目を選んでいるあいだは、読み直すボタンの左に、選んでいる項目を消すボタンと移すボタンを出す。
 * どのボタンも、ボタンの説明をツールチップに出す。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudMarksTopBar(state: AppState, controller: CloudMarksController, operations: OperationActions) {
    val onMain = state.screen == Screen.MAIN
    TopAppBar(
        title = { Text(titleOf(state), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            Row {
                TooltipIconButton(Icons.Filled.Menu, TREE_PANE_DESCRIPTION, onClick = controller::toggleTreePane, enabled = onMain)
                TooltipIconButton(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    BACK_DESCRIPTION,
                    onClick = controller::back,
                    enabled = state.canGoBack,
                )
            }
        },
        actions = {
            val selectedItemIds = state.browse.selectedItemIds
            if (onMain && selectedItemIds.isNotEmpty()) {
                TooltipIconButton(
                    Icons.Filled.Delete,
                    DELETE_LABEL,
                    onClick = operations.onDeleteSelectedItems,
                    enabled = operations.canWrite,
                )
                TooltipIconButton(
                    MoveToFolderIcon,
                    MOVE_LABEL,
                    onClick = { operations.openMoveDialog(state.browse) },
                    enabled = operations.canWrite,
                )
            }
            TooltipIconButton(Icons.Filled.Refresh, RELOAD_DESCRIPTION, onClick = controller::reload, enabled = state.canReload)
            TooltipIconButton(Icons.Filled.Settings, SETTINGS_DESCRIPTION, onClick = controller::openSettings, enabled = onMain)
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
