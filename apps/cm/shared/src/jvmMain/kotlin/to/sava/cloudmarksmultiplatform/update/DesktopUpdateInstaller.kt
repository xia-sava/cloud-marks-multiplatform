package to.sava.cloudmarksmultiplatform.update

import to.sava.cloudmarksmultiplatform.files.describeIoFailure
import to.sava.cloudmarksmultiplatform.files.describeThrowable
import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.lang.ProcessBuilder.Redirect
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** ダウンロードごとに作るディレクトリの、名前の頭。更新のディレクトリ（[to.sava.cloudmarksmultiplatform.AppFiles.updateDirectory]）の下に作る。 */
private const val DOWNLOAD_DIRECTORY_PREFIX = "download-"

/** 適用するスクリプトの名前。配布物と同じディレクトリに置く。 */
private const val APPLY_SCRIPT_NAME = "cloudmarks-apply-update.ps1"

/** 適用するスクリプトが、終了を待つプロセスの 1 つごとに待つ上限（秒）。 */
private const val EXIT_WAIT_SECONDS = 60

/** 適用するスクリプトを走らせる PowerShell の実行ファイルの、`%SystemRoot%` から見た位置。 */
private const val POWERSHELL_RELATIVE_PATH = """System32\WindowsPowerShell\v1.0\powershell.exe"""

/** Windows のシステムのディレクトリを指す環境変数。 */
private const val SYSTEM_ROOT = "SystemRoot"

/**
 * UTF-8 の BOM。
 *
 * 適用するスクリプトを走らせる Windows PowerShell は、BOM の無いファイルをシステムのコードページとして読む。
 * パスに ASCII でない文字があると、別のパスを指すスクリプトになる。
 */
private val UTF8_BYTE_ORDER_MARK: String = Char(0xFEFF).toString()

/**
 * PowerShell が文字列のリテラルの単一引用符として扱う文字。ASCII の `'`（U+0027）のほかに、左右の単一引用符（U+2018・U+2019）、
 * 下付きの単一引用符（U+201A）、逆向きの単一引用符（U+201B）。
 */
private val POWERSHELL_SINGLE_QUOTES: Set<Char> = setOf('\'', Char(0x2018), Char(0x2019), Char(0x201A), Char(0x201B))

/**
 * ダウンロードのディレクトリを、置いたままのものと見なすまでの時間。
 *
 * 適用するスクリプトは、終了を待つプロセス（動いている JVM とランチャーの 2 つ）を 1 つごとに [EXIT_WAIT_SECONDS] まで、
 * 合わせて最長で 120 秒待ってからインストーラを走らせ、インストールが終わるまで配布物を使う。それより十分に長く取る。
 */
private val STALE_AFTER: Duration = 10.minutes

/**
 * [value] を、PowerShell の単一引用符の文字列のリテラルにする。中の単一引用符（[POWERSHELL_SINGLE_QUOTES]）は 2 つ重ねる。
 * 単一引用符の中では `$` も逆引用符も字のまま読まれる。
 */
internal fun powerShellLiteral(value: String): String =
    buildString {
        append('\'')
        value.forEach { char ->
            append(char)
            if (char in POWERSHELL_SINGLE_QUOTES) append(char)
        }
        append('\'')
    }

/**
 * 更新を適用する PowerShell のスクリプトの中身を作る。
 *
 * 動いているアプリの実行ファイルは置き換えられないので、適用はアプリの外で行う。スクリプトは [waitPids] のプロセスの終了を
 * それぞれ [waitSeconds] まで待ってから、インストーラ（Inno Setup）[setupPath] を人手なしで走らせ、成功したら [launcherPath] を起動し直す。
 *
 * - インストーラへ渡す引数に空白を入れない。`Start-Process` は引数を空白でつなぐだけなので、空白を含む引数は 2 つに割れる。
 *   確認のダイアログを抑える引数が読まれないと、誰も答えられない画面が出る
 * - インストーラの終了は、`Start-Process -PassThru` で受け取ったプロセスの `WaitForExit()` で待つ。`Start-Process -Wait` は、
 *   インストーラが起動したプロセスの終了まで待つ
 * - 結果（インストーラを起動できなかった、終了コードが 0 でなかった、成功した）を、書いた時刻を添えて [resultLogPath] に書き足す。
 *   成功の行は [APPLY_SUCCEEDED]。適用しているあいだアプリは終了しているので、成否はこのファイルにだけ残る（[UpdateApplyLog]）。
 *   起動し直すのは成功したときだけ
 */
