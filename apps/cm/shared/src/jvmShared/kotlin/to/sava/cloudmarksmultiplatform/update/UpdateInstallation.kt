package to.sava.cloudmarksmultiplatform.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import to.sava.cloudmarksmultiplatform.files.describeThrowable
import java.io.IOException
import java.nio.file.Path

/** 配布物をダウンロードしてファイルに置く関数。置いたファイルを返す。 */
internal typealias ReleaseDownloader = suspend (url: String, onProgress: (received: Long, total: Long) -> Unit) -> Path

/**
 * 新しいバージョンの配布物をダウンロードし、SHA-256 を確かめ、通れば [accept] へ渡す。
 *
 * ダウンロードと確かめるところまではプラットフォームで同じで、確かめたあとに何をするか（[accept]）がプラットフォームで違う。
 * インストールしてよいかの返事を待つプラットフォームは [UpdateInstall.ReadyToApply] を、そのままインストーラへ渡すプラットフォームは
 * [UpdateInstall.Launching] を返す。
 *
 * @param download 配布物をダウンロードして置き、置いたファイルを返す。https でない URL を断ること、失敗したとき（取り消されたときを含む）に
 * 書きかけを残さないことは、この関数が受け持つ（[downloadToFile] がそうする）
 * @param discard 使わない配布物を消す。投げたら、ログに書いて続ける
 * @param accept 確かめに通った配布物を受け取り、次の段階を返す
 * @param log 失敗の細かい理由（HTTP の状態、例外）を書く
 */
internal class UpdateInstallation(
    private val download: ReleaseDownloader,
    private val discard: (release: Path) -> Unit,
    private val accept: (release: Path, available: UpdateCheck.Available) -> UpdateInstall,
    private val log: (String) -> Unit,
) {
    /**
     * [url] から [available] の配布物をダウンロードして確かめ、通れば [accept] へ渡して、最後の段階を返す。
     * 途中の段階（[UpdateInstall.Downloading] と [UpdateInstall.Verifying]）は [onStage] へ知らせる。
     *
     * - ダウンロードに失敗したら [UpdateInstallFailure.DOWNLOAD_FAILED]
     * - SHA-256 が合わないか、確かめるために読めなければ、配布物を消して [UpdateInstallFailure.VERIFICATION_FAILED]
     * - [accept] が投げたら、配布物を消して [UpdateInstallFailure.HAND_OVER_FAILED]
     *
     * 取り消されたら投げ直し、失敗の段階は返さない。ダウンロードを終えたあとで取り消されたら、配布物を消してから投げ直す。
     * SHA-256 を確かめるところはブロックする。呼び出し側が I/O 用のスレッド（`Dispatchers.IO`）で呼ぶ。
     */
    suspend fun install(
        url: String,
        available: UpdateCheck.Available,
        onStage: (UpdateInstall) -> Unit,
    ): UpdateInstall {
        val release = try {
            download(url) { received, total -> onStage(UpdateInstall.Downloading(received, total)) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            log("更新をダウンロードできなかった: ${describeThrowable(e)}")
            return UpdateInstall.Failed(UpdateInstallFailure.DOWNLOAD_FAILED)
        }
        return try {
            verifyAndAccept(release, available, onStage)
        } catch (cancellation: CancellationException) {
            discardLogging(release, discard, log)
            throw cancellation
        }
    }

    private suspend fun verifyAndAccept(
        release: Path,
        available: UpdateCheck.Available,
        onStage: (UpdateInstall) -> Unit,
    ): UpdateInstall {
        onStage(UpdateInstall.Verifying)
        val matches = try {
            matchesSha256(release, available.sha256).also { if (!it) log("ダウンロードした更新の SHA-256 が、更新情報と合わない") }
        } catch (e: IOException) {
            log("ダウンロードした更新を、確かめるために読めなかった: ${describeThrowable(e)}")
            false
        }
        if (!matches) {
            discardLogging(release, discard, log)
            return UpdateInstall.Failed(UpdateInstallFailure.VERIFICATION_FAILED)
        }
        currentCoroutineContext().ensureActive()
        return try {
            accept(release, available)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            log("確かめた更新を、インストーラへ渡せなかった: ${describeThrowable(e)}")
            discardLogging(release, discard, log)
            UpdateInstall.Failed(UpdateInstallFailure.HAND_OVER_FAILED)
        }
    }
}

/** 使わない配布物 [release] を [discard] で消す。投げたら、消せなかったことを [log] に書いて続ける。 */
internal fun discardLogging(release: Path, discard: (release: Path) -> Unit, log: (String) -> Unit) {
    try {
        discard(release)
    } catch (e: Exception) {
        log("使わない更新のファイルを消せなかった: ${describeThrowable(e)}")
    }
}
