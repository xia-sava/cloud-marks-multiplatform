package to.sava.cloudmarksmultiplatform

import androidx.core.content.FileProvider

/**
 * アプリの更新でダウンロードした APK を、端末のインストーラが読めるようにする FileProvider。マニフェストで、authority を
 * `${applicationId}.updates`、渡せる場所を `update_paths` として宣言する。
 *
 * ライブラリがマニフェストで宣言する `FileProvider` と、マニフェストを統合するときにぶつからないよう、専用のサブクラスにする。
 */
class UpdateFileProvider : FileProvider()
