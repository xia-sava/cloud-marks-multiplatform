package to.sava.cloudmarksmultiplatform

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
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
import to.sava.cloudmarksmultiplatform.window.WindowGeometryFile
import to.sava.cloudmarksmultiplatform.window.rememberGeometryWindowState
import to.sava.cloudmarksmultiplatform.window.saveWindowGeometryChanges
import to.sava.cloudmarksmultiplatform.window.windowGeometryOf
import kotlin.time.TimeSource

/** 書き込むマニフェストのヘッダーの `updatedBy`。 */
private const val UPDATED_BY = "desktop"

/** ウィンドウの位置と大きさの記録が無いときの、ウィンドウの大きさ。 */
private val DEFAULT_WINDOW_SIZE = DpSize(800.dp, 600.dp)

fun main() {
    // 配布版か開発版かで、データの場所、拡張機能に渡す呼び出しの URL、ウィンドウのタイトルを分ける
    val version = desktopAppVersion()
    val distributed = version.distributed
    val scope = MainScope()
    val appFiles = desktopAppFiles(distributed)
    val fileLog = FileLog(appFiles.logDirectory)
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
    val updater = desktopAppUpdater(version, appFiles, fileLog::write)
    // 前の起動で記録したウィンドウの位置と大きさで開く
    val windowGeometryFile = WindowGeometryFile(appFiles.windowGeometryFile, fileLog::write)
    val restoredGeometry = windowGeometryFile.read()
    controller.start()
    application {
        val windowState = rememberGeometryWindowState(restoredGeometry, DEFAULT_WINDOW_SIZE)
        LaunchedEffect(Unit) { saveWindowGeometryChanges(windowState, windowGeometryFile) }
        // ウィンドウを閉じるときと、更新をインストーラへ渡したときの後始末。更新を適用するスクリプトは、アプリの終了を待ってから動き出す。
        // ウィンドウの位置と大きさは、動かしたり大きさを変えたりしてから記録に書くまでに待ち時間があるので、終える前にも書く
        val exit = {
            windowGeometryOf(windowState)?.let(windowGeometryFile::write)
            controller.close()
            updater.close()
            scope.cancel()
            exitApplication()
        }
        Window(
            title = desktopWindowTitle(version),
            icon = painterResource("icon.png"),
            state = windowState,
            onCloseRequest = exit,
        ) {
            CloudMarksApp(controller, updater, onReadyToExit = exit)
        }
    }
}
