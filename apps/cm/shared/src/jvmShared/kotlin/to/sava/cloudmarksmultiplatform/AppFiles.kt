package to.sava.cloudmarksmultiplatform

import java.nio.file.Path

/** 設定のファイルの名前。 */
internal const val SETTINGS_FILE_NAME = "settings.json"

/**
 * アプリが手元に置くファイルの場所。
 *
 * 場所の決め方はプラットフォームごとに違い、それぞれのアプリの入口で作って渡す。
 * パスを持つだけで、ディレクトリは作らない。作るのは書く側。
 *
 * @property settingsFile 設定のファイル
 * @property cacheDirectory 手元のキャッシュの根。接続先ごとのディレクトリはこの下に作る
 */
class AppFiles(val settingsFile: Path, val cacheDirectory: Path)
