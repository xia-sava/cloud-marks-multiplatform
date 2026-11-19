package to.sava.cloudmarksmultiplatform.host

import to.sava.cloudmarksmultiplatform.files.describeIllegalCaller
import to.sava.cloudmarksmultiplatform.files.describeThrowable
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.StructLayout
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.invoke.MethodHandle
import java.nio.charset.StandardCharsets.UTF_16LE
import java.nio.file.Path

/**
 * デスクトップのアプリのランチャーの exe の名前。配布物の名前（`desktopApp/build.gradle.kts` の `cloudMarksPackageName`）に
 * `.exe` を付けたもの。配ったら変えられない値（`docs/distribution.md` の「配ったら変えられないもの」）なので、ここに定数で持つ。
 */
private const val APP_LAUNCHER_FILE_NAME = "CloudMarks.exe"

private const val KERNEL32 = "kernel32.dll"

/** プロセスを起動する関数の名前。失敗の結果（[ProcessLaunchResult.Failed.function]）にも使う。 */
internal const val CREATE_PROCESS = "CreateProcessW"
private const val CLOSE_HANDLE = "CloseHandle"

/** [Linker.Option.captureCallState] で取る値の名前。 */
private const val GET_LAST_ERROR = "GetLastError"

/**
 * 起動するプロセスを、呼び出したプロセスのジョブに入れない。呼び出したプロセスがジョブに入っていなければ、何も変わらない。
 * ジョブが抜けることを許していなければ（`JOB_OBJECT_LIMIT_BREAKAWAY_OK` が無ければ）、[ERROR_ACCESS_DENIED] で失敗する。
 */
internal const val CREATE_BREAKAWAY_FROM_JOB = 0x01000000

/** アクセスが拒否された（`GetLastError` の値）。 */
internal const val ERROR_ACCESS_DENIED = 5

/** `BOOL` の偽。 */
private const val FALSE = 0

/** `STARTUPINFOW` と `PROCESS_INFORMATION` の、値を読み書きする欄の名前。 */
private const val CB = "cb"
private const val H_PROCESS = "hProcess"
private const val H_THREAD = "hThread"

/** 実行ファイルを起動した結果。 */
internal sealed interface ProcessLaunchResult {
    /**
     * 起動した。起動したプロセスの終わりも、ウィンドウが出ることも待っていない。
     *
     * @property brokeAwayFromJob [CREATE_BREAKAWAY_FROM_JOB] を付けて起動できたか。偽なら、アクセス拒否のあとフラグを外して
     * 起動し直したもので、起動したプロセスはこのプロセスのジョブに残っている
     */
    data class Launched(val brokeAwayFromJob: Boolean) : ProcessLaunchResult

    /**
     * 起動できなかった。[function] は失敗した関数の名前、[lastError] はそのときの `GetLastError` の値。
     * 関数を呼ぶ前に失敗した（DLL を読み込めない、ネイティブの関数の呼び出しが許されていない、など）ときは、
     * [lastError] が null で、[reason] にその説明が入る。どれもパスを含まない。
     */
    data class Failed(val function: String, val lastError: Int?, val reason: String? = null) : ProcessLaunchResult
}

/**
 * `CreateProcessW` に渡す値のうち、起動するごとに変わるもの。
 *
 * @property applicationName `lpApplicationName`。実行ファイルの絶対パス
 * @property commandLine `lpCommandLine`。[commandLineOf] で組み立てたもの
 * @property currentDirectory `lpCurrentDirectory`。null なら、このプロセスの作業ディレクトリを引き継ぐ
 * @property creationFlags `dwCreationFlags`
 */
internal data class CreateProcessRequest(
    val applicationName: String,
    val commandLine: String,
    val currentDirectory: String?,
    val creationFlags: Int,
)

/**
 * [CreateProcessRequest] のとおりにプロセスを起動する関数。起動したら null、起動できなければその失敗を返す。
 * 起動したプロセスを待たずに返る。例外を投げない。
 */
internal typealias CreateProcess = (request: CreateProcessRequest) -> ProcessLaunchResult.Failed?

/** 実行ファイルを、このプロセスのジョブから抜けて起動する関数。[launchOutsideJob] の形。 */
internal typealias LaunchOutsideJob = (executable: Path) -> ProcessLaunchResult

/**
 * ホストの起動口 [hostExecutable]（`cloud-marks-host.exe`。配布版のホストでの [to.sava.cloudmarksmultiplatform.AppPath.verified]）と
 * 同じディレクトリにある、デスクトップのアプリのランチャー（`CloudMarks.exe`）のパス。
 */
internal fun appLauncherBesideHost(hostExecutable: String): Path =
    Path.of(hostExecutable).resolveSibling(APP_LAUNCHER_FILE_NAME)

