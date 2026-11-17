package to.sava.cloudmarksmultiplatform

import android.app.Application
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.merge
import to.sava.cloudmarksmultiplatform.app.CloudMarksController
import to.sava.cloudmarksmultiplatform.app.ForegroundReturns
import to.sava.cloudmarksmultiplatform.app.createCloudMarksController
import to.sava.cloudmarksmultiplatform.app.defaultNetworkAvailability
import to.sava.cloudmarksmultiplatform.browser.androidBrowserOpener
import to.sava.cloudmarksmultiplatform.browser.androidItemSharer
import to.sava.cloudmarksmultiplatform.settings.AndroidKeystoreProtector

/** 書き込むマニフェストのヘッダーの `updatedBy`。 */
private const val UPDATED_BY = "android"

/**
 * プロセスに 1 つだけコントローラを持つ。プロセスを始めたときにコントローラを組み立てて動かし始め、プロセスが終わるまで閉じない。
 * 画面（[MainActivity]）を閉じても、S3 への書き込みを途中で取り消さない。
 * 画面が前面に戻るたびと（[foregroundReturns]）、既定のネットワークにつながるたびに（[defaultNetworkAvailability]）、
 * コントローラに S3 から読み直させる。溜めた削除も、そのときに書かせる。
 */
class CloudMarksApplication : Application() {
    /** 画面が描く、プロセスに 1 つのコントローラ。 */
    lateinit var controller: CloudMarksController
        private set

    /** 画面が前面に戻ったことの知らせ。[MainActivity] の `onStart` から知らせ、コントローラが受けて S3 から読み直す。 */
    val foregroundReturns = ForegroundReturns()

    override fun onCreate() {
        super.onCreate()
        controller = createCloudMarksController(
            appFiles = androidAppFiles(this),
            protector = AndroidKeystoreProtector,
            timeSource = ElapsedRealtimeTimeSource,
            updatedBy = UPDATED_BY,
            storageChanges = { merge(foregroundReturns.returns, defaultNetworkAvailability(this)) },
            deletesStaleFiles = false,
            browserOpener = { log -> androidBrowserOpener(this, log) },
            itemSharer = { log -> androidItemSharer(this, log) },
            scope = MainScope(),
        )
        controller.start()
    }
}
