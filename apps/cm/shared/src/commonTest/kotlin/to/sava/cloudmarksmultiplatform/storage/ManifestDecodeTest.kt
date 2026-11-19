package to.sava.cloudmarksmultiplatform.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

class ManifestDecodeTest {
    private val header = manifestHeaderLine()
    private val root = rootLine()
    private val folderA = folderLine(FOLDER_A, ROOT_ID, "a")

    private fun readable(text: String): Manifest =
        assertIs<ManifestDecodeResult.Readable>(decodeManifest(text)).manifest

    // JSON の整数の字句でない表記（先頭の +、先頭の 0、全角の数字、小数、指数）。[digit] はその表記が表す 0 か 1
    private fun nonJsonIntegerLiterals(digit: Int): List<String> =
        listOf("+$digit", "0$digit", (0xFF10 + digit).toChar().toString(), "$digit.0", "${digit}e0")

    // ルートフォルダだけのマニフェストが読めて、ヘッダーとルートフォルダの行が欄どおりに取り出せる
    @Test
    fun readsMinimalManifest() {
        val manifest = readable(jsonl(header, root))

        assertEquals(
            ManifestHeader(1, 1, COMMIT_ID, Instant.parse("2026-11-16T12:30:15.123Z"), "test"),
            manifest.header,
        )
        assertEquals(listOf(FolderEntry(ROOT_ID, null, "", null, 0)), manifest.folders)
    }

    // 行きがけ順なら、親が直前の行でも、直前の行の祖先でも読める
    @Test
    fun acceptsPreorderIncludingParentThatIsAncestorOfPreviousLine() {
        val folderD = "00000000-0000-4000-8000-00000000000d"
        val text = jsonl(
            header,
            root,
            folderA,
            folderLine(FOLDER_B, FOLDER_A, "b"),
            folderLine(FOLDER_C, FOLDER_A, "c"),
            folderLine(folderD, ROOT_ID, "d"),
        )

        assertEquals(listOf(ROOT_ID, FOLDER_A, FOLDER_B, FOLDER_C, folderD), readable(text).folders.map { it.id })
    }

    // 知らない欄は、ヘッダーにも行にあっても無視して読める
    @Test
    fun ignoresUnknownFields() {
        val text = jsonl(
            header.replace("\"updatedBy\":\"test\"", "\"updatedBy\":\"test\",\"extra\":{\"a\":1}"),
            root.replace("\"count\":0", "\"count\":0,\"future\":[1,2]"),
        )

        assertEquals(FolderEntry(ROOT_ID, null, "", null, 0), readable(text).folders.single())
    }

    // 日時は小数部が 0・1・6・9・12 桁でも受け入れる（10 桁目以降は読み捨てる）
    @Test
    fun acceptsAnyNumberOfFractionDigits() {
        val base = Instant.parse("2026-11-16T09:15:00Z")
        val expectations = mapOf(
            "2026-11-16T09:15:00Z" to base,
            "2026-11-16T09:15:00.000Z" to base,
            "2026-11-16T09:15:00.123456Z" to base + 123456.microseconds,
            "2026-11-16T09:15:00.5Z" to base + 500000.microseconds,
            "2026-11-16T09:15:00.123456789Z" to base + 123456789.nanoseconds,
            "2026-11-16T09:15:00.123456789012Z" to base + 123456789.nanoseconds,
            "2026-11-16T18:15:00.1234567899999999+09:00" to base + 123456789.nanoseconds,
        )

        expectations.forEach { (text, expected) ->
            val manifest = readable(jsonl(manifestHeaderLine(updatedAt = text), root))
            assertEquals(expected, manifest.header.updatedAt, text)
        }
    }

    // 整数の欄は JSON の整数の字句だけを受け入れる（version と count で確かめる）
    @Test
    fun rejectsIntegerFieldsThatAreNotJsonIntegerLiterals() {
        nonJsonIntegerLiterals(digit = 1).forEach { literal ->
            val text = jsonl(header.replace("\"version\":1", "\"version\":$literal"), root)
            assertManifestCorrupt(text, 1, "version=$literal", mention = "`version`")
        }
        nonJsonIntegerLiterals(digit = 0).forEach { literal ->
            val text = jsonl(header, root.replace("\"count\":0", "\"count\":$literal"))
            assertManifestCorrupt(text, 2, "count=$literal", mention = "`count`")
        }
    }

