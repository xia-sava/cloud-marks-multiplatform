package to.sava.cloudmarksmultiplatform.browser

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import to.sava.cloudmarksmultiplatform.AppFiles
import to.sava.cloudmarksmultiplatform.files.describeIoFailure
import to.sava.cloudmarksmultiplatform.settings.TabLoading
import java.awt.Desktop
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.security.SecureRandom
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** まとめて開くコマンドを書いてブラウザを起動してから、コマンドのファイルが残っているかを確かめるまでの時間。 */
internal val COMMAND_DELIVERY_TIMEOUT: Duration = 10.seconds

/** ブラウザの起動の引数に渡すときにパーセントエンコードする、URL に使えない ASCII の記号。空白と制御文字もエンコードする。 */
private const val UNSAFE_URL_CHARACTERS = "\"<>\\^`{|}"

/** フラグメント（`#` より後ろ）より前と、IPv6 のアドレスのホストのほかでは、パーセントエンコードする角括弧。 */
private const val BRACKETS = "[]"

/** パーセントエンコードの頭。後ろに 16 進の 2 桁が続かなければ、それ自身をパーセントエンコードする。 */
private const val PERCENT = '%'

/** フラグメントの頭。 */
private const val FRAGMENT_START = '#'

/** オーソリティ（`//` から後ろのホストなど）の頭。 */
private const val AUTHORITY_PREFIX = "//"

/** オーソリティの終わりを表す文字。 */
private val AUTHORITY_END = charArrayOf('/', '?', FRAGMENT_START)

/** オーソリティの中の、ユーザーの情報とホストの区切り。 */
private const val USERINFO_END = '@'

/** IPv6 のアドレスのホストを囲む括弧。 */
private const val IPV6_HOST_START = '['
private const val IPV6_HOST_END = ']'

/** パーセントエンコードで、`%` の後ろに続く 16 進の桁の数。 */
private const val PERCENT_ENCODED_DIGITS = 2

/** ASCII の空白。これ以下の文字（制御文字を含む）はパーセントエンコードする。 */
private const val SPACE = ' '

/** ASCII の DEL。パーセントエンコードする。 */
private const val DELETE = '\u007F'

/** プロセスを起動する。起動できなければ例外を投げる。 */
internal fun interface ProcessStarter {
    /**
     * [command]（実行ファイルと引数の並び）でプロセスを起動する。終わるのを待たない。
     *
     * @throws IOException 起動できなかった
     */
    fun start(command: List<String>)
}

/** URL を、Windows が決めた既定のブラウザで開く。 */
internal fun interface DefaultBrowser {
    /**
     * [uri] を開く。開いたあとを待たない。
     *
     * @throws IOException 開けなかった
     * @throws UnsupportedOperationException この環境では開けない
     * @throws IllegalArgumentException [uri] を開ける形にできない
     */
    fun browse(uri: URI)
}

/**
 * デスクトップのアプリの [BrowserOpener]。
 *
 * PC に入っているブラウザはレジストリから読み（[readInstalledBrowsers]）、拡張機能が入っていることの記録
 * （[AppFiles.browsersWithExtensionFile]）に実行ファイルが載っているものを、まとめて開けるとする。
 * 既定のブラウザで開くときは、ブラウザを調べずに Windows に開かせる（[DefaultBrowser]）。http と https の URL だけを開く。
 * 選んだブラウザで開くときは、その実行ファイルを起動する。1 つなら URL を引数にし、2 つ以上ならコマンドを
 * [AppFiles.commandDirectory] に書いてから呼び出しの URL を引数にする（`docs/native-messaging.md`）。
 *
 * @param log ブラウザを読めなかったこと、記録を読めなかったこと、開けなかった理由のうち画面に出さないもの、
 * 古いコマンドのファイルを消せなかったことを書く。URL とタイトルは書かない
 * @param registry ブラウザを読むレジストリ
 * @param processStarter ブラウザを起動する
 * @param defaultBrowser 既定のブラウザで開く
 * @param clock 古いコマンドのファイルを決める、端末の時計
 * @param random コマンドのトークンを作る乱数
 * @param ioDispatcher レジストリとファイルの読み書き、ブラウザの起動に使う
 */
