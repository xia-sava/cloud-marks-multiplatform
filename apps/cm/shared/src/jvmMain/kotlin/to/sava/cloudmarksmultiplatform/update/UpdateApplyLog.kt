package to.sava.cloudmarksmultiplatform.update

import to.sava.cloudmarksmultiplatform.files.describeIoFailure
import to.sava.cloudmarksmultiplatform.files.replaceFileAtomically
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/** 適用するスクリプトが結果を書くログのファイルの名前。ログのディレクトリに置く。 */
private const val APPLY_LOG_FILE_NAME = "update-apply.log"

/** 適用を試みたことの記録のファイルの名前。次の起動へ持ち越す記録のディレクトリに置く。 */
private const val APPLY_ATTEMPT_FILE_NAME = "update-apply-attempt.txt"

/** 適用できたときに、適用するスクリプトがログに書く文。 */
internal const val APPLY_SUCCEEDED = "setup succeeded"

/** 記録の中で、バージョンと時刻を分ける文字。バージョンには現れない。 */
private const val ATTEMPT_SEPARATOR = "\t"

/** 適用を試みたことの記録。どのバージョンを、いつインストーラへ渡したか。 */
internal data class UpdateApplyAttempt(val versionName: String, val at: LocalDateTime)

/** 適用した結果。 */
internal enum class ApplyOutcome { SUCCEEDED, FAILED }

/**
 * 適用するスクリプトのログ [log] に書かれた結果のうち、時刻が [after] より前でない最後のもの。まだ書かれていなければ null。
 * [APPLY_SUCCEEDED] の行なら成功、ほかの行なら失敗。時刻として読めない行は、どの試みのものか決められないので数えない。
 */
internal fun lastApplyOutcome(log: String, after: LocalDateTime): ApplyOutcome? =
    log.lineSequence()
        .mapNotNull(::applyLogEntry)
        .filter { (at, _) -> !at.isBefore(after) }
        .lastOrNull()
        ?.let { (_, text) -> if (text == APPLY_SUCCEEDED) ApplyOutcome.SUCCEEDED else ApplyOutcome.FAILED }

/** ログの 1 行から、書いた時刻と中身を取り出す。行の頭が時刻として読めなければ null。 */
private fun applyLogEntry(line: String): Pair<LocalDateTime, String>? {
    val at = line.substringBefore(' ', missingDelimiterValue = "")
    return try {
        LocalDateTime.parse(at) to line.substringAfter(' ', missingDelimiterValue = "").trim()
    } catch (e: DateTimeParseException) {
        null
    }
}

/**
 * 更新を適用した成否の置き場所。
 *
 * 適用しているあいだアプリは終了しているので、成否は決まった場所に残し、次の起動で読む。インストーラへ渡すときに、試みたバージョンと時刻を
 * [attemptFile] に記録し（[record]）、適用するスクリプトは結果を [resultLogFile] に書き足す（[applyUpdateScript]）。
 * 次の起動で、2 つを突き合わせる（[takeFailedVersionName]）。
 *
 * 時刻は、スクリプトが書く時刻（PowerShell の `Get-Date -Format s`）と同じく、この PC の時刻帯の、秒までの日時。
 *
 * @param logDirectory ログのディレクトリ（[to.sava.cloudmarksmultiplatform.AppFiles.logDirectory]）。[resultLogFile] を置く
 * @param stateDirectory 次の起動へ持ち越す記録のディレクトリ（[to.sava.cloudmarksmultiplatform.AppFiles.stateDirectory]）。
 * [attemptFile] を置く
 * @param log 記録を書けなかったときなどの細かい理由を書く
 */
