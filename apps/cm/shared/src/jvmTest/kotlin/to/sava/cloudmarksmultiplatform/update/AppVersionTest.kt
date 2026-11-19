package to.sava.cloudmarksmultiplatform.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppVersionTest {
    // 配布版の表示はバージョンの文字列そのもの。ビルドの時刻の有無に関わらず添えない
    @Test
    fun distributedDisplaysVersionName() {
        listOf(1_700_000_000_000L, null).forEach { buildEpochMillis ->
            val version = appVersionOf(57, "0.1.0", distributed = true, buildEpochMillis = buildEpochMillis)

            assertEquals(AppVersion(57, "0.1.0", "0.1.0", distributed = true), version, "buildEpochMillis=$buildEpochMillis")
        }
    }

    // 開発版の表示は、バージョンの後ろに (dev-<ビルドの時刻>) を添えたもの
    @Test
    fun devDisplaysBuildTime() {
        val version = appVersionOf(1, "0.0.0", distributed = false, buildEpochMillis = 1_700_000_000_000L)

        assertTrue(Regex("""^0\.0\.0 \(dev-\d{8}_\d{6}\)$""").matches(version.displayName), version.displayName)
        assertEquals(devVersionName("0.0.0", 1_700_000_000_000L), version.displayName)
    }

    // 開発版でビルドの時刻が分からなければ、表示は (dev) だけを添えたもの。バージョンの文字列とコードは変わらない
    @Test
    fun devWithoutBuildTimeDisplaysMarker() {
        val version = appVersionOf(1, "0.0.0", distributed = false, buildEpochMillis = null)

        assertEquals(AppVersion(1, "0.0.0", "0.0.0 (dev)", distributed = false), version)
    }
}
