package to.sava.cloudmarksmultiplatform.update

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import to.sava.cloudmarksmultiplatform.AppFiles
import to.sava.cloudmarksmultiplatform.AppPath
import to.sava.cloudmarksmultiplatform.files.describeIoFailure
import to.sava.cloudmarksmultiplatform.files.describeThrowable
import java.io.IOException
import java.nio.file.Path

/**
 * デスクトップのアプリの更新を作る。デスクトップのアプリのエントリポイントが、起動するたびに 1 つ作り、使い終えたら閉じる。
 *
 * 配布物は [AppFiles.updateDirectory] に置き、適用の結果のログは [AppFiles.logDirectory] に、適用を試みたことの記録は
 * [AppFiles.stateDirectory] に置く（[UpdateApplyLog]）。起動し直す実行ファイルは [AppPath.verified]。
 *
 * 作るときに、次のことをしてから返す。どちらも小さなファイルを扱うだけなので、呼んだスレッドで済ませる。
 * - 前の起動で試みた適用の結果を読み（[UpdateApplyLog.takeFailedVersionName]。動いているバージョンを渡す）、失敗していれば
 *   [UpdateState.applyFailedVersionName] に入れる
 * - 置いたままのダウンロードを消す（[discardStaleDownloads]）。適用したすぐあとの起動では、インストーラへ渡した配布物はまだ新しいので残り、
 *   その次の起動で消える
 *
 * @param version 動いているアプリのバージョン
 * @param log 失敗の細かい理由（HTTP の状態、例外）を書く。アプリのログのファイル（[to.sava.cloudmarksmultiplatform.logging.FileLog]）
 */
fun desktopAppUpdater(version: AppVersion, appFiles: AppFiles, log: (String) -> Unit): AppUpdater {
    val applyLog = UpdateApplyLog(appFiles.logDirectory, appFiles.stateDirectory, log)
    val installer = DesktopUpdateInstaller(AppPath.verified, applyLog.resultLogFile)
    val applyFailedVersionName = applyLog.takeFailedVersionName(version.versionName)
    discardStaleDownloads(appFiles.updateDirectory, log)
    return DesktopUpdater(
        version = version,
        httpClient = createUpdateHttpClient(),
        log = log,
        updateDirectory = appFiles.updateDirectory,
        applyLog = applyLog,
        handOver = installer::launch,
        applyFailedVersionName = applyFailedVersionName,
    )
}

/**
 * デスクトップのアプリの更新。
 *
 * 配布物は、ダウンロードするたびに [updateDirectory] の下に新しく作るディレクトリに置く（[createDownloadDirectory]）。
 * 確かめたあとはすぐにインストーラへ渡さず、インストールしてよいかの返事を待つ（[UpdateInstall.ReadyToApply]）。
 * インストールするとアプリをいったん終了するので、利用者が [apply] で返事をしてから渡す。
 * 更新の確認を受け付けたときに、置いたままのダウンロードを消す（[discardStaleDownloads]。起動したときは [desktopAppUpdater] が消す）。
 *
 * @param httpClient 更新情報と配布物を取るクライアント。[close] で閉じる
 * @param updateDirectory 配布物を置くディレクトリ（[AppFiles.updateDirectory]）
 * @param applyLog 適用の成否の置き場所。インストーラへ渡すときに、試みたバージョンを記録する
 * @param handOver 確かめた配布物を、アプリの終了を待って適用するものへ渡す（[DesktopUpdateInstaller.launch]）。
 * 渡せなければ投げる
 * @param applyFailedVersionName 前の起動で更新を適用できなかったときの、適用しようとしたバージョン（[UpdateApplyLog.takeFailedVersionName]）
 */
