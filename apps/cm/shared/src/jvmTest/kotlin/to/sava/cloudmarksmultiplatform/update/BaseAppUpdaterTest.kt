package to.sava.cloudmarksmultiplatform.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** ダウンロードしたことにする配布物の中身。 */
private val RELEASE_CONTENT = "cloudmarks-setup".encodeToByteArray()

/** 1 回の確認で出す要求の数。更新情報とその署名。 */
private const val REQUESTS_PER_CHECK = 2

/** 予期しない例外と取り消しを確かめるテストの、実際の時間での上限。途中の状態のまま固まれば、ここで落ちる。 */
private val UNEXPECTED_FAILURE_TIMEOUT = 10.seconds

/**
 * 配布物を [release] に置き、確かめた配布物を受け取ったら返事を待つ（デスクトップと同じ）更新。
 * ダウンロードした URL・消した配布物・受け取った配布物を数える。
 */
private class RecordingUpdater(
    version: AppVersion,
    val httpClient: HttpClient,
    publicKey: String,
    private val release: Path,
    log: (String) -> Unit,
    applyFailedVersionName: String? = null,
) : BaseAppUpdater(version, UpdatePlatform.DESKTOP, httpClient, log, applyFailedVersionName, publicKey) {
    val downloadedUrls = CopyOnWriteArrayList<String>()
    val discarded = CopyOnWriteArrayList<Path>()
    val accepted = CopyOnWriteArrayList<Path>()

    /** あれば、それが終わるまでダウンロードを終えない。 */
    @Volatile
    var downloadGate: CompletableDeferred<Unit>? = null

    /** あれば、ダウンロードでそれを投げる。 */
    @Volatile
    var downloadFailure: Throwable? = null

    /** [scope] で走らせているものが、すべて終わるまで待つ。 */
    suspend fun awaitRunning() {
        scope.coroutineContext.job.children.toList().joinAll()
    }

    override suspend fun downloadRelease(url: String, onProgress: (received: Long, total: Long) -> Unit): Path {
        downloadedUrls += url
        downloadGate?.await()
        downloadFailure?.let { throw it }
        release.writeBytes(RELEASE_CONTENT)
        onProgress(RELEASE_CONTENT.size.toLong(), RELEASE_CONTENT.size.toLong())
        return release
    }

    override fun discard(release: Path) {
        discarded.add(release)
        Files.deleteIfExists(release)
    }

    override fun accept(release: Path, available: UpdateCheck.Available): UpdateInstall {
        accepted.add(release)
        return UpdateInstall.ReadyToApply
    }
}

/** 確認を始め、終わるまで待つ。 */
private suspend fun AppUpdater.checkAndWait(): UpdateState {
    check()
    return state.first { it.check != UpdateCheck.Checking }
}

/** ダウンロードを始め、返事を待つ段階か、インストーラへ渡したか、止まるまで待つ。 */
private suspend fun AppUpdater.downloadAndWait(): UpdateState {
    download()
    return state.first { it.install == UpdateInstall.ReadyToApply || it.install == UpdateInstall.Launching || it.install is UpdateInstall.Failed }
}

class BaseAppUpdaterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val key = TestSigningKey()
    private val logs = CopyOnWriteArrayList<String>()

    /** デスクトップの配布物の SHA-256 が [RELEASE_CONTENT] のものである更新情報。 */
    private val matchingUpdateInfo = updateInfoJson(desktopSha256 = sha256Of(RELEASE_CONTENT))

    private fun updater(
        version: AppVersion = DISTRIBUTED_VERSION,
        engine: MockEngine = updateInfoEngine(matchingUpdateInfo, key.sign(matchingUpdateInfo)),
        applyFailedVersionName: String? = null,
    ): RecordingUpdater =
        RecordingUpdater(
            version = version,
            httpClient = HttpClient(engine),
            publicKey = key.publicKey,
            release = temporaryFolder.root.toPath().resolve("cloudmarks-setup.exe"),
            log = { logs += it },
            applyFailedVersionName = applyFailedVersionName,
        )

    // 作ったときは、動いているバージョンと前の起動で適用できなかったバージョンを持ち、まだ確認していない
    @Test
    fun startsUnchecked() {
        val updater = updater(applyFailedVersionName = "1.5.0")

        assertEquals(UpdateState(DISTRIBUTED_VERSION, applyFailedVersionName = "1.5.0"), updater.state.value)
    }

    // 確認すると確認している最中になり、新しいバージョンがあれば、そのバージョンと SHA-256 を持つ
    @Test
    fun checkFindsAvailableRelease() = runTest {
        val updater = updater()

        updater.check()
        assertEquals(UpdateCheck.Checking, updater.state.value.check)
        val checked = updater.state.first { it.check != UpdateCheck.Checking }

        assertEquals(UpdateCheck.Available(RELEASED_VERSION_NAME, sha256Of(RELEASE_CONTENT)), checked.check)
    }

    // 配布しているバージョンが新しくなければ最新
    @Test
    fun checkFindsUpToDate() = runTest {
        val updater = updater(version = DISTRIBUTED_VERSION.copy(versionCode = RELEASED_VERSION_CODE))

        assertEquals(UpdateCheck.UpToDate, updater.checkAndWait().check)
    }

    // 確認できなければ、その理由を持つ
    @Test
    fun checkKeepsFailure() = runTest {
        val updater = updater(engine = updateInfoEngine(matchingUpdateInfo, signature = null))

        assertEquals(UpdateCheck.Failed(UpdateCheckFailure.SIGNATURE_UNVERIFIED), updater.checkAndWait().check)
    }

    // 確認している最中にもう一度確認しても、重ねて問い合わせない
    @Test
    fun checkIsNotRepeatedWhileChecking() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = updateInfoEngine(matchingUpdateInfo, key.sign(matchingUpdateInfo), gate)
        val updater = updater(engine = engine)

        updater.check()
        updater.check()
        gate.complete(Unit)
        updater.state.first { it.check != UpdateCheck.Checking }

        assertEquals(REQUESTS_PER_CHECK, engine.requestHistory.size)
    }

    // 確認が終わったあとは、もう一度確認できる
    @Test
    fun checkCanBeRepeatedAfterFinishing() = runTest {
        val engine = updateInfoEngine(matchingUpdateInfo, key.sign(matchingUpdateInfo))
        val updater = updater(engine = engine)

        updater.checkAndWait()
        updater.checkAndWait()

        assertEquals(REQUESTS_PER_CHECK * 2, engine.requestHistory.size)
    }

    // 新しいバージョンをダウンロードすると、決まった URL から取り、確かめに通った配布物を受け取って返事を待つ段階へ進む
    @Test
    fun downloadGoesThroughToAcceptedStage() = runTest {
        val updater = updater()
        updater.checkAndWait()

        updater.download()
        assertEquals(UpdateInstall.Downloading(0, 0), updater.state.value.install)
        val installed = updater.state.first { it.install == UpdateInstall.ReadyToApply || it.install is UpdateInstall.Failed }

        assertEquals(UpdateInstall.ReadyToApply, installed.install)
        assertEquals(listOf(releaseAssetUrl("cloudmarks-setup.exe")), updater.downloadedUrls)
        assertEquals(1, updater.accepted.size)
        assertTrue(updater.discarded.isEmpty())
    }

    // 配布物の SHA-256 が更新情報と合わなければ、消して、確かめに失敗したことで止まる
    @Test
    fun downloadStopsWhenDigestDiffers() = runTest {
        val updater = updater(engine = updateInfoEngine(UPDATE_INFO, key.sign(UPDATE_INFO)))
        updater.checkAndWait()

        val installed = updater.downloadAndWait()

        assertEquals(UpdateInstall.Failed(UpdateInstallFailure.VERIFICATION_FAILED), installed.install)
        assertEquals(1, updater.discarded.size)
        assertTrue(updater.accepted.isEmpty())
    }

    // 開発版は、確認して新しいバージョンがあると分かっても、ダウンロードを受け付けない
    @Test
    fun devBuildChecksButDoesNotDownload() = runTest {
        val updater = updater(version = DEV_VERSION)

        assertEquals(UpdateCheck.Available(RELEASED_VERSION_NAME, sha256Of(RELEASE_CONTENT)), updater.checkAndWait().check)
        updater.download()

        assertNull(updater.state.value.install)
        assertTrue(updater.downloadedUrls.isEmpty())
    }

    // 新しいバージョンがあると分かる前は、ダウンロードを受け付けない
    @Test
    fun downloadNeedsAvailableRelease() = runTest {
        val updater = updater(version = DISTRIBUTED_VERSION.copy(versionCode = RELEASED_VERSION_CODE))

        updater.download()
        updater.checkAndWait()
        updater.download()

        assertNull(updater.state.value.install)
        assertTrue(updater.downloadedUrls.isEmpty())
    }

    // 返事を待つあいだは、確認もダウンロードもし直さない（ダウンロードした配布物と確認の結果が食い違う）
    @Test
    fun checkAndDownloadAreRefusedWhileReadyToApply() = runTest {
        val engine = updateInfoEngine(matchingUpdateInfo, key.sign(matchingUpdateInfo))
        val updater = updater(engine = engine)
        updater.checkAndWait()
        updater.downloadAndWait()

        updater.check()
        updater.download()

        assertEquals(UpdateInstall.ReadyToApply, updater.state.value.install)
        assertEquals(REQUESTS_PER_CHECK, engine.requestHistory.size)
        assertEquals(1, updater.downloadedUrls.size)
    }

    // 返事を待つのをやめると、ダウンロードを始める前に戻り、もう一度ダウンロードできる
    @Test
    fun cancelReturnsFromReadyToApply() = runTest {
        val updater = updater()
        updater.checkAndWait()
        updater.downloadAndWait()

        updater.cancel()

        assertNull(updater.state.value.install)
        assertTrue(updater.state.value.canDownload)
    }

    // 止まった理由を閉じると、ダウンロードを始める前に戻る
    @Test
    fun cancelClosesFailure() = runTest {
        val updater = updater(engine = updateInfoEngine(UPDATE_INFO, key.sign(UPDATE_INFO)))
        updater.checkAndWait()
        updater.downloadAndWait()

        updater.cancel()

        assertNull(updater.state.value.install)
    }

    // ダウンロードしている最中は、やめる操作を受け付けない
    @Test
    fun cancelIsRefusedWhileDownloading() = runTest {
        val updater = updater()
        updater.checkAndWait()
        val gate = CompletableDeferred<Unit>().also { updater.downloadGate = it }

        updater.download()
        updater.cancel()
        assertEquals(UpdateInstall.Downloading(0, 0), updater.state.value.install)
        gate.complete(Unit)

        assertEquals(UpdateInstall.ReadyToApply, updater.state.first { it.install == UpdateInstall.ReadyToApply }.install)
    }

    // 止まったあとで確認し直すと、止まった理由を消してから確認し、またダウンロードできる
    @Test
    fun checkAfterFailureClearsIt() = runTest {
        val updater = updater(engine = updateInfoEngine(UPDATE_INFO, key.sign(UPDATE_INFO)))
        updater.checkAndWait()
        updater.downloadAndWait()

        updater.check()
        assertNull(updater.state.value.install)
        val checked = updater.state.first { it.check != UpdateCheck.Checking }

        assertTrue(checked.canDownload)
    }

    // 前の起動で適用できなかった知らせを閉じると、無くなる
    @Test
    fun dismissApplyFailureClearsVersion() {
        val updater = updater(applyFailedVersionName = "1.5.0")

        updater.dismissApplyFailure()

        assertNull(updater.state.value.applyFailedVersionName)
    }

    // 返事を待つ段階を通らない既定のままでは、インストールの操作は何もせず、アプリを終える関数も呼ばない
    @Test
    fun applyDoesNothingByDefault() = runTest {
        val updater = updater()
        updater.checkAndWait()
        updater.downloadAndWait()
        var exited = false

        updater.apply { exited = true }

        assertFalse(exited)
        assertEquals(UpdateInstall.ReadyToApply, updater.state.value.install)
    }

    // 閉じると、確認している最中のものを取り消し、HTTP のクライアントを閉じる
    @Test
    fun closeClosesHttpClient() = runTest {
        val updater = updater(engine = updateInfoEngine(matchingUpdateInfo, key.sign(matchingUpdateInfo), CompletableDeferred()))
        updater.check()

        updater.close()

        assertFalse(updater.httpClient.isActive)
    }

    // 確認が予期しない例外（Error を含む）で止まっても、確認している最中のまま固まらず、取得できなかったことにして、例外をログに書く
    @Test
    fun checkDoesNotStickOnUnexpectedError() = runTest(timeout = UNEXPECTED_FAILURE_TIMEOUT) {
        val updater = updater(engine = MockEngine { throw AssertionError("unexpected in check") })

        val checked = updater.checkAndWait()

        assertEquals(UpdateCheck.Failed(UpdateCheckFailure.UNREACHABLE), checked.check)
        assertTrue(logs.any { "予期しない" in it && "unexpected in check" in it }, logs.toString())
        assertTrue(checked.canCheck)
    }

    // ダウンロードが予期しない例外（Error を含む）で止まっても、途中のまま固まらず、ダウンロードできなかったことにして、例外をログに書く
    @Test
    fun downloadDoesNotStickOnUnexpectedError() = runTest(timeout = UNEXPECTED_FAILURE_TIMEOUT) {
        val updater = updater()
        updater.checkAndWait()
        updater.downloadFailure = AssertionError("unexpected in download")

        val installed = updater.downloadAndWait()

        assertEquals(UpdateInstall.Failed(UpdateInstallFailure.DOWNLOAD_FAILED), installed.install)
        assertTrue(logs.any { "予期しない" in it && "unexpected in download" in it }, logs.toString())
        assertTrue(installed.canCheck && installed.canDownload)
    }

    // 取り消し（閉じること）は失敗にせず、ログにも書かない
    @Test
    fun cancellationIsNotFailure() = runTest(timeout = UNEXPECTED_FAILURE_TIMEOUT) {
        val updater = updater(engine = updateInfoEngine(matchingUpdateInfo, key.sign(matchingUpdateInfo), CompletableDeferred()))
        updater.check()

        updater.close()
        updater.awaitRunning()

        assertEquals(UpdateCheck.Checking, updater.state.value.check)
        assertTrue(logs.isEmpty(), logs.toString())
    }

    // 途中の状態だけを失敗にする。確認している最中は取得できなかったこと、ダウンロードか確かめている最中はダウンロードできなかったことにし、
    // ほかの状態（返事を待つ段階・インストーラへ渡した段階・止まった段階・確認の結果）は替えない
    @Test
    fun failInProgressOnlyTouchesRunningStages() {
        val available = UpdateCheck.Available(RELEASED_VERSION_NAME, DESKTOP_SHA256)

        assertEquals(
            UpdateState(DISTRIBUTED_VERSION, check = UpdateCheck.Failed(UpdateCheckFailure.UNREACHABLE)),
            failInProgress(UpdateState(DISTRIBUTED_VERSION, check = UpdateCheck.Checking)),
        )
        listOf(UpdateInstall.Downloading(1, 2), UpdateInstall.Verifying).forEach { stage ->
            assertEquals(
                UpdateState(DISTRIBUTED_VERSION, check = available, install = UpdateInstall.Failed(UpdateInstallFailure.DOWNLOAD_FAILED)),
                failInProgress(UpdateState(DISTRIBUTED_VERSION, check = available, install = stage)),
                stage.toString(),
            )
        }
        listOf(
            UpdateState(DISTRIBUTED_VERSION),
            UpdateState(DISTRIBUTED_VERSION, check = UpdateCheck.UpToDate),
            UpdateState(DISTRIBUTED_VERSION, check = available, install = UpdateInstall.ReadyToApply),
            UpdateState(DISTRIBUTED_VERSION, check = available, install = UpdateInstall.Launching),
            UpdateState(DISTRIBUTED_VERSION, check = available, install = UpdateInstall.Failed(UpdateInstallFailure.HAND_OVER_FAILED)),
        ).forEach { state -> assertEquals(state, failInProgress(state), state.toString()) }
    }
}
