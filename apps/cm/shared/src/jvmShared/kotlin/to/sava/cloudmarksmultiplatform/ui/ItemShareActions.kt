package to.sava.cloudmarksmultiplatform.ui

import to.sava.cloudmarksmultiplatform.operations.ItemRef

/**
 * リストと検索の結果から始める、項目をほかのアプリで開く操作と共有する操作の入口。データを変えないので、[OperationActions.canWrite] によらず
 * 受け付ける。項目をほかのアプリで開くことと共有することができるプラットフォーム（[to.sava.cloudmarksmultiplatform.app.AppState.sharesItems]）
 * でだけ作る。
 *
 * @property onOpenInOtherApp 渡した項目を、ほかのアプリで開く。開いているフォルダでないフォルダの項目も開ける。選んでいる項目は変えない
 * @property onShare 渡した項目を共有する。開いているフォルダでないフォルダの項目も共有できる。選んでいる項目は変えない
 */
internal class ItemShareActions(
    val onOpenInOtherApp: (ItemRef) -> Unit,
    val onShare: (ItemRef) -> Unit,
)
