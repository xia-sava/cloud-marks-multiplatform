package to.sava.cloudmarksmultiplatform.settings

/** 各バイトと XOR する値。 */
private const val MASK = 0x5a

private const val DEFAULT_OWNER: Byte = 1

/**
 * テスト用の [SecretProtector]。暗号化は、[owner] とエントロピーを前に付け、各バイトを XOR で変換する。
 *
 * 復号は、先頭が自分の [owner] と渡されたエントロピーでなければ失敗する。ほかのユーザーが暗号化したものや、
 * エントロピーの違うものを復号できないことを模擬する。
 *
 * @param owner 暗号化したユーザーの代わりの値
 * @param available false なら、暗号化も復号も失敗する。暗号化の仕組みが使えない環境を模擬する
 */
internal class FakeSecretProtector(
    private val owner: Byte = DEFAULT_OWNER,
    private val available: Boolean = true,
) : SecretProtector {
    /** 渡されたエントロピー。呼ばれた順。 */
    val entropies = mutableListOf<ByteArray>()

    override fun protect(data: ByteArray, entropy: ByteArray): ByteArray {
        entropies += entropy.copyOf()
        if (!available) throw SecretProtectionException("暗号化の仕組みが使えない")
        return byteArrayOf(owner) + entropy + data.masked()
    }

    override fun unprotect(data: ByteArray, entropy: ByteArray): ByteArray {
        entropies += entropy.copyOf()
        if (!available) throw SecretProtectionException("暗号化の仕組みが使えない")
        val header = byteArrayOf(owner) + entropy
        if (data.size < header.size || !data.copyOf(header.size).contentEquals(header)) {
            throw SecretProtectionException("復号できない")
        }
        return data.copyOfRange(header.size, data.size).masked()
    }
}

private fun ByteArray.masked(): ByteArray = ByteArray(size) { (this[it].toInt() xor MASK).toByte() }
