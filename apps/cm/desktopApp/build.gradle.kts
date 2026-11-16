import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

/** Desktopを動かすJetBrains Runtimeのバージョン。コンパイルに使うJDKのバージョンも同じにする。 */
private val JETBRAINS_RUNTIME_VERSION = libs.versions.desktop.jdk.get().toInt()

/**
 * FFM（`java.lang.foreign`）でネイティブの関数を呼ぶことを許す起動引数。
 * Java 24以降は、許していないコードが呼ぶと警告が出る。
 */
private val ENABLE_NATIVE_ACCESS = "--enable-native-access=ALL-UNNAMED"

private val MISSING_JETBRAINS_RUNTIME =
    "JetBrains Runtime $JETBRAINS_RUNTIME_VERSION が見当たらないため、同じバージョンの別のJDKで動かす。" +
        "Windowsでマウスを載せただけのアクティブ化（X-mouse）は動作しない。"

private val REQUIRED_JETBRAINS_RUNTIME_MISSING =
    "JetBrains Runtime $JETBRAINS_RUNTIME_VERSION が見当たらない。" +
        "同じバージョンの別のJDKでパッケージングした配布物は、" +
        "Windowsでマウスを載せただけのアクティブ化（X-mouse）が動作しない。"

/** ローカルにあるJetBrains Runtime。無ければ失敗として返り、[sameVersionRuntime]へ委ねる。 */
private val jetBrainsRuntime = runCatching {
    extensions.getByType<JavaToolchainService>().launcherFor {
        languageVersion.set(JavaLanguageVersion.of(JETBRAINS_RUNTIME_VERSION))
        vendor.set(JvmVendorSpec.JETBRAINS)
    }.get()
}

/**
 * JetBrains Runtimeが無いときに動かす、同じバージョンのJDK（ベンダーを問わない）。
 *
 * Composeの既定はGradleのデーモンのJDKで、バージョンが低いとコンパイルしたクラスを読み込めない。
 */
private val sameVersionRuntime = extensions.getByType<JavaToolchainService>().launcherFor {
    languageVersion.set(JavaLanguageVersion.of(JETBRAINS_RUNTIME_VERSION))
}

/**
 * 同じバージョンの別のJDKへフォールバックすることを許さないか。
 *
 * 配布物をビルドするときに有効にする。ログへ出すだけでは、動作しない部分のある配布物がビルドに成功したまま作られる。
 */
private val requiresJetBrainsRuntime =
    providers.gradleProperty("cloudmarks.requireJetBrainsRuntime").isPresent

kotlin {
    jvmToolchain(JETBRAINS_RUNTIME_VERSION)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)
}

/** 配布物の名前。インストール先のディレクトリとランチャーのexeの名前になる。表示名は`installer/cloudmarks.iss`が持つ。 */
val cloudMarksPackageName = "CloudMarks"

/** バージョンを渡されなかったときの文字列。 */
private val DEFAULT_VERSION_NAME = "0.0.0"

/** インストーラが受け付けるバージョンの書式。jpackageは配布形式ごとにこれを検証する。 */
private val PACKAGE_VERSION_PATTERN = Regex("""\d+\.\d+\.\d+""")

/** Inno Setupのコンパイラの名前。 */
private val INNO_SETUP_COMPILER_NAME = "ISCC.exe"

/** Inno Setupをインストールしたときのディレクトリの名前。 */
private val INNO_SETUP_DIRECTORY_NAME = "Inno Setup 6"

private val MISSING_INNO_SETUP =
    "$INNO_SETUP_COMPILER_NAME が見当たらない。`winget install JRSoftware.InnoSetup` でインストールするか、" +
        "$INNO_SETUP_COMPILER_NAME のあるディレクトリをPATHへ加える。"

/** UTF-8のバイトオーダーマーク。 */
private val UTF8_BYTE_ORDER_MARK = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

private val MISSING_BYTE_ORDER_MARK =
    "installer/cloudmarks.iss の先頭にUTF-8のBOMが無い。BOM付きのUTF-8で保存し直す。"

/**
 * Inno Setupのコンパイラ。PATH、ユーザーごとのインストール先、全ユーザーのインストール先の順に探す。
 * 見つからなければnull。
 */
