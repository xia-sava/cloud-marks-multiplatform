package to.sava.cloudmarksmultiplatform.storage

import to.sava.cloudmarksmultiplatform.findContracts
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** `contracts/storage/example/` の見本を読み、読み書きの実装が見本の形を扱えることを確かめる。 */
class ContractExampleTest {
    private val exampleDirectory: File = findContracts("storage").resolve("example")

    private val manifestBytes: ByteArray = exampleDirectory.resolve("manifest.jsonl").readBytes()

    private fun readableManifest(): Manifest =
        assertIs<ManifestDecodeResult.Readable>(decodeManifest(manifestBytes.decodeToString())).manifest

    private fun itemsFileOf(folder: FolderEntry): File =
        exampleDirectory.resolve("folders/${folder.id}/${folder.items}.jsonl")

    // 見本のマニフェストが「読めた」になり、デコードしてエンコードし直すと見本のバイト列と完全に一致する
    @Test
    fun manifestIsReadableAndRoundTripsByteForByte() {
        val manifest = readableManifest()

        assertEquals(7, manifest.folders.size)
        assertContentEquals(manifestBytes, encodeManifest(manifest).encodeToByteArray())
    }

    // 見本の項目ファイルがすべて「読めた」になり、エンコードし直すと見本のバイト列と完全に一致する
    @Test
    fun itemsFilesAreReadableAndRoundTripByteForByte() {
        val manifest = readableManifest()
        val foldersWithItems = manifest.folders.filter { it.items != null }

        foldersWithItems.forEach { folder ->
            val file = itemsFileOf(folder)
            val bytes = file.readBytes()

            val result = decodeItemsFile(bytes.decodeToString(), folder.id, folder, manifest.header.version)

            val readable = assertIs<ItemsFileDecodeResult.Readable>(result, file.path)
            assertEquals(folder.count, readable.file.items.size, file.path)
            assertContentEquals(bytes, encodeItemsFile(readable.file).encodeToByteArray(), file.path)
        }
    }

    // マニフェストが指すファイルと見本のファイルが過不足なく対応している（読み漏らしが無い）
    @Test
    fun everyExampleItemsFileIsReferencedByManifest() {
        val referenced = readableManifest().folders.filter { it.items != null }
            .map { itemsFileOf(it).canonicalFile }
            .toSet()
        val onDisk = exampleDirectory.resolve("folders").walkTopDown()
            .filter { it.isFile }
            .map { it.canonicalFile }
            .toSet()

        assertEquals(onDisk, referenced)
        assertEquals(4, onDisk.size)
    }

    // 見本のバイト列を gzip で圧縮し、展開してデコードした値が、見本をそのままデコードした値と等しい
    @Test
    fun gzippedExampleDecodesToSameValuesAsPlainExample() {
        val plain = readableManifest()
        val gzipped = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(manifestBytes) }
        }.toByteArray()

        val viaGzip = decodeManifestGzip(gzipped)

        assertEquals(ManifestDecodeResult.Readable(plain), viaGzip)
    }
}
