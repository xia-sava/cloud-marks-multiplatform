import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.UsesKotlinJavaToolchain

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

/** デスクトップ（`jvm()`）のコンパイルとテストに使うJDKのバージョン。 */
private val DESKTOP_JDK_VERSION = libs.versions.desktop.jdk.get().toInt()

/**
 * FFM（`java.lang.foreign`）でネイティブの関数を呼ぶことを許す起動引数。
 * Java 24以降は、許していないコードが呼ぶと警告が出る。
 */
private val ENABLE_NATIVE_ACCESS = "--enable-native-access=ALL-UNNAMED"

/**
 * デスクトップのJDK。`jvm()`のコンパイルと`jvmTest`だけに使い、Androidのコンパイルとホストのテストは既定のJDKのまま動かす。
 *
 * `kotlin.jvmToolchain`はプロジェクト全体に適用され、Androidにも及ぶ。
 */
private val desktopJdkSpec = Action<JavaToolchainSpec> {
    languageVersion.set(JavaLanguageVersion.of(DESKTOP_JDK_VERSION))
}
private val desktopJdk = extensions.getByType<JavaToolchainService>().launcherFor(desktopJdkSpec)
private val desktopJavaCompiler = extensions.getByType<JavaToolchainService>().compilerFor(desktopJdkSpec)

kotlin {
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.fromTarget(DESKTOP_JDK_VERSION.toString())
        }
        compilations.configureEach {
            compileTaskProvider.configure {
                (this as UsesKotlinJavaToolchain).kotlinJavaToolchain.toolchain.use(desktopJdk)
            }
            compileJavaTaskProvider?.configure {
                javaCompiler.set(desktopJavaCompiler)
            }
        }
    }

    android {
        namespace = "to.sava.cloudmarksmultiplatform.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        withHostTest {}
    }

    sourceSets {
        // jvmMainとandroidMainの双方から使うJVMの実装を置く場所。S3へのアクセス、gzipの読み書き、設定のファイルの読み書き、
        // 画面とその状態を置く。
        val jvmShared = create("jvmShared") {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.aws.sdk.s3)
                implementation(libs.compose.material3Adaptive)
                implementation(libs.compose.materialIconsCore)
            }
        }

        androidMain {
            dependsOn(jvmShared)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.kotlinx.coroutines)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serializationJson)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.testJunit)
            // 画面のテストは、窓を出さずに runComposeUiTest で画面を動かす。描くための Skia のネイティブのライブラリを、
            // 走らせる OS のものから取る。
            implementation(libs.compose.uiTest)
            implementation(compose.desktop.currentOs)
        }
        jvmMain {
            dependsOn(jvmShared)
        }
    }
}

// jvmMainの認証情報の暗号化（DPAPI）をFFMで呼ぶため、デスクトップのJDKで走らせ、ネイティブの関数の呼び出しを許す。
tasks.named<Test>("jvmTest") {
    javaLauncher.set(desktopJdk)
    jvmArgs(ENABLE_NATIVE_ACCESS)
}

/**
 * フィクスチャを、`jvmTest`の入力として宣言する。
 *
 * 宣言しなければGradleからは見えない。見えないと、**フィクスチャだけを書き換えた回はタスクがup-to-dateになり、
 * 1件も走らないままBUILD SUCCESSFULが返る**。読むのは`jvmTest`のテストだけ。
 */
tasks.named<Test>("jvmTest") {
    inputs.dir(rootProject.file("../../contracts/storage"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("storageContracts")
    inputs.dir(rootProject.file("../../contracts/native-messaging"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("nativeMessagingContracts")
}
