package to.sava.cloudmarksmultiplatform.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import to.sava.cloudmarksmultiplatform.files.describeIoFailure
import to.sava.cloudmarksmultiplatform.files.readUtf8File
import to.sava.cloudmarksmultiplatform.files.replaceFileAtomically
import to.sava.cloudmarksmultiplatform.storage.isUuidShape
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.Path

private val lastOpenedItemJson = Json { ignoreUnknownKeys = true }

/** ファイルに書く形。[itemId] は最後に開いた項目の ID。 */
@Serializable
private class StoredLastOpenedItem(val itemId: String)

/**
 * 最後に開いた項目の記録のファイル（JSON。`{"itemId":"…"}`）を読み書きする。
 *
 * 端末ごとの記録で、S3 には載せない。形式の版の欄は持たず、読むときは知らない欄を無視する。
 * 読み書きの失敗は [log] にだけ書き、利用者には見せない。
 *
 * 読み書きはブロックする。呼び出し側が I/O 用のスレッドで呼ぶ。
 *
 * @param path 記録のファイル（[to.sava.cloudmarksmultiplatform.AppFiles.lastOpenedItemFile]）。親のディレクトリは、書くときに無ければ作る
 * @param log 読み書きの失敗と、記録の形でないことを書く。ファイルが無いことは書かない
 */
internal class LastOpenedItemFile(private val path: Path, private val log: (String) -> Unit) {
    /**
     * 最後に開いた項目の ID を読む。ファイルが無いか、読めないか、記録の形でなければ（JSON として読めない・`itemId` が無いか
     * 文字列でない・ID が UUID の形でない）null。
     */
    fun read(): String? {
        val text = try {
            readUtf8File(path)
        } catch (e: NoSuchFileException) {
            return null
        } catch (e: IOException) {
            log("最後に開いた項目の記録を読めなかった: ${describeIoFailure(e)}")
            return null
        }
        val stored = try {
            lastOpenedItemJson.decodeFromString(StoredLastOpenedItem.serializer(), text)
        } catch (e: SerializationException) {
            log("最後に開いた項目の記録が、JSON として読めないか、形の合わない値がある")
            return null
        }
        if (!isUuidShape(stored.itemId)) {
            log("最後に開いた項目の記録の ID が、UUID の形でない")
            return null
        }
        return stored.itemId
    }

    /** 最後に開いた項目の ID [itemId] を書く。同じディレクトリの一時ファイルに書いてから、ファイルを置き換える。書けなければログに書く。 */
    fun write(itemId: String) {
        val text = lastOpenedItemJson.encodeToString(StoredLastOpenedItem.serializer(), StoredLastOpenedItem(itemId))
        try {
            replaceFileAtomically(path, text.encodeToByteArray())
        } catch (e: IOException) {
            log("最後に開いた項目の記録を書けなかった: ${describeIoFailure(e)}")
        }
    }
}
