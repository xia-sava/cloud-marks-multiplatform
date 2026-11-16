package to.sava.cloudmarksmultiplatform.logging

import to.sava.cloudmarksmultiplatform.files.describeIoFailure
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.time.Clock

/** ログのファイルの名前。 */
private const val LOG_FILE_NAME = "cloudmarks.log"

/** 切り替えたときに 1 世代だけ残す、前のログのファイルの名前。 */
private const val PREVIOUS_LOG_FILE_NAME = "cloudmarks.log.1"

/** 1 KiB のバイト数。 */
private const val KIBIBYTE = 1024L

/** ログのファイルの大きさの上限（バイト数）の既定。1 MiB。 */
private const val DEFAULT_MAX_LOG_BYTES = KIBIBYTE * KIBIBYTE

/**
 * ファイルへ 1 行ずつ追記するログ。後から調べるための記録で、利用者には見せない。
 *
 * 1 行は、書いた日時（UTC の RFC 3339）と [write] に渡した文を、空白でつないだもの。
 * 追記するといまのファイルが [maxBytes] を超えるときは、いまのファイルを前のログのファイルへ替えてから
 * （それまでの前のログのファイルは消える）、新しいファイルに書く。残すのは 1 世代だけ。
 *
 * 認証情報・シークレットを渡さない。項目のタイトルや URL など、S3 から来た文字列も渡さない。
 * 書けなかったときは呼び出し元へ投げず、標準エラーへ出す。複数のスレッドから呼べる。書き込みはブロックする。
 *
 * @param directory ログのファイルを置くディレクトリ。無ければ、書くときに作る
 * @param maxBytes ログのファイルの大きさの上限（バイト数）
 * @param clock 行の日時に使う時計
 */
class FileLog(
    private val directory: Path,
    private val maxBytes: Long = DEFAULT_MAX_LOG_BYTES,
    private val clock: Clock = Clock.System,
) {
    private val current: Path = directory.resolve(LOG_FILE_NAME)
    private val previous: Path = directory.resolve(PREVIOUS_LOG_FILE_NAME)
    private val lock = Any()

    /** [message] を 1 行として追記する。 */
    fun write(message: String) {
        val line = "${clock.now()} $message\n".encodeToByteArray()
        synchronized(lock) {
            try {
                Files.createDirectories(directory)
                if (exceedsLimit(line.size)) Files.move(current, previous, StandardCopyOption.REPLACE_EXISTING)
                Files.write(current, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            } catch (e: IOException) {
                System.err.println("ログを書けなかった: ${describeIoFailure(e)}")
            }
        }
    }

    /** いまのファイルに [incoming] バイトを足すと、上限を超えるか。ファイルが無いか空なら、超えないとする。 */
    private fun exceedsLimit(incoming: Int): Boolean {
        val size = try {
            Files.size(current)
        } catch (e: NoSuchFileException) {
            return false
        }
        return size > 0 && size + incoming > maxBytes
    }
}
