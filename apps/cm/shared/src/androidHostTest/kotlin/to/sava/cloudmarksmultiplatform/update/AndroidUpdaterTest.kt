package to.sava.cloudmarksmultiplatform.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ダウンロードしたことにする APK の中身。 */
private val APK_CONTENT = "cloudmarks-apk".encodeToByteArray()

/** 偽の端末のインストーラ。パッケージ名を見た APK と、渡された APK とその中身を残す。 */
private class FakeApkInstaller : ApkInstaller {
    /** APK のパッケージ名を、このアプリのものとするか。 */
    @Volatile
    var ownPackage: Boolean = true

    /** あれば、渡すときにそれを投げる。 */
    @Volatile
    var handOverFailure: Exception? = null

    val checked = CopyOnWriteArrayList<Path>()
    val handedOver = CopyOnWriteArrayList<Path>()
    val handedOverContents = CopyOnWriteArrayList<ByteArray>()

    override fun isOwnPackage(apk: Path): Boolean {
        checked.add(apk)
        return ownPackage
    }

    override fun install(apk: Path) {
        handOverFailure?.let { throw it }
        handedOverContents.add(apk.readBytes())
        handedOver.add(apk)
    }
}

class AndroidUpdaterTest {
    private val root: Path = Files.createTempDirectory("cmm-android-updater-test")
    private val updateDirectory: Path = root.resolve("updates")
    private val key = TestSigningKey()
    private val logs = CopyOnWriteArrayList<String>()
    private val installer = FakeApkInstaller()
    private var created: AndroidUpdater? = null

    /** ダウンロードした APK の置き場所。更新のディレクトリの下の、決まった名前のファイル。 */
    private val downloadedApk: Path = updateDirectory.resolve(UpdatePlatform.ANDROID.assetName)

    @AfterTest
    fun cleanUp() {
        created?.close()
        root.toFile().deleteRecursively()
    }

