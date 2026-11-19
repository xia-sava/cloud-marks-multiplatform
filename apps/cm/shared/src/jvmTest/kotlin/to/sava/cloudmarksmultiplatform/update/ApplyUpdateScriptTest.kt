package to.sava.cloudmarksmultiplatform.update

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ダウンロードした配布物。 */
private const val SETUP = """C:\Users\me\AppData\Local\CloudMarks\updates\download-1\cloudmarks-setup.exe"""

/** 配布物の実行ファイル。適用したあとに起動し直す。 */
private const val LAUNCHER = """C:\Users\me\AppData\Local\Programs\CloudMarks\CloudMarks.exe"""

/** 適用するスクリプトが結果を書くログ。 */
private const val RESULT_LOG = """C:\Users\me\AppData\Local\CloudMarks\logs\update-apply.log"""

/** スクリプトが終了を待つプロセス。動いている JVM と、それを起動したランチャー。 */
private val WAIT_PIDS = listOf(1234L, 5678L)

/** UTF-8 の BOM のバイト列。 */
private val UTF8_BYTE_ORDER_MARK_BYTES = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

class ApplyUpdateScriptTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val script = applyUpdateScript(SETUP, LAUNCHER, RESULT_LOG, WAIT_PIDS)

    /** [script] の、[text] を含む行。1 行だけあることも確かめる。 */
    private fun lineWith(text: String): String = script.lines().filter { text in it }.also { assertEquals(1, it.size, text) }.single()

    // 動いている JVM とランチャーは実行ファイルを開いたままなので、どちらもそれぞれ 60 秒まで終了を待ってから始める
    @Test
    fun waitsForJvmAndLauncherToExit() {
        assertEquals(
            "foreach (\$id in @(1234,5678)) { Wait-Process -Id \$id -Timeout 60 -ErrorAction SilentlyContinue }",
            lineWith("Wait-Process"),
        )
        assertTrue(script.indexOf("Wait-Process") < script.indexOf("Start-Process"))
    }

    // インストーラは人手なしで走らせる。引数は空白を含まない 3 つで、Start-Process が空白でつないでも割れない
    @Test
    fun runsInstallerSilentlyWithArgumentsWithoutSpaces() {
        val start = lineWith("-ArgumentList")
        assertEquals(
            "\$installer = Start-Process '$SETUP' -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART' -PassThru",
            start.trim(),
        )
        val arguments = start.substringAfter("-ArgumentList ").substringBefore(" -PassThru").split(", ")
        assertEquals(listOf("'/VERYSILENT'", "'/SUPPRESSMSGBOXES'", "'/NORESTART'"), arguments)
        assertTrue(arguments.none { ' ' in it.trim('\'') })
    }

    // インストーラの終了は、受け取ったプロセスの WaitForExit() で待つ。-Wait は、インストーラが起動したプロセスの終了まで待つので使わない
    @Test
    fun waitsForInstallerAlone() {
        assertFalse(script.contains("-Wait "))
        assertTrue(script.contains("\$installer.WaitForExit()"))
        assertTrue(script.contains("\$code = \$installer.ExitCode"))
    }

    // 結果は、時刻を添えてログのファイルに書き足す。起動できなかった、終了コードが 0 でなかった、成功した、のどれか
    @Test
    fun writesOutcomeToResultLog() {
        assertTrue(script.contains("\$log = '$RESULT_LOG'"))
        val outcomes = script.lines().filter { "Add-Content" in it }.map { it.trim() }
        assertEquals(
            listOf(
                "Add-Content -LiteralPath \$log -Value \"\$(Get-Date -Format s) setup could not be started: \$_\"",
                "Add-Content -LiteralPath \$log -Value \"\$(Get-Date -Format s) setup failed with exit code \$code\"",
                "Add-Content -LiteralPath \$log -Value \"\$(Get-Date -Format s) $APPLY_SUCCEEDED\"",
            ),
            outcomes,
        )
    }

    // 起動し直すのは配布物の実行ファイルで、成功したときだけ。失敗したら、その前で終える
    @Test
    fun relaunchesOnlyAfterSuccess() {
        val failureExit = script.indexOf("exit \$code")
        val succeeded = script.indexOf(APPLY_SUCCEEDED)
        val relaunch = script.indexOf("Start-Process '$LAUNCHER'")

        assertTrue(failureExit in 0..<succeeded, script)
        assertTrue(succeeded < relaunch, script)
        assertEquals("Start-Process '$LAUNCHER'", script.trimEnd().lines().last())
    }

    // パスの単一引用符は 2 つ重ね、文字列が途中で切れないようにする。PowerShell が単一引用符として扱う、左右の単一引用符も同じ
    @Test
    fun escapesSingleQuotesInPaths() {
        assertEquals("""'C:\it''s\setup.exe'""", powerShellLiteral("""C:\it's\setup.exe"""))
        assertEquals("'C:\\O\u2019\u2019Brien\\\u2018\u2018a\u201A\u201A\u201B\u201B'", powerShellLiteral("C:\\O\u2019Brien\\\u2018a\u201A\u201B"))
        assertEquals("""'C:\${'$'}env\`x'""", powerShellLiteral("""C:\${'$'}env\`x"""))

        val quoted = applyUpdateScript("""C:\it's\setup.exe""", LAUNCHER, RESULT_LOG, listOf(1L))

        assertTrue(quoted.contains("""Start-Process 'C:\it''s\setup.exe' -ArgumentList"""))
    }

    // スクリプトは配布物と同じディレクトリに、BOM 付きの UTF-8 で書く。Windows PowerShell は、BOM の無いファイルをシステムのコードページとして読む
    @Test
    fun writesScriptBesideSetupWithByteOrderMark() {
        val directory = temporaryFolder.newFolder("download-1").toPath()
        val setup = directory.resolve("cloudmarks-setup.exe").also { it.writeText("setup") }
        val resultLog = temporaryFolder.root.toPath().resolve("logs").resolve("update-apply.log")
        val launcher = """C:\Users\利用者\AppData\Local\Programs\CloudMarks\CloudMarks.exe"""
        val installer = DesktopUpdateInstaller(launcherPath = launcher, resultLogFile = resultLog)

        val written = installer.writeApplyScript(setup, WAIT_PIDS)

        assertEquals(directory, written.parent)
        val bytes = written.readBytes()
        assertContentEquals(UTF8_BYTE_ORDER_MARK_BYTES, bytes.copyOf(UTF8_BYTE_ORDER_MARK_BYTES.size))
        val text = bytes.copyOfRange(UTF8_BYTE_ORDER_MARK_BYTES.size, bytes.size).decodeToString()
        assertEquals(applyUpdateScript(setup.toAbsolutePath().toString(), launcher, resultLog.toAbsolutePath().toString(), WAIT_PIDS), text)
    }

    // 起動し直す実行ファイルが分からなければ（開発版）、スクリプトを書かずに失敗にする
    @Test
    fun refusesWithoutLauncher() {
        val directory = temporaryFolder.newFolder("download-2").toPath()
        val setup = directory.resolve("cloudmarks-setup.exe").also { it.writeText("setup") }
        val installer = DesktopUpdateInstaller(launcherPath = null, resultLogFile = temporaryFolder.root.toPath().resolve("update-apply.log"))

        assertFailsWith<IOException> { installer.writeApplyScript(setup, WAIT_PIDS) }
        assertEquals(listOf<Path>(setup), Files.list(directory).use { it.toList() })
    }
}
