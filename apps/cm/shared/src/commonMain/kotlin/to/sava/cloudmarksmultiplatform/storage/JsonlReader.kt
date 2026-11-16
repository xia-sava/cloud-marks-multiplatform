package to.sava.cloudmarksmultiplatform.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant

private val BYTE_ORDER_MARK: Char = 0xFEFF.toChar()

/** 小文字の 16 進で 8-4-4-4-12。版の桁は問わない。 */
private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/** JSON の整数の字句。先頭の `+`、先頭の 0、全角の数字、小数、指数は含まない。 */
private val JSON_INTEGER_PATTERN = Regex("-?(0|[1-9][0-9]*)")

/** 小数部の 10 桁目以降。`Instant` が読めるのは 9 桁までなので、読む前に落とす。 */
private val EXCESS_FRACTION_PATTERN = Regex("(\\.[0-9]{9})[0-9]+")

internal fun isUuidShape(value: String): Boolean = UUID_PATTERN.matches(value)

/** 検証の失敗を、デコードの入口まで運ぶための内部の例外。入口で必ず捕まえて結果の値にする。 */
internal class StorageProblemException(val problem: StorageProblem) : Exception(problem.toString())

/** [line] 行目（ファイル全体に係る問題では null）の問題として検証の失敗を投げる。 */
internal fun throwStorageProblem(line: Int?, message: String): Nothing =
    throw StorageProblemException(StorageProblem(line, message))

/** JSONL の 1 行（JSON のオブジェクト）と、その欄を表の型で取り出す操作。 */
internal class JsonlLine(val number: Int, private val obj: JsonObject) {
    fun fail(message: String): Nothing = throwStorageProblem(number, message)

    private fun element(name: String) = obj[name] ?: fail("欄 `$name` が無い")

    fun string(name: String): String =
        (element(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: fail("欄 `$name` が文字列でない")

    fun nullableString(name: String): String? =
        if (element(name) is JsonNull) null else string(name)

    fun uuid(name: String): String =
        string(name).also { if (!isUuidShape(it)) fail("欄 `$name` が UUID の形でない: $it") }

    fun nullableUuid(name: String): String? =
        if (element(name) is JsonNull) null else uuid(name)

    fun integer(name: String): Int =
        (element(name) as? JsonPrimitive)
            ?.takeIf { !it.isString && JSON_INTEGER_PATTERN.matches(it.content) }
            ?.content?.toIntOrNull()
            ?: fail("欄 `$name` が整数でない")

    fun instant(name: String): Instant =
        string(name).let { text ->
            try {
                Instant.parse(text.replace(EXCESS_FRACTION_PATTERN, "\$1"))
            } catch (e: IllegalArgumentException) {
                fail("欄 `$name` が RFC 3339 の日時でない: $text")
            }
        }

    /** 欄ごと無くてよい日時。欄があるのに日時でなければ失敗する。 */
    fun optionalInstant(name: String): Instant? =
        if (name in obj) instant(name) else null

    fun requireFormat(expected: String) {
        val actual = string("format")
        if (actual != expected) fail("`format` が `$expected` でない: $actual")
    }
}

/** 1 行を JSON のオブジェクトとして読む。 */
private fun parseObjectLine(number: Int, line: String): JsonObject {
    if (line.isEmpty()) throwStorageProblem(number, "空の行がある")
    val element = try {
        Json.parseToJsonElement(line)
    } catch (e: IllegalArgumentException) {
        throwStorageProblem(number, "JSON として読めない")
    }
    return element as? JsonObject ?: throwStorageProblem(number, "JSON のオブジェクトでない")
}

/** 見出し（1 行目）を読む。BOM は壊れているとみなす。 */
internal fun readHeaderLine(rawLines: List<String>): JsonlLine {
    val first = rawLines.first()
    if (first.startsWith(BYTE_ORDER_MARK)) throwStorageProblem(1, "BOM がある")
    return JsonlLine(1, parseObjectLine(1, first))
}

/**
 * 2 行目から後ろを読む。最後の行も LF で終わっていなければならない。
 *
 * @param rawLines 文字列全体を LF で分けたもの。最後の LF の後ろの空の要素を含む
 */
internal fun readBodyLines(rawLines: List<String>): List<JsonlLine> {
    if (rawLines.last().isNotEmpty()) throwStorageProblem(rawLines.size, "最後の行が LF で終わっていない")
    return rawLines.subList(1, rawLines.size - 1).mapIndexed { index, line ->
        val number = index + 2
        JsonlLine(number, parseObjectLine(number, line))
    }
}
