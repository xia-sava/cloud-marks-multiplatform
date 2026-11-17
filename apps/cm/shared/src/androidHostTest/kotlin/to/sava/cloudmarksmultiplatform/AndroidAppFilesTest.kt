package to.sava.cloudmarksmultiplatform

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidAppFilesTest {
    private val noBackupDirectory = Path.of("no-backup")
    private val cacheDirectory = Path.of("cache-dir")
    private val filesDirectory = Path.of("files")

    private val files = androidAppFilesIn(noBackupDirectory, cacheDirectory, filesDirectory)

    // 設定のファイルと端末ごとの設定のファイルは、バックアップの対象にしないディレクトリの直下の settings.json と device.json
    @Test
    fun settingsFilesAreInNoBackupDirectory() {
        assertEquals(noBackupDirectory.resolve("settings.json"), files.settingsFile)
        assertEquals(noBackupDirectory.resolve("device.json"), files.deviceSettingsFile)
    }

    // ローカルキャッシュは、キャッシュのディレクトリの下の cache
    @Test
    fun cacheIsInCacheDirectory() {
        assertEquals(cacheDirectory.resolve("cache"), files.cacheDirectory)
    }

    // ログと次の起動へ持ち越す記録とコマンドは、アプリのファイルのディレクトリの下の logs・state・commands
    @Test
    fun logsStateAndCommandsAreInFilesDirectory() {
        assertEquals(filesDirectory.resolve("logs"), files.logDirectory)
        assertEquals(filesDirectory.resolve("state"), files.stateDirectory)
        assertEquals(filesDirectory.resolve("commands"), files.commandDirectory)
    }
}
