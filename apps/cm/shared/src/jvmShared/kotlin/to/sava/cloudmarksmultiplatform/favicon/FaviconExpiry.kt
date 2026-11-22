package to.sava.cloudmarksmultiplatform.favicon

import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** 取れた favicon をキャッシュに置いておく長さの下限。応答の指定（[responseLifetime]）がこれより長ければ、そちらにする。 */
internal val FOUND_LIFETIME = 30.days

/** 取れなかったことをキャッシュに置いておく長さ。応答の指定は見ない。 */
internal val MISSING_LIFETIME = 1.days

/** `Cache-Control` の、応答を新しいとみなす秒数の指示の名前。 */
private const val MAX_AGE_DIRECTIVE = "max-age"

/** `Cache-Control` の指示どうしの区切り。 */
private const val DIRECTIVE_SEPARATOR = ','

/** `Cache-Control` の指示の、名前と値の区切り。 */
private const val DIRECTIVE_VALUE_SEPARATOR = '='

/** `Cache-Control` の指示の値を囲むことのある引用符。 */
private const val DIRECTIVE_VALUE_QUOTE = '"'

/**
 * 画像の応答のヘッダーのうち、キャッシュの期限と、変わったかを問い合わせることに使うもの。
 *
 * @property cacheControl `Cache-Control` の値。複数あれば、すべて
 * @property expires `Expires` の値
 * @property date `Date` の値
 * @property eTag `ETag` の値
 * @property lastModified `Last-Modified` の値
 */
internal data class CacheHeaders(
    val cacheControl: List<String> = emptyList(),
    val expires: String? = null,
    val date: String? = null,
    val eTag: String? = null,
    val lastModified: String? = null,
)

/** 応答のヘッダー [headers] の、キャッシュの期限と、変わったかを問い合わせることに使うもの。 */
internal fun cacheHeadersOf(headers: Headers): CacheHeaders =
    CacheHeaders(
        cacheControl = headers.getAll(HttpHeaders.CacheControl).orEmpty(),
        expires = headers[HttpHeaders.Expires],
        date = headers[HttpHeaders.Date],
        eTag = headers[HttpHeaders.ETag],
        lastModified = headers[HttpHeaders.LastModified],
    )

/**
 * [fetchedAt] に取れた favicon の、キャッシュの期限。[fetchedAt] から、[FOUND_LIFETIME] と応答の指定（[responseLifetime]）の
 * 長いほうだけたった時刻。応答の指定が無いか読めなければ、[FOUND_LIFETIME]。
 */
internal fun foundExpiry(fetchedAt: Instant, headers: CacheHeaders): Instant =
    fetchedAt + maxOf(FOUND_LIFETIME, responseLifetime(headers, fetchedAt) ?: FOUND_LIFETIME)

/**
 * 応答のヘッダー [headers] が指定する、応答を新しいとみなす長さ。
 *
 * `Cache-Control` に `max-age` があればそれ（秒数）を使い、`Expires` は見ない。無ければ `Expires` から、`Date` があれば `Expires` と
 * `Date` の差を、無ければ `Expires` と取った時刻 [fetchedAt] の差を長さとする。ほかの指示（`no-store`・`no-cache` など）は見ない。
 * 指定が無い、読めない、負なら null。
 */
internal fun responseLifetime(headers: CacheHeaders, fetchedAt: Instant): Duration? {
    val maxAge = headers.cacheControl
        .flatMap { it.split(DIRECTIVE_SEPARATOR) }
        .map { it.trim() }
        .firstOrNull { it.substringBefore(DIRECTIVE_VALUE_SEPARATOR).trim().equals(MAX_AGE_DIRECTIVE, ignoreCase = true) }
    if (maxAge != null) {
        return maxAge.substringAfter(DIRECTIVE_VALUE_SEPARATOR, missingDelimiterValue = "")
            .trim()
            .removeSurrounding(DIRECTIVE_VALUE_QUOTE.toString())
            .toLongOrNull()
            ?.takeIf { it >= 0 }
            ?.seconds
    }
    val expires = headers.expires?.let(::parseHttpDate) ?: return null
    val base = headers.date?.let(::parseHttpDate) ?: fetchedAt
    return (expires - base).takeIf { !it.isNegative() }
}

/** HTTP の日時（`Wed, 21 Oct 2015 07:28:00 GMT` の形）。読めなければ null。 */
private fun parseHttpDate(value: String): Instant? =
    try {
        ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant()
            .toEpochMilli()
            .let(Instant::fromEpochMilliseconds)
    } catch (e: DateTimeParseException) {
        null
    }
