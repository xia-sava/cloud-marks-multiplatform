package to.sava.cloudmarksmultiplatform.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** 鍵を置くプロバイダ。 */
private const val ANDROID_KEY_STORE = "AndroidKeyStore"

/** 認証情報を暗号化する鍵の別名。変えると、それまでに書いた設定のファイルの認証情報を復号できなくなる。 */
private const val KEY_ALIAS = "to.sava.cloudmarksmultiplatform.credentials"

/** 鍵の長さ（ビット）。 */
private const val KEY_SIZE_BITS = 256

/**
 * Android Keystore の鍵で暗号化する [SecretProtector]。暗号化と復号は [AesGcmProtector] が行う。
 *
 * 鍵は AES-256 で、端末の外へ取り出せない。アプリで 1 つだけ持ち、初めて暗号化するときに作る。利用者の認証は求めない。
 * 鍵はアプリと端末に結びつき、アプリのデータを消すか、ほかの端末へ移すと復号できない。
 */
object AndroidKeystoreProtector : SecretProtector by AesGcmProtector(AndroidKeystoreKeys)

/** Android Keystore の、別名 [KEY_ALIAS] の鍵。 */
private object AndroidKeystoreKeys : SecretKeySource {
    override fun existingKey(): SecretKey? = keyStore().getKey(KEY_ALIAS, null) as? SecretKey

    override fun newKey(): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(KEY_SIZE_BITS)
                        .build(),
                )
            }
            .generateKey()

    override fun deleteKey() {
        keyStore().deleteEntry(KEY_ALIAS)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
}
