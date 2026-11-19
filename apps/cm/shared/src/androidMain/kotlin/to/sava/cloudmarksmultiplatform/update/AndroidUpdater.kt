package to.sava.cloudmarksmultiplatform.update

import android.content.Context
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import to.sava.cloudmarksmultiplatform.AppFiles
import java.nio.file.Files
import java.nio.file.Path

/**
 * Android のアプリの更新を作る。アプリ（`Application`）がプロセスに 1 つ作り、プロセスが終わるまで閉じない。
 *
 * 配布物は [AppFiles.updateDirectory] に置き、確かめたら端末のインストーラへ渡す（[AndroidUpdateInstaller]）。
 * 画面が操作を受け付けられるようになるたびに（Activity の `onResume`）、[AppUpdater.onResumed] を呼ぶ。
 *
 * @param context APK のパッケージ名を読み、端末のインストーラを起動する。アプリの Context（`applicationContext`）を使う
 * @param version 動いているアプリのバージョン（[androidAppVersion]）
 * @param log 失敗の細かい理由（HTTP の状態、例外）を書く。アプリのログのファイル（[to.sava.cloudmarksmultiplatform.logging.FileLog]）
 */
fun androidAppUpdater(context: Context, version: AppVersion, appFiles: AppFiles, log: (String) -> Unit): AppUpdater =
    AndroidUpdater(
        version = version,
        httpClient = createUpdateHttpClient(),
        log = log,
        updateDirectory = appFiles.updateDirectory,
        installer = AndroidUpdateInstaller(context.applicationContext, log),
    )

/**
 * Android のアプリの更新。
 *
 * 配布物は [updateDirectory] の下の決まった名前（[UpdatePlatform.assetName]）のファイルに置き、ダウンロードするたびに書き換える。
 * 確かめたあとは、インストールしてよいかを自分では尋ねず、すぐに端末のインストーラへ渡す（[UpdateInstall.Launching]）。
 * インストールするかどうかは端末が利用者に尋ねるので、ここでも尋ねると同じことを 2 度聞くことになる。
 * 返事を待つ段階（[UpdateInstall.ReadyToApply]）を通らないので、[apply] は何もしない。
 *
 * @param httpClient 更新情報と配布物を取るクライアント。[close] で閉じる
 * @param updateDirectory 配布物を置くディレクトリ（[AppFiles.updateDirectory]）
 * @param installer 確かめた APK のパッケージ名を見て、端末のインストーラへ渡す
 */
internal class AndroidUpdater(
    version: AppVersion,
    private val httpClient: HttpClient,
    log: (String) -> Unit,
    private val updateDirectory: Path,
    private val installer: ApkInstaller,
    publicKey: String = UPDATE_INFO_PUBLIC_KEY,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BaseAppUpdater(version, UpdatePlatform.ANDROID, httpClient, log, publicKey = publicKey, dispatcher = dispatcher) {
    /** 配布物を、[updateDirectory] の下の決まった名前のファイルへダウンロードする。前にダウンロードしたものがあれば書き換える。 */
    override suspend fun downloadRelease(url: String, onProgress: (received: Long, total: Long) -> Unit): Path {
        val apk = updateDirectory.resolve(UpdatePlatform.ANDROID.assetName)
        httpClient.downloadToFile(url, apk, onProgress = onProgress)
        return apk
    }

    override fun discard(release: Path) {
        Files.deleteIfExists(release)
    }

    /**
     * SHA-256 を確かめた APK [release] のパッケージ名を見て（[ApkInstaller.isOwnPackage]）、すぐに端末のインストーラへ渡し、
     * [UpdateInstall.Launching] へ進める。
     *
     * - パッケージ名がこのアプリのものでないか、APK として読めなければ、渡さずに消して [UpdateInstallFailure.VERIFICATION_FAILED] で止まる
     * - 渡せなければ投げる（[UpdateInstallFailure.HAND_OVER_FAILED] で止まり、APK は消える）
     *
     * 渡した APK は端末のインストーラが読むので、消さない。次にダウンロードするときに書き換える。
     */
    override fun accept(release: Path, available: UpdateCheck.Available): UpdateInstall {
        if (!installer.isOwnPackage(release)) {
            discardLogging(release, ::discard, log)
            return UpdateInstall.Failed(UpdateInstallFailure.VERIFICATION_FAILED)
        }
        installer.install(release)
        return UpdateInstall.Launching
    }

    /**
     * 端末のインストーラへ渡したあとなら、ダウンロードを始める前に戻す。インストールすればこのアプリは終わるので、渡したまま画面が
     * 操作を受け付けられるようになるのは、インストールされなかったとき。利用者がやめたとき、端末の側でインストールが失敗して
     * 戻ったとき、画面が裏にあるあいだに渡して端末がインストーラの起動を止めたときのどれでも、もう一度ダウンロードから
     * やり直せるようにする。止まった理由（[UpdateInstall.Failed]）は消さない。
     */
    override fun onResumed() {
        if (state.value.install == UpdateInstall.Launching) moveTo(null)
    }
}
