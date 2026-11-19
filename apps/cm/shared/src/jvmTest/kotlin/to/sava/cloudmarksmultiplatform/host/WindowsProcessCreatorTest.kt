package to.sava.cloudmarksmultiplatform.host

import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** 存在しないファイル（`GetLastError` の値）。 */
private const val ERROR_FILE_NOT_FOUND = 2

/** 引数が正しくない（`GetLastError` の値）。 */
private const val ERROR_INVALID_PARAMETER = 87

/** コンソールを持たずに起動する。[CREATE_NEW_CONSOLE] と両立しない。 */
private const val DETACHED_PROCESS = 0x00000008

/** 新しいコンソールを作って起動する。[DETACHED_PROCESS] と両立しない。 */
private const val CREATE_NEW_CONSOLE = 0x00000010

/** 起動したプロセスが書くファイルを待つ時間の上限。 */
private val OUTPUT_TIMEOUT = 10.seconds

/** 起動したプロセスが書くファイルを見に行く間隔。 */
private val OUTPUT_POLL_INTERVAL = 50.milliseconds

/**
 * `CreateProcessW` を実際に呼んで、ウィンドウを出さずにすぐ終わる `cmd.exe` を起動する。
 * Windows でだけ走るものと、Windows でないときだけ走るものがある。
 */
class WindowsProcessCreatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    private fun assumeWindows() {
        assumeTrue("CreateProcessW は Windows でだけ呼べる", isWindows)
    }

    /** `%SystemRoot%\System32\cmd.exe`。 */
    private val commandInterpreter: Path by lazy { Path.of(System.getenv("SystemRoot"), "System32", "cmd.exe") }

    // cmd.exe を、すぐ終わる引数で起動できる。ジョブから抜けられたかは、テストを走らせる環境のジョブによる
    @Test
    fun launchesCommandInterpreter() {
        assumeWindows()

        assertIs<ProcessLaunchResult.Launched>(launchOutsideJob(commandInterpreter, listOf("/c", "exit")))
    }

    // 起動したプロセスは、コマンドラインの引数を受け取り、実行ファイルのディレクトリを作業ディレクトリにして動く
    @Test
    fun launchedProcessRunsInExecutableDirectory() {
        assumeWindows()
        val output = temporaryFolder.root.toPath().resolve("current-directory.txt")

        val result = launchOutsideJob(commandInterpreter, listOf("/c", "cd", ">", "\"$output\""))

        assertIs<ProcessLaunchResult.Launched>(result)
        assertEquals(commandInterpreter.parent.toString().lowercase(), awaitLine(output).lowercase())
    }

    // 要求のフラグは CreateProcessW に届く。両立しないフラグ（DETACHED_PROCESS と CREATE_NEW_CONSOLE）を渡すと、
    // 引数が正しくないとして失敗する
    @Test
    fun creationFlagsReachCreateProcess() {
        assumeWindows()
        val request = CreateProcessRequest(
            applicationName = commandInterpreter.toString(),
            commandLine = commandLineOf(commandInterpreter.toString(), listOf("/c", "exit")),
            currentDirectory = commandInterpreter.parent.toString(),
            creationFlags = DETACHED_PROCESS or CREATE_NEW_CONSOLE,
        )

        val result = WindowsProcessCreator.create(request)

        assertEquals(ProcessLaunchResult.Failed(CREATE_PROCESS, ERROR_INVALID_PARAMETER), result)
    }

    // 無い実行ファイルは、CreateProcessW と GetLastError の値を添えて失敗する。デスクトップのアプリを起動する形（引数なし）で確かめる
    @Test
    fun missingExecutableFails() {
        assumeWindows()
        val launch: LaunchOutsideJob = ::launchOutsideJob

        val result = launch(temporaryFolder.root.toPath().resolve("missing.exe"))

        assertEquals(ProcessLaunchResult.Failed(CREATE_PROCESS, ERROR_FILE_NOT_FOUND), result)
    }

    // Windows でなければ、関数を呼べないものとして、GetLastError の値の無い失敗になる
    @Test
    fun unavailableOutsideWindows() {
        assumeFalse("Windows でないときだけ確かめる", isWindows)

        val result = assertIs<ProcessLaunchResult.Failed>(launchOutsideJob(Path.of("missing.exe")))

        assertEquals(CREATE_PROCESS, result.function)
        assertNull(result.lastError)
        assertNotNull(result.reason)
    }

    /** 起動したプロセスが [file] に書いた 1 行（改行を除く）。書き終わるまで、[OUTPUT_TIMEOUT] まで待つ。 */
    private fun awaitLine(file: Path): String {
        val deadline = TimeSource.Monotonic.markNow() + OUTPUT_TIMEOUT
        while (deadline.hasNotPassedNow()) {
            completedLine(file)?.let { return it }
            Thread.sleep(OUTPUT_POLL_INTERVAL.inWholeMilliseconds)
        }
        throw AssertionError("$OUTPUT_TIMEOUT 待っても、起動したプロセスが ${file.fileName} に書き終えなかった")
    }

    /** [file] に改行まで書かれていれば、その行。まだ無いか、書いている途中なら null。 */
    private fun completedLine(file: Path): String? =
        try {
            Files.readString(file).takeIf { it.endsWith("\n") }?.trimEnd()
        } catch (e: IOException) {
            // まだ無いか、起動したプロセスが開いている
            null
        }
}
