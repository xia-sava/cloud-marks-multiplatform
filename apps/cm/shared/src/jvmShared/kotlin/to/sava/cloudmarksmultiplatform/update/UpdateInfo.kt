package to.sava.cloudmarksmultiplatform.update

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import to.sava.cloudmarksmultiplatform.storage.jsonIntegerOrNull

/** 配布物の SHA-256 の表記。小文字の 16 進で 64 文字。 */
private val SHA256_PATTERN = Regex("[0-9a-f]{64}")

/** 更新情報を読む。知らない欄は無視する。 */
private val updateInfoJson = Json { ignoreUnknownKeys = true }

/**
 * 更新を配るプラットフォーム。
 *
 * @property key 更新情報の、このプラットフォームの項目のキー
 * @property assetName GitHub Releases に置く配布物の名前。バージョンが変わっても名前は変えない
 */
internal enum class UpdatePlatform(val key: String, val assetName: String) {
    DESKTOP("desktop", "cloudmarks-setup.exe"),
    ANDROID("android", "cloudmarks.apk"),
}

/**
 * 更新情報の、1 つのプラットフォームの項目。配布物の URL は載せない（アプリが決まった形で組む。[releaseAssetUrl]）。
 * 知らない欄は無視する。
 *
 * @property versionCode 配布しているバージョンの、更新の新旧の比較に使う整数。JSON の整数の字句でなければ読まない（文字列・小数・指数の形は
 * 受け付けない）
 * @property versionName 配布しているバージョンの文字列。空白だけのものは受け付けない
 * @property sha256 配布物の SHA-256。小文字の 16 進で 64 文字でなければ受け付けない
 * @throws IllegalArgumentException [versionName] か [sha256] の形が合わない
 */
@Serializable
internal data class PlatformRelease(
    @Serializable(with = JsonIntegerSerializer::class) val versionCode: Int,
    val versionName: String,
    val sha256: String,
) {
    init {
        require(versionName.isNotBlank()) { "versionName が空" }
        require(SHA256_PATTERN.matches(sha256)) { "sha256 が小文字の 16 進で 64 文字でない" }
    }
}

/**
 * 更新情報（GitHub Releases の `latest.json`。UTF-8 の JSON のオブジェクト）のバイト列 [bytes] から、[platform] の項目
 * （キー [UpdatePlatform.key]）だけを読む。ほかのキー（ほかのプラットフォームの項目を含む）は、形が合わなくても読まない。
 *
 * @return [platform] の項目。キーが無いか、値が null なら null
 * @throws IllegalArgumentException JSON のオブジェクトとして読めないか、[platform] の項目の形が合わない。[SerializationException] も含む
 */
internal fun decodePlatformRelease(bytes: ByteArray, platform: UpdatePlatform): PlatformRelease? {
    val root = updateInfoJson.parseToJsonElement(bytes.decodeToString()) as? JsonObject
        ?: throw IllegalArgumentException("更新情報が JSON のオブジェクトでない")
    val release = root[platform.key]?.takeIf { it !is JsonNull } ?: return null
    return updateInfoJson.decodeFromJsonElement(PlatformRelease.serializer(), release)
}

/** JSON の整数の字句で `Int` に収まるものだけを読む（[jsonIntegerOrNull]）。文字列にした数は受け付けない。 */
private object JsonIntegerSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("JsonInteger", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement()
            ?: throw SerializationException("JSON からしか読めない")
        return jsonIntegerOrNull(element) ?: throw SerializationException("整数でない: $element")
    }

    override fun serialize(encoder: Encoder, value: Int) {
        encoder.encodeInt(value)
    }
}
