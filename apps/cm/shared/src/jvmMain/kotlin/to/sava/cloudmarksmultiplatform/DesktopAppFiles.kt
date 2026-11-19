package to.sava.cloudmarksmultiplatform

import java.nio.file.Path

/** 配布版のデータを置くディレクトリの名前。 */
private const val APP_DIRECTORY_NAME = "CloudMarks"

/** 開発版のデータを置くディレクトリの名前。配布版と並べて動かしても、設定やローカルキャッシュを取り合わない。 */
private const val DEV_APP_DIRECTORY_NAME = "CloudMarks-dev"

/** Windows で、ユーザーごとの、ほかの PC へ持ち出さないデータを置く場所を指す環境変数。 */
private const val LOCAL_APP_DATA = "LOCALAPPDATA"

private const val USER_HOME = "user.home"

/**
 * デスクトップのアプリが端末に置くファイルの場所。
 *
 * 配布版（[distributed] が真）は `%LOCALAPPDATA%\CloudMarks`、開発版は `%LOCALAPPDATA%\CloudMarks-dev` を使う。
 * その直下に設定のファイルと端末ごとの設定のファイルを、その下の `cache` にローカルキャッシュを、
 * `logs` にログを、`state` に次の起動へ持ち越す記録を、`commands` にブラウザの拡張機能へのコマンドを、`updates` にアプリの更新で
 * ダウンロードした配布物を置く。
 * 環境変数 `LOCALAPPDATA` が無ければ、ホームの下の同じ名前のディレクトリを使う。
 */
fun desktopAppFiles(distributed: Boolean = AppPath.isDistributed): AppFiles =
    desktopAppFilesIn(resolveAppDataPath(System.getenv(LOCAL_APP_DATA), System.getProperty(USER_HOME), distributed))

/** アプリのデータを置くディレクトリ [directory] の中の、それぞれのファイルの場所。 */
internal fun desktopAppFilesIn(directory: Path): AppFiles =
    AppFiles(
        settingsFile = directory.resolve(SETTINGS_FILE_NAME),
        cacheDirectory = directory.resolve(CACHE_DIRECTORY_NAME),
        logDirectory = directory.resolve(LOG_DIRECTORY_NAME),
        stateDirectory = directory.resolve(STATE_DIRECTORY_NAME),
        deviceSettingsFile = directory.resolve(DEVICE_SETTINGS_FILE_NAME),
        commandDirectory = directory.resolve(COMMAND_DIRECTORY_NAME),
        updateDirectory = directory.resolve(UPDATE_DIRECTORY_NAME),
    )

/**
 * [localAppData]（環境変数 `LOCALAPPDATA` の値）の下、それが無いか空なら [userHome] の下の、
 * 配布版（[distributed] が真）なら `CloudMarks`、開発版なら `CloudMarks-dev`。
 */
internal fun resolveAppDataPath(localAppData: String?, userHome: String, distributed: Boolean): Path =
    Path.of(
        localAppData?.takeIf { it.isNotBlank() } ?: userHome,
        if (distributed) APP_DIRECTORY_NAME else DEV_APP_DIRECTORY_NAME,
    )
