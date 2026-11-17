package to.sava.cloudmarksmultiplatform.ui

import to.sava.cloudmarksmultiplatform.browser.Browser

/**
 * リストとツリーから始める、項目をブラウザで開く操作の入口。データを変えないので、[OperationActions.canWrite] によらず受け付ける。
 *
 * @property browsers PC に入っているブラウザ。メニューに並べる順は [browsersInMenuOrder] が決める
 * @property onOpenItem 開いているフォルダの項目を、既定のブラウザで開く
 * @property onOpenSelectedItems 選んでいる項目を、渡したブラウザで開く
 * @property onOpenFolderItems フォルダの直下の項目を、渡したブラウザで開く
 * @property onMenuOpen 項目かフォルダのメニューを出したときに、PC に入っているブラウザを読み直す
 */
internal class BrowserActions(
    val browsers: List<Browser>,
    val onOpenItem: (itemId: String) -> Unit,
    val onOpenSelectedItems: (Browser) -> Unit,
    val onOpenFolderItems: (folderId: String, Browser) -> Unit,
    val onMenuOpen: () -> Unit,
)
