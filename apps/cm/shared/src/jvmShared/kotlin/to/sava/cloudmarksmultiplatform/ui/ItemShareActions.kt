package to.sava.cloudmarksmultiplatform.ui

/**
 * リストから始める、項目をほかのアプリで開く操作と共有する操作の入口。データを変えないので、[OperationActions.canWrite] によらず受け付ける。
 * 項目をほかのアプリで開くことと共有することができるプラットフォーム（[to.sava.cloudmarksmultiplatform.app.AppState.sharesItems]）でだけ作る。
 *
 * @property onOpenInOtherApp 開いているフォルダの、渡した ID の項目を、ほかのアプリで開く。選んでいる項目は変えない
 * @property onShare 開いているフォルダの、渡した ID の項目を共有する。選んでいる項目は変えない
 */
internal class ItemShareActions(
    val onOpenInOtherApp: (itemId: String) -> Unit,
    val onShare: (itemId: String) -> Unit,
)
