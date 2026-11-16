package to.sava.cloudmarksmultiplatform.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.sava.cloudmarksmultiplatform.files.replaceFileAtomically
import to.sava.cloudmarksmultiplatform.storage.normalizePrefix
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.security.MessageDigest

/** 目録のファイルの名前。 */
private const val MANIFEST_FILE_NAME = "manifest"

/** フォルダのファイルを置くディレクトリの名前。 */
private const val ITEMS_FILES_DIRECTORY = "items"

/** 目録のファイルの先頭に置く、ETag の長さ（バイト数）の大きさ。 */
private const val ETAG_LENGTH_BYTES = Int.SIZE_BYTES

private const val HASH_ALGORITHM = "SHA-256"

/** バケットとプレフィックスから作る名前の区切り。バケットの名前に改行は現れないので、区切りは一意になる。 */
private const val CONNECTION_NAME_SEPARATOR = "\n"

/** [HASH_ALGORITHM] の 16 進の文字列の形。フォルダのファイルの名前は、この形に限る。 */
private val HASH_NAME_PATTERN = Regex("[0-9a-f]{64}")

/**
 * 接続先のバケットとプレフィックスに対する、手元のキャッシュのディレクトリを返す。
 *
 * 名前は、バケットと（前後の `/` を除いた）プレフィックスから決まる SHA-256 の 16 進で、パスとして安全な固定長になる。
 * 接続先が同じなら、アプリと Native Messaging のホストのように別のプロセスでも同じディレクトリになる。
 *
 * @param root キャッシュ全体の根のディレクトリ
 */
fun connectionCacheDirectory(root: Path, bucket: String, prefix: String): Path =
    root.resolve(sha256Hex(bucket + CONNECTION_NAME_SEPARATOR + normalizePrefix(prefix)))

/**
 * ファイルで持つ手元のキャッシュ。アプリを閉じても残る。接続先ごとに 1 つ作る。
 *
 * [directory] の下に、目録を 1 つのファイル、フォルダのファイルを S3 のキーごとの 1 つのファイルで置く。
 * フォルダのファイルの名前は、キーの SHA-256 の 16 進にする。キーをそのままパスにしない。
 * 目録のファイルは、ETag の長さ、ETag（UTF-8）、本文（gzip のまま）の順に並べる。
 *
 * 書くときは同じディレクトリの一時ファイルに書いてから置き換える。アプリと Native Messaging のホストのように、
 * 別のプロセスが同じディレクトリを使っても、読み手には置き換える前か後の中身が見える。
 * ファイルが無い、形式が崩れている、消している最中のファイルを読んだときは、「無い」として返す。
 * プロセスをまたぐロックは持たない。
 *
 * キャッシュは速くするためのもので、S3 の読み書きの成否を変えない。ファイルの I/O の失敗は呼び出し元へ投げず、
 * [onFailure] へ渡す。読めないときは「無い」を返し、書けない・消せないときは何もしない。
 * ファイルが無いことは失敗として渡さない。
 *
 * @param directory この接続先のキャッシュのディレクトリ。[connectionCacheDirectory] で決める。無ければ、書くときに作る
 * @param onFailure ファイルの I/O の失敗を受け取る。複数のスレッドから呼ばれうるので、例外を投げない実装を渡す。
 * 渡す例外のメッセージは絶対パスを含みうるため、記録するときは `describeIoFailure`（`files` パッケージ）で説明にする。
 * Windows では、読み手がファイルを開いているあいだの置き換えや削除が失敗して通知されることがある。
 * 受け手は記録にとどめ、利用者への誤りとして見せない
 */
