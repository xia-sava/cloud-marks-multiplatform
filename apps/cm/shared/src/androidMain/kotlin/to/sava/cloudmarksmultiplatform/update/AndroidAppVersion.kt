package to.sava.cloudmarksmultiplatform.update

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/** `versionName` が無いときのバージョン。`-Pcloudmarks.versionName` を渡さないビルドと同じ。 */
private const val FALLBACK_VERSION_NAME = "0.0.0"

/**
 * Android のアプリのバージョン。`PackageManager` が持つ自分のパッケージの `versionCode` と `versionName` を使い、
 * 配布版かは `FLAG_DEBUGGABLE` が立っていないことで決める。
 *
 * @throws IllegalStateException 自分のパッケージの情報を取れなかった
 */
fun androidAppVersion(context: Context): AppVersion {
    val packageInfo = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    } catch (e: PackageManager.NameNotFoundException) {
        throw IllegalStateException("自分のパッケージの情報を取れない: ${context.packageName}", e)
    }
    return androidAppVersionOf(
        longVersionCode = packageInfo.longVersionCode,
        versionName = packageInfo.versionName,
        applicationFlags = context.applicationInfo.flags,
    )
}

/**
 * `PackageManager` の値から [AppVersion] を作る。[versionName] が null なら既定のバージョン。
 * [applicationFlags]（`ApplicationInfo.flags`）に `FLAG_DEBUGGABLE` が立っていれば開発版。
 *
 * Android にはビルドの時刻を渡す手段が無いので、開発版の表示は時刻を添えない形（[devVersionName]）になる。
 *
 * @throws ArithmeticException [longVersionCode] が `Int` に収まらない
 */
internal fun androidAppVersionOf(longVersionCode: Long, versionName: String?, applicationFlags: Int): AppVersion =
    appVersionOf(
        versionCode = Math.toIntExact(longVersionCode),
        versionName = versionName ?: FALLBACK_VERSION_NAME,
        distributed = applicationFlags and ApplicationInfo.FLAG_DEBUGGABLE == 0,
        buildEpochMillis = null,
    )
