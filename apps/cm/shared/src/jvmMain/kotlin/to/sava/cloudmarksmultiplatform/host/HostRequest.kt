package to.sava.cloudmarksmultiplatform.host

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import to.sava.cloudmarksmultiplatform.operations.AddBookmarks
import to.sava.cloudmarksmultiplatform.operations.AddFolder
import to.sava.cloudmarksmultiplatform.operations.NewItem
import to.sava.cloudmarksmultiplatform.operations.Operation
import java.nio.charset.CharacterCodingException
import kotlin.time.Instant

/** 取り決めの版。要求の `protocol` の値。 */
internal const val PROTOCOL_VERSION = 1

private const val PROTOCOL = "protocol"
private const val TYPE = "type"
private const val NAME = "name"
private const val TABS = "tabs"
private const val URL = "url"
private const val TITLE = "title"

private const val TYPE_ADD_FOLDER = "addFolder"
private const val TYPE_ADD_BOOKMARKS = "addBookmarks"

/** 拡張から送られたタブ。[url] は空でない。[title] は空でもよい。 */
internal data class HostTab(val url: String, val title: String)

/**
 * 拡張からの要求（`docs/native-messaging.md` の「拡張から送るもの」）。[tabs] は 1 つ以上。
 * URL とタイトルを含むので、ログに渡さない。
 */
internal sealed interface HostRequest {
    val tabs: List<HostTab>

    /** フォルダとして追加する。[name] はフォルダの名前で、空でない。 */
    data class AddFolder(val name: String, override val tabs: List<HostTab>) : HostRequest

    /** ブックマークとして追加する。 */
    data class AddBookmarks(override val tabs: List<HostTab>) : HostRequest
}

/** 要求を読んだ結果。 */
internal sealed interface HostRequestParseResult {
    data class Valid(val request: HostRequest) : HostRequestParseResult

    /** 取り決めに合わない。[reason] は後から追うための短い文で、要求の中の値を含まない。 */
    data class Invalid(val reason: String) : HostRequestParseResult
}

/** 要求を読む途中で、取り決めに合わないと分かったことを [parseHostRequest] まで運ぶための内部の例外。[parseHostRequest] で必ず捕まえる。 */
private class InvalidRequestException(val reason: String) : Exception(reason)

private fun invalid(reason: String): Nothing = throw InvalidRequestException(reason)

/**
 * メッセージの本体 [body]（UTF-8 の JSON）を、要求として読む。
 *
 * `protocol` が [PROTOCOL_VERSION] でない、`type` が知らない値、欄が欠けているか型が違う、`addFolder` の `name` が空、
 * `tabs` が空、`tabs[].url` が空のときは、取り決めに合わないとする。表に無い欄は無視する。
 * `addBookmarks` の `name` は、あっても型によらず無視する。
 */
internal fun parseHostRequest(body: ByteArray): HostRequestParseResult =
    try {
        HostRequestParseResult.Valid(requestOf(parseObject(body)))
    } catch (e: InvalidRequestException) {
        HostRequestParseResult.Invalid(e.reason)
    }

/**
 * 要求を、S3 へ書く操作にする。`addFolder` は [AddFolder]、`addBookmarks` は [AddBookmarks]。
 * フォルダと項目の ID は [generateId] で振る。フォルダの ID を先に振り、項目の ID をタブの順に振る。
 *
 * @param added 追加した日時。各項目の `added` になる
 */
internal fun HostRequest.toOperation(added: Instant, generateId: () -> String): Operation =
    when (this) {
        is HostRequest.AddFolder -> generateId().let { folderId ->
            AddFolder(folderId = folderId, name = name, items = tabs.toNewItems(generateId), added = added)
        }
        is HostRequest.AddBookmarks -> AddBookmarks(items = tabs.toNewItems(generateId), added = added)
    }

private fun List<HostTab>.toNewItems(generateId: () -> String): List<NewItem> =
    map { tab -> NewItem(id = generateId(), url = tab.url, title = tab.title) }

private fun parseObject(body: ByteArray): JsonObject {
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

private fun requestOf(message: JsonObject): HostRequest {
    if (!message.hasProtocolVersion()) invalid("`$PROTOCOL` が $PROTOCOL_VERSION でない")
    return when (message.string(TYPE, TYPE)) {
        TYPE_ADD_FOLDER -> HostRequest.AddFolder(name = message.folderName(), tabs = message.tabs())
        TYPE_ADD_BOOKMARKS -> HostRequest.AddBookmarks(tabs = message.tabs())
        else -> invalid("`$TYPE` が知らない値")
    }
}

/** `protocol` が、JSON の整数の字句で [PROTOCOL_VERSION] と書かれているか。 */
private fun JsonObject.hasProtocolVersion(): Boolean =
    (get(PROTOCOL) as? JsonPrimitive)?.let { !it.isString && it.content == PROTOCOL_VERSION.toString() } == true

private fun JsonObject.folderName(): String =
    string(NAME, NAME).also { if (it.isEmpty()) invalid("`$NAME` が空") }

private fun JsonObject.tabs(): List<HostTab> {
    val tabs = get(TABS) as? JsonArray ?: invalid("`$TABS` が無いか、配列でない")
    if (tabs.isEmpty()) invalid("`$TABS` が空")
    return tabs.mapIndexed { index, element ->
        (element as? JsonObject)?.tab("$TABS[$index]") ?: invalid("`$TABS[$index]` がオブジェクトでない")
    }
}

/** タブ 1 つ。[label] は、取り決めに合わないときの説明で、このタブを指す語。 */
private fun JsonObject.tab(label: String): HostTab {
    val url = string(URL, "$label.$URL").also { if (it.isEmpty()) invalid("`$label.$URL` が空") }
    return HostTab(url = url, title = string(TITLE, "$label.$TITLE"))
}

/** 欄 [key] の文字列。無いか、文字列でなければ取り決めに合わないとする。[label] は、そのときの説明で欄を指す語。 */
private fun JsonObject.string(key: String, label: String): String =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid("`$label` が無いか、文字列でない")
