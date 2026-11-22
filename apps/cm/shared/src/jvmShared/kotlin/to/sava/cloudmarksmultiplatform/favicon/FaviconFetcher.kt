package to.sava.cloudmarksmultiplatform.favicon

import androidx.compose.ui.graphics.ImageBitmap
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlin.time.Duration.Companion.seconds

/** 1 つのオリジンの favicon を取るのにかける時間の上限。超えたら、残りの取り先を打ち切る。 */
internal val FETCH_TIME_LIMIT = 10.seconds

private const val KIBIBYTE = 1024

/** 画像の応答を受け取る大きさの上限。1 MiB。超える応答は、取れなかったものとする。 */
internal const val MAX_IMAGE_BYTES = 1024 * KIBIBYTE

/** オリジンの HTML を読む大きさの上限。512 KiB。これより後ろは読まない。 */
internal const val MAX_HTML_BYTES = 512 * KIBIBYTE

/** オリジンの直下の favicon のパス。 */
private const val FAVICON_ICO_PATH = "/favicon.ico"

/** はてなの favicon の取り先。クエリの [HATENA_URL_PARAMETER] にサイトの URL を渡す。 */
private const val HATENA_FAVICON_URL = "https://cdn-ak.favicon.st-hatena.com/"

private const val HATENA_URL_PARAMETER = "url"

/** Google の favicon の取り先。クエリの [GOOGLE_DOMAIN_PARAMETER] にホストを、[GOOGLE_SIZE_PARAMETER] に大きさを渡す。 */
private const val GOOGLE_FAVICON_URL = "https://www.google.com/s2/favicons"

private const val GOOGLE_DOMAIN_PARAMETER = "domain"

private const val GOOGLE_SIZE_PARAMETER = "sz"

/** Google の取り先に求める画像の大きさ（画素）。 */
private const val GOOGLE_ICON_SIZE = 64

/** 1 つのオリジンの favicon を取った結果。 */
internal sealed interface FetchedFavicon {
    /**
     * 取れた。
     *
     * @property url 画像を取りに行った URL（転送の前）
     * @property bytes 応答の本体のまま
     * @property image [bytes] を読んだ画像
     * @property headers 画像の応答の、キャッシュの期限と、変わったかを問い合わせることに使うヘッダー
     */
    class Found(val url: String, val bytes: ByteArray, val image: ImageBitmap, val headers: CacheHeaders) : FetchedFavicon

    /**
     * 前に取った画像から変わっていない（前に取った画像の URL が `304` を返した）。
     *
     * @property headers `304` の応答の、キャッシュの期限と、変わったかを問い合わせることに使うヘッダー
     */
    data class NotModified(val headers: CacheHeaders) : FetchedFavicon

    /** HTTP の応答は 1 つ以上得られたが、どの取り先からも取れなかった。 */
    data object Missing : FetchedFavicon

    /**
     * どの取り先からも HTTP の応答が 1 つも得られなかった。接続の失敗、時間切れ、証明書の検証の失敗、HTTP として読めない応答などを含む。
     * 端末がオフラインとみなす。
     */
    data object Unreachable : FetchedFavicon
}

/**
 * 前に取った画像が変わったかを問い合わせる先と、問い合わせに付ける値。
 *
 * @property url 前に画像を取りに行った URL
 * @property eTag 前の画像の応答の `ETag`。あれば `If-None-Match` に付ける
 * @property lastModified 前の画像の応答の `Last-Modified`。あれば `If-Modified-Since` に付ける
 */
internal data class Revalidation(val url: String, val eTag: String?, val lastModified: String?)

/** 1 つの要求の結果。 */
private sealed interface Exchange {
    /** HTTP の応答が得られなかった。接続の失敗、時間切れ、HTTP として読めない応答、証明書の検証の失敗など。 */
    data object NoResponse : Exchange

    /**
     * HTTP の応答が得られた。
     *
     * @property finalUrl 転送後の最終の URL
     * @property body 読んだ本体。応答が成功（2xx）でないか、本体を読み終える前に失敗したか、読む側が断ったなら null
     */
    class Responded(val status: HttpStatusCode, val headers: Headers, val finalUrl: String, val body: ByteArray?) : Exchange {
        /** 本体を [body] にしたもの。 */
        fun withBody(body: ByteArray?): Responded = Responded(status, headers, finalUrl, body)
    }
}

