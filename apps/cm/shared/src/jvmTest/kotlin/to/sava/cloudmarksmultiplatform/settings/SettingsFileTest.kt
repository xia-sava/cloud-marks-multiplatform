package to.sava.cloudmarksmultiplatform.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.encoding.Base64
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class SettingsFileTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** まだ無いディレクトリの下の設定のファイル。 */
    private val directory: Path by lazy { temporaryFolder.root.toPath().resolve("CloudMarks") }
    private val path: Path by lazy { directory.resolve("settings.json") }

    private fun settingsFile(protector: SecretProtector = FakeSecretProtector()): SettingsFile = SettingsFile(path, protector)

    /** [text] を、設定のファイルとしてそのまま置く。 */
    private fun placeRaw(text: String) {
        Files.createDirectories(directory)
        Files.writeString(path, text)
    }

    /** [json] の UTF-8 を、偽の暗号化で暗号化して Base64 にする。 */
    private fun encryptedCredentials(json: String): String =
        Base64.encode(FakeSecretProtector().protect(json.encodeToByteArray(), EXPECTED_CREDENTIALS_ENTROPY))

    /** 例の設定を、書く形の JSON のオブジェクトにする。 */
    private fun validStoredSettings(): JsonObject = buildJsonObject {
        put("region", SETTINGS.region)
        put("bucket", SETTINGS.bucket)
        put("prefix", SETTINGS.prefix)
        put(
            "credentials",
            encryptedCredentials("""{"accessKeyId":"${CREDENTIALS.accessKeyId}","secretAccessKey":"${CREDENTIALS.secretAccessKey}"}"""),
        )
    }

    // 書いた設定を読み直すと、同じ設定が返る。プレフィックスが空でもよい
    @Test
    fun writtenSettingsReadBack() {
        listOf(SETTINGS, SETTINGS.copy(prefix = "")).forEach { settings ->
            val file = settingsFile()

            assertEquals(SettingsWriteResult.Success, file.write(settings))

            assertEquals(SettingsReadResult.Found(settings), file.read(), settings.prefix)
        }
    }

    // ファイルが無ければ（親のディレクトリも無ければ）、無いとして返る
    @Test
    fun missingFileIsNotFound() {
        assertEquals(SettingsReadResult.NotFound, settingsFile().read())
    }

    // JSON として読めない中身は、壊れているとして返る
    @Test
    fun unparsableJsonIsCorrupt() {
        val inputs = mapOf(
            "空" to "",
            "途中で切れた" to """{"region":""",
            "JSON でない" to "region=ap-northeast-1",
        )

        inputs.forEach { (name, text) ->
            placeRaw(text)

            assertIs<SettingsReadResult.Corrupt>(settingsFile().read(), name)
        }
    }

    // オブジェクトでない JSON、必須の値が無い・null・文字列でないものは、壊れているとして返る
    @Test
    fun missingOrMistypedRequiredValueIsCorrupt() {
        val valid = validStoredSettings()
        val inputs = buildMap {
            put("配列", "[]")
            put("文字列", "\"settings\"")
            listOf("region", "bucket", "prefix", "credentials").forEach { key ->
                put("$key が無い", JsonObject(valid - key).toString())
                put("$key が null", JsonObject(valid + (key to JsonNull)).toString())
                put("$key が数", JsonObject(valid + (key to JsonPrimitive(1))).toString())
            }
        }

        inputs.forEach { (name, text) ->
            placeRaw(text)

            assertIs<SettingsReadResult.Corrupt>(settingsFile().read(), name)
        }
    }

    // UTF-8 として読めないファイルは、壊れているとして返る
    @Test
    fun invalidUtf8IsCorrupt() {
        Files.createDirectories(directory)
        Files.write(path, byteArrayOf('{'.code.toByte(), 0xff.toByte(), '}'.code.toByte()))

        assertIs<SettingsReadResult.Corrupt>(settingsFile().read())
    }

    // 認証情報が Base64 として読めない、または復号できても認証情報の形でなければ、壊れているとして返る
    @Test
    fun malformedCredentialsAreCorrupt() {
        val inputs = mapOf(
            "Base64 でない" to "not base64!",
            "復号すると JSON でない" to encryptedCredentials("not json"),
            "復号すると UTF-8 でない" to Base64.encode(
                FakeSecretProtector().protect(byteArrayOf(0xff.toByte()), EXPECTED_CREDENTIALS_ENTROPY),
            ),
            "secretAccessKey が無い" to encryptedCredentials("""{"accessKeyId":"${CREDENTIALS.accessKeyId}"}"""),
        )

        inputs.forEach { (name, credentials) ->
            placeRaw(JsonObject(validStoredSettings() + ("credentials" to JsonPrimitive(credentials))).toString())

            assertIs<SettingsReadResult.Corrupt>(settingsFile().read(), name)
        }
    }

    // 復号した認証情報が壊れているとき、壊れているという結果の説明に認証情報の値が現れない
    @Test
    fun corruptCredentialsDetailHidesValues() {
        val inputs = mapOf(
            "secretAccessKey が文字列でない" to
                """{"accessKeyId":"${CREDENTIALS.accessKeyId}","secretAccessKey":1}""",
            "途中で切れた" to
                """{"accessKeyId":"${CREDENTIALS.accessKeyId}","secretAccessKey":"${CREDENTIALS.secretAccessKey}""",
            "accessKeyId が文字列でない" to
                """{"accessKeyId":1,"secretAccessKey":"${CREDENTIALS.secretAccessKey}"}""",
        )

        inputs.forEach { (name, json) ->
            placeRaw(JsonObject(validStoredSettings() + ("credentials" to JsonPrimitive(encryptedCredentials(json)))).toString())

            val detail = assertIs<SettingsReadResult.Corrupt>(settingsFile().read(), name).detail
            assertFalse(CREDENTIALS.accessKeyId in detail, "$name: $detail")
            assertFalse(CREDENTIALS.secretAccessKey in detail, "$name: $detail")
        }
    }

    // ほかのユーザーが暗号化した認証情報や、暗号化の仕組みが使えない環境では、復号できないとして返る
    @Test
    fun credentialsThatCannotBeDecryptedAreUndecryptable() {
        val readers = mapOf(
            "ほかのユーザー" to FakeSecretProtector(owner = 2),
            "暗号化の仕組みが使えない" to FakeSecretProtector(available = false),
        )
        settingsFile().write(SETTINGS)

        readers.forEach { (name, protector) ->
            assertIs<SettingsReadResult.Undecryptable>(settingsFile(protector).read(), name)
        }
    }

    // 暗号化の仕組みが使えなければ、書かずにそう返し、ファイルもディレクトリも作らない
    @Test
    fun unavailableProtectionWritesNothing() {
        val result = settingsFile(FakeSecretProtector(available = false)).write(SETTINGS)

        assertIs<SettingsWriteResult.ProtectionUnavailable>(result)
        assertFalse(Files.exists(directory))
    }

    // 暗号化の仕組みが使えなければ、置いてあるファイルを書き換えない
    @Test
    fun unavailableProtectionKeepsExistingFile() {
        settingsFile().write(SETTINGS)
        val before = Files.readAllBytes(path)

        settingsFile(FakeSecretProtector(available = false)).write(SETTINGS.copy(bucket = "other-bucket"))

        assertContentEquals(before, Files.readAllBytes(path))
    }

    // 書いたファイルには、認証情報が平文で現れない。Base64 を解いた中身にも現れない
    @Test
    fun writtenFileHasNoPlaintextCredentials() {
        settingsFile().write(SETTINGS)
        val bytes = Files.readAllBytes(path)
        val credentials = Json.parseToJsonElement(bytes.decodeToString()).jsonObject.getValue("credentials")
            .jsonPrimitive.content
            .let { Base64.decode(it) }

        listOf(CREDENTIALS.accessKeyId, CREDENTIALS.secretAccessKey).map { it.encodeToByteArray() }.forEach { secret ->
            assertFalse(bytes.containsSequence(secret), secret.decodeToString())
            assertFalse(credentials.containsSequence(secret), secret.decodeToString())
        }
    }

    // 書いたファイルは、接続先の 3 つの値と、決まったエントロピーで暗号化した認証情報の JSON の Base64 を持つ
    @Test
    fun writtenFileHasDocumentedShape() {
        settingsFile().write(SETTINGS)

        val stored = Json.parseToJsonElement(Files.readString(path)).jsonObject

        assertEquals(setOf("region", "bucket", "prefix", "credentials"), stored.keys)
        assertEquals(SETTINGS.region, stored.getValue("region").jsonPrimitive.content)
        assertEquals(SETTINGS.bucket, stored.getValue("bucket").jsonPrimitive.content)
        assertEquals(SETTINGS.prefix, stored.getValue("prefix").jsonPrimitive.content)
        val decrypted = FakeSecretProtector().unprotect(
            Base64.decode(stored.getValue("credentials").jsonPrimitive.content),
            EXPECTED_CREDENTIALS_ENTROPY,
        )
        assertEquals(
            """{"accessKeyId":"${CREDENTIALS.accessKeyId}","secretAccessKey":"${CREDENTIALS.secretAccessKey}"}""",
            decrypted.decodeToString(),
        )
    }

    // 暗号化と復号には、アプリと秘密の種類で決まるエントロピーを渡す
    @Test
    fun protectorReceivesCredentialsEntropy() {
        val protector = FakeSecretProtector()
        val file = settingsFile(protector)

        file.write(SETTINGS)
        file.read()

        assertEquals(2, protector.entropies.size)
        protector.entropies.forEach { assertContentEquals(EXPECTED_CREDENTIALS_ENTROPY, it) }
    }

    // 置いてあるファイルを置き換えると新しい設定が読め、一時ファイルが残らない
    @Test
    fun replacingLeavesNoTemporaryFile() {
        val updated = SETTINGS.copy(bucket = "other-bucket", credentials = S3Credentials("AKIAOTHER", "other-secret"))
        settingsFile().write(SETTINGS)

        assertEquals(SettingsWriteResult.Success, settingsFile().write(updated))

        assertEquals(SettingsReadResult.Found(updated), settingsFile().read())
        assertEquals(listOf("settings.json"), directory.listDirectoryEntries().map { it.name })
    }

    // 置き換えに失敗したら I/O の失敗として返し、一時ファイルが残らない
    @Test
    fun failedReplaceLeavesNoTemporaryFile() {
        // 中身のあるディレクトリは、ファイルで置き換えられない
        Files.createDirectories(path.resolve("occupied"))

        val result = settingsFile().write(SETTINGS)

        assertIs<SettingsWriteResult.Failed>(result)
        assertEquals(listOf("settings.json"), directory.listDirectoryEntries().map { it.name })
    }

    // 知らない欄は無視して読む
    @Test
    fun unknownKeysAreIgnored() {
        val withUnknownKeys = JsonObject(
            validStoredSettings() + mapOf(
                "version" to JsonPrimitive(2),
                "window" to buildJsonObject { put("width", 800) },
            ),
        )
        placeRaw(withUnknownKeys.toString())

        assertEquals(SettingsReadResult.Found(SETTINGS), settingsFile().read())
    }

    // ファイルを読めなければ（ファイルの代わりにディレクトリがあるなど）、I/O の失敗として返る
    @Test
    fun unreadableFileIsFailed() {
        Files.createDirectories(path)

        assertIs<SettingsReadResult.Failed>(settingsFile().read())
    }

    // 認証情報と設定の文字列は、認証情報の値を含まない
    @Test
    fun toStringHidesCredentials() {
        listOf(CREDENTIALS.toString(), SETTINGS.toString(), SettingsReadResult.Found(SETTINGS).toString()).forEach { text ->
            assertFalse(CREDENTIALS.accessKeyId in text, text)
            assertFalse(CREDENTIALS.secretAccessKey in text, text)
        }
    }
}
