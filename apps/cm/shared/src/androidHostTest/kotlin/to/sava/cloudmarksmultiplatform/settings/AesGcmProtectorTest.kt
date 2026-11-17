package to.sava.cloudmarksmultiplatform.settings

import java.security.ProviderException
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AES-256 の鍵の長さ（バイト）。 */
private const val AES_256_KEY_SIZE = 32

/** AES の鍵として長さの合わない鍵の長さ（バイト）。暗号化の用意で InvalidKeyException になる。 */
private const val INVALID_KEY_SIZE = 7

private const val AES = "AES"

/** 乱数で作った AES-256 の鍵。 */
private fun aesKey(): SecretKey = SecretKeySpec(ByteArray(AES_256_KEY_SIZE).also(SecureRandom()::nextBytes), AES)

/** 長さが合わず、暗号化に使えない AES の鍵。 */
private fun unusableKey(): SecretKey = SecretKeySpec(ByteArray(INVALID_KEY_SIZE), AES)

/** 鍵を消したこと（[FakeKeySource.events]）。 */
private const val DELETED = "deleted"

/** 鍵を作ったこと（[FakeKeySource.events]）。 */
private const val CREATED = "created"

/**
 * テスト用の [SecretKeySource]。初めは [stored] を持つ（null なら持たない）。[unreadable] なら、持っている鍵を読もうとすると
 * [UnrecoverableKeyException] を投げる。鍵を作ると、[newKeys] を順に持つ。
 */
private class FakeKeySource(
    var stored: SecretKey?,
    var unreadable: Boolean = false,
    private val newKeys: ArrayDeque<SecretKey> = ArrayDeque(listOf(aesKey())),
) : SecretKeySource {
    /** 鍵を消したこと（[DELETED]）と作ったこと（[CREATED]）。起きた順。 */
    val events = mutableListOf<String>()

    override fun existingKey(): SecretKey? {
        if (unreadable) throw UnrecoverableKeyException("鍵を読めない")
        return stored
    }

    override fun newKey(): SecretKey =
        newKeys.removeFirst().also {
            stored = it
            unreadable = false
            events += CREATED
        }

    override fun deleteKey() {
        stored = null
        unreadable = false
        events += DELETED
    }
}

/** 暗号化する認証情報の、シークレットの部分。 */
private const val SECRET_ACCESS_KEY = "test-secret-access-key"

class AesGcmProtectorTest {
    private val data = """{"accessKeyId":"test-access-key-id","secretAccessKey":"$SECRET_ACCESS_KEY"}""".encodeToByteArray()
    private val entropy = "to.sava.cloudmarksmultiplatform/test".encodeToByteArray()

    // 暗号化したものを同じエントロピーで復号すると、元のバイト列に戻る
    @Test
    fun roundTrips() {
        val protector = AesGcmProtector(FakeKeySource(stored = aesKey()))

        val restored = protector.unprotect(protector.protect(data, entropy), entropy)

        assertContentEquals(data, restored)
    }

    // 暗号文は IV と認証タグの分だけ長く、元のバイト列もシークレットも現れない。暗号化するたびに IV が替わる
    @Test
    fun ciphertextHidesPlaintext() {
        val protector = AesGcmProtector(FakeKeySource(stored = aesKey()))

        val first = protector.protect(data, entropy)
        val second = protector.protect(data, entropy)

        assertEquals(GCM_IV_SIZE + data.size + GCM_TAG_SIZE, first.size)
        assertFalse(first.containsSequence(data))
        assertFalse(first.containsSequence(SECRET_ACCESS_KEY.encodeToByteArray()))
        assertFalse(SealedSecret.of(first).iv.contentEquals(SealedSecret.of(second).iv))
    }

    // 暗号化したときと違うエントロピーでは復号できず、SecretProtectionException になる
    @Test
    fun differentEntropyCannotUnprotect() {
        val protector = AesGcmProtector(FakeKeySource(stored = aesKey()))
        val encrypted = protector.protect(data, entropy)

        assertFailsWith<SecretProtectionException> {
            protector.unprotect(encrypted, "to.sava.cloudmarksmultiplatform/other".encodeToByteArray())
        }
    }

    // 暗号文（IV・暗号文・認証タグ）のどの 1 バイトを変えても復号できず、SecretProtectionException になる
    @Test
    fun tamperedCiphertextCannotUnprotect() {
        val protector = AesGcmProtector(FakeKeySource(stored = aesKey()))
        val encrypted = protector.protect(data, entropy)

        encrypted.indices.forEach { index ->
            val tampered = encrypted.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertFailsWith<SecretProtectionException>("$index バイト目") { protector.unprotect(tampered, entropy) }
        }
    }