/**
 * 1 つのオリジンの favicon を、決まった順に取り先を試して取る。
 *
 * 取り先は、上から試し、最初に取れたものを使う。取れたとは、HTTP の応答が成功（2xx）で、本体が空でなく、
 * [MAX_IMAGE_BYTES] を超えず、[decode] で画像として読めたこと。読めなければ（SVG など）次へ進む。
 * 1. 前に取った画像があれば（[Revalidation]）、その URL。`304` なら変わっていない（[FetchedFavicon.NotModified]）。
 *    前の画像をオリジンの外の取り先（4〜6）から取っていたなら、問い合わせずに 2 から始める。サイトが favicon を置いたら、そちらに移るため
 * 2. `<オリジン>/favicon.ico`
 * 3. `<オリジン>/` の HTML の、favicon を指す `<link>` の URL（[iconLinksOf]）。文書の順に試す。HTML は [MAX_HTML_BYTES] まで読み、
 *    相対の URL は転送後の最終の URL を基準に解決する。2 で HTTP の応答が得られなければ、サイトにつながらないので飛ばす
 * 4. `https://cdn-ak.favicon.st-hatena.com/?url=https://<ホスト[:ポート]>`
 * 5. `https://cdn-ak.favicon.st-hatena.com/?url=http://<ホスト[:ポート]>`
 * 6. `https://www.google.com/s2/favicons?domain=<ホスト>&sz=64`
 *
 * 4〜6 は、ホストをオリジンの外へ渡してよいとき（[FaviconOrigin.allowsExternalServices]）だけ試す。
 * 同じ URL は 1 回だけ試す。クエリの値は URL エンコードする。1 つのオリジンにかける時間は、全部で [FETCH_TIME_LIMIT] まで。
 *
 * どの取り先からも取れなければ、HTTP の応答が 1 つでも得られたなら [FetchedFavicon.Missing]、1 つも得られなければ
 * [FetchedFavicon.Unreachable]。1 つの取り先への要求と本体を読むことで起きた例外は、種類を問わず、その取り先から応答が得られなかった
 * ものとして次の取り先へ進む（サイトが無い、つながらない、時間切れ、証明書の検証の失敗、HTTP として読めない応答などはふつうに起こる）。
 * この取得が取り消されたときは投げ直す。画像を読むこと（[decode]）と HTML を読むことで起きた例外は、そのまま投げる。
 *
 * @param httpClient 取りに行くクライアント（[createFaviconHttpClient]）。転送を追うもの
 * @param decode バイト列を画像として読む（[decodeFavicon]）
 */
