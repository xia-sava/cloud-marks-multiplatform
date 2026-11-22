package to.sava.cloudmarksmultiplatform.favicon

import androidx.compose.ui.graphics.ImageBitmap
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** 同時に favicon を取りに行くオリジンの数の上限。 */
private const val MAX_CONCURRENT_FETCHES = 4

/**
 * どの取り先からも HTTP の応答が得られなかったか、取得が予期しない例外で止まったオリジンを、取り直さずにおく長さ。
 * これを過ぎてから [FaviconStore.faviconOf] を呼ぶと取り直す。
 */
internal val RETRY_AFTER_FAILURE = 5.minutes

/**
 * 項目の URL のオリジンごとに favicon を取りに行き、端末のキャッシュ（[FaviconCache]）に置いて、状態を出す。
 *
 * 初めて [faviconOf] を呼んだオリジンは、まずキャッシュを読む。キャッシュがあれば、その状態（取れた画像か、無し）を出す。
 * キャッシュが無いか古ければ、取りに行く（[FaviconFetcher]）。古いキャッシュがあるあいだは、取り直しが済むまでその状態を出しておく。
 * 取れたものは [foundExpiry]、無しは [MISSING_LIFETIME] たったら古いとみなし、次に [faviconOf] を呼んだときに取り直す。
 * 取れた画像を取り直すときは、まず前に取った URL へ、変わったかを問い合わせる（オリジンの外の取り先から取った画像は問い合わせない）。
 * 変わっていなければ画像はそのままにし、期限を延ばしてキャッシュのファイルを書き直す。画像のバイト列はメモリに持たないので、書き直すときは
 * キャッシュのファイルから読み直す。キャッシュの画像を読めなければ、キャッシュが無いものとして取り直す。
 *
 * どの取り先からも HTTP の応答が得られなかったときは、端末がオフラインとみなし、キャッシュに書かない。そのときは古いキャッシュが
 * あればその状態を、無ければ [Favicon.Missing] を出し、[RETRY_AFTER_FAILURE] たってから [faviconOf] を呼ぶまで取り直さない
 * （このことはメモリの中だけで持つ）。HTTP の応答が得られて、それでも取れなかったときは、無しとしてキャッシュに書く。
 *
 * 取りに行くのは同時に [MAX_CONCURRENT_FETCHES] オリジンまで。1 つのオリジンにかける時間の上限（[FETCH_TIME_LIMIT]）は、取りに行き
 * 始めてから数え、順番を待つ時間を含まない。同じオリジンは、取りに行っている最中に [faviconOf] を呼んでも、重ねて取りに行かない。
 * キャッシュの読み書き、取得、画像を読むことは [ioDispatcher] で走らせ、[faviconOf] は呼んだスレッドをブロックしない。
 *
 * 取れなかったこと（サイトが無い・`404`・時間切れなど）はログに書かない。キャッシュのファイルの I/O の失敗は、キャッシュが無いものとして
 * 続け、[log] に書く（[FaviconCache]）。取得が予期しない例外（画像を読むところの不具合など）で止まったら、例外の種類を [log] に書き、
 * HTTP の応答が得られなかったときと同じく扱う。ログには項目の URL を書かない。
 *
 * 使い終えたら [close] を呼ぶ。
 *
 * @param cacheDirectory ローカルキャッシュのルートディレクトリ。favicon のキャッシュは、この下の [FAVICON_DIRECTORY_NAME] に置く
 * @param httpClient favicon を取るクライアント（[createFaviconHttpClient]）。[close] で閉じる
 * @param scope 取得を走らせるスコープの親。このストアの取得は、子のスコープで走らせる。[scope] の取り消しは渡した側が行う
 * @param log キャッシュのファイルの I/O の失敗と、取得が予期しない例外で止まったことを書く
 * @param clock キャッシュの期限を決め、古いかと、取り直さずにおく長さを過ぎたかを判断する時計
 * @param ioDispatcher キャッシュの読み書き、取得、画像を読むことに使う
 * @param decode バイト列を画像として読む（[decodeFavicon]）
 */
