package to.sava.cloudmarksmultiplatform.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** 上限を確かめるときの、小さな上限。 */
private const val SMALL_LIMIT_BYTES = 1_000L

/** 取りに行く配布物の URL。 */
private const val RELEASE_URL = "https://example.com/cloudmarks-setup.exe"

/** 取り消しを確かめるときに、先に送る本体の大きさ。受け取った量を知らせる間隔（512 KiB）より大きい。 */
private const val PARTIAL_BODY_BYTES = 600 * 1024

/** 取り消しを確かめるテストの、実際の時間での上限。取り消しが受け取りを止められなければ、ここで落ちる。 */
private val CANCELLATION_TIMEOUT = 10.seconds

class ReleaseDownloadTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** ダウンロードした配布物を置くファイル。まだ無いディレクトリの中。 */
    private val target: Path by lazy { temporaryFolder.root.toPath().resolve("updates").resolve("cloudmarks-setup.exe") }

    private fun copy(source: ByteArray, total: Long): Pair<ByteArray, List<Pair<Long, Long>>> {
        val output = ByteArrayOutputStream()
        val reports = mutableListOf<Pair<Long, Long>>()
        copyReportingProgress(ByteArrayInputStream(source), output, total, MAX_RELEASE_BYTES) { received, reported ->
            reports += received to reported
        }
        return output.toByteArray() to reports
    }

    /** 本体 [body] を返すエンジン。[announcesLength] なら、全体の大きさを Content-Length で言う。 */
    private fun engineResponding(body: ByteArray, announcesLength: Boolean = true): MockEngine =
        MockEngine {
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
                headers = if (announcesLength) headersOf(HttpHeaders.ContentLength, body.size.toString()) else headersOf(),
            )
        }

    // https で、ホストを持つ URL だけを取る
    @Test
    fun acceptsOnlyHttpsUrlWithHost() {
        assertTrue(isDownloadableHttpsUrl(RELEASE_URL))
        assertTrue(isDownloadableHttpsUrl("HTTPS://example.com/cloudmarks.apk"))
        listOf(
            "http://example.com/cloudmarks-setup.exe",
            "https:///cloudmarks-setup.exe",
            "ftp://example.com/cloudmarks-setup.exe",
            "file:///C:/cloudmarks-setup.exe",
            "not a url at all",
            "",
        ).forEach { url -> assertFalse(isDownloadableHttpsUrl(url), url) }
    }

    // 中身を余さず書き写す
    @Test
    fun copiesAllBytes() {
        val source = ByteArray(300_000) { (it % 251).toByte() }

        val (copied, _) = copy(source, source.size.toLong())

        assertContentEquals(source, copied)
    }

    // 書き終えたときは、受け取った量と全体の大きさを必ず知らせる。小さい入力では、その 1 回だけ
    @Test
    fun reportsFinalProgressOnce() {
        val source = ByteArray(1_000)

        val (_, reports) = copy(source, source.size.toLong())

        assertEquals(listOf(1_000L to 1_000L), reports)
    }

    // 知らせる間隔より大きい入力では、途中の量も知らせる
    @Test
    fun reportsIntermediateProgressForLargeInput() {
        val source = ByteArray(3 * 1024 * 1024)

        val (_, reports) = copy(source, source.size.toLong())

        assertTrue(reports.size > 1, reports.toString())
        assertEquals(source.size.toLong() to source.size.toLong(), reports.last())
    }

    // 全体の大きさが分からなければ、0 をそのまま渡す
    @Test
    fun passesUnknownTotalThrough() {
        val (_, reports) = copy(ByteArray(1_000), total = 0)

        assertEquals(1_000L to 0L, reports.last())
    }

    // 上限を超えるところで書き写すのをやめる（際限なく送り続ける応答からディスクを守る）
    @Test
    fun stopsWritingBeyondLimit() {
        val output = ByteArrayOutputStream()

        assertFailsWith<IOException> {
            copyReportingProgress(ByteArrayInputStream(ByteArray(300_000)), output, 0, SMALL_LIMIT_BYTES) { _, _ -> }
        }

        assertTrue(output.size() <= SMALL_LIMIT_BYTES, "wrote ${output.size()} bytes")
    }

    // 全体の大きさが分かっていて、読み終えたときに受け取った量がそれより少なければ、途中で切れた応答として投げ、書き終えたことは知らせない
    @Test
    fun rejectsTruncatedInput() {
        val reports = mutableListOf<Pair<Long, Long>>()

        assertFailsWith<IOException> {
            copyReportingProgress(ByteArrayInputStream(ByteArray(500)), ByteArrayOutputStream(), 1_000, MAX_RELEASE_BYTES) { received, total ->
                reports += received to total
            }
        }

        assertTrue(reports.isEmpty(), reports.toString())
    }

    // 配布物を、無いディレクトリを作ってファイルに書き、受け取った量を知らせる
    @Test
    fun downloadsIntoFile() = runTest {
        val body = ByteArray(10_000) { (it % 7).toByte() }
        val reports = mutableListOf<Pair<Long, Long>>()

        HttpClient(engineResponding(body)).downloadToFile(RELEASE_URL, target) { received, total -> reports += received to total }

        assertContentEquals(body, target.readBytes())
        assertEquals(10_000L to 10_000L, reports.last())
    }

    // 全体の大きさが上限を超えると言う応答は、本体を受け取らずに断り、ファイルを残さない
    @Test
    fun rejectsAnnouncedOversizeResponse() = runTest {
        assertFailsWith<IOException> {
            HttpClient(engineResponding(ByteArray(300_000))).downloadToFile(RELEASE_URL, target, maxBytes = SMALL_LIMIT_BYTES)
        }

        assertFalse(target.exists())
    }

    // 受け取る途中で上限を超えたら、書きかけのファイルを残さない
    @Test
    fun removesPartialFileOnFailure() = runTest {
        assertFailsWith<IOException> {
            HttpClient(engineResponding(ByteArray(300_000), announcesLength = false))
                .downloadToFile(RELEASE_URL, target, maxBytes = SMALL_LIMIT_BYTES)
        }

        assertFalse(target.exists())
    }

    // 前にあったファイルは、失敗したら消え、書きかけと入れ替わったまま残らない
    @Test
    fun removesStaleFileOnFailure() = runTest {
        target.parent.toFile().mkdirs()
        target.toFile().writeText("stale")

        assertFailsWith<IOException> {
            HttpClient(engineResponding(ByteArray(300_000), announcesLength = false))
                .downloadToFile(RELEASE_URL, target, maxBytes = SMALL_LIMIT_BYTES)
        }

        assertFalse(target.exists())
    }

    // https でない URL には要求を 1 つも出さずに断る
    @Test
    fun refusesNonHttpsUrlWithoutRequest() = runTest {
        val engine = engineResponding(ByteArray(10))

        assertFailsWith<IOException> {
            HttpClient(engine).downloadToFile("http://example.com/cloudmarks-setup.exe", target)
        }

        assertTrue(engine.requestHistory.isEmpty())
        assertFalse(target.exists())
    }

    // 受け取っている最中に取り消されたら、書きかけのファイルを消してから取り消しを伝える
    @Test
    fun removesPartialFileOnCancellation() = runTest(timeout = CANCELLATION_TIMEOUT) {
        val body = ByteChannel(autoFlush = true)
        val engine = MockEngine { respond(content = body, status = HttpStatusCode.OK, headers = headersOf()) }
        val progressed = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) {
            HttpClient(engine).downloadToFile(RELEASE_URL, target) { _, _ -> progressed.complete(Unit) }
        }

        body.writeFully(ByteArray(PARTIAL_BODY_BYTES))
        progressed.await()
        assertTrue(target.exists(), "the download did not start writing")
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertFalse(target.exists(), "a partial download was left")
    }

    // 更新のクライアントでは、https から http への転送を、配布物でも小さいものでも追わず、要求を 1 回だけ出して投げる。ファイルは残さない
    @Test
    fun refusesHttpsDowngradeRedirect() = runTest {
        val engine = MockEngine {
            respond(
                content = "",
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, "http://objects.example.com/cloudmarks-setup.exe"),
            )
        }
        val client = HttpClient(engine) { configureUpdateClient() }

        assertFailsWith<IOException> { client.downloadToFile(RELEASE_URL, target) }
        assertEquals(1, engine.requestHistory.size)
        assertFalse(target.exists())

        assertFailsWith<IOException> { client.downloadBytes(RELEASE_URL) }
        assertEquals(2, engine.requestHistory.size)
        assertTrue(engine.requestHistory.all { it.url.protocol.name == "https" }, engine.requestHistory.map { it.url }.toString())
    }

    // HTTP の状態が成功でなければ投げ、ファイルを残さない
    @Test
    fun failsOnErrorStatus() = runTest {
        val engine = MockEngine { respond(content = "not found", status = HttpStatusCode.NotFound) }

        val failure = assertFailsWith<IOException> { HttpClient(engine).downloadToFile(RELEASE_URL, target) }

        assertTrue("404" in failure.message.orEmpty(), failure.message)
        assertFalse(target.exists())
    }

    // 小さいものは、上限までバイト列として受け取る
    @Test
    fun downloadsBytesWithinLimit() = runTest {
        val body = "signature".encodeToByteArray()

        assertContentEquals(body, HttpClient(engineResponding(body)).downloadBytes(RELEASE_URL, SMALL_LIMIT_BYTES))
    }

    // バイト列として受け取るときも、上限を超えれば、大きさを言っていてもいなくても断る
    @Test
    fun downloadBytesRejectsOversize() = runTest {
        listOf(true, false).forEach { announcesLength ->
            assertFailsWith<IOException>("announcesLength=$announcesLength") {
                HttpClient(engineResponding(ByteArray(SMALL_LIMIT_BYTES.toInt() + 1), announcesLength))
                    .downloadBytes(RELEASE_URL, SMALL_LIMIT_BYTES)
            }
        }
    }

    // 上限は、更新情報と署名が 64 KiB、配布物が 200 MiB
    @Test
    fun limitsAreSmallForUpdateInfoAndLargeForReleases() {
        assertEquals(64L * 1024, MAX_UPDATE_INFO_BYTES)
        assertEquals(200L * 1024 * 1024, MAX_RELEASE_BYTES)
    }
}
