package to.sava.cloudmarksmultiplatform.settings

import java.io.IOException
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val TRANSFORMATION = "AES/GCM/NoPadding"

/** GCM の IV の長さ（バイト）。暗号化するたびに、暗号の実装が作る。 */
internal const val GCM_IV_SIZE = 12

/** GCM の認証タグの長さ（ビット）。 */
private const val GCM_TAG_SIZE_BITS = 128

/** GCM の認証タグの長さ（バイト）。暗号文の末尾に付く。 */
internal const val GCM_TAG_SIZE = GCM_TAG_SIZE_BITS / Byte.SIZE_BITS

private const val PROTECT_ACTION = "暗号化"
private const val UNPROTECT_ACTION = "復号"

/** 暗号化に使う、ただ 1 つの鍵を持つ所。 */
internal interface SecretKeySource {
    /**
     * いまの鍵。まだ作っていなければ null。
     *
     * @throws UnrecoverableKeyException 鍵はあるが読めない
     */
    fun existingKey(): SecretKey?

    /** 鍵を新しく作って持ち、それを返す。 */
    fun newKey(): SecretKey

    /** 持っている鍵を消す。 */
    fun deleteKey()
}

/**
 * [keys] の鍵で、`AES/GCM/NoPadding` で暗号化する [SecretProtector]。
 *
 * [entropy] は追加の認証データにする。暗号文は、IV のあとに暗号文（認証タグを含む）を続けた 1 つのバイト列（[SealedSecret]）にする。
 * 暗号化するとき、鍵が無ければ作り、ある鍵が読めないか使えなければ、その鍵を消して 1 回だけ作り直す。復号では作り直さない。
 * 失敗はどれも [SecretProtectionException] にする。その文には例外の種類だけを入れる。
 */
internal class AesGcmProtector(private val keys: SecretKeySource) : SecretProtector {
    /** 鍵を探し、無ければ作る手順を、1 つずつ順に行う。 */
    private val keyPreparation = Any()

    override fun protect(data: ByteArray, entropy: ByteArray): ByteArray =
        translatingFailures(PROTECT_ACTION) {
            val cipher = encryptingCipher()
            cipher.updateAAD(entropy)
            val ciphertext = cipher.doFinal(data)
            val iv = cipher.iv ?: throw SecretProtectionException("暗号化の IV が無い")
            SealedSecret(iv, ciphertext).toBytes()
        }

    override fun unprotect(data: ByteArray, entropy: ByteArray): ByteArray {
        val sealed = SealedSecret.of(data)
        return translatingFailures(UNPROTECT_ACTION) {
            val key = keys.existingKey() ?: throw SecretProtectionException("復号する鍵が無い")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_SIZE_BITS, sealed.iv))
            cipher.updateAAD(entropy)
            cipher.doFinal(sealed.ciphertext)
        }
    }

    /** 暗号化する [Cipher]。いまの鍵で用意できなければ（鍵が無い・読めない・使えない）、鍵を新しく作って用意する。 */
    private fun encryptingCipher(): Cipher =
        synchronized(keyPreparation) { existingKeyCipher() ?: encryptingCipherOf(keys.newKey()) }

    /** いまの鍵で暗号化する [Cipher]。鍵が無ければ null。鍵が読めないか使えなければ、その鍵を消して null。 */
    private fun existingKeyCipher(): Cipher? =
        try {
            keys.existingKey()?.let(::encryptingCipherOf)
        } catch (e: UnrecoverableKeyException) {
            keys.deleteKey()
            null
        } catch (e: InvalidKeyException) {
            // 鍵が無効になった（KeyPermanentlyInvalidatedException を含む）
            keys.deleteKey()
            null
        }
}

/** [key] で暗号化する [Cipher]。IV は、暗号の実装が作る。 */
private fun encryptingCipherOf(key: SecretKey): Cipher =
    Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }

/** [block] を走らせ、鍵と暗号の失敗を、[action] ができなかったことを伝える [SecretProtectionException] にする。 */
private fun <T> translatingFailures(action: String, block: () -> T): T =
    try {
        block()
    } catch (e: GeneralSecurityException) {
        // 鍵を読めない・作れない・使えない、復号した結果が認証タグと合わない（別のエントロピーや壊れた暗号文）など
        throw protectionFailure(action, e)
    } catch (e: IOException) {
        // 鍵を置く所を読み込めない
        throw protectionFailure(action, e)
    } catch (e: ProviderException) {
        // 鍵を置く所の内部の失敗
        throw protectionFailure(action, e)
    } catch (e: IllegalStateException) {
        // 鍵を置く所を使える状態でない
        throw protectionFailure(action, e)
    }

private fun protectionFailure(action: String, cause: Exception): SecretProtectionException =
    SecretProtectionException("${action}できなかった（${cause::class.simpleName}）")

/**
 * 暗号化した秘密。[iv] は GCM の IV（[GCM_IV_SIZE] バイト）、[ciphertext] は認証タグ（[GCM_TAG_SIZE] バイト）を末尾に含む暗号文。
 * IV の長さが違うか、暗号文が認証タグより短ければ [SecretProtectionException] を投げる。
 */
internal class SealedSecret(val iv: ByteArray, val ciphertext: ByteArray) {
    init {
        if (iv.size != GCM_IV_SIZE) throw SecretProtectionException("IV の長さが $GCM_IV_SIZE バイトでない")
        if (ciphertext.size < GCM_TAG_SIZE) throw SecretProtectionException("暗号文が認証タグより短い")
    }

    /** [iv] のあとに [ciphertext] を続けた、1 つのバイト列。 */
    fun toBytes(): ByteArray = iv + ciphertext

    companion object {
        /** [toBytes] の形のバイト列を、IV と暗号文に分ける。IV と認証タグを合わせた長さに足りなければ [SecretProtectionException] を投げる。 */
        fun of(bytes: ByteArray): SealedSecret {
            if (bytes.size < GCM_IV_SIZE + GCM_TAG_SIZE) throw SecretProtectionException("暗号文が IV と認証タグより短い")
            return SealedSecret(bytes.copyOfRange(0, GCM_IV_SIZE), bytes.copyOfRange(GCM_IV_SIZE, bytes.size))
        }
    }
}
