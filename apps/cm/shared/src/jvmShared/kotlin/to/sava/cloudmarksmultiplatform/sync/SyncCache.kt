package to.sava.cloudmarksmultiplatform.sync

import to.sava.cloudmarksmultiplatform.storage.Manifest
import to.sava.cloudmarksmultiplatform.storage.referencedItemsFileKeys
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** ローカルキャッシュに持つマニフェスト。GET で受けた gzip のままの中身と、その ETag。 */
class CachedManifest(val body: ByteArray, val eTag: String)

/**
 * ローカルキャッシュ。
 *
 * マニフェストは ETag と一緒に持ち、書き込みの手順 1 の条件付きの GET に使う。
 * 項目ファイルは書き換えないため、キーごとに持てば古くならない。中身は gzip のまま持ち、使うたびにいまのマニフェストで検証する。
 *
 * 接続先（バケットとプレフィックス）ごとに 1 つ作る。
 */
interface SyncCache {
    /** ローカルキャッシュのマニフェスト。無ければ null。 */
    suspend fun manifest(): CachedManifest?

    /** ローカルキャッシュのマニフェストを [manifest] に替える。 */
    suspend fun putManifest(manifest: CachedManifest)

    /** キー [key] の項目ファイル。無ければ null。 */
    suspend fun itemsFile(key: String): ByteArray?

    /** キー [key] の項目ファイルを持つ。 */
    suspend fun putItemsFile(key: String, body: ByteArray)

    /**
     * 項目ファイルのうち、キーが [keys] にあるものだけを残し、ほかを消す。マニフェストは変えない。
     *
     * キャッシュ自身はマニフェストを解釈しない。どのキーを残すかは呼び出し側が決める。
     */
    suspend fun retainItemsFiles(keys: Set<String>)

    /** ローカルキャッシュのものをすべて消す。マニフェストも項目ファイルも、何も持たない状態に戻る。 */
    suspend fun clear()
}

/**
 * 新しいマニフェストをローカルキャッシュのマニフェストとして受け入れる。
 * ローカルキャッシュのマニフェストを [body] と [eTag] に替え、[manifest] の指さない項目ファイルをローカルキャッシュから消す。
 *
 * @param prefix 接続先のプレフィックス。項目ファイルのキーを作るのに使う
 * @param manifest [body] を読んだマニフェスト
 */
internal suspend fun SyncCache.acceptManifest(prefix: String, manifest: Manifest, body: ByteArray, eTag: String) {
    putManifest(CachedManifest(body, eTag))
    retainItemsFiles(referencedItemsFileKeys(prefix, manifest))
}

/** メモリ上のキャッシュ。複数のスレッドから使える。 */
class InMemorySyncCache : SyncCache {
    private val manifest = AtomicReference<CachedManifest?>(null)
    private val itemsFiles = ConcurrentHashMap<String, ByteArray>()

    override suspend fun manifest(): CachedManifest? = manifest.get()

    override suspend fun putManifest(manifest: CachedManifest) {
        this.manifest.set(manifest)
    }

    override suspend fun itemsFile(key: String): ByteArray? = itemsFiles[key]

    override suspend fun putItemsFile(key: String, body: ByteArray) {
        itemsFiles[key] = body
    }

    override suspend fun retainItemsFiles(keys: Set<String>) {
        itemsFiles.keys.retainAll(keys)
    }

    override suspend fun clear() {
        manifest.set(null)
        itemsFiles.clear()
    }
}
