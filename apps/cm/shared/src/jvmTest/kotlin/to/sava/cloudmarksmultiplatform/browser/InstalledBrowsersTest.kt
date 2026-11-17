package to.sava.cloudmarksmultiplatform.browser

import kotlin.test.Test
import kotlin.test.assertEquals

private const val START_MENU_INTERNET = "SOFTWARE\\Clients\\StartMenuInternet"
private const val USER_CHOICE = "Software\\Microsoft\\Windows\\Shell\\Associations\\UrlAssociations\\https\\UserChoice"
private const val USER_CHOICE_LATEST = "Software\\Microsoft\\Windows\\Shell\\Associations\\UrlAssociations\\https\\UserChoiceLatest\\ProgId"

private const val CHROME_EXE = "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"
private const val FIREFOX_EXE = "C:\\Program Files\\Mozilla Firefox\\firefox.exe"
private const val EDGE_EXE = "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe"

/** 子のキーと文字列の値を決めておく [RegistryReader]。決めていないキーは子を持たず、決めていない値は無い。 */
internal class FakeRegistry : RegistryReader {
    private val subKeys = mutableMapOf<Pair<RegistryRoot, String>, List<String>>()
    private val values = mutableMapOf<Triple<RegistryRoot, String, String?>, String>()

    override fun subKeyNames(root: RegistryRoot, path: String): List<String> = subKeys[root to path].orEmpty()

    override fun stringValue(root: RegistryRoot, path: String, name: String?): String? = values[Triple(root, path, name)]

    /** [root] の [path] のキーの、名前が [name]（null なら既定の値）の値を [value] にする。 */
    fun setValue(root: RegistryRoot, path: String, name: String?, value: String) {
        values[Triple(root, path, name)] = value
    }

    /** 既定のブラウザとして選んだ、https の URL を開く ProgId を、`UserChoice` で [progId] にする。 */
    fun setUserChoice(progId: String) {
        setValue(RegistryRoot.CURRENT_USER, USER_CHOICE, "ProgId", progId)
    }

    /** 既定のブラウザとして選んだ、https の URL を開く ProgId を、新しい Windows が書く `UserChoiceLatest\ProgId` で [progId] にする。 */
    fun setUserChoiceLatest(progId: String) {
        setValue(RegistryRoot.CURRENT_USER, USER_CHOICE_LATEST, "ProgId", progId)
    }

    /**
     * [root] の StartMenuInternet に、ブラウザのキー [keyName] を足す。値が null のものは置かない。
     *
     * @param name キーの既定の値
     * @param applicationName `Capabilities\ApplicationName`
     * @param command `shell\open\command` の既定の値
     * @param httpsProgId `Capabilities\URLAssociations\https`
     */
    fun addBrowser(
        root: RegistryRoot,
        keyName: String,
        name: String? = null,
        applicationName: String? = null,
        command: String? = null,
        httpsProgId: String? = null,
    ) {
        subKeys[root to START_MENU_INTERNET] = subKeyNames(root, START_MENU_INTERNET) + keyName
        val path = "$START_MENU_INTERNET\\$keyName"
        name?.let { setValue(root, path, null, it) }
        applicationName?.let { setValue(root, "$path\\Capabilities", "ApplicationName", it) }
        command?.let { setValue(root, "$path\\shell\\open\\command", null, it) }
        httpsProgId?.let { setValue(root, "$path\\Capabilities\\URLAssociations", "https", it) }
    }
}

/** Chrome（HKLM）、Firefox（HKLM。キーの既定の値が無い）、Edge（HKCU。名前が無い）を登録したレジストリ。 */
private fun threeBrowsers(): FakeRegistry =
    FakeRegistry().apply {
        addBrowser(RegistryRoot.LOCAL_MACHINE, "Google Chrome", name = "Google Chrome", command = "\"$CHROME_EXE\"", httpsProgId = "ChromeHTML")
        addBrowser(
            RegistryRoot.LOCAL_MACHINE,
            "Firefox-308046B0AF4A39CB",
            applicationName = "Firefox",
            command = "\"$FIREFOX_EXE\" -osint -url \"%1\"",
            httpsProgId = "FirefoxURL-308046B0AF4A39CB",
        )
        addBrowser(RegistryRoot.CURRENT_USER, "Microsoft Edge", command = "\"$EDGE_EXE\"", httpsProgId = "MSEdgeHTM")
    }

