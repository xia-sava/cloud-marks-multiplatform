package to.sava.cloudmarksmultiplatform.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import to.sava.cloudmarksmultiplatform.files.describeIoFailure
import to.sava.cloudmarksmultiplatform.files.readUtf8File
import to.sava.cloudmarksmultiplatform.files.replaceFileAtomically
import to.sava.cloudmarksmultiplatform.operations.ItemRef
import to.sava.cloudmarksmultiplatform.storage.isUuidShape
import to.sava.cloudmarksmultiplatform.sync.connectionName
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

private val pendingDeletesJson = Json { ignoreUnknownKeys = true }

/** 溜めた削除の記録のファイルの、名前の末尾。名前の頭は、接続先から決まる名前（[connectionName]）。 */
private const val PENDING_DELETES_FILE_SUFFIX = ".pending-deletes.json"

/**
 * 接続先のバケット [bucket] とプレフィックス [prefix] の、溜めた削除の記録のファイル。
 *
 * @param stateDirectory 記録を置くディレクトリ（[to.sava.cloudmarksmultiplatform.AppFiles.stateDirectory]）
 */
internal fun pendingDeletesFile(stateDirectory: Path, bucket: String, prefix: String): Path =
    stateDirectory.resolve(connectionName(bucket, prefix) + PENDING_DELETES_FILE_SUFFIX)

/** ファイルに書く、溜めた削除の対象 1 つ。項目を持つフォルダの ID と、項目の ID。 */
@Serializable
private class StoredTarget(val folderId: String, val itemId: String)

/** ファイルに書く形。[targets] は溜めた削除の対象の並び。 */
@Serializable
private class StoredPendingDeletes(val targets: List<StoredTarget>)

/**
 * 溜めた削除（S3 につながらないために端末に溜めた、項目の削除）の記録のファイル（JSON。`{"targets":[{"folderId":"…","itemId":"…"}]}`）を
 * 読み書きする。接続先ごとに 1 つのファイル（[pendingDeletesFile]）にする。
 *
 * 端末ごとの記録で、S3 には載せない。形式のバージョンの欄は持たず、読むときは知らない欄を無視する。
 * 読み書きの失敗は [log] にだけ書き、利用者には見せない。
 *
 * 読み書きはブロックする。呼び出し側が I/O 用のスレッドで呼ぶ。
 *
 * @param path 記録のファイル。親のディレクトリは、書くときに無ければ作る
 * @param log 読み書きの失敗と、記録の形でないことを書く。ファイルが無いことは書かない
 */
internal class PendingDeletesFile(private val path: Path, private val log: (String) -> Unit) {
    /**
     * 溜めた削除の対象を、記録の並びの順で読む。同じ対象が 2 度以上書かれていれば、最初のものだけを残す。
     * ファイルが無いか、読めないか、記録の形でなければ（JSON として読めない・欄が無いか型が合わない・ID が UUID の形でない）空。
     */
    fun read(): Set<ItemRef> {
        val text = try {
            readUtf8File(path)
        } catch (e: NoSuchFileException) {
            return emptySet()
        } catch (e: IOException) {
            log("溜めた削除の記録を読めなかった: ${describeIoFailure(e)}")
            return emptySet()
        }
        val stored = try {
            pendingDeletesJson.decodeFromString(StoredPendingDeletes.serializer(), text)
        } catch (e: SerializationException) {
            log("溜めた削除の記録が、JSON として読めないか、形の合わない値がある")
            return emptySet()
        }
        if (stored.targets.any { !isUuidShape(it.folderId) || !isUuidShape(it.itemId) }) {
            log("溜めた削除の記録の ID が、UUID の形でない")
            return emptySet()
        }
        return stored.targets.map { ItemRef(it.folderId, it.itemId) }.toSet()
    }

    /**
     * 溜めた削除の対象 [targets] を、その並びで書く。同じディレクトリの一時ファイルに書いてから、ファイルを置き換える。
     * [targets] が空なら、ファイルを消す。書けないか消せなければログに書く。
     */
    fun write(targets: Set<ItemRef>) {
        if (targets.isEmpty()) {
            delete()
            return
        }
        val stored = StoredPendingDeletes(targets.map { StoredTarget(it.folderId, it.itemId) })
        val text = pendingDeletesJson.encodeToString(StoredPendingDeletes.serializer(), stored)
        try {
            replaceFileAtomically(path, text.encodeToByteArray())
        } catch (e: IOException) {
            log("溜めた削除の記録を書けなかった: ${describeIoFailure(e)}")
        }
    }

    private fun delete() {
        try {
            Files.deleteIfExists(path)
        } catch (e: IOException) {
            log("溜めた削除の記録を消せなかった: ${describeIoFailure(e)}")
        }
    }
}
