package to.sava.cloudmarksmultiplatform.update

import android.content.pm.ApplicationInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidAppVersionTest {
    // FLAG_DEBUGGABLE が立っていないアプリは配布版。表示はバージョンの文字列そのもの
    @Test
    fun nonDebuggableIsDistributed() {
        val version = androidAppVersionOf(57L, "0.1.0", applicationFlags = ApplicationInfo.FLAG_ALLOW_BACKUP)

        assertEquals(AppVersion(57, "0.1.0", "0.1.0", distributed = true), version)
    }

    // FLAG_DEBUGGABLE が立っているアプリは開発版。表示はビルドの時刻の無い (dev) を添えたもの
    @Test
    fun debuggableIsDev() {
        val flags = ApplicationInfo.FLAG_ALLOW_BACKUP or ApplicationInfo.FLAG_DEBUGGABLE

        val version = androidAppVersionOf(1L, "0.0.0", applicationFlags = flags)

        assertEquals(AppVersion(1, "0.0.0", "0.0.0 (dev)", distributed = false), version)
    }

    // versionName が無ければ、-Pcloudmarks.versionName を渡さないビルドと同じ 0.0.0
    @Test
    fun missingVersionNameFallsBackToDefault() {
        val version = androidAppVersionOf(1L, null, applicationFlags = 0)

        assertEquals("0.0.0", version.versionName)
        assertEquals("0.0.0", version.displayName)
    }

    // Int に収まらない versionCode は、黙って丸めず失敗にする
    @Test
    fun versionCodeBeyondIntFails() {
        assertFailsWith<ArithmeticException> {
            androidAppVersionOf(Int.MAX_VALUE.toLong() + 1, "0.1.0", applicationFlags = 0)
        }
    }
}
