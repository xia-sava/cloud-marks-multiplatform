package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
import to.sava.cloudmarksmultiplatform.app.WriteOutcome
import to.sava.cloudmarksmultiplatform.app.WriteState
import to.sava.cloudmarksmultiplatform.browse.BrowseState
import to.sava.cloudmarksmultiplatform.sync.counts

/** 広い画面での、ツリーの枠の幅の初め。 */
internal val DEFAULT_TREE_PANE_WIDTH = 280.dp

/** ツリーの枠の幅の下限。 */
internal val MIN_TREE_PANE_WIDTH = 160.dp

/** ツリーの枠の幅の上限の、画面の幅に対する割合。 */
internal const val MAX_TREE_PANE_FRACTION = 0.6f

/** ツリーとリストの境目の、ドラッグを受ける幅。 */
internal val PANE_DIVIDER_HANDLE_WIDTH = 8.dp

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
 * [ScaledPopupContent]）。メインの画面・設定の画面・以前の状態に戻す画面の上で、Ctrl を押しながらホイールを縦に回すと、表示の大きさを 1 段ずつ替える
 * （[displayScaleWheel]）。画面の中身は、システムバー・画面の切り欠き・キーボードに重ならないように置く。
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
                CloudMarksScreen(state, controller, panes, Modifier.safeDrawingPadding())
            }
        }
    }
}

/**
 * メインの画面の部品に渡す、いまの状態と操作の入口。
 *
 * @property shareActions 項目をほかのアプリで開く操作と共有する操作の入口。できないプラットフォームでは null
 */
private data class MainScreenContent(
    val state: AppState,
    val panes: MainPaneState,
    val operations: OperationActions,
    val browserActions: BrowserActions,
    val shareActions: ItemShareActions?,
)

/**
 * メインの画面の、狭い画面と広い画面の両方で使う部品。どれも [movableContentOf] で作り、窓の幅の区分が替わったら、中の状態
 * （行や AppBar が出しているメニュー、リストとツリーの中で覚えていることなど）を保ったまま、替わった区分の画面へ移す。
 * 窓の幅の区分が替わっても作り直さないので、出しているメニューは閉じず、ツリーは開いているフォルダの行までスクロールし直さない
 * （[FolderTreePane] がスクロールし直すのは、開いているフォルダが替わったときと、ツリーを新しく出したとき）。
 *
 * 部品を呼ぶ場所は、レイアウトのときに中身を組む部品（[androidx.compose.foundation.layout.BoxWithConstraints] など）の中に置かない。
 * Compose は、外した中身を、そのフレームの組み立てを終えるまでにどこかで呼ばなければ捨てるが、レイアウトのときに組む中身は、
 * それより後に組むことがある。
 *
 * @property topBar 最上部の AppBar（[CloudMarksTopBar]）
 * @property treePane フォルダのツリー（[FolderTreePane]）。渡した修飾を、ツリーの枠に付ける
 * @property listPane 開いているフォルダのリスト（[FolderListPane]）。渡した修飾を、リストの枠に付ける
 */
private class MainScreenParts(
    val topBar: @Composable (MainScreenContent) -> Unit,
    val treePane: @Composable (MainScreenContent, Modifier) -> Unit,
    val listPane: @Composable (MainScreenContent, Modifier) -> Unit,
)

/** [controller] の操作を呼ぶ、メインの画面の部品（[MainScreenParts]）。 */
@Composable
private fun rememberMainScreenParts(controller: CloudMarksController): MainScreenParts =
    remember(controller) {
        MainScreenParts(
            topBar = movableContentOf { content: MainScreenContent ->
                CloudMarksTopBar(content.state, controller, content.operations)
            },
            treePane = movableContentOf { content: MainScreenContent, modifier: Modifier ->
                FolderTreePane(
                    browse = content.state.browse,
                    listState = content.panes.treeListState,
                    actions = content.operations,
                    browserActions = content.browserActions,
                    onOpen = controller::openFolder,
                    onToggle = controller::toggleExpanded,
                    modifier = modifier,
                )
            },
            listPane = movableContentOf { content: MainScreenContent, modifier: Modifier ->
                FolderListPane(
                    browse = content.state.browse,
                    lastOpenedItemId = content.state.lastOpenedItemId,
                    listState = content.panes.itemListState,
                    actions = content.operations,
                    browserActions = content.browserActions,
                    shareActions = content.shareActions,
                    onOpenFolder = controller::openFolder,
                    onSelectItem = controller::selectItem,
                    onClearSelection = controller::clearSelection,
                    modifier = modifier,
                )
            },
        )
    }