internal class FaviconFetcher(
    private val httpClient: HttpClient,
    private val decode: (ByteArray) -> ImageBitmap?,
) {
    /** [origin] の favicon を取る。前に取った画像があれば [revalidation] を渡し、まずそれが変わったかを問い合わせる。 */
    suspend fun fetch(origin: FaviconOrigin, revalidation: Revalidation? = null): FetchedFavicon {
        val attempt = Attempt()
        return withTimeoutOrNull(FETCH_TIME_LIMIT) { attempt.fetch(origin, revalidation) } ?: attempt.nothingFound()
    }

    /** 1 つのオリジンの favicon を取る 1 回分。HTTP の応答が得られたかと、試した URL を持つ。 */
    private inner class Attempt {
        /** HTTP の応答（ステータスの付いたもの）が 1 つでも得られたか。 */
        private var responded = false

        /** 試した画像の URL と、その URL から HTTP の応答が得られたか。 */
        private val tried = mutableMapOf<String, Boolean>()

        /** どの取り先からも取れなかったときの結果。 */
        fun nothingFound(): FetchedFavicon = if (responded) FetchedFavicon.Missing else FetchedFavicon.Unreachable

        suspend fun fetch(origin: FaviconOrigin, revalidation: Revalidation?): FetchedFavicon {
            revalidation?.takeUnless { isExternalServiceUrl(it.url) }?.let { imageAt(it.url, it) }?.let { return it }
            val faviconIco = "$origin$FAVICON_ICO_PATH"
            imageAt(faviconIco)?.let { return it }
            if (tried[faviconIco] == true) iconLinks(origin).forEach { url -> imageAt(url)?.let { return it } }
            if (origin.allowsExternalServices) externalServiceUrls(origin).forEach { url -> imageAt(url)?.let { return it } }
            return nothingFound()
        }

        /**
         * [url] の画像。取れなければ null。前に試した URL なら、要求を出さずに null。
         * [revalidation] を渡すと、変わったかの問い合わせにし、`304` なら [FetchedFavicon.NotModified] を返す。
         */
        private suspend fun imageAt(url: String, revalidation: Revalidation? = null): FetchedFavicon? {
            if (url in tried) return null
            val exchange = request(url, configure = { revalidation?.let { conditionalOn(it) } }) { it.imageBytes() }
            tried[url] = exchange is Exchange.Responded
            val response = exchange as? Exchange.Responded ?: return null
            if (revalidation != null && response.status == HttpStatusCode.NotModified) {
                return FetchedFavicon.NotModified(cacheHeadersOf(response.headers))
            }
            val bytes = response.body ?: return null
            val image = decode(bytes) ?: return null
            return FetchedFavicon.Found(url, bytes, image, cacheHeadersOf(response.headers))
        }

        /** [origin] の HTML の、favicon を指す `<link>` の URL。HTML を取れなければ空。 */
        private suspend fun iconLinks(origin: FaviconOrigin): List<String> {
            val exchange = request("$origin/") { it.bodyAsChannel().readBuffer(MAX_HTML_BYTES.toLong()).readByteArray() }
            val response = exchange as? Exchange.Responded ?: return emptyList()
            val html = response.body ?: return emptyList()
            return iconLinksOf(html.decodeToString(), response.finalUrl)
        }

        /**
         * [url] を GET し、応答のステータスとヘッダーを返す。応答が成功（2xx）なら、本体を [readBody] で読んで添える。
         * HTTP の応答が得られたら、それを覚える。
         *
         * 要求を出して本体を読むまでに起きた例外は、種類を問わず受ける。応答のステータスより前なら [Exchange.NoResponse]、後なら本体の無い
         * [Exchange.Responded] にする。`CancellationException` は、この取得が取り消されていれば投げ直し、取り消されていなければ
         * （ktor の中の、取り消しで閉じたものから来た例外）、ほかの例外と同じく扱う。
         */
        private suspend fun request(
            url: String,
            configure: HttpRequestBuilder.() -> Unit = {},
            readBody: suspend (HttpResponse) -> ByteArray?,
        ): Exchange {
            var head: Exchange.Responded? = null
            return try {
                httpClient.prepareGet(url, configure).execute { response ->
                    responded = true
                    val received = Exchange.Responded(response.status, response.headers, response.call.request.url.toString(), body = null)
                    head = received
                    if (response.status.isSuccess()) received.withBody(readBody(response)) else received
                }
            } catch (cancellation: CancellationException) {
                currentCoroutineContext().ensureActive()
                head ?: Exchange.NoResponse
            } catch (e: Exception) {
                // つながらない、時間切れ、証明書の検証の失敗、HTTP として読めない応答、読めない転送先などは、ふつうに起こるのでログに書かない
                head ?: Exchange.NoResponse
            }
        }
    }
}

/** 変わったかの問い合わせにするヘッダーを付ける。前の応答に `ETag` も `Last-Modified` も無ければ、何も付けない。 */
private fun HttpRequestBuilder.conditionalOn(revalidation: Revalidation) {
    revalidation.eTag?.let { header(HttpHeaders.IfNoneMatch, it) }
    revalidation.lastModified?.let { header(HttpHeaders.IfModifiedSince, it) }
}

/** 応答の本体。空か、[MAX_IMAGE_BYTES] を超えれば null。大きさが上限を超えると応答が言えば、本体を読まない。 */
private suspend fun HttpResponse.imageBytes(): ByteArray? {
    if ((contentLength() ?: 0L) > MAX_IMAGE_BYTES) return null
    return bodyAsChannel().readBuffer(MAX_IMAGE_BYTES + 1L).readByteArray().takeIf { it.isNotEmpty() && it.size <= MAX_IMAGE_BYTES }
}

/** オリジンの外の、favicon を返す取り先の URL。試す順に並べる。 */
private fun externalServiceUrls(origin: FaviconOrigin): List<String> =
    listOf(
        hatenaFaviconUrl("https://${origin.authority}"),
        hatenaFaviconUrl("http://${origin.authority}"),
        "$GOOGLE_FAVICON_URL?$GOOGLE_DOMAIN_PARAMETER=${origin.host.encodeURLParameter()}&$GOOGLE_SIZE_PARAMETER=$GOOGLE_ICON_SIZE",
    )

/** はてなの取り先の、サイト [site] の favicon の URL。 */
private fun hatenaFaviconUrl(site: String): String = "$HATENA_FAVICON_URL?$HATENA_URL_PARAMETER=${site.encodeURLParameter()}"

/** [url] が、オリジンの外の取り先（はてな・Google）のものか。 */
private fun isExternalServiceUrl(url: String): Boolean = url.startsWith(HATENA_FAVICON_URL) || url.startsWith(GOOGLE_FAVICON_URL)
