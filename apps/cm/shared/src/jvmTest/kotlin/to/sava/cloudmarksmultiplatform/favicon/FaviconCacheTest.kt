package to.sava.cloudmarksmultiplatform.favicon

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** 取った時刻。 */
private val FETCHED_AT: Instant = Instant.parse("2026-11-16T12:00:00Z")

/** 期限の時刻。 */
private val EXPIRES_AT: Instant = Instant.parse("2026-12-16T12:00:00Z")

/** 取れた画像のキャッシュ。 */
private val FOUND = CachedFavicon(
    FETCHED_AT,
    EXPIRES_AT,
    CachedImage("https://example.com/favicon.ico", "\"v1\"", "Mon, 01 Jan 2024 00:00:00 GMT", byteArrayOf(1, 2, 3, 0, -1)),
)

/** 無しのキャッシュ。 */
private val MISSING = CachedFavicon(FETCHED_AT, EXPIRES_AT, image = null)

/** 形の版（1）と、2 つの時刻の大きさ（バイト数）。 */
private const val FIXED_HEADER_BYTES = 4 + 8 + 8

class FaviconCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val cacheDirectory: Path by lazy { temporaryFolder.root.toPath().resolve("cache") }

    private val logs = mutableListOf<String>()

    private val cache by lazy { FaviconCache(cacheDirectory, logs::add) }

    private fun assertSameFavicon(expected: CachedFavicon, actual: CachedFavicon?) {
        val read = assertNotNull(actual)
        assertEquals(expected.fetchedAt, read.fetchedAt)
        assertEquals(expected.expiresAt, read.expiresAt)
        assertEquals(expected.image?.url, read.image?.url)
        assertEquals(expected.image?.eTag, read.image?.eTag)
        assertEquals(expected.image?.lastModified, read.image?.lastModified)
        assertContentEquals(expected.image?.bytes, read.image?.bytes)
    }

    // 取れた画像と無しを書いて、同じものを読める
    @Test
    fun roundTrips() {
        listOf(FOUND, MISSING).forEach { favicon ->
            cache.write(EXAMPLE_ORIGIN, favicon)

            assertSameFavicon(favicon, cache.read(EXAMPLE_ORIGIN))
        }
        assertEquals(emptyList(), logs)
    }

    // ETag と Last-Modified が無い画像も、無いまま読める
    @Test
    fun roundTripsWithoutValidators() {
        val favicon = CachedFavicon(FETCHED_AT, EXPIRES_AT, CachedImage("https://example.com/a.png", null, null, byteArrayOf(9)))

        cache.write(EXAMPLE_ORIGIN, favicon)

        assertSameFavicon(favicon, cache.read(EXAMPLE_ORIGIN))
    }

    // ファイルは、ローカルキャッシュの下の favicons/ に、オリジンの SHA-256 の 16 進の名前で置く
    @Test
    fun fileIsNamedByOriginHash() {
        val hash = MessageDigest.getInstance("SHA-256").digest("https://example.com".encodeToByteArray()).toHexString()

        cache.write(EXAMPLE_ORIGIN, MISSING)

        assertEquals(cacheDirectory.resolve("favicons").resolve(hash), cache.fileOf(EXAMPLE_ORIGIN))
        assertTrue(cache.fileOf(EXAMPLE_ORIGIN).exists())
    }

    // 中身の形は、版・取った時刻・期限・URL・ETag・Last-Modified・画像のバイト列の順
    @Test
    fun encodesInDocumentedLayout() {
        val image = assertNotNull(FOUND.image)
        val texts = listOf(image.url, image.eTag!!, image.lastModified!!).map { it.encodeToByteArray() }
        val expected = ByteBuffer.allocate(FIXED_HEADER_BYTES + texts.sumOf { 4 + it.size } + image.bytes.size)
            .putInt(1)
            .putLong(FETCHED_AT.toEpochMilliseconds())
            .putLong(EXPIRES_AT.toEpochMilliseconds())
            .apply { texts.forEach { putInt(it.size).put(it) } }
            .put(image.bytes)
            .array()

        assertContentEquals(expected, encodeCachedFavicon(FOUND))
    }

    // 無いファイルは、失敗として書かずに null
    @Test
    fun missingFileIsNull() {
        assertNull(cache.read(EXAMPLE_ORIGIN))
        assertEquals(emptyList(), logs)
    }

    // 形の崩れたファイル（短すぎる・版が違う・長さが合わない・URL とバイト列の片方だけがある・UTF-8 として読めない）は、
    // 無いものとして null にし、ログに書く
    @Test
    fun brokenFileIsNull() {
        val valid = encodeCachedFavicon(FOUND)
        val missing = encodeCachedFavicon(MISSING)
        val urlLengthOffset = FIXED_HEADER_BYTES
        listOf(
            "空" to ByteArray(0),
            "短すぎる" to valid.copyOf(FIXED_HEADER_BYTES - 1),
            "版が違う" to valid.copyOf().also { ByteBuffer.wrap(it).putInt(0, 2) },
            "長さが残りを超える" to valid.copyOf().also { ByteBuffer.wrap(it).putInt(urlLengthOffset, valid.size) },
            "長さが負" to valid.copyOf().also { ByteBuffer.wrap(it).putInt(urlLengthOffset, -1) },
            "文字列の途中で切れる" to valid.copyOf(FIXED_HEADER_BYTES + 2),
            "URL が無いのにバイト列がある" to missing + byteArrayOf(1),
            "URL があるのにバイト列が無い" to valid.copyOf(valid.size - FOUND.image!!.bytes.size),
            "UTF-8 として読めない" to valid.copyOf().also { it[FIXED_HEADER_BYTES + 4] = 0xFF.toByte() },
        ).forEach { (name, bytes) ->
            logs.clear()
            Files.createDirectories(cache.fileOf(EXAMPLE_ORIGIN).parent)
            Files.write(cache.fileOf(EXAMPLE_ORIGIN), bytes)

            assertNull(cache.read(EXAMPLE_ORIGIN), name)
            assertEquals(1, logs.size, name)
        }
    }

    // ファイルを読めない I/O の失敗は、null にし、ログに書く
    @Test
    fun readFailureIsLogged() {
        Files.createDirectories(cache.fileOf(EXAMPLE_ORIGIN))

        assertNull(cache.read(EXAMPLE_ORIGIN))
        assertTrue(logs.single().startsWith("favicon のキャッシュを読めなかった: "), logs.toString())
    }

    // ファイルを書けない I/O の失敗は、投げずにログに書く
    @Test
    fun writeFailureIsLogged() {
        Files.createDirectories(cacheDirectory)
        Files.write(cacheDirectory.resolve("favicons"), byteArrayOf())

        cache.write(EXAMPLE_ORIGIN, FOUND)

        assertTrue(logs.single().startsWith("favicon のキャッシュを書けなかった: "), logs.toString())
    }
}
