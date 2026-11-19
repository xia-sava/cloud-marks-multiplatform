package to.sava.cloudmarksmultiplatform.update

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 適用を試みた時刻。 */
private val ATTEMPTED_AT: LocalDateTime = LocalDateTime.parse("2026-09-02T12:00:00")

/** 適用を試みたバージョン。 */
private const val VERSION = "1.2.3"

/** 動いているアプリのバージョン。適用を試みる前のもの。 */
private const val RUNNING_VERSION = "1.2.2"

class UpdateApplyLogTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val logs = CopyOnWriteArrayList<String>()

    /** ログのディレクトリ。まだ無い。 */
    private val logDirectory: Path by lazy { temporaryFolder.root.toPath().resolve("logs") }

    /** 次の起動へ持ち越す記録のディレクトリ。まだ無い。 */
    private val stateDirectory: Path by lazy { temporaryFolder.root.toPath().resolve("state") }

    private val applyLog: UpdateApplyLog by lazy { UpdateApplyLog(logDirectory, stateDirectory) { logs += it } }

    /** 適用するスクリプトが書いたことにする行を、ログのファイルに書く。 */
    private fun writeResultLog(vararg lines: String) {
        logDirectory.createDirectories()
        applyLog.resultLogFile.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    // ログはログのディレクトリの update-apply.log、記録は次の起動へ持ち越す記録のディレクトリの update-apply-attempt.txt
    @Test
    fun placesFilesInLogAndStateDirectories() {
        assertEquals(logDirectory.resolve("update-apply.log"), applyLog.resultLogFile)
        assertEquals(stateDirectory.resolve("update-apply-attempt.txt"), applyLog.attemptFile)
    }

    // インストーラへ渡す前に、ログのディレクトリと記録のディレクトリを作る
    @Test
    fun prepareDirectoriesCreatesBoth() {
        applyLog.prepareDirectories()

        assertTrue(Files.isDirectory(logDirectory))
        assertTrue(Files.isDirectory(stateDirectory))
    }

    // 記録は、バージョンと秒までの時刻を書く（スクリプトが書く時刻も秒まで）。ディレクトリが無ければ作り、書き損じの一時ファイルを残さない
    @Test
    fun recordWritesVersionAndTimeToSeconds() {
        applyLog.record(VERSION, LocalDateTime.parse("2026-09-02T12:00:00.987"))

        assertEquals("1.2.3\t2026-09-02T12:00:00", applyLog.attemptFile.readText())
        assertEquals(listOf(applyLog.attemptFile), Files.list(stateDirectory).use { it.toList() })
    }

    // 記録を書けなくても投げず、ログに書く（適用は進められる）
    @Test
    fun recordFailureIsLogged() {
        Files.writeString(stateDirectory, "記録のディレクトリの場所にあるファイル")

        applyLog.record(VERSION, ATTEMPTED_AT)

        assertTrue(logs.any { "記録できなかった" in it }, logs.toString())
    }

    // 試みていなければ、何も伝えない
    @Test
    fun tellsNothingWithoutAttempt() {
        writeResultLog("2026-09-02T12:00:01 setup failed with exit code 1")

        assertNull(applyLog.takeFailedVersionName(RUNNING_VERSION))
    }

    // 失敗していたら、試みたバージョンを伝える
    @Test
    fun tellsWhichVersionFailed() {
        applyLog.record(VERSION, ATTEMPTED_AT)
        writeResultLog("2026-09-02T12:00:01 setup failed with exit code 1")

        assertEquals(VERSION, applyLog.takeFailedVersionName(RUNNING_VERSION))
    }

    // インストーラを起動できなかったことも失敗。理由の行はシステムのコードページで書かれうるが、UTF-8 として読めなくても読み進める
    @Test
    fun treatsUnstartedSetupAsFailureEvenInSystemCodePage() {
        applyLog.record(VERSION, ATTEMPTED_AT)
        logDirectory.createDirectories()
        applyLog.resultLogFile.writeBytes("2026-09-02T12:00:01 setup could not be started: アクセスが拒否されました。\n".toByteArray(Charset.forName("windows-31j")))

        assertEquals(VERSION, applyLog.takeFailedVersionName(RUNNING_VERSION))
    }

    // 適用できていたら、何も伝えない
    @Test
    fun tellsNothingWhenApplied() {
        applyLog.record(VERSION, ATTEMPTED_AT)
        writeResultLog("2026-09-02T12:00:01 $APPLY_SUCCEEDED")

        assertNull(applyLog.takeFailedVersionName(RUNNING_VERSION))
        assertFalse(applyLog.attemptFile.exists())
    }

    // 試みたバージョンが動いていれば、結果の行が失敗でも適用できているので、何も伝えずに記録を消す（失敗のあとで手で入れ直したとき）
    @Test
    fun treatsAttemptOfRunningVersionAsApplied() {
        applyLog.record(VERSION, ATTEMPTED_AT)
        writeResultLog("2026-09-02T12:00:01 setup failed with exit code 1")

        assertNull(applyLog.takeFailedVersionName(runningVersionName = VERSION))
        assertFalse(applyLog.attemptFile.exists())
    }

    // 試みたバージョンが動いていれば、結果の行がまだ無くても、記録を消す
    @Test
    fun discardsAttemptOfRunningVersionWithoutOutcome() {
        applyLog.record(VERSION, ATTEMPTED_AT)

        assertNull(applyLog.takeFailedVersionName(runningVersionName = VERSION))
        assertFalse(applyLog.attemptFile.exists())
    }

    // スクリプトはアプリの終了を待ってから走る。結果がまだ書かれていなければ失敗とせず、記録の時刻より前の結果も数えない
    @Test
    fun waitsForOutcomeOfThisAttempt() {
        applyLog.record(VERSION, ATTEMPTED_AT)
        writeResultLog("2026-09-02T11:59:59 setup failed with exit code 1")

        assertNull(applyLog.takeFailedVersionName(RUNNING_VERSION))
        assertTrue(applyLog.attemptFile.exists())
    }

    // 結果がまだ書かれていなければ記録を残し、書かれたあとの起動で伝える
    @Test
    fun keepsAttemptUntilOutcomeIsWritten() {
        applyLog.record(VERSION, ATTEMPTED_AT)
        assertNull(applyLog.takeFailedVersionName(RUNNING_VERSION))

        writeResultLog("2026-09-02T12:00:01 setup failed with exit code 1")

        assertEquals(VERSION, applyLog.takeFailedVersionName(RUNNING_VERSION))
    }

    // 試みたのと同じ秒に書かれた結果も、この試みの結果として数える（記録の時刻は秒より下を切り捨てる）
    @Test
    fun countsOutcomeWrittenInSameSecond() {
        applyLog.record(VERSION, LocalDateTime.parse("2026-09-02T12:00:00.500"))
        writeResultLog("2026-09-02T12:00:00 setup failed with exit code 1")

        assertEquals(VERSION, applyLog.takeFailedVersionName(RUNNING_VERSION))
    }

    // 同じ失敗を 2 度は伝えない
    @Test
    fun doesNotTellSameFailureTwice() {
        applyLog.record(VERSION, ATTEMPTED_AT)
        writeResultLog("2026-09-02T12:00:01 setup failed with exit code 1")
        applyLog.takeFailedVersionName(RUNNING_VERSION)

        assertNull(applyLog.takeFailedVersionName(RUNNING_VERSION))
    }

    // この試みの最後の結果を読む。失敗のあとで成功していれば、何も伝えない
    @Test
    fun readsLastOutcomeOfAttempt() {
        applyLog.record(VERSION, ATTEMPTED_AT)
        writeResultLog(
            "2026-09-02T12:00:01 setup failed with exit code 1",
            "2026-09-02T12:00:02 $APPLY_SUCCEEDED",
        )

        assertNull(applyLog.takeFailedVersionName(RUNNING_VERSION))
    }

    // 時刻として読めない行（時刻の無い行と、頭に BOM（U+FEFF）が付いた行）は、どの試みのものか決められないので数えない
    @Test
    fun ignoresLinesWithoutTime() {
        applyLog.record(VERSION, ATTEMPTED_AT)
        writeResultLog("setup failed with exit code 1", "${Char(0xFEFF)}2026-09-02T12:00:01 setup failed with exit code 1")

        assertNull(applyLog.takeFailedVersionName(RUNNING_VERSION))
    }
}
