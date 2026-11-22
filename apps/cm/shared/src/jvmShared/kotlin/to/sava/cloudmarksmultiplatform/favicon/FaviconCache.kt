package to.sava.cloudmarksmultiplatform.favicon

import to.sava.cloudmarksmultiplatform.files.describeIoFailure
import to.sava.cloudmarksmultiplatform.files.replaceFileAtomically
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.time.Instant

/** favicon のキャッシュを置くディレクトリの名前。ローカルキャッシュのルートディレクトリの下に置く。 */
internal const val FAVICON_DIRECTORY_NAME = "favicons"

/** キャッシュのファイルの形の版。形を変えたら上げ、前の版のファイルは読まない（無いものとして取り直す）。 */
private const val FORMAT_VERSION = 1

/** キャッシュのファイルの、版と 2 つの時刻の大きさ（バイト数）。 */
private const val FIXED_HEADER_BYTES = Int.SIZE_BYTES + Long.SIZE_BYTES + Long.SIZE_BYTES

/** キャッシュのファイルの、文字列の長さ（バイト数）の大きさ。 */
private const val TEXT_LENGTH_BYTES = Int.SIZE_BYTES

private const val HASH_ALGORITHM = "SHA-256"

/**
 * キャッシュに置いた、取れた favicon の画像。
 *
 * @property url 画像を取りに行った URL（転送の前）。取り直すときに、まずここへ問い合わせる
 * @property eTag 画像の応答の `ETag`。無ければ null
 * @property lastModified 画像の応答の `Last-Modified`。無ければ null
 * @property bytes 取ったときのバイト列のまま。空でない
 */
internal class CachedImage(val url: String, val eTag: String?, val lastModified: String?, val bytes: ByteArray)

/**
 * 1 つのオリジンの favicon のキャッシュ。
 *
 * @property fetchedAt 取った時刻（取り直して変わっていないと分かったときは、その時刻）
 * @property expiresAt これより後は古いとみなして取り直す時刻
 * @property image 取れた画像。取れなかった（無し）なら null
 */
internal class CachedFavicon(val fetchedAt: Instant, val expiresAt: Instant, val image: CachedImage?)

/**
 * favicon のキャッシュのファイル。ローカルキャッシュのルートディレクトリ [cacheDirectory] の下の [FAVICON_DIRECTORY_NAME] に、
 * オリジンごとに 1 つのファイルで置く。名前はオリジン（[FaviconOrigin.toString]）の SHA-256 の 16 進。
 *
 * ファイルは、次を順に並べる（数はビッグエンディアン、文字列は長さ（4 バイト）と UTF-8 の組で、無ければ長さ 0）。
 * 1. 形の版（4 バイト。[FORMAT_VERSION]）
 * 2. 取った時刻（8 バイト。UNIX 時間のミリ秒）
 * 3. 期限の時刻（8 バイト。UNIX 時間のミリ秒）
 * 4. 画像を取りに行った URL（無しなら空）
 * 5. 画像の応答の `ETag`
 * 6. 画像の応答の `Last-Modified`
 * 7. 残りすべて：取ったときの画像のバイト列のまま（無しなら空）
 *
 * 書くときは、一時ファイルに書いてから置き換える。版が違う、短すぎる、長さが合わない、取れた画像なのに URL かバイト列が空、
 * 無しなのに URL かバイト列がある、といった形の崩れたファイルは、無いものとして扱う。
 *
 * 読み書きはブロックする。呼び出し側が I/O 用のスレッドで呼ぶ。ファイルの I/O の失敗と形の崩れは [log] に書き、
 * 読めなければ無いものとし、書けなければ何もしない。ファイルが無いことは失敗として書かない。
 */
internal class FaviconCache(cacheDirectory: Path, private val log: (String) -> Unit) {
    private val directory: Path = cacheDirectory.resolve(FAVICON_DIRECTORY_NAME)

    /** [origin] のキャッシュのファイル。 */
    fun fileOf(origin: FaviconOrigin): Path = directory.resolve(sha256Hex(origin.toString()))

    /** [origin] のキャッシュ。無いか、読めないか、形が崩れていれば null。 */
    fun read(origin: FaviconOrigin): CachedFavicon? {
        val bytes = try {
            Files.readAllBytes(fileOf(origin))
        } catch (e: NoSuchFileException) {
            return null
        } catch (e: IOException) {
            log("favicon のキャッシュを読めなかった: ${describeIoFailure(e)}")
            return null
        }
        return decodeCachedFavicon(bytes) ?: run {
            log("favicon のキャッシュのファイルの形が崩れている")
            null
        }
    }

    /** [origin] のキャッシュを [favicon] にする。 */
    fun write(origin: FaviconOrigin, favicon: CachedFavicon) {
        try {
            replaceFileAtomically(fileOf(origin), encodeCachedFavicon(favicon))
        } catch (e: IOException) {
            log("favicon のキャッシュを書けなかった: ${describeIoFailure(e)}")
        }
    }
}

private fun sha256Hex(text: String): String =
    MessageDigest.getInstance(HASH_ALGORITHM).digest(text.encodeToByteArray()).toHexString()

/** [favicon] を、キャッシュのファイルの中身にする。 */
internal fun encodeCachedFavicon(favicon: CachedFavicon): ByteArray {
    val image = favicon.image
    val texts = listOf(image?.url, image?.eTag, image?.lastModified).map { it.orEmpty().encodeToByteArray() }
    val body = image?.bytes ?: ByteArray(0)
    return ByteBuffer.allocate(FIXED_HEADER_BYTES + texts.sumOf { TEXT_LENGTH_BYTES + it.size } + body.size)
        .putInt(FORMAT_VERSION)
        .putLong(favicon.fetchedAt.toEpochMilliseconds())
        .putLong(favicon.expiresAt.toEpochMilliseconds())
        .apply { texts.forEach { putInt(it.size).put(it) } }
        .put(body)
        .array()
}

/** キャッシュのファイルの中身 [bytes] を読む。形が崩れていれば null。 */
internal fun decodeCachedFavicon(bytes: ByteArray): CachedFavicon? {
    if (bytes.size < FIXED_HEADER_BYTES) return null
    val buffer = ByteBuffer.wrap(bytes)
    if (buffer.getInt() != FORMAT_VERSION) return null
    val fetchedAt = Instant.fromEpochMilliseconds(buffer.getLong())
    val expiresAt = Instant.fromEpochMilliseconds(buffer.getLong())
    val url = buffer.getText() ?: return null
    val eTag = buffer.getText() ?: return null
    val lastModified = buffer.getText() ?: return null
    val body = ByteArray(buffer.remaining()).also { buffer.get(it) }
    val image = when {
        url.isEmpty() && body.isEmpty() && eTag.isEmpty() && lastModified.isEmpty() -> null
        url.isNotEmpty() && body.isNotEmpty() ->
            CachedImage(url, eTag.takeIf { it.isNotEmpty() }, lastModified.takeIf { it.isNotEmpty() }, body)
        else -> return null
    }
    return CachedFavicon(fetchedAt, expiresAt, image)
}

/** 長さと UTF-8 の組の文字列を読む。長さが残りに収まらないか、UTF-8 として読めなければ null。 */
private fun ByteBuffer.getText(): String? {
    if (remaining() < TEXT_LENGTH_BYTES) return null
    val length = getInt()
    if (length !in 0..remaining()) return null
    val text = ByteArray(length).also { get(it) }
    return try {
        text.decodeToString(throwOnInvalidSequence = true)
    } catch (e: CharacterCodingException) {
        null
    }
}