/**
 * `CreateProcessW` に渡すコマンドライン。[executable] を `"` で囲み、[arguments] を空白で区切って後ろに足す。
 * 引数には手を加えない。空白を含む引数は、呼び出し側が `"` で囲む。
 */
internal fun commandLineOf(executable: String, arguments: List<String> = emptyList()): String =
    (listOf("\"$executable\"") + arguments).joinToString(" ")

/**
 * 実行ファイル [executable] を、このプロセスのジョブから抜けて起動する。起動したプロセスの終わりを待たない。
 *
 * Firefox はホストをジョブに入れ、ホストが終わるとジョブの中のプロセスをまとめて終わらせる。ジョブに残ったまま起動したアプリは
 * それと一緒に終わるので、[CREATE_BREAKAWAY_FROM_JOB] を付けて起動する。ジョブが抜けることを許していないときの
 * アクセス拒否（[ERROR_ACCESS_DENIED]）でだけ、フラグを外してもう一度起動する。ほかの失敗では起動し直さない。
 *
 * コマンドラインは [commandLineOf]、作業ディレクトリは実行ファイルのディレクトリ。環境変数はこのプロセスのものを引き継ぎ、
 * ハンドルは引き継がない。ただし、コンソールの実行ファイルには、ハンドルを引き継がなくても Windows がこのプロセスの標準入出力を渡す。
 * デスクトップのアプリ（`CloudMarks.exe`）はウィンドウのアプリなので渡らない。
 *
 * @param executable 起動する実行ファイル。相対パスなら、絶対パスにしてから使う
 * @param arguments 実行ファイルの後ろに足す引数（[commandLineOf]）。デスクトップのアプリを起動するときは空
 * @param createProcess 起動の本体
 */
internal fun launchOutsideJob(
    executable: Path,
    arguments: List<String> = emptyList(),
    createProcess: CreateProcess = WindowsProcessCreator::create,
): ProcessLaunchResult {
    val application = executable.toAbsolutePath()
    val request = CreateProcessRequest(
        applicationName = application.toString(),
        commandLine = commandLineOf(application.toString(), arguments),
        currentDirectory = application.parent?.toString(),
        creationFlags = CREATE_BREAKAWAY_FROM_JOB,
    )
    val failure = createProcess(request) ?: return ProcessLaunchResult.Launched(brokeAwayFromJob = true)
    if (!failure.isAccessDenied()) return failure
    return createProcess(request.withoutBreakaway()) ?: ProcessLaunchResult.Launched(brokeAwayFromJob = false)
}

/** `CreateProcessW` がアクセス拒否で失敗したか。 */
private fun ProcessLaunchResult.Failed.isAccessDenied(): Boolean =
    function == CREATE_PROCESS && lastError == ERROR_ACCESS_DENIED

/** [CREATE_BREAKAWAY_FROM_JOB] を外した要求。 */
private fun CreateProcessRequest.withoutBreakaway(): CreateProcessRequest =
    copy(creationFlags = creationFlags and CREATE_BREAKAWAY_FROM_JOB.inv())

/**
 * `kernel32` の `CreateProcessW` を FFM（`java.lang.foreign`）で呼んで起動する [CreateProcess]。
 *
 * 警告を出さずに呼ぶには、JVM の起動引数に `--enable-native-access=ALL-UNNAMED` が要る。最初に使うときに DLL を読み込む。
 * Windows でない、DLL を読み込めない、関数を呼べないときは、`GetLastError` の値の無い [ProcessLaunchResult.Failed] になる。
 */
internal object WindowsProcessCreator {
    private val kernel32: Kernel32 by lazy { Kernel32() }

    fun create(request: CreateProcessRequest): ProcessLaunchResult.Failed? =
        try {
            kernel32.createProcess(request)
        } catch (e: IllegalArgumentException) {
            // DLL を読み込めない、または関数を探せない、関数や構造体のレイアウトを組めない
            notCalled(describeThrowable(e))
        } catch (e: UnsupportedOperationException) {
            // この JVM がネイティブの関数の呼び出しに対応していない
            notCalled(describeThrowable(e))
        } catch (e: IllegalCallerException) {
            // ネイティブの関数の呼び出しが許されていない
            notCalled(describeIllegalCaller(e))
        }

    private fun notCalled(reason: String): ProcessLaunchResult.Failed =
        ProcessLaunchResult.Failed(CREATE_PROCESS, lastError = null, reason = reason)
}

/** `kernel32` のプロセスの関数を呼ぶ。作るときに DLL を読み込み、関数を探して呼び出せるようにする。 */
private class Kernel32 {
    private val linker = Linker.nativeLinker()
    private val library = SymbolLookup.libraryLookup(KERNEL32, Arena.global())

