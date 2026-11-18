package to.sava.cloudmarksmultiplatform.remote

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.DeleteObjectRequest
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.ListObjectVersionsRequest
import aws.sdk.kotlin.services.s3.model.ListObjectVersionsResponse
import aws.sdk.kotlin.services.s3.model.ListObjectsV2Request
import aws.sdk.kotlin.services.s3.model.ListObjectsV2Response
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.sdk.kotlin.services.s3.withConfig
import aws.smithy.kotlin.runtime.SdkBaseException
import aws.smithy.kotlin.runtime.client.ProtocolResponseInterceptorContext
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.toByteArray
import aws.smithy.kotlin.runtime.http.interceptors.HttpInterceptor
import aws.smithy.kotlin.runtime.http.isSuccess
import aws.smithy.kotlin.runtime.http.request.HttpRequest
import aws.smithy.kotlin.runtime.http.response.HttpResponse
import aws.smithy.kotlin.runtime.time.epochMilliseconds
import aws.smithy.kotlin.runtime.time.Instant as SdkInstant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import to.sava.cloudmarksmultiplatform.storage.normalizePrefix
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

private const val MISSING_ETAG = "応答に ETag が無い"
private const val MISSING_DATE = "一覧の応答に Date が無いか、読めない"
private const val MISSING_KEY = "一覧のオブジェクトにキーが無い"
private const val MISSING_LAST_MODIFIED = "一覧のオブジェクトに LastModified が無い"
private const val MISSING_CONTINUATION_TOKEN = "一覧が切り詰められたのに継続トークンが無い"
private const val MISSING_VERSION_ID = "バージョンの一覧のエントリに VersionId が無い"
private const val MISSING_KEY_MARKER = "バージョンの一覧が切り詰められたのに次のキーのマーカーが無い"
private const val MISSING_VERSION_ID_MARKER = "バージョンの一覧が切り詰められたのに次のバージョンのマーカーが無い"
private const val DATE_HEADER = "Date"

/**
 * AWS SDK for Kotlin による [ObjectStore]。
 *
 * 接続先と認証情報は引数だけから取り、プロファイルや環境変数は読まない。
 * SDK は `5xx` とタイムアウトを自分で再送し、それでも失敗したものを返す。
 * 呼び出し元のキャンセルはそのまま伝える。呼び出し元がキャンセルされていないのに SDK の中から届いた
 * `CancellationException`（SDK の中のタイムアウトなど）は、結果が不明とする。
 * 使い終えたら [close] で SDK のクライアントを閉じる。
 *
 * @param maxKeys 一覧の 1 ページに入れる数の上限。オブジェクトの一覧ではキーの数、バージョンの一覧ではバージョンと削除マーカーを合わせたエントリの数。
 * null なら SDK（S3）の既定のまま。ページングのテストが小さな値で使う
 */
