package to.sava.cloudmarksmultiplatform.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import to.sava.cloudmarksmultiplatform.files.describeIoFailure
import to.sava.cloudmarksmultiplatform.files.readUtf8File
import to.sava.cloudmarksmultiplatform.files.replaceFileAtomically
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.Path

private val deviceSettingsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private val DEFAULTS = DeviceSettings()

/**
 * ファイルに書く形。[scale] は表示の大きさの百分率。欄が無ければ既定の値にする。
 *
 * @property loadAtOnce 同時に読み込むタブの数
 * @property loadAhead 先頭からこの数のタブを、少しずつ読み込む。0 なら、すべてのタブを順に読み込む
 */
@Serializable
private class StoredDeviceSettings(
    val scale: Int = DEFAULTS.displayScale.percent,
    val loadAtOnce: Int = DEFAULTS.tabLoading.loadAtOnce,
    val loadAhead: Int = DEFAULTS.tabLoading.loadAhead,
)

/**
 * 端末ごとの設定のファイル（JSON）を読み書きする。表示の大きさ（[DisplayScale]）と、まとめてブラウザで開くときのタブの読み込み方
 * （[TabLoading]）を持つ。
 *
 * 接続の設定（[SettingsFile]）とは別のファイルにし、S3 には載せない。形式のバージョンの欄は持たず、読むときは知らない欄を無視し、
 * 無い欄は既定の値にする。読み書きの失敗は [log] にだけ書き、利用者には見せない。
 *
 * 読み書きはブロックする。呼び出し側が I/O 用のスレッドで呼ぶ。
 *
 * @param path 端末ごとの設定のファイル。親のディレクトリは、書くときに無ければ作る
 * @param log 読み書きの失敗と、使えない値を書く。ファイルが無いことは書かない
 */
class DeviceSettingsFile(private val path: Path, private val log: (String) -> Unit) {
    /**
     * 端末ごとの設定を読む。ファイルが無いか、読めないか、JSON として読めないか型の合わない値があれば、すべて既定の値。
     * 段に無い表示の大きさと、下限（同時に読み込むタブの数は [MINIMUM_TAB_COUNT]、先に読み込むタブの数は [MINIMUM_LOAD_AHEAD]）
     * 未満の数は、その欄だけ既定の値にする。
     */
    fun read(): DeviceSettings {
        val text = try {
            readUtf8File(path)
        } catch (e: NoSuchFileException) {
            return DEFAULTS
        } catch (e: IOException) {
            log("端末ごとの設定を読めなかった: ${describeIoFailure(e)}")
            return DEFAULTS
        }
        val stored = try {
            deviceSettingsJson.decodeFromString(StoredDeviceSettings.serializer(), text)
        } catch (e: SerializationException) {
            log("端末ごとの設定が、JSON として読めないか、形の合わない値がある")
            return DEFAULTS
        }
        return DeviceSettings(
            displayScale = DisplayScale.ofPercent(stored.scale) ?: DEFAULTS.displayScale.also {
                log("端末ごとの設定の表示の大きさ ${stored.scale} が、段に無い")
            },
            tabLoading = TabLoading(
                loadAtOnce = countOrDefault(
                    "同時に読み込むタブの数",
                    stored.loadAtOnce,
                    MINIMUM_TAB_COUNT,
                    DEFAULTS.tabLoading.loadAtOnce,
                ),
                loadAhead = countOrDefault(
                    "先に読み込むタブの数",
                    stored.loadAhead,
                    MINIMUM_LOAD_AHEAD,
                    DEFAULTS.tabLoading.loadAhead,
                ),
            ),
        )
    }

    /** 端末ごとの設定 [settings] を書く。同じディレクトリの一時ファイルに書いてから、ファイルを置き換える。書けなければログに書く。 */
    fun write(settings: DeviceSettings) {
        val stored = StoredDeviceSettings(
            scale = settings.displayScale.percent,
            loadAtOnce = settings.tabLoading.loadAtOnce,
            loadAhead = settings.tabLoading.loadAhead,
        )
        val text = deviceSettingsJson.encodeToString(StoredDeviceSettings.serializer(), stored)
        try {
            replaceFileAtomically(path, text.encodeToByteArray())
        } catch (e: IOException) {
            log("端末ごとの設定を書けなかった: ${describeIoFailure(e)}")
        }
    }

    /** 読んだ数 [value] が [minimum] 以上ならそのまま、そうでなければ [label] の欄の値が使えないことをログに書き、[default]。 */
    private fun countOrDefault(label: String, value: Int, minimum: Int, default: Int): Int =
        value.takeIf { it >= minimum } ?: default.also { log("端末ごとの設定の$label $value が、$minimum 未満") }
}
