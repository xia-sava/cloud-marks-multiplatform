package to.sava.cloudmarksmultiplatform.host

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.desktopAppFilesIn
import to.sava.cloudmarksmultiplatform.settings.FakeSecretProtector
import to.sava.cloudmarksmultiplatform.settings.SETTINGS
import to.sava.cloudmarksmultiplatform.settings.SettingsFile
import to.sava.cloudmarksmultiplatform.settings.SettingsWriteResult
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HostEntryPointTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // 標準出力を取り分けると、元の標準出力が返り、そのあと System.out へ書いたものは標準エラーへ出る
    @Test
    fun standardOutputIsReservedForResponse() {
        val originalOut = System.out
        val originalErr = System.err
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val testOut = PrintStream(stdout, true, Charsets.UTF_8)
        val testErr = PrintStream(stderr, true, Charsets.UTF_8)
        try {
            System.setOut(testOut)
            System.setErr(testErr)

            val reserved = reserveStandardOutputForResponse()
            System.out.print("ライブラリの出力")

            assertSame(testOut, reserved)
            assertSame(testErr, System.out)
            assertEquals(0, stdout.size())
            assertEquals("ライブラリの出力", stderr.toString(Charsets.UTF_8))
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }

    // ホストとして動かすと、出力には応答のメッセージをちょうど 1 つだけ書き、失敗の詳細はアプリと同じログのディレクトリに書く。
    // 設定が無いので、S3 には接続しない
    @Test
    fun hostWritesSingleResponseAndLogs() {
        val appFiles = desktopAppFilesIn(temporaryFolder.newFolder().toPath())
        val output = ByteArrayOutputStream()

        runNativeMessagingHost(inputOf(contractBytes("add-folder.json")), PrintStream(output), appFiles, FakeSecretProtector())

        assertEquals(
            jsonOf(encodeHostResponse(HostResponse.Failed(SETTINGS_MISSING_ERROR))),
            jsonOf(singleMessageIn(output)),
        )
        val log = appFiles.logDirectory.listDirectoryEntries().single().readText()
        assertTrue(log.contains(HOST_LOG_PREFIX), log)
    }

    // 処理から Error が抜けても、そのほかの失敗の応答を 1 つ書き、例外のクラス名をログに書く
    @Test
    fun errorFromHostIsInternal() {
        val (host, logs) = hostWhoseConnectionThrows()
        val output = ByteArrayOutputStream()

        runNativeMessagingHost(inputOf(contractBytes("add-folder.json")), PrintStream(output), host) { logs += it }

        assertEquals(jsonOf(encodeHostResponse(HostResponse.Failed(INTERNAL_ERROR))), jsonOf(singleMessageIn(output)))
        assertTrue(logs.single().contains(HostTestError::class.java.name), logs.toString())
    }

    // 応答を書き始めたあとに Error が起きたら、ログに書くだけで、2 つ目の応答は書かない
    @Test
    fun errorAfterResponseStartedWritesNoSecondResponse() {
        val (host, logs) = hostWhoseConnectionThrows()
        val output = ByteArrayOutputStream()

        runNativeMessagingHost(inputOf(ByteArray(0)), FailingFlushStream(output), host) { logs += it }

        assertEquals(jsonOf(encodeHostResponse(HostResponse.Failed(INVALID_REQUEST_ERROR))), jsonOf(singleMessageIn(output)))
        assertTrue(logs.last().contains(HostTestError::class.java.name), logs.toString())
    }

    /** 保存済みの設定があり、接続を作ろうとすると [HostTestError] を投げるホストと、そのログ。 */
    private fun hostWhoseConnectionThrows(): Pair<NativeMessagingHost, MutableList<String>> {
        val appFiles = desktopAppFilesIn(temporaryFolder.newFolder().toPath())
        assertEquals(SettingsWriteResult.Success, SettingsFile(appFiles.settingsFile, FakeSecretProtector()).write(SETTINGS))
        val logs = mutableListOf<String>()
        val host = NativeMessagingHost(
            appFiles = appFiles,
            protector = FakeSecretProtector(),
            log = { logs += it },
            openConnection = { _, _, _, _ -> throw HostTestError() },
        )
        return host to logs
    }
}

/** テストで投げる [Error]。 */
private class HostTestError : Error("テストで投げた")

/** 書いたものを [output] へ渡したうえで、flush のたびに [HostTestError] を投げる。 */
private class FailingFlushStream(output: ByteArrayOutputStream) : PrintStream(output) {
    override fun flush() {
        super.flush()
        throw HostTestError()
    }
}
