package to.sava.cloudmarksmultiplatform.settings

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SealedSecretTest {
    private val iv = ByteArray(GCM_IV_SIZE) { it.toByte() }
    private val ciphertext = ByteArray(GCM_TAG_SIZE + 5) { (100 + it).toByte() }

    // IV のあとに暗号文を続けた 1 つのバイト列にし、それを分けると元の IV と暗号文に戻る
    @Test
    fun joinsAndSplits() {
        val bytes = SealedSecret(iv, ciphertext).toBytes()

        assertContentEquals(iv + ciphertext, bytes)
        val split = SealedSecret.of(bytes)
        assertContentEquals(iv, split.iv)
        assertContentEquals(ciphertext, split.ciphertext)
    }

    // IV は 12 バイト、認証タグは 16 バイト
    @Test
    fun ivAndTagSizes() {
        assertEquals(12, GCM_IV_SIZE)
        assertEquals(16, GCM_TAG_SIZE)
    }

    // 空の秘密の暗号文（認証タグだけ）も分けられる
    @Test
    fun splitsTagOnlyCiphertext() {
        val split = SealedSecret.of(ByteArray(GCM_IV_SIZE + GCM_TAG_SIZE))

        assertEquals(GCM_TAG_SIZE, split.ciphertext.size)
    }

    // IV と認証タグを合わせた長さに足りなければ、分けずに失敗する
    @Test
    fun tooShortCannotBeSplit() {
        listOf(0, GCM_IV_SIZE, GCM_IV_SIZE + GCM_TAG_SIZE - 1).forEach { size ->
            assertFailsWith<SecretProtectionException>("$size バイト") { SealedSecret.of(ByteArray(size)) }
        }
    }

    // IV の長さが違うか、暗号文が認証タグより短ければ、まとめずに失敗する
    @Test
    fun invalidPartsCannotBeJoined() {
        assertFailsWith<SecretProtectionException> { SealedSecret(ByteArray(GCM_IV_SIZE + 4), ciphertext) }
        assertFailsWith<SecretProtectionException> { SealedSecret(iv, ByteArray(GCM_TAG_SIZE - 1)) }
    }
}