    /** `STARTUPINFOW`。64 ビットでは、ポインタの位置を揃えるパディングが 2 か所に入る。 */
    private val startupInfo: StructLayout = MemoryLayout.structLayout(
        JAVA_INT.withName(CB),
        MemoryLayout.paddingLayout(ADDRESS.byteAlignment() - JAVA_INT.byteSize()),
        ADDRESS.withName("lpReserved"),
        ADDRESS.withName("lpDesktop"),
        ADDRESS.withName("lpTitle"),
        JAVA_INT.withName("dwX"),
        JAVA_INT.withName("dwY"),
        JAVA_INT.withName("dwXSize"),
        JAVA_INT.withName("dwYSize"),
        JAVA_INT.withName("dwXCountChars"),
        JAVA_INT.withName("dwYCountChars"),
        JAVA_INT.withName("dwFillAttribute"),
        JAVA_INT.withName("dwFlags"),
        JAVA_SHORT.withName("wShowWindow"),
        JAVA_SHORT.withName("cbReserved2"),
        MemoryLayout.paddingLayout(ADDRESS.byteAlignment() - 2 * JAVA_SHORT.byteSize()),
        ADDRESS.withName("lpReserved2"),
        ADDRESS.withName("hStdInput"),
        ADDRESS.withName("hStdOutput"),
        ADDRESS.withName("hStdError"),
    )
    private val cbOffset = startupInfo.byteOffset(MemoryLayout.PathElement.groupElement(CB))

    /** `PROCESS_INFORMATION`。 */
    private val processInformation: StructLayout = MemoryLayout.structLayout(
        ADDRESS.withName(H_PROCESS),
        ADDRESS.withName(H_THREAD),
        JAVA_INT.withName("dwProcessId"),
        JAVA_INT.withName("dwThreadId"),
    )
    private val hProcessOffset = processInformation.byteOffset(MemoryLayout.PathElement.groupElement(H_PROCESS))
    private val hThreadOffset = processInformation.byteOffset(MemoryLayout.PathElement.groupElement(H_THREAD))

    private val callState: StructLayout = Linker.Option.captureStateLayout()
    private val lastErrorOffset = callState.byteOffset(MemoryLayout.PathElement.groupElement(GET_LAST_ERROR))

    /**
     * `BOOL CreateProcessW(LPCWSTR, LPWSTR, LPSECURITY_ATTRIBUTES, LPSECURITY_ATTRIBUTES, BOOL, DWORD, LPVOID, LPCWSTR,
     * LPSTARTUPINFOW, LPPROCESS_INFORMATION)`。
     */
    private val createProcessW = downcall(
        CREATE_PROCESS,
        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
        Linker.Option.captureCallState(GET_LAST_ERROR),
    )

    /** `BOOL CloseHandle(HANDLE)`。 */
    private val closeHandle = downcall(CLOSE_HANDLE, FunctionDescriptor.of(JAVA_INT, ADDRESS))

    private fun downcall(name: String, descriptor: FunctionDescriptor, vararg options: Linker.Option): MethodHandle =
        library.find(name)
            .orElseThrow { IllegalArgumentException("$name が見つからない") }
            .let { linker.downcallHandle(it, descriptor, *options) }

    /**
     * [request] のとおりに `CreateProcessW` を呼ぶ。失敗したら、`GetLastError` の値を添えた失敗を返す。
     * 起動したら、プロセスとスレッドのハンドルを閉じ、プロセスを待たずに null を返す。
     *
     * プロセスとスレッドのセキュリティ属性と環境変数のブロックには null を渡し、ハンドルは引き継がない。
     * `STARTUPINFOW` は、大きさ（`cb`）のほかをゼロにして渡す。
     */
    fun createProcess(request: CreateProcessRequest): ProcessLaunchResult.Failed? =
        Arena.ofConfined().use { arena ->
            val state = arena.allocate(callState)
            // Arena が確保した領域はゼロで埋まっている
            val startup = arena.allocate(startupInfo).also { it.set(JAVA_INT, cbOffset, startupInfo.byteSize().toInt()) }
            val information = arena.allocate(processInformation)
            val result = createProcessW.invoke(
                state,
                arena.allocateFrom(request.applicationName, UTF_16LE),
                // CreateProcessW はコマンドラインを書き換えることがあるので、書き込める領域に写して渡す
                arena.allocateFrom(request.commandLine, UTF_16LE),
                MemorySegment.NULL,
                MemorySegment.NULL,
                FALSE,
                request.creationFlags,
                MemorySegment.NULL,
                request.currentDirectory?.let { arena.allocateFrom(it, UTF_16LE) } ?: MemorySegment.NULL,
                startup,
                information,
            ) as Int
            if (result == FALSE) {
                ProcessLaunchResult.Failed(CREATE_PROCESS, state.get(JAVA_INT, lastErrorOffset))
            } else {
                // 閉じる失敗は、起動したプロセスに影響しないため問わない
                closeHandle.invoke(information.get(ADDRESS, hProcessOffset))
                closeHandle.invoke(information.get(ADDRESS, hThreadOffset))
                null
            }
        }
}
