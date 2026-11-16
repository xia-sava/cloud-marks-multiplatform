package to.sava.cloudmarksmultiplatform.storage

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** マニフェストを、gzip で圧縮した UTF-8 のバイト列にする。 */
fun encodeManifestGzip(manifest: Manifest): ByteArray = gzip(encodeManifest(manifest))

/** 項目ファイルを、gzip で圧縮した UTF-8 のバイト列にする。 */
fun encodeItemsFileGzip(file: ItemsFile): ByteArray = gzip(encodeItemsFile(file))

/** gzip で圧縮したマニフェストを読む。展開できない、または UTF-8 として読めなければ壊れているとみなす。 */
fun decodeManifestGzip(
    bytes: ByteArray,
    knownVersion: Int = SUPPORTED_FORMAT_VERSION,
): ManifestDecodeResult =
    try {
        decodeManifest(gunzipToText(bytes), knownVersion)
    } catch (e: StorageProblemException) {
        ManifestDecodeResult.Corrupt(e.problem)
    }

/** gzip で圧縮した項目ファイルを読む。引数の意味は [decodeItemsFile] と同じ。 */
fun decodeItemsFileGzip(
    bytes: ByteArray,
    keyFolderId: String,
    folder: FolderEntry,
    manifestVersion: Int,
    knownVersion: Int = SUPPORTED_FORMAT_VERSION,
): ItemsFileDecodeResult =
    try {
        decodeItemsFile(gunzipToText(bytes), keyFolderId, folder, manifestVersion, knownVersion)
    } catch (e: StorageProblemException) {
        itemsFileFailure(e.problem, manifestVersion, knownVersion)
    }

private fun gzip(text: String): ByteArray =
    ByteArrayOutputStream().also { output ->
        GZIPOutputStream(output).use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }.toByteArray()

/** gzip を展開して UTF-8 の文字列にする。失敗は [StorageProblemException] にして投げる。 */
private fun gunzipToText(bytes: ByteArray): String {
    val decompressed = try {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
    } catch (e: IOException) {
        throwStorageProblem(null, "gzip として展開できない: ${e.message}")
    }
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(decompressed))
            .toString()
    } catch (e: CharacterCodingException) {
        throwStorageProblem(null, "UTF-8 として読めない")
    }
}
