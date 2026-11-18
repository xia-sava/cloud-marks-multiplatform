package to.sava.cloudmarksmultiplatform.remote

import aws.sdk.kotlin.services.s3.model.DeleteMarkerEntry
import aws.sdk.kotlin.services.s3.model.ListObjectVersionsResponse
import aws.sdk.kotlin.services.s3.model.ObjectVersion
import aws.smithy.kotlin.runtime.time.Instant as SdkInstant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/** S3 のバージョンの一覧の 1 ページの応答（[ListObjectVersionsResponse]）を、[VersionPageResult] にする処理。 */
class VersionPageOfTest {
    private val key = "cm/data/manifest.jsonl.gz"
    private val modified = SdkInstant.fromEpochSeconds(1_700_000_000L)

    private fun version(
        key: String? = this.key,
        versionId: String? = "v1",
        lastModified: SdkInstant? = modified,
        isLatest: Boolean? = false,
    ): ObjectVersion =
        ObjectVersion {
            this.key = key
            this.versionId = versionId
            this.lastModified = lastModified
            this.isLatest = isLatest
        }

    private fun deleteMarker(
        key: String? = this.key,
        versionId: String? = "d1",
        lastModified: SdkInstant? = modified,
        isLatest: Boolean? = false,
    ): DeleteMarkerEntry =
        DeleteMarkerEntry {
            this.key = key
            this.versionId = versionId
            this.lastModified = lastModified
            this.isLatest = isLatest
        }

    private fun response(
        versions: List<ObjectVersion>? = listOf(version()),
        deleteMarkers: List<DeleteMarkerEntry>? = null,
        truncated: Boolean? = false,
        nextKeyMarker: String? = null,
        nextVersionIdMarker: String? = null,
    ): ListObjectVersionsResponse =
        ListObjectVersionsResponse {
            this.versions = versions
            this.deleteMarkers = deleteMarkers
            this.isTruncated = truncated
            this.nextKeyMarker = nextKeyMarker
            this.nextVersionIdMarker = nextVersionIdMarker
        }

    private fun failureDetail(result: VersionPageResult): String =
        assertIs<ListVersionsResult.Failed>(assertIs<VersionPageResult.Stopped>(result).failure).detail

    // バージョンと削除マーカーを、それぞれの順のまま 1 つの一覧にし、時刻・いまのバージョンか・削除マーカーかを引き継ぐ
    @Test
    fun keepsVersionsAndDeleteMarkers() {
        val page = assertIs<VersionPageResult.Page>(
            versionPageOf(
                response(
                    versions = listOf(version(versionId = "v2", isLatest = false), version(versionId = "v1")),
                    deleteMarkers = listOf(deleteMarker(versionId = "d1", isLatest = true)),
                ),
                key,
            ),
        )

        val expectedTime = Instant.fromEpochSeconds(1_700_000_000L)
        assertEquals(
            listOf(
                ListedVersion("v2", expectedTime, isLatest = false, isDeleteMarker = false),
                ListedVersion("v1", expectedTime, isLatest = false, isDeleteMarker = false),
                ListedVersion("d1", expectedTime, isLatest = true, isDeleteMarker = true),
            ),
            page.versions,
        )
        assertEquals(null, page.next)
    }

    // キーが一致しないエントリ（プレフィックスだけが一致する別のキー）は、バージョンも削除マーカーも除く
    @Test
    fun dropsEntriesOfOtherKeys() {
        val page = assertIs<VersionPageResult.Page>(
            versionPageOf(
                response(
                    versions = listOf(version(versionId = "v1"), version(key = "$key.bak", versionId = "other-v")),
                    deleteMarkers = listOf(deleteMarker(key = "$key.bak", versionId = "other-d")),
                ),
                key,
            ),
        )

        assertEquals(listOf("v1"), page.versions.map { it.versionId })
    }

    // IsLatest が無いエントリは、いまのバージョンではないものとする
    @Test
    fun missingIsLatestMeansNotLatest() {
        val page = assertIs<VersionPageResult.Page>(versionPageOf(response(listOf(version(isLatest = null))), key))

        assertEquals(false, page.versions.single().isLatest)
    }

