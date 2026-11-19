package to.sava.cloudmarksmultiplatform.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DevVersionTest {
    // ビルドの時刻が分かれば、バージョンの後ろに (dev-<時刻>) と添える
    @Test
    fun buildTimeIsAppendedToVersionName() {
        val shown = devVersionName("0.0.0", 1_700_000_000_000L)

        assertTrue(Regex("""^0\.0\.0 \(dev-\d{8}_\d{6}\)$""").matches(shown), shown)
    }

    // ビルドの時刻が分からなければ、(dev) だけを添える
    @Test
    fun devMarkerOnlyWhenBuildTimeUnknown() {
        assertEquals("0.0.0 (dev)", devVersionName("0.0.0", null))
    }
}
