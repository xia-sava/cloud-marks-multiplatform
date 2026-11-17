package to.sava.cloudmarksmultiplatform

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import to.sava.cloudmarksmultiplatform.ui.CloudMarksApp

/**
 * プロセスに 1 つのコントローラ（[CloudMarksApplication.controller]）の画面を出す。画面を閉じても、コントローラは閉じない。
 * 画面はシステムバーの下まで広げて描き、画面の側で、システムバーに重ならない余白を付ける。
 * 画面が前面に出るたびに、[CloudMarksApplication.foregroundReturns] へ知らせる。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val controller = (application as CloudMarksApplication).controller
        setContent { CloudMarksApp(controller) }
    }

    override fun onStart() {
        super.onStart()
        (application as CloudMarksApplication).foregroundReturns.onActivityStart()
    }

    /** 構成の変化では Activity を作り直さないので、端末の明暗が替わったときに、システムバーのアイコンの明暗を合わせ直す。 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enableEdgeToEdge()
    }
}