class InstalledBrowsersTest {
    // 名前はキーの既定の値、無ければ Capabilities\ApplicationName、それも無ければキーの名前。実行ファイルは shell\open\command から取り出す。
    // HKCU と HKLM の両方から読み、名前の順に並べる
    @Test
    fun namesAndExecutablesAreRead() {
        val browsers = readInstalledBrowsers(threeBrowsers())

        val expected = listOf(
            InstalledBrowser("Firefox", FIREFOX_EXE, isDefault = false),
            InstalledBrowser("Google Chrome", CHROME_EXE, isDefault = false),
            InstalledBrowser("Microsoft Edge", EDGE_EXE, isDefault = false),
        )
        assertEquals(expected, browsers)
    }

    // 既定のブラウザは、UserChoiceLatest の ProgId があればそれ、無ければ UserChoice の ProgId と、https の ProgId が同じもの
    @Test
    fun userChoiceLatestIsReadFirst() {
        val cases = listOf(
            Triple("MSEdgeHTM", "ChromeHTML", setOf("Microsoft Edge")),
            Triple(null, "ChromeHTML", setOf("Google Chrome")),
            Triple(" ", "ChromeHTML", setOf("Google Chrome")),
            Triple("chromehtml", null, setOf("Google Chrome")),
        )
        cases.forEach { (latest, userChoice, expected) ->
            val registry = threeBrowsers().apply {
                latest?.let(::setUserChoiceLatest)
                userChoice?.let(::setUserChoice)
            }

            assertEquals(expected, readInstalledBrowsers(registry).filter { it.isDefault }.map { it.name }.toSet(), "$latest, $userChoice")
        }
    }

    // 既定のブラウザは、UserChoice の ProgId と https の ProgId が同じもの（大文字と小文字は区別しない）。UserChoice が無ければ無い
    @Test
    fun defaultBrowserMatchesUserChoice() {
        val cases = listOf(
            "ChromeHTML" to setOf("Google Chrome"),
            "chromehtml" to setOf("Google Chrome"),
            "MSEdgeHTM" to setOf("Microsoft Edge"),
            "SomeOtherProgId" to emptySet(),
            null to emptySet(),
        )
        cases.forEach { (progId, expected) ->
            val registry = threeBrowsers().apply { progId?.let(::setUserChoice) }

            assertEquals(expected, readInstalledBrowsers(registry).filter { it.isDefault }.map { it.name }.toSet(), "$progId")
        }
    }

    // 同じ名前のキーが HKCU と HKLM の両方にあれば、HKCU のものを使う（大文字と小文字は区別しない）
    @Test
    fun currentUserKeyWins() {
        val registry = FakeRegistry().apply {
            addBrowser(RegistryRoot.LOCAL_MACHINE, "Google Chrome", name = "Chrome (all users)", command = "\"$CHROME_EXE\"", httpsProgId = "ChromeHTML")
            addBrowser(
                RegistryRoot.CURRENT_USER,
                "google chrome",
                name = "Chrome (me)",
                command = "\"C:\\Users\\me\\chrome.exe\"",
                httpsProgId = "ChromeHTML",
            )
        }

        assertEquals(listOf(InstalledBrowser("Chrome (me)", "C:\\Users\\me\\chrome.exe", isDefault = false)), readInstalledBrowsers(registry))
    }

