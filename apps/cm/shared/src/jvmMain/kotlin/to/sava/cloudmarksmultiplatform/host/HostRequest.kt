package to.sava.cloudmarksmultiplatform.host

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import to.sava.cloudmarksmultiplatform.browser.BrowserTab
import to.sava.cloudmarksmultiplatform.browser.COMMAND_TOKEN_FORMAT
import to.sava.cloudmarksmultiplatform.browser.InvalidMessageException
import to.sava.cloudmarksmultiplatform.browser.invalid
import to.sava.cloudmarksmultiplatform.browser.parseObject
import to.sava.cloudmarksmultiplatform.browser.string
import to.sava.cloudmarksmultiplatform.browser.tabs
import to.sava.cloudmarksmultiplatform.operations.AddBookmarks
import to.sava.cloudmarksmultiplatform.operations.AddFolder
import to.sava.cloudmarksmultiplatform.operations.NewItem
import to.sava.cloudmarksmultiplatform.operations.Operation
import kotlin.time.Instant

/** 取り決めのバージョン。要求の `protocol` の値。 */
internal const val PROTOCOL_VERSION = 1

private const val PROTOCOL = "protocol"
private const val TYPE = "type"
private const val NAME = "name"
private const val TOKEN = "token"

private const val TYPE_ADD_FOLDER = "addFolder"
private const val TYPE_ADD_BOOKMARKS = "addBookmarks"
private const val TYPE_EXTENSION_PRESENT = "extensionPresent"
private const val TYPE_TAKE_COMMAND = "takeCommand"
private const val TYPE_OPEN_APP = "openApp"

/**
 * 拡張からの要求（`docs/native-messaging.md` の「拡張から送るもの」）。
 * 追加の要求は URL とタイトルを含むので、ログに渡さない。
 */
internal sealed interface HostRequest {
    /** S3 へ書く追加の要求。[tabs] は 1 つ以上。各タブの `url` は空でない。`title` は空でもよい。 */
    sealed interface Add : HostRequest {
        val tabs: List<BrowserTab>
    }

    /** フォルダとして追加する。[name] はフォルダの名前で、空でない。 */
    data class AddFolder(val name: String, override val tabs: List<BrowserTab>) : Add

    /** ブックマークとして追加する。 */
    data class AddBookmarks(override val tabs: List<BrowserTab>) : Add

    /** 拡張が入っていることの知らせ。 */
    data object ExtensionPresent : HostRequest

    /** アプリからのコマンドの受け取り。[token] は [COMMAND_TOKEN_FORMAT] に合う。 */
    data class TakeCommand(val token: String) : HostRequest

    /** デスクトップのアプリを開く。起動していなければ起動し、起動していればそのウィンドウを前に出す。 */
    data object OpenApp : HostRequest
}

/** 要求を読んだ結果。 */
internal sealed interface HostRequestParseResult {
    data class Valid(val request: HostRequest) : HostRequestParseResult

    /** 取り決めに合わない。[reason] は後から追うための短い文で、要求の中の値を含まない。 */
    data class Invalid(val reason: String) : HostRequestParseResult
}

/**
 * メッセージの本体 [body]（UTF-8 の JSON）を、要求として読む。
 *
 * `protocol` が [PROTOCOL_VERSION] でない、`type` が知らない値、欄が欠けているか型が違う、`addFolder` の `name` が空、
 * `tabs` が空、`tabs[].url` が空、`takeCommand` の `token` が小文字の 16 進で 32 文字でないときは、取り決めに合わないとする。
 * 表に無い欄は無視する。`addBookmarks` の `name` は、あっても型によらず無視する。
 */
internal fun parseHostRequest(body: ByteArray): HostRequestParseResult =
    try {
        HostRequestParseResult.Valid(requestOf(parseObject(body)))
    } catch (e: InvalidMessageException) {
        HostRequestParseResult.Invalid(e.reason)
    }

/**
 * 要求を、S3 へ書く操作にする。`addFolder` は [AddFolder]、`addBookmarks` は [AddBookmarks]。
 * フォルダと項目の ID は [generateId] で振る。フォルダの ID を先に振り、項目の ID をタブの順に振る。
 *
 * @param added 追加した日時。各項目の `added` になる
 */
internal fun HostRequest.Add.toOperation(added: Instant, generateId: () -> String): Operation =
    when (this) {
        is HostRequest.AddFolder -> generateId().let { folderId ->
            AddFolder(folderId = folderId, name = name, items = tabs.toNewItems(generateId), added = added)
        }
        is HostRequest.AddBookmarks -> AddBookmarks(items = tabs.toNewItems(generateId), added = added)
    }

private fun List<BrowserTab>.toNewItems(generateId: () -> String): List<NewItem> =
    map { tab -> NewItem(id = generateId(), url = tab.url, title = tab.title) }

private fun requestOf(message: JsonObject): HostRequest {
    if (!message.hasProtocolVersion()) invalid("`$PROTOCOL` が $PROTOCOL_VERSION でない")
    return when (message.string(TYPE, TYPE)) {
        TYPE_ADD_FOLDER -> HostRequest.AddFolder(name = message.folderName(), tabs = message.tabs())
        TYPE_ADD_BOOKMARKS -> HostRequest.AddBookmarks(tabs = message.tabs())
        TYPE_EXTENSION_PRESENT -> HostRequest.ExtensionPresent
        TYPE_TAKE_COMMAND -> HostRequest.TakeCommand(token = message.token())
        TYPE_OPEN_APP -> HostRequest.OpenApp
        else -> invalid("`$TYPE` が知らない値")
    }
}

/** `protocol` が、JSON の整数の字句で [PROTOCOL_VERSION] と書かれているか。 */
private fun JsonObject.hasProtocolVersion(): Boolean =
    (get(PROTOCOL) as? JsonPrimitive)?.let { !it.isString && it.content == PROTOCOL_VERSION.toString() } == true

private fun JsonObject.folderName(): String =
    string(NAME, NAME).also { if (it.isEmpty()) invalid("`$NAME` が空") }

private fun JsonObject.token(): String =
    string(TOKEN, TOKEN).also { if (!COMMAND_TOKEN_FORMAT.matches(it)) invalid("`$TOKEN` が小文字の 16 進で 32 文字でない") }
