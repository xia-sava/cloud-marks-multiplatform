package to.sava.cloudmarksmultiplatform

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import to.sava.cloudmarksmultiplatform.app.createCloudMarksController
import to.sava.cloudmarksmultiplatform.app.watchStorageChanges
import to.sava.cloudmarksmultiplatform.browser.desktopBrowserOpener
import to.sava.cloudmarksmultiplatform.settings.WindowsDpapiProtector
import to.sava.cloudmarksmultiplatform.ui.CloudMarksApp
import kotlin.time.TimeSource

/** 書き込むマニフェストのヘッダーの `updatedBy`。 */
private const val UPDATED_BY = "desktop"

fun main() {
    val scope = MainScope()
    val appFiles = desktopAppFiles()
    val controller = createCloudMarksController(
        appFiles = appFiles,
        protector = WindowsDpapiProtector,
        timeSource = TimeSource.Monotonic,
        updatedBy = UPDATED_BY,
        storageChanges = { log -> watchStorageChanges(appFiles, log) },
        deletesStaleFiles = true,
        browserOpener = { log -> desktopBrowserOpener(appFiles, log) },
        scope = scope,
    )
    controller.start()
    application {
        Window(
            title = "Cloud Marks",
            icon = painterResource("icon.png"),
            onCloseRequest = {
                controller.close()
                scope.cancel()
                exitApplication()
            },
        ) {
            CloudMarksApp(controller)
        }
    }
}
