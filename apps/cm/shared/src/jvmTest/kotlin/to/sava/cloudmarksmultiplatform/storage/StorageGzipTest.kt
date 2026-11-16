package to.sava.cloudmarksmultiplatform.storage

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class StorageGzipTest {
    private val manifest = Manifest(
        header = ManifestHeader(1, 1, COMMIT_ID, Instant.parse("2026-11-16T09:15:00.123Z"), "android"),
        folders = listOf(
            FolderEntry(ROOT_ID, null, "", FILE_ID, 1),
        ),
    )
    private val folder = FolderEntry(ROOT_ID, null, "", FILE_ID, 1)
    private val itemsFile = ItemsFile(
        ItemsFileHeader(ROOT_ID),
        listOf(Item(ITEM_1, "https://example.com/", "日本語のタイトル", Instant.parse("2026-11-16T09:15:00.123Z"))),
    )

    private fun gzipOf(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(bytes) } }.toByteArray()

    // マニフェストを圧縮してから展開すると、元の型に戻る
    @Test
    fun manifestRoundTrips() {
        val result = decodeManifestGzip(encodeManifestGzip(manifest))

        assertEquals(ManifestDecodeResult.Readable(manifest), result)
    }

    // 項目ファイルを圧縮してから展開すると、元の型に戻る
    @Test
    fun itemsFileRoundTrips() {
        val result = decodeItemsFileGzip(encodeItemsFileGzip(itemsFile), ROOT_ID, folder, manifestVersion = 1)

        assertEquals(ItemsFileDecodeResult.Readable(itemsFile), result)
    }

    // 圧縮した結果は gzip のマジックナンバーで始まる
    @Test
    fun outputStartsWithGzipMagic() {
        val bytes = encodeManifestGzip(manifest)

        assertEquals(listOf(0x1f.toByte(), 0x8b.toByte()), bytes.take(2))
    }

    // gzip として展開できないバイト列（空・でたらめ・途中で切れたもの）は、壊れている
    @Test
    fun undecompressableBytesAreCorrupt() {
        val truncated = encodeManifestGzip(manifest).let { it.copyOf(it.size / 2) }
        val inputs = mapOf(
            "空" to ByteArray(0),
            "でたらめ" to "not gzip".toByteArray(),
            "途中で切れた" to truncated,
        )

        inputs.forEach { (name, bytes) ->
            assertIs<ManifestDecodeResult.Corrupt>(decodeManifestGzip(bytes), name)
            assertIs<ItemsFileDecodeResult.Corrupt>(decodeItemsFileGzip(bytes, ROOT_ID, folder, 1), name)
        }
    }

    // 展開できても UTF-8 として読めなければ壊れている
    @Test
    fun invalidUtf8IsCorrupt() {
        val bytes = gzipOf(byteArrayOf(0xff.toByte(), 0xfe.toByte(), '\n'.code.toByte()))

        assertIs<ManifestDecodeResult.Corrupt>(decodeManifestGzip(bytes))
    }

    // マニフェストの version が新しいとき、展開できない項目ファイルは「アプリの更新が要る」として返る
    @Test
    fun undecompressableItemsFileWithNewerManifestIsUpdateRequired() {
        val result = decodeItemsFileGzip("x".toByteArray(), ROOT_ID, folder, manifestVersion = 2)

        assertIs<ItemsFileDecodeResult.UpdateRequired>(result)
    }

    // 展開できても中身が形式に合わなければ、検証の結果（壊れている）になる
    @Test
    fun decompressedButInvalidContentIsCorrupt() {
        val bytes = gzipOf("{}\n".toByteArray())

        assertIs<ManifestDecodeResult.Corrupt>(decodeManifestGzip(bytes))
    }
}