class S3ObjectStore internal constructor(
    region: String,
    accessKeyId: String,
    secretAccessKey: String,
    private val bucket: String,
    prefix: String,
    private val maxKeys: Int?,
) : ObjectStore, AutoCloseable {
    constructor(
        region: String,
        accessKeyId: String,
        secretAccessKey: String,
        bucket: String,
        prefix: String,
    ) : this(region, accessKeyId, secretAccessKey, bucket, prefix, maxKeys = null)

    override val prefix: String = normalizePrefix(prefix)

    private val client = S3Client {
        this.region = region
        credentialsProvider = StaticCredentialsProvider {
            this.accessKeyId = accessKeyId
            this.secretAccessKey = secretAccessKey
        }
    }

    override suspend fun get(key: String, ifNoneMatch: String?): GetObjectResult =
        getObject(key, ifNoneMatch = ifNoneMatch, versionId = null)

    override suspend fun getVersion(key: String, versionId: String): GetObjectResult =
        getObject(key, ifNoneMatch = null, versionId = versionId)

    private suspend fun getObject(key: String, ifNoneMatch: String?, versionId: String?): GetObjectResult {
        val request = GetObjectRequest {
            bucket = this@S3ObjectStore.bucket
            this.key = key
            this.ifNoneMatch = ifNoneMatch
            this.versionId = versionId
        }
        return try {
            client.getObject(request) { response ->
                response.eTag
                    ?.let { eTag -> GetObjectResult.Found(response.body?.toByteArray() ?: ByteArray(0), eTag) }
                    ?: GetObjectResult.Failed(MISSING_ETAG)
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            GetObjectResult.Unknown(describeFailure(e))
        } catch (e: SdkBaseException) {
            getResultOf(e)
        } catch (e: IOException) {
            GetObjectResult.Unknown(describeFailure(e))
        }
    }

    override suspend fun put(
        key: String,
        body: ByteArray,
        contentType: String,
        condition: PutCondition,
    ): PutObjectResult {
        val request = PutObjectRequest {
            bucket = this@S3ObjectStore.bucket
            this.key = key
            this.body = ByteStream.fromBytes(body)
            this.contentType = contentType
            when (condition) {
                is PutCondition.IfMatch -> ifMatch = condition.eTag
                PutCondition.IfNoneMatchAny -> ifNoneMatch = "*"
            }
        }
        return try {
            // 書けても ETag が無ければ次の書き込みの条件に使えないため、結果が不明とする。呼び出し側が読み直して確かめる
            client.putObject(request).eTag
                ?.let { PutObjectResult.Success(it) }
                ?: PutObjectResult.Unknown(MISSING_ETAG)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            PutObjectResult.Unknown(describeFailure(e))
        } catch (e: SdkBaseException) {
            putResultOf(e)
        } catch (e: IOException) {
            PutObjectResult.Unknown(describeFailure(e))
        }
    }

    /**
     * 一覧の応答の `Date` は SDK の応答の型に出ないため、この一覧のあいだだけ付ける interceptor で、成功した応答のヘッダから取る。
     * interceptor を付けたクライアントは設定だけを替えた複製で、通信のリソースは元のクライアントと共有する。
     * 複製は使い終えたら閉じる（リソースは、共有するすべてのクライアントが閉じたときに閉じる）。
     */
    override suspend fun list(keyPrefix: String): ListObjectsResult {
        val dateCapture = ResponseDateCapture()
        return client.withConfig { interceptors.add(dateCapture) }.use { listing ->
            listAllPages { token -> listing.readPage(keyPrefix, token, dateCapture) }
        }
    }

    private suspend fun S3Client.readPage(keyPrefix: String, token: String?, dateCapture: ResponseDateCapture): ListPageResult {
        val request = ListObjectsV2Request {
            bucket = this@S3ObjectStore.bucket
            prefix = keyPrefix
            continuationToken = token
            maxKeys = this@S3ObjectStore.maxKeys
        }
        dateCapture.reset()
        return try {
            listPageOf(listObjectsV2(request), dateCapture.date())
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            ListPageResult.Stopped(ListObjectsResult.Unknown(describeFailure(e)))
        } catch (e: SdkBaseException) {
            ListPageResult.Stopped(listResultOf(e))
        } catch (e: IOException) {
            ListPageResult.Stopped(ListObjectsResult.Unknown(describeFailure(e)))
        }
    }

    override suspend fun listVersions(key: String): ListVersionsResult =
        listAllVersionPages { token -> client.readVersionPage(key, token) }

    private suspend fun S3Client.readVersionPage(key: String, token: VersionPageToken?): VersionPageResult {
        val request = ListObjectVersionsRequest {
            bucket = this@S3ObjectStore.bucket
            prefix = key
            keyMarker = token?.keyMarker
            versionIdMarker = token?.versionIdMarker
            maxKeys = this@S3ObjectStore.maxKeys
        }
        return try {
            versionPageOf(listObjectVersions(request), key)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            VersionPageResult.Stopped(ListVersionsResult.Unknown(describeFailure(e)))
        } catch (e: SdkBaseException) {
            VersionPageResult.Stopped(listVersionsResultOf(e))
        } catch (e: IOException) {
            VersionPageResult.Stopped(ListVersionsResult.Unknown(describeFailure(e)))
        }
    }

    override suspend fun delete(key: String): DeleteObjectResult {
        val request = DeleteObjectRequest {
            bucket = this@S3ObjectStore.bucket
            this.key = key
        }
        return try {
            client.deleteObject(request)
            DeleteObjectResult.Success
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            DeleteObjectResult.Unknown(describeFailure(e))
        } catch (e: SdkBaseException) {
            deleteResultOf(e)
        } catch (e: IOException) {
            DeleteObjectResult.Unknown(describeFailure(e))
        }
    }

    override fun close() {
        client.close()
    }
}

/**
 * 一覧の 1 ページの応答を [ListPageResult] にする。
 *
 * 切り詰められた（`IsTruncated`）のに継続トークンが無い応答、`Date` が無いか読めない応答、
 * キーか `LastModified` の無いオブジェクトを含む応答は、最後まで読めたと言えないので失敗にする。
 */
internal fun listPageOf(response: ListObjectsV2Response, serverTime: Instant?): ListPageResult {
    val objects = response.contents.orEmpty().map { entry ->
        val key = entry.key ?: return ListPageResult.Stopped(ListObjectsResult.Failed(MISSING_KEY))
        val lastModified = entry.lastModified ?: return ListPageResult.Stopped(ListObjectsResult.Failed(MISSING_LAST_MODIFIED))
        ListedObject(key, Instant.fromEpochMilliseconds(lastModified.epochMilliseconds))
    }
    val next = response.nextContinuationToken.takeIf { response.isTruncated == true }
    return when {
        serverTime == null -> ListPageResult.Stopped(ListObjectsResult.Failed(MISSING_DATE))
        response.isTruncated == true && next == null -> ListPageResult.Stopped(ListObjectsResult.Failed(MISSING_CONTINUATION_TOKEN))
        else -> ListPageResult.Page(objects, next, serverTime)
    }
}

/** [versionPageOf] が、バージョンと削除マーカーを同じ形で扱うための、応答のエントリの写し。 */
private class VersionEntry(
    val key: String?,
    val versionId: String?,
    val lastModified: SdkInstant?,
    val isLatest: Boolean?,
    val isDeleteMarker: Boolean,
)

/**
 * バージョンの一覧の 1 ページの応答を [VersionPageResult] にする。
 *
 * ListObjectVersions はキーではなくプレフィックスで絞るので、キーが [key] に一致するバージョンと削除マーカーだけを残す。
 * キーの無いエントリ、対象のキーのエントリに `VersionId` か `LastModified` が無い応答、
 * 切り詰められたのに次のキーのマーカーが無い応答、次のキーのマーカーが [key] なのに次のバージョンのマーカーが無い応答は、
 * 最後まで読めたと言えないので失敗にする。
 * 次のキーのマーカーが [key] でなければ、対象のキーは読み終えているので、そこで最後のページとする。
 * `IsLatest` が無ければ、いまのバージョンではないものとする。
 */
internal fun versionPageOf(response: ListObjectVersionsResponse, key: String): VersionPageResult {
    val entries = response.versions.orEmpty().map { VersionEntry(it.key, it.versionId, it.lastModified, it.isLatest, isDeleteMarker = false) } +
        response.deleteMarkers.orEmpty().map { VersionEntry(it.key, it.versionId, it.lastModified, it.isLatest, isDeleteMarker = true) }
    val versions = entries.mapNotNull { entry ->
        val entryKey = entry.key ?: return VersionPageResult.Stopped(ListVersionsResult.Failed(MISSING_KEY))
        if (entryKey != key) return@mapNotNull null
        val versionId = entry.versionId ?: return VersionPageResult.Stopped(ListVersionsResult.Failed(MISSING_VERSION_ID))
        val lastModified = entry.lastModified ?: return VersionPageResult.Stopped(ListVersionsResult.Failed(MISSING_LAST_MODIFIED))
        ListedVersion(versionId, Instant.fromEpochMilliseconds(lastModified.epochMilliseconds), entry.isLatest == true, entry.isDeleteMarker)
    }
    if (response.isTruncated != true) return VersionPageResult.Page(versions, next = null)
    val nextKeyMarker = response.nextKeyMarker
        ?: return VersionPageResult.Stopped(ListVersionsResult.Failed(MISSING_KEY_MARKER))
    if (nextKeyMarker != key) return VersionPageResult.Page(versions, next = null)
    val nextVersionIdMarker = response.nextVersionIdMarker
        ?: return VersionPageResult.Stopped(ListVersionsResult.Failed(MISSING_VERSION_ID_MARKER))
    return VersionPageResult.Page(versions, VersionPageToken(nextKeyMarker, nextVersionIdMarker))
}

/**
 * 成功した応答の `Date` ヘッダを保持する interceptor。1 つのリクエストのあいだに応答を複数受けたとき（SDK の再送）は、最後のもの。
 */
private class ResponseDateCapture : HttpInterceptor {
    private val header = AtomicReference<String?>(null)

    /** 保持した値を捨てる。リクエストの前に呼ぶ。 */
    fun reset() {
        header.set(null)
    }

    /** 保持した `Date` を読む。無いか、読めなければ null。 */
    fun date(): Instant? = header.get()?.let(::parseHttpDate)

    override fun readAfterTransmit(context: ProtocolResponseInterceptorContext<Any, HttpRequest, HttpResponse>) {
        context.protocolResponse
            .takeIf { it.status.isSuccess() }
            ?.headers?.get(DATE_HEADER)
            ?.let(header::set)
    }
}

/** HTTP の日時（RFC 9110 の IMF-fixdate。例：`Mon, 16 Nov 2026 12:34:56 GMT`）を読む。読めなければ null。 */
internal fun parseHttpDate(value: String): Instant? =
    try {
        Instant.fromEpochMilliseconds(ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli())
    } catch (_: DateTimeParseException) {
        null
    }
