package to.sava.cloudmarksmultiplatform.host

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

private const val INSTALLED_LAUNCHER = """C:\Users\me\AppData\Local\Programs\Cloud Marks\CloudMarks.exe"""

/** 起動の本体が、起動したときに返す値。 */
private val CREATED: ProcessLaunchResult.Failed? = null

/** `CreateProcessW` の代わり。渡された要求を [requests] に残し、[results] を順に返す。 */
private class FakeCreateProcess(vararg results: ProcessLaunchResult.Failed?) {
    private val remaining = ArrayDeque(results.toList())

    val requests = mutableListOf<CreateProcessRequest>()

    fun create(request: CreateProcessRequest): ProcessLaunchResult.Failed? {
        requests += request
        return remaining.removeFirst()
    }
}

class AppLaunchTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val accessDenied = ProcessLaunchResult.Failed(CREATE_PROCESS, ERROR_ACCESS_DENIED)

    /** 起動する実行ファイル。空白を含むディレクトリの中に置く（ファイルは作らない）。 */
    private val executable: Path by lazy { temporaryFolder.newFolder("Cloud Marks").toPath().resolve("CloudMarks.exe") }

    private fun launch(fake: FakeCreateProcess, arguments: List<String> = emptyList()): ProcessLaunchResult =
        launchOutsideJob(executable, arguments, fake::create)

    // 空白を含むパスを " で囲む。引数が無ければ、それだけ
    @Test
    fun commandLineQuotesExecutable() {
        assertEquals("\"$INSTALLED_LAUNCHER\"", commandLineOf(INSTALLED_LAUNCHER))
    }

    // 引数は、空白で区切って手を加えずに後ろに足す
    @Test
    fun commandLineAppendsArgumentsAsTheyAre() {
        assertEquals(
            """"$INSTALLED_LAUNCHER" /c cd > "C:\Temp Dir\out.txt"""",
            commandLineOf(INSTALLED_LAUNCHER, listOf("/c", "cd", ">", """"C:\Temp Dir\out.txt"""")),
        )
    }

    // ホストの起動口と同じディレクトリの CloudMarks.exe。Windows のパスは大文字小文字によらず等しくなるので、文字列で比べる
    @Test
    fun appLauncherIsBesideHost() {
        val directory = temporaryFolder.newFolder("Programs", "Cloud Marks").toPath()

        val launcher = appLauncherBesideHost(directory.resolve("cloud-marks-host.exe").toString())

        assertEquals(directory.resolve("CloudMarks.exe").toString(), launcher.toString())
    }

    // ジョブから抜けるフラグだけを付け、実行ファイルの絶対パス、" で囲んだコマンドライン、実行ファイルのディレクトリで、1 回だけ起動する。
    // 結果は、ジョブから抜けて起動したもの
    @Test
    fun launchesOnceWithBreakaway() {
        val fake = FakeCreateProcess(CREATED)

        val result = launch(fake)

        assertEquals(ProcessLaunchResult.Launched(brokeAwayFromJob = true), result)
        assertEquals(
            listOf(
                CreateProcessRequest(
                    applicationName = executable.toString(),
                    commandLine = "\"$executable\"",
                    currentDirectory = executable.parent.toString(),
                    creationFlags = CREATE_BREAKAWAY_FROM_JOB,
                ),
            ),
            fake.requests,
        )
    }

    // 相対パスは、絶対パスにしてから使う
    @Test
    fun relativeExecutableIsMadeAbsolute() {
        val fake = FakeCreateProcess(CREATED)
        val relative = Path.of("CloudMarks.exe")

        launchOutsideJob(relative, createProcess = fake::create)

        val absolute = relative.toAbsolutePath()
        assertEquals(absolute.toString(), fake.requests.single().applicationName)
        assertEquals("\"$absolute\"", fake.requests.single().commandLine)
        assertEquals(absolute.parent.toString(), fake.requests.single().currentDirectory)
    }

    // 引数は、コマンドラインの実行ファイルの後ろに足す
    @Test
    fun argumentsFollowExecutable() {
        val fake = FakeCreateProcess(CREATED)

        launch(fake, listOf("/c", "exit"))

        assertEquals("\"$executable\" /c exit", fake.requests.single().commandLine)
    }

    // アクセス拒否なら、フラグを外し、ほかは同じ要求でもう一度起動する。結果は、ジョブから抜けずに起動したもの
    @Test
    fun accessDeniedRetriesWithoutBreakaway() {
        val fake = FakeCreateProcess(accessDenied, CREATED)

        val result = launch(fake)

        assertEquals(ProcessLaunchResult.Launched(brokeAwayFromJob = false), result)
        assertEquals(listOf(CREATE_BREAKAWAY_FROM_JOB, 0), fake.requests.map { it.creationFlags })
        assertEquals(fake.requests[0].copy(creationFlags = 0), fake.requests[1])
    }

    // 呼び直しても失敗したら、その結果を返し、それより呼ばない
    @Test
    fun retriesOnlyOnce() {
        val fake = FakeCreateProcess(accessDenied, accessDenied)

        val result = launch(fake)

        assertEquals(accessDenied, result)
        assertEquals(2, fake.requests.size)
    }

    // 呼び直したあとの失敗は、アクセス拒否でなくてもそのまま返す
    @Test
    fun retryFailureIsReturned() {
        val notFound = ProcessLaunchResult.Failed(CREATE_PROCESS, 2)
        val fake = FakeCreateProcess(accessDenied, notFound)

        assertEquals(notFound, launch(fake))
        assertEquals(2, fake.requests.size)
    }

    // アクセス拒否のほかの失敗では、呼び直さない
    @Test
    fun otherFailuresAreNotRetried() {
        val failures = listOf(
            ProcessLaunchResult.Failed(CREATE_PROCESS, 2),
            ProcessLaunchResult.Failed(CREATE_PROCESS, 3),
            ProcessLaunchResult.Failed(CREATE_PROCESS, lastError = null, reason = "IllegalArgumentException: kernel32.dll"),
            ProcessLaunchResult.Failed("OtherFunction", ERROR_ACCESS_DENIED),
        )
        failures.forEach { failure ->
            val fake = FakeCreateProcess(failure)

            assertEquals(failure, launch(fake))
            assertEquals(1, fake.requests.size, "$failure")
        }
    }
}
