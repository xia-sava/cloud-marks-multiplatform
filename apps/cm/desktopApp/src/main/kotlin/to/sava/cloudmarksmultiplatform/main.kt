package to.sava.cloudmarksmultiplatform

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(title = "Cloud Marks", onCloseRequest = ::exitApplication) {
        App()
    }
}
