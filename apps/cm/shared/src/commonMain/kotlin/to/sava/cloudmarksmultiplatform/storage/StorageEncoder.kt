package to.sava.cloudmarksmultiplatform.storage

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

private const val TIMESTAMP_FRACTION_DIGITS = 3

/**
 * マニフェストを JSONL の文字列にする。各行を LF で終え、最後の行も LF で終える。
 *
 * 日時（`updatedAt`）はミリ秒未満を切り捨てて書く。書いた値と読み直した値を等しくするには、
 * 呼び出し側が [truncatedToMilliseconds] を通した値を渡す。
 */
fun encodeManifest(manifest: Manifest): String =
    (listOf(manifestHeaderJson(manifest.header)) + manifest.folders.map(::folderEntryJson))
        .toJsonl()

/**
 * 項目ファイルを JSONL の文字列にする。各行を LF で終え、最後の行も LF で終える。
 *
 * 日時（`added`）はミリ秒未満を切り捨てて書く。書いた値と読み直した値を等しくするには、
 * 呼び出し側が [truncatedToMilliseconds] を通した値を渡す。
 */
fun encodeItemsFile(file: ItemsFile): String =
    (listOf(itemsFileHeaderJson(file.header)) + file.items.map(::itemJson))
        .toJsonl()

/** ミリ秒未満を切り捨てた日時。形式が書ける精度（小数部 3 桁）に揃える。 */
fun Instant.truncatedToMilliseconds(): Instant = Instant.fromEpochMilliseconds(toEpochMilliseconds())

/** UTC の `Z` 付きで、小数部をちょうど 3 桁で書く（例：`2026-11-16T09:15:00.000Z`）。ミリ秒未満は切り捨てる。 */
internal fun formatTimestamp(instant: Instant): String =
    instant.truncatedToMilliseconds().toString().removeSuffix("Z").let { text ->
        val seconds = text.substringBefore('.')
        val fraction = text.substringAfter('.', "").padEnd(TIMESTAMP_FRACTION_DIGITS, '0')
        "$seconds.${fraction.take(TIMESTAMP_FRACTION_DIGITS)}Z"
    }

private fun List<JsonObject>.toJsonl(): String = joinToString("") { "$it\n" }

private fun manifestHeaderJson(header: ManifestHeader): JsonObject = buildJsonObject {
    put("format", MANIFEST_FORMAT)
    put("version", header.version)
    put("readableBy", header.readableBy)
    put("commit", header.commit)
    put("updatedAt", formatTimestamp(header.updatedAt))
    put("updatedBy", header.updatedBy)
}

private fun folderEntryJson(entry: FolderEntry): JsonObject = buildJsonObject {
    put("id", entry.id)
    put("parent", entry.parent)
    put("name", entry.name)
    put("items", entry.items)
    put("count", entry.count)
}

private fun itemsFileHeaderJson(header: ItemsFileHeader): JsonObject = buildJsonObject {
    put("format", ITEMS_FORMAT)
    put("folder", header.folder)
}

private fun itemJson(item: Item): JsonObject = buildJsonObject {
    put("id", item.id)
    put("url", item.url)
    put("title", item.title)
    item.added?.let { put("added", formatTimestamp(it)) }
}
