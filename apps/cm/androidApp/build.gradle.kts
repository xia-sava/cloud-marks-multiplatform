import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.activity.compose)
    // アプリの更新でダウンロードした APK を、端末のインストーラへ渡す FileProvider（UpdateFileProvider）。
    implementation(libs.androidx.core)
    implementation(libs.kotlinx.coroutinesAndroid)
}

// バージョンは配布時に -Pcloudmarks.versionCode / -Pcloudmarks.versionName で渡す。
// 渡されないローカルのビルドは既定値を使う。versionCode の検証はルートの `build.gradle.kts` が行う。
val cloudMarksVersionCode = rootProject.extra["cloudMarksVersionCode"] as Int
val cloudMarksVersionName = providers.gradleProperty("cloudmarks.versionName").getOrElse("0.0.0")

// 配布用の署名鍵。設定が無ければ署名なしでビルドする。
val releaseKeystore = providers.environmentVariable("CLOUDMARKS_RELEASE_KEYSTORE").orNull

android {
    namespace = "to.sava.cloudmarksmultiplatform"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "to.sava.cloudmarksmultiplatform"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = cloudMarksVersionCode
        versionName = cloudMarksVersionName
    }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("CLOUDMARKS_RELEASE_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("CLOUDMARKS_RELEASE_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("CLOUDMARKS_RELEASE_KEY_PASSWORD").orNull
            }
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

// FileProvider の宣言と、URI を組み立てる側の値の食い違いを見る。食い違うと、ビルドも lint も通ったまま、端末のインストーラへ
// 渡すところ（FileProvider.getUriForFile）で初めて失敗する。見るのは次の 3 つ。
// - 同じクラス（`android:name`）で宣言した provider が無いこと。provider ごとにクラスを分ける
// - マニフェストの provider の authority の `${applicationId}` に続く部分と、shared の androidMain の `*PROVIDER_SUFFIX` の定数
// - 更新の provider（authority が `UPDATE_PROVIDER_SUFFIX` のもの）が渡せる場所が、キャッシュのディレクトリの下の
//   shared の `UPDATE_DIRECTORY_NAME`（`AppFiles.updateDirectory` の名前）だけであること
val verifyFileProviders = tasks.register("verifyFileProviders") {
    // 実行するときに残るのは File だけにする。スクリプトへの参照を持つと configuration cache に入らない。
    val manifest = layout.projectDirectory.file("src/main/AndroidManifest.xml").asFile
    val xmlResources = layout.projectDirectory.dir("src/main/res/xml").asFile
    val androidSources = layout.projectDirectory.dir("../shared/src/androidMain").asFile
    val sharedSources = layout.projectDirectory.dir("../shared/src/jvmShared").asFile
    inputs.file(manifest)
    inputs.dir(xmlResources)
    inputs.dir(androidSources)
    inputs.dir(sharedSources)

    doLast {
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        fun NodeList.elements(): List<Element> = (0 until length).map { item(it) }.filterIsInstance<Element>()

        // directory の Kotlin のソースにある、名前が namePattern に合う文字列の定数の値
        fun constants(directory: File, namePattern: String): List<String> {
            val definition = Regex("const val $namePattern(?:\\s*:\\s*\\w+)?\\s*=\\s*\"([^\"]+)\"")
            return directory.walk()
                .filter { it.extension == "kt" }
                .flatMap { definition.findAll(it.readText()) }
                .map { it.groupValues[1] }
                .toList()
        }

        val applicationIdPlaceholder = "\${applicationId}"
        val providers = builder.parse(manifest).getElementsByTagName("provider").elements()
        val duplicatedClasses = providers.groupingBy { it.getAttribute("android:name") }.eachCount().filterValues { it > 1 }.keys
        if (duplicatedClasses.isNotEmpty()) {
            throw GradleException("同じクラスで宣言した provider がある: ${duplicatedClasses.sorted()}。provider ごとにクラスを分ける")
        }

        val authorities = providers.map { it.getAttribute("android:authorities").removePrefix(applicationIdPlaceholder) }.toSet()
        val suffixes = constants(androidSources, "\\w*PROVIDER_SUFFIX").toSet()
        if (authorities != suffixes) {
            throw GradleException(
                "provider の authority と、URI を組み立てる定数が食い違っている: " +
                    "マニフェスト=${authorities.sorted()} 定数=${suffixes.sorted()}"
            )
        }

        val updateSuffix = constants(androidSources, "UPDATE_PROVIDER_SUFFIX").singleOrNull()
            ?: throw GradleException("shared の UPDATE_PROVIDER_SUFFIX が 1 つに決まらない")
        val updateProvider = providers.singleOrNull { it.getAttribute("android:authorities") == applicationIdPlaceholder + updateSuffix }
            ?: throw GradleException("authority が $applicationIdPlaceholder$updateSuffix の provider が 1 つに決まらない")
        val pathsReference = updateProvider.getElementsByTagName("meta-data").elements()
            .singleOrNull { it.getAttribute("android:name") == "android.support.FILE_PROVIDER_PATHS" }
            ?.getAttribute("android:resource")
            ?.takeIf { it.startsWith("@xml/") }
            ?: throw GradleException("更新の provider の android.support.FILE_PROVIDER_PATHS が、@xml/ の場所の定義を指していない")
        val paths = builder.parse(File(xmlResources, "${pathsReference.removePrefix("@xml/")}.xml")).documentElement
        val entries = paths.childNodes.elements()
        val updateDirectoryName = constants(sharedSources, "UPDATE_DIRECTORY_NAME").singleOrNull()
            ?: throw GradleException("shared の UPDATE_DIRECTORY_NAME が 1 つに決まらない")
        val expectedPath = "$updateDirectoryName/"
        val onlyUpdateDirectory = paths.tagName == "paths" &&
            entries.singleOrNull()?.let { it.tagName == "cache-path" && it.getAttribute("path") == expectedPath } == true
        if (!onlyUpdateDirectory) {
            throw GradleException(
                "$pathsReference の渡せる場所が、キャッシュのディレクトリの下の $expectedPath だけでない: " +
                    "<${paths.tagName}> の中は ${entries.map { "<${it.tagName} path=\"${it.getAttribute("path")}\">" }}"
            )
        }
    }
}