internal class UpdateApplyLog(
    logDirectory: Path,
    stateDirectory: Path,
    private val log: (String) -> Unit,
) {
    /** 適用するスクリプトが結果を書き足すファイル。 */
    val resultLogFile: Path = logDirectory.resolve(APPLY_LOG_FILE_NAME)

    /** 適用を試みたことの記録のファイル。 */
    val attemptFile: Path = stateDirectory.resolve(APPLY_ATTEMPT_FILE_NAME)

    /**
     * [resultLogFile] と [attemptFile] を置くディレクトリを作る。インストーラへ渡す前に呼ぶ。
     * 適用するスクリプトは、ログのディレクトリが無いと結果を書けず、起動し直すところまで進まない。
     *
     * @throws IOException 作れなかった
     */
    fun prepareDirectories() {
        resultLogFile.parent?.let { Files.createDirectories(it) }
        attemptFile.parent?.let { Files.createDirectories(it) }
    }

    /**
     * [versionName] をこれから適用することを、時刻 [at]（秒より下は切り捨てる）とともに記録する。前の記録は置き換える。
     * 書けなければログに書いて続ける。失うのは、次の起動で成否を伝える手立てだけで、適用はできる。
     */
    fun record(versionName: String, at: LocalDateTime = LocalDateTime.now()) {
        val line = versionName + ATTEMPT_SEPARATOR + DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(at.truncatedTo(ChronoUnit.SECONDS))
        try {
            replaceFileAtomically(attemptFile, line.encodeToByteArray())
        } catch (e: IOException) {
            log("更新を適用することを記録できなかった: ${describeIoFailure(e)}")
        }
    }

    /**
     * 前に試みた適用が失敗していたら、そのバージョン。失敗していなければ null。
     *
     * 試みたバージョンが、動いているアプリのバージョン [runningVersionName] と同じなら、適用できているので、記録を消して null を返す。
     * インストーラが失敗したあとで利用者が手で入れ直したときに、適用できなかったと伝えない。
     * そうでなければ、記録の時刻より前でない結果がログに書かれていれば、記録を消す。同じ失敗を 2 度は伝えない。
     * 結果がまだ書かれていなければ、記録を残して null を返す。スクリプトはアプリの終了を待ってから走るので、書かれていないことは
     * 失敗を意味しない。
     */
    fun takeFailedVersionName(runningVersionName: String): String? {
        val attempt = readAttempt() ?: return null
        if (attempt.versionName == runningVersionName) {
            discardAttempt()
            return null
        }
        val outcome = lastApplyOutcome(readText(resultLogFile), attempt.at) ?: return null
        discardAttempt()
        return attempt.versionName.takeIf { outcome == ApplyOutcome.FAILED }
    }

    /** [attemptFile] の記録を消す。消せなければ、ログに書いて続ける。 */
    private fun discardAttempt() {
        try {
            Files.deleteIfExists(attemptFile)
        } catch (e: IOException) {
            log("更新を適用した記録を消せなかった: ${describeIoFailure(e)}")
        }
    }

    /** [attemptFile] の記録。無いか読めなければ null。 */
    private fun readAttempt(): UpdateApplyAttempt? {
        val recorded = readText(attemptFile).trim().ifEmpty { return null }
        val at = try {
            LocalDateTime.parse(recorded.substringAfterLast(ATTEMPT_SEPARATOR))
        } catch (e: DateTimeParseException) {
            log("更新を適用した記録の時刻を読めなかった")
            return null
        }
        return UpdateApplyAttempt(recorded.substringBeforeLast(ATTEMPT_SEPARATOR), at)
    }

    /**
     * [file] の中身を UTF-8 として読む。UTF-8 として読めないバイトは置き換え文字にする（スクリプトの書く理由の行は、システムの
     * コードページで書かれうる）。無ければ空。読めなければ、ログに書いて空。
     */
    private fun readText(file: Path): String =
        try {
            Files.readAllBytes(file).decodeToString()
        } catch (e: NoSuchFileException) {
            ""
        } catch (e: IOException) {
            log("更新を適用した結果を読めなかった: ${describeIoFailure(e)}")
            ""
        }
}
