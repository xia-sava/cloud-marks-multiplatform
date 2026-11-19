package to.sava.cloudmarksmultiplatform.update

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 呼ばれた操作を数えるだけの、偽のアプリの更新。確認もダウンロードもせず、状態は [state] に入れた値のまま。
 * 画面の表示と、ボタンが操作を渡すことを確かめるのに使う。[apply] に渡された関数は呼ばずに [onReadyToExitPassed] に残す。
 */
internal class FakeAppUpdater(initial: UpdateState) : AppUpdater {
    override val state = MutableStateFlow(initial)

    var checks = 0
        private set
    var downloads = 0
        private set
    var applies = 0
        private set
    var cancels = 0
        private set
    var dismissals = 0
        private set
    var resumes = 0
        private set
    var closes = 0
        private set

    /** 最後に [apply] に渡された、アプリを終了する関数。 */
    var onReadyToExitPassed: (() -> Unit)? = null
        private set

    override fun check() {
        checks += 1
    }

    override fun download() {
        downloads += 1
    }

    override fun apply(onReadyToExit: () -> Unit) {
        applies += 1
        onReadyToExitPassed = onReadyToExit
    }

    override fun cancel() {
        cancels += 1
    }

    override fun dismissApplyFailure() {
        dismissals += 1
    }

    override fun onResumed() {
        resumes += 1
    }

    override fun close() {
        closes += 1
    }
}
