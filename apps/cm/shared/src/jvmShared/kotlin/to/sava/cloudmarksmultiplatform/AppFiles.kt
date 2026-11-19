package to.sava.cloudmarksmultiplatform

import java.nio.file.Path

/** 設定のファイルの名前。 */
internal const val SETTINGS_FILE_NAME = "settings.json"

/** 端末ごとの設定のファイルの名前。 */
internal const val DEVICE_SETTINGS_FILE_NAME = "device.json"

/** ローカルキャッシュを置くディレクトリの名前。 */
internal const val CACHE_DIRECTORY_NAME = "cache"

/** ログのファイルを置くディレクトリの名前。 */
internal const val LOG_DIRECTORY_NAME = "logs"

/** 次の起動へ持ち越す記録を置くディレクトリの名前。 */
internal const val STATE_DIRECTORY_NAME = "state"

/** ブラウザの拡張機能へのコマンドを置くディレクトリの名前。 */
internal const val COMMAND_DIRECTORY_NAME = "commands"

/** アプリの更新でダウンロードした配布物を置くディレクトリの名前。 */
internal const val UPDATE_DIRECTORY_NAME = "updates"

/** S3 へ書いたことを同じ端末のほかのプロセスへ知らせるファイルの名前。 */
internal const val STORAGE_CHANGED_FILE_NAME = "storage-changed"

/** 拡張機能が入っていると分かったブラウザの記録のファイルの名前。 */
internal const val BROWSERS_WITH_EXTENSION_FILE_NAME = "browsers-with-extension.json"

/** 最後に開いた項目の記録のファイルの名前。 */
internal const val LAST_OPENED_ITEM_FILE_NAME = "last-opened-item.json"

/** デスクトップのアプリのウィンドウの位置と大きさの記録のファイルの名前。 */
internal const val WINDOW_GEOMETRY_FILE_NAME = "window.json"

/** デスクトップのアプリの多重起動を防ぐロックのファイルの名前。 */
internal const val INSTANCE_LOCK_FILE_NAME = "instance.lock"

/** 起動しているデスクトップのアプリに、ウィンドウを前に出させる知らせのファイルの名前。 */
internal const val SHOW_WINDOW_FILE_NAME = "show-window"

/**
 * アプリが端末に置くファイルの場所。
 *
 * 場所の決め方はプラットフォームごとに違い、それぞれのアプリのエントリポイントで作って渡す。
 * パスを持つだけで、ディレクトリは作らない。作るのは書く側。[storageChangedFile] と [showWindowFile] は、見張る側も見張るために
 * ディレクトリを作る。[instanceLockFile] は、ロックを取る側が作る。
 *
 * @property settingsFile 設定のファイル
 * @property cacheDirectory ローカルキャッシュのルートディレクトリ。接続先ごとのディレクトリはこの下に作る
 * @property logDirectory ログのファイルを置くディレクトリ
 * @property stateDirectory 次の起動へ持ち越す記録と、同じ端末のプロセスどうしの知らせを、小さなファイルにして置くディレクトリ。
 * 接続先ごとに置くもの（古いファイルの削除を最後に終えた時刻、溜めた削除）と、接続先によらないもの（最後に開いた項目、拡張機能が入っていると
 * 分かったブラウザ、S3 へ書いたことの知らせ、デスクトップのアプリのウィンドウの位置と大きさ、デスクトップのアプリの多重起動を防ぐロック、
 * ウィンドウを前に出させる知らせ）がある
 * @property deviceSettingsFile 端末ごとの設定（表示の大きさと、まとめてブラウザで開くときのタブの読み込み方）のファイル。
 * [settingsFile] とは別に持ち、S3 に載せない
 * @property commandDirectory アプリからブラウザの拡張機能へのコマンド（開くタブの並びなど）を、1 つのコマンドにつき 1 つのファイルにして
 * 置くディレクトリ。ファイルの名前はコマンドのトークン。Native Messaging のホストが読んで消す
 * @property updateDirectory アプリの更新でダウンロードした配布物を置くディレクトリ。デスクトップは、ダウンロードごとのディレクトリをこの下に作り、
 * 配布物を適用するスクリプトも同じディレクトリに置く
 */
class AppFiles(
    val settingsFile: Path,
    val cacheDirectory: Path,
    val logDirectory: Path,
    val stateDirectory: Path,
    val deviceSettingsFile: Path,
    val commandDirectory: Path,
    val updateDirectory: Path,
) {
    /**
     * S3 へ書いたことを、同じ端末のほかのプロセスへ知らせるファイル。拡張から追加したホストが書き換え、デスクトップのアプリが
     * 書き換わったのを見て S3 から読み直す。中身は使わない。
     */
    val storageChangedFile: Path get() = stateDirectory.resolve(STORAGE_CHANGED_FILE_NAME)

    /**
     * 拡張機能が入っていると分かったブラウザの記録。拡張機能から知らせを受けたホストが、そのブラウザの実行ファイルのパスを書き足し、
     * デスクトップのアプリが、複数の項目を開けるブラウザを決めるのに読む。
     */
    val browsersWithExtensionFile: Path get() = stateDirectory.resolve(BROWSERS_WITH_EXTENSION_FILE_NAME)

    /**
     * 最後に開いた項目の記録。デスクトップのアプリと Android のアプリが、項目を 1 つだけ開いたときに書き、次の起動で読む。
     * 端末ごとの記録で、S3 に載せない。
     */
    val lastOpenedItemFile: Path get() = stateDirectory.resolve(LAST_OPENED_ITEM_FILE_NAME)

    /**
     * デスクトップのアプリのウィンドウの位置と大きさの記録。デスクトップのアプリが、ウィンドウを動かしたり大きさを変えたりしたときと
     * 終了するときに書き、次の起動で読む。端末ごとの記録で、S3 に載せない。
     */
    val windowGeometryFile: Path get() = stateDirectory.resolve(WINDOW_GEOMETRY_FILE_NAME)

    /**
     * デスクトップのアプリの多重起動を防ぐロックのファイル。起動したアプリが開いて OS のロックを取り、終了の後始末で閉じるまで持つ。
     * ロックを取れなければ、このアプリのデータを置くディレクトリを、既に起動しているアプリが使っている。ファイルがあるかでは決めない。
     * 中身は使わない。
     */
    val instanceLockFile: Path get() = stateDirectory.resolve(INSTANCE_LOCK_FILE_NAME)

    /**
     * 起動しているデスクトップのアプリに、ウィンドウを前に出させる知らせ。既に起動しているときに起動したアプリが書き換えて終わり、
     * 起動しているアプリが書き換わったのを見てウィンドウを前に出す。中身は使わない。
     */
    val showWindowFile: Path get() = stateDirectory.resolve(SHOW_WINDOW_FILE_NAME)
}
