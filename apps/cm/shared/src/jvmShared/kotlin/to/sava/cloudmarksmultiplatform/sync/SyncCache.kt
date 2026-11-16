package to.sava.cloudmarksmultiplatform.sync

import to.sava.cloudmarksmultiplatform.storage.Manifest
import to.sava.cloudmarksmultiplatform.storage.referencedItemsFileKeys
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** 手元に持つ目録。GET で受けた gzip のままの中身と、その ETag。 */
class CachedManifest(val body: ByteArray, val eTag: String)

/**
 * 手元のキャッシュ。
 *
 * 目録は ETag と一緒に持ち、書き込みの手順 1 の条件付きの GET に使う。
 * フォルダのファイルは書き換えないため、キーごとに持てば古くならない。中身は gzip のまま持ち、使うたびにいまの目録で検証する。
 *
 * 接続先（バケットとプレフィックス）ごとに 1 つ作る。
 */
interface SyncCache {
    /** 手元の目録。無ければ null。 */
    suspend fun manifest(): CachedManifest?

    /** 手元の目録を [manifest] に替える。 */
    suspend fun putManifest(manifest: CachedManifest)

    /** キー [key] のフォルダのファイル。無ければ null。 */
    suspend fun itemsFile(key: String): ByteArray?

    /** キー [key] のフォルダのファイルを持つ。 */
    suspend fun putItemsFile(key: String, body: ByteArray)

    /**
     * フォルダのファイルのうち、キーが [keys] にあるものだけを残し、ほかを消す。目録は変えない。
     *
     * キャッシュ自身は目録を解釈しない。どのキーを残すかは呼び出し側が決める。
     */
    suspend fun retainItemsFiles(keys: Set<String>)

    /** 手元のものをすべて消す。目録もフォルダのファイルも、何も持たない状態に戻る。 */
    suspend fun clear()
}

/**
 * 新しい目録を手元の目録として受け入れる。手元の目録を [body] と [eTag] に替え、[manifest] の指さないフォルダのファイルを手元から消す。
 *
 * @param prefix 接続先のプレフィックス。フォルダのファイルのキーを作るのに使う
 * @param manifest [body] を読んだ目録
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
