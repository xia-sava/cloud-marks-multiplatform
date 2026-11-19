package to.sava.cloudmarksmultiplatform

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopAppFilesTest {
    private val localAppData = "local-app-data"
    private val userHome = "user-home"

    /** 配布版（真）と開発版（偽）の、データを置くディレクトリの名前。 */
    private val directoryNames = mapOf(true to "CloudMarks", false to "CloudMarks-dev")

    // LOCALAPPDATA があれば、その下の、配布版なら CloudMarks、開発版なら CloudMarks-dev
    @Test
    fun usesLocalAppData() {
        directoryNames.forEach { (distributed, name) ->
            assertEquals(Path.of(localAppData, name), resolveAppDataPath(localAppData, userHome, distributed), "distributed=$distributed")
        }
    }

    // LOCALAPPDATA が無いか空なら、ホームの下の、配布版なら CloudMarks、開発版なら CloudMarks-dev
    @Test
    fun fallsBackToUserHome() {
        directoryNames.forEach { (distributed, name) ->
            listOf(null, "", " ").forEach { value ->
                assertEquals(
                    Path.of(userHome, name),
                    resolveAppDataPath(value, userHome, distributed),
                    "LOCALAPPDATA=$value, distributed=$distributed",
                )
            }
        }
    }

    // 設定のファイルはディレクトリの直下の settings.json、端末ごとの設定のファイルは同じく直下の device.json、
    // ローカルキャッシュはその下の cache、ログはその下の logs、次の起動へ持ち越す記録はその下の state、
    // アプリの更新でダウンロードした配布物はその下の updates
    @Test
    fun filesAreInDirectory() {
        val directory = Path.of(localAppData, "CloudMarks")

        val files = desktopAppFilesIn(directory)

        assertEquals(directory.resolve("settings.json"), files.settingsFile)
        assertEquals(directory.resolve("device.json"), files.deviceSettingsFile)
        assertEquals(directory.resolve("commands"), files.commandDirectory)
        assertEquals(directory.resolve("cache"), files.cacheDirectory)
        assertEquals(directory.resolve("logs"), files.logDirectory)
        assertEquals(directory.resolve("state"), files.stateDirectory)
        assertEquals(directory.resolve("updates"), files.updateDirectory)
    }

    // アプリの更新でダウンロードした配布物は、配布版なら CloudMarks の下の updates、開発版なら CloudMarks-dev の下の updates
    @Test
    fun updateDirectoryFollowsVariant() {
        directoryNames.forEach { (distributed, name) ->
            assertEquals(
                Path.of(localAppData, name, "updates"),
                desktopAppFilesIn(resolveAppDataPath(localAppData, userHome, distributed)).updateDirectory,
                "distributed=$distributed",
            )
        }
    }
}
