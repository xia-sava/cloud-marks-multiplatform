package to.sava.cloudmarksmultiplatform

import java.nio.file.Path

/** 設定のファイルの名前。 */
internal const val SETTINGS_FILE_NAME = "settings.json"

/**
 * アプリが端末に置くファイルの場所。
 *
 * 場所の決め方はプラットフォームごとに違い、それぞれのアプリのエントリポイントで作って渡す。
 * パスを持つだけで、ディレクトリは作らない。作るのは書く側。
 *
 * @property settingsFile 設定のファイル
 * @property cacheDirectory ローカルキャッシュのルートディレクトリ。接続先ごとのディレクトリはこの下に作る
 * @property logDirectory ログのファイルを置くディレクトリ
 */
class AppFiles(val settingsFile: Path, val cacheDirectory: Path, val logDirectory: Path)
