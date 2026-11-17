package to.sava.cloudmarksmultiplatform.browser

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.CharacterCodingException

/** タブの並びの欄の名前。拡張機能からの追加の要求と、タブを開くコマンドで共通。 */
internal const val TABS_KEY = "tabs"

/** タブの URL の欄の名前。 */
internal const val URL_KEY = "url"

/** タブのタイトルの欄の名前。 */
internal const val TITLE_KEY = "title"

/**
 * 拡張機能からの要求やコマンド（`docs/native-messaging.md`）を読む途中で、取り決めに合わないと分かったことを、読む関数
 * （[parseOpenTabsCommand] と、ホストの要求を読む関数）まで運ぶための内部の例外。それぞれで必ず捕まえる。
 *
 * @property reason 後から追うための短い文。要求やコマンドの中の値を含まない
 */
internal class InvalidMessageException(val reason: String) : Exception(reason)

/** 取り決めに合わないとする。 */
internal fun invalid(reason: String): Nothing = throw InvalidMessageException(reason)

/** [body] を、UTF-8 の JSON のオブジェクトとして読む。読めなければ取り決めに合わないとする。 */
internal fun parseObject(body: ByteArray): JsonObject {
    val text = try {
        body.decodeToString(throwOnInvalidSequence = true)
    } catch (e: CharacterCodingException) {
        invalid("UTF-8 として読めない")
    }
    val element = try {
        Json.parseToJsonElement(text)
    } catch (e: SerializationException) {
        invalid("JSON として読めない")
    }
    return element as? JsonObject ?: invalid("JSON のオブジェクトでない")
}

/** 欄 [key] の文字列。無いか、文字列でなければ取り決めに合わないとする。[label] は、そのときの説明で欄を指す語。 */
internal fun JsonObject.string(key: String, label: String): String =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid("`$label` が無いか、文字列でない")

/** 欄 [TABS_KEY] のタブの並び。1 つ以上。各タブの `url` は空でない文字列、`title` は文字列（空でもよい）。 */
internal fun JsonObject.tabs(): List<BrowserTab> {
    val tabs = get(TABS_KEY) as? JsonArray ?: invalid("`$TABS_KEY` が無いか、配列でない")
    if (tabs.isEmpty()) invalid("`$TABS_KEY` が空")
    return tabs.mapIndexed { index, element ->
        (element as? JsonObject)?.tab("$TABS_KEY[$index]") ?: invalid("`$TABS_KEY[$index]` がオブジェクトでない")
    }
}

/** タブ 1 つ。[label] は、取り決めに合わないときの説明で、このタブを指す語。 */
private fun JsonObject.tab(label: String): BrowserTab {
    val url = string(URL_KEY, "$label.$URL_KEY").also { if (it.isEmpty()) invalid("`$label.$URL_KEY` が空") }
    return BrowserTab(url = url, title = string(TITLE_KEY, "$label.$TITLE_KEY"))
}
