package to.sava.cloudmarksmultiplatform.remote

import aws.sdk.kotlin.services.s3.model.S3Exception
import aws.smithy.kotlin.runtime.ClientException
import aws.smithy.kotlin.runtime.InternalApi
import aws.smithy.kotlin.runtime.SdkBaseException
import aws.smithy.kotlin.runtime.ServiceErrorMetadata
import aws.smithy.kotlin.runtime.http.HttpErrorCode
import aws.smithy.kotlin.runtime.http.HttpException
import aws.smithy.kotlin.runtime.http.HttpStatusCode
import aws.smithy.kotlin.runtime.http.operation.CallTimeoutException
import aws.smithy.kotlin.runtime.http.response.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * S3 の応答を受けた例外を、SDK と同じ形で作る。SDK は応答を受けると、例外の `protocolResponse` に HTTP の応答を、
 * `errorCode` に S3 のエラーコードを持たせる（`setS3ErrorMetadata`）。
 */
@OptIn(InternalApi::class)
private fun s3Exception(status: Int, errorCode: String? = null, message: String = "service error"): S3Exception =
    S3Exception(message).apply {
        sdkErrorMetadata.attributes[ServiceErrorMetadata.ProtocolResponse] = HttpResponse(HttpStatusCode.fromValue(status))
        errorCode?.let { sdkErrorMetadata.attributes[ServiceErrorMetadata.ErrorCode] = it }
    }

class S3ResultsTest {
    // GET：S3 の応答は HTTP の状態で振り分ける。304・404・403 はそれぞれの結果、5xx は結果が不明、そのほかは失敗
    @Test
    fun mapsGetStatuses() {
        assertEquals(GetObjectResult.NotModified, getResultOf(s3Exception(304)))
        assertEquals(GetObjectResult.NotFound, getResultOf(s3Exception(404, "NoSuchKey")))
        assertEquals(GetObjectResult.Forbidden, getResultOf(s3Exception(403, "AccessDenied")))
        listOf(500, 503).forEach { status -> assertIs<GetObjectResult.Unknown>(getResultOf(s3Exception(status)), "$status") }
        listOf(301, 400).forEach { status -> assertIs<GetObjectResult.Failed>(getResultOf(s3Exception(status)), "$status") }
    }

    // PUT：412・409・404・403 はそれぞれの結果、5xx（503 SlowDown を含む）は結果が不明、そのほかは失敗
    @Test
    fun mapsPutStatuses() {
        assertEquals(PutObjectResult.PreconditionFailed, putResultOf(s3Exception(412, "PreconditionFailed")))
        assertEquals(PutObjectResult.Conflict, putResultOf(s3Exception(409, "ConditionalRequestConflict")))
        assertEquals(PutObjectResult.NotFound, putResultOf(s3Exception(404, "NoSuchKey")))
        assertEquals(PutObjectResult.Forbidden, putResultOf(s3Exception(403, "AccessDenied")))
        listOf(500, 503).forEach { status -> assertIs<PutObjectResult.Unknown>(putResultOf(s3Exception(status)), "$status") }
        listOf(301, 400).forEach { status -> assertIs<PutObjectResult.Failed>(putResultOf(s3Exception(status)), "$status") }
    }

    // 一覧：403 は権限が無い、5xx は結果が不明、そのほか（無いバケットの 404 を含む）は失敗
    @Test
    fun mapsListStatuses() {
        assertEquals(ListObjectsResult.Forbidden, listResultOf(s3Exception(403, "AccessDenied")))
        listOf(500, 503).forEach { status -> assertIs<ListObjectsResult.Unknown>(listResultOf(s3Exception(status)), "$status") }
        listOf(301, 400, 404).forEach { status -> assertIs<ListObjectsResult.Failed>(listResultOf(s3Exception(status)), "$status") }
    }

    // バージョンの一覧：403 は権限が無い、5xx は結果が不明、そのほか（無いバケットの 404 を含む）は失敗
    @Test
    fun mapsListVersionsStatuses() {
        assertEquals(ListVersionsResult.Forbidden, listVersionsResultOf(s3Exception(403, "AccessDenied")))
        listOf(500, 503).forEach { status -> assertIs<ListVersionsResult.Unknown>(listVersionsResultOf(s3Exception(status)), "$status") }
        listOf(301, 400, 404).forEach { status -> assertIs<ListVersionsResult.Failed>(listVersionsResultOf(s3Exception(status)), "$status") }
    }

    // バージョンの一覧：応答を受けていない失敗と、エラーコードが RequestTimeout の 400 は結果が不明。種類を知らない SDK の失敗は失敗
    @Test
    fun listVersionsFailuresWithoutStatus() {
        listOf(
            HttpException("connect timeout", HttpErrorCode.CONNECT_TIMEOUT, true),
            CallTimeoutException("call timeout", RuntimeException("cause")),
            S3Exception("no response"),
            s3Exception(400, "RequestTimeout"),
        ).forEach { e -> assertIs<ListVersionsResult.Unknown>(listVersionsResultOf(e), e.toString()) }
        listOf(ClientException("client failure"), SdkBaseException("unclassified failure")).forEach { e ->
            assertIs<ListVersionsResult.Failed>(listVersionsResultOf(e), e.toString())
        }
    }