/**
 * 画面の幅の区分に合わせてメインの画面を組み、データを変える操作のダイアログと、項目を消したあとのお知らせと、以前の状態に戻せたお知らせと、
 * ドラッグ中の札を重ねる。
 * ダイアログとドラッグの状態は、窓の幅の区分が替わっても保つ。AppBar とリストとツリーは、窓の幅の区分が替わっても作り直さずに移す
 * （[MainScreenParts]）。ドラッグを始めたあとのポインターは、窓いっぱいの枠で追う（[moveDragTracker]）。
 * タッチの画面（[LocalTouchScreen]）では、システムの戻る操作を受け、[systemBackOf] が決めたことを行う。することが無いあいだは受けない。
 *
 * @param modifier 画面の枠に付ける修飾。[CloudMarksApp] は、システムバー・画面の切り欠き・キーボードに重ならない余白を付ける
 */
@Composable
private fun CloudMarksScreen(state: AppState, controller: CloudMarksController, panes: MainPaneState, modifier: Modifier) {
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
            onDeleteItem = { itemId -> controller.deleteItems(setOf(itemId)) },
            onMove = controller::moveTo,
        )
    }
    // 同じく、この端末に入っているブラウザの並びが替わったときだけ作り直す
    val browserActions = remember(state.browsers, controller) {
        BrowserActions(
            browsers = state.browsers,
            onOpenItem = controller::openItem,
            onOpenSelectedItems = controller::openSelectedItems,
            onOpenFolderItems = controller::openFolderItems,
            onMenuOpen = controller::refreshBrowsers,
        )
    }
    val shareActions = remember(state.sharesItems, controller) {
        ItemShareActions(onOpenInOtherApp = controller::openItemInOtherApp, onShare = controller::shareItem).takeIf { state.sharesItems }
    }
    val parts = rememberMainScreenParts(controller)
    val content = MainScreenContent(state, panes, operations, browserActions, shareActions)
    UndoDeleteNotice(
        undoable = state.write.undoableDelete,
        visible = state.screen == Screen.MAIN,
        hostState = snackbarHostState,
        onUndo = controller::undoDelete,
        onDismiss = controller::dismissUndo,
    )
    RestoredNotice(
        restored = state.write.outcome as? WriteOutcome.Restored,
        visible = state.screen == Screen.MAIN,
        hostState = snackbarHostState,
        onDismiss = controller::dismissWriteOutcome,
    )
    Box(modifier.fillMaxSize().moveDragTracker(dragState, onDrop = operations.onMove)) {
        if (state.screen == Screen.MAIN && !wide) {
            NarrowMainScreen(content, controller, parts)
        } else {
            Column(Modifier.fillMaxSize()) {
                parts.topBar(content)
                when (state.screen) {
                    Screen.STARTING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    Screen.SETTINGS -> SettingsScreen(
                        settings = state.settings,
                        displayScale = state.displayScale,
                        tabLoading = state.tabLoading.takeIf { state.opensMultipleItems },
                        canRestore = state.canRestore,
                        onEdit = controller::editSettings,
                        onSave = controller::saveSettings,
                        onCreateManifest = controller::createManifest,
                        onDisplayScaleChange = controller::setDisplayScale,
                        onTabLoadingChange = controller::setTabLoading,
                        onOpenRevisions = controller::openRevisions,
                    )
                    Screen.REVISIONS -> RevisionsScreen(
                        revisions = state.revisions,
                        currentCounts = state.browse.tree?.manifest?.counts(),
                        canRestore = state.canRestore,
                        onSelect = controller::selectRevision,
                        onRestore = controller::restoreSelectedRevision,
                        onDismissConfirmation = controller::dismissRevisionSelection,
                        onDismissNotice = controller::dismissRevisionNotice,
                    )
                    Screen.MAIN -> {
                        MainBanners(state, controller)
                        WideMainBody(content, parts)
                    }
                }
            }
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
        MoveDragLabel(dragState, state.browse)
    }
    dialog?.let { OperationDialogHost(it, state, controller, onClose = { dialog = null }) }
    val touchScreen = LocalTouchScreen.current
    SystemBackHandler(enabled = touchScreen && systemBackOf(state) != null) {
        systemBackOf(controller.state.value)?.let(controller::perform)
    }
}

