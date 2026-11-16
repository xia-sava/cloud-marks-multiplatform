package to.sava.cloudmarksmultiplatform

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopAppFilesTest {
    private val localAppData = "local-app-data"
    private val userHome = "user-home"

    // LOCALAPPDATA があれば、その下の CloudMarks
    @Test
    fun usesLocalAppData() {
        assertEquals(Path.of(localAppData, "CloudMarks"), resolveAppDataPath(localAppData, userHome))
    }

    // LOCALAPPDATA が無いか空なら、ホームの下の CloudMarks
    @Test
    fun fallsBackToUserHome() {
        listOf(null, "", " ").forEach { value ->
            assertEquals(Path.of(userHome, "CloudMarks"), resolveAppDataPath(value, userHome), "LOCALAPPDATA=$value")
        }
    }

    // 設定のファイルはディレクトリの直下の settings.json、ローカルキャッシュはその下の cache、ログはその下の logs
    @Test
    fun filesAreInDirectory() {
        val directory = Path.of(localAppData, "CloudMarks")

        val files = desktopAppFilesIn(directory)

        assertEquals(directory.resolve("settings.json"), files.settingsFile)
        assertEquals(directory.resolve("cache"), files.cacheDirectory)
        assertEquals(directory.resolve("logs"), files.logDirectory)
    }
}
