package to.sava.cloudmarksmultiplatform.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class StorageEncoderTest {
    private val manifest = Manifest(
        header = ManifestHeader(1, 1, COMMIT_ID, Instant.parse("2026-11-16T09:15:00Z"), "desktop"),
        folders = listOf(
            FolderEntry(ROOT_ID, null, "", null, 0),
            FolderEntry(FOLDER_A, ROOT_ID, "A/B \"x\"", FILE_ID, 2),
        ),
    )

    // 欄の順は表の順、コンパクトな JSON、null は明示、非 ASCII はそのまま、各行 LF、日時は 3 桁
    @Test
    fun encodesManifestInSpecifiedShape() {
        val expected = jsonl(
            """{"format":"cloud-marks-manifest","version":1,"readableBy":1,"commit":"$COMMIT_ID",""" +
                """"updatedAt":"2026-11-16T09:15:00.000Z","updatedBy":"desktop"}""",
            """{"id":"$ROOT_ID","parent":null,"name":"","items":null,"count":0}""",
            """{"id":"$FOLDER_A","parent":"$ROOT_ID","name":"A/B \"x\"","items":"$FILE_ID","count":2}""",
        )

        assertEquals(expected, encodeManifest(manifest))
    }

    // added は無ければ欄ごと書かず、あれば 3 桁の日時で書く
    @Test
    fun encodesItemsFileOmittingAbsentAdded() {
        val file = ItemsFile(
            ItemsFileHeader(FOLDER_A),
            listOf(
                Item(ITEM_1, "https://example.com/", "a\\b"),
                Item(ITEM_2, "about:blank", "", Instant.parse("2026-11-16T12:30:15.123456789Z")),
            ),
        )
        val expected = jsonl(
            itemsHeaderLine(FOLDER_A),
            """{"id":"$ITEM_1","url":"https://example.com/","title":"a\\b"}""",
            """{"id":"$ITEM_2","url":"about:blank","title":"","added":"2026-11-16T12:30:15.123Z"}""",
        )

        assertEquals(expected, encodeItemsFile(file))
    }

    // 日時は小数部をちょうど 3 桁にそろえ、3 桁を超える分は切り捨てる
    @Test
    fun formatsTimestampWithExactlyThreeFractionDigits() {
        val expectations = mapOf(
            "2026-11-16T09:15:00Z" to "2026-11-16T09:15:00.000Z",
            "2026-11-16T09:15:00.1Z" to "2026-11-16T09:15:00.100Z",
            "2026-11-16T09:15:00.120Z" to "2026-11-16T09:15:00.120Z",
            "2026-11-16T09:15:00.123999Z" to "2026-11-16T09:15:00.123Z",
            "2026-11-16T18:15:00+09:00" to "2026-11-16T09:15:00.000Z",
        )

        expectations.forEach { (input, expected) ->
            assertEquals(expected, formatTimestamp(Instant.parse(input)), input)
        }
    }

    // ミリ秒に切り詰めた日時は、エンコード → デコードで元の値と等しい（updatedAt・added の両方）
    @Test
    fun truncatedInstantSurvivesEncodeDecode() {
        val truncated = Instant.parse("2026-11-16T09:15:00.123456789Z").truncatedToMilliseconds()
        val manifestWithTimes = manifest.copy(header = manifest.header.copy(updatedAt = truncated))
        val file = ItemsFile(ItemsFileHeader(FOLDER_A), listOf(Item(ITEM_1, "https://example.com/", "t", truncated)))
        val folder = FolderEntry(FOLDER_A, ROOT_ID, "a", FILE_ID, 1)

        val manifestResult = decodeManifest(encodeManifest(manifestWithTimes))
        val itemsResult = decodeItemsFile(encodeItemsFile(file), FOLDER_A, folder, manifestVersion = 1)

        assertEquals(Instant.parse("2026-11-16T09:15:00.123Z"), truncated)
        assertEquals(ManifestDecodeResult.Readable(manifestWithTimes), manifestResult)
        assertEquals(ItemsFileDecodeResult.Readable(file), itemsResult)
    }

    // エンコードしたものをデコードすると元の型に戻る
    @Test
    fun encodedTextDecodesBackToSameValues() {
        val manifestResult = decodeManifest(encodeManifest(manifest))
        val file = ItemsFile(ItemsFileHeader(FOLDER_A), listOf(Item(ITEM_1, "https://example.com/", "t")))
        val itemsResult = decodeItemsFile(
            encodeItemsFile(file),
            FOLDER_A,
            FolderEntry(FOLDER_A, ROOT_ID, "a", FILE_ID, 1),
            manifestVersion = 1,
        )

        assertEquals(ManifestDecodeResult.Readable(manifest), manifestResult)
        assertEquals(ItemsFileDecodeResult.Readable(file), itemsResult)
    }
}