    /**
     * Android の項目の SHA-256 が [androidSha256] の更新情報とその署名を返し、APK の要求には [APK_CONTENT] を返す偽のエンジン。
     * [apkGate] を渡すと、それが終わるまで APK の要求に応答を返さない。
     */
    private fun engine(androidSha256: String = sha256Of(APK_CONTENT), apkGate: Deferred<Unit>? = null): MockEngine {
        val updateInfo = updateInfoJson(androidSha256 = androidSha256)
        val signature = key.sign(updateInfo)
        return MockEngine { request ->
            val url = request.url.toString()
            when {
                url.endsWith(".sig") -> respond(content = signature, status = HttpStatusCode.OK)
                url.endsWith(UpdatePlatform.ANDROID.assetName) -> {
                    apkGate?.await()
                    respond(
                        content = ByteReadChannel(APK_CONTENT),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentLength, APK_CONTENT.size.toString()),
                    )
                }
                else -> respond(
                    content = updateInfo,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
        }
    }

    /** [engine] から取り、確かめた APK を [installer] へ渡す更新。 */
    private fun updater(version: AppVersion = DISTRIBUTED_VERSION, engine: MockEngine = engine()): AndroidUpdater =
        AndroidUpdater(
            version = version,
            httpClient = HttpClient(engine),
            log = { logs += it },
            updateDirectory = updateDirectory,
            installer = installer,
            publicKey = key.publicKey,
        ).also { created = it }

    /** 確認を始め、終わるまで待つ。 */
    private suspend fun AndroidUpdater.checkAndWait(): UpdateState {
        check()
        return state.first { it.check != UpdateCheck.Checking }
    }

    /** ダウンロードを始め、インストーラへ渡すか、止まるまで待つ。 */
    private suspend fun AndroidUpdater.downloadAndWait(): UpdateState {
        download()
        return state.first { it.install == UpdateInstall.Launching || it.install is UpdateInstall.Failed }
    }

    // 確かめに通ったら、返事を待たずにすぐ端末のインストーラへ渡し、インストーラへ渡した段階へ進む。渡すのは、更新のディレクトリの下の
    // 決まった名前のファイルにダウンロードした APK で、渡す前にそのパッケージ名を見る
    @Test
    fun handsOverRightAfterVerifying() = runTest {
        val updater = updater()
        updater.checkAndWait()

        val installed = updater.downloadAndWait()

        assertEquals(UpdateInstall.Launching, installed.install)
        assertEquals(listOf(downloadedApk), installer.checked)
        assertEquals(listOf(downloadedApk), installer.handedOver)
        assertContentEquals(APK_CONTENT, installer.handedOverContents.single())
    }

    // パッケージ名がこのアプリのものでないか、APK として読めなければ、渡さずに APK を消して、確かめに失敗したことで止まる
    @Test
    fun refusesApkOfOtherPackage() = runTest {
        installer.ownPackage = false
        val updater = updater()
        updater.checkAndWait()

        val installed = updater.downloadAndWait()

        assertEquals(UpdateInstall.Failed(UpdateInstallFailure.VERIFICATION_FAILED), installed.install)
        assertTrue(installer.handedOver.isEmpty())
        assertFalse(downloadedApk.exists())
    }

    // SHA-256 が更新情報と合わなければ、パッケージ名を見る前に APK を消して、確かめに失敗したことで止まる
    @Test
    fun discardsApkWhoseDigestDiffers() = runTest {
        val updater = updater(engine = engine(androidSha256 = ANDROID_SHA256))
        updater.checkAndWait()

        val installed = updater.downloadAndWait()

        assertEquals(UpdateInstall.Failed(UpdateInstallFailure.VERIFICATION_FAILED), installed.install)
        assertTrue(installer.checked.isEmpty())
        assertTrue(installer.handedOver.isEmpty())
        assertFalse(downloadedApk.exists())
    }

    // 端末のインストーラへ渡せなければ、理由をログに書き、APK を消して、渡せなかったことで止まる
    @Test
    fun handOverFailureStops() = runTest {
        installer.handOverFailure = IllegalArgumentException("FileProvider の場所の外")
        val updater = updater()
        updater.checkAndWait()

        val installed = updater.downloadAndWait()

        assertEquals(UpdateInstall.Failed(UpdateInstallFailure.HAND_OVER_FAILED), installed.install)
        assertFalse(downloadedApk.exists())
        assertTrue(logs.any { "インストーラへ渡せなかった" in it && "FileProvider の場所の外" in it }, logs.toString())
    }

    // 渡したあとで画面が操作を受け付けられるようになったら（インストールしなかった）、ダウンロードを始める前に戻り、
    // もう一度ダウンロードして渡せる。APK は同じファイルに書き換える
    @Test
    fun resumingAfterHandOverAllowsDownloadAgain() = runTest {
        val updater = updater()
        updater.checkAndWait()
        updater.downloadAndWait()

        updater.onResumed()
        assertNull(updater.state.value.install)
        assertTrue(updater.state.value.canDownload)
        val installedAgain = updater.downloadAndWait()

        assertEquals(UpdateInstall.Launching, installedAgain.install)
        assertEquals(listOf(downloadedApk, downloadedApk), installer.handedOver)
    }

    // ダウンロードしている最中に画面が操作を受け付けられるようになっても、ダウンロードを続け、確かめたら渡す
    @Test
    fun resumingWhileDownloadingKeepsDownloading() = runTest {
        val gate = CompletableDeferred<Unit>()
        val updater = updater(engine = engine(apkGate = gate))
        updater.checkAndWait()

        updater.download()
        updater.onResumed()
        assertEquals(UpdateInstall.Downloading(0, 0), updater.state.value.install)
        gate.complete(Unit)
        val installed = updater.state.first { it.install == UpdateInstall.Launching || it.install is UpdateInstall.Failed }

        assertEquals(UpdateInstall.Launching, installed.install)
        assertEquals(listOf(downloadedApk), installer.handedOver)
    }

    // 止まった理由は、画面が操作を受け付けられるようになっただけでは消さない
    @Test
    fun resumingKeepsFailure() = runTest {
        installer.ownPackage = false
        val updater = updater()
        updater.checkAndWait()
        updater.downloadAndWait()

        updater.onResumed()

        assertEquals(UpdateInstall.Failed(UpdateInstallFailure.VERIFICATION_FAILED), updater.state.value.install)
    }

    // インストールしてよいかを自分では尋ねないので、渡したあとでインストールの操作を呼んでも何もせず、アプリを終える関数も呼ばない
    @Test
    fun applyDoesNothing() = runTest {
        val updater = updater()
        updater.checkAndWait()
        updater.downloadAndWait()
        var exits = 0

        updater.apply { exits += 1 }

        assertEquals(0, exits)
        assertEquals(UpdateInstall.Launching, updater.state.value.install)
        assertEquals(1, installer.handedOver.size)
    }

    // 開発版（デバッグビルド）は、新しいバージョンがあると分かっても、APK をダウンロードせず、インストーラへも渡さない
    @Test
    fun devBuildDoesNotDownload() = runTest {
        val engine = engine()
        val updater = updater(version = DEV_VERSION, engine = engine)

        val checked = updater.checkAndWait()
        updater.download()

        assertEquals(UpdateCheck.Available(RELEASED_VERSION_NAME, sha256Of(APK_CONTENT)), checked.check)
        assertNull(updater.state.value.install)
        assertTrue(engine.requestHistory.none { it.url.toString().endsWith(UpdatePlatform.ANDROID.assetName) })
        assertTrue(installer.handedOver.isEmpty())
    }

    // 渡した APK は、閉じても消さない。端末のインストーラが読む
    @Test
    fun keepsHandedOverApkWhenClosed() = runTest {
        val updater = updater()
        updater.checkAndWait()
        updater.downloadAndWait()

        updater.close()

        assertTrue(downloadedApk.exists())
    }
}
