package to.sava.cloudmarksmultiplatform.settings

/**
 * 秘密のバイト列を、プラットフォームの仕組みで暗号化する・復号する。
 *
 * 暗号文は、暗号化したのと同じユーザー・同じ端末でだけ復号できるものとする。
 * [entropy] は秘密ではなく、ほかのアプリや別の種類の秘密の暗号文を取り違えて復号しないための値。
 * 暗号化したときと違う [entropy] では復号できない。
 */
interface SecretProtector {
    /** [data] を暗号化する。この環境で使えない、または失敗したら [SecretProtectionException] を投げる。 */
    fun protect(data: ByteArray, entropy: ByteArray): ByteArray

    /** [data] を復号する。この環境で使えない、または復号できなければ [SecretProtectionException] を投げる。 */
    fun unprotect(data: ByteArray, entropy: ByteArray): ByteArray
}

/** 暗号化または復号ができなかった。[message] は後から調べるための短い文で、秘密のバイト列を含まない。 */
class SecretProtectionException(override val message: String) : Exception(message)
