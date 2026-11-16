package to.sava.cloudmarksmultiplatform.app

import to.sava.cloudmarksmultiplatform.files.describeIoFailure
import to.sava.cloudmarksmultiplatform.remote.S3ObjectStore
import to.sava.cloudmarksmultiplatform.settings.ConnectionSettings
import to.sava.cloudmarksmultiplatform.sync.FileSyncCache
import to.sava.cloudmarksmultiplatform.sync.StorageSync
import to.sava.cloudmarksmultiplatform.sync.connectionCacheDirectory
import java.nio.file.Path
import kotlin.time.TimeSource

/**
 * 設定から作った、S3 への接続。使い終えたら [close] で S3 のクライアントを閉じる。
 *
 * @param closeStore S3 のクライアントを閉じる
 */
class Connection(val sync: StorageSync, private val closeStore: () -> Unit) : AutoCloseable {
    override fun close() {
        closeStore()
    }
}

/**
 * [settings] の S3 への接続を作る。ローカルキャッシュは、接続先ごとのディレクトリ（[connectionCacheDirectory]）に置く。
 *
 * キャッシュのファイルの I/O の失敗は、説明にして [log] へ書き、利用者への誤りとしては見せない。
 * S3 のクライアントを作るので、I/O 用のスレッドで呼ぶ。
 *
 * @param cacheRoot ローカルキャッシュのルートディレクトリ
 * @param updatedBy 書き込むマニフェストのヘッダーの `updatedBy`
 * @param timeSource 書き込みの手順 3 から手順 4 までの経過を測る、モノトニッククロック
 */
fun openS3Connection(
    settings: ConnectionSettings,
    cacheRoot: Path,
    updatedBy: String,
    timeSource: TimeSource,
    log: (String) -> Unit,
): Connection {
    val store = S3ObjectStore(
        region = settings.region,
        accessKeyId = settings.credentials.accessKeyId,
        secretAccessKey = settings.credentials.secretAccessKey,
        bucket = settings.bucket,
        prefix = settings.prefix,
    )
    val cache = FileSyncCache(connectionCacheDirectory(cacheRoot, settings.bucket, settings.prefix)) { failure ->
        log("ローカルキャッシュを読み書きできなかった: ${describeIoFailure(failure)}")
    }
    return Connection(StorageSync(store, cache, updatedBy, timeSource), store::close)
}
