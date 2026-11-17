package to.sava.cloudmarksmultiplatform.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DefaultBrowserPackageTest {
    // 既定のブラウザが決まっていれば、引いた Activity のパッケージ
    @Test
    fun resolvedBrowserIsDefault() {
        assertEquals(
            "com.android.chrome",
            defaultBrowserPackageOf(packageName = "com.android.chrome", activityName = "com.google.android.apps.chrome.IntentDispatcher"),
        )
        assertEquals(
            "org.mozilla.firefox",
            defaultBrowserPackageOf(packageName = "org.mozilla.firefox", activityName = "org.mozilla.fenix.IntentReceiverActivity"),
        )
    }

    // 選択画面（パッケージが android か、Activity の名前に resolver を含む。大文字と小文字は区別しない）を引いたら、既定のブラウザは無い
    @Test
    fun chooserIsNotDefault() {
        listOf(
            "android" to "com.android.internal.app.ResolverActivity",
            "android" to null,
            "com.huawei.android.internal.app" to "com.huawei.android.internal.app.HwResolverActivity",
            "com.example.vendor" to "com.example.vendor.RESOLVER_ACTIVITY",
        ).forEach { (packageName, activityName) ->
            assertNull(defaultBrowserPackageOf(packageName, activityName), "$packageName/$activityName")
        }
    }

    // 引けなかった（パッケージが無いか空）ら、既定のブラウザは無い
    @Test
    fun unresolvedIsNotDefault() {
        assertNull(defaultBrowserPackageOf(packageName = null, activityName = null))
        assertNull(defaultBrowserPackageOf(packageName = "", activityName = "com.example.BrowserActivity"))
    }
}
