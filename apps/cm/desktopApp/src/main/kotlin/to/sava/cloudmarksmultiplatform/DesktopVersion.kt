package to.sava.cloudmarksmultiplatform

import to.sava.cloudmarksmultiplatform.update.AppVersion
import to.sava.cloudmarksmultiplatform.update.appVersionOf
import java.util.Properties

/** バージョンを書いたリソース。`build.gradle.kts` の `generateVersionProperties` が書く。 */
private const val VERSION_RESOURCE = "/cloudmarks-version.properties"

/** [VERSION_RESOURCE] の、更新の新旧の比較に使うバージョンのキー。 */
private const val VERSION_CODE_KEY = "versionCode"

/** [VERSION_RESOURCE] の、バージョンのキー。 */
private const val VERSION_NAME_KEY = "versionName"

/** [VERSION_RESOURCE] を読めないときの `versionCode`。`-Pcloudmarks.versionCode` を渡さないビルドと同じ。 */
private const val FALLBACK_VERSION_CODE = 1

/** [VERSION_RESOURCE] を読めないときのバージョン。`-Pcloudmarks.versionName` を渡さないビルドと同じ。 */
private const val FALLBACK_VERSION_NAME = "0.0.0"

/** デスクトップのアプリのバージョン。 */
object DesktopVersion {
    private val properties: Properties = Properties().apply {
        DesktopVersion::class.java.getResourceAsStream(VERSION_RESOURCE)?.use { load(it) }
    }

    /**
     * 更新の新旧の比較に使うバージョン。ビルドに `-Pcloudmarks.versionCode` で渡したもの。
     * リソースに書かれた値が1以上の整数でなければ、起動を失敗させる。
     */
    val versionCode: Int = properties.getProperty(VERSION_CODE_KEY)
        ?.let { value ->
            value.toIntOrNull()?.takeIf { it > 0 }
                ?: throw IllegalStateException("$VERSION_RESOURCE の $VERSION_CODE_KEY が1以上の整数でない: $value")
        }
        ?: FALLBACK_VERSION_CODE

    /** バージョン。ビルドに `-Pcloudmarks.versionName` で渡したもの。 */
    val versionName: String = properties.getProperty(VERSION_NAME_KEY) ?: FALLBACK_VERSION_NAME

    /** 開発版のバージョンの表示に添える、ビルドの時刻（[desktopBuildEpochMillis]）。分からなければ null。 */
    val buildEpochMillis: Long? = desktopBuildEpochMillis(DesktopVersion::class.java)
}

/** 動いているデスクトップのアプリのバージョン。配布版かどうかは [AppPath.isDistributed] で決める。 */
fun desktopAppVersion(): AppVersion =
    appVersionOf(
        versionCode = DesktopVersion.versionCode,
        versionName = DesktopVersion.versionName,
        distributed = AppPath.isDistributed,
        buildEpochMillis = DesktopVersion.buildEpochMillis,
    )