    // 満たさない最小の入力が、それぞれ「壊れている」になり、行番号が合って、理由が原因の欄を挙げる
    @Test
    fun rejectsEachViolatedRuleAsCorrupt() {
        val d = "00000000-0000-4000-8000-00000000000d"
        val cases = listOf(
            CorruptCase("id の重複", jsonl(header, root, folderA, folderLine(FOLDER_A, ROOT_ID, "b")), 4, "`id`"),
            CorruptCase("ルートフォルダと同じ id", jsonl(header, root, folderLine(ROOT_ID, ROOT_ID, "x")), 3, "`id`"),
            CorruptCase("2 行目がルートフォルダでない（parent がある）", jsonl(header, folderLine(ROOT_ID, FOLDER_A, "")), 2, "`parent`"),
            CorruptCase("2 行目がルートフォルダでない（name が空でない）", jsonl(header, folderLine(ROOT_ID, null, "r")), 2, "`name`"),
            CorruptCase("2 行目が無い", jsonl(header), 2),
            CorruptCase("3 行目以降の parent が null", jsonl(header, root, folderLine(FOLDER_A, null, "a")), 3, "`parent`"),
            CorruptCase(
                "後ろの行を指す parent",
                jsonl(header, root, folderLine(FOLDER_A, FOLDER_B, "a"), folderLine(FOLDER_B, ROOT_ID, "b")),
                3,
                "`parent`",
            ),
            CorruptCase("自分自身を指す parent", jsonl(header, root, folderLine(FOLDER_A, FOLDER_A, "a")), 3, "`parent`"),
            CorruptCase("マニフェストに無い parent", jsonl(header, root, folderLine(FOLDER_A, d, "a")), 3, "`parent`"),
            CorruptCase(
                "行きがけ順でない並び（A, B がルートフォルダの子、C が A の子）",
                jsonl(
                    header,
                    root,
                    folderA,
                    folderLine(FOLDER_B, ROOT_ID, "b"),
                    folderLine(FOLDER_C, FOLDER_A, "c"),
                ),
                5,
                "`parent`",
            ),
            CorruptCase("items があるのに count が 0", jsonl(header, root, folderLine(FOLDER_A, ROOT_ID, "a", FILE_ID, 0)), 3, "`count`"),
            CorruptCase("items が null なのに count が 1", jsonl(header, root, folderLine(FOLDER_A, ROOT_ID, "a", null, 1)), 3, "`count`"),
            CorruptCase("負の count", jsonl(header, root, folderLine(FOLDER_A, ROOT_ID, "a", FILE_ID, -1)), 3, "`count`"),
            CorruptCase("空の name", jsonl(header, root, folderLine(FOLDER_A, ROOT_ID, "")), 3, "`name`"),
            CorruptCase("UUID の形でない id", jsonl(header, root, folderLine("not-a-uuid", ROOT_ID, "a")), 3, "`id`"),
            CorruptCase("桁の足りない UUID", jsonl(header, root, folderLine(FOLDER_A.dropLast(1), ROOT_ID, "a")), 3, "`id`"),
            CorruptCase("36 文字を超える UUID", jsonl(header, root, folderLine(FOLDER_A + "0", ROOT_ID, "a")), 3, "`id`"),
            CorruptCase("大文字の UUID の id", jsonl(header, root, folderLine(FOLDER_A.uppercase(), ROOT_ID, "a")), 3, "`id`"),
            CorruptCase(
                "大文字の UUID の parent",
                jsonl(header, root, folderLine(FOLDER_A, ROOT_ID.replace("0", "A"), "a")),
                3,
                "`parent`",
            ),
            CorruptCase("UUID の形でない items", jsonl(header, root, folderLine(FOLDER_A, ROOT_ID, "a", "x", 1)), 3, "`items`"),
            CorruptCase("UUID の形でない commit", jsonl(header.replace(COMMIT_ID, "x"), root), 1, "`commit`"),
            CorruptCase("空の行（途中）", header + "\n" + root + "\n\n" + folderA + "\n", 3, "空の行"),
            CorruptCase("空の行（末尾の LF の後）", jsonl(header, root) + "\n", 3, "空の行"),
            CorruptCase("最後の LF が無い", jsonl(header, root, folderA).removeSuffix("\n"), 3, "LF"),
            CorruptCase("BOM", BOM + jsonl(header, root), 1, "BOM"),
            CorruptCase("空の文字列", "", 1, "空の行"),
            CorruptCase("format 違い", jsonl(header.replace("cloud-marks-manifest", "cloud-marks-items"), root), 1, "`format`"),
            CorruptCase("JSON として読めない行", jsonl(header, root, "{broken"), 3, "JSON"),
            CorruptCase("オブジェクトでない行", jsonl(header, root, "[]"), 3, "オブジェクト"),
            CorruptCase("欄が無い（count）", jsonl(header, root.replace(",\"count\":0", "")), 2, "`count`"),
            CorruptCase("ルートフォルダの parent の欄が無い", jsonl(header, root.replace("\"parent\":null,", "")), 2, "`parent`"),
            CorruptCase("ルートフォルダの items の欄が無い", jsonl(header, root.replace("\"items\":null,", "")), 2, "`items`"),
            CorruptCase("count が文字列", jsonl(header, root.replace("\"count\":0", "\"count\":\"0\"")), 2, "`count`"),
            CorruptCase("count が小数", jsonl(header, root.replace("\"count\":0", "\"count\":0.5")), 2, "`count`"),
            CorruptCase("ルートフォルダの name が文字列でない", jsonl(header, root.replace("\"name\":\"\"", "\"name\":1")), 2, "`name`"),
            CorruptCase("3 行目の name が文字列でない", jsonl(header, root, folderA.replace("\"name\":\"a\"", "\"name\":1")), 3, "`name`"),
            CorruptCase("version が 0", jsonl(manifestHeaderLine(version = 0, readableBy = 0), root), 1, "`version`"),
            CorruptCase("version が文字列", jsonl(header.replace("\"version\":1", "\"version\":\"1\""), root), 1, "`version`"),
            CorruptCase("readableBy が 0", jsonl(manifestHeaderLine(version = 1, readableBy = 0), root), 1, "`readableBy`"),
            CorruptCase("readableBy が負", jsonl(manifestHeaderLine(version = 1, readableBy = -1), root), 1, "`readableBy`"),
            CorruptCase("readableBy が version より大きい", jsonl(manifestHeaderLine(version = 1, readableBy = 2), root), 1, "`readableBy`"),
            CorruptCase("日時でない updatedAt", jsonl(manifestHeaderLine(updatedAt = "yesterday"), root), 1, "`updatedAt`"),
            CorruptCase("updatedBy が無い", jsonl(header.replace(",\"updatedBy\":\"test\"", ""), root), 1, "`updatedBy`"),
            CorruptCase("updatedBy が文字列でない", jsonl(header.replace("\"updatedBy\":\"test\"", "\"updatedBy\":1"), root), 1, "`updatedBy`"),
        )

        cases.forEach { assertManifestCorrupt(it.text, it.line, it.name, it.mention) }
    }