class FaviconStore internal constructor(
    cacheDirectory: Path,
    private val httpClient: HttpClient,
    scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val clock: Clock = Clock.System,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val decode: (ByteArray) -> ImageBitmap? = ::decodeFavicon,
) : FaviconSource, AutoCloseable {
    private val cache = FaviconCache(cacheDirectory, log)

    private val fetcher = FaviconFetcher(httpClient, decode)

    /** 取りに行くことを、同時に [MAX_CONCURRENT_FETCHES] オリジンまでにする。キャッシュを読むことは縛らない。 */
    private val fetchPermits = Semaphore(MAX_CONCURRENT_FETCHES)

    /** [faviconOf] を呼んだことのあるオリジンと、その favicon。 */
    private val origins = ConcurrentHashMap<FaviconOrigin, OriginFavicon>()

    /** 取得が予期しない例外で止まったことを書く。例外のメッセージは URL を含みうるので、種類だけを書く。取り消しでは呼ばれない。 */
    private val unexpectedFailure = CoroutineExceptionHandler { _, e ->
        log("favicon の取得が、予期しない例外で止まった: ${e::class.simpleName}")
    }

    /** 取得を走らせるスコープ。[close] で取り消す。 */
    private val storeScope =
        CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + ioDispatcher + unexpectedFailure)

    override fun faviconOf(url: String): StateFlow<Favicon> {
        val origin = faviconOriginOf(url) ?: return NO_FAVICON
        val favicon = origins.getOrPut(origin) { OriginFavicon() }
        if (favicon.claimRefresh(clock.now())) startRefresh(origin, favicon)
        return favicon.state
    }

    /** 取得を取り消し、[httpClient] を閉じる。 */
    override fun close() {
        storeScope.cancel()
        httpClient.close()
    }

    /** [origin] の favicon（[favicon]）を、キャッシュから読み、無いか古ければ取りに行く。 */
    private fun startRefresh(origin: FaviconOrigin, favicon: OriginFavicon) {
        storeScope.launch { refresh(origin, favicon) }.invokeOnCompletion { cause ->
            if (cause != null && cause !is CancellationException) favicon.holdOff(clock.now() + RETRY_AFTER_FAILURE)
            favicon.release()
        }
    }

    private suspend fun refresh(origin: FaviconOrigin, favicon: OriginFavicon) {
        val current = favicon.known ?: readCache(origin)?.also(favicon::show)
        if (current != null && current.isFresh(clock.now())) return
        val fetched = fetchPermits.withPermit { fetcher.fetch(origin, current?.source) }
        val now = clock.now()
        when (fetched) {
            is FetchedFavicon.Found -> {
                val record = CachedFavicon(
                    fetchedAt = now,
                    expiresAt = foundExpiry(now, fetched.headers),
                    image = CachedImage(fetched.url, fetched.headers.eTag, fetched.headers.lastModified, fetched.bytes),
                )
                favicon.show(KnownFavicon.of(record, fetched.image))
                cache.write(origin, record)
            }
            is FetchedFavicon.NotModified -> {
                val previous = checkNotNull(current) { "変わったかを問い合わせるのは、取れた画像のキャッシュがあるときだけ" }
                val source = checkNotNull(previous.source) { "変わったかを問い合わせるのは、取れた画像のキャッシュがあるときだけ" }
                val revalidated = Revalidation(source.url, fetched.headers.eTag ?: source.eTag, fetched.headers.lastModified ?: source.lastModified)
                val expiresAt = foundExpiry(now, fetched.headers)
                favicon.show(KnownFavicon(expiresAt, revalidated, previous.image))
                rewriteCachedImage(origin, now, expiresAt, revalidated)
            }
            FetchedFavicon.Missing -> {
                val record = CachedFavicon(now, now + MISSING_LIFETIME, image = null)
                favicon.show(KnownFavicon.of(record, image = null))
                cache.write(origin, record)
            }
            FetchedFavicon.Unreachable -> favicon.holdOff(now + RETRY_AFTER_FAILURE)
        }
    }

    /**
     * 変わっていなかった画像のキャッシュを、期限 [expiresAt] と、問い合わせた時刻 [now] と、[source] の URL・`ETag`・`Last-Modified` で
     * 書き直す。画像のバイト列はキャッシュのファイルから読み直す。ファイルに画像が無くなっているか、ファイルの画像を取った URL が
     * 問い合わせた URL（[source] の URL）と違えば書かない。
     */
    private fun rewriteCachedImage(origin: FaviconOrigin, now: Instant, expiresAt: Instant, source: Revalidation) {
        val cached = cache.read(origin)?.image?.takeIf { it.url == source.url } ?: return
        cache.write(origin, CachedFavicon(now, expiresAt, CachedImage(source.url, source.eTag, source.lastModified, cached.bytes)))
    }

    /** [origin] のキャッシュ。無いか、読めないか、画像として読めなければ null。 */
    private fun readCache(origin: FaviconOrigin): KnownFavicon? {
        val record = cache.read(origin) ?: return null
        val image = record.image?.let { decode(it.bytes) ?: return null }
        return KnownFavicon.of(record, image)
    }
}

