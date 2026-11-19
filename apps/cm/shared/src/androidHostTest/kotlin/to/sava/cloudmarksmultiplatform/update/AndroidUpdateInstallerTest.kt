package to.sava.cloudmarksmultiplatform.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** このアプリのパッケージ名。 */
private const val OWN_PACKAGE = "to.sava.cloudmarksmultiplatform"

class AndroidUpdateInstallerTest {
    private val logs = mutableListOf<String>()

    // APK のパッケージ名がこのアプリのものなら通し、ログに書かない
    @Test
    fun ownPackageNamePasses() {
        assertTrue(isOwnPackageName(OWN_PACKAGE, OWN_PACKAGE) { logs += it })
        assertEquals(emptyList(), logs)
    }

    // 別のパッケージ名なら通さず、両方の名前をログに書く
    @Test
    fun otherPackageNameIsRefused() {
        assertFalse(isOwnPackageName("example.other", OWN_PACKAGE) { logs += it })
        assertTrue(logs.single().let { "example.other" in it && OWN_PACKAGE in it }, logs.toString())
    }

    // APK として読めなければ（パッケージ名が無い）通さず、読めなかったことをログに書く
    @Test
    fun unreadableApkIsRefused() {
        assertFalse(isOwnPackageName(null, OWN_PACKAGE) { logs += it })
        assertTrue(logs.single().let { "読めなかった" in it }, logs.toString())
    }
}