    // バージョンの GET：削除マーカーのバージョンへの GET が返す 405 は失敗（GET と同じ振り分け）
    @Test
    fun deleteMarkerVersionGetIsFailed() {
        assertIs<GetObjectResult.Failed>(getResultOf(s3Exception(405, "MethodNotAllowed")))
    }

    // DELETE：403 は権限が無い、5xx は結果が不明、そのほかは失敗
    @Test
    fun mapsDeleteStatuses() {
        assertEquals(DeleteObjectResult.Forbidden, deleteResultOf(s3Exception(403, "AccessDenied")))
        listOf(500, 503).forEach { status -> assertIs<DeleteObjectResult.Unknown>(deleteResultOf(s3Exception(status)), "$status") }
        listOf(301, 400, 404).forEach { status -> assertIs<DeleteObjectResult.Failed>(deleteResultOf(s3Exception(status)), "$status") }
    }

    // 一覧と DELETE：応答を受けていない失敗と、エラーコードが RequestTimeout の 400 は結果が不明
    @Test
    fun listAndDeleteFailuresWithoutStatusAreUnknown() {
        listOf(
            HttpException("connect timeout", HttpErrorCode.CONNECT_TIMEOUT, true),
            CallTimeoutException("call timeout", RuntimeException("cause")),
            S3Exception("no response"),
            s3Exception(400, "RequestTimeout"),
        ).forEach { e ->
            assertIs<ListObjectsResult.Unknown>(listResultOf(e), e.toString())
            assertIs<DeleteObjectResult.Unknown>(deleteResultOf(e), e.toString())
        }
    }

    // DELETE：S3 の応答でない、種類を知らない SDK の失敗は、消せたかどうかが不明（一覧は失敗）
    @Test
    fun unclassifiedSdkFailuresAreUnknownForDeleteAndFailedForList() {
        listOf(ClientException("client failure"), SdkBaseException("unclassified failure")).forEach { e ->
            assertIs<DeleteObjectResult.Unknown>(deleteResultOf(e), e.toString())
            assertIs<ListObjectsResult.Failed>(listResultOf(e), e.toString())
        }
    }

    // HTTP の `Date`（IMF-fixdate）を、時刻として読む。読めない値は null
    @Test
    fun parsesHttpDate() {
        assertEquals(Instant.parse("2026-11-16T12:34:56Z"), parseHttpDate("Mon, 16 Nov 2026 12:34:56 GMT"))
        assertNull(parseHttpDate("2026-11-16T12:34:56Z"))
        assertNull(parseHttpDate(""))
    }

    // エラーコードが RequestTimeout の 400 は、ほかの 400 と違い、結果が不明
    @Test
    fun requestTimeoutIsUnknown() {
        val e = s3Exception(400, "RequestTimeout")

        assertIs<GetObjectResult.Unknown>(getResultOf(e))
        assertIs<PutObjectResult.Unknown>(putResultOf(e))
        assertIs<PutObjectResult.Failed>(putResultOf(s3Exception(400, "InvalidArgument")))
    }

    // 応答を受けていない失敗（通信の失敗、タイムアウト）と、HTTP の状態が取れない S3 の例外は、結果が不明
    @Test
    fun failuresWithoutStatusAreUnknown() {
        val cases = listOf(
            HttpException("connect timeout", HttpErrorCode.CONNECT_TIMEOUT, true),
            HttpException("connection closed", HttpErrorCode.CONNECTION_CLOSED, false),
            CallTimeoutException("call timeout", RuntimeException("cause")),
            S3Exception("no response"),
        )

        cases.forEach { e ->
            assertIs<GetObjectResult.Unknown>(getResultOf(e), e.toString())
            assertIs<PutObjectResult.Unknown>(putResultOf(e), e.toString())
        }
    }

    // S3 の応答でない、そのほかの SDK の失敗：GET は失敗、PUT は書けたかどうかが不明
    @Test
    fun otherSdkFailuresAreFailedForGetAndUnknownForPut() {
        val cases = listOf(
            ClientException("client failure"),
            SdkBaseException("unclassified failure"),
        )

        cases.forEach { e ->
            assertIs<GetObjectResult.Failed>(getResultOf(e), e.toString())
            assertIs<PutObjectResult.Unknown>(putResultOf(e), e.toString())
        }
    }

    // 失敗の内容は、例外の種類・HTTP の状態・エラーコード・通信の失敗の種類だけで作り、例外のメッセージを含めない
    @Test
    fun describesFailureWithoutExceptionMessage() {
        assertEquals(
            "S3Exception HTTP 403 AccessDenied",
            describeFailure(s3Exception(403, "AccessDenied", message = "message-must-not-appear")),
        )
        assertEquals(
            "HttpException SOCKET_TIMEOUT",
            describeFailure(HttpException("message-must-not-appear", HttpErrorCode.SOCKET_TIMEOUT, true)),
        )
    }
}