tasks.named("check") { dependsOn(verifyFileProviders) }

/**
 * ファイルを、APK の assets に入れるディレクトリへ写す。写す元のファイルが無ければ失敗する。
 * 写す先のディレクトリは、前の回のファイルを残さないよう、写す前に空にする。
 */
abstract class CopyApkAssets : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val sourceFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copy() {
        // 写す元が無くても、入力のファイルの集まりは失敗しないので、写す前に確かめる。
        sourceFiles.files.firstOrNull { !it.isFile }?.let { missing ->
            throw GradleException("APK に入れるファイルが無い: $missing")
        }
        val directory = outputDirectory.get().asFile
        directory.deleteRecursively()
        directory.mkdirs()
        sourceFiles.files.forEach { it.copyTo(directory.resolve(it.name)) }
    }
}

/** APK の assets に入れる、このリポジトリのライセンスと、配布物に入る第三者のライブラリの表記。 */
val licenseFiles = listOf("LICENSE", "THIRD-PARTY-NOTICES.md").map { rootProject.file("../../$it") }

// リポジトリのライセンスと第三者のライブラリの表記を、APK の assets の直下に入れる。画面には出さない。
val copyLicenseAssets = tasks.register<CopyApkAssets>("copyLicenseAssets") {
    sourceFiles.from(licenseFiles)
    outputDirectory.set(layout.buildDirectory.dir("generated/license-assets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyLicenseAssets, CopyApkAssets::outputDirectory)
    }
}
