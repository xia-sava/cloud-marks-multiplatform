package to.sava.cloudmarksmultiplatform.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 更新情報と署名の上限より大きい本体の大きさ。 */
private const val OVERSIZE_BYTES = MAX_UPDATE_INFO_BYTES.toInt() + 1

class UpdateCheckerTest {
    private val key = TestSigningKey()
    private val logs = mutableListOf<String>()

    private fun checker(
        engine: MockEngine,
        platform: UpdatePlatform = UpdatePlatform.DESKTOP,
        currentVersionCode: Int = DISTRIBUTED_VERSION.versionCode,
        publicKey: String = key.publicKey,
    ): UpdateChecker = UpdateChecker(HttpClient(engine), platform, currentVersionCode, { logs += it }, publicKey)

    /** [updateInfo] と、それへのテストの鍵の署名を返すエンジン。 */
    private fun signed(updateInfo: String): MockEngine = updateInfoEngine(updateInfo, key.sign(updateInfo))

    // 配布しているバージョンが新しければ、新しいバージョンの文字列と、デスクトップの配布物の SHA-256 を返す
    @Test
    fun availableWhenReleasedVersionIsNewer() = runTest {
        val result = checker(signed(UPDATE_INFO)).check()

        assertEquals(UpdateCheck.Available(RELEASED_VERSION_NAME, DESKTOP_SHA256), result)
    }

    // Android は、更新情報の Android の項目を読む
    @Test
    fun androidReadsAndroidRelease() = runTest {
        val result = checker(signed(UPDATE_INFO), platform = UpdatePlatform.ANDROID).check()

        assertEquals(UpdateCheck.Available(RELEASED_VERSION_NAME, ANDROID_SHA256), result)
    }

    // 配布しているバージョンが同じか古ければ、最新
    @Test
    fun upToDateWhenReleasedVersionIsNotNewer() = runTest {
        listOf(RELEASED_VERSION_CODE, RELEASED_VERSION_CODE + 1, Int.MAX_VALUE).forEach { current ->
            assertEquals(UpdateCheck.UpToDate, checker(signed(UPDATE_INFO), currentVersionCode = current).check(), "current=$current")
        }
    }

    // 更新情報、その署名の順に、決まった URL へ要求を出す
    @Test
    fun requestsFixedUrlsInOrder() = runTest {
        val engine = signed(UPDATE_INFO)

        checker(engine).check()

        assertEquals(
            listOf(UPDATE_INFO_URL, UPDATE_INFO_SIGNATURE_URL),
            engine.requestHistory.map { it.url.toString() },
        )
    }

    // 知らない欄（配布物の URL を含む）のある更新情報も読み、配布物の URL は使わない
    @Test
    fun ignoresUnknownFieldsInSignedUpdateInfo() = runTest {
        val updateInfo = """{"schemaVersion":2,"desktop":{"versionCode":30,"versionName":"3.0.0","sha256":"$DESKTOP_SHA256",""" +
            """"url":"http://example.invalid/evil.exe"}}"""

        val result = checker(signed(updateInfo)).check()

        assertEquals(UpdateCheck.Available("3.0.0", DESKTOP_SHA256), result)
    }

    // 更新情報の HTTP の状態が成功でなければ、取得できなかったことにし、署名は取りに行かない。状態はログにだけ書く
    @Test
    fun unreachableWhenUpdateInfoStatusIsNotSuccess() = runTest {
        val engine = MockEngine { respond(content = "", status = HttpStatusCode.NotFound) }

        val result = checker(engine).check()

        assertEquals(UpdateCheck.Failed(UpdateCheckFailure.UNREACHABLE), result)
        assertEquals(1, engine.requestHistory.size)
        assertTrue(logs.any { "404" in it }, logs.toString())
    }

    // 通信の失敗は握り潰さず、取得できなかったことにして、例外の種類をログに書く
    @Test
    fun unreachableWhenRequestThrows() = runTest {
        val engine = MockEngine { throw IOException("connection refused") }

        val result = checker(engine).check()

        assertEquals(UpdateCheck.Failed(UpdateCheckFailure.UNREACHABLE), result)
        assertTrue(logs.any { "IOException" in it }, logs.toString())
    }

    // 更新情報が上限より大きければ、大きさを言っていてもいなくても、取得できなかったことにする
    @Test
    fun unreachableWhenUpdateInfoIsTooLarge() = runTest {
        listOf(true, false).forEach { announcesLength ->
            val engine = MockEngine {
                respond(
                    content = ByteReadChannel(ByteArray(OVERSIZE_BYTES)),
                    status = HttpStatusCode.OK,
                    headers = if (announcesLength) headersOf(HttpHeaders.ContentLength, OVERSIZE_BYTES.toString()) else headersOf(),
                )
            }

            assertEquals(UpdateCheck.Failed(UpdateCheckFailure.UNREACHABLE), checker(engine).check(), "announcesLength=$announcesLength")
        }
    }

