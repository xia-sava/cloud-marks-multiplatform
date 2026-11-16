package to.sava.cloudmarksmultiplatform.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class ItemsFileDecodeTest {
    private val header = itemsHeaderLine()
    private val item1 = itemLine(ITEM_1)
    private val item2 = itemLine(ITEM_2)
    private val folder = FolderEntry(FOLDER_A, ROOT_ID, "a", FILE_ID, count = 2)

    private fun decode(text: String, manifestVersion: Int = 1): ItemsFileDecodeResult =
        decodeItemsFile(text, FOLDER_A, folder, manifestVersion)

    // 項目を欄どおりに取り出せる。added は省略でき、あれば日時として読む
    @Test
    fun readsItemsWithAndWithoutAdded() {
        val withAdded = """{"id":"$ITEM_2","url":"about:blank","title":"","added":"2026-11-16T09:15:00Z"}"""

        val result = assertIs<ItemsFileDecodeResult.Readable>(decode(jsonl(header, item1, withAdded)))

        assertEquals(
            listOf(
                Item(ITEM_1, "https://example.com/", "t", null),
                Item(ITEM_2, "about:blank", "", Instant.parse("2026-11-16T09:15:00Z")),
            ),
            result.file.items,
        )
        assertEquals(FOLDER_A, result.file.header.folder)
    }

    // 知らない欄は無視して読める
    @Test
    fun ignoresUnknownFields() {
        val text = jsonl(
            header.replace("}", ",\"x\":1}"),
            item1.replace("}", ",\"y\":[]}"),
            item2,
        )

        assertIs<ItemsFileDecodeResult.Readable>(decode(text))
    }

    // 満たさない最小の入力が、それぞれ壊れているになり、行番号が合って、理由が原因の欄を挙げる
    @Test
    fun rejectsEachViolatedRuleAsCorrupt() {
        val one = FolderEntry(FOLDER_A, ROOT_ID, "a", FILE_ID, count = 1)
        val cases = listOf(
            CorruptCase("キーと違う folder", jsonl(itemsHeaderLine(FOLDER_B), item1, item2), 1, "`folder`"),
            CorruptCase("format 違い", jsonl(header.replace("cloud-marks-items", "cloud-marks-manifest"), item1, item2), 1, "`format`"),
            CorruptCase("folder が無い", jsonl("""{"format":"cloud-marks-items"}""", item1, item2), 1, "`folder`"),
            CorruptCase("id の重複", jsonl(header, item1, item1), 3, "`id`"),
            CorruptCase("UUID の形でない id", jsonl(header, item1, itemLine("nope")), 3, "`id`"),
            CorruptCase("36 文字を超える UUID", jsonl(header, item1, itemLine(ITEM_2 + "0")), 3, "`id`"),
            CorruptCase(
                "大文字の UUID の id",
                jsonl(header, item1, itemLine("2000000a-0000-4000-8000-00000000000a".uppercase())),
                3,
                "`id`",
            ),
            CorruptCase("空の url", jsonl(header, item1, itemLine(ITEM_2, url = "")), 3, "`url`"),
            CorruptCase("url が文字列でない", jsonl(header, item1, item2.replace("\"url\":\"https://example.com/\"", "\"url\":1")), 3, "`url`"),
            CorruptCase("title が無い", jsonl(header, item1, item2.replace(",\"title\":\"t\"", "")), 3, "`title`"),
            CorruptCase("title が文字列でない", jsonl(header, item1, item2.replace("\"title\":\"t\"", "\"title\":1")), 3, "`title`"),
            CorruptCase("added が日時でない", jsonl(header, item1, item2.replace("}", ",\"added\":\"x\"}")), 3, "`added`"),
            CorruptCase("added が null", jsonl(header, item1, item2.replace("}", ",\"added\":null}")), 3, "`added`"),
            CorruptCase("空の行", header + "\n" + item1 + "\n\n" + item2 + "\n", 3, "空の行"),
            CorruptCase("最後の LF が無い", jsonl(header, item1, item2).removeSuffix("\n"), 3, "LF"),
            CorruptCase("BOM", BOM + jsonl(header, item1, item2), 1, "BOM"),
            CorruptCase("JSON として読めない行", jsonl(header, item1, "{"), 3, "JSON"),
            CorruptCase("空の文字列", "", 1, "空の行"),
            CorruptCase("行数が count より少ない", jsonl(header, item1), null, "`count`"),
            CorruptCase(
                "行数が count より多い",
                jsonl(header, item1, item2, itemLine("20000000-0000-4000-8000-000000000003")),
                null,
                "`count`",
            ),
        )

        cases.forEach { assertItemsCorrupt(it.text, it.line, folder = folder, message = it.name, mention = it.mention) }

        // 目録のフォルダの id、count との不一致も壊れている
        assertItemsCorrupt(jsonl(header, item1), null, folder = one.copy(count = 2), message = "count 不一致", mention = "`count`")
        assertItemsCorrupt(jsonl(header, item1), 1, folder = one.copy(id = FOLDER_B), message = "目録の id と不一致", mention = "`id`")
        assertItemsCorrupt(jsonl(header, item1), 1, keyFolderId = FOLDER_B, folder = one, message = "キーの id と不一致", mention = "キー")
    }

    // 目録の version が新しいとき、検証に失敗しても「壊れている」でなく「アプリの更新が要る」として返り、通れば読める
    @Test
    fun newerManifestVersionTurnsFailureIntoUpdateRequired() {
        val bad = jsonl(header, "{\"unknown\":1}")

        assertIs<ItemsFileDecodeResult.UpdateRequired>(decode(bad, manifestVersion = 2))
        assertIs<ItemsFileDecodeResult.Corrupt>(decode(bad, manifestVersion = 1))
        assertIs<ItemsFileDecodeResult.Readable>(decode(jsonl(header, item1, item2), manifestVersion = 2))
    }
}
