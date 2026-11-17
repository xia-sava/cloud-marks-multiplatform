package to.sava.cloudmarksmultiplatform

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import to.sava.cloudmarksmultiplatform.app.createCloudMarksController
import to.sava.cloudmarksmultiplatform.settings.WindowsDpapiProtector
import to.sava.cloudmarksmultiplatform.ui.CloudMarksApp
import kotlin.time.TimeSource

/** 書き込むマニフェストのヘッダーの `updatedBy`。 */
private const val UPDATED_BY = "desktop"

fun main() {
    val scope = MainScope()
    val controller = createCloudMarksController(
        appFiles = desktopAppFiles(),
        protector = WindowsDpapiProtector,
        timeSource = TimeSource.Monotonic,
        updatedBy = UPDATED_BY,
        deletesStaleFiles = true,
        scope = scope,
    )
    controller.start()
    application {
        Window(
            title = "Cloud Marks",
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
