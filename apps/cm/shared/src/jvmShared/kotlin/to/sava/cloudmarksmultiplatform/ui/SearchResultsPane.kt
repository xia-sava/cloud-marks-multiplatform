package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerType
import to.sava.cloudmarksmultiplatform.browse.BrowseState
import to.sava.cloudmarksmultiplatform.browse.ItemSearchResult
import to.sava.cloudmarksmultiplatform.browse.searchItems
import to.sava.cloudmarksmultiplatform.browse.searchTerms
import to.sava.cloudmarksmultiplatform.operations.ItemRef

/** 検索の結果の、一致する項目が無いことを見せる行のキー。 */
private const val NO_RESULTS_KEY = "no-results"

/** 検索の結果の、項目の行のキー。項目と、その項目が入っているフォルダの組から作る。 */
private fun resultKeyOf(ref: ItemRef): String = "${ref.folderId}/${ref.itemId}"

/**
 * 検索の結果の枠。検索を開いているあいだ、開いているフォルダのリスト（[FolderListPane]）の代わりに出す。
 * 入力した語（[to.sava.cloudmarksmultiplatform.browse.ItemSearchState.query]）に合う項目（[searchItems]）を、ツリーの順に 1 行ずつ並べる
 * （[SearchResultRow]）。結果は、ツリー・フォルダの項目を読んだ結果・溜めた削除・入力した語が替わったときだけ計算し直す。
 * 語が無い（空か、空白だけ）あいだは、何も出さない。
 *
 * 検索のための読み込みの最中（[BrowseState.searchLoading]）は、一番上に進み具合の線を出し、一致する項目が無くても、そのことを伝える文
 * （[NO_SEARCH_RESULTS_NOTICE]）は出さない。読み終えて一致する項目が無ければ、その文を出す。
 * マニフェストを読んでいる最中（[BrowseState.loading]）は、AppBar の下に進み具合の線を出すので、この枠には線を出さない。
 * 項目を読めなかったフォルダ（[BrowseState.unreadableFoldersForSearch]）があれば、結果の上に、その数を伝える帯
 * （[unreadableFoldersNotice]）を出す。帯に閉じるボタンは置かない。
 * 右端に縦のスクロールバー（[ListVerticalScrollbar]）を出す。
 *
 * @param lastOpenedItemId 最後に開いた項目の ID（[to.sava.cloudmarksmultiplatform.app.AppState.lastOpenedItemId]）。覚えていなければ null
 * @param listState スクロール位置。入力した語が替わったら、呼ぶ側が新しいものを渡す
 * @param actions データを変える操作の入口
 * @param browserActions 項目をブラウザで開く操作の入口
 * @param shareActions 項目をほかのアプリで開く操作と共有する操作の入口。できないプラットフォームでは null
 * @param onOpenFolderOf 項目が入っているフォルダを開く
 */
