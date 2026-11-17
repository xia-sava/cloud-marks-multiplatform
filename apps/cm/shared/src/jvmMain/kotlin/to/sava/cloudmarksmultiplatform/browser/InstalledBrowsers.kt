package to.sava.cloudmarksmultiplatform.browser

/** ブラウザを登録するキー。`HKEY_CURRENT_USER` と `HKEY_LOCAL_MACHINE` のそれぞれの下にあり、ブラウザごとに子のキーを持つ。 */
private const val START_MENU_INTERNET = "SOFTWARE\\Clients\\StartMenuInternet"

/** ブラウザのキーの下の、ブラウザの名前の値を持つキー。 */
private const val CAPABILITIES = "Capabilities"

/** [CAPABILITIES] の、ブラウザの名前の値。 */
private const val APPLICATION_NAME = "ApplicationName"

/** ブラウザのキーの下の、ブラウザを起動するコマンドを既定の値に持つキー。 */
private const val OPEN_COMMAND = "shell\\open\\command"

/** ブラウザのキーの下の、URL の種類ごとに、そのブラウザが開くときの ProgId を持つキー。 */
private const val URL_ASSOCIATIONS = "Capabilities\\URLAssociations"

/** [URL_ASSOCIATIONS] と [USER_CHOICE] の、https の URL の種類。 */
private const val HTTPS = "https"

/** https の URL を開くアプリについて、利用者が選んだものを持つキー。`HKEY_CURRENT_USER` の下にある。 */
private const val HTTPS_URL_ASSOCIATION = "Software\\Microsoft\\Windows\\Shell\\Associations\\UrlAssociations\\$HTTPS"

/** ProgId の値と、その値を持つキーの名前。 */
private const val PROG_ID = "ProgId"

/**
 * 利用者が既定のブラウザとして選んだ、https の URL を開く ProgId を持つキー（新しい Windows が書く）。
 * ProgId は、`UserChoiceLatest` の下の `ProgId` キーの、`ProgId` の値にある。
 */
private const val USER_CHOICE_LATEST = "$HTTPS_URL_ASSOCIATION\\UserChoiceLatest\\$PROG_ID"

/** 利用者が既定のブラウザとして選んだ、https の URL を開く ProgId を持つキー。ProgId は、このキーの `ProgId` の値にある。 */
private const val USER_CHOICE = "$HTTPS_URL_ASSOCIATION\\UserChoice"

/** 実行ファイルの名前の末尾。 */
private const val EXECUTABLE_SUFFIX = ".exe"

/** 引用符で囲んでいないコマンドの、先頭の実行ファイルのパス。最初の `.exe` のうち、後ろが空白か終わりのものまで。 */
private val UNQUOTED_EXECUTABLE = Regex("""^.*?\.exe(?=\s|$)""", RegexOption.IGNORE_CASE)

/** 絶対パスの頭。ドライブ文字か、UNC の `\\`。 */
private val ABSOLUTE_PATH = Regex("""^([A-Za-z]:\\|\\\\)""")

/** 引用符。 */
private const val QUOTE = '"'

/**
 * PC に入っているブラウザ。拡張機能が入っているかは含まない。
 *
 * @property name 画面に出す名前
 * @property executable 実行ファイルの絶対パス
 * @property isDefault 既定のブラウザか
 */
internal data class InstalledBrowser(val name: String, val executable: String, val isDefault: Boolean)