    // 応答にエントリが無い（versions も deleteMarkers も無い）ページは、エントリの無い最後のページ
    @Test
    fun emptyResponseIsLastPageWithoutVersions() {
        val page = assertIs<VersionPageResult.Page>(versionPageOf(response(versions = null, truncated = null), key))

        assertEquals(emptyList(), page.versions)
        assertEquals(null, page.next)
    }

    // 切り詰められていて、次のキーのマーカーが対象のキーなら、次のバージョンのマーカーとともに続きの位置を返す
    @Test
    fun truncatedResponseOnTheSameKeyHasNextToken() {
        val page = assertIs<VersionPageResult.Page>(
            versionPageOf(response(truncated = true, nextKeyMarker = key, nextVersionIdMarker = "v1"), key),
        )

        val next = assertIs<VersionPageToken>(page.next)
        assertEquals(key, next.keyMarker)
        assertEquals("v1", next.versionIdMarker)
    }

    // 切り詰められていても、次のキーのマーカーが対象のキーより後ろなら、対象のキーは読み終えているので最後のページ
    @Test
    fun truncatedResponseMovedPastTheKeyIsLastPage() {
        val page = assertIs<VersionPageResult.Page>(
            versionPageOf(response(truncated = true, nextKeyMarker = "$key.bak", nextVersionIdMarker = "x"), key),
        )

        assertEquals(null, page.next)
        assertEquals(listOf("v1"), page.versions.map { it.versionId })
    }

    // 切り詰められていなければ、マーカーが付いていても次のページは無い
    @Test
    fun notTruncatedResponseHasNoNextPageEvenWithMarkers() {
        val page = assertIs<VersionPageResult.Page>(
            versionPageOf(response(truncated = false, nextKeyMarker = key, nextVersionIdMarker = "v1"), key),
        )

        assertEquals(null, page.next)
    }

    // 切り詰められたのに次のキーのマーカーが無い応答は、最後まで読めたと言えないので失敗にする
    @Test
    fun truncatedResponseWithoutKeyMarkerFails() {
        assertContains(failureDetail(versionPageOf(response(truncated = true), key)), "キーのマーカー")
    }

    // 次のキーのマーカーが対象のキーなのに、次のバージョンのマーカーが無い応答は失敗にする
    @Test
    fun truncatedResponseOnTheSameKeyWithoutVersionIdMarkerFails() {
        assertContains(
            failureDetail(versionPageOf(response(truncated = true, nextKeyMarker = key), key)),
            "バージョンのマーカー",
        )
    }

    // キーの無いエントリを含む応答は、バージョンでも削除マーカーでも失敗にする
    @Test
    fun entryWithoutKeyFails() {
        assertContains(failureDetail(versionPageOf(response(listOf(version(), version(key = null))), key)), "キー")
        assertContains(
            failureDetail(versionPageOf(response(deleteMarkers = listOf(deleteMarker(key = null))), key)),
            "キー",
        )
    }

    // 対象のキーのエントリに VersionId が無い応答は失敗にする
    @Test
    fun entryWithoutVersionIdFails() {
        assertContains(failureDetail(versionPageOf(response(listOf(version(versionId = null))), key)), "VersionId")
        assertContains(
            failureDetail(versionPageOf(response(deleteMarkers = listOf(deleteMarker(versionId = null))), key)),
            "VersionId",
        )
    }

    // 対象のキーのエントリに LastModified が無い応答は失敗にする
    @Test
    fun entryWithoutLastModifiedFails() {
        assertContains(failureDetail(versionPageOf(response(listOf(version(lastModified = null))), key)), "LastModified")
        assertContains(
            failureDetail(versionPageOf(response(deleteMarkers = listOf(deleteMarker(lastModified = null))), key)),
            "LastModified",
        )
    }

    // 別のキーのエントリは、VersionId や LastModified が欠けていても確かめない（除く）
    @Test
    fun otherKeysEntriesAreNotValidated() {
        val page = assertIs<VersionPageResult.Page>(
            versionPageOf(
                response(listOf(version(), version(key = "$key.bak", versionId = null, lastModified = null))),
                key,
            ),
        )

        assertEquals(listOf("v1"), page.versions.map { it.versionId })
    }
}
