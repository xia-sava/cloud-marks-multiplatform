package to.sava.cloudmarksmultiplatform.update

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** SHA-256 を求めるときに一度に読む大きさ。配布物は数十 MB あるので、全体をメモリに載せない。 */
private const val DIGEST_BUFFER_BYTES = 64 * 1024

/**
 * [file] の SHA-256 を、小文字の 16 進で返す。読み込みはブロックする。
 *
 * @throws IOException 読めなかった
 */
internal fun sha256HexOf(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DIGEST_BUFFER_BYTES)
    Files.newInputStream(file).buffered().use { input ->
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHexString()
}

/**
 * ダウンロードした [file] の SHA-256 が [expectedSha256]（小文字の 16 進）と同じか。
 * [expectedSha256] が空白だけなら、読まずに偽を返す（確かめずに進む道を作らない）。
 *
 * @throws IOException 読めなかった
 */
internal fun matchesSha256(file: Path, expectedSha256: String): Boolean =
    expectedSha256.isNotBlank() && sha256HexOf(file) == expectedSha256
