package to.sava.cloudmarksmultiplatform.update

import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateInfoSignatureTest {
    private val key = TestSigningKey()
    private val logs = mutableListOf<String>()

    private fun verify(updateInfo: String, signature: String, publicKey: String = key.publicKey): Boolean =
        verifyUpdateInfoSignature(updateInfo.encodeToByteArray(), signature, publicKey) { logs += it }

    // 対になる鍵で署名したものは、署名したバイト列に対して通り、ログに何も書かない
    @Test
    fun acceptsSignatureByMatchingKey() {
        assertTrue(verify(UPDATE_INFO, key.sign(UPDATE_INFO)))
        assertTrue(logs.isEmpty(), logs.toString())
    }

    // 署名したものと 1 バイトでも違うバイト列は通らず、合わないことをログに書く
    @Test
    fun rejectsTamperedBytes() {
        val tampered = UPDATE_INFO.replaceFirst("\"versionCode\":20", "\"versionCode\":21")

        assertFalse(verify(tampered, key.sign(UPDATE_INFO)))
        assertTrue(logs.isNotEmpty())
    }

    // 解釈が同じでも、空白を足したバイト列は通らない（確かめるのは受け取ったバイト列そのもの）
    @Test
    fun rejectsSameJsonWithDifferentBytes() {
        assertFalse(verify("$UPDATE_INFO\n", key.sign(UPDATE_INFO)))
    }

    // 別の鍵で署名したものは通らない
    @Test
    fun rejectsSignatureByAnotherKey() {
        assertFalse(verify(UPDATE_INFO, TestSigningKey().sign(UPDATE_INFO)))
    }

    // 空の署名・base64 として読めない署名・DER の署名でないものは通らず、理由をログに書く
    @Test
    fun rejectsUnreadableSignatures() {
        listOf("", "not base64!!", Base64.getEncoder().encodeToString("not a DER signature".encodeToByteArray())).forEach { signature ->
            logs.clear()

            assertFalse(verify(UPDATE_INFO, signature), "signature=$signature")
            assertTrue(logs.isNotEmpty(), "signature=$signature")
        }
    }

    // 読めない公開鍵では通らない
    @Test
    fun rejectsUnreadablePublicKey() {
        assertFalse(verify(UPDATE_INFO, key.sign(UPDATE_INFO), publicKey = "not a key"))
    }

    // 配るときに付いた前後の空白（末尾の改行など）は除いて読む
    @Test
    fun acceptsSignatureWithSurroundingWhitespace() {
        assertTrue(verify(UPDATE_INFO, " ${key.sign(UPDATE_INFO)}\r\n"))
    }

    // 76 桁で折り返した署名も、途中の改行を除いて読む（base64 の MIME の形）
    @Test
    fun acceptsSignatureFoldedAt76Columns() {
        val signature = key.sign(UPDATE_INFO)
        val folded = signature.chunked(76).joinToString("\r\n") + "\n"
        val shortChunks = signature.chunked(20).joinToString("\n")

        assertTrue(signature.length > 76, "the signature is not long enough to fold: ${signature.length}")
        assertTrue(verify(UPDATE_INFO, folded))
        assertTrue(verify(UPDATE_INFO, shortChunks))
    }

    // 埋め込みの公開鍵は、テストで作った鍵の署名を通さない
    @Test
    fun embeddedPublicKeyRejectsTestSignature() {
        assertFalse(verify(UPDATE_INFO, key.sign(UPDATE_INFO), publicKey = UPDATE_INFO_PUBLIC_KEY))
    }

    // 埋め込みの公開鍵は、X.509 SubjectPublicKeyInfo の DER を base64 にした、P-256（secp256r1）の公開鍵として読める
    @Test
    fun embeddedPublicKeyIsP256() {
        val p256 = AlgorithmParameters.getInstance("EC")
            .apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)

        val publicKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(UPDATE_INFO_PUBLIC_KEY))) as ECPublicKey

        assertEquals(p256.curve, publicKey.params.curve)
        assertEquals(p256.order, publicKey.params.order)
        assertEquals(p256.generator, publicKey.params.generator)
    }
}