    // 署名を取れなければ、署名を確かめられなかったことにする（署名が無ければ通す道を作らない）
    @Test
    fun signatureUnverifiedWhenSignatureIsMissing() = runTest {
        val result = checker(updateInfoEngine(UPDATE_INFO, signature = null)).check()

        assertEquals(UpdateCheck.Failed(UpdateCheckFailure.SIGNATURE_UNVERIFIED), result)
    }

    // 署名が上限より大きければ読まず、署名を確かめられなかったことにする
    @Test
    fun signatureUnverifiedWhenSignatureIsTooLarge() = runTest {
        val result = checker(updateInfoEngine(UPDATE_INFO, signature = "A".repeat(OVERSIZE_BYTES))).check()

        assertEquals(UpdateCheck.Failed(UpdateCheckFailure.SIGNATURE_UNVERIFIED), result)
    }

    // 署名したあとで書き換えた更新情報は、署名を確かめられなかったことにする
    @Test
    fun signatureUnverifiedWhenUpdateInfoIsTampered() = runTest {
        val tampered = UPDATE_INFO.replace("\"versionName\":\"2.0.0\"", "\"versionName\":\"9.9.9\"")

        val result = checker(updateInfoEngine(tampered, key.sign(UPDATE_INFO))).check()

        assertEquals(UpdateCheck.Failed(UpdateCheckFailure.SIGNATURE_UNVERIFIED), result)
    }

    // 別の鍵で署名した更新情報は、署名を確かめられなかったことにする
    @Test
    fun signatureUnverifiedWhenSignedByAnotherKey() = runTest {
        val result = checker(updateInfoEngine(UPDATE_INFO, TestSigningKey().sign(UPDATE_INFO))).check()

        assertEquals(UpdateCheck.Failed(UpdateCheckFailure.SIGNATURE_UNVERIFIED), result)
    }

    // 埋め込みの公開鍵を使う既定のままでは、テストの鍵で署名した更新情報を受け付けない
    @Test
    fun embeddedPublicKeyRejectsTestSignature() = runTest {
        val checker = UpdateChecker(HttpClient(signed(UPDATE_INFO)), UpdatePlatform.DESKTOP, DISTRIBUTED_VERSION.versionCode, { logs += it })

        assertEquals(UpdateCheck.Failed(UpdateCheckFailure.SIGNATURE_UNVERIFIED), checker.check())
    }

    // 署名は合うが JSON として読めない更新情報は、読み取れなかったことにする
    @Test
    fun unreadableWhenSignedUpdateInfoIsNotJson() = runTest {
        val result = checker(signed("not json {")).check()

        assertEquals(UpdateCheck.Failed(UpdateCheckFailure.UNREADABLE), result)
    }

    // 署名は合うが、このプラットフォームの項目が無ければ、読み取れなかったことにし、そのことをログに書く
    @Test
    fun unreadableWhenPlatformReleaseIsMissing() = runTest {
        val androidOnly = """{"android":{"versionCode":20,"versionName":"2.0.0","sha256":"$ANDROID_SHA256"}}"""

        val result = checker(signed(androidOnly)).check()

        assertEquals(UpdateCheck.Failed(UpdateCheckFailure.UNREADABLE), result)
        assertTrue(logs.any { "desktop" in it }, logs.toString())
    }

    // 署名は合い、ほかのプラットフォームの項目が壊れていても、自分の項目を読む
    @Test
    fun availableWhenOtherPlatformReleaseIsMalformed() = runTest {
        val updateInfo = """{"android":{"versionCode":"20"},"desktop":{"versionCode":20,"versionName":"2.0.0","sha256":"$DESKTOP_SHA256"}}"""

        val result = checker(signed(updateInfo)).check()

        assertEquals(UpdateCheck.Available("2.0.0", DESKTOP_SHA256), result)
    }

    // 署名は合うが、versionCode が整数でないか sha256 の形が合わなければ、読み取れなかったことにする
    @Test
    fun unreadableWhenSignedReleaseIsMalformed() = runTest {
        listOf(
            """{"desktop":{"versionCode":"20","versionName":"2.0.0","sha256":"$DESKTOP_SHA256"}}""",
            """{"desktop":{"versionCode":20,"versionName":"2.0.0","sha256":"${DESKTOP_SHA256.uppercase()}"}}""",
        ).forEach { updateInfo ->
            assertEquals(UpdateCheck.Failed(UpdateCheckFailure.UNREADABLE), checker(signed(updateInfo)).check(), updateInfo)
        }
    }

    // 確認している最中の取り消しは握らずに伝わり、結果を返さない
    @Test
    fun cancellationPropagates() = runTest {
        val requested = CompletableDeferred<Unit>()
        val engine = MockEngine {
            requested.complete(Unit)
            awaitCancellation()
        }
        var returned = false
        val job = launch {
            checker(engine).check()
            returned = true
        }

        requested.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertFalse(returned)
        assertTrue(logs.isEmpty(), logs.toString())
    }
}