internal fun applyUpdateScript(
    setupPath: String,
    launcherPath: String,
    resultLogPath: String,
    waitPids: List<Long>,
    waitSeconds: Int = EXIT_WAIT_SECONDS,
): String {
    val pids = waitPids.joinToString(",")
    return """
        ${'$'}ErrorActionPreference = 'Stop'
        ${'$'}log = ${powerShellLiteral(resultLogPath)}
        foreach (${'$'}id in @($pids)) { Wait-Process -Id ${'$'}id -Timeout $waitSeconds -ErrorAction SilentlyContinue }
        try {
            ${'$'}installer = Start-Process ${powerShellLiteral(setupPath)} -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART' -PassThru
            ${'$'}null = ${'$'}installer.Handle
            ${'$'}installer.WaitForExit()
            ${'$'}code = ${'$'}installer.ExitCode
        } catch {
            Add-Content -LiteralPath ${'$'}log -Value "${'$'}(Get-Date -Format s) setup could not be started: ${'$'}_"
            exit 1
        }
        if (${'$'}code -ne 0) {
            Add-Content -LiteralPath ${'$'}log -Value "${'$'}(Get-Date -Format s) setup failed with exit code ${'$'}code"
            exit ${'$'}code
        }
        Add-Content -LiteralPath ${'$'}log -Value "${'$'}(Get-Date -Format s) $APPLY_SUCCEEDED"
        Start-Process ${powerShellLiteral(launcherPath)}
    """.trimIndent() + "\n"
}

/** 更新のディレクトリ [updateDirectory] の下に、ダウンロードのディレクトリを新しく作る。更新のディレクトリが無ければ作る。 */
internal fun createDownloadDirectory(updateDirectory: Path): Path {
    Files.createDirectories(updateDirectory)
    return Files.createTempDirectory(updateDirectory, DOWNLOAD_DIRECTORY_PREFIX)
}

/** [directory] が、ダウンロードのディレクトリ（[createDownloadDirectory] が作るもの）の名前を持つか。 */
private fun isDownloadDirectoryName(directory: Path): Boolean =
    directory.fileName?.toString()?.startsWith(DOWNLOAD_DIRECTORY_PREFIX) == true

/**
 * ダウンロードした配布物 [setup] を、置いたダウンロードのディレクトリごと消す。ディレクトリにある適用するスクリプトも消える。
 * [setup] がダウンロードのディレクトリに無ければ、[setup] だけを消す。
 *
 * @throws IOException 消せなかった
 */
@OptIn(ExperimentalPathApi::class)
internal fun discardDownload(setup: Path) {
    val directory = setup.toAbsolutePath().parent
    if (directory != null && isDownloadDirectoryName(directory)) directory.deleteRecursively() else Files.deleteIfExists(setup)
}

/**
 * 更新のディレクトリ [updateDirectory] の下の、置いたままのダウンロードのディレクトリを消す。
 *
 * インストーラへ渡した配布物は、適用するスクリプトが使うので、渡した側では消せない（[DesktopUpdater.apply]）。
 * 残ったものはここで消す。最終更新が [olderThan] より新しいディレクトリは残す。スクリプトはアプリの終了を待ってからインストーラを
 * 走らせるので、できたばかりのディレクトリは、これから使われるものかもしれない。
 * ダウンロードのディレクトリの名前（[createDownloadDirectory]）でないものは消さない。消せなかったものは、ログに書いて続ける。
 *
 * @param now いまの時刻（エポックからのミリ秒）
 */
