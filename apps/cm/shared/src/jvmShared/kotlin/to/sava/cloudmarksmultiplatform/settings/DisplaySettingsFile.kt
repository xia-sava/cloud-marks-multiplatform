package to.sava.cloudmarksmultiplatform.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import to.sava.cloudmarksmultiplatform.files.describeIoFailure
import to.sava.cloudmarksmultiplatform.files.replaceFileAtomically
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

private val displaySettingsJson = Json { ignoreUnknownKeys = true }

/** ファイルに書く形。[scale] は表示の大きさの百分率。 */
@Serializable
private class StoredDisplaySettings(val scale: Int)

/**
 * 端末ごとの表示の設定のファイル（JSON）を読み書きする。表示の大きさ（[DisplayScale]）を持つ。
 *
 * 接続の設定（[SettingsFile]）とは別のファイルにし、S3 には載せない。形式の版の欄は持たず、読むときは知らない欄を無視する。
 * 読み書きの失敗は [log] にだけ書き、利用者には見せない。
 *
 * 読み書きはブロックする。呼び出し側が I/O 用のスレッドで呼ぶ。
 *
 * @param path 表示の設定のファイル。親のディレクトリは、書くときに無ければ作る
 * @param log 読み書きの失敗を書く。ファイルが無いことは書かない
 */
class DisplaySettingsFile(private val path: Path, private val log: (String) -> Unit) {
    /** 表示の大きさを読む。ファイルが無いか、読めないか、段に無い値なら、既定の大きさ。 */
    fun read(): DisplayScale {
        val text = try {
            Files.readString(path)
        } catch (e: NoSuchFileException) {
            return DisplayScale.DEFAULT
        } catch (e: IOException) {
            log("表示の設定を読めなかった: ${describeIoFailure(e)}")
            return DisplayScale.DEFAULT
        }
        val stored = try {
            displaySettingsJson.decodeFromString(StoredDisplaySettings.serializer(), text)
        } catch (e: SerializationException) {
            log("表示の設定が、JSON として読めないか、形の合わない値がある")
            return DisplayScale.DEFAULT
        }
        return DisplayScale.ofPercent(stored.scale) ?: DisplayScale.DEFAULT.also {
            log("表示の設定の大きさ ${stored.scale} が、段に無い")
        }
    }

    /** 表示の大きさ [scale] を書く。同じディレクトリの一時ファイルに書いてから、ファイルを置き換える。書けなければログに書く。 */
    fun write(scale: DisplayScale) {
        val text = displaySettingsJson.encodeToString(StoredDisplaySettings.serializer(), StoredDisplaySettings(scale.percent))
        try {
            replaceFileAtomically(path, text.encodeToByteArray())
        } catch (e: IOException) {
            log("表示の設定を書けなかった: ${describeIoFailure(e)}")
        }
    }
}
