package to.sava.cloudmarksmultiplatform.ui

import to.sava.cloudmarksmultiplatform.browser.Browser
import to.sava.cloudmarksmultiplatform.operations.ItemRef

/**
 * リスト・ツリー・検索の結果から始める、項目をブラウザで開く操作の入口。データを変えないので、[OperationActions.canWrite] によらず受け付ける。
 *
 * @property browsers この端末に入っているブラウザ。メニューに並べる順は [browsersInMenuOrder] が決める
 * @property onOpenItem 項目を、既定のブラウザで開く。開いているフォルダでないフォルダの項目も開ける
 * @property onOpenItemInBrowser 項目を、渡したブラウザで開く。開いているフォルダでないフォルダの項目も開ける
 * @property onOpenSelectedItems 選んでいる項目を、渡したブラウザで開く
 * @property onOpenFolderItems フォルダの直下の項目を、渡したブラウザで開く
 * @property onMenuOpen 項目かフォルダのメニューを出したときに、この端末に入っているブラウザを読み直す
 */
internal class BrowserActions(
    val browsers: List<Browser>,
    val onOpenItem: (ItemRef) -> Unit,
    val onOpenItemInBrowser: (ItemRef, Browser) -> Unit,
    val onOpenSelectedItems: (Browser) -> Unit,
    val onOpenFolderItems: (folderId: String, Browser) -> Unit,
    val onMenuOpen: () -> Unit,
)