    // version が新しく readableBy が知っているバージョン以下なら、検証を通れば読むだけになる
    @Test
    fun newerVersionWithReadableByKnownIsReadOnly() {
        val result = decodeManifest(jsonl(manifestHeaderLine(version = 2, readableBy = 1), root))

        assertEquals(2, assertIs<ManifestDecodeResult.ReadOnly>(result).manifest.header.version)
    }

    // readableBy が知っているバージョンより新しければ、残りが壊れていても読めない（更新が要る）
    @Test
    fun newerReadableByIsUpdateRequiredWithoutCheckingRest() {
        val result = decodeManifest(jsonl(manifestHeaderLine(version = 2, readableBy = 2), "{\"x\":1}") + "garbage")

        val required = assertIs<ManifestDecodeResult.UpdateRequired>(result)
        assertEquals(2, required.version)
        assertEquals(2, required.readableBy)
        assertNull(required.cause)
    }

    // version が新しいときに、未知の形が混じって検証に失敗しても、「壊れている」でなく「アプリの更新が要る」として返る
    @Test
    fun newerVersionWithFailingValidationIsUpdateRequiredNotCorrupt() {
        val newer = manifestHeaderLine(version = 2, readableBy = 1)
        val texts = listOf(
            jsonl(newer, "{\"unknownShape\":true}"),
            jsonl(newer),
            jsonl(newer, root, folderLine(FOLDER_A, FOLDER_B, "a")),
            jsonl(newer, root).removeSuffix("\n"),
            jsonl(newer.replace(COMMIT_ID, "x"), root),
        )

        texts.forEach { text ->
            val required = assertIs<ManifestDecodeResult.UpdateRequired>(decodeManifest(text), text)
            assertNotNull(required.cause, text)
        }
    }

    // ヘッダーの format・version・readableBy の検証は形式のバージョンによらず先に行い、失敗すれば壊れている
    @Test
    fun headerRulesAreCheckedBeforeVersionDecision() {
        val wrongFormat = manifestHeaderLine(version = 2, readableBy = 1).replace("cloud-marks-manifest", "other")
        val inverted = manifestHeaderLine(version = 3, readableBy = 5)

        assertManifestCorrupt(jsonl(wrongFormat, root), 1, mention = "`format`")
        assertManifestCorrupt(jsonl(inverted, root), 1, mention = "`readableBy`")
    }

    // 知っているバージョンを引数で変えると、そのバージョン以下は読み書きできる
    @Test
    fun knownVersionParameterControlsDecision() {
        val text = jsonl(manifestHeaderLine(version = 2, readableBy = 2), root)

        assertIs<ManifestDecodeResult.Readable>(decodeManifest(text, knownVersion = 2))
        assertIs<ManifestDecodeResult.UpdateRequired>(decodeManifest(text, knownVersion = 1))
    }
}
