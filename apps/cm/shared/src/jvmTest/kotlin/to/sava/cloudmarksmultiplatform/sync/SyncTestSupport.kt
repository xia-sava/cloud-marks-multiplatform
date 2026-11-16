package to.sava.cloudmarksmultiplatform.sync

import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore
import to.sava.cloudmarksmultiplatform.storage.FolderEntry
import to.sava.cloudmarksmultiplatform.storage.Item
import to.sava.cloudmarksmultiplatform.storage.ItemsFile
import to.sava.cloudmarksmultiplatform.storage.ItemsFileDecodeResult
import to.sava.cloudmarksmultiplatform.storage.ItemsFileHeader
import to.sava.cloudmarksmultiplatform.storage.Manifest
import to.sava.cloudmarksmultiplatform.storage.ManifestDecodeResult
import to.sava.cloudmarksmultiplatform.storage.ManifestHeader
import to.sava.cloudmarksmultiplatform.storage.decodeItemsFileGzip
import to.sava.cloudmarksmultiplatform.storage.decodeManifestGzip
import to.sava.cloudmarksmultiplatform.storage.encodeItemsFileGzip
import to.sava.cloudmarksmultiplatform.storage.encodeManifestGzip
import to.sava.cloudmarksmultiplatform.storage.itemsFileKey
import to.sava.cloudmarksmultiplatform.storage.manifestKey
import to.sava.cloudmarksmultiplatform.storage.newId
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.TestTimeSource

private fun uuid(kind: Int, n: Int): String = "${kind}0000000-0000-4000-8000-" + n.toString().padStart(12, '0')

internal fun folderId(n: Int): String = uuid(0, n)

internal fun fileId(n: Int): String = uuid(1, n)

internal fun itemId(n: Int): String = uuid(2, n)

internal val ROOT_ID = folderId(0)
internal val FOLDER_A = folderId(1)
internal val FOLDER_B = folderId(2)
internal val ROOT_FILE_ID = fileId(1)
internal val A_FILE_ID = fileId(2)
internal val INITIAL_COMMIT = uuid(3, 1)

internal const val UPDATED_BY = "desktop"

/** 時計が返す現在。ミリ秒未満を持つ。 */
internal val NOW: Instant = Instant.parse("2026-11-16T12:34:56.789123Z")

internal val ADDED: Instant = Instant.parse("2026-11-16T12:30:00.000Z")

internal fun item(n: Int): Item = Item(itemId(n), "https://example.com/$n", "title-$n")

internal val ROOT_ITEMS = listOf(item(1), item(2))
internal val A_ITEMS = listOf(item(3))

/** 根（項目 2 つ）→ A（項目 1 つ）、B（項目なし）の目録。 */
internal fun initialManifest(version: Int = 1, readableBy: Int = 1): Manifest =
    Manifest(
        header = ManifestHeader(version, readableBy, INITIAL_COMMIT, Instant.parse("2026-11-01T00:00:00.000Z"), "android"),
        folders = listOf(
            FolderEntry(ROOT_ID, null, "", ROOT_FILE_ID, ROOT_ITEMS.size),
            FolderEntry(FOLDER_A, ROOT_ID, "A", A_FILE_ID, A_ITEMS.size),
            FolderEntry(FOLDER_B, ROOT_ID, "B", null, 0),
        ),
    )

internal object FixedClock : Clock {
    override fun now(): Instant = NOW
}

/** [InMemorySyncCache] と同じに振る舞い、[retainItemsFiles] に渡されたキーの集合を呼ばれた順に記録する。 */
internal class RecordingSyncCache(private val delegate: SyncCache = InMemorySyncCache()) : SyncCache by delegate {
    val retained = mutableListOf<Set<String>>()

    override suspend fun retainItemsFiles(keys: Set<String>) {
        retained += keys
        delegate.retainItemsFiles(keys)
    }
}

/**
 * 偽のストレージ、メモリ上のキャッシュ、時計と、それらを使う [StorageSync]。
 *
 * 待ちは記録して [onWait] を走らせるだけで、時間を使わない。
 */
