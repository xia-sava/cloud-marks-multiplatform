package to.sava.cloudmarksmultiplatform.storage

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal const val ROOT_ID = "00000000-0000-4000-8000-000000000000"
internal const val FOLDER_A = "00000000-0000-4000-8000-00000000000a"
internal const val FOLDER_B = "00000000-0000-4000-8000-00000000000b"
internal const val FOLDER_C = "00000000-0000-4000-8000-00000000000c"
internal const val FILE_ID = "10000000-0000-4000-8000-000000000001"
internal const val ITEM_1 = "20000000-0000-4000-8000-000000000001"
internal const val ITEM_2 = "20000000-0000-4000-8000-000000000002"
internal const val COMMIT_ID = "30000000-0000-4000-8000-000000000001"

/** バイト順マーク（U+FEFF）。 */
internal val BOM: String = 0xFEFF.toChar().toString()

/** 満たさない規則を 1 つだけ持つ入力。[mention] は、失敗の理由に含まれるべき語（欄の名前など）。 */
internal class CorruptCase(
    val name: String,
    val text: String,
    val line: Int?,
    val mention: String? = null,
)

internal fun manifestHeaderLine(
    version: Int = 1,
    readableBy: Int = 1,
    updatedAt: String = "2026-11-16T12:30:15.123Z",
): String =
    """{"format":"cloud-marks-manifest","version":$version,"readableBy":$readableBy,""" +
        """"commit":"$COMMIT_ID","updatedAt":"$updatedAt","updatedBy":"test"}"""

internal fun folderLine(
    id: String,
    parent: String?,
    name: String,
    items: String? = null,
    count: Int = if (items == null) 0 else 1,
): String {
    val parentJson = parent?.let { "\"$it\"" } ?: "null"
    val itemsJson = items?.let { "\"$it\"" } ?: "null"
    return """{"id":"$id","parent":$parentJson,"name":"$name","items":$itemsJson,"count":$count}"""
}

internal fun rootLine(items: String? = null, count: Int = if (items == null) 0 else 1): String =
    folderLine(ROOT_ID, null, "", items, count)

/** 各行を LF で終えた JSONL の文字列。 */
internal fun jsonl(vararg lines: String): String = lines.joinToString("") { "$it\n" }

internal fun itemsHeaderLine(folder: String = FOLDER_A): String =
    """{"format":"cloud-marks-items","folder":"$folder"}"""

internal fun itemLine(id: String = ITEM_1, url: String = "https://example.com/", title: String = "t"): String =
    """{"id":"$id","url":"$url","title":"$title"}"""

/** 目録が壊れているとして返ってきたこと、その行番号、理由に [mention] を含むことを確かめる。 */
internal fun assertManifestCorrupt(text: String, line: Int?, message: String = text, mention: String? = null) {
    val result = decodeManifest(text)
    val corrupt = assertIs<ManifestDecodeResult.Corrupt>(result, message)
    assertProblem(corrupt.problem, line, mention, message)
}

/** フォルダのファイルが壊れているとして返ってきたこと、その行番号、理由に [mention] を含むことを確かめる。 */
internal fun assertItemsCorrupt(
    text: String,
    line: Int?,
    folder: FolderEntry = FolderEntry(FOLDER_A, ROOT_ID, "a", FILE_ID, 1),
    keyFolderId: String = FOLDER_A,
    message: String = text,
    mention: String? = null,
) {
    val result = decodeItemsFile(text, keyFolderId, folder, manifestVersion = 1)
    val corrupt = assertIs<ItemsFileDecodeResult.Corrupt>(result, message)
    assertProblem(corrupt.problem, line, mention, message)
}

private fun assertProblem(problem: StorageProblem, line: Int?, mention: String?, message: String) {
    assertEquals(line, problem.line, "$message: $problem")
    mention?.let { assertTrue(it in problem.message, "$message: 理由に $it が無い: $problem") }
}
