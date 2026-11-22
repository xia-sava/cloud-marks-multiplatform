package to.sava.cloudmarksmultiplatform.favicon

import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** 取った時刻。 */
private val FETCHED_AT: Instant = Instant.parse("2026-11-16T12:00:00Z")

/** [FETCHED_AT] と同じ時刻の、HTTP の日時の形。 */
private const val FETCHED_AT_HTTP_DATE = "Mon, 16 Nov 2026 12:00:00 GMT"

/** 30 日より長い、応答の指定の長さ。 */
private val SIXTY_DAYS = 60.days

class FaviconExpiryTest {
    private fun expiry(headers: CacheHeaders): Instant = foundExpiry(FETCHED_AT, headers)

    // 期限は、取った時刻から「30 日」と「応答の指定」の長いほう。指定が無ければ 30 日
    @Test
    fun expiryIsLongerOfThirtyDaysAndResponse() {
        listOf(
            CacheHeaders() to FOUND_LIFETIME,
            CacheHeaders(cacheControl = listOf("max-age=${SIXTY_DAYS.inWholeSeconds}")) to SIXTY_DAYS,
            CacheHeaders(cacheControl = listOf("max-age=${1.days.inWholeSeconds}")) to FOUND_LIFETIME,
            CacheHeaders(cacheControl = listOf("max-age=0")) to FOUND_LIFETIME,
        ).forEach { (headers, lifetime) -> assertEquals(FETCHED_AT + lifetime, expiry(headers), headers.toString()) }
    }

    // max-age は、ほかの指示と並んでいても、複数のヘッダーに分かれていても、大文字でも、値が引用符で囲まれていても読む。ほかの指示は見ない
    @Test
    fun readsMaxAgeAmongDirectives() {
        val seconds = SIXTY_DAYS.inWholeSeconds
        listOf(
            listOf("public, max-age=$seconds, immutable"),
            listOf("no-cache", "max-age=$seconds"),
            listOf("no-store, MAX-AGE=$seconds"),
            listOf("max-age = \"$seconds\""),
        ).forEach { cacheControl ->
            assertEquals(FETCHED_AT + SIXTY_DAYS, expiry(CacheHeaders(cacheControl = cacheControl)), cacheControl.toString())
        }
    }

    // max-age があれば Expires より優先する。max-age が読めない・負なら、Expires は見ずに 30 日にする
    @Test
    fun maxAgeTakesPrecedenceOverExpires() {
        val farExpires = "Wed, 16 Nov 2050 12:00:00 GMT"
        listOf("max-age=${1.days.inWholeSeconds}", "max-age=abc", "max-age=-1", "max-age").forEach { directive ->
            assertEquals(
                FETCHED_AT + FOUND_LIFETIME,
                expiry(CacheHeaders(cacheControl = listOf(directive), expires = farExpires)),
                directive,
            )
        }
    }

    // max-age が無ければ Expires を使う。Date があれば Expires と Date の差を長さとし、取った時刻から数える
    @Test
    fun expiresMinusDate() {
        val headers = CacheHeaders(expires = "Sun, 31 Mar 2024 00:00:00 GMT", date = "Wed, 31 Jan 2024 00:00:00 GMT")

        assertEquals(FETCHED_AT + SIXTY_DAYS, expiry(headers))
    }

    // Date が無いか読めなければ、Expires と取った時刻の差を長さとする
    @Test
    fun expiresMinusFetchedAtWithoutDate() {
        val expires = "Fri, 15 Jan 2027 12:00:00 GMT"
        listOf(null, "not a date").forEach { date ->
            assertEquals(Instant.parse("2027-01-15T12:00:00Z"), expiry(CacheHeaders(expires = expires, date = date)), "$date")
        }
    }

    // Expires が読めないか、Date より前なら 30 日にする
    @Test
    fun unreadableOrPastExpiresFallsBack() {
        listOf(
            CacheHeaders(expires = "0"),
            CacheHeaders(expires = "-1"),
            CacheHeaders(expires = "Mon, 16 Nov 2026 11:00:00 GMT", date = FETCHED_AT_HTTP_DATE),
            CacheHeaders(expires = "Mon, 16 Nov 2026 11:00:00 GMT"),
        ).forEach { headers -> assertEquals(FETCHED_AT + FOUND_LIFETIME, expiry(headers), headers.toString()) }
    }

    // 非常に長い max-age でも、例外を投げずに、30 日より先の期限にする
    @Test
    fun hugeMaxAgeDoesNotOverflow() {
        val expiresAt = expiry(CacheHeaders(cacheControl = listOf("max-age=${Long.MAX_VALUE}")))

        assertTrue(expiresAt > FETCHED_AT + SIXTY_DAYS, expiresAt.toString())
        assertTrue(decodeCachedFavicon(encodeCachedFavicon(CachedFavicon(FETCHED_AT, expiresAt, null)))!!.expiresAt > FETCHED_AT + SIXTY_DAYS)
    }

    // 応答のヘッダーから、Cache-Control（すべて）・Expires・Date・ETag・Last-Modified を取り出す
    @Test
    fun extractsCacheHeaders() {
        val headers = headersOf(
            HttpHeaders.CacheControl to listOf("public", "max-age=60"),
            HttpHeaders.Expires to listOf(FETCHED_AT_HTTP_DATE),
            HttpHeaders.Date to listOf(FETCHED_AT_HTTP_DATE),
            HttpHeaders.ETag to listOf("\"v1\""),
            HttpHeaders.LastModified to listOf(FETCHED_AT_HTTP_DATE),
            HttpHeaders.ContentType to listOf("image/png"),
        )

        assertEquals(
            CacheHeaders(listOf("public", "max-age=60"), FETCHED_AT_HTTP_DATE, FETCHED_AT_HTTP_DATE, "\"v1\"", FETCHED_AT_HTTP_DATE),
            cacheHeadersOf(headers),
        )
    }

    // 応答の指定の長さは、max-age の秒数そのまま
    @Test
    fun lifetimeIsMaxAgeSeconds() {
        assertEquals(3600.seconds, responseLifetime(CacheHeaders(cacheControl = listOf("max-age=3600")), FETCHED_AT))
    }
}
