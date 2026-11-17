package to.sava.cloudmarksmultiplatform.host

import to.sava.cloudmarksmultiplatform.browser.OpenTabsCommand
import to.sava.cloudmarksmultiplatform.browser.OpenTabsCommandParseResult
import to.sava.cloudmarksmultiplatform.browser.commandFileOf
import to.sava.cloudmarksmultiplatform.browser.parseOpenTabsCommand
import to.sava.cloudmarksmultiplatform.files.describeIoFailure
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * [commandDirectory] の、トークンが [token] のコマンドのファイル（[commandFileOf]）を読み、コマンドとして返してファイルを消す。
 * [token] は検証済みの形でなければならない。
 *
 * ファイルが無いか、読んだ後で別のホストが先に消していれば null（同じコマンドを 2 回返さない）。
 * 読めない、中身がコマンドの形に合わない（[parseOpenTabsCommand]）、応答が [MAX_RESPONSE_BYTES] を超えるときは、
 * ファイルを消さず、理由を [log] に書いて null を返す。消せなかったときは、[log] に書いてコマンドを返す。
 * ログには、コマンドの中の URL とタイトルを書かない。
 *
 * @param deleteIfExists ファイルを消す。消したら true、既に無ければ false
 */
internal fun takeCommandFile(
    commandDirectory: Path,
    token: String,
    log: (String) -> Unit,
    deleteIfExists: (Path) -> Boolean = Files::deleteIfExists,
): OpenTabsCommand? {
    val file = commandFileOf(commandDirectory, token)
    val body = try {
        Files.readAllBytes(file)
    } catch (e: NoSuchFileException) {
        return null
    } catch (e: IOException) {
        log(HOST_LOG_PREFIX + "コマンドのファイルを読めなかった: ${describeIoFailure(e)}")
        return null
    }
    val command = when (val parsed = parseOpenTabsCommand(body)) {
        is OpenTabsCommandParseResult.Valid -> parsed.command
        is OpenTabsCommandParseResult.Invalid -> {
            log(HOST_LOG_PREFIX + "コマンドの形に合わない: ${parsed.reason}")
            return null
        }
    }
    val responseBytes = encodeHostResponse(HostResponse.CommandTaken(command)).size
    if (responseBytes > MAX_RESPONSE_BYTES) {
        log(HOST_LOG_PREFIX + "コマンドの応答（$responseBytes バイト）が上限を超えた")
        return null
    }
    val deleted = try {
        deleteIfExists(file)
    } catch (e: IOException) {
        log(HOST_LOG_PREFIX + "コマンドのファイルを消せなかった: ${describeIoFailure(e)}")
        true
    }
    return command.takeIf { deleted }
}
