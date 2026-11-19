package to.sava.cloudmarksmultiplatform

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import to.sava.cloudmarksmultiplatform.app.createCloudMarksController
import to.sava.cloudmarksmultiplatform.app.watchStorageChanges
import to.sava.cloudmarksmultiplatform.browser.desktopBrowserOpener
import to.sava.cloudmarksmultiplatform.logging.FileLog
import to.sava.cloudmarksmultiplatform.settings.WindowsDpapiProtector
import to.sava.cloudmarksmultiplatform.ui.CloudMarksApp
import to.sava.cloudmarksmultiplatform.update.desktopAppUpdater
import kotlin.time.TimeSource

/** 書き込むマニフェストのヘッダーの `updatedBy`。 */
private const val UPDATED_BY = "desktop"

fun main() {
    // 配布版か開発版かで、データの場所、拡張機能に渡す呼び出しの URL、ウィンドウのタイトルを分ける
    val version = desktopAppVersion()
    val distributed = version.distributed
    val scope = MainScope()
    val appFiles = desktopAppFiles(distributed)
    val controller = createCloudMarksController(
        appFiles = appFiles,
        protector = WindowsDpapiProtector,
        timeSource = TimeSource.Monotonic,
        updatedBy = UPDATED_BY,
        storageChanges = { log -> watchStorageChanges(appFiles, log) },
        deletesStaleFiles = true,
        browserOpener = { log -> desktopBrowserOpener(appFiles, distributed, log) },
        itemSharer = null,
        scope = scope,
    )
    val updater = desktopAppUpdater(version, appFiles, FileLog(appFiles.logDirectory)::write)
    controller.start()
    application {
        // 窓を閉じるときと、更新をインストーラへ渡したときの後始末。更新を適用するスクリプトは、アプリの終了を待ってから動き出す
        val exit = {
            controller.close()
            updater.close()
            scope.cancel()
            exitApplication()
        }
        Window(
            title = desktopWindowTitle(version),
            icon = painterResource("icon.png"),
            onCloseRequest = exit,
        ) {
            CloudMarksApp(controller, updater, onReadyToExit = exit)
        }
    }
}