/**
 * [visible] のあいだ（メインの画面を出しているあいだ）、元に戻せる項目の削除 [undoable] があれば、画面の下にお知らせを出す。
 * 本文は、溜めた削除（[UndoableDelete.pending]）なら [pendingDeletedItemsNotice]、そうでなければ [deletedItemsNotice]。
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
                message = if (undoable.pending) pendingDeletedItemsNotice(undoable.count) else deletedItemsNotice(undoable.count),
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
 * [visible] のあいだ（メインの画面を出しているあいだ）、以前の状態に戻せた結果 [restored] があれば、画面の下に、戻した時点の日時を入れた
 * お知らせ（[restoredNotice]）を出す。ボタンは置かない。閉じたら（時間切れを含む）[onDismiss] を呼ぶ。[restored] が替わるか無くなるか、
 * [visible] が偽になったら、出しているお知らせを閉じる。
 */
@Composable
private fun RestoredNotice(
    restored: WriteOutcome.Restored?,
    visible: Boolean,
    hostState: SnackbarHostState,
    onDismiss: () -> Unit,
) {
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(restored, visible) {
        if (restored == null || !visible) return@LaunchedEffect
        hostState.showSnackbar(
            message = restoredNotice(formatRevisionTime(restored.lastModified)),
            duration = SnackbarDuration.Long,
        )
        currentOnDismiss()
    }
}

/**
 * 狭い画面のメインの画面。ツリーはドロワーにし、ハンバーガーで開く。画面の端からのスワイプでは開かない
 * （閉じているあいだはジェスチャーを受けない）。開いたドロワーは、スワイプや外側を押す操作で閉じられる。
 */
