package to.sava.cloudmarksmultiplatform.remote

import aws.smithy.kotlin.runtime.SdkBaseException
import aws.smithy.kotlin.runtime.ServiceException
import aws.smithy.kotlin.runtime.http.HttpException
import aws.smithy.kotlin.runtime.http.operation.ClientTimeoutException
import aws.smithy.kotlin.runtime.http.response.HttpResponse
import aws.smithy.kotlin.runtime.retries.RetryException

private const val NOT_MODIFIED = 304
private const val FORBIDDEN = 403
private const val NOT_FOUND = 404
private const val CONFLICT = 409
private const val PRECONDITION_FAILED = 412
private val SERVER_ERROR = 500..599

/**
 * S3 が、リクエストを時間内に受け取りきれなかったときのエラーコード。`400` で返るが、SDK はこれを一時的な失敗として再送する。
 * 再送してもなお返ったものは、通信の失敗と同じく結果が不明とする。
 */
private const val REQUEST_TIMEOUT_CODE = "RequestTimeout"

/**
 * GET で SDK が投げた例外を、結果にする。
 *
 * S3 が返した応答は HTTP の状態で振り分ける。`5xx` と、エラーコードが `RequestTimeout` のものと、状態の取れない応答と、
 * 通信の失敗・タイムアウトは、結果が不明とする。
 */
internal fun getResultOf(e: SdkBaseException): GetObjectResult =
    when (e.httpStatus()) {
        NOT_MODIFIED -> GetObjectResult.NotModified
        NOT_FOUND -> GetObjectResult.NotFound
        FORBIDDEN -> GetObjectResult.Forbidden
        else -> if (e.isIndeterminate()) {
            GetObjectResult.Unknown(describeFailure(e))
        } else {
            GetObjectResult.Failed(describeFailure(e))
        }
    }

/**
 * PUT で SDK が投げた例外を、結果にする。
 *
 * S3 が返した応答は HTTP の状態で振り分け、上のどれでもない応答は書いていないとする。ただし `5xx` と、
 * エラーコードが `RequestTimeout` のものと、状態の取れない応答は、書けたかどうかが分からないとする。
 * S3 の応答でない失敗は、通信の失敗・タイムアウトも種類を知らないものも、書けたかどうかが分からないとする。
 */
internal fun putResultOf(e: SdkBaseException): PutObjectResult =
    when (e.httpStatus()) {
        PRECONDITION_FAILED -> PutObjectResult.PreconditionFailed
        CONFLICT -> PutObjectResult.Conflict
        NOT_FOUND -> PutObjectResult.NotFound
        FORBIDDEN -> PutObjectResult.Forbidden
        else -> if (e is ServiceException && !e.isIndeterminate()) {
            PutObjectResult.Failed(describeFailure(e))
        } else {
            PutObjectResult.Unknown(describeFailure(e))
        }
    }

/**
 * 一覧で SDK が投げた例外を、結果にする。
 *
 * S3 が返した応答は HTTP の状態で振り分ける。`5xx` と、エラーコードが `RequestTimeout` のものと、状態の取れない応答と、
 * 通信の失敗・タイムアウトは、結果が不明とする。
 */
internal fun listResultOf(e: SdkBaseException): ListObjectsResult =
    when {
        e.httpStatus() == FORBIDDEN -> ListObjectsResult.Forbidden
        e.isIndeterminate() -> ListObjectsResult.Unknown(describeFailure(e))
        else -> ListObjectsResult.Failed(describeFailure(e))
    }

/**
 * バージョンの一覧で SDK が投げた例外を、結果にする。振り分けは [listResultOf] と同じ。
 */
internal fun listVersionsResultOf(e: SdkBaseException): ListVersionsResult =
    when {
        e.httpStatus() == FORBIDDEN -> ListVersionsResult.Forbidden
        e.isIndeterminate() -> ListVersionsResult.Unknown(describeFailure(e))
        else -> ListVersionsResult.Failed(describeFailure(e))
    }

/**
 * DELETE で SDK が投げた例外を、結果にする。
 *
 * S3 が返した応答は HTTP の状態で振り分け、`403` 以外の応答は消していないとする。ただし `5xx` と、
 * エラーコードが `RequestTimeout` のものと、状態の取れない応答は、消せたかどうかが分からないとする。
 * S3 の応答でない失敗は、通信の失敗・タイムアウトも種類を知らないものも、消せたかどうかが分からないとする。
 */
internal fun deleteResultOf(e: SdkBaseException): DeleteObjectResult =
    when {
        e.httpStatus() == FORBIDDEN -> DeleteObjectResult.Forbidden
        e is ServiceException && !e.isIndeterminate() -> DeleteObjectResult.Failed(describeFailure(e))
        else -> DeleteObjectResult.Unknown(describeFailure(e))
    }

/**
 * 失敗を、後から調べるための短い文にする。
 *
 * 例外の種類、HTTP の状態、S3 のエラーコード、通信の失敗の種類だけを使う。例外のメッセージは、接続先やリクエストの中身を
 * 含みうるため使わない。
 */
internal fun describeFailure(e: Exception): String =
    listOfNotNull(
        e::class.simpleName,
        (e as? SdkBaseException)?.httpStatus()?.let { "HTTP $it" },
        (e as? ServiceException)?.sdkErrorMetadata?.errorCode,
        (e as? HttpException)?.errorCode?.name,
    ).joinToString(" ")

/** S3 が返した応答の HTTP の状態。応答を受けていない失敗（通信の失敗など）では null。 */
private fun SdkBaseException.httpStatus(): Int? =
    ((this as? ServiceException)?.sdkErrorMetadata?.protocolResponse as? HttpResponse)?.status?.value

/**
 * 結果が不明な失敗か。S3 の応答なら `5xx`、エラーコードが `RequestTimeout`、または状態が取れないもの。
 * そうでなければ通信の失敗・タイムアウト。
 */
private fun SdkBaseException.isIndeterminate(): Boolean =
    when (this) {
        is ServiceException ->
            httpStatus().let { it == null || it in SERVER_ERROR } || sdkErrorMetadata.errorCode == REQUEST_TIMEOUT_CODE
        is HttpException, is ClientTimeoutException, is RetryException -> true
        else -> false
    }
