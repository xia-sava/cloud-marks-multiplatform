package to.sava.cloudmarksmultiplatform.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import to.sava.cloudmarksmultiplatform.files.describeIoFailure
import to.sava.cloudmarksmultiplatform.files.readUtf8File
import to.sava.cloudmarksmultiplatform.files.replaceFileAtomically
import to.sava.cloudmarksmultiplatform.settings.ConnectionSettings
import to.sava.cloudmarksmultiplatform.sync.DeleteStaleFilesResult
import to.sava.cloudmarksmultiplatform.sync.StorageSync
import to.sava.cloudmarksmultiplatform.sync.connectionName
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** 古いファイルの削除を走らせる間隔。`docs/storage-format.md` の「古いファイルの削除」の、1 日に 1 回程度。 */
internal val STALE_FILE_DELETION_INTERVAL = 24.hours

/** 古いファイルの削除を最後に終えた時刻を記録するファイルの、名前の末尾。名前の頭は、接続先から決まる名前（[connectionName]）。 */
private const val LAST_DELETION_FILE_SUFFIX = ".last-stale-file-deletion"

/**
 * 接続先のバケット [bucket] とプレフィックス [prefix] の、古いファイルの削除を最後に終えた時刻を記録するファイル。
 *
 * @param stateDirectory 記録を置くディレクトリ（[to.sava.cloudmarksmultiplatform.AppFiles.stateDirectory]）
 */
internal fun lastStaleFileDeletionFile(stateDirectory: Path, bucket: String, prefix: String): Path =
    stateDirectory.resolve(connectionName(bucket, prefix) + LAST_DELETION_FILE_SUFFIX)

/**
 * 古いファイルの削除（`docs/storage-format.md`）を、接続先ごとに [STALE_FILE_DELETION_INTERVAL] を空けて走らせる。
 *
 * 前に削除を終えた時刻を、接続先ごとの小さなファイル（[lastStaleFileDeletionFile]）に、RFC 3339 の UTC の日時で記録する。
 * 書くときは一時ファイルに書いてから置き換える。経過は端末の時計 [clock] で測る。間隔の目安なので、端末の時計がずれていてもよい。
 * 記録が無いか読めなければ、間隔を過ぎているとする。
 *
 * 削除の結果と、記録のファイルの I/O の失敗は [log] にだけ書き、利用者には見せない。
 *
 * @param stateDirectory 記録を置くディレクトリ。無ければ、書くときに作る
 * @param log 結果を書く。[ioDispatcher] で呼ぶ
 * @param ioDispatcher 記録のファイルの読み書きと、ログを書くことに使う
 */
internal class StaleFileDeletion(
    private val stateDirectory: Path,
    private val clock: Clock,
    private val log: (String) -> Unit,
    private val ioDispatcher: CoroutineDispatcher,
) {
    /**
     * 接続先 [settings] で前に削除を終えてから間隔を過ぎていれば、[sync] で古いファイルを消し、結果をログに書く。
     * 最後まで進め、消せなかったファイルも無ければ、その時刻を記録する。
     */
    suspend fun runIfDue(settings: ConnectionSettings, sync: StorageSync) {
        val record = lastStaleFileDeletionFile(stateDirectory, settings.bucket, settings.prefix)
        val last = withContext(ioDispatcher) { readRecord(record) }
        if (last != null && clock.now() - last < STALE_FILE_DELETION_INTERVAL) return
        val result = sync.deleteStaleFiles()
        withContext(ioDispatcher) {
            log(describeStaleFileDeletion(result))
            if (result is DeleteStaleFilesResult.Success && result.failed == 0) writeRecord(record, clock.now())
        }
    }

    /** 記録した時刻。ファイルが無いか、読めないか、日時として読めなければ null。読めなかったことはログに書く。 */
    private fun readRecord(path: Path): Instant? {
        val text = try {
            readUtf8File(path)
        } catch (e: NoSuchFileException) {
            return null
        } catch (e: IOException) {
            log("古いファイルの削除の記録を読めなかった: ${describeIoFailure(e)}")
            return null
        }
        return try {
            Instant.parse(text.trim())
        } catch (e: IllegalArgumentException) {
            log("古いファイルの削除の記録が、日時として読めない")
            null
        }
    }

    /** [time] を記録する。書けなければログに書く。 */
    private fun writeRecord(path: Path, time: Instant) {
        try {
            replaceFileAtomically(path, "$time\n".encodeToByteArray())
        } catch (e: IOException) {
            log("古いファイルの削除の記録を書けなかった: ${describeIoFailure(e)}")
        }
    }
}

/** 古いファイルの削除の結果を、ログの 1 行にする。 */
private fun describeStaleFileDeletion(result: DeleteStaleFilesResult): String =
    when (result) {
        is DeleteStaleFilesResult.Success ->
            "古いファイルの削除: 消した ${result.deleted}、消せなかった ${result.failed}、猶予の中なので残した ${result.withinGrace}、" +
                "形に合わず触らなかったキー ${result.untouched}" + (result.lastFailure?.let { "、最後の失敗 $it" } ?: "")
        is DeleteStaleFilesResult.Failed -> "古いファイルの削除を、何も消さずに終えた: ${result.failure}"
    }
