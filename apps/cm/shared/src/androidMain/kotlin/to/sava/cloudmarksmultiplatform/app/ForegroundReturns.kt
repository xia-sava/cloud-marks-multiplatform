package to.sava.cloudmarksmultiplatform.app

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Android のアプリの画面が前面に戻ったことの知らせ。プロセスに 1 つだけ持ち、[returns] を、既定のネットワークにつながったことの知らせ
 * （[defaultNetworkAvailability]）と合わせて `createCloudMarksController` の `storageChanges` に渡して、前面に戻るたびに S3 から読み直させる。
 *
 * 画面（Activity）の `onStart` のたびに [onActivityStart] を呼ぶ。プロセスで初めての呼び出し（起動して画面を出したとき）は、
 * コントローラが起動したときに読むので知らせず、2 回目からを知らせる。
 * 集めている側がまだ受け取っていない知らせは、1 つにまとめる。集めている側が無いあいだの知らせは捨てる。
 * [onActivityStart] はメインのスレッドから呼ぶ。
 */
class ForegroundReturns {
    private val notifications = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** [onActivityStart] を呼んだことがあるか。 */
    private var started = false

    /** 画面が前面に戻ったことの知らせ。 */
    val returns: Flow<Unit> = notifications.asSharedFlow()

    /** 画面の `onStart` で呼ぶ。プロセスで初めての呼び出しでなければ、前面に戻ったことを知らせる。 */
    fun onActivityStart() {
        // 溢れたら古い知らせを捨てるので、tryEmit は断らない
        if (started) notifications.tryEmit(Unit) else started = true
    }
}
