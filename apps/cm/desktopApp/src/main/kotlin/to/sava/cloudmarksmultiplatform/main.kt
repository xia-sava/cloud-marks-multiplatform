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
import to.sava.cloudmarksmultiplatform.window.InstanceLockResult
import to.sava.cloudmarksmultiplatform.window.WindowGeometryFile
import to.sava.cloudmarksmultiplatform.window.acquireInstanceLock
import to.sava.cloudmarksmultiplatform.window.deiconifiedExtendedState
import to.sava.cloudmarksmultiplatform.window.rememberGeometryWindowState
import to.sava.cloudmarksmultiplatform.window.requestShowWindow
import to.sava.cloudmarksmultiplatform.window.saveWindowGeometryChanges
import to.sava.cloudmarksmultiplatform.window.watchShowWindowRequests
import to.sava.cloudmarksmultiplatform.window.windowGeometryOf
import java.awt.Frame
import kotlin.time.TimeSource

/** 書き込むマニフェストのヘッダーの `updatedBy`。 */
private const val UPDATED_BY = "desktop"

/** ウィンドウの位置と大きさの記録が無いときの、ウィンドウの大きさ。 */
private val DEFAULT_WINDOW_SIZE = DpSize(800.dp, 600.dp)

/** 多重起動を防ぐロック。GC で閉じられてロックが外れないよう、終了の後始末で閉じるまで参照を保つ。 */
private var heldInstanceLock: InstanceLockResult.Acquired? = null

fun main() {
    // 配布版か開発版かで、データの場所、拡張機能に渡す呼び出しの URL、ウィンドウのタイトルを分ける
    val version = desktopAppVersion()
    val distributed = version.distributed
    val appFiles = desktopAppFiles(distributed)
    // このアプリのデータを置くディレクトリを、既に起動しているアプリが使っていれば、そのウィンドウを前に出させて、ほかに何も触れずに終わる。
    // ロックを取れなかったときは、多重起動を防がずに起動する
    val instanceLock = acquireInstanceLock(appFiles.instanceLockFile)
    if (instanceLock is InstanceLockResult.AlreadyRunning) {
        if (!distributed) println("Cloud Marks は既に起動している。起動しているほうのウィンドウを前に出させて終わる")
        requestShowWindow(appFiles.showWindowFile, System.err::println)
        return
    }
    heldInstanceLock = instanceLock as? InstanceLockResult.Acquired
    val scope = MainScope()
    val fileLog = FileLog(appFiles.logDirectory)
    if (instanceLock is InstanceLockResult.Failed) {
        fileLog.write("多重起動を防ぐロックを取れなかったので、防がずに起動する: ${instanceLock.reason}")
    }
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
        // ウィンドウの位置と大きさは、動かしたり大きさを変えたりしてから記録に書くまでに待ち時間があるので、終える前にも書く。
        // 多重起動を防ぐロックは、あとから起動したアプリの知らせに応えられるあいだだけ持ち、ここで外す。外さないと、プロセスが終わるまでの
        // あいだに起動したアプリが、既に起動しているものとして何も出さずに終わる
        val exit = {
            windowGeometryOf(windowState)?.let(windowGeometryFile::write)
            controller.close()
            updater.close()
            heldInstanceLock?.close(fileLog::write)
            scope.cancel()
            exitApplication()
        }
        Window(
            title = desktopWindowTitle(version),
            icon = painterResource("icon.png"),
            state = windowState,
            onCloseRequest = exit,
        ) {
            // あとから起動したアプリから知らせを受けたら、ウィンドウを前に出す
            LaunchedEffect(Unit) {
                watchShowWindowRequests(appFiles.showWindowFile, fileLog::write).collect { bringWindowToFront(window) }
            }
            CloudMarksApp(controller, updater, onReadyToExit = exit)
        }
    }
}

/**
 * [window] を前に出してフォーカスを移す。最小化していれば、最小化だけを解く（最大化していたものは最大化したまま戻る）。
 * AWT のイベントを扱うスレッドで呼ぶ。
 */
private fun bringWindowToFront(window: Frame) {
    deiconifiedExtendedState(window.extendedState)
        .takeIf { it != window.extendedState }
        ?.let { window.extendedState = it }
    window.toFront()
    window.requestFocus()
}
