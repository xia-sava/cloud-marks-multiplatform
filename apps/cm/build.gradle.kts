plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
}

/** `-Pcloudmarks.versionCode` を渡されなかったときの値。 */
private val DEFAULT_VERSION_CODE = 1

// 更新の新旧の比較に使う、1以上の整数のバージョン。`:androidApp` と `:desktopApp` が `rootProject.extra` から読む。
// 渡された値が1以上の整数でなければ、どちらのアプリを組む場合もここで失敗させる。
extra["cloudMarksVersionCode"] = providers.gradleProperty("cloudmarks.versionCode")
    .map { value ->
        value.toIntOrNull()?.takeIf { it > 0 }
            ?: throw GradleException("-Pcloudmarks.versionCode は1以上の整数で渡す: $value")
    }
    .getOrElse(DEFAULT_VERSION_CODE)
