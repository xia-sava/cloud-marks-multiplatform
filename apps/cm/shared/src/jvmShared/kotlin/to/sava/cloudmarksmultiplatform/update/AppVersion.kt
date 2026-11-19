package to.sava.cloudmarksmultiplatform.update

/**
 * 動いているアプリのバージョン。
 *
 * @property versionCode 更新の新旧の比較に使う整数のバージョン。
 * @property versionName 配布版のバージョンの文字列。
 * @property displayName 画面に出すバージョン。配布版は [versionName]、開発版は [devVersionName]。
 * @property distributed 配布版か。開発版は、更新を確認できるが、ダウンロードとインストールは受け付けない。
 */
data class AppVersion(
    val versionCode: Int,
    val versionName: String,
    val displayName: String,
    val distributed: Boolean,
)

/**
 * [AppVersion] を作る。配布版（[distributed] が真）の [AppVersion.displayName] は [versionName]、
 * 開発版はビルドの時刻 [buildEpochMillis]（エポックからのミリ秒、分からなければ null）を添えた [devVersionName]。
 */
fun appVersionOf(versionCode: Int, versionName: String, distributed: Boolean, buildEpochMillis: Long?): AppVersion =
    AppVersion(
        versionCode = versionCode,
        versionName = versionName,
        displayName = if (distributed) versionName else devVersionName(versionName, buildEpochMillis),
        distributed = distributed,
    )
