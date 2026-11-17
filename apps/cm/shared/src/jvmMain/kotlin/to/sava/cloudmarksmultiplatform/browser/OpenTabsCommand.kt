package to.sava.cloudmarksmultiplatform.browser

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import to.sava.cloudmarksmultiplatform.settings.MINIMUM_LOAD_AHEAD
import to.sava.cloudmarksmultiplatform.settings.MINIMUM_TAB_COUNT
import to.sava.cloudmarksmultiplatform.settings.TabLoading
import java.nio.file.Path
import java.security.SecureRandom
import java.util.HexFormat

private const val TYPE = "type"
private const val LOAD_AT_ONCE = "loadAtOnce"
private const val LOAD_AHEAD = "loadAhead"

/** コマンドの `type` の、タブを開くもの。 */
private const val TYPE_OPEN_TABS = "openTabs"

/** コマンドのトークンにする乱数のバイト数。小文字の 16 進で 32 文字になる。 */
private const val TOKEN_BYTES = 16

/** コマンドのファイルの名前の末尾。名前はトークンにこれを付けたもの。 */
internal const val COMMAND_FILE_SUFFIX = ".json"

/**
 * コマンドのトークンの形。小文字の 16 進で 32 文字（[newCommandToken] が作る形）。
 * ホストは、ファイルのパスの組み立てに使うので、全体がこれに合うものだけを受け付ける。
 */
internal val COMMAND_TOKEN_FORMAT = Regex("[0-9a-f]{32}")

/**
 * アプリから拡張機能へのコマンドのうち、タブを開くもの（`docs/native-messaging.md` の「コマンドの形」）。
 * アプリが書き、Native Messaging のホストが読んで拡張機能へ渡す。[tabs] は 1 つ以上で、開く順に並べる。
 * URL とタイトルを含むので、ログに渡さない。
 *
 * @property tabLoading タブの読み込み方。`loadAtOnce` と `loadAhead` になる
 */
internal data class OpenTabsCommand(val tabs: List<BrowserTab>, val tabLoading: TabLoading)

/** コマンドを読んだ結果。 */
internal sealed interface OpenTabsCommandParseResult {
    data class Valid(val command: OpenTabsCommand) : OpenTabsCommandParseResult

    /** 取り決めに合わない。[reason] は後から追うための短い文で、コマンドの中の値を含まない。 */
    data class Invalid(val reason: String) : OpenTabsCommandParseResult
}

/** [random] の [TOKEN_BYTES] バイトを小文字の 16 進にした、コマンドのトークン。[COMMAND_TOKEN_FORMAT] に合う。 */
internal fun newCommandToken(random: SecureRandom): String =
    ByteArray(TOKEN_BYTES).also(random::nextBytes).let { HexFormat.of().formatHex(it) }

/** [directory] の、トークンが [token] のコマンドのファイル。 */
internal fun commandFileOf(directory: Path, token: String): Path = directory.resolve(token + COMMAND_FILE_SUFFIX)

/** コマンドの JSON。コマンドのファイルの中身と、ホストの応答の `command` になる。 */
internal fun OpenTabsCommand.toJson(): JsonElement =
    buildJsonObject {
        put(TYPE, TYPE_OPEN_TABS)
        put(
            TABS_KEY,
            buildJsonArray {
                tabs.forEach { tab ->
                    add(
                        buildJsonObject {
                            put(URL_KEY, tab.url)
                            put(TITLE_KEY, tab.title)
                        },
                    )
                }
            },
        )
        put(LOAD_AT_ONCE, tabLoading.loadAtOnce)
        put(LOAD_AHEAD, tabLoading.loadAhead)
    }

/** コマンドのファイルの中身（UTF-8 の JSON。[toJson]）。 */
internal fun OpenTabsCommand.encode(): ByteArray = toJson().toString().encodeToByteArray()

/**
 * コマンドのファイルの中身 [body]（UTF-8 の JSON）を、コマンドとして読む。
 *
 * `type` が `"openTabs"` でない、欄が欠けているか型が違う、`tabs` が空、`tabs[].url` が空、
 * `loadAtOnce` が [MINIMUM_TAB_COUNT] 以上、`loadAhead` が [MINIMUM_LOAD_AHEAD] 以上の 32 ビットの整数でないときは、
 * 取り決めに合わないとする。
 * 表に無い欄は無視する。
 */
internal fun parseOpenTabsCommand(body: ByteArray): OpenTabsCommandParseResult =
    try {
        parseObject(body).let { command ->
            if (command.string(TYPE, TYPE) != TYPE_OPEN_TABS) invalid("`$TYPE` が $TYPE_OPEN_TABS でない")
            OpenTabsCommandParseResult.Valid(
                OpenTabsCommand(
                    tabs = command.tabs(),
                    tabLoading = TabLoading(
                        loadAtOnce = command.count(LOAD_AT_ONCE, MINIMUM_TAB_COUNT),
                        loadAhead = command.count(LOAD_AHEAD, MINIMUM_LOAD_AHEAD),
                    ),
                ),
            )
        }
    } catch (e: InvalidMessageException) {
        OpenTabsCommandParseResult.Invalid(e.reason)
    }

/**
 * 欄 [key] の、[minimum] 以上 [Int.MAX_VALUE] 以下の整数。JSON の整数の字句でないか、その範囲に無ければ、
 * 取り決めに合わないとする。
 */
private fun JsonObject.count(key: String, minimum: Int): Int =
    (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()?.takeIf { it >= minimum }
        ?: invalid("`$key` が $minimum 以上 ${Int.MAX_VALUE} 以下の整数でない")
