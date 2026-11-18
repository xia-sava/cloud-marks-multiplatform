package to.sava.cloudmarksmultiplatform.remote

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** 一覧のページをつなぐ処理（S3 の実装と偽のストレージが共有する）が、継続トークンを最後まで辿ること。 */
class ListPagingTest {
    private val time = Instant.parse("2026-11-16T12:00:00Z")

    private fun page(vararg keys: String, next: String?, serverTime: Instant = time) =
        ListPageResult.Page(keys.map { ListedObject(it, time) }, next, serverTime)

    // 継続トークンが無くなるまで読み、各ページのオブジェクトを順につなぐ。最初のページは継続トークンなしで読む
    @Test
    fun followsContinuationTokensToTheLastPage() = runTest {
        val pages = mapOf(
            null to page("a", "b", next = "t1"),
            "t1" to page("c", "d", next = "t2"),
            "t2" to page("e", next = null),
        )
        val tokens = mutableListOf<String?>()

        val result = listAllPages { token ->
            tokens += token
            pages.getValue(token)
        }

        assertEquals(listOf(null, "t1", "t2"), tokens)
        assertEquals(listOf("a", "b", "c", "d", "e"), assertIs<ListObjectsResult.Listed>(result).objects.map { it.key })
    }

    // 時刻は最初のページの応答のもの（後のページの時刻は使わない）
    @Test
    fun usesServerTimeOfTheFirstPage() = runTest {
        val later = time + 1.hours
        val pages = mapOf(
            null to page("a", next = "t1"),
            "t1" to page("b", next = null, serverTime = later),
        )

        val result = listAllPages { token -> pages.getValue(token) }

        assertEquals(time, assertIs<ListObjectsResult.Listed>(result).serverTime)
    }

    // 途中のページが失敗したら、そこまでに読んだものを返さず、その失敗を返し、続きのページを読まない
    @Test
    fun failureOnLaterPageDiscardsEarlierPagesAndStops() = runTest {
        val tokens = mutableListOf<String?>()

        val result = listAllPages { token ->
            tokens += token
            when (token) {
                null -> page("a", next = "t1")
                "t1" -> ListPageResult.Stopped(ListObjectsResult.Forbidden)
                else -> error("失敗のあとのページを読んだ")
            }
        }

        assertEquals(ListObjectsResult.Forbidden, result)
        assertEquals(listOf(null, "t1"), tokens)
    }

    private fun version(id: String, isLatest: Boolean = false) = ListedVersion(id, time, isLatest, isDeleteMarker = false)

    private fun versionPage(vararg ids: String, next: VersionPageToken?) =
        VersionPageResult.Page(ids.map { version(it) }, next)

    // バージョンの一覧：続きの位置が無くなるまで読み、各ページを順につなぐ。最初のページは位置なしで読み、位置はそのまま渡す
    @Test
    fun followsVersionTokensToTheLastPage() = runTest {
        val tokens = mutableListOf<VersionPageToken?>()
        val first = VersionPageToken("k", "v2")
        val second = VersionPageToken("k", "v4")

        val result = listAllVersionPages { token ->
            tokens += token
            when (token) {
                null -> versionPage("v1", "v2", next = first)
                first -> versionPage("v3", "v4", next = second)
                second -> versionPage("v5", next = null)
                else -> error("知らない位置を読んだ")
            }
        }

        assertEquals(listOf(null, first, second), tokens)
        assertEquals(listOf("v1", "v2", "v3", "v4", "v5"), assertIs<ListVersionsResult.Listed>(result).versions.map { it.versionId })
    }

    // バージョンの一覧：途中のページが失敗したら、そこまでに読んだものを返さず、その失敗を返し、続きのページを読まない
    @Test
    fun versionFailureOnLaterPageDiscardsEarlierPagesAndStops() = runTest {
        val first = VersionPageToken("k", "v1")
        var reads = 0

        val result = listAllVersionPages { token ->
            reads += 1
            when (token) {
                null -> versionPage("v1", next = first)
                first -> VersionPageResult.Stopped(ListVersionsResult.Forbidden)
                else -> error("失敗のあとのページを読んだ")
            }
        }

        assertEquals(ListVersionsResult.Forbidden, result)
        assertEquals(2, reads)
    }
}