@OptIn(ExperimentalPathApi::class)
internal fun discardStaleDownloads(
    updateDirectory: Path,
    log: (String) -> Unit,
    olderThan: Duration = STALE_AFTER,
    now: () -> Long = System::currentTimeMillis,
) {
    val cutoff = now() - olderThan.inWholeMilliseconds
    val directories = try {
        Files.list(updateDirectory).use { entries ->
            entries.filter { Files.isDirectory(it) && isDownloadDirectoryName(it) }.toList()
        }
    } catch (e: NoSuchFileException) {
        return
    } catch (e: IOException) {
        log("更新のディレクトリを読めなかった: ${describeIoFailure(e)}")
        return
    } catch (e: UncheckedIOException) {
        log("更新のディレクトリを読めなかった: ${describeThrowable(e.cause ?: e)}")
        return
    }
    directories.forEach { directory ->
        try {
            if (Files.getLastModifiedTime(directory).toMillis() < cutoff) directory.deleteRecursively()
        } catch (e: IOException) {
            log("置いたままの更新のファイルを消せなかった: ${describeIoFailure(e)}")
        }
    }
}

/**
 * デスクトップへ更新を適用する。適用する PowerShell のスクリプト（[applyUpdateScript]）を配布物と同じディレクトリに書き、走らせる。
 * スクリプトはアプリの終了を待つので、走らせた側はそのあとアプリを終了する。終了しなければ適用されない。
 *
 * @param launcherPath 配布物の実行ファイル（[to.sava.cloudmarksmultiplatform.AppPath.verified]）。適用したあとに起動し直す。
 * null なら（jpackage のランチャーから起動されていない開発版）、適用できない
 * @param resultLogFile 適用するスクリプトが結果を書くファイル（[UpdateApplyLog.resultLogFile]）
 */
internal class DesktopUpdateInstaller(
    private val launcherPath: String?,
    private val resultLogFile: Path,
) {
    /**
     * 適用するスクリプトを、配布物 [setup] と同じディレクトリに BOM 付きの UTF-8 で書き、そのパスを返す。
     * スクリプトは [waitPids] のプロセスの終了を待つ。
     *
     * @throws IOException 配布物の実行ファイルが分からない（[launcherPath] が null）か、書けなかった
     */
    fun writeApplyScript(setup: Path, waitPids: List<Long>): Path {
        val launcher = launcherPath?.takeIf { it.isNotBlank() } ?: throw IOException("起動し直す実行ファイルが分からない")
        val script = setup.toAbsolutePath().resolveSibling(APPLY_SCRIPT_NAME)
        val content = applyUpdateScript(
            setupPath = setup.toAbsolutePath().toString(),
            launcherPath = launcher,
            resultLogPath = resultLogFile.toAbsolutePath().toString(),
            waitPids = waitPids,
        )
        Files.writeString(script, UTF8_BYTE_ORDER_MARK + content, Charsets.UTF_8)
        return script
    }

    /**
     * 配布物 [setup] を適用するスクリプトを書き、PowerShell で走らせる。スクリプトは、動いている JVM と、それを起動したランチャー
     * （`CloudMarks.exe`）の終了を待つ。どちらも実行ファイルを開いたままなので、置き換えられない。
     *
     * PowerShell の標準出力と標準エラーは捨てる。アプリが終了したあとも走り続けるので、アプリにつないだままにしない。
     *
     * @throws IOException 配布物の実行ファイルが分からない、スクリプトを書けなかった、PowerShell が見つからないか起動できなかった
     */
    fun launch(setup: Path) {
        val script = writeApplyScript(setup, processIdsToAwait())
        ProcessBuilder(
            powerShellPath(),
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy", "Bypass",
            "-WindowStyle", "Hidden",
            "-File", script.toString(),
        )
            .redirectOutput(Redirect.DISCARD)
            .redirectError(Redirect.DISCARD)
            .start()
    }
}

/** 適用するスクリプトが終了を待つプロセス。動いている JVM と、それを起動したランチャー。 */
private fun processIdsToAwait(): List<Long> {
    val current = ProcessHandle.current()
    return listOfNotNull(current.pid(), current.parent().orElse(null)?.pid())
}

/**
 * 適用するスクリプトを走らせる PowerShell の実行ファイル。`PATH` から探さず、`%SystemRoot%` から組み立てる。
 *
 * @throws IOException 見つからない
 */
private fun powerShellPath(): String =
    System.getenv(SYSTEM_ROOT)
        ?.let { File(it, POWERSHELL_RELATIVE_PATH) }
        ?.takeIf { it.isFile }
        ?.absolutePath
        ?: throw IOException("%SystemRoot% の下に PowerShell が見つからない")
