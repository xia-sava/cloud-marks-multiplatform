package to.sava.cloudmarksmultiplatform

import java.nio.file.Path

/** アプリのデータを置くディレクトリの名前。 */
private const val APP_DIRECTORY_NAME = "CloudMarks"

/** ローカルキャッシュを置くディレクトリの名前。 */
private const val CACHE_DIRECTORY_NAME = "cache"

/** ログのファイルを置くディレクトリの名前。 */
private const val LOG_DIRECTORY_NAME = "logs"

/** Windows で、ユーザーごとの、ほかの PC へ持ち出さないデータを置く場所を指す環境変数。 */
private const val LOCAL_APP_DATA = "LOCALAPPDATA"

private const val USER_HOME = "user.home"

/**
 * デスクトップのアプリが端末に置くファイルの場所。
 *
 * `%LOCALAPPDATA%\CloudMarks` の直下に設定のファイルを、その下の `cache` にローカルキャッシュを、`logs` にログを置く。
 * 環境変数 `LOCALAPPDATA` が無ければ、ホームの下の `CloudMarks` を使う。
 */
fun desktopAppFiles(): AppFiles =
    desktopAppFilesIn(resolveAppDataPath(System.getenv(LOCAL_APP_DATA), System.getProperty(USER_HOME)))

/** アプリのデータを置くディレクトリ [directory] の中の、それぞれのファイルの場所。 */
internal fun desktopAppFilesIn(directory: Path): AppFiles =
    AppFiles(
        settingsFile = directory.resolve(SETTINGS_FILE_NAME),
        cacheDirectory = directory.resolve(CACHE_DIRECTORY_NAME),
        logDirectory = directory.resolve(LOG_DIRECTORY_NAME),
    )

/** [localAppData]（環境変数 `LOCALAPPDATA` の値）の下、それが無いか空なら [userHome] の下の `CloudMarks`。 */
internal fun resolveAppDataPath(localAppData: String?, userHome: String): Path =
    Path.of(localAppData?.takeIf { it.isNotBlank() } ?: userHome, APP_DIRECTORY_NAME)
