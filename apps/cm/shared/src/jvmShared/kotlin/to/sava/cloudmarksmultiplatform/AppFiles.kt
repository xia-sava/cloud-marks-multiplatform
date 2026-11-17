package to.sava.cloudmarksmultiplatform

import java.nio.file.Path

/** 設定のファイルの名前。 */
internal const val SETTINGS_FILE_NAME = "settings.json"

/** 表示の設定のファイルの名前。 */
internal const val DISPLAY_SETTINGS_FILE_NAME = "display.json"

/**
 * アプリが端末に置くファイルの場所。
 *
 * 場所の決め方はプラットフォームごとに違い、それぞれのアプリのエントリポイントで作って渡す。
 * パスを持つだけで、ディレクトリは作らない。作るのは書く側。
 *
 * @property settingsFile 設定のファイル
 * @property cacheDirectory ローカルキャッシュのルートディレクトリ。接続先ごとのディレクトリはこの下に作る
 * @property logDirectory ログのファイルを置くディレクトリ
 * @property stateDirectory 次の起動へ持ち越す記録（古いファイルの削除を最後に終えた時刻など）を、接続先ごとの小さなファイルにして
 * 置くディレクトリ
 * @property displaySettingsFile 端末ごとの表示の設定（表示の大きさ）のファイル。[settingsFile] とは別に持ち、S3 に載せない
 */
class AppFiles(
    val settingsFile: Path,
    val cacheDirectory: Path,
    val logDirectory: Path,
    val stateDirectory: Path,
    val displaySettingsFile: Path,
)
