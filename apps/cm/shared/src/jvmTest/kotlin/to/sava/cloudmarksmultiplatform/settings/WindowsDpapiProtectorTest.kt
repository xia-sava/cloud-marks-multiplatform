package to.sava.cloudmarksmultiplatform.settings

import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Windows の DPAPI を実際に呼ぶ。Windows でだけ走るものと、Windows でないときだけ走るものがある。 */
class WindowsDpapiProtectorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    private val data = """{"accessKeyId":"${CREDENTIALS.accessKeyId}","secretAccessKey":"${CREDENTIALS.secretAccessKey}"}"""
        .encodeToByteArray()
    private val entropy = "to.sava.cloudmarksmultiplatform/test".encodeToByteArray()

    private fun assumeWindows() {
        assumeTrue("DPAPI は Windows でだけ使える", isWindows)
    }

    // 暗号化したものを同じエントロピーで復号すると、元のバイト列に戻る
    @Test
    fun roundTrips() {
        assumeWindows()

        val restored = WindowsDpapiProtector.unprotect(WindowsDpapiProtector.protect(data, entropy), entropy)

        assertContentEquals(data, restored)
    }

    // 暗号文に、元のバイト列もシークレットも現れない
    @Test
    fun ciphertextDoesNotContainPlaintext() {
        assumeWindows()

        val encrypted = WindowsDpapiProtector.protect(data, entropy)

        assertFalse(encrypted.containsSequence(data))
        assertFalse(encrypted.containsSequence(CREDENTIALS.secretAccessKey.encodeToByteArray()))
    }

    // 暗号化したときと違うエントロピーでは復号できず、失敗した関数と GetLastError の値を添えて失敗する
    @Test
    fun differentEntropyCannotUnprotect() {
        assumeWindows()
        val encrypted = WindowsDpapiProtector.protect(data, entropy)

        val failure = assertFailsWith<SecretProtectionException> {
            WindowsDpapiProtector.unprotect(encrypted, "to.sava.cloudmarksmultiplatform/other".encodeToByteArray())
        }

        assertTrue("CryptUnprotectData" in failure.message, failure.message)
        assertTrue("GetLastError" in failure.message, failure.message)
    }

    // 設定のファイルを DPAPI で暗号化して書き、読み直せる
    @Test
    fun settingsFileRoundTripsWithDpapi() {
        assumeWindows()
        val file = SettingsFile(temporaryFolder.root.toPath().resolve("settings.json"), WindowsDpapiProtector)

        assertEquals(SettingsWriteResult.Success, file.write(SETTINGS))

        assertEquals(SettingsReadResult.Found(SETTINGS), file.read())
    }

    // Windows でなければ、暗号化も復号も使えないものとして失敗する
    @Test
    fun unavailableOutsideWindows() {
        assumeFalse("Windows でないときだけ確かめる", isWindows)

        assertFailsWith<SecretProtectionException> { WindowsDpapiProtector.protect(data, entropy) }
        assertFailsWith<SecretProtectionException> { WindowsDpapiProtector.unprotect(data, entropy) }
    }
}
