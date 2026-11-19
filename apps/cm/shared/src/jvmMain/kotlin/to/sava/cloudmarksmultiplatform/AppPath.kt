package to.sava.cloudmarksmultiplatform

import java.io.File
import java.io.IOException

/** jpackage のランチャーが、自分の実行ファイルのパスを渡すシステムプロパティ。 */
private const val APP_PATH_PROPERTY = "jpackage.app-path"

/** 動いているランタイムのディレクトリを持つシステムプロパティ。 */
private const val JAVA_HOME_PROPERTY = "java.home"

/**
 * 配布物の実行ファイルのパス（jpackage のランチャーが渡す `jpackage.app-path`）。
 *
 * この値はシステムプロパティから来るので、起動するときに差し替えられる。実在する実行ファイルで、
 * 動いているランタイム（`java.home`）をそのディレクトリの配下に持つものだけを使う。
 *
 * これで配布版か開発版かを見分ける。jpackage のランチャーから起動されたものが配布版で、IDE から直に起動したもの、
 * `:desktopApp:run`、開発用のホスト（`writeDevNativeMessagingHost` が書くバッチ）は開発版になる。
 */
object AppPath {
    /** 確かめた実行ファイルのパス。jpackage のランチャーから起動されていないとき、値が確かめに通らなかったときは null。 */
    val verified: String? by lazy {
        System.getProperty(APP_PATH_PROPERTY)?.takeIf { isTrusted(it, System.getProperty(JAVA_HOME_PROPERTY)) }
    }

    /** 配布版か。[verified] があれば配布版、無ければ開発版。 */
    val isDistributed: Boolean
        get() = verified != null

    /**
     * [appPath] を配布物の実行ファイルとして使ってよいか。
     *
     * 実在するファイルで、そのディレクトリの配下に [runtimeHome] があれば使う。辿れないパスは、確かめられないので使わない。
     */
    fun isTrusted(appPath: String?, runtimeHome: String?): Boolean {
        if (appPath.isNullOrBlank() || runtimeHome.isNullOrBlank()) return false
        return try {
            val app = File(appPath).canonicalFile
            val installDirectory = app.parentFile ?: return false
            app.isFile && File(runtimeHome).canonicalFile.toPath().startsWith(installDirectory.toPath())
        } catch (e: IOException) {
            false
        }
    }
}
