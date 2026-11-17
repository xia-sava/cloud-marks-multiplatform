package to.sava.cloudmarksmultiplatform

import java.nio.file.Path

/** 設定のファイルの名前。 */
internal const val SETTINGS_FILE_NAME = "settings.json"

/** 表示の設定のファイルの名前。 */
internal const val DISPLAY_SETTINGS_FILE_NAME = "display.json"

/** S3 へ書いたことを同じ端末のほかのプロセスへ知らせるファイルの名前。 */
internal const val STORAGE_CHANGED_FILE_NAME = "storage-changed"

/**
 * アプリが端末に置くファイルの場所。
 *
 * 場所の決め方はプラットフォームごとに違い、それぞれのアプリのエントリポイントで作って渡す。
 * パスを持つだけで、ディレクトリは作らない。作るのは書く側。[storageChangedFile] は、見張る側も見張るためにディレクトリを作る。
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
) {
    /**
     * S3 へ書いたことを、同じ端末のほかのプロセスへ知らせるファイル。拡張から追加したホストが書き換え、デスクトップのアプリが
     * 書き換わったのを見て S3 から読み直す。中身は使わない。
     */
    val storageChangedFile: Path get() = stateDirectory.resolve(STORAGE_CHANGED_FILE_NAME)
}