internal class SyncFixture {
    val store = FakeObjectStore()
    val cache = RecordingSyncCache()
    val timeSource = TestTimeSource()
    val waits = mutableListOf<Duration>()

    /** 待ちのたびに走らせる。待つあいだに起きる事象（時計が進むなど）を差し込む。 */
    var onWait: (Duration) -> Unit = {}

    val sync = StorageSync(
        store = store,
        cache = cache,
        updatedBy = UPDATED_BY,
        timeSource = timeSource,
        clock = FixedClock,
        wait = { duration ->
            waits += duration
            onWait(duration)
        },
    )

    val manifestKey: String get() = manifestKey(store.prefix)

    fun itemsFileKey(folderId: String, fileId: String): String = itemsFileKey(store.prefix, folderId, fileId)

    /** フォルダ [folderId] のフォルダのファイルのキーか。 */
    fun isItemsFileOf(folderId: String): (String) -> Boolean = { key -> key.startsWith("${store.prefix}/folders/$folderId/") }

    /** 目録のキーか。 */
    val isManifest: (String) -> Boolean get() = { key -> key == manifestKey }
}

internal fun itemsFileBody(folderId: String, items: List<Item>): ByteArray =
    encodeItemsFileGzip(ItemsFile(ItemsFileHeader(folderId), items))

/** [manifest] と、[initialManifest] の根と A のフォルダのファイルを、条件を付けずに置く。 */
internal fun FakeObjectStore.seed(manifest: Manifest = initialManifest()) {
    overwrite(itemsFileKey(prefix, ROOT_ID, ROOT_FILE_ID), itemsFileBody(ROOT_ID, ROOT_ITEMS))
    overwrite(itemsFileKey(prefix, FOLDER_A, A_FILE_ID), itemsFileBody(FOLDER_A, A_ITEMS))
    overwrite(manifestKey(prefix), encodeManifestGzip(manifest))
}

/** 置いてある目録。読み書きできる目録でなければ失敗にする。 */
internal fun FakeObjectStore.storedManifest(): Manifest {
    val stored = assertNotNull(objectAt(manifestKey(prefix)), "目録が無い")
    return assertIs<ManifestDecodeResult.Readable>(decodeManifestGzip(stored.body)).manifest
}

/** 置いてある目録の ETag。 */
internal fun FakeObjectStore.storedManifestETag(): String = assertNotNull(objectAt(manifestKey(prefix))).eTag

/** [manifest] が指す、フォルダ [folderId] の項目。 */
internal fun FakeObjectStore.storedItems(manifest: Manifest, folderId: String): List<Item> {
    val entry = manifest.folders.single { it.id == folderId }
    val fileId = entry.items ?: return emptyList()
    val stored = assertNotNull(objectAt(itemsFileKey(prefix, folderId, fileId)), "フォルダ $folderId のファイルが無い")
    val decoded = decodeItemsFileGzip(stored.body, folderId, entry, manifest.header.version)
    return assertIs<ItemsFileDecodeResult.Readable>(decoded).file.items
}

/** ほかの書き手として、根の子のフォルダの末尾に、項目を持たないフォルダ [folderId] を足す。目録の `commit` と ETag が替わる。 */
internal fun FakeObjectStore.addFolderAsOtherWriter(folderId: String) {
    val current = storedManifest()
    val updated = Manifest(
        header = current.header.copy(commit = newId(), updatedBy = "android"),
        folders = current.folders + FolderEntry(folderId, current.folders.first().id, "other", null, 0),
    )
    overwrite(manifestKey(prefix), encodeManifestGzip(updated))
}

internal fun FakeObjectStore.puts(matches: (String) -> Boolean): List<FakeObjectStore.Request.Put> =
    requests.filterIsInstance<FakeObjectStore.Request.Put>().filter { matches(it.key) }

internal fun FakeObjectStore.gets(matches: (String) -> Boolean): List<FakeObjectStore.Request.Get> =
    requests.filterIsInstance<FakeObjectStore.Request.Get>().filter { matches(it.key) }

/** フォルダのファイルのキーから、ファイルの ID を取り出す。 */
internal fun fileIdOfKey(key: String): String = key.substringAfterLast('/').removeSuffix(".jsonl.gz")