private fun findInnoSetupCompiler(): File? {
    val onPath = System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }
    val installed = listOfNotNull(
        System.getenv("LOCALAPPDATA")?.let { "$it/Programs/$INNO_SETUP_DIRECTORY_NAME" },
        System.getenv("ProgramFiles(x86)")?.let { "$it/$INNO_SETUP_DIRECTORY_NAME" },
    )
    return (onPath + installed).map { File(it, INNO_SETUP_COMPILER_NAME) }.firstOrNull { it.isFile }
}

// バージョンは配布時に -Pcloudmarks.versionName で渡す。渡されないローカルのビルドは既定値を使う。
val cloudMarksVersionName =
    providers.gradleProperty("cloudmarks.versionName").getOrElse(DEFAULT_VERSION_NAME)
/** 数字3つの書式のバージョン。[cloudMarksVersionName]がその書式でなければ既定の値。 */
val cloudMarksPackageVersion =
    cloudMarksVersionName.takeIf { PACKAGE_VERSION_PATTERN.matches(it) } ?: DEFAULT_VERSION_NAME

compose.desktop {
    application {
        mainClass = "to.sava.cloudmarksmultiplatform.MainKt"
        // 認証情報の暗号化（DPAPI）をFFMで呼ぶ。`run`と配布物の両方に適用される。
        jvmArgs += ENABLE_NATIVE_ACCESS

        // Windowsでマウスを載せただけのアクティブ化を働かせるには、JetBrains Runtimeで動かす必要がある。
        // 素のOpenJDKはこれに対応していない。ローカルに無ければ同じバージョンの別のJDKで動かして
        // 何が働かなくなるかをログへ出し、配布物をビルドするときは失敗させる。
        jetBrainsRuntime
            .onSuccess { javaHome = it.metadata.installationPath.asFile.path }
            .onFailure {
                if (requiresJetBrainsRuntime) throw GradleException(REQUIRED_JETBRAINS_RUNTIME_MISSING)
                logger.lifecycle(MISSING_JETBRAINS_RUNTIME)
                javaHome = sameVersionRuntime.get().metadata.installationPath.asFile.path
            }

        nativeDistributions {
            // jlinkの自動解決では含まれないが、S3へのHTTPS接続に要る。
            modules("jdk.crypto.ec")
            // Windowsのインストーラはjpackageではビルドしない。`createDistributable`の出力を
            // `packageInnoSetup`がInno Setupへ渡す。
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)
            packageName = cloudMarksPackageName
            // バージョンを指定しないビルドの名前は書式に合わず、インストーラをビルドしない場面でも
            // 設定が拒否されるため、その場合は既定へフォールバックする。
            packageVersion = cloudMarksPackageVersion
        }
    }
}

// WindowsのインストーラをInno Setupでビルドする。jpackageのMSIは更新とアンインストールのたびに
// インストール先を中身ごと消す。
tasks.register<Exec>("packageInnoSetup") {
    group = "compose desktop"
    description = "Builds the Windows installer with Inno Setup. Runs on Windows only."
    dependsOn("createDistributable")

    val script = layout.projectDirectory.file("installer/cloudmarks.iss")
    val distributable = layout.buildDirectory.dir("compose/binaries/main/app/$cloudMarksPackageName")
    val installerDir = layout.buildDirectory.dir("compose/binaries/main/inno")
    val packageName = cloudMarksPackageName
    val versionName = cloudMarksVersionName
    val fileVersion = cloudMarksPackageVersion
    val missingCompiler = MISSING_INNO_SETUP
    val byteOrderMark = UTF8_BYTE_ORDER_MARK
    val missingByteOrderMark = MISSING_BYTE_ORDER_MARK
    val compiler = providers.provider { findInnoSetupCompiler()?.absolutePath }

    inputs.file(script)
    inputs.dir(distributable)
    inputs.property("versionName", versionName)
    inputs.property("fileVersion", fileVersion)
    outputs.dir(installerDir)

    doFirst {
        // BOMが無くてもコンパイルは通り、ビルドに成功したまま日本語の化けたインストーラができる。
        val head = script.asFile.inputStream().use { it.readNBytes(byteOrderMark.size) }
        if (!head.contentEquals(byteOrderMark)) throw GradleException(missingByteOrderMark)

        executable = compiler.orNull ?: throw GradleException(missingCompiler)
        args(
            "/Qp",
            "/DAppName=$packageName",
            "/DAppVersion=$versionName",
            "/DFileVersion=$fileVersion",
            "/DSourceDir=${distributable.get().asFile.absolutePath}",
            "/DOutputDir=${installerDir.get().asFile.absolutePath}",
            script.asFile.absolutePath,
        )
    }
}