internal class DesktopUpdater(
    version: AppVersion,
    private val httpClient: HttpClient,
    log: (String) -> Unit,
    private val updateDirectory: Path,
    private val applyLog: UpdateApplyLog,
    private val handOver: (setup: Path) -> Unit,
    applyFailedVersionName: String? = null,
    publicKey: String = UPDATE_INFO_PUBLIC_KEY,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BaseAppUpdater(version, UpdatePlatform.DESKTOP, httpClient, log, applyFailedVersionName, publicKey, dispatcher) {
    /**
     * 確かめに通り、インストールしてよいかの返事を待っている配布物。返事を待つあいだだけ持つ。
     * ダウンロードのスレッドで入れ、メインのスレッドで読む。
     */
    @Volatile
    private var verified: VerifiedRelease? = null

    /**
     * 更新を確認する。確認を受け付けるときは、先に置いたままのダウンロードを消す。インストーラへ渡した配布物は、渡した側では消せないので、
     * 次にダウンロードする前のここで消す。置いておくのは多くて 1 つで済む。
     */
    override fun check() {
        if (state.value.canCheck) scope.launch { discardStaleDownloads(updateDirectory, log) }
        super.check()
    }

    /** 配布物を、[updateDirectory] の下に新しく作るディレクトリへダウンロードする。失敗したら（取り消されたときを含む）ディレクトリごと消す。 */
    override suspend fun downloadRelease(url: String, onProgress: (received: Long, total: Long) -> Unit): Path {
        val directory = createDownloadDirectory(updateDirectory)
        val setup = directory.resolve(UpdatePlatform.DESKTOP.assetName)
        try {
            httpClient.downloadToFile(url, setup, onProgress = onProgress)
        } catch (e: Exception) {
            try {
                discardDownload(setup)
            } catch (discardFailure: IOException) {
                e.addSuppressed(discardFailure)
            }
            throw e
        }
        return setup
    }

    override fun discard(release: Path) {
        discardDownload(release)
    }

    /** 確かめに通った配布物を持ち、インストールしてよいかの返事を待つ段階へ進める。 */
    override fun accept(release: Path, available: UpdateCheck.Available): UpdateInstall {
        verified = VerifiedRelease(release, available.sha256, available.versionName)
        return UpdateInstall.ReadyToApply
    }

    /**
     * 返事を待っている配布物を、アプリの終了を待って適用するものへ渡し（[handOver]）、[UpdateInstall.Launching] へ進めてから
     * [onReadyToExit] を呼ぶ。呼ばれた側は、そこでアプリを終了する。返事を待っていなければ何もしない。
     *
     * - 渡す直前に、SHA-256 をもう一度確かめる。返事を待つあいだに中身が替われば、確かめたバイト列と渡すバイト列が食い違う。
     *   合わないか、読めない（無くなっていた）ときは、配布物を消して [UpdateInstallFailure.VERIFICATION_FAILED] で止まる
     * - 渡す前に、適用するスクリプトが結果を書くログのディレクトリと、記録のディレクトリを作る（[UpdateApplyLog.prepareDirectories]）
     * - 渡したら、試みたバージョンを記録する（[UpdateApplyLog.record]）。次の起動で成否を伝えるのに使う
     * - 渡せなければ、ログに書き、配布物を消して [UpdateInstallFailure.HAND_OVER_FAILED] で止まる。[onReadyToExit] は呼ばない
     *
     * 渡した配布物は手放し、[close] でも消さない。適用するスクリプトは、アプリの終了を待ってからそれを使う。
     */
    override fun apply(onReadyToExit: () -> Unit) {
        if (state.value.install != UpdateInstall.ReadyToApply) return
        val release = verified ?: return
        if (!stillMatches(release)) {
            discardVerified()
            moveTo(UpdateInstall.Failed(UpdateInstallFailure.VERIFICATION_FAILED))
            return
        }
        try {
            applyLog.prepareDirectories()
            handOver(release.setup)
        } catch (e: Exception) {
            log("確かめた更新を、インストーラへ渡せなかった: ${describeThrowable(e)}")
            discardVerified()
            moveTo(UpdateInstall.Failed(UpdateInstallFailure.HAND_OVER_FAILED))
            return
        }
        verified = null
        applyLog.record(release.versionName)
        moveTo(UpdateInstall.Launching)
        onReadyToExit()
    }

    /** 返事を待っているなら、配布物を消してから、ダウンロードを始める前に戻す。 */
    override fun cancel() {
        if (state.value.install == UpdateInstall.ReadyToApply) discardVerified()
        super.cancel()
    }

    /** 返事を待っている配布物を消してから、確認とダウンロードを取り消し、HTTP のクライアントを閉じる。 */
    override fun close() {
        discardVerified()
        super.close()
    }

    /** [release] の SHA-256 が、確かめたときの値と今も同じか。読めなければ偽。どちらも理由をログに書く。 */
    private fun stillMatches(release: VerifiedRelease): Boolean =
        try {
            matchesSha256(release.setup, release.sha256)
                .also { if (!it) log("インストールする前に確かめ直した更新の SHA-256 が、更新情報と合わない") }
        } catch (e: IOException) {
            log("インストールする前に、更新を確かめ直すために読めなかった: ${describeIoFailure(e)}")
            false
        }

    /** 返事を待っている配布物を、ダウンロードのディレクトリごと消して手放す。消せなければ、ログに書いて続ける。 */
    private fun discardVerified() {
        val release = verified ?: return
        verified = null
        discardLogging(release.setup, ::discard, log)
    }

    /**
     * 確かめに通った配布物 [setup] と、そのときの SHA-256 [sha256]（渡す直前に確かめ直すのに使う）と、バージョン [versionName]
     * （適用を試みたことの記録に使う）。
     */
    private data class VerifiedRelease(val setup: Path, val sha256: String, val versionName: String)
}
