package to.sava.cloudmarksmultiplatform.browser

import to.sava.cloudmarksmultiplatform.files.describeIoFailure
import to.sava.cloudmarksmultiplatform.files.replaceFileAtomically
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** [COMMAND_FILE_SUFFIX] で終わるファイルに合う、ディレクトリの中を探すときの形。 */
private const val COMMAND_FILE_GLOB = "*$COMMAND_FILE_SUFFIX"

/** 配布版の、呼び出しの URL のホスト。`contracts/native-messaging/identities.json` の `distributed.callUrlHost`。 */
private const val DISTRIBUTED_KNOCK_HOST = "cmm.invalid"

/** 開発版の、呼び出しの URL のホスト。`contracts/native-messaging/identities.json` の `dev.callUrlHost`。 */
private const val DEV_KNOCK_HOST = "cmm-dev.invalid"

/** 新しいコマンドを書くときに消す、古いコマンドのファイルの、最後に書き換えてからの時間。 */
internal val STALE_COMMAND_AGE: Duration = 1.hours

/**
 * トークンが [token] のコマンドを、拡張機能に受け取らせる呼び出しの URL。配布版（[distributed] が真）は配布版の拡張機能の、
 * 開発版は開発版の拡張機能のもの。配布版の拡張機能は配布版の、開発版の拡張機能は開発版の呼び出しの URL を開いたタブだけを見つける。
 */
internal fun knockUrl(token: String, distributed: Boolean): String =
    "https://${if (distributed) DISTRIBUTED_KNOCK_HOST else DEV_KNOCK_HOST}/knock?token=$token"

/**
 * [directory] に、トークンが [token] のコマンド [command] のファイルを書く（中身は [OpenTabsCommand.encode]）。
 * 同じディレクトリの一時ファイルに書いてから置き換えるので、ホストが書きかけのファイルを読むことはない。ディレクトリが無ければ作る。
 * 書き込みはブロックする。
 *
 * @throws IOException 書けなかった
 */
internal fun writeCommandFile(directory: Path, token: String, command: OpenTabsCommand) {
    replaceFileAtomically(commandFileOf(directory, token), command.encode())
}

/**
 * [directory] の、名前が [COMMAND_FILE_SUFFIX] で終わり、最後に書き換えてから [now] までに [STALE_COMMAND_AGE] より長くたったファイルを消す。
 * ディレクトリが無ければ何もしない。ディレクトリを読めないときと、消せないファイルがあったときは、[log] に書く（消せるものは消し続ける）。
 * 読み込みと削除はブロックする。
 */
internal fun deleteStaleCommandFiles(directory: Path, now: Instant, log: (String) -> Unit) {
    val threshold = now - STALE_COMMAND_AGE
    try {
        Files.newDirectoryStream(directory, COMMAND_FILE_GLOB).use { files ->
            files.filter { Files.isRegularFile(it) }.forEach { file -> deleteIfOlder(file, threshold, log) }
        }
    } catch (e: NoSuchFileException) {
        return
    } catch (e: IOException) {
        log("古いコマンドのファイルを探せなかった: ${describeIoFailure(e)}")
    } catch (e: DirectoryIteratorException) {
        log("古いコマンドのファイルを探せなかった: ${e.cause?.let(::describeIoFailure)}")
    }
}

/** [file] を、最後に書き換えたのが [threshold] より前なら消す。消せなければ [log] に書く。 */
private fun deleteIfOlder(file: Path, threshold: Instant, log: (String) -> Unit) {
    try {
        val modified = Instant.fromEpochMilliseconds(Files.getLastModifiedTime(file).toMillis())
        if (modified < threshold) Files.deleteIfExists(file)
    } catch (e: NoSuchFileException) {
        // 調べているあいだに、ホストが受け取って消した
        return
    } catch (e: IOException) {
        log("古いコマンドのファイルを消せなかった: ${describeIoFailure(e)}")
    }
}