    // 別の鍵で暗号化したものは復号できない
    @Test
    fun otherKeyCannotUnprotect() {
        val encrypted = AesGcmProtector(FakeKeySource(stored = aesKey())).protect(data, entropy)

        assertFailsWith<SecretProtectionException> { AesGcmProtector(FakeKeySource(stored = aesKey())).unprotect(encrypted, entropy) }
    }

    // 鍵や暗号の失敗は SecretProtectionException になり、その文は秘密を含まず、例外の種類を含む
    @Test
    fun failuresBecomeSecretProtectionException() {
        val failing = object : SecretKeySource {
            override fun existingKey(): SecretKey? = null

            override fun newKey(): SecretKey = throw ProviderException("鍵を作れない")

            override fun deleteKey() = Unit
        }

        val failure = assertFailsWith<SecretProtectionException> { AesGcmProtector(failing).protect(data, entropy) }

        assertTrue("ProviderException" in failure.message, failure.message)
        assertFalse(SECRET_ACCESS_KEY in failure.message, failure.message)
    }

    // 鍵が無いまま復号しようとすると、鍵を作らずに SecretProtectionException になる
    @Test
    fun missingKeyCannotUnprotect() {
        val keys = FakeKeySource(stored = null)
        val encrypted = AesGcmProtector(FakeKeySource(stored = aesKey())).protect(data, entropy)

        assertFailsWith<SecretProtectionException> { AesGcmProtector(keys).unprotect(encrypted, entropy) }

        assertEquals(emptyList(), keys.events)
    }

    // 鍵が無ければ、暗号化するときに作る。消さない
    @Test
    fun protectCreatesMissingKey() {
        val keys = FakeKeySource(stored = null)
        val protector = AesGcmProtector(keys)

        val restored = protector.unprotect(protector.protect(data, entropy), entropy)

        assertContentEquals(data, restored)
        assertEquals(listOf(CREATED), keys.events)
    }

    // 鍵があれば、暗号化するときに作り直さない
    @Test
    fun protectKeepsUsableKey() {
        val keys = FakeKeySource(stored = aesKey())

        AesGcmProtector(keys).protect(data, entropy)

        assertEquals(emptyList(), keys.events)
    }

    // ある鍵が読めない（UnrecoverableKeyException）か使えない（InvalidKeyException）なら、暗号化するときに消して 1 回だけ作り直し、
    // 作り直した鍵で暗号化する
    @Test
    fun protectRecreatesUnusableKey() {
        val cases = mapOf(
            "読めない鍵" to FakeKeySource(stored = aesKey(), unreadable = true),
            "使えない鍵" to FakeKeySource(stored = unusableKey()),
        )
        cases.forEach { (name, keys) ->
            val protector = AesGcmProtector(keys)

            val restored = protector.unprotect(protector.protect(data, entropy), entropy)

            assertContentEquals(data, restored, name)
            assertEquals(listOf(DELETED, CREATED), keys.events, name)
        }
    }

    // 作り直した鍵も使えなければ、それ以上作り直さずに SecretProtectionException になる
    @Test
    fun protectRecreatesOnlyOnce() {
        val keys = FakeKeySource(stored = unusableKey(), newKeys = ArrayDeque(listOf(unusableKey(), aesKey())))

        assertFailsWith<SecretProtectionException> { AesGcmProtector(keys).protect(data, entropy) }

        assertEquals(listOf(DELETED, CREATED), keys.events)
    }

    // 復号では、鍵が読めないか使えなくても、消さずに作り直さず、SecretProtectionException になる
    @Test
    fun unprotectDoesNotRecreateKey() {
        val encrypted = AesGcmProtector(FakeKeySource(stored = aesKey())).protect(data, entropy)
        val cases = mapOf(
            "読めない鍵" to FakeKeySource(stored = aesKey(), unreadable = true),
            "使えない鍵" to FakeKeySource(stored = unusableKey()),
        )
        cases.forEach { (name, keys) ->
            assertFailsWith<SecretProtectionException>(name) { AesGcmProtector(keys).unprotect(encrypted, entropy) }

            assertEquals(emptyList(), keys.events, name)
        }
    }
}

/** [sequence] が、このバイト列の中に続けて現れるか。 */
private fun ByteArray.containsSequence(sequence: ByteArray): Boolean =
    (0..size - sequence.size).any { start -> sequence.indices.all { this[start + it] == sequence[it] } }
