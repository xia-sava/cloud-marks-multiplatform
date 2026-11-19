package to.sava.cloudmarksmultiplatform.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.desktopAppFilesIn
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.LocalDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ダウンロードしたことにする配布物の中身。 */
private val RELEASE_CONTENT = "cloudmarks-setup".encodeToByteArray()

/** 置いたままと見なすまでの時間（10 分）より古い時間。 */
private const val STALE_MINUTES = 30L

private const val MILLIS_PER_MINUTE = 60_000L

class DesktopUpdaterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val key = TestSigningKey()
    private val logs = CopyOnWriteArrayList<String>()
    private val handedOver = CopyOnWriteArrayList<Path>()

    /** 確認とダウンロードを、1 つのスレッドで、走らせた順に動かす。置いたままのダウンロードを消し終えてから確認が始まる。 */
    private val dispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private var created: DesktopUpdater? = null

    private val root: Path by lazy { temporaryFolder.root.toPath() }
    private val updateDirectory: Path by lazy { root.resolve("updates") }
    private val logDirectory: Path by lazy { root.resolve("logs") }
    private val stateDirectory: Path by lazy { root.resolve("state") }
    private val applyLog: UpdateApplyLog by lazy { UpdateApplyLog(logDirectory, stateDirectory) { logs += it } }

    @After
    fun cleanUp() {
        created?.close()
        dispatcher.close()
    }

    /**
     * デスクトップの項目の SHA-256 が [desktopSha256] の更新情報とその署名を返し、配布物の要求には [RELEASE_CONTENT] を返す偽のエンジン。
     * [releaseStatus] が成功でなければ、配布物の要求にはその状態を返す。
     */
    private fun engine(
        desktopSha256: String = sha256Of(RELEASE_CONTENT),
        releaseStatus: HttpStatusCode = HttpStatusCode.OK,
    ): MockEngine {
        val updateInfo = updateInfoJson(desktopSha256)
        val signature = key.sign(updateInfo)
        return MockEngine { request ->
            val url = request.url.toString()
            when {
                url.endsWith(".sig") -> respond(content = signature, status = HttpStatusCode.OK)
                url.endsWith(UpdatePlatform.DESKTOP.assetName) && releaseStatus != HttpStatusCode.OK ->
                    respond(content = "", status = releaseStatus)
                url.endsWith(UpdatePlatform.DESKTOP.assetName) -> respond(
                    content = ByteReadChannel(RELEASE_CONTENT),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentLength, RELEASE_CONTENT.size.toString()),
                )
                else -> respond(
                    content = updateInfo,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
        }
    }

    /** [engine] から取り、確かめた配布物を [handOver] へ渡す更新。既定では、渡された配布物を [handedOver] に残すだけ。 */
    private fun updater(engine: MockEngine = engine(), handOver: (Path) -> Unit = { handedOver.add(it) }): DesktopUpdater =
        DesktopUpdater(
            version = DISTRIBUTED_VERSION,
            httpClient = HttpClient(engine),
            log = { logs += it },
            updateDirectory = updateDirectory,
            applyLog = applyLog,
            handOver = handOver,
            publicKey = key.publicKey,
            dispatcher = dispatcher,
        ).also { created = it }

    /** 確認して、ダウンロードし、返事を待つ段階か止まるまで待つ。 */
    private suspend fun DesktopUpdater.downloadAndWait(): UpdateState {
        check()
        state.first { it.check != UpdateCheck.Checking }
        download()
        return state.first { it.install == UpdateInstall.ReadyToApply || it.install is UpdateInstall.Failed }
    }

    /** 更新のディレクトリの下の、ダウンロードのディレクトリ。 */
    private fun downloadDirectories(): List<Path> =
        if (updateDirectory.exists()) Files.list(updateDirectory).use { entries -> entries.toList() } else emptyList()

    /** ただ 1 つのダウンロードのディレクトリに置いた配布物。 */
    private fun downloadedSetup(): Path = downloadDirectories().single().resolve(UpdatePlatform.DESKTOP.assetName)

    // 確かめに通ったら、すぐにはインストーラへ渡さず、返事を待つ。配布物は更新のディレクトリの下のダウンロードのディレクトリに置く
    @Test
    fun stopsForAnswerAfterVerifying() = runTest {
        val updater = updater()

        val installed = updater.downloadAndWait()

        assertEquals(UpdateInstall.ReadyToApply, installed.install)
        assertTrue(handedOver.isEmpty())
        val setup = downloadedSetup()
        assertTrue(setup.parent.fileName.toString().startsWith("download-"), setup.toString())
        assertContentEquals(RELEASE_CONTENT, setup.readBytes())
    }

    // ダウンロードするたびに新しいディレクトリに置く。やめたら、そのディレクトリごと消す
    @Test
    fun downloadsIntoNewDirectoryEachTime() = runTest {
        val updater = updater()
        updater.downloadAndWait()
        val first = downloadDirectories().single()

        updater.cancel()
        assertNull(updater.state.value.install)
        assertTrue(downloadDirectories().isEmpty())
        updater.download()
        updater.state.first { it.install == UpdateInstall.ReadyToApply }

        assertNotEquals(first, downloadDirectories().single())
    }

    // SHA-256 が更新情報と合わなければ、ダウンロードのディレクトリごと消して止まり、インストーラへ渡さない
    @Test
    fun discardsDownloadThatFailsVerification() = runTest {
        val updater = updater(engine = engine(desktopSha256 = DESKTOP_SHA256))

        val installed = updater.downloadAndWait()

        assertEquals(UpdateInstall.Failed(UpdateInstallFailure.VERIFICATION_FAILED), installed.install)
        assertTrue(downloadDirectories().isEmpty())
        assertTrue(handedOver.isEmpty())
    }

    // ダウンロードできなければ、作ったダウンロードのディレクトリを残さない
    @Test
    fun discardsDirectoryOfFailedDownload() = runTest {
        val updater = updater(engine = engine(releaseStatus = HttpStatusCode.NotFound))

        val installed = updater.downloadAndWait()

        assertEquals(UpdateInstall.Failed(UpdateInstallFailure.DOWNLOAD_FAILED), installed.install)
        assertTrue(downloadDirectories().isEmpty())
    }

    // インストールすると、確かめた配布物をインストーラへ渡し、インストーラへ渡した段階へ進めてから、アプリを終了する関数を呼ぶ
    @Test
    fun handsOverVerifiedSetupThenLetsCallerExit() = runTest {
        val updater = updater()
        updater.downloadAndWait()
        val setup = downloadedSetup()
        val stagesAtExit = mutableListOf<UpdateInstall?>()

        updater.apply { stagesAtExit += updater.state.value.install }

        assertEquals(listOf(setup), handedOver)
        assertEquals(listOf<UpdateInstall?>(UpdateInstall.Launching), stagesAtExit)
        assertEquals(UpdateInstall.Launching, updater.state.value.install)
    }

    // インストーラへ渡す前に、適用するスクリプトが結果を書くログのディレクトリと、記録のディレクトリを作る
    @Test
    fun preparesDirectoriesBeforeHandingOver() = runTest {
        val directoriesAtHandOver = mutableListOf<Boolean>()
        val updater = updater(handOver = { directoriesAtHandOver += Files.isDirectory(logDirectory) && Files.isDirectory(stateDirectory) })
        updater.downloadAndWait()
        assertFalse(logDirectory.exists() || stateDirectory.exists())

        updater.apply {}

        assertEquals(listOf(true), directoriesAtHandOver)
    }

    // 渡したバージョンを記録し、次の起動で、スクリプトが書いた結果と突き合わせて伝える
    @Test
    fun recordsVersionItHandedOver() = runTest {
        val updater = updater()
        updater.downloadAndWait()

        updater.apply {}
        val recorded = applyLog.attemptFile.readText()
        val attemptedAt = LocalDateTime.parse(recorded.substringAfter('\t'))
        applyLog.resultLogFile.writeText("${attemptedAt.plusSeconds(1)} setup failed with exit code 1\n")

        assertEquals(RELEASED_VERSION_NAME, recorded.substringBefore('\t'))
        assertEquals(RELEASED_VERSION_NAME, applyLog.takeFailedVersionName(DISTRIBUTED_VERSION.versionName))
    }

    // アプリを終了する関数を呼ぶときには、試みの記録を書き終えている
    @Test
    fun recordsAttemptBeforeLettingCallerExit() = runTest {
        val updater = updater()
        updater.downloadAndWait()
        val recordedAtExit = mutableListOf<Boolean>()

        updater.apply { recordedAtExit += applyLog.attemptFile.exists() }

        assertEquals(listOf(true), recordedAtExit)
    }

    // 返事を待つあいだに 10 分を超えても、確認を受け付けないので、置いたままのダウンロードとして消さない。そのあとインストールできる
    @Test
    fun keepsDownloadWaitingLongForAnswer() = runTest {
        val updater = updater()
        updater.downloadAndWait()
        val setup = downloadedSetup()
        Files.setLastModifiedTime(setup.parent, FileTime.fromMillis(System.currentTimeMillis() - STALE_MINUTES * MILLIS_PER_MINUTE))

        updater.check()
        // 確認で走らせたものがあれば、同じスレッドで先に終わる
        withContext(dispatcher) {}

        assertTrue(setup.exists())
        updater.apply {}
        assertEquals(listOf(setup), handedOver)
        assertEquals(UpdateInstall.Launching, updater.state.value.install)
    }

    // 返事を待つあいだに中身が替わったら、渡す直前に確かめ直して気づき、渡さずに止まる。アプリを終了する関数も呼ばない
    @Test
    fun refusesSetupChangedWhileWaiting() = runTest {
        val updater = updater()
        updater.downloadAndWait()
        downloadedSetup().writeText("swapped")
        var exits = 0

        updater.apply { exits += 1 }

        assertEquals(UpdateInstall.Failed(UpdateInstallFailure.VERIFICATION_FAILED), updater.state.value.install)
        assertTrue(handedOver.isEmpty())
        assertEquals(0, exits)
        assertTrue(downloadDirectories().isEmpty())
        assertFalse(applyLog.attemptFile.exists())
    }

    // 返事を待つあいだに配布物が無くなっていたら、確かめに失敗したことにして止まる
    @Test
    fun refusesSetupMissingWhileWaiting() = runTest {
        val updater = updater()
        updater.downloadAndWait()
        downloadedSetup().deleteExisting()
        var exits = 0

        updater.apply { exits += 1 }

        assertEquals(UpdateInstall.Failed(UpdateInstallFailure.VERIFICATION_FAILED), updater.state.value.install)
        assertTrue(handedOver.isEmpty())
        assertEquals(0, exits)
    }

    // インストーラへ渡せなければ、理由をログに書き、配布物を消して止まる。アプリを終了する関数は呼ばず、試みも記録しない
    @Test
    fun handOverFailureStopsWithoutExit() = runTest {
        val updater = updater(handOver = { throw IOException("PowerShell が見つからない") })
        updater.downloadAndWait()
        var exits = 0

        updater.apply { exits += 1 }

        assertEquals(UpdateInstall.Failed(UpdateInstallFailure.HAND_OVER_FAILED), updater.state.value.install)
        assertEquals(0, exits)
        assertTrue(downloadDirectories().isEmpty())
        assertFalse(applyLog.attemptFile.exists())
        assertTrue(logs.any { "インストーラへ渡せなかった" in it && "PowerShell が見つからない" in it }, logs.toString())
    }

    // 返事を待つ段階でなければ、インストールの操作は何もしない
    @Test
    fun applyDoesNothingUnlessReadyToApply() = runTest {
        val updater = updater()
        var exits = 0

        updater.apply { exits += 1 }
        updater.check()
        updater.state.first { it.check != UpdateCheck.Checking }
        updater.apply { exits += 1 }

        assertEquals(0, exits)
        assertNull(updater.state.value.install)
        assertTrue(handedOver.isEmpty())
    }

    // インストーラへ渡した配布物は、閉じても消さない。適用するスクリプトが、アプリの終了を待ってから使う
    @Test
    fun keepsHandedOverSetupWhenClosed() = runTest {
        val updater = updater()
        updater.downloadAndWait()
        val setup = downloadedSetup()

        updater.apply {}
        updater.close()

        assertTrue(setup.exists())
    }

    // 返事を待っているあいだに閉じたら、ダウンロードしたものを残さない
    @Test
    fun discardsDownloadWhenClosedBeforeAnswer() = runTest {
        val updater = updater()
        updater.downloadAndWait()

        updater.close()

        assertTrue(downloadDirectories().isEmpty())
    }

    // 確認を受け付けたら、置いたままのダウンロードを消す。できたばかりのものは残す
    @Test
    fun checkDiscardsStaleDownloads() = runTest {
        val stale = updateDirectory.resolve("download-stale").createDirectories().also {
            it.resolve(UpdatePlatform.DESKTOP.assetName).writeText("handed over before")
            Files.setLastModifiedTime(it, FileTime.fromMillis(System.currentTimeMillis() - STALE_MINUTES * MILLIS_PER_MINUTE))
        }
        val fresh = updateDirectory.resolve("download-fresh").createDirectories()
        val updater = updater()

        updater.check()
        updater.state.first { it.check != UpdateCheck.Checking }

        assertFalse(stale.exists())
        assertTrue(fresh.exists())
    }

    // デスクトップのアプリの更新は、作るときに前の起動で試みた適用の結果を読み、失敗していればそのバージョンを状態に持つ
    @Test
    fun desktopAppUpdaterReadsPreviousApplyFailure() {
        val appFiles = desktopAppFilesIn(root.resolve("app"))
        UpdateApplyLog(appFiles.logDirectory, appFiles.stateDirectory) { logs += it }.run {
            prepareDirectories()
            record("1.5.0", LocalDateTime.parse("2026-09-02T12:00:00"))
            resultLogFile.writeText("2026-09-02T12:00:30 setup failed with exit code 1\n")
        }

        desktopAppUpdater(DISTRIBUTED_VERSION, appFiles) { logs += it }.use { updater ->
            assertEquals("1.5.0", updater.state.value.applyFailedVersionName)
            updater.dismissApplyFailure()
            assertNull(updater.state.value.applyFailedVersionName)
        }
        desktopAppUpdater(DISTRIBUTED_VERSION, appFiles) { logs += it }.use { updater ->
            assertNull(updater.state.value.applyFailedVersionName)
        }
    }

    // 試みたバージョンが動いていれば（失敗のあとで手で入れ直した）、結果の行が失敗でも知らせず、記録を消す
    @Test
    fun desktopAppUpdaterTreatsAttemptOfRunningVersionAsApplied() {
        val appFiles = desktopAppFilesIn(root.resolve("app"))
        val previous = UpdateApplyLog(appFiles.logDirectory, appFiles.stateDirectory) { logs += it }.apply {
            prepareDirectories()
            record(DISTRIBUTED_VERSION.versionName, LocalDateTime.parse("2026-09-02T12:00:00"))
            resultLogFile.writeText("2026-09-02T12:00:30 setup failed with exit code 1\n")
        }

        desktopAppUpdater(DISTRIBUTED_VERSION, appFiles) { logs += it }.use { updater ->
            assertNull(updater.state.value.applyFailedVersionName)
        }
        assertFalse(previous.attemptFile.exists())
    }

    // 起動したときにも、置いたままのダウンロードを消す。できたばかりのもの（適用したすぐあとの起動で、インストーラへ渡した配布物）は残す
    @Test
    fun desktopAppUpdaterDiscardsStaleDownloadsOnStart() {
        val appFiles = desktopAppFilesIn(root.resolve("app"))
        val stale = appFiles.updateDirectory.resolve("download-stale").createDirectories().also {
            it.resolve(UpdatePlatform.DESKTOP.assetName).writeText("handed over two launches ago")
            Files.setLastModifiedTime(it, FileTime.fromMillis(System.currentTimeMillis() - STALE_MINUTES * MILLIS_PER_MINUTE))
        }
        val fresh = appFiles.updateDirectory.resolve("download-fresh").createDirectories().also {
            it.resolve(UpdatePlatform.DESKTOP.assetName).writeText("handed over just before this launch")
        }

        desktopAppUpdater(DISTRIBUTED_VERSION, appFiles) { logs += it }.close()

        assertFalse(stale.exists())
        assertTrue(fresh.resolve(UpdatePlatform.DESKTOP.assetName).exists())
    }
}