@Composable
private fun NarrowMainScreen(content: MainScreenContent, controller: CloudMarksController, parts: MainScreenParts) {
    val browse = content.state.browse
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
                parts.treePane(content, Modifier.fillMaxSize())
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            parts.topBar(content)
            MainBanners(content.state, controller)
            parts.listPane(content, Modifier)
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

/** 広い画面のメインの画面の本体の、並べる枠。 */
private enum class WideMainSlot { TREE, DIVIDER, LIST }

/** 広い画面のメインの画面の本体の、ツリーの枠の幅の上限。本体を測るたびに、本体の幅から求め直す。 */
private class TreePaneWidthLimit {
    var max: Dp = MIN_TREE_PANE_WIDTH
}

/**
 * 広い画面のメインの画面の本体。左にツリーの枠、右にリスト。境目をドラッグしてツリーの枠の幅を変える。
 * ツリーの枠の幅は、[MIN_TREE_PANE_WIDTH] から、本体の幅の [MAX_TREE_PANE_FRACTION] まで（下限より狭ければ下限）に詰める。
 *
 * ツリーとリストは、[Layout] で並べる（[MainScreenParts]）。
 */
@Composable
private fun WideMainBody(content: MainScreenContent, parts: MainScreenParts) {
    var paneWidth by content.panes.treePaneWidth
    val limit = remember { TreePaneWidthLimit() }
    Layout(
        content = {
            if (content.state.browse.treePaneVisible) {
                parts.treePane(content, Modifier.layoutId(WideMainSlot.TREE))
                PaneDivider(
                    onDrag = { delta ->
                        paneWidth = (paneWidth.coerceIn(MIN_TREE_PANE_WIDTH, limit.max) + delta).coerceIn(MIN_TREE_PANE_WIDTH, limit.max)
                    },
                    modifier = Modifier.layoutId(WideMainSlot.DIVIDER),
                )
            }
            parts.listPane(content, Modifier.layoutId(WideMainSlot.LIST))
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        limit.max = max(MIN_TREE_PANE_WIDTH, constraints.maxWidth.toDp() * MAX_TREE_PANE_FRACTION)
        val height = constraints.maxHeight
        val slotOf = { slot: WideMainSlot -> measurables.firstOrNull { it.layoutId == slot } }
        val tree = slotOf(WideMainSlot.TREE)?.measure(
            Constraints.fixed(paneWidth.coerceIn(MIN_TREE_PANE_WIDTH, limit.max).roundToPx().coerceAtMost(constraints.maxWidth), height),
        )
        val divider = slotOf(WideMainSlot.DIVIDER)?.measure(
            Constraints(maxWidth = constraints.maxWidth - (tree?.width ?: 0), minHeight = height, maxHeight = height),
        )
        val listWidth = (constraints.maxWidth - (tree?.width ?: 0) - (divider?.width ?: 0)).coerceAtLeast(0)
        val list = slotOf(WideMainSlot.LIST)?.measure(Constraints.fixed(listWidth, height))
        layout(constraints.maxWidth, height) {
            var x = 0
            listOfNotNull(tree, divider, list).forEach { placeable ->
                placeable.placeRelative(x, 0)
                x += placeable.width
            }
        }
    }
}

/** ツリーとリストの境目。横にドラッグすると、動かした長さを [onDrag] に渡す。 */
@Composable
private fun PaneDivider(onDrag: (Dp) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val currentOnDrag by rememberUpdatedState(onDrag)
    Box(
        modifier = modifier
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
 * 溜めた削除があること（[PendingDeletesBanner]）と、項目をブラウザで開こうとして伝えること（[AppState.browserNotice]。
 * [CLOSE_LABEL] を押すと閉じる）。
 */
@Composable
private fun MainBanners(state: AppState, controller: CloudMarksController) {
    BrowseBanners(state.browse, onDismissFailure = controller::dismissReadFailure)
    WriteBanner(state.write, onDismissOutcome = controller::dismissWriteOutcome)
    PendingDeletesBanner(state.browse.pendingDeletes.size)
    state.browserNotice?.let { Banner(describeBrowserNotice(it), onDismiss = controller::dismissBrowserNotice) }
}

/** 溜めた削除が [count] 件あれば、そのことを伝える帯（[pendingDeletesNotice]）。閉じるボタンは置かず、溜めた削除が無くなるまで出す。 */
@Composable
private fun PendingDeletesBanner(count: Int) {
    if (count == 0) return
    StatusBanner(UiMessage(pendingDeletesNotice(count)))
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
        StatusBanner(UiMessage(WRITING_NOTICE))
        return
    }
    write.outcome?.let(::describeWriteOutcome)?.let { Banner(it, onDismiss = onDismissOutcome) }
}

/**
 * メインの画面と以前の状態に戻す画面の帯。色を指定しなければ、誤りの色で出す。
 *
 * @param onDismiss 帯の右端の [CLOSE_LABEL] を押したとき。null なら、ボタンを置かない
 */
@Composable
internal fun Banner(
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
 * 誤りではない、いまの様子を伝える帯。[Banner] を、誤りの色の代わりに secondaryContainer の色で出し、閉じるボタンは置かない。
 * 操作を書いている最中・溜めた削除があること・以前の状態に戻している最中に使う。
 */
@Composable
internal fun StatusBanner(message: UiMessage) {
    Banner(
        message = message,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )
}

/**
 * 最上部の AppBar。左にハンバーガーと戻るボタン、右に読み直すボタンと歯車。
 * メインの画面で項目を選んでいるあいだは、読み直すボタンの左に、選んでいる項目を消すボタンと移すボタンを出す。
 * タッチの画面（[LocalTouchScreen]）では、メインの画面で項目を選んでいるあいだ、選ぶ状態の AppBar（[SelectionTopBar]）に替える。
 * どのボタンも、ボタンの説明をツールチップに出す。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudMarksTopBar(state: AppState, controller: CloudMarksController, operations: OperationActions) {
    val onMain = state.screen == Screen.MAIN
    val selecting = onMain && state.browse.selectedItemIds.isNotEmpty()
    if (selecting && LocalTouchScreen.current) {
        SelectionTopBar(state, controller, operations)
        return
    }
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
            if (selecting) SelectedItemsButtons(state.browse, operations)
            TooltipIconButton(Icons.Filled.Refresh, RELOAD_DESCRIPTION, onClick = controller::reload, enabled = state.canReload)
            TooltipIconButton(Icons.Filled.Settings, SETTINGS_DESCRIPTION, onClick = controller::openSettings, enabled = onMain)
        },
    )
}

/**
 * タッチの画面の、メインの画面で項目を選んでいるあいだの AppBar。左に選ぶのをやめるボタン、題に選んでいる項目の数、
 * 右に選んでいる項目を消すボタンと移すボタン（[SelectedItemsButtons]）。選んでいる項目が 1 つだけなら、その右に、
 * その他のオプションのボタン（[MoreOptionsButton]）を出す。
 * どのボタンも、ボタンの説明をツールチップに出す。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(state: AppState, controller: CloudMarksController, operations: OperationActions) {
    val count = state.browse.selectedItemIds.size
    val selectedItem = state.browse.selectedItems.singleOrNull()
    TopAppBar(
        title = { Text(selectionTitle(count), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            TooltipIconButton(Icons.Filled.Close, CLEAR_SELECTION_DESCRIPTION, onClick = controller::clearSelection)
        },
        actions = {
            SelectedItemsButtons(state.browse, operations)
            if (count == 1 && selectedItem != null) {
                MoreOptionsButton(
                    sharesItems = state.sharesItems,
                    canWrite = operations.canWrite,
                    onOpenInOtherApp = controller::openSelectedItemInOtherApp,
                    onShare = controller::shareSelectedItem,
                    onRename = {
                        operations.onOpenDialog(OperationDialog.RenameItem(selectedItem.id, selectedItem.title))
                        controller.clearSelection()
                    },
                )
            }
        },
    )
}

/** AppBar の、選んでいる項目を消すボタンと移すボタン。操作を受け付けないあいだ（[OperationActions.canWrite]）は押せない。 */
@Composable
private fun SelectedItemsButtons(browse: BrowseState, operations: OperationActions) {
    TooltipIconButton(
        Icons.Filled.Delete,
        DELETE_LABEL,
        onClick = operations.onDeleteSelectedItems,
        enabled = operations.canWrite,
    )
    TooltipIconButton(
        MoveToFolderIcon,
        MOVE_LABEL,
        onClick = { operations.openMoveDialog(browse) },
        enabled = operations.canWrite,
    )
}

/**
 * AppBar の、その他のオプションのボタン。押すと、ボタンの下にメニュー（[MoreOptionsMenuItems]）を出す。
 * メニューの行を押したら、メニューを閉じてから、その行の操作を呼ぶ。
 *
 * @param sharesItems ほかのアプリで開く行と共有の行を出すか
 * @param canWrite タイトルを変更する行を押せるか
 * @param onOpenInOtherApp 選んでいる項目を、ほかのアプリで開く
 * @param onShare 選んでいる項目を共有する
 * @param onRename 選んでいる項目のタイトルを変える
 */
@Composable
private fun MoreOptionsButton(
    sharesItems: Boolean,
    canWrite: Boolean,
    onOpenInOtherApp: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
) {
    val menu = rememberContextMenuState()
    Box {
        TooltipIconButton(Icons.Filled.MoreVert, MORE_OPTIONS_DESCRIPTION, onClick = { menu.open(Offset.Zero) })
        ContextMenu(menu) {
            MoreOptionsMenuItems(
                sharesItems = sharesItems,
                canWrite = canWrite,
                onOpenInOtherApp = {
                    menu.close()
                    onOpenInOtherApp()
                },
                onShare = {
                    menu.close()
                    onShare()
                },
                onRename = {
                    menu.close()
                    onRename()
                },
            )
        }
    }
}

/** AppBar の題。メインの画面では開いているフォルダの名前、設定の画面では「設定」、以前の状態に戻す画面では [REVISIONS_TITLE]。 */
private fun titleOf(state: AppState): String =
    when (state.screen) {
        Screen.STARTING -> APP_TITLE
        Screen.SETTINGS -> SETTINGS_TITLE
        Screen.REVISIONS -> REVISIONS_TITLE
        Screen.MAIN -> state.browse.currentFolder?.let { folder ->
            if (folder.parent == null) ROOT_FOLDER_LABEL else folder.name
        } ?: APP_TITLE
    }
