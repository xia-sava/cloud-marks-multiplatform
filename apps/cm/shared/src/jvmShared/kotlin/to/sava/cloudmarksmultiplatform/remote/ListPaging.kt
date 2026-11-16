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
