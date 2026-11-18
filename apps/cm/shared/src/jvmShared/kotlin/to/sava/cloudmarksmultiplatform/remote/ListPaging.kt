package to.sava.cloudmarksmultiplatform.remote

import kotlin.time.Instant

/** 一覧の 1 ページを読んだ結果。 */
internal sealed interface ListPageResult {
    /**
     * 読めた。
     *
     * @property next 次のページを読む継続トークン。最後のページなら null
     * @property serverTime このページの応答の `Date`
     */
    class Page(val objects: List<ListedObject>, val next: String?, val serverTime: Instant) : ListPageResult

    /** 読めなかった。一覧全体の結果として返す失敗。 */
    class Stopped(val failure: ListObjectsResult) : ListPageResult
}

/**
 * [readPage] で、継続トークンが無くなるまでページを読み続けて、1 つの一覧にする。
 *
 * 途中のページが [ListPageResult.Stopped] なら、そこまでに読んだものは返さず、その失敗を返す。
 * 返す時刻は最初のページのもの。
 *
 * @param readPage 継続トークンを受け取り（最初のページは null）、そのページを読む
 */
internal suspend fun listAllPages(readPage: suspend (continuationToken: String?) -> ListPageResult): ListObjectsResult =
    collectPages(readPage, token = null, listed = emptyList(), serverTime = null)

/**
 * バージョンの一覧の、次のページを読む位置。ListObjectVersions の `KeyMarker` と `VersionIdMarker` の組。
 *
 * @property versionIdMarker 同じキーの途中から続けるときの、直前のバージョンの ID
 */
internal class VersionPageToken(val keyMarker: String, val versionIdMarker: String)

/** バージョンの一覧の 1 ページを読んだ結果。 */
internal sealed interface VersionPageResult {
    /**
     * 読めた。
     *
     * @property next 次のページを読む位置。最後のページなら null
     */
    class Page(val versions: List<ListedVersion>, val next: VersionPageToken?) : VersionPageResult

    /** 読めなかった。一覧全体の結果として返す失敗。 */
    class Stopped(val failure: ListVersionsResult) : VersionPageResult
}

/**
 * [readPage] で、続きの位置が無くなるまでページを読み続けて、1 つの一覧にする。
 *
 * 途中のページが [VersionPageResult.Stopped] なら、そこまでに読んだものは返さず、その失敗を返す。
 *
 * @param readPage 読む位置を受け取り（最初のページは null）、そのページを読む
 */
internal suspend fun listAllVersionPages(readPage: suspend (token: VersionPageToken?) -> VersionPageResult): ListVersionsResult =
    collectVersionPages(readPage, token = null, listed = emptyList())

private tailrec suspend fun collectVersionPages(
    readPage: suspend (VersionPageToken?) -> VersionPageResult,
    token: VersionPageToken?,
    listed: List<ListedVersion>,
): ListVersionsResult =
    when (val page = readPage(token)) {
        is VersionPageResult.Stopped -> page.failure
        is VersionPageResult.Page ->
            if (page.next == null) {
                ListVersionsResult.Listed(listed + page.versions)
            } else {
                collectVersionPages(readPage, page.next, listed + page.versions)
            }
    }

private tailrec suspend fun collectPages(
    readPage: suspend (String?) -> ListPageResult,
    token: String?,
    listed: List<ListedObject>,
    serverTime: Instant?,
): ListObjectsResult =
    when (val page = readPage(token)) {
        is ListPageResult.Stopped -> page.failure
        is ListPageResult.Page ->
            if (page.next == null) {
                ListObjectsResult.Listed(listed + page.objects, serverTime ?: page.serverTime)
            } else {
                collectPages(readPage, page.next, listed + page.objects, serverTime ?: page.serverTime)
            }
    }
