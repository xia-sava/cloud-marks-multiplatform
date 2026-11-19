package to.sava.cloudmarksmultiplatform.update

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Deferred
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** 更新情報の、デスクトップの項目の SHA-256。 */
internal val DESKTOP_SHA256 = "d".repeat(64)

/** 更新情報の、Android の項目の SHA-256。 */
internal val ANDROID_SHA256 = "a".repeat(64)

/** 配布しているバージョンの、更新の新旧の比較に使う整数。 */
internal const val RELEASED_VERSION_CODE = 20

/** 配布しているバージョンの文字列。 */
internal const val RELEASED_VERSION_NAME = "2.0.0"

/** デスクトップと Android の項目を持つ更新情報。 */
internal val UPDATE_INFO = updateInfoJson(desktopSha256 = DESKTOP_SHA256)

/** デスクトップの項目の SHA-256 を [desktopSha256]、Android の項目の SHA-256 を [androidSha256] にした、デスクトップと Android の項目を持つ更新情報。 */
internal fun updateInfoJson(desktopSha256: String = DESKTOP_SHA256, androidSha256: String = ANDROID_SHA256): String =
    """{"android":{"versionCode":$RELEASED_VERSION_CODE,"versionName":"$RELEASED_VERSION_NAME","sha256":"$androidSha256"},""" +
        """"desktop":{"versionCode":$RELEASED_VERSION_CODE,"versionName":"$RELEASED_VERSION_NAME","sha256":"$desktopSha256"}}"""

/** 配布版の、バージョン 1.0.0 のアプリ。[RELEASED_VERSION_CODE] より古い。 */
internal val DISTRIBUTED_VERSION = AppVersion(versionCode = 10, versionName = "1.0.0", displayName = "1.0.0", distributed = true)

/** 開発版のアプリ。[RELEASED_VERSION_CODE] より古い。 */
internal val DEV_VERSION = AppVersion(versionCode = 1, versionName = "0.0.0", displayName = "0.0.0 (dev)", distributed = false)

/** [content] の SHA-256。小文字の 16 進。 */
internal fun sha256Of(content: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(content).toHexString()

/**
 * テストの中で作る、ECDSA P-256 の鍵の組。埋め込みの公開鍵（[UPDATE_INFO_PUBLIC_KEY]）の秘密鍵はテストで使わないので、
 * 署名が通る道を確かめるときは、ここで作った鍵の [publicKey] を確かめる側へ渡す。
 */
internal class TestSigningKey {
    private val keyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    /** X.509 SubjectPublicKeyInfo の DER を base64 にした公開鍵。 */
    val publicKey: String = Base64.getEncoder().encodeToString(keyPair.public.encoded)

    /** [content] の UTF-8 のバイト列への署名。DER の署名を base64 にしたもの。 */
    fun sign(content: String): String =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(content.encodeToByteArray())
            Base64.getEncoder().encodeToString(sign())
        }
}

/**
 * 更新情報 [updateInfo] と、その署名 [signature] を返す偽のエンジン。URL の末尾が `.sig` の要求には [signature] を、
 * ほかの要求には [updateInfo] を返す。[signature] が null なら、署名の要求には 404 を返す。
 * [gate] を渡すと、それが終わるまで応答を返さない。
 */
internal fun updateInfoEngine(updateInfo: String, signature: String?, gate: Deferred<Unit>? = null): MockEngine =
    MockEngine { request ->
        gate?.await()
        if (request.url.toString().endsWith(".sig")) {
            signature?.let { respond(content = it, status = HttpStatusCode.OK) }
                ?: respond(content = "", status = HttpStatusCode.NotFound)
        } else {
            respond(
                content = updateInfo,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
    }
