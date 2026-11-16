package to.sava.cloudmarksmultiplatform.remote

import aws.sdk.kotlin.services.s3.model.ListObjectsV2Response
import aws.smithy.kotlin.runtime.time.Instant as SdkInstant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant
import aws.sdk.kotlin.services.s3.model.Object as S3Object

/** S3 の一覧の 1 ページの応答（[ListObjectsV2Response]）を、[ListPageResult] にする処理。 */
class ListPageOfTest {
    private val serverTime = Instant.parse("2026-11-16T12:00:00Z")
    private val modified = SdkInstant.fromEpochSeconds(1_700_000_000L)

    private fun entry(key: String? = "a/1", lastModified: SdkInstant? = modified): S3Object =
        S3Object {
            this.key = key
            this.lastModified = lastModified
        }

    private fun response(
        contents: List<S3Object>? = listOf(entry()),
        truncated: Boolean? = false,
        token: String? = null,
    ): ListObjectsV2Response =
        ListObjectsV2Response {
            this.contents = contents
            this.isTruncated = truncated
            this.nextContinuationToken = token
        }

    private fun failureDetail(result: ListPageResult): String =
        assertIs<ListObjectsResult.Failed>(assertIs<ListPageResult.Stopped>(result).failure).detail

    // 切り詰められていて継続トークンがあれば、キーと最終更新を写し、次のページを読むトークンを返す
    @Test
    fun truncatedResponseWithTokenHasNextPage() {
        val page = assertIs<ListPageResult.Page>(
            listPageOf(response(listOf(entry("a/1"), entry("a/2")), truncated = true, token = "t1"), serverTime),
        )

        assertEquals("t1", page.next)
        assertEquals(listOf("a/1", "a/2"), page.objects.map { it.key })
        assertEquals(Instant.fromEpochSeconds(1_700_000_000L), page.objects.first().lastModified)
        assertEquals(serverTime, page.serverTime)
    }

    // 切り詰められていなければ、継続トークンが付いていても次のページは無い
    @Test
    fun notTruncatedResponseHasNoNextPageEvenWithToken() {
        val page = assertIs<ListPageResult.Page>(listPageOf(response(truncated = false, token = "t1"), serverTime))

        assertEquals(null, page.next)
    }

    // 一覧が空（contents が無い）のページは、オブジェクトの無い最後のページ
    @Test
    fun emptyResponseIsLastPageWithoutObjects() {
        val page = assertIs<ListPageResult.Page>(listPageOf(response(contents = null, truncated = null), serverTime))

        assertEquals(emptyList(), page.objects)
        assertEquals(null, page.next)
    }

    // 切り詰められたのに継続トークンが無い応答は、最後まで読めたと言えないので失敗にする
    @Test
    fun truncatedResponseWithoutTokenFails() {
        assertContains(failureDetail(listPageOf(response(truncated = true, token = null), serverTime)), "継続トークン")
    }

    // キーの無いオブジェクトを含む応答は失敗にする
    @Test
    fun objectWithoutKeyFails() {
        assertContains(failureDetail(listPageOf(response(listOf(entry(), entry(key = null))), serverTime)), "キー")
    }

    // LastModified の無いオブジェクトを含む応答は失敗にする
    @Test
    fun objectWithoutLastModifiedFails() {
        assertContains(failureDetail(listPageOf(response(listOf(entry(), entry(lastModified = null))), serverTime)), "LastModified")
    }

    // 応答の Date が無いか読めない（時刻が null）ときは失敗にする。端末の時計で代えない
    @Test
    fun missingServerTimeFails() {
        assertContains(failureDetail(listPageOf(response(), serverTime = null)), "Date")
    }
}