class FileSyncCache(
    private val directory: Path,
    private val onFailure: (IOException) -> Unit,
) : SyncCache {
    private val manifestPath: Path = directory.resolve(MANIFEST_FILE_NAME)
    private val itemsDirectory: Path = directory.resolve(ITEMS_FILES_DIRECTORY)

    override suspend fun manifest(): CachedManifest? =
        attempt(failureValue = null) {
            readOrNull(manifestPath)?.let { decodeManifest(it, manifestPath) }
        }

    override suspend fun putManifest(manifest: CachedManifest) {
        attempt(failureValue = Unit) { replaceFileAtomically(manifestPath, encodeManifest(manifest)) }
    }

    override suspend fun itemsFile(key: String): ByteArray? =
        attempt(failureValue = null) { readOrNull(itemsFilePath(key)) }

    override suspend fun putItemsFile(key: String, body: ByteArray) {
        attempt(failureValue = Unit) { replaceFileAtomically(itemsFilePath(key), body) }
    }

    override suspend fun retainItemsFiles(keys: Set<String>) {
        val retained = keys.map { itemsFileName(it) }.toSet()
        attempt(failureValue = Unit) {
            listItemsFiles()
                .filter { it.fileName.toString().let { name -> HASH_NAME_PATTERN.matches(name) && name !in retained } }
                .forEach { deleteReportingFailure(it) }
        }
    }

    override suspend fun clear() {
        attempt(failureValue = Unit) {
            deleteReportingFailure(manifestPath)
            listItemsFiles()
                .filter { HASH_NAME_PATTERN.matches(it.fileName.toString()) }
                .forEach { deleteReportingFailure(it) }
        }
    }

    private fun itemsFilePath(key: String): Path = itemsDirectory.resolve(itemsFileName(key))

    /** フォルダのファイルのディレクトリにあるファイル。ディレクトリが無ければ空。 */
    private fun listItemsFiles(): List<Path> =
        try {
            Files.newDirectoryStream(itemsDirectory).use { stream -> stream.toList() }
        } catch (e: NoSuchFileException) {
            emptyList()
        } catch (e: DirectoryIteratorException) {
            throw checkNotNull(e.cause) { "DirectoryIteratorException は原因の IOException を持つ" }
        }

    /** [path] を消す。消せなかった失敗は [onFailure] へ渡し、ほかのファイルの削除を続けられるようにする。 */
    private fun deleteReportingFailure(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (e: IOException) {
            onFailure(e)
        }
    }

    /**
     * ブロックする I/O を I/O 用のスレッドで走らせる。[IOException] は [onFailure] へ渡し、[failureValue] を返す。
     * キャンセルはそのまま伝える。
     */
    private suspend fun <T> attempt(failureValue: T, block: () -> T): T =
        try {
            withContext(Dispatchers.IO) { block() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: IOException) {
            onFailure(e)
            failureValue
        }
}

/** [path] の中身。ファイルが無ければ null。 */
private fun readOrNull(path: Path): ByteArray? =
    try {
        Files.readAllBytes(path)
    } catch (e: NoSuchFileException) {
        null
    }

private fun itemsFileName(key: String): String = sha256Hex(key)

private fun sha256Hex(text: String): String =
    MessageDigest.getInstance(HASH_ALGORITHM).digest(text.encodeToByteArray()).toHexString()

private fun encodeManifest(manifest: CachedManifest): ByteArray =
    manifest.eTag.encodeToByteArray().let { eTag ->
        ByteBuffer.allocate(ETAG_LENGTH_BYTES + eTag.size + manifest.body.size)
            .putInt(eTag.size)
            .put(eTag)
            .put(manifest.body)
            .array()
    }

private fun corruptManifestFile(path: Path): IOException =
    IOException("手元のキャッシュの目録のファイルの形式が崩れている: ${path.fileName}")

/** 目録のファイルの中身を読む。形式が崩れていれば [IOException] を投げる。 */
private fun decodeManifest(bytes: ByteArray, path: Path): CachedManifest {
    if (bytes.size < ETAG_LENGTH_BYTES) throw corruptManifestFile(path)
    val eTagLength = ByteBuffer.wrap(bytes).getInt()
    if (eTagLength !in 1..(bytes.size - ETAG_LENGTH_BYTES)) throw corruptManifestFile(path)
    val bodyStart = ETAG_LENGTH_BYTES + eTagLength
    // UTF-8 として読めない ETag は、CharacterCodingException（IOException）になる
    val eTag = bytes.decodeToString(ETAG_LENGTH_BYTES, bodyStart, throwOnInvalidSequence = true)
    return CachedManifest(bytes.copyOfRange(bodyStart, bytes.size), eTag)
}
