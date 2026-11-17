package to.sava.cloudmarksmultiplatform.browser

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import to.sava.cloudmarksmultiplatform.files.replaceFileAtomically
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

private const val BROWSERS = "browsers"

/**
 * 拡張機能が入っていると分かったブラウザの記録（`docs/native-messaging.md` の `browsers-with-extension.json`。
 * `{"browsers":[…]}`）を読んだ結果。
 */
internal sealed interface BrowsersWithExtensionReadResult {
    /** 読めた。[browsers] は実行ファイルのパスを [normalizedExecutable] の形にそろえたもの。ファイルが無いときは空。 */
    data class Read(val browsers: List<String>) : BrowsersWithExtensionReadResult

    /** 読めたが、記録の形でない（JSON でない・オブジェクトでない・`browsers` が無いか配列でない・文字列でない要素がある・UTF-8 でない）。 */
    data object Broken : BrowsersWithExtensionReadResult

    /** 読めなかった。 */
    data class Failed(val cause: IOException) : BrowsersWithExtensionReadResult
}

/** 実行ファイルのパス [path] を、記録に書き、記録と比べる形にする。比べやすいように小文字にそろえる。 */
internal fun normalizedExecutable(path: String): String = path.lowercase()

/**
 * 拡張機能が入っていると分かったブラウザの記録 [file] を読む。記録は Native Messaging のホストが書き足し、デスクトップのアプリが、
 * 複数の項目を開けるブラウザを決めるのに読む。読み込みはブロックする。
 */
internal fun readBrowsersWithExtension(file: Path): BrowsersWithExtensionReadResult {
    val bytes = try {
        Files.readAllBytes(file)
    } catch (e: NoSuchFileException) {
        return BrowsersWithExtensionReadResult.Read(emptyList())
    } catch (e: IOException) {
        return BrowsersWithExtensionReadResult.Failed(e)
    }
    return try {
        val browsers = (Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject)
            ?.get(BROWSERS) as? JsonArray
        browsers?.map { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
            ?.takeIf { paths -> paths.all { it != null } }
            ?.filterNotNull()
            ?.map(::normalizedExecutable)
            ?.let { BrowsersWithExtensionReadResult.Read(it) }
            ?: BrowsersWithExtensionReadResult.Broken
    } catch (e: SerializationException) {
        BrowsersWithExtensionReadResult.Broken
    } catch (e: CharacterCodingException) {
        BrowsersWithExtensionReadResult.Broken
    }
}

/**
 * 読んだ記録 [known]（[readBrowsersWithExtension] の [BrowsersWithExtensionReadResult.Read.browsers]）に、実行ファイル [executable] を
 * [normalizedExecutable] の形にそろえて書き足し、[file] に書く。既に載っていれば書かずに false を返す。
 * 同じディレクトリの一時ファイルに書いてから置き換える。ディレクトリが無ければ作る。書き込みはブロックする。
 *
 * @throws IOException 書けなかった
 */
internal fun appendBrowserWithExtension(file: Path, known: List<String>, executable: String): Boolean {
    val browser = normalizedExecutable(executable)
    if (browser in known) return false
    val record = buildJsonObject { put(BROWSERS, buildJsonArray { (known + browser).forEach { add(JsonPrimitive(it)) } }) }
    replaceFileAtomically(file, record.toString().encodeToByteArray())
    return true
}
