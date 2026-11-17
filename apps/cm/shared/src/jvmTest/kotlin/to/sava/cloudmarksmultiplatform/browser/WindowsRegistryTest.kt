package to.sava.cloudmarksmultiplatform.browser

import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** どの Windows にもある、Windows の版の情報を持つキー。 */
private const val CURRENT_VERSION = "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion"

/** どの Windows にもあるキー。子に `Microsoft` を持つ。 */
private const val SOFTWARE = "SOFTWARE"

/** 利用者の環境変数を持つキー。`TEMP` は、環境変数を含む文字列（`%USERPROFILE%\…`）で書かれている。 */
private const val ENVIRONMENT = "Environment"

/** 拡張子 `.exe` のキー。既定の値に、実行ファイルの種類の名前 [EXE_CLASS] を持つ。 */
private const val EXE_EXTENSION_KEY = "SOFTWARE\\Classes\\.exe"

/** [EXE_EXTENSION_KEY] の既定の値。 */
private const val EXE_CLASS = "exefile"

/** どこにも無いキー。 */
private const val MISSING_KEY = "SOFTWARE\\to.sava.cloudmarksmultiplatform\\missing-key-for-test"

/** Windows のレジストリを実際に読む。書きはしない。Windows でだけ走る。 */
class WindowsRegistryTest {
    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    private fun assumeWindows() {
        assumeTrue("レジストリは Windows でだけ読める", isWindows)
    }

    // 文字列の値を読める。子のキーの名前を読める
    @Test
    fun readsExistingValueAndSubKeys() {
        assumeWindows()

        val productName = WindowsRegistry.stringValue(RegistryRoot.LOCAL_MACHINE, CURRENT_VERSION, "ProductName")
        val subKeys = WindowsRegistry.subKeyNames(RegistryRoot.LOCAL_MACHINE, SOFTWARE)

        assertFalse(productName.isNullOrBlank(), "$productName")
        assertTrue(subKeys.any { it.equals("Microsoft", ignoreCase = true) }, "$subKeys")
    }

    // キーの既定の値（名前の無い値）を読める
    @Test
    fun readsDefaultValue() {
        assumeWindows()

        assertEquals(EXE_CLASS, WindowsRegistry.stringValue(RegistryRoot.LOCAL_MACHINE, EXE_EXTENSION_KEY, null))
    }

    // 無いキーの子のキーは空、無いキーと無い値の文字列は null
    @Test
    fun missingKeyIsEmptyAndNull() {
        assumeWindows()

        assertEquals(emptyList(), WindowsRegistry.subKeyNames(RegistryRoot.LOCAL_MACHINE, MISSING_KEY))
        assertNull(WindowsRegistry.stringValue(RegistryRoot.LOCAL_MACHINE, MISSING_KEY, null))
        assertNull(WindowsRegistry.stringValue(RegistryRoot.LOCAL_MACHINE, CURRENT_VERSION, "missing-value-for-test"))
    }

    // 環境変数を含む文字列の値は、展開して返す
    @Test
    fun expandableValueIsExpanded() {
        assumeWindows()

        val temp = assertNotNull(WindowsRegistry.stringValue(RegistryRoot.CURRENT_USER, ENVIRONMENT, "TEMP"))

        assertFalse('%' in temp, temp)
        assertTrue(temp.isNotBlank(), temp)
    }
}
