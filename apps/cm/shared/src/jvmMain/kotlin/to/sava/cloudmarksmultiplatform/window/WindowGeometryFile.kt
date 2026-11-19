package to.sava.cloudmarksmultiplatform.window

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import to.sava.cloudmarksmultiplatform.files.describeIoFailure
import to.sava.cloudmarksmultiplatform.files.readUtf8File
import to.sava.cloudmarksmultiplatform.files.replaceFileAtomically
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.Path

private val windowGeometryJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * ウィンドウの位置と大きさと、最大化しているか。位置と大きさは dp（AWT の座標と同じ）。
 *
 * 最大化しているときの位置と大きさは、記録から読んだもの（[WindowGeometryFile.read]）では最大化を解いたときに戻る値、
 * いまのウィンドウから取ったもの（[windowGeometryOf]）では画面いっぱいの値。
 */
data class WindowGeometry(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val maximized: Boolean,
)

/** ファイルに書く形。[maximized] が無ければ、最大化していないものとする。 */
@Serializable
private class StoredWindowGeometry(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val maximized: Boolean = false,
)

/**
 * デスクトップのアプリのウィンドウの位置と大きさの記録のファイル
 * （JSON。`{"x":…,"y":…,"width":…,"height":…,"maximized":…}`）を読み書きする。
 *
 * 端末ごとの記録で、S3 には載せない。形式のバージョンの欄は持たず、読むときは知らない欄を無視する。
 * 読み書きの失敗は [log] にだけ書き、利用者には見せない。
 *
 * 読み書きはブロックする。呼び出し側が I/O 用のスレッドで呼ぶ。
 *
 * @param path 記録のファイル（[to.sava.cloudmarksmultiplatform.AppFiles.windowGeometryFile]）。親のディレクトリは、書くときに無ければ作る
 * @param log 読み書きの失敗と、記録の形でないことを書く。ファイルが無いことは書かない
 */
class WindowGeometryFile(private val path: Path, private val log: (String) -> Unit) {
    /**
     * 記録したウィンドウの位置と大きさを読む。ファイルが無いか、読めないか、記録の形でなければ（JSON として読めない・
     * `x`・`y`・`width`・`height` のどれかが無いか整数でない・`maximized` が真偽値でない・幅か高さが 0 以下）null。
     */
    fun read(): WindowGeometry? {
        val text = try {
            readUtf8File(path)
        } catch (e: NoSuchFileException) {
            return null
        } catch (e: IOException) {
            log("ウィンドウの位置と大きさの記録を読めなかった: ${describeIoFailure(e)}")
            return null
        }
        val stored = try {
            windowGeometryJson.decodeFromString(StoredWindowGeometry.serializer(), text)
        } catch (e: SerializationException) {
            log("ウィンドウの位置と大きさの記録が、JSON として読めないか、形の合わない値がある")
            return null
        }
        if (stored.width <= 0 || stored.height <= 0) {
            log("ウィンドウの位置と大きさの記録の幅か高さが、0 以下")
            return null
        }
        return WindowGeometry(x = stored.x, y = stored.y, width = stored.width, height = stored.height, maximized = stored.maximized)
    }

    /**
     * ウィンドウの位置と大きさ [geometry] を書く。同じディレクトリの一時ファイルに書いてから、ファイルを置き換える。書けなければログに書く。
     *
     * 最大化しているときの位置と大きさは画面いっぱいの値なので書かず、最大化を解いたときに戻る位置と大きさとして、記録にある値を残す。
     * 記録が無ければ（読めなければ）、何も書かない。
     */
    fun write(geometry: WindowGeometry) {
        val written = if (geometry.maximized) read()?.copy(maximized = true) ?: return else geometry
        val stored = StoredWindowGeometry(
            x = written.x,
            y = written.y,
            width = written.width,
            height = written.height,
            maximized = written.maximized,
        )
        val text = windowGeometryJson.encodeToString(StoredWindowGeometry.serializer(), stored)
        try {
            replaceFileAtomically(path, text.encodeToByteArray())
        } catch (e: IOException) {
            log("ウィンドウの位置と大きさの記録を書けなかった: ${describeIoFailure(e)}")
        }
    }
}
