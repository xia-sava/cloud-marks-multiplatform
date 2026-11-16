package to.sava.cloudmarksmultiplatform.sync

import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** どの [SyncCache] の実装も満たす振る舞い。実装ごとのサブクラスで走らせる。 */
abstract class SyncCacheContractTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** 空のキャッシュを作る。失敗の通知が来たらテストを失敗にする。 */
    protected abstract fun newCache(): SyncCache

    private val body = byteArrayOf(0x1f, 0x8b.toByte(), 0, 1, 2, 3)

    // 何も持たないとき、目録もフォルダのファイルも無い
    @Test
    fun emptyCacheHasNothing() = runTest {
        val cache = newCache()

        assertNull(cache.manifest())
        assertNull(cache.itemsFile("cloud_marks/folders/a/b.jsonl.gz"))
    }

    // 目録は、本文と ETag が往復する。ETag は引用符もそのまま
    @Test
    fun manifestRoundTrips() = runTest {
        val cache = newCache()

        cache.putManifest(CachedManifest(body, "\"abc123\""))

        val read = assertNotNull(cache.manifest())
        assertContentEquals(body, read.body)
        assertEquals("\"abc123\"", read.eTag)
    }

    // 目録を持ち直すと、新しいほうに替わる
    @Test
    fun putManifestReplacesPrevious() = runTest {
        val cache = newCache()
        cache.putManifest(CachedManifest(byteArrayOf(1, 2, 3, 4, 5), "\"old\""))

        cache.putManifest(CachedManifest(byteArrayOf(9), "\"new\""))

        val read = assertNotNull(cache.manifest())
        assertContentEquals(byteArrayOf(9), read.body)
        assertEquals("\"new\"", read.eTag)
    }

    // 本文が空でも、ETag が日本語でも往復する
    @Test
    fun manifestWithEmptyBodyAndNonAsciiETagRoundTrips() = runTest {
        val cache = newCache()

        cache.putManifest(CachedManifest(byteArrayOf(), "\"日本語\""))

        val read = assertNotNull(cache.manifest())
        assertContentEquals(byteArrayOf(), read.body)
        assertEquals("\"日本語\"", read.eTag)
    }

    // フォルダのファイルは、キーごとに本文が往復する。別のキーのファイルとは混ざらない
    @Test
    fun itemsFilesRoundTripPerKey() = runTest {
        val cache = newCache()

        cache.putItemsFile("p/folders/a/1.jsonl.gz", body)
        cache.putItemsFile("p/folders/a/2.jsonl.gz", byteArrayOf(7))

        assertContentEquals(body, cache.itemsFile("p/folders/a/1.jsonl.gz"))
        assertContentEquals(byteArrayOf(7), cache.itemsFile("p/folders/a/2.jsonl.gz"))
        assertNull(cache.itemsFile("p/folders/a/3.jsonl.gz"))
    }

    // 残すキーのファイルは残り、ほかのフォルダのファイルは消える。目録は変わらない
    @Test
    fun retainKeepsListedItemsFilesAndRemovesOthers() = runTest {
        val cache = newCache()
        cache.putManifest(CachedManifest(body, "\"m\""))
        cache.putItemsFile("keep-1", byteArrayOf(1))
        cache.putItemsFile("keep-2", byteArrayOf(2))
        cache.putItemsFile("drop", byteArrayOf(3))

        cache.retainItemsFiles(setOf("keep-1", "keep-2", "never-stored"))

        assertContentEquals(byteArrayOf(1), cache.itemsFile("keep-1"))
        assertContentEquals(byteArrayOf(2), cache.itemsFile("keep-2"))
        assertNull(cache.itemsFile("drop"))
        assertNull(cache.itemsFile("never-stored"))
        assertEquals("\"m\"", cache.manifest()?.eTag)
    }

    // 空の集合を渡すと、フォルダのファイルはすべて消える
    @Test
    fun retainWithEmptySetRemovesAllItemsFiles() = runTest {
        val cache = newCache()
        cache.putItemsFile("a", byteArrayOf(1))
        cache.putItemsFile("b", byteArrayOf(2))

        cache.retainItemsFiles(emptySet())

        assertNull(cache.itemsFile("a"))
        assertNull(cache.itemsFile("b"))
    }

    // clear すると、目録もフォルダのファイルもすべて消える
    @Test
    fun clearRemovesManifestAndAllItemsFiles() = runTest {
        val cache = newCache()
        cache.putManifest(CachedManifest(body, "\"m\""))
        cache.putItemsFile("a", byteArrayOf(1))
        cache.putItemsFile("b", byteArrayOf(2))

        cache.clear()

        assertNull(cache.manifest())
        assertNull(cache.itemsFile("a"))
        assertNull(cache.itemsFile("b"))
    }

    // 何も持たないキャッシュに clear しても失敗しない。clear のあとに持ち直せる
    @Test
    fun clearOnEmptyCacheIsHarmlessAndCacheIsUsableAfterClear() = runTest {
        val cache = newCache()

        cache.clear()
        cache.putManifest(CachedManifest(body, "\"m\""))
        cache.putItemsFile("a", byteArrayOf(1))

        assertEquals("\"m\"", cache.manifest()?.eTag)
        assertContentEquals(byteArrayOf(1), cache.itemsFile("a"))
    }

    // 何も持たないキャッシュに retain しても失敗しない。そのあと持てる
    @Test
    fun retainOnEmptyCacheIsHarmless() = runTest {
        val cache = newCache()

        cache.retainItemsFiles(setOf("a"))
        cache.putItemsFile("a", byteArrayOf(1))

        assertContentEquals(byteArrayOf(1), cache.itemsFile("a"))
    }
}

/** [InMemorySyncCache] が [SyncCache] の振る舞いを満たす。 */
class InMemorySyncCacheTest : SyncCacheContractTest() {
    override fun newCache(): SyncCache = InMemorySyncCache()
}

/** [FileSyncCache] が [SyncCache] の振る舞いを満たす。 */
class FileSyncCacheContractTest : SyncCacheContractTest() {
    override fun newCache(): SyncCache =
        FileSyncCache(temporaryFolder.root.toPath().resolve("cache")) { throw AssertionError("失敗の通知は来ないはず", it) }
}