internal class DesktopBrowserOpener(
    private val appFiles: AppFiles,
    private val log: (String) -> Unit,
    private val registry: RegistryReader = WindowsRegistry,
    private val processStarter: ProcessStarter = ProcessStarter(::startDetached),
    private val defaultBrowser: DefaultBrowser = DefaultBrowser { Desktop.getDesktop().browse(it) },
    private val clock: Clock = Clock.System,
    private val random: SecureRandom = SecureRandom(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BrowserOpener {
    override val opensMultipleItems: Boolean = true

    /** レジストリを読めなければ、理由をログに書いて空にする。記録が壊れているか読めなければ、ログに書き、どのブラウザもまとめて開けないとする。 */
    override suspend fun browsers(): List<Browser> =
        withContext(ioDispatcher) {
            val installed = try {
                readInstalledBrowsers(registry)
            } catch (e: RegistryException) {
                log("PC に入っているブラウザを読めなかった: ${e.message}")
                emptyList()
            }
            val recorded = recordedBrowsers()
            installed.map { browser ->
                Browser(
                    name = browser.name,
                    executable = browser.executable,
                    isDefault = browser.isDefault,
                    opensMultipleItems = normalizedExecutable(browser.executable) in recorded,
                )
            }
        }

    /**
     * 開けなかったときは、理由をログに書き、詳細の無い [BrowserOpenResult.LaunchFailed] を返す。
     * [DefaultBrowser] の例外のメッセージは URL を含みうるので、ログには例外の種類だけを書く。
     */
    override suspend fun openInDefaultBrowser(url: String): BrowserOpenResult =
        withContext(ioDispatcher) {
            val uri = try {
                launchArgumentOf(url)?.let(::URI)
            } catch (e: URISyntaxException) {
                null
            } ?: return@withContext defaultBrowserFailed(log, "URL の形が正しくない")
            if (!isWebUrl(url)) return@withContext defaultBrowserFailed(log, NOT_WEB_URL_REASON)
            try {
                defaultBrowser.browse(uri)
                BrowserOpenResult.Opened
            } catch (e: IOException) {
                defaultBrowserFailed(log, e::class.simpleName)
            } catch (e: UnsupportedOperationException) {
                // デスクトップの機能が無い環境（HeadlessException を含む）
                defaultBrowserFailed(log, e::class.simpleName)
            } catch (e: IllegalArgumentException) {
                // URI を URL にできない
                defaultBrowserFailed(log, e::class.simpleName)
            }
        }

    /**
     * 1 つならその URL を引数にしてブラウザを起動する。2 つ以上なら、[AppFiles.commandDirectory] の古いコマンドのファイルを消してから
     * コマンドのファイルを書き、呼び出しの URL を引数にしてブラウザを起動する。起動してから [COMMAND_DELIVERY_TIMEOUT] たっても
     * コマンドのファイルが残っていれば、[BrowserOpenResult.CommandUndelivered] を返す（ファイルは消さない）。
     * コマンドのファイルを書けなければ、ブラウザを起動せずに [BrowserOpenResult.LaunchFailed] を返す。
     * 起動できなければ、書いたコマンドのファイルを消す。1 つの URL が起動の引数にできない形なら、ログに書き、詳細の無い
     * [BrowserOpenResult.LaunchFailed] を返す。
     */
    override suspend fun openInBrowser(browser: Browser, tabs: List<BrowserTab>, tabLoading: TabLoading): BrowserOpenResult {
        require(tabs.isNotEmpty()) { "開くタブが無い" }
        tabs.singleOrNull()?.let { tab ->
            val argument = launchArgumentOf(tab.url) ?: run {
                log("ブラウザを起動しなかった: URL の形が正しくない")
                return BrowserOpenResult.LaunchFailed(detail = null)
            }
            return withContext(ioDispatcher) { launch(browser, argument) }
        }
        val token = newCommandToken(random)
        val launched = withContext(ioDispatcher) { sendCommand(browser, token, OpenTabsCommand(tabs, tabLoading)) }
        if (launched != BrowserOpenResult.Opened) return launched
        delay(COMMAND_DELIVERY_TIMEOUT)
        val remains = withContext(ioDispatcher) { Files.exists(commandFileOf(appFiles.commandDirectory, token)) }
        return if (remains) BrowserOpenResult.CommandUndelivered else BrowserOpenResult.Opened
    }

    /** 拡張機能が入っていると分かったブラウザ。記録が壊れているか読めなければ、ログに書いて空にする。 */
    private fun recordedBrowsers(): Set<String> =
        when (val read = readBrowsersWithExtension(appFiles.browsersWithExtensionFile)) {
            is BrowsersWithExtensionReadResult.Read -> read.browsers.toSet()
            BrowsersWithExtensionReadResult.Broken -> {
                log("拡張機能が入っているブラウザの記録が、記録の形でない")
                emptySet()
            }
            is BrowsersWithExtensionReadResult.Failed -> {
                log("拡張機能が入っているブラウザの記録を読めなかった: ${describeIoFailure(read.cause)}")
                emptySet()
            }
        }

    /** 古いコマンドのファイルを消し、トークンが [token] のコマンド [command] のファイルを書いてから、呼び出しの URL で [browser] を起動する。 */
    private fun sendCommand(browser: Browser, token: String, command: OpenTabsCommand): BrowserOpenResult {
        val directory = appFiles.commandDirectory
        deleteStaleCommandFiles(directory, clock.now(), log)
        try {
            writeCommandFile(directory, token, command)
        } catch (e: IOException) {
            return BrowserOpenResult.LaunchFailed(describeIoFailure(e))
        }
        return launch(browser, knockUrl(token)).also { result ->
            if (result is BrowserOpenResult.LaunchFailed) discardCommandFile(token)
        }
    }

    /** [browser] の実行ファイルを、[argument] を引数にして起動する。 */
    private fun launch(browser: Browser, argument: String): BrowserOpenResult =
        try {
            processStarter.start(listOf(browser.executable, argument))
            BrowserOpenResult.Opened
        } catch (e: IOException) {
            BrowserOpenResult.LaunchFailed(describeIoFailure(e))
        }

    /** 渡せなかったコマンドのファイルを消す。消せなければログに書く（そのファイルは、古いコマンドのファイルとして消える）。 */
    private fun discardCommandFile(token: String) {
        try {
            Files.deleteIfExists(commandFileOf(appFiles.commandDirectory, token))
        } catch (e: IOException) {
            log("渡せなかったコマンドのファイルを消せなかった: ${describeIoFailure(e)}")
        }
    }
}

/** デスクトップのアプリの [BrowserOpener] を作る。[log] には、利用者には見せない失敗を書く。 */
fun desktopBrowserOpener(appFiles: AppFiles, log: (String) -> Unit): BrowserOpener = DesktopBrowserOpener(appFiles, log)

/**
 * 1 つの URL [url] を、ブラウザの起動の引数と、既定のブラウザで開く URI にできる形にする。
 * 次の文字をパーセントエンコードする（引数が分かれたり、引用符が閉じたりしないようにし、URI として読めるようにする）。
 *
 * - 空白・制御文字・引用符など、URL に使えない ASCII の文字
 * - 後ろに 16 進の 2 桁が続かない `%`
 * - フラグメント（最初の `#` より後ろ）より前の `[` と `]`。ただし、ホストが IPv6 のアドレス（`[…]`）なら、その括弧は残す
 *
 * ほかの文字（ASCII でない文字を含む）はそのまま残す。
 * 先頭が URL の種類（`https:` など）でなければ null（`-` で始まるものを、ブラウザが起動のオプションと読まないようにする）。
 */
internal fun launchArgumentOf(url: String): String? {
    val scheme = URL_SCHEME.find(url) ?: return null
    val fragmentStart = url.indexOf(FRAGMENT_START).takeIf { it >= 0 } ?: url.length
    val ipv6Host = ipv6HostRangeOf(url, afterScheme = scheme.range.last + 1)
    return buildString {
        url.forEachIndexed { index, char ->
            val encodes = char <= SPACE || char == DELETE || char in UNSAFE_URL_CHARACTERS ||
                (char == PERCENT && !url.hasPercentEncodedDigitsAt(index)) ||
                (char in BRACKETS && index < fragmentStart && index !in ipv6Host)
            if (encodes) append("%%%02X".format(char.code)) else append(char)
        }
    }
}

/**
 * [url] の、URL の種類のすぐ後ろ [afterScheme] から始まるオーソリティのホストが、IPv6 のアドレス（`[…]`）なら、その `[` から `]` までの位置。
 * オーソリティが無いか、ホストが IPv6 のアドレスでなければ空。
 */
private fun ipv6HostRangeOf(url: String, afterScheme: Int): IntRange {
    if (!url.startsWith(AUTHORITY_PREFIX, afterScheme)) return IntRange.EMPTY
    val authorityStart = afterScheme + AUTHORITY_PREFIX.length
    val authorityEnd = url.indexOfAny(AUTHORITY_END, authorityStart).takeIf { it >= 0 } ?: url.length
    val hostStart = url.lastIndexOf(USERINFO_END, authorityEnd - 1).takeIf { it >= authorityStart }?.plus(1) ?: authorityStart
    if (url.getOrNull(hostStart) != IPV6_HOST_START) return IntRange.EMPTY
    val hostEnd = url.indexOf(IPV6_HOST_END, hostStart).takeIf { it in hostStart until authorityEnd } ?: return IntRange.EMPTY
    return hostStart..hostEnd
}

/** 位置 [index] の `%` の後ろに、16 進の 2 桁が続くか。 */
private fun String.hasPercentEncodedDigitsAt(index: Int): Boolean =
    index + PERCENT_ENCODED_DIGITS < length && (1..PERCENT_ENCODED_DIGITS).all { this[index + it].isHexDigit() }

/** 16 進の桁（`0`–`9`・`a`–`f`・`A`–`F`）か。 */
private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

/** [command] でプロセスを起動し、終わるのを待たない。標準出力と標準エラーは捨て、標準入力は閉じる。 */
private fun startDetached(command: List<String>) {
    ProcessBuilder(command)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
        .outputStream
        .close()
}
