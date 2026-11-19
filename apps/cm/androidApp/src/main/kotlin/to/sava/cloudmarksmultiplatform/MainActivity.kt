package to.sava.cloudmarksmultiplatform

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import to.sava.cloudmarksmultiplatform.ui.CloudMarksApp

/**
 * プロセスに 1 つのコントローラとアプリの更新（[CloudMarksApplication.controller]・[CloudMarksApplication.updater]）の画面を出す。
 * 画面を閉じても、どちらも閉じない。画面はシステムバーの下まで広げて描き、画面の側で、システムバーに重ならない余白を付ける。
 * 画面が前面に出るたびに [CloudMarksApplication.foregroundReturns] へ、操作を受け付けられるようになるたびにアプリの更新へ知らせる。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val cloudMarksApplication = application as CloudMarksApplication
        setContent { CloudMarksApp(cloudMarksApplication.controller, cloudMarksApplication.updater) }
    }

    override fun onStart() {
        super.onStart()
        (application as CloudMarksApplication).foregroundReturns.onActivityStart()
    }

    /**
     * 画面が操作を受け付けられるようになったことを、アプリの更新へ知らせる（[to.sava.cloudmarksmultiplatform.update.AppUpdater.onResumed]）。
     * 端末のインストーラの確認から戻ったことを知るのに使う。確認は画面を隠さず操作だけを取ることがあり、そのときは戻っても `onStart` を
     * 通らないので、前面に戻ったことの知らせ（[CloudMarksApplication.foregroundReturns]）ではなく、`onResume` で知らせる。
     */
    override fun onResume() {
        super.onResume()
        (application as CloudMarksApplication).updater.onResumed()
    }

    /** 構成の変化では Activity を作り直さないので、端末の明暗が替わったときに、システムバーのアイコンの明暗を合わせ直す。 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enableEdgeToEdge()
    }
}
