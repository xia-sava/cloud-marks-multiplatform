package to.sava.cloudmarksmultiplatform

import to.sava.cloudmarksmultiplatform.update.AppVersion

/** ウィンドウのタイトルの、バージョンの前に置くアプリの名前。 */
private const val APP_NAME = "Cloud Marks"

/**
 * デスクトップのアプリのウィンドウのタイトル。`Cloud Marks <バージョンの表示>`（[AppVersion.displayName]）。
 * 開発版は、配布版や前のビルドと並んでも見分けられるよう、表示にビルドの時刻が添えられている。
 */
fun desktopWindowTitle(version: AppVersion): String = "$APP_NAME ${version.displayName}"
