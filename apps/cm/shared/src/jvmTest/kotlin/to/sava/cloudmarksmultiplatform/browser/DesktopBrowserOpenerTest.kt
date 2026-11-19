package to.sava.cloudmarksmultiplatform.browser

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.AppFiles
import to.sava.cloudmarksmultiplatform.app.TestClock
import to.sava.cloudmarksmultiplatform.nativeMessagingIdentity
import to.sava.cloudmarksmultiplatform.settings.TabLoading
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.SecureRandom
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** 古いコマンドのファイルを決めるときの、いまの時刻。 */
private val NOW = Instant.parse("2026-11-17T12:00:00Z")

private val TABS = listOf(BrowserTab("https://example.com/1", "1"), BrowserTab("https://example.com/2", "2"))

private val TAB_LOADING = TabLoading(loadAtOnce = 2, loadAhead = 10)

/** 起動したコマンドを記録する [ProcessStarter]。[failure] があれば、記録せずにそれを投げる。 */
private class RecordingStarter : ProcessStarter {
    val started = mutableListOf<List<String>>()
    var failure: IOException? = null

    override fun start(command: List<String>) {
        failure?.let { throw it }
        started += command
    }
}

// runCurrent と advanceTimeBy が ExperimentalCoroutinesApi であるため。
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopBrowserOpenerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val logs = mutableListOf<String>()
    private val starter = RecordingStarter()
    private val browsed = mutableListOf<URI>()
    private val registry = FakeRegistry()

    private val appFiles: AppFiles by lazy {
        temporaryFolder.root.toPath().let { directory ->
            AppFiles(
                settingsFile = directory.resolve("settings.json"),
                cacheDirectory = directory.resolve("cache"),
                logDirectory = directory.resolve("logs"),
                stateDirectory = directory.resolve("state"),
                deviceSettingsFile = directory.resolve("device.json"),
                commandDirectory = directory.resolve("commands"),
                updateDirectory = directory.resolve("updates"),
            )
        }
    }

    private fun TestScope.opener(
        registry: RegistryReader = this@DesktopBrowserOpenerTest.registry,
        defaultBrowser: DefaultBrowser = DefaultBrowser { browsed += it },
        distributed: Boolean = true,
    ): DesktopBrowserOpener =
        DesktopBrowserOpener(
            appFiles = appFiles,
            distributed = distributed,
            log = { logs += it },
            registry = registry,
            processStarter = starter,
            defaultBrowser = defaultBrowser,
            clock = TestClock(NOW),
            random = SecureRandom(),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

    /** レジストリに、Chrome と Firefox を登録する。 */
    private fun addChromeAndFirefox() {
        registry.addBrowser(
            RegistryRoot.LOCAL_MACHINE,
            "Chrome",
            name = "Google Chrome",
            command = "\"C:\\Chrome\\chrome.exe\"",
            httpsProgId = "ChromeHTML",
        )
        registry.addBrowser(
            RegistryRoot.LOCAL_MACHINE,
            "Firefox",
            name = "Firefox",
            command = "\"C:\\Firefox\\firefox.exe\"",
            httpsProgId = "FirefoxURL",
        )
    }

    /** コマンドのディレクトリにある、コマンドのファイル。 */
    private fun commandFiles(): List<Path> =
        appFiles.commandDirectory.takeIf { it.exists() }?.listDirectoryEntries().orEmpty()

    // PC に入っているブラウザのうち、拡張機能が入っていることの記録に実行ファイルが載っているもの（大文字と小文字は区別しない）を、
    // まとめて開けるとする
    @Test
    fun browsersWithRecordedExecutableOpenMultipleItems() = runTest {
        addChromeAndFirefox()
        Files.createDirectories(appFiles.stateDirectory)
        Files.writeString(appFiles.browsersWithExtensionFile, """{"browsers":["c:\\chrome\\chrome.exe"]}""")

        val browsers = opener().browsers()

        assertEquals(mapOf("Firefox" to false, "Google Chrome" to true), browsers.associate { it.name to it.opensMultipleItems })
        assertEquals(emptyList(), logs)
    }

    // 拡張機能が入っていることの記録が壊れているか読めなければ、ログに書き、どのブラウザもまとめて開けないとする
    @Test
    fun brokenOrUnreadableRecordIsLoggedAndOpensNothingTogether() = runTest {
        addChromeAndFirefox()
        Files.createDirectories(appFiles.stateDirectory)
        val cases = listOf<Pair<String, () -> Unit>>(
            "壊れている" to { Files.writeString(appFiles.browsersWithExtensionFile, "壊れた") },
            "読めない" to {
                Files.delete(appFiles.browsersWithExtensionFile)
                Files.createDirectory(appFiles.browsersWithExtensionFile)
            },
        )
        cases.forEach { (name, arrange) ->
            arrange()
            logs.clear()

            val browsers = opener().browsers()

            assertEquals(listOf(false, false), browsers.map { it.opensMultipleItems }, name)
            assertEquals(1, logs.size, name)
        }
    }

    // レジストリを読めなければ、ログに書いてブラウザを空にする
    @Test
    fun registryFailureIsLoggedAndEmpty() = runTest {
        val failing = object : RegistryReader {
            override fun subKeyNames(root: RegistryRoot, path: String): List<String> = throw RegistryException("読めない")

            override fun stringValue(root: RegistryRoot, path: String, name: String?): String? = throw RegistryException("読めない")
        }

        assertEquals(emptyList(), opener(registry = failing).browsers())
        assertEquals(1, logs.size)
    }

    // 1 つなら、その URL を引数にしてブラウザを起動し、コマンドのファイルを書かない
    @Test
    fun singleTabLaunchesUrlDirectly() = runTest {
        val result = opener().openInBrowser(CHROME, TABS.take(1), TAB_LOADING)

        assertEquals(BrowserOpenResult.Opened, result)
        assertEquals(listOf(listOf(CHROME.executable, TABS[0].url)), starter.started)
        assertEquals(emptyList(), commandFiles())
    }

    // 1 つの URL が起動の引数にできない形なら、起動せず、ログに書いて詳細の無い失敗とする。ログに URL を書かない
    @Test
    fun singleTabWithoutSchemeIsNotLaunched() = runTest {
        val result = opener().openInBrowser(CHROME, listOf(BrowserTab("--secret-flag", "")), TAB_LOADING)

        assertEquals(BrowserOpenResult.LaunchFailed(detail = null), result)
        assertEquals(emptyList(), starter.started)
        assertFalse(logs.single().contains("secret"), logs.single())
    }

    // 2 つ以上なら、コマンドのファイルを書いてから、呼び出しの URL を引数にしてブラウザを起動する。
    // 10 秒たってもファイルが残っていれば、届かなかったかもしれないとし、ファイルは消さない
    @Test
    fun remainingCommandIsUndelivered() = runTest {
        val opening = async { opener().openInBrowser(CHROME, TABS, TAB_LOADING) }
        runCurrent()

        val file = commandFiles().single()
        val token = file.name.removeSuffix(".json")
        assertEquals(listOf(listOf(CHROME.executable, knockUrl(token, distributed = true))), starter.started)
        assertContentEquals(OpenTabsCommand(TABS, TAB_LOADING).encode(), file.readBytes())

        advanceTimeBy(COMMAND_DELIVERY_TIMEOUT - 1.milliseconds)
        runCurrent()
        assertFalse(opening.isCompleted)

        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(BrowserOpenResult.CommandUndelivered, opening.await())
        assertTrue(file.exists())
    }

    // 2 つ以上を開くときの呼び出しの URL は、配布版なら配布版の、開発版なら開発版の、identities.json の callUrlHost をホストに持つ
    @Test
    fun knockUrlFollowsVariant() = runTest {
        listOf(true, false).forEach { distributed ->
            starter.started.clear()

            val opening = async { opener(distributed = distributed).openInBrowser(CHROME, TABS, TAB_LOADING) }
            runCurrent()

            val argument = starter.started.single()[1]
            val host = Regex.escape(nativeMessagingIdentity(distributed, "callUrlHost"))
            assertTrue(Regex("^https://$host/knock\\?token=[0-9a-f]{32}$").matches(argument), "distributed=$distributed: $argument")
            opening.cancel()
        }
    }

    // 10 秒たつまでにホストがコマンドのファイルを受け取って消していれば、開いたとする
    @Test
    fun takenCommandIsOpened() = runTest {
        val opening = async { opener().openInBrowser(CHROME, TABS, TAB_LOADING) }
        runCurrent()
        Files.delete(commandFiles().single())

        advanceTimeBy(COMMAND_DELIVERY_TIMEOUT)
        runCurrent()

        assertEquals(BrowserOpenResult.Opened, opening.await())
    }

    // コマンドを書く前に、1 時間より古いコマンドのファイルを消す
    @Test
    fun staleCommandsAreDeletedBeforeWriting() = runTest {
        Files.createDirectories(appFiles.commandDirectory)
        val stale = appFiles.commandDirectory.resolve("0".repeat(32) + ".json").also { file ->
            Files.writeString(file, "{}")
            Files.setLastModifiedTime(file, FileTime.fromMillis((NOW - 2.hours).toEpochMilliseconds()))
        }

        val opening = async { opener().openInBrowser(CHROME, TABS, TAB_LOADING) }
        runCurrent()

        assertFalse(stale.exists())
        assertEquals(1, commandFiles().size)
        opening.cancel()
    }

    // ブラウザを起動できなければ、詳細を添えて失敗とし、書いたコマンドのファイルを消す。待たずに返す
    @Test
    fun launchFailureDiscardsCommand() = runTest {
        starter.failure = IOException("CreateProcess error=2")

        val single = opener().openInBrowser(CHROME, TABS.take(1), TAB_LOADING)
        val multiple = opener().openInBrowser(CHROME, TABS, TAB_LOADING)

        assertEquals(BrowserOpenResult.LaunchFailed("IOException: CreateProcess error=2"), single)
        assertEquals(BrowserOpenResult.LaunchFailed("IOException: CreateProcess error=2"), multiple)
        assertEquals(emptyList(), commandFiles())
    }

    // コマンドのファイルを書けなければ、ブラウザを起動せずに失敗とする
    @Test
    fun commandWriteFailureDoesNotLaunch() = runTest {
        // コマンドのディレクトリの場所に、ファイルを置く
        Files.writeString(appFiles.commandDirectory, "file")

        val result = opener().openInBrowser(CHROME, TABS, TAB_LOADING)

        assertIs<BrowserOpenResult.LaunchFailed>(result)
        assertEquals(emptyList(), starter.started)
    }

    // 既定のブラウザでは、http と https の URL だけを開く。ほかの種類と、種類の無いもの、URI にできないものは、開かずに
    // 詳細の無い失敗とし、理由をログに書く
    @Test
    fun defaultBrowserOpensOnlyWebUrls() = runTest {
        val opened = listOf("https://example.com/a?b=1&c=2", "HTTP://example.com/", "https://example.com/a[1]")
        val refused = listOf("file:///C:/Windows/System32/calc.exe", "javascript:alert(1)", "example.com", "--flag", "", "https:")

        opened.forEach { assertEquals(BrowserOpenResult.Opened, opener().openInDefaultBrowser(it), it) }
        refused.forEach { url ->
            logs.clear()

            assertEquals(BrowserOpenResult.LaunchFailed(detail = null), opener().openInDefaultBrowser(url), url)
            assertEquals(1, logs.size, url)
        }

        assertEquals(opened.map { URI(checkNotNull(launchArgumentOf(it))) }, browsed)
    }

    // 既定のブラウザで開けなければ、詳細の無い失敗とし、例外の種類だけをログに書く（例外のメッセージは URL を含みうる）
    @Test
    fun defaultBrowserFailureIsLoggedWithoutMessage() = runTest {
        val failing = DefaultBrowser { throw IOException("Failed to open https://secret.example/") }

        val result = opener(defaultBrowser = failing).openInDefaultBrowser("https://secret.example/")

        assertEquals(BrowserOpenResult.LaunchFailed(detail = null), result)
        assertTrue(logs.single().contains("IOException"), logs.single())
        assertFalse(logs.single().contains("secret"), logs.single())
    }

    // 起動の引数にする URL は、空白・引用符・制御文字など URL に使えない ASCII の文字をパーセントエンコードし、ほかの文字はそのまま残す。
    // 先頭が URL の種類でなければ、引数にしない
    @Test
    fun launchArgumentEncodesUnsafeCharacters() {
        val cases = listOf(
            "https://example.com/a b\"c" to "https://example.com/a%20b%22c",
            "https://example.com/{x}|^`<>\\" to "https://example.com/%7Bx%7D%7C%5E%60%3C%3E%5C",
            "https://example.com/\t\n\u007F" to "https://example.com/%09%0A%7F",
            "https://ja.wikipedia.org/wiki/日本?q=1&r=%20#top" to "https://ja.wikipedia.org/wiki/日本?q=1&r=%20#top",
            "about:blank" to "about:blank",
            "--gpu-launcher=calc" to null,
            "example.com/a" to null,
            "" to null,
        )
        cases.forEach { (url, expected) -> assertEquals(expected, launchArgumentOf(url), url) }
    }

    // # より前の [ と ] はパーセントエンコードし、# より後ろと、ホストの IPv6 のアドレスの括弧は残す
    @Test
    fun launchArgumentEncodesBracketsBeforeFragment() {
        val cases = listOf(
            "https://example.com/a[1]?b=[2]#c[3]" to "https://example.com/a%5B1%5D?b=%5B2%5D#c[3]",
            "http://[::1]:8080/x[y]" to "http://[::1]:8080/x%5By%5D",
            "https://user:pw@[fe80::1%25eth0]/" to "https://user:pw@[fe80::1%25eth0]/",
            "https://exa[mple].com/" to "https://exa%5Bmple%5D.com/",
            "mailto:[a]@example.com" to "mailto:%5Ba%5D@example.com",
        )
        cases.forEach { (url, expected) -> assertEquals(expected, launchArgumentOf(url), url) }
    }

    // 後ろに 16 進の 2 桁が続かない % はパーセントエンコードし、続くものは残す
    @Test
    fun launchArgumentEncodesLonePercent() {
        val cases = listOf(
            "https://example.com/100%" to "https://example.com/100%25",
            "https://example.com/%2" to "https://example.com/%252",
            "https://example.com/%zz" to "https://example.com/%25zz",
            "https://example.com/%2g%20%aF" to "https://example.com/%252g%20%aF",
            "https://example.com/?q=50%#top%" to "https://example.com/?q=50%25#top%25",
        )
        cases.forEach { (url, expected) -> assertEquals(expected, launchArgumentOf(url), url) }
    }
}
