package to.sava.cloudmarksmultiplatform.settings

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import to.sava.cloudmarksmultiplatform.files.describeIoFailure
import to.sava.cloudmarksmultiplatform.files.replaceFileAtomically
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.encoding.Base64

/**
 * 認証情報の暗号化に添える追加エントロピー。アプリと秘密の種類で決まる。
 * 変えると、それまでに書いたファイルの認証情報を復号できなくなる。
 */
private val CREDENTIALS_ENTROPY: ByteArray = "to.sava.cloudmarksmultiplatform/credentials".encodeToByteArray()

private val settingsJson = Json { ignoreUnknownKeys = true }

/** ファイルに書く形。[credentials] は、[StoredCredentials] の JSON を UTF-8 にして暗号化したものの Base64。 */
@Serializable
private class StoredSettings(
    val region: String,
    val bucket: String,
    val prefix: String,
    val credentials: String,
)

/** 暗号化する前の認証情報の形。`toString` が値を出さないよう、data class にしない。 */
@Serializable
private class StoredCredentials(
    val accessKeyId: String,
    val secretAccessKey: String,
)

/** 読む途中の失敗を、[SettingsFile.read] の入口まで運ぶための内部の例外。入口で必ず捕まえて結果の値にする。 */
private class SettingsReadFailure(val result: SettingsReadResult) : Exception()

private fun corrupt(detail: String): Nothing = throw SettingsReadFailure(SettingsReadResult.Corrupt(detail))

/**
 * 設定のファイル（JSON）を読み書きする。
 *
 * S3 の接続先（リージョン・バケット・プレフィックス）と、[protector] で暗号化した認証情報を 1 つのファイルに入れる。
 * 認証情報は平文で書かない。暗号化できなければ、書かずに [SettingsWriteResult.ProtectionUnavailable] を返す。
 * 版の欄は持たず、読むときは知らない欄を無視する。
 *
 * 読み書きはブロックする。呼び出し側が I/O 用のスレッドで呼ぶ。
 *
 * @param path 設定のファイル。親のディレクトリは、書くときに無ければ作る
 */
class SettingsFile(
    private val path: Path,
    private val protector: SecretProtector,
) {
    /** 設定を読む。 */
    fun read(): SettingsReadResult =
        try {
            readText()
                ?.let { text -> SettingsReadResult.Found(decodeSettings(text)) }
                ?: SettingsReadResult.NotFound
        } catch (e: SettingsReadFailure) {
            e.result
        }

    /**
     * 設定を書く。
     *
     * 同じディレクトリの一時ファイルに書いてから、ファイルを置き換える。失敗したら一時ファイルを残さない。
     */
    fun write(settings: ConnectionSettings): SettingsWriteResult {
        val credentials = try {
            protector.protect(encodeCredentials(settings.credentials), CREDENTIALS_ENTROPY)
        } catch (e: SecretProtectionException) {
            return SettingsWriteResult.ProtectionUnavailable(e.message)
        }
        val stored = StoredSettings(settings.region, settings.bucket, settings.prefix, Base64.encode(credentials))
        return try {
            replaceFileAtomically(path, settingsJson.encodeToString(StoredSettings.serializer(), stored).encodeToByteArray())
            SettingsWriteResult.Success
        } catch (e: IOException) {
            SettingsWriteResult.Failed(describeIoFailure(e))
        }
    }

    /** ファイルの中身。ファイルが無ければ null。 */
    private fun readText(): String? =
        try {
            Files.readAllBytes(path).decodeToString(throwOnInvalidSequence = true)
        } catch (e: NoSuchFileException) {
            null
        } catch (e: CharacterCodingException) {
            corrupt("UTF-8 として読めない")
        } catch (e: IOException) {
            throw SettingsReadFailure(SettingsReadResult.Failed(describeIoFailure(e)))
        }

    private fun decodeSettings(text: String): ConnectionSettings =
        decodeJson(StoredSettings.serializer(), text, "設定").let { stored ->
            ConnectionSettings(stored.region, stored.bucket, stored.prefix, decryptCredentials(stored.credentials))
        }

    private fun decryptCredentials(encoded: String): S3Credentials {
        val encrypted = try {
            Base64.decode(encoded)
        } catch (e: IllegalArgumentException) {
            corrupt("`credentials` が Base64 として読めない")
        }
        val decrypted = try {
            protector.unprotect(encrypted, CREDENTIALS_ENTROPY)
        } catch (e: SecretProtectionException) {
            throw SettingsReadFailure(SettingsReadResult.Undecryptable(e.message))
        }
        val text = try {
            decrypted.decodeToString(throwOnInvalidSequence = true)
        } catch (e: CharacterCodingException) {
            corrupt("復号した認証情報が UTF-8 として読めない")
        }
        return decodeJson(StoredCredentials.serializer(), text, "復号した認証情報")
            .let { S3Credentials(it.accessKeyId, it.secretAccessKey) }
    }

    private fun encodeCredentials(credentials: S3Credentials): ByteArray =
        StoredCredentials(credentials.accessKeyId, credentials.secretAccessKey)
            .let { settingsJson.encodeToString(StoredCredentials.serializer(), it) }
            .encodeToByteArray()

    /**
     * JSON を読む。失敗は [SettingsReadResult.Corrupt] にして投げる。
     *
     * 例外のメッセージは入力の一部を含むため、結果に使わない。
     *
     * @param subject 結果の説明で、何を読もうとしたかを示す語
     */
    private fun <T> decodeJson(deserializer: DeserializationStrategy<T>, text: String, subject: String): T {
        val element = try {
            settingsJson.parseToJsonElement(text)
        } catch (e: SerializationException) {
            corrupt("$subject が JSON として読めない")
        }
        return try {
            settingsJson.decodeFromJsonElement(deserializer, element)
        } catch (e: SerializationException) {
            corrupt("$subject に、必須の値が無いか、形の合わない値がある")
        }
    }
}
