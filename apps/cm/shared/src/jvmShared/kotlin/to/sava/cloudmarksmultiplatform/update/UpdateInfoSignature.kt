package to.sava.cloudmarksmultiplatform.update

import to.sava.cloudmarksmultiplatform.files.describeThrowable
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.ProviderException
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** 更新情報の署名の方式。Android（minSdk 30）と JVM の両方が標準で持つ。 */
private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

private const val KEY_ALGORITHM = "EC"

/**
 * 更新情報の署名を確かめる公開鍵。ECDSA P-256 の公開鍵の、X.509 SubjectPublicKeyInfo の DER を base64 にしたもの。
 *
 * 配る経路（TLS）とも更新情報の中身とも別の、信頼の起点になる。対になる秘密鍵は、リリースのワークフローが更新情報に署名するときだけ使う。
 * 配ったあとは替えられない（替えると、それより前のアプリが更新を受け付けなくなる）。
 */
internal const val UPDATE_INFO_PUBLIC_KEY: String =
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEZ/SCr5q6ioqrciIQYJgvrQfiqh5vpTfTb5ZBBL0Gnd+9NNpT1s0mV8PUiKX+1o8NEyiYcgooPGcUgTlqdeXyBA=="

/**
 * 受け取った更新情報のバイト列 [updateInfo] への署名 [signature]（DER の署名を base64 にしたもの。空白と改行は除いて読む）を、
 * 公開鍵 [publicKey]（[UPDATE_INFO_PUBLIC_KEY] と同じ形）で確かめる。
 *
 * 署名が合えば真。署名か公開鍵を読めない、署名が合わない、バイト列が署名したものと違うときは偽で、偽の理由を [log] に書く。
 */
internal fun verifyUpdateInfoSignature(
    updateInfo: ByteArray,
    signature: String,
    publicKey: String,
    log: (String) -> Unit,
): Boolean =
    try {
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initVerify(publicKeyOf(publicKey))
            update(updateInfo)
            verify(decodeBase64(signature))
        }.also { verified ->
            if (!verified) log("更新情報の署名が合わない")
        }
    } catch (e: GeneralSecurityException) {
        log("更新情報の署名を確かめられなかった: ${describeThrowable(e)}")
        false
    } catch (e: IllegalArgumentException) {
        log("更新情報の署名か公開鍵が base64 として読めない: ${describeThrowable(e)}")
        false
    } catch (e: ProviderException) {
        log("更新情報の署名を確かめられなかった: ${describeThrowable(e)}")
        false
    }

private fun publicKeyOf(base64: String): PublicKey =
    KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(decodeBase64(base64)))

/** 空白（改行を含む）をすべて除いてから base64 を読む。配るときに、末尾に改行が付くことや、途中で折り返されることがある。 */
private fun decodeBase64(text: String): ByteArray = Base64.getDecoder().decode(text.filterNot { it.isWhitespace() })