/**
 * favicon を取るストアを作る。HTTP クライアントは CIO で作り、ストアの [FaviconStore.close] で閉じる。
 *
 * @param cacheDirectory ローカルキャッシュのルートディレクトリ（[to.sava.cloudmarksmultiplatform.AppFiles.cacheDirectory]）
 * @param scope 取得を走らせるスコープの親。取り消しは呼んだ側が行う
 * @param log キャッシュのファイルの I/O の失敗と、取得が予期しない例外で止まったことを書く
 */
fun createFaviconStore(cacheDirectory: Path, scope: CoroutineScope, log: (String) -> Unit): FaviconStore =
    FaviconStore(cacheDirectory, createFaviconHttpClient(), scope, log)

/**
 * キャッシュか取得で分かった、1 つのオリジンの favicon。画像のバイト列は持たない。
 *
 * @property expiresAt これより後は古いとみなして取り直す時刻
 * @property source 画像を取った URL と、その応答の `ETag`・`Last-Modified`。無しなら null
 * @property image 読んだ画像。無しなら null
 */
private class KnownFavicon(val expiresAt: Instant, val source: Revalidation?, val image: ImageBitmap?) {
    /** 画面に出す状態。 */
    val state: Favicon get() = image?.let(Favicon::Found) ?: Favicon.Missing

    /** [now] の時点で、まだ古くないか。 */
    fun isFresh(now: Instant): Boolean = now < expiresAt

    companion object {
        /** キャッシュの中身 [record] と、その画像を読んだ [image] から作る。 */
        fun of(record: CachedFavicon, image: ImageBitmap?): KnownFavicon =
            KnownFavicon(record.expiresAt, record.image?.let { Revalidation(it.url, it.eTag, it.lastModified) }, image)
    }
}

/**
 * 1 つのオリジンの favicon の状態と、取りに行っているか。[faviconOf][FaviconStore.faviconOf] を呼ぶスレッドと、取得を走らせるスレッドの
 * 両方から触る。
 */
private class OriginFavicon {
    private val lock = Any()

    private val mutableState = MutableStateFlow<Favicon>(Favicon.Loading)

    /** 画面に出す状態。 */
    val state: StateFlow<Favicon> = mutableState.asStateFlow()

    private var knownFavicon: KnownFavicon? = null

    /** キャッシュを読むか取りに行っている最中か。 */
    private var refreshing = false

    /** 取れなかったので取り直さずにおく、その終わりの時刻。取り直してよければ null。 */
    private var heldOffUntil: Instant? = null

    /** キャッシュか取得で分かった favicon。まだ分かっていなければ null。 */
    val known: KnownFavicon? get() = synchronized(lock) { knownFavicon }

    /**
     * [now] の時点で取りに行くなら、取りに行っている最中にして true を返す。最中か、取り直さずにおくあいだか、分かったものがまだ古くなければ
     * false。
     */
    fun claimRefresh(now: Instant): Boolean =
        synchronized(lock) {
            if (refreshing || heldOffUntil?.let { now < it } == true || knownFavicon?.isFresh(now) == true) return false
            heldOffUntil = null
            refreshing = true
            true
        }

    /** 取りに行っている最中をやめる。 */
    fun release() {
        synchronized(lock) { refreshing = false }
    }

    /** 分かった favicon を [favicon] にし、その状態を出す。 */
    fun show(favicon: KnownFavicon) {
        synchronized(lock) {
            knownFavicon = favicon
            mutableState.value = favicon.state
        }
    }

    /** [until] まで取り直さずにおくことにし、分かっている favicon があればその状態を、無ければ [Favicon.Missing] を出す。 */
    fun holdOff(until: Instant) {
        synchronized(lock) {
            heldOffUntil = until
            mutableState.value = knownFavicon?.state ?: Favicon.Missing
        }
    }
}
