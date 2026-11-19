package to.sava.cloudmarksmultiplatform.update

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.sava.cloudmarksmultiplatform.files.describeThrowable
import java.nio.file.Path

/**
 * アプリの更新の、プラットフォームで同じ部分。確認（[UpdateChecker]）と、配布物をダウンロードして SHA-256 を確かめるところ
 * （[UpdateInstallation]）までを持つ。
 *
 * プラットフォームで違うところは、次を差し替える。
 * - [downloadRelease]：配布物をどこに置くか
 * - [discard]：使わない配布物の消し方
 * - [accept]：確かめに通った配布物を受け取ったあとの段階。デスクトップは返事を待ち（[UpdateInstall.ReadyToApply]）、
 *   Android は端末のインストーラへ渡す（[UpdateInstall.Launching]）
 * - [apply]・[cancel]・[onResumed]・[dismissApplyFailure]：既定では、[apply] と [onResumed] は何もせず、[cancel] はダウンロードを
 *   始める前に戻し、[dismissApplyFailure] は知らせを消す
 *
 * [downloadRelease]・[discard]・[accept] は [download] のあとにだけ呼ばれ、作っている最中には呼ばれない。
 * そのため、サブクラスのプロパティを使ってよい。
 *
 * 配布物の URL は決まった形で組む（[releaseAssetUrl] と [UpdatePlatform.assetName]）。
 *
 * [scope] で走らせたものが予期しない例外（`Error` を含む）で止まったら、例外をログに書き、途中の状態を失敗にする（確認している最中は
 * [UpdateCheckFailure.UNREACHABLE]、ダウンロードしているか確かめている最中は [UpdateInstallFailure.DOWNLOAD_FAILED]）。
 * 途中の状態のまま固まって、操作を受け付けなくなることを防ぐ。取り消しは失敗にしない。
 *
 * @param version 動いているアプリのバージョン。開発版（[AppVersion.distributed] が偽）は、確認はするがダウンロードを受け付けない
 * @param platform 動いているプラットフォーム。更新情報のこの項目と、この配布物を使う
 * @param httpClient 更新情報と配布物を取るクライアント（[createUpdateHttpClient]）。[close] で閉じる
 * @param log 失敗の細かい理由（HTTP の状態、例外）を書く
 * @param applyFailedVersionName 前の起動で更新を適用できなかったときの、適用しようとしたバージョン。無ければ null
 * @param publicKey 更新情報の署名を確かめる公開鍵。配布するアプリは [UPDATE_INFO_PUBLIC_KEY] を使う
 * @param dispatcher 確認とダウンロード（[downloadRelease]・[discard]・[accept] を含む）を走らせるディスパッチャ
 */
internal abstract class BaseAppUpdater(
    version: AppVersion,
    private val platform: UpdatePlatform,
    private val httpClient: HttpClient,
    protected val log: (String) -> Unit,
    applyFailedVersionName: String? = null,
    publicKey: String = UPDATE_INFO_PUBLIC_KEY,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AppUpdater {
    private val mutableState = MutableStateFlow(UpdateState(version, applyFailedVersionName = applyFailedVersionName))

    final override val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    /** [scope] で走らせたものが予期しない例外で止まったときに、例外をログに書き、途中の状態を失敗にする。取り消しでは呼ばれない。 */
    private val unexpectedFailure = CoroutineExceptionHandler { _, e ->
        log("アプリの更新が、予期しない例外で止まった: ${describeThrowable(e)}")
        mutableState.update(::failInProgress)
    }

    /** 確認とダウンロードを走らせるスコープ。[close] で取り消す。 */
    protected val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher + unexpectedFailure)

    private val checker = UpdateChecker(httpClient, platform, version.versionCode, log, publicKey)

    private val installation = UpdateInstallation(::downloadRelease, ::discard, ::accept, log)

    /**
     * 配布物を [url] からダウンロードして置き、置いたファイルを返す。受け取った量を [onProgress] へ知らせる。
     * https でない URL を断ること、失敗したとき（取り消されたときを含む）に書きかけを残さないことを受け持つ（[downloadToFile] がそうする）。
     */
    protected abstract suspend fun downloadRelease(url: String, onProgress: (received: Long, total: Long) -> Unit): Path

    /**
     * 使わない配布物 [release]（確かめに通らなかったもの、インストーラへ渡せなかったもの、確かめている最中に取り消されたもの）を消す。
     * 投げたら、ログに書いて続ける。
     */
    protected abstract fun discard(release: Path)

    /** 確かめに通った配布物 [release] を受け取り、次の段階を返す。投げたら、[UpdateInstallFailure.HAND_OVER_FAILED] になる。 */
    protected abstract fun accept(release: Path, available: UpdateCheck.Available): UpdateInstall

    /** ダウンロードからインストーラへ渡すまでの段階を [next] にする。始める前に戻すときは null。 */
    protected fun moveTo(next: UpdateInstall?) {
        mutableState.update { it.copy(install = next) }
    }

    /** 更新を確認する。止まった理由（[UpdateInstall.Failed]）が出ていれば、確認を始めるときに消す。 */
    override fun check() {
        transition { current -> current.takeIf { it.canCheck }?.copy(check = UpdateCheck.Checking, install = null) } ?: return
        scope.launch {
            val result = checker.check()
            mutableState.update { it.copy(check = result) }
        }
    }

    override fun download() {
        val started = transition { current ->
            current.takeIf { it.canDownload }?.copy(install = UpdateInstall.Downloading(receivedBytes = 0, totalBytes = 0))
        } ?: return
        val available = started.check as? UpdateCheck.Available ?: return
        scope.launch {
            moveTo(installation.install(releaseAssetUrl(platform.assetName), available, ::moveTo))
        }
    }

    override fun apply(onReadyToExit: () -> Unit) = Unit

    override fun cancel() {
        transition { current ->
            current.takeIf { it.install == UpdateInstall.ReadyToApply || it.install is UpdateInstall.Failed }?.copy(install = null)
        }
    }

    override fun dismissApplyFailure() {
        mutableState.update { it.copy(applyFailedVersionName = null) }
    }

    override fun onResumed() = Unit

    /** 確認とダウンロードを取り消し、[httpClient] を閉じる。 */
    override fun close() {
        scope.cancel()
        httpClient.close()
    }

    /**
     * 状態を、いまの状態から [next] が返すものへ替え、替えたあとの状態を返す。[next] が null を返したら替えずに null を返す。
     * ほかのスレッドが先に状態を替えていたら、替えたあとの状態でやり直す。
     */
    private inline fun transition(next: (UpdateState) -> UpdateState?): UpdateState? {
        while (true) {
            val current = mutableState.value
            val replaced = next(current) ?: return null
            if (mutableState.compareAndSet(current, replaced)) return replaced
        }
    }
}

/**
 * [state] の途中の状態を失敗にする。確認している最中は [UpdateCheckFailure.UNREACHABLE]、ダウンロードしているか確かめている最中は
 * [UpdateInstallFailure.DOWNLOAD_FAILED] にする。返事を待っている段階とインストーラへ渡した段階は、走らせているものが無いので替えない。
 */
internal fun failInProgress(state: UpdateState): UpdateState =
    state.copy(
        check = if (state.check == UpdateCheck.Checking) UpdateCheck.Failed(UpdateCheckFailure.UNREACHABLE) else state.check,
        install = when (state.install) {
            is UpdateInstall.Downloading, UpdateInstall.Verifying -> UpdateInstall.Failed(UpdateInstallFailure.DOWNLOAD_FAILED)
            else -> state.install
        },
    )
