package to.sava.cloudmarksmultiplatform.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.walk
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val BODY = byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, 42)

private const val CONCURRENT_WORKERS = 6
private const val CONCURRENT_ROUNDS = 100

/** 同時に書く中身の種類の数。 */
private const val CONCURRENT_VERSIONS = 7

/** 同時に読み書きするあいだに、何回に 1 回 retain を呼ぶか。 */
private const val CONCURRENT_RETAIN_INTERVAL = 5

private const val BODY_UNIT_SIZE = 2048

private val HASH_NAME = Regex("[0-9a-f]{64}")

/** バージョン [version] の中身。全バイトが [version] で、長さがバージョンごとに違うため、途中までの中身や混ざった中身はバージョンの中身にならない。 */
private fun versionedBody(version: Int): ByteArray = ByteArray(BODY_UNIT_SIZE * (version + 1)) { version.toByte() }

private fun versionedETag(version: Int): String = "\"etag-$version\""

/** [bytes] が、いずれかのバージョンの [versionedBody] と完全に一致するか。 */
private fun isVersionedBody(bytes: ByteArray): Boolean =
    bytes.isNotEmpty() && bytes[0] in 0 until CONCURRENT_VERSIONS && bytes.contentEquals(versionedBody(bytes[0].toInt()))

/** [FileSyncCache] と [connectionCacheDirectory] に固有の振る舞い。実装に依らない振る舞いは [SyncCacheContractTest]。 */
class FileSyncCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val root: Path by lazy { temporaryFolder.root.toPath().resolve("cache-root") }
    private val directory: Path by lazy { connectionCacheDirectory(root, "bucket", "prefix") }

    private val failures = mutableListOf<IOException>()

    private fun cache(at: Path = directory): FileSyncCache = FileSyncCache(at) { failures += it }

    private fun Path.names(): List<String> = listDirectoryEntries().map { it.name }.sorted()

    // 接続先のディレクトリは、バケットかプレフィックスが違えば違い、同じなら同じ。前後の / は同じ接続先として扱う
    @Test
    fun connectionDirectoryDependsOnBucketAndPrefix() {
        val base = connectionCacheDirectory(root, "bucket", "prefix")

        assertEquals(base, connectionCacheDirectory(root, "bucket", "prefix"))
        assertEquals(base, connectionCacheDirectory(root, "bucket", "/prefix/"))
        assertNotEquals(base, connectionCacheDirectory(root, "other", "prefix"))
        assertNotEquals(base, connectionCacheDirectory(root, "bucket", "other"))
    }

    // バケットとプレフィックスの境目が違えば、つなげた文字列が同じでも別のディレクトリになる
    @Test
    fun connectionDirectoryDistinguishesBucketPrefixBoundary() {
        assertNotEquals(
            connectionCacheDirectory(root, "ab", "c"),
            connectionCacheDirectory(root, "a", "bc"),
        )
    }

    // ディレクトリ名は、ルートディレクトリの直下の、パスとして安全な固定長の 16 進
    @Test
    fun connectionDirectoryNameIsFixedLengthHexDirectlyUnderRoot() {
        val dangerous = connectionCacheDirectory(root, "..", "../../x\\y:*?")
        val plain = connectionCacheDirectory(root, "b", "")

        assertEquals(root, dangerous.parent)
        assertEquals(root, plain.parent)
        listOf(dangerous, plain).forEach { assertTrue(Regex("[0-9a-f]{64}").matches(it.name), it.name) }
    }

    // 危険な文字を含むキーでも、ファイルはキャッシュのディレクトリの中にだけでき、往復する
    @Test
    fun hostileKeysStayInsideCacheDirectory() = runTest {
        val cache = cache()
        val keys = listOf("../../escape", "a/../../b", "C:\\windows\\x", "x".repeat(2000), "")

        keys.forEachIndexed { index, key -> cache.putItemsFile(key, byteArrayOf(index.toByte())) }

        keys.forEachIndexed { index, key -> assertContentEquals(byteArrayOf(index.toByte()), cache.itemsFile(key)) }
        assertEquals(emptyList(), failures)
        @OptIn(kotlin.io.path.ExperimentalPathApi::class)
        val files = temporaryFolder.root.toPath().walk().filter { !it.isDirectory() }.toList()
        assertEquals(keys.size, files.size)
        assertTrue(files.all { it.startsWith(directory) })
    }

    // 別のインスタンス（別のプロセスの代わり）が、同じディレクトリのファイルを読める
    @Test
    fun anotherInstanceReadsTheSameDirectory() = runTest {
        cache().apply {
            putManifest(CachedManifest(BODY, "\"e\""))
            putItemsFile("k", byteArrayOf(5))
        }

        val other = cache()

        assertContentEquals(BODY, other.manifest()?.body)
        assertEquals("\"e\"", other.manifest()?.eTag)
        assertContentEquals(byteArrayOf(5), other.itemsFile("k"))
    }

    // 別のインスタンスが消したファイルは、読むと null になるだけで、失敗の通知は来ない
    @Test
    fun fileRemovedByAnotherInstanceReadsAsNullWithoutNotification() = runTest {
        val writer = cache()
        writer.putItemsFile("k", byteArrayOf(5))
        cache().retainItemsFiles(emptySet())

        assertNull(writer.itemsFile("k"))
        assertEquals(emptyList(), failures)
    }

    // 置き換えたあとに一時ファイルが残らない（マニフェストと項目ファイルの両方）
    @Test
    fun replacingLeavesNoTemporaryFile() = runTest {
        val cache = cache()

        repeat(3) {
            cache.putManifest(CachedManifest(byteArrayOf(it.toByte()), "\"e$it\""))
            cache.putItemsFile("k", byteArrayOf(it.toByte()))
        }

        assertEquals(listOf("items", "manifest"), directory.names())
        assertEquals(1, directory.resolve("items").names().size)
        assertEquals(emptyList(), failures)
    }

    // マニフェストのファイルの形式が崩れていれば、null を返して通知する。ファイルが無いだけなら通知しない
    @Test
    fun corruptManifestFileReadsAsNullAndIsReported() = runTest {
        val cache = cache()
        assertNull(cache.manifest())
        assertEquals(0, failures.size)

        Files.createDirectories(directory)
        val manifestPath = directory.resolve("manifest")
        val corruptContents = listOf(
            byteArrayOf(),
            byteArrayOf(0, 0),
            byteArrayOf(0, 0, 0, 0, 1, 2),
            byteArrayOf(0, 0, 0, 9, 1, 2),
            byteArrayOf(-1, -1, -1, -1, 1, 2),
            byteArrayOf(0, 0, 0, 2, 0xff.toByte(), 0xfe.toByte(), 1),
        )
        corruptContents.forEachIndexed { index, contents ->
            Files.write(manifestPath, contents)

            assertNull(cache.manifest(), "case $index")
            assertEquals(index + 1, failures.size, "case $index")
        }
    }

    // 書けない（ルートディレクトリがファイルになっている）とき、例外は出ず、失敗を通知する処理が呼ばれる。読むと null
    @Test
    fun unwritableRootDoesNotThrowAndNotifies() = runTest {
        Files.createDirectories(root.parent)
        Files.write(root, byteArrayOf(1))
        val cache = cache()

        cache.putManifest(CachedManifest(BODY, "\"e\""))
        val afterManifest = failures.size
        cache.putItemsFile("k", BODY)
        val afterItems = failures.size
        cache.retainItemsFiles(emptySet())

        assertTrue(afterManifest >= 1)
        assertTrue(afterItems > afterManifest)
        assertNull(cache.manifest())
        assertNull(cache.itemsFile("k"))
        assertTrue(root.exists() && !root.isDirectory())
    }

    // I/O の失敗は、呼び出し元のスレッドではなく I/O 用のスレッドで通知する（UI のスレッドから呼んでも、そこでログを書かない）
    @Test
    fun failureIsReportedOffCallerThread() = runTest {
        Files.createDirectories(root.parent)
        Files.write(root, byteArrayOf(1))
        val callerThread = Thread.currentThread()
        val reportingThreads = mutableListOf<Thread>()
        val cache = FileSyncCache(directory) { reportingThreads += Thread.currentThread() }

        cache.putManifest(CachedManifest(BODY, "\"e\""))

        assertTrue(reportingThreads.isNotEmpty())
        assertTrue(reportingThreads.none { it == callerThread })
    }

    // 書けなかったとき、一時ファイルが残らない（置き換え先が中身のあるディレクトリ）
    @Test
    fun failedReplaceLeavesNoTemporaryFile() = runTest {
        Files.createDirectories(directory.resolve("manifest").resolve("occupied"))
        val cache = cache()

        cache.putManifest(CachedManifest(BODY, "\"e\""))

        assertEquals(1, failures.size)
        assertEquals(listOf("manifest"), directory.names())
    }

    // retain は、名前がハッシュの形でないファイル（ほかのプロセスが書いている途中の一時ファイルなど）には触らない
    @Test
    fun retainLeavesFilesThatAreNotCacheEntries() = runTest {
        val cache = cache()
        cache.putItemsFile("drop", byteArrayOf(1))
        val foreign = directory.resolve("items").resolve("a".repeat(64) + ".123.tmp")
        Files.write(foreign, byteArrayOf(9))

        cache.retainItemsFiles(emptySet())

        assertTrue(foreign.exists())
        assertNull(cache.itemsFile("drop"))
        assertEquals(emptyList(), failures)
    }

    // 複数のコルーチンが同じディレクトリを同時に読み書き・削除しても、例外は出ず、読めた中身は書いた中身のどれかと
    // 完全に一致し（途中の中身は見えない）、終わったあとに一時ファイルは残らない。
    // Windows では置き換えや削除が失敗して通知されることがあるので、通知の有無は見ない
    @Test
    fun concurrentAccessNeverExposesPartialContentsAndLeavesNoTemporaryFile() = runBlocking(Dispatchers.IO) {
        val cache = FileSyncCache(directory) { }
        val keys = listOf("k0", "k1", "k2")

        (0 until CONCURRENT_WORKERS).map { worker ->
            launch {
                repeat(CONCURRENT_ROUNDS) { round ->
                    val version = (worker * CONCURRENT_ROUNDS + round) % CONCURRENT_VERSIONS
                    val key = keys[round % keys.size]
                    cache.putItemsFile(key, versionedBody(version))
                    cache.itemsFile(key)?.let { assertTrue(isVersionedBody(it), "items $key") }
                    cache.putManifest(CachedManifest(versionedBody(version), versionedETag(version)))
                    cache.manifest()?.let { assertTrue(isVersionedBody(it.body) && it.eTag == versionedETag(it.body[0].toInt()), "manifest") }
                    if (round % CONCURRENT_RETAIN_INTERVAL == 0) cache.retainItemsFiles(setOf(key))
                }
            }
        }.joinAll()

        assertEquals(setOf("items", "manifest"), directory.names().toSet())
        assertTrue(directory.resolve("items").names().all { HASH_NAME.matches(it) }, directory.resolve("items").names().toString())
    }

    // 消せないファイルがあっても、その失敗を通知して、残りのファイルは消す
    @Test
    fun retainReportsUndeletableEntryAndContinues() = runTest {
        val cache = cache()
        cache.putItemsFile("drop", byteArrayOf(1))
        // ハッシュの形の名前の、中身のあるディレクトリは消せない
        val stuck = directory.resolve("items").resolve("b".repeat(64))
        Files.createDirectories(stuck.resolve("child"))

        cache.retainItemsFiles(emptySet())

        assertNull(cache.itemsFile("drop"))
        assertEquals(1, failures.size)
        assertTrue(stuck.exists())
    }

    // clear は、消せないファイルがあっても、その失敗を通知して、残りのファイルは消す。
    // ハッシュの形でない名前のファイル（ほかのプロセスが書いている途中の一時ファイル）は消さない
    @Test
    fun clearReportsUndeletableEntryAndContinuesAndLeavesTemporaryFiles() = runTest {
        val cache = cache()
        cache.putManifest(CachedManifest(BODY, "\"m\""))
        cache.putItemsFile("drop", byteArrayOf(1))
        val stuck = directory.resolve("items").resolve("b".repeat(64))
        Files.createDirectories(stuck.resolve("child"))
        val temporary = directory.resolve("items").resolve("writing.tmp")
        Files.write(temporary, byteArrayOf(2))

        cache.clear()

        assertNull(cache.manifest())
        assertNull(cache.itemsFile("drop"))
        assertEquals(1, failures.size)
        assertTrue(stuck.exists())
        assertTrue(temporary.exists())
    }
}
