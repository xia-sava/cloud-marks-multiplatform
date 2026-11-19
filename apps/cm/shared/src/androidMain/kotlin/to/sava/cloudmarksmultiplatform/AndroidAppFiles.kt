package to.sava.cloudmarksmultiplatform

import android.content.Context
import java.nio.file.Path

/**
 * Android のアプリが端末に置くファイルの場所。
 *
 * バックアップの対象にしないディレクトリ（`noBackupFilesDir`）の直下に設定のファイルと端末ごとの設定のファイルを、
 * キャッシュのディレクトリ（`cacheDir`）の下の `cache` にローカルキャッシュを、`updates` にアプリの更新でダウンロードした配布物を、
 * アプリのファイルのディレクトリ（`filesDir`）の下の `logs` にログを、`state` に次の起動へ持ち越す記録を置く。
 * ブラウザの拡張機能へのコマンドは使わないが、場所は `filesDir` の下の `commands` とする。
 */
fun androidAppFiles(context: Context): AppFiles =
    androidAppFilesIn(
        noBackupDirectory = context.noBackupFilesDir.toPath(),
        cacheDirectory = context.cacheDir.toPath(),
        filesDirectory = context.filesDir.toPath(),
    )

/**
 * バックアップの対象にしないディレクトリ [noBackupDirectory]、キャッシュのディレクトリ [cacheDirectory]、アプリのファイルの
 * ディレクトリ [filesDirectory] の中の、それぞれのファイルの場所。
 */
internal fun androidAppFilesIn(noBackupDirectory: Path, cacheDirectory: Path, filesDirectory: Path): AppFiles =
    AppFiles(
        settingsFile = noBackupDirectory.resolve(SETTINGS_FILE_NAME),
        cacheDirectory = cacheDirectory.resolve(CACHE_DIRECTORY_NAME),
        logDirectory = filesDirectory.resolve(LOG_DIRECTORY_NAME),
        stateDirectory = filesDirectory.resolve(STATE_DIRECTORY_NAME),
        deviceSettingsFile = noBackupDirectory.resolve(DEVICE_SETTINGS_FILE_NAME),
        commandDirectory = filesDirectory.resolve(COMMAND_DIRECTORY_NAME),
        updateDirectory = cacheDirectory.resolve(UPDATE_DIRECTORY_NAME),
    )