/**
 * [registry] から、PC に入っているブラウザを読む。名前の順（大文字と小文字は区別しない）に並べる。
 *
 * ブラウザは `HKEY_CURRENT_USER` と `HKEY_LOCAL_MACHINE` の [START_MENU_INTERNET] の子のキーのうち、https の URL を開く
 * ProgId（`Capabilities\URLAssociations` の `https`）を持ち、実行ファイルを `shell\open\command` の既定の値から取り出せる
 * （[executableOfCommand]）もの。https の URL を開けないもの（Internet Explorer など）は除く。
 * そのうえで同じ名前のキーが両方にあれば、`HKEY_CURRENT_USER` のものを使う（`HKEY_CURRENT_USER` のキーが除かれていれば、
 * `HKEY_LOCAL_MACHINE` のものを使う）。実行ファイルが同じブラウザは、先に読んだものだけにする。
 * 名前はキーの既定の値、無ければ `Capabilities\ApplicationName`、それも無ければキーの名前。
 *
 * 既定のブラウザは、利用者が選んだ ProgId と、https の URL を開く ProgId が同じもの（大文字と小文字は区別しない）。
 * 利用者が選んだ ProgId は、`HKEY_CURRENT_USER` の [USER_CHOICE_LATEST] から読み、無ければ [USER_CHOICE] から読む。
 *
 * @throws RegistryException 読めなかった
 */
internal fun readInstalledBrowsers(registry: RegistryReader): List<InstalledBrowser> {
    val defaultProgId = listOf(USER_CHOICE_LATEST, USER_CHOICE).firstNotNullOfOrNull { key ->
        registry.stringValue(RegistryRoot.CURRENT_USER, key, PROG_ID)?.takeIf { it.isNotBlank() }
    }
    return listOf(RegistryRoot.CURRENT_USER, RegistryRoot.LOCAL_MACHINE)
        .flatMap { root -> registry.subKeyNames(root, START_MENU_INTERNET).map { keyName -> root to keyName } }
        .mapNotNull { (root, keyName) -> registry.browserAt(root, keyName, defaultProgId)?.let { keyName to it } }
        .distinctBy { (keyName, _) -> keyName.lowercase() }
        .map { (_, browser) -> browser }
        .distinctBy { it.executable.lowercase() }
        .sortedBy { it.name.lowercase() }
}

/**
 * [root] の [START_MENU_INTERNET] の子のキー [keyName] のブラウザ。https の URL を開く ProgId が無いか、実行ファイルを取り出せなければ null。
 */
private fun RegistryReader.browserAt(root: RegistryRoot, keyName: String, defaultProgId: String?): InstalledBrowser? {
    val path = "$START_MENU_INTERNET\\$keyName"
    val httpsProgId = stringValue(root, "$path\\$URL_ASSOCIATIONS", HTTPS)?.takeIf { it.isNotBlank() } ?: return null
    val executable = stringValue(root, "$path\\$OPEN_COMMAND", null)?.let(::executableOfCommand) ?: return null
    val name = stringValue(root, path, null)?.takeIf { it.isNotBlank() }
        ?: stringValue(root, "$path\\$CAPABILITIES", APPLICATION_NAME)?.takeIf { it.isNotBlank() }
        ?: keyName
    val isDefault = defaultProgId != null && httpsProgId.equals(defaultProgId, ignoreCase = true)
    return InstalledBrowser(name = name.trim(), executable = executable, isDefault = isDefault)
}

/**
 * ブラウザを起動するコマンド [command]（`shell\open\command` の既定の値）から、実行ファイルのパスを取り出す。
 *
 * 引用符で始まれば、次の引用符までをパスとする。そうでなければ、先頭から、後ろが空白か終わりの最初の `.exe` までをパスとする
 * （空白を含むパスを引用符で囲まずに書いたものも読める）。パスが絶対パスでないか、`.exe` で終わらなければ null。
 */
internal fun executableOfCommand(command: String): String? {
    val trimmed = command.trim()
    val path = if (trimmed.startsWith(QUOTE)) {
        trimmed.drop(1).substringBefore(QUOTE, missingDelimiterValue = "")
    } else {
        UNQUOTED_EXECUTABLE.find(trimmed)?.value.orEmpty()
    }
    return path.trim().takeIf { ABSOLUTE_PATH.containsMatchIn(it) && it.endsWith(EXECUTABLE_SUFFIX, ignoreCase = true) }
}
