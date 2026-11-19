package to.sava.cloudmarksmultiplatform.update

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path

/** 取ってよい URL のスキーム。平文の http は、経路の途中で差し替えられても気づけないので取らない。 */
private const val ALLOWED_SCHEME = "https"

/** 書き写すときに一度に読む大きさ。 */
private const val COPY_BUFFER_BYTES = 64 * 1024

/** 受け取った量を知らせる間隔。1 回読むたびに知らせると、画面を描き直しすぎる。 */
private const val PROGRESS_NOTIFY_BYTES = 512L * 1024

private const val KIBIBYTE = 1024L

/** 更新情報と、その署名を受け取る大きさの上限。64 KiB。どちらも数百バイトなので、それより大きい応答は読まない。 */
internal const val MAX_UPDATE_INFO_BYTES: Long = 64 * KIBIBYTE

/**
 * 配布物を受け取る大きさの上限。200 MiB。デスクトップのインストーラ（約 64 MB）と APK に余裕を持たせた値で、
 * 際限なく送り続ける応答からディスクを守る。
 */
internal const val MAX_RELEASE_BYTES: Long = 200 * KIBIBYTE * KIBIBYTE

/** [url] が https で、ホストを持つか。 */
internal fun isDownloadableHttpsUrl(url: String): Boolean {
    val uri = try {
        URI(url)
    } catch (e: URISyntaxException) {
        return false
    }
    return uri.scheme?.lowercase() == ALLOWED_SCHEME && !uri.host.isNullOrEmpty()
}

/**
 * [url] の中身を [file] へ書きながら、受け取った量を [onProgress] へ知らせる。[file] の親のディレクトリが無ければ作る。
 * [file] が既にあれば書き換える。
 *
 * 受け取るのは [maxBytes] まで（[readHttpsResponse]）。途中で失敗したとき（取り消されたときを含む）は、書きかけの [file] を消してから
 * 投げ直す。消せなかったときは、その失敗を suppressed に添える。
 *
 * 受け取りながら書く。一度メモリに載せると、数十 MB の配布物がまるごと載るうえ、読み終えるまで進み具合を知らせられない。
 * 本体を読むところと書くところはブロックする。呼び出し側が I/O 用のスレッド（`Dispatchers.IO`）で呼ぶ。
 *
 * @param onProgress 受け取ったバイト数と全体のバイト数（分からなければ 0）を受け取る。間引いて呼び、書き終えたときは必ず呼ぶ
 * @throws IOException https でない、HTTP の状態が成功でない、大きさが上限を超える、途中で切れた、通信か書き込みに失敗した
 */
internal suspend fun HttpClient.downloadToFile(
    url: String,
    file: Path,
    maxBytes: Long = MAX_RELEASE_BYTES,
    onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
) {
    file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
    try {
        readHttpsResponse(url, maxBytes) { input, total ->
            Files.newOutputStream(file).buffered().use { output ->
                copyReportingProgress(input, output, total, maxBytes, onProgress)
            }
        }
    } catch (e: Exception) {
        discardPartialFile(file, e)
        throw e
    }
}

/**
 * [url] の中身を、[maxBytes] までのバイト列として受け取る。更新情報とその署名のような、小さいものに使う。
 * 本体を読むところはブロックする。呼び出し側が I/O 用のスレッド（`Dispatchers.IO`）で呼ぶ。
 *
 * @throws IOException https でない、HTTP の状態が成功でない、大きさが上限を超える、途中で切れた、通信に失敗した
 */
internal suspend fun HttpClient.downloadBytes(url: String, maxBytes: Long = MAX_UPDATE_INFO_BYTES): ByteArray =
    readHttpsResponse(url, maxBytes) { input, total ->
        ByteArrayOutputStream().also { output -> copyReportingProgress(input, output, total, maxBytes) { _, _ -> } }
            .toByteArray()
    }

/**
 * [url] を GET し、成功した応答の本体を [read] に渡す。https でない URL には要求を出さない。
 * 応答が全体の大きさ（Content-Length）を [maxBytes] より大きいと言えば、本体を読まずに投げる。
 *
 * @param read 本体と、全体のバイト数（分からなければ 0）を受け取る。ブロックして読んでよい
 * @throws IOException https でない、HTTP の状態が成功でない、全体の大きさが上限を超える
 */
private suspend fun <T> HttpClient.readHttpsResponse(
    url: String,
    maxBytes: Long,
    read: (input: InputStream, total: Long) -> T,
): T {
    if (!isDownloadableHttpsUrl(url)) throw IOException("https でない URL には要求を出さない")
    return prepareGet(url).execute { response ->
        if (!response.status.isSuccess()) throw IOException("HTTP ${response.status.value}")
        val total = response.contentLength() ?: 0L
        if (total > maxBytes) throw IOException("応答の大きさ $total バイトが、上限の $maxBytes バイトを超える")
        response.bodyAsChannel().toInputStream().use { input -> read(input, total) }
    }
}

/**
 * [input] を [output] へ書き写しながら、受け取った量を [onProgress] へ知らせる。
 *
 * 知らせるのは一定の量（[PROGRESS_NOTIFY_BYTES]）ごとに間引き、書き終えたときは必ず 1 回知らせる。[total] は全体のバイト数で、
 * 分からなければ 0 をそのまま渡す。受け取った量が [maxBytes] を超えるところで、書かずにやめて投げる。
 * [total] が分かっていて、読み終えたときに受け取った量がそれと違えば、途中で切れた応答として投げる（書き終えたことは知らせない）。
 *
 * @throws IOException 受け取った量が [maxBytes] を超える、[total] と違う、または読み書きに失敗した
 */
internal fun copyReportingProgress(
    input: InputStream,
    output: OutputStream,
    total: Long,
    maxBytes: Long,
    onProgress: (received: Long, total: Long) -> Unit,
) {
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    var received = 0L
    var notified = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (received + read > maxBytes) throw IOException("受け取った量が、上限の $maxBytes バイトを超える")
        output.write(buffer, 0, read)
        received += read
        if (received - notified >= PROGRESS_NOTIFY_BYTES) {
            notified = received
            onProgress(received, total)
        }
    }
    if (total > 0 && received != total) throw IOException("受け取った量 $received バイトが、応答の大きさ $total バイトと違う")
    onProgress(received, total)
}

/** 書きかけの [file] を消す。消せなければ、その失敗を [cause] に添える。 */
private fun discardPartialFile(file: Path, cause: Exception) {
    try {
        Files.deleteIfExists(file)
    } catch (e: IOException) {
        cause.addSuppressed(e)
    }
}