@Composable
internal fun SearchResultsPane(
    browse: BrowseState,
    lastOpenedItemId: String?,
    listState: LazyListState,
    actions: OperationActions,
    browserActions: BrowserActions,
    shareActions: ItemShareActions?,
    onOpenFolderOf: (ItemRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val query = browse.search?.query.orEmpty()
    val results = remember(browse.tree, browse.folderReads, browse.pendingDeletes, query) { browse.searchItems(query) }
    if (searchTerms(query).isEmpty()) {
        Box(modifier.fillMaxSize())
        return
    }
    val loading = browse.searchLoading
    Column(modifier.fillMaxSize()) {
        // マニフェストを読んでいる最中は、AppBar の下に同じ線を出しているので、2 本並べない
        if (loading && !browse.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        browse.unreadableFoldersForSearch().size.takeIf { it > 0 }?.let { count ->
            Banner(UiMessage(unreadableFoldersNotice(count)))
        }
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                if (results.isEmpty() && !loading) {
                    item(NO_RESULTS_KEY) { StatusText(UiMessage(NO_SEARCH_RESULTS_NOTICE)) }
                }
                items(results, key = { resultKeyOf(it.ref) }) { result ->
                    SearchResultRow(
                        result = result,
                        lastOpened = result.item.id == lastOpenedItemId,
                        actions = actions,
                        browserActions = browserActions,
                        shareActions = shareActions,
                        onOpenFolder = { onOpenFolderOf(result.ref) },
                    )
                }
            }
            ListVerticalScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

/**
 * 検索の結果の行を押したときにすること。選ぶ状態は無いので、選ばない。
 *
 * タッチの画面（[touchScreen]）で、マウスでない押下（ポインターで押していないときを含む）の長押しなら、項目のメニューを出す。
 * そのほかは開く。マウスでは、修飾キーを押していても、長押しかどうかによらず開く。キーボードや画面の読み上げの操作での押下も開く。
 *
 * @param press 押したときの様子。ポインターで押していなければ null
 * @param longPress 長押しか
 * @param touchScreen 画面をタッチの画面の振る舞いで動かすか（[LocalTouchScreen]）
 */
internal fun searchResultClickOf(press: PointerPress?, longPress: Boolean, touchScreen: Boolean): ItemClick =
    if (touchScreen && longPress && press?.type != PointerType.Mouse) ItemClick.Menu else ItemClick.Open

/**
 * 検索の結果の、項目 [result] の行（[ItemRow]）。タイトルと URL の下に、項目が入っているフォルダの表記（[searchResultFolderLabel]）を出す。
 * 最後に開いた項目の行は、開いているフォルダのリストと同じ色で強調する。選ぶ状態は無く、ドラッグして移す元にもしない。
 *
 * 押すと（[searchResultClickOf]）、既定のブラウザで開く。右クリックでは、押した位置に、この項目のメニュー（[ItemContextMenu]。
 * ブラウザを選んで開く・タイトルを変更・移動・削除・入っているフォルダを開く）を出す。タッチの画面（[LocalTouchScreen]）では、
 * 長押しすると、押した位置に、この項目の長押しのメニュー（[ItemLongPressMenu]。「選択」の行は無く、入っているフォルダを開く行がある）を出す。
 * メニューの操作は、どれもこの項目について行う。
 *
 * @param lastOpened 最後に開いた項目か
 * @param shareActions 項目をほかのアプリで開く操作と共有する操作の入口。できないプラットフォームでは null で、長押しのメニューに
 * それらの行を出さない
 * @param onOpenFolder 項目が入っているフォルダを開く
 */
@Composable
private fun SearchResultRow(
    result: ItemSearchResult,
    lastOpened: Boolean,
    actions: OperationActions,
    browserActions: BrowserActions,
    shareActions: ItemShareActions?,
    onOpenFolder: () -> Unit,
) {
    val ref = result.ref
    val item = result.item
    val touchScreen = LocalTouchScreen.current
    val open = { browserActions.onOpenItem(ref) }
    ItemRow(
        item = item,
        selected = false,
        lastOpened = lastOpened,
        clickOf = { press, longPress -> searchResultClickOf(press, longPress, touchScreen) },
        onClick = { click -> if (click == ItemClick.Open) open() },
        onMenuPress = browserActions.onMenuOpen,
        contextMenu = { state ->
            ItemContextMenu(
                state = state,
                browsers = browserActions.browsers,
                itemCount = 1,
                canWrite = actions.canWrite,
                onOpenInBrowser = { browser -> browserActions.onOpenItemInBrowser(ref, browser) },
                onRename = { actions.onOpenDialog(OperationDialog.RenameItem(ref, item.title)) },
                onMove = { actions.openItemMoveDialog(ref) },
                onDelete = { actions.onDeleteItem(ref) },
                onOpenFolder = onOpenFolder,
            )
        },
        longPressMenu = { state ->
            ItemLongPressMenu(
                state = state,
                ref = ref,
                title = item.title,
                actions = actions,
                shareActions = shareActions,
                onOpen = open,
                onOpenFolder = onOpenFolder,
            )
        },
        caption = searchResultFolderLabel(result.folderPath),
    )
}
