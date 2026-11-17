package to.sava.cloudmarksmultiplatform.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * Android の端末が、既定のネットワークにつながったことの知らせ。`ConnectivityManager.registerDefaultNetworkCallback` の
 * `onAvailable` のたびに知らせる。前面に戻ったことの知らせ（[ForegroundReturns]）と合わせて `createCloudMarksController` の
 * `storageChanges` に渡し、つながるたびに S3 から読み直させる（溜めた削除もそのとき書く）。
 *
 * 集め始めるとコールバックを登録し、集めるのをやめると登録を外す。登録したときに既定のネットワークがあれば、
 * そのネットワークについてもすぐに知らせる。集めている側が前の知らせを扱っているあいだに来た知らせは、1 つにまとめる。
 * `AndroidManifest.xml` に `ACCESS_NETWORK_STATE` の権限が要る。
 */
fun defaultNetworkAvailability(context: Context): Flow<Unit> {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    return callbackNotifications { notify ->
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                notify()
            }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        return@callbackNotifications { connectivity.unregisterNetworkCallback(callback) }
    }
}

/**
 * [register] で登録したコールバックが呼ばれるたびに知らせる Flow。集め始めるたびに [register] を呼び、集めるのをやめると、
 * [register] が返した関数で登録を外す。集めている側が前の知らせを扱っているあいだに来た知らせは、1 つにまとめる。
 *
 * @param register 知らせる関数を受け取ってコールバックを登録し、登録を外す関数を返す。知らせる関数は、どのスレッドから呼んでもよい
 */
internal fun callbackNotifications(register: (notify: () -> Unit) -> () -> Unit): Flow<Unit> =
    callbackFlow {
        // 知らせを 1 つにまとめるので、trySend が断るのは、集めるのをやめて閉じたあとだけ
        val unregister = register { trySend(Unit) }
        awaitClose(unregister)
    }.conflate()
