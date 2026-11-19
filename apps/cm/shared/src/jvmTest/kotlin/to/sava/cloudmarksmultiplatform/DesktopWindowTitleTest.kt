package to.sava.cloudmarksmultiplatform

import to.sava.cloudmarksmultiplatform.update.appVersionOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopWindowTitleTest {
    // 配布版は、アプリの名前とバージョンだけ。ビルドの時刻は添えない
    @Test
    fun distributedShowsVersionName() {
        listOf(1_700_000_000_000L, null).forEach { buildEpochMillis ->
            val title = desktopWindowTitle(appVersionOf(5, "1.2.3", distributed = true, buildEpochMillis = buildEpochMillis))

            assertEquals("Cloud Marks 1.2.3", title, "buildEpochMillis=$buildEpochMillis")
        }
    }

    // 開発版は、バージョンの後ろに (dev-<ビルドの時刻>) を添える
    @Test
    fun devShowsBuildTime() {
        val title = desktopWindowTitle(appVersionOf(5, "1.2.3", distributed = false, buildEpochMillis = 1_700_000_000_000L))

        assertTrue(Regex("""^Cloud Marks 1\.2\.3 \(dev-\d{8}_\d{6}\)$""").matches(title), title)
    }

    // 開発版でビルドの時刻が分からなければ、(dev) だけを添える
    @Test
    fun devWithoutBuildTimeShowsMarker() {
        val title = desktopWindowTitle(appVersionOf(5, "1.2.3", distributed = false, buildEpochMillis = null))

        assertEquals("Cloud Marks 1.2.3 (dev)", title)
    }
}
