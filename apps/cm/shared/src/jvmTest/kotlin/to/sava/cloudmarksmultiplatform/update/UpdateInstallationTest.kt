package to.sava.cloudmarksmultiplatform.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ダウンロードしたことにする配布物の中身。 */
private val RELEASE_CONTENT = "cloudmarks-release".encodeToByteArray()

/** 配布物の URL。 */
private val RELEASE_URL = releaseAssetUrl(UpdatePlatform.DESKTOP.assetName)

class UpdateInstallationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val release: Path by lazy { temporaryFolder.root.toPath().resolve("cloudmarks-setup.exe") }
    private val stages = mutableListOf<UpdateInstall>()
    private val discarded = mutableListOf<Path>()
    private val accepted = mutableListOf<Path>()
    private val requestedUrls = mutableListOf<String>()
    private val logs = mutableListOf<String>()

    private fun available(sha256: String = sha256Of(RELEASE_CONTENT)) = UpdateCheck.Available(RELEASED_VERSION_NAME, sha256)

    /** [RELEASE_CONTENT] を [release] に書き、受け取った量を 1 回知らせて返すダウンロード。 */
    private val writingDownload: ReleaseDownloader = { url, onProgress ->
        requestedUrls += url
        release.writeBytes(RELEASE_CONTENT)
        onProgress(RELEASE_CONTENT.size.toLong(), RELEASE_CONTENT.size.toLong())
        release
    }

    private fun installation(
        download: ReleaseDownloader = writingDownload,
        accept: (Path, UpdateCheck.Available) -> UpdateInstall = { file, _ ->
            accepted.add(file)
            UpdateInstall.ReadyToApply
        },
    ): UpdateInstallation =
        UpdateInstallation(
            download = download,
            discard = { file ->
                discarded.add(file)
                Files.deleteIfExists(file)
            },
            accept = accept,
            log = { logs += it },
        )

    private suspend fun UpdateInstallation.install(available: UpdateCheck.Available): UpdateInstall =
        install(RELEASE_URL, available) { stages += it }

    // SHA-256 が合えば、配布物を accept へ渡し、accept が返した段階で終わる。途中はダウンロードと確かめている最中を知らせる
    @Test
    fun acceptsReleaseWhoseDigestMatches() = runTest {
        val result = installation().install(available())

        assertEquals(UpdateInstall.ReadyToApply, result)
        assertEquals(listOf(release), accepted)
        assertTrue(discarded.isEmpty())
        assertTrue(release.exists())
        val size = RELEASE_CONTENT.size.toLong()
        assertEquals(listOf(UpdateInstall.Downloading(size, size), UpdateInstall.Verifying), stages)
        assertEquals(listOf(RELEASE_URL), requestedUrls)
    }

    // accept がそのままインストーラへ渡したら、その段階（Launching）で終わる
    @Test
    fun endsWithStageAcceptReturns() = runTest {
        val result = installation(accept = { _, _ -> UpdateInstall.Launching }).install(available())

        assertEquals(UpdateInstall.Launching, result)
    }

    // SHA-256 が合わなければ、配布物を消し、accept へ渡さず、確かめに失敗したことで終わる
    @Test
    fun discardsReleaseWhoseDigestDiffers() = runTest {
        val result = installation().install(available(sha256 = "0".repeat(64)))

        assertEquals(UpdateInstall.Failed(UpdateInstallFailure.VERIFICATION_FAILED), result)
        assertEquals(listOf(release), discarded)
        assertFalse(release.exists())
        assertTrue(accepted.isEmpty())
        assertTrue(logs.any { "SHA-256" in it }, logs.toString())
    }

    // 期待した値が空なら確かめられないので、配布物を消して、確かめに失敗したことで終わる
    @Test
    fun discardsReleaseWhenExpectedDigestIsBlank() = runTest {
        val result = installation().install(available(sha256 = ""))

        assertEquals(UpdateInstall.Failed(UpdateInstallFailure.VERIFICATION_FAILED), result)
        assertEquals(listOf(release), discarded)
        assertTrue(accepted.isEmpty())
    }

    // 確かめるために読めなかったときも、合わなかったときと同じく、消して確かめに失敗したことで終わる
    @Test
    fun treatsUnreadableReleaseAsMismatch() = runTest {
        val missing = temporaryFolder.root.toPath().resolve("missing")

        val result = installation(download = { _, _ -> missing }).install(available())

        assertEquals(UpdateInstall.Failed(UpdateInstallFailure.VERIFICATION_FAILED), result)
        assertEquals(listOf(missing), discarded)
        assertTrue(accepted.isEmpty())
    }

    // ダウンロードに失敗したら、確かめに進まず、何も消さずに、ダウンロードできなかったことで終わる。例外の種類をログに書く
    @Test
    fun reportsDownloadFailure() = runTest {
        val result = installation(download = { _, _ -> throw IOException("connection reset") }).install(available())

        assertEquals(UpdateInstall.Failed(UpdateInstallFailure.DOWNLOAD_FAILED), result)
        assertFalse(UpdateInstall.Verifying in stages)
        assertTrue(discarded.isEmpty())
        assertTrue(accepted.isEmpty())
        assertTrue(logs.any { "IOException" in it }, logs.toString())
    }

    // 本物のダウンロード（downloadToFile）で途中で上限を超えたら、書きかけを残さず、ダウンロードできなかったことで終わる
    @Test
    fun leavesNoPartialFileWhenRealDownloadFails() = runTest {
        val client = HttpClient(
            MockEngine { respond(content = ByteReadChannel(ByteArray(300_000)), status = HttpStatusCode.OK, headers = headersOf()) },
        )
        val download: ReleaseDownloader = { url, onProgress ->
            client.downloadToFile(url, release, maxBytes = 100_000, onProgress = onProgress)
            release
        }

        val result = installation(download = download).install(available())

        assertEquals(UpdateInstall.Failed(UpdateInstallFailure.DOWNLOAD_FAILED), result)
        assertFalse(release.exists(), "a partial download was left")
    }

    // 確かめた配布物を accept が受け取れずに投げたら、配布物を消し、インストールできなかったことで終わる
    @Test
    fun reportsHandOverFailure() = runTest {
        val result = installation(accept = { _, _ -> throw IllegalStateException("no installer") }).install(available())

        assertEquals(UpdateInstall.Failed(UpdateInstallFailure.HAND_OVER_FAILED), result)
        assertEquals(listOf(release), discarded)
        assertTrue(logs.any { "IllegalStateException" in it }, logs.toString())
    }

    // ダウンロードを終えたあとで取り消されたら、配布物を消し、accept へ渡さずに取り消しを伝える。止まったことは知らせない
    @Test
    fun discardsReleaseWhenCancelledAfterDownload() = runTest {
        var returned = false
        val job = launch {
            installation(
                download = { url, onProgress ->
                    writingDownload(url, onProgress).also { currentCoroutineContext().job.cancel() }
                },
            ).install(available())
            returned = true
        }

        job.join()

        assertTrue(job.isCancelled)
        assertFalse(returned)
        assertEquals(listOf(release), discarded)
        assertFalse(release.exists(), "the downloaded release was left")
        assertTrue(accepted.isEmpty())
        assertTrue(stages.none { it is UpdateInstall.Failed }, stages.toString())
    }

    // 配布物を消すところで投げても、止まった段階はそのまま返し、消せなかったことをログに書く
    @Test
    fun keepsFailureWhenDiscardThrows() = runTest {
        val installation = UpdateInstallation(
            download = writingDownload,
            discard = { throw IOException("locked") },
            accept = { _, _ -> UpdateInstall.ReadyToApply },
            log = { logs += it },
        )

        val result = installation.install(RELEASE_URL, available(sha256 = "0".repeat(64))) { stages += it }

        assertEquals(UpdateInstall.Failed(UpdateInstallFailure.VERIFICATION_FAILED), result)
        assertTrue(logs.any { "消せなかった" in it && "locked" in it }, logs.toString())
    }

    // ダウンロードしている最中の取り消しは握らずに伝わる。止まったことは知らせず、何も消さず、accept へも渡さない
    @Test
    fun cancellationPropagatesWithoutFailure() = runTest {
        val started = CompletableDeferred<Unit>()
        var returned = false
        val job = launch {
            installation(
                download = { _, _ ->
                    started.complete(Unit)
                    awaitCancellation()
                },
            ).install(available())
            returned = true
        }

        started.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertFalse(returned)
        assertTrue(stages.none { it is UpdateInstall.Failed }, stages.toString())
        assertTrue(discarded.isEmpty())
        assertTrue(accepted.isEmpty())
    }
}