    // HKCU のキーが使えない（コマンドが無い・https の ProgId が無い）ときは、HKLM の同じ名前のキーを使う
    @Test
    fun localMachineKeyIsUsedWhenCurrentUserKeyIsUnusable() {
        val cases = listOf(
            "コマンドが無い" to { registry: FakeRegistry ->
                registry.addBrowser(RegistryRoot.CURRENT_USER, "Google Chrome", name = "Chrome (me)", httpsProgId = "ChromeHTML")
            },
            "https の ProgId が無い" to { registry: FakeRegistry ->
                registry.addBrowser(RegistryRoot.CURRENT_USER, "Google Chrome", name = "Chrome (me)", command = "\"C:\\Users\\me\\chrome.exe\"")
            },
        )
        cases.forEach { (name, addCurrentUserKey) ->
            val registry = FakeRegistry().apply {
                addCurrentUserKey(this)
                addBrowser(RegistryRoot.LOCAL_MACHINE, "Google Chrome", name = "Chrome (all users)", command = "\"$CHROME_EXE\"", httpsProgId = "ChromeHTML")
            }

            assertEquals(listOf(InstalledBrowser("Chrome (all users)", CHROME_EXE, isDefault = false)), readInstalledBrowsers(registry), name)
        }
    }

    // 実行ファイルを取り出せないキー（コマンドが無い・相対パス・.exe でない）と、https の URL を開く ProgId が無いキー
    // （Internet Explorer など）は除き、同じ実行ファイルのキーは 1 つにする
    @Test
    fun unusableKeysAreSkipped() {
        val registry = FakeRegistry().apply {
            addBrowser(RegistryRoot.LOCAL_MACHINE, "No Command", name = "No Command", httpsProgId = "NoCommandURL")
            addBrowser(RegistryRoot.LOCAL_MACHINE, "Relative", name = "Relative", command = "browser.exe", httpsProgId = "RelativeURL")
            addBrowser(RegistryRoot.LOCAL_MACHINE, "Script", name = "Script", command = "\"C:\\tools\\browser.bat\"", httpsProgId = "ScriptURL")
            addBrowser(RegistryRoot.LOCAL_MACHINE, "IEXPLORE.EXE", name = "Internet Explorer", command = "\"C:\\Program Files\\Internet Explorer\\iexplore.exe\"")
            addBrowser(RegistryRoot.LOCAL_MACHINE, "Blank ProgId", name = "Blank ProgId", command = "\"C:\\blank\\blank.exe\"", httpsProgId = " ")
            addBrowser(RegistryRoot.LOCAL_MACHINE, "Chrome A", name = "Chrome A", command = "\"$CHROME_EXE\"", httpsProgId = "ChromeHTML")
            addBrowser(RegistryRoot.LOCAL_MACHINE, "Chrome B", name = "Chrome B", command = "\"${CHROME_EXE.uppercase()}\"", httpsProgId = "ChromeHTML")
        }

        assertEquals(listOf("Chrome A"), readInstalledBrowsers(registry).map { it.name })
    }

    // コマンドから実行ファイルのパスを取り出す。引用符で囲んでいれば次の引用符まで、囲んでいなければ最初の .exe まで
    @Test
    fun executableIsTakenFromCommand() {
        val cases = listOf(
            "\"$CHROME_EXE\"" to CHROME_EXE,
            "\"$FIREFOX_EXE\" -osint -url \"%1\"" to FIREFOX_EXE,
            "  \"$EDGE_EXE\" --single-argument %1" to EDGE_EXE,
            "$FIREFOX_EXE -osint -url \"%1\"" to FIREFOX_EXE,
            "C:\\Browser\\Browser.EXE" to "C:\\Browser\\Browser.EXE",
            "\\\\server\\share\\browser.exe" to "\\\\server\\share\\browser.exe",
            "\"$CHROME_EXE" to null,
            "browser.exe" to null,
            "\"C:\\tools\\browser.bat\"" to null,
            "C:\\exes\\browser" to null,
            "" to null,
        )
        cases.forEach { (command, expected) ->
            assertEquals(expected, executableOfCommand(command), command)
        }
    }
}
