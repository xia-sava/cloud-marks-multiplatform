import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.WriteProperties
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

/** Desktopを動かすJetBrains Runtimeのバージョン。コンパイルにも同じバージョンのJetBrains Runtimeを使う。 */
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
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(JETBRAINS_RUNTIME_VERSION))
        vendor.set(JvmVendorSpec.JETBRAINS)
    }
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

// バージョンは配布時に -Pcloudmarks.versionName と -Pcloudmarks.versionCode で渡す。渡されないローカルのビルドは既定値を使う。
val cloudMarksVersionName =
    providers.gradleProperty("cloudmarks.versionName").getOrElse(DEFAULT_VERSION_NAME)
/** 更新の新旧の比較に使う、1以上の整数のバージョン。検証はルートの `build.gradle.kts` が行う。 */
val cloudMarksVersionCode: Int = rootProject.extra["cloudMarksVersionCode"] as Int
/** 数字3つの書式のバージョン。[cloudMarksVersionName]がその書式でなければ既定の値。 */
val cloudMarksPackageVersion =
    cloudMarksVersionName.takeIf { PACKAGE_VERSION_PATTERN.matches(it) } ?: DEFAULT_VERSION_NAME

/** バージョンをアプリへ渡すリソースを置くディレクトリ。 */
val versionResourceDirectory = layout.buildDirectory.dir("generated/version")

// バージョン（`versionCode`と`versionName`）をリソースに書く。`run`と配布物の両方のクラスパスに載り、`DesktopVersion`が読む。
val generateVersionProperties = tasks.register<WriteProperties>("generateVersionProperties") {
    destinationFile = versionResourceDirectory.map { it.file("cloudmarks-version.properties") }
    property("versionCode", cloudMarksVersionCode)
    property("versionName", cloudMarksVersionName)
}

sourceSets.main {
    resources.srcDir(files(versionResourceDirectory).builtBy(generateVersionProperties))
}

compose.desktop {
    application {
        mainClass = "to.sava.cloudmarksmultiplatform.MainKt"
        // 認証情報の暗号化（DPAPI）とレジストリの読み取り（ブラウザの一覧）をFFMで呼ぶ。`run`と配布物の両方に適用される。
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
            // `:desktopApp:suggestRuntimeModules`が挙げるもの。依存するライブラリが使い、無いと使った場面で
            // NoClassDefFoundErrorになる。依存を変えたら、このタスクの結果と見比べる。
            modules("java.instrument", "java.management", "jdk.unsupported")
            // Windowsのインストーラはjpackageではビルドしない。`createDistributable`の出力を
            // `packageInnoSetup`がInno Setupへ渡す。
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)
            packageName = cloudMarksPackageName
            // バージョンを指定しないビルドの名前は書式に合わず、インストーラをビルドしない場面でも
            // 設定が拒否されるため、その場合は既定へフォールバックする。
            packageVersion = cloudMarksPackageVersion
            windows {
                iconFile.set(project.file("icons/cloudmarks.ico"))
            }
        }
    }
}

/**
 * 配布版と開発版の、Native Messagingのホストの名前と拡張のIDの一覧（`docs/native-messaging.md`の「配布版と開発版」）。
 * 拡張のテストと`:shared`のテストも読む。
 */
private val NATIVE_MESSAGING_IDENTITIES = rootProject.file("../../contracts/native-messaging/identities.json")

/** [NATIVE_MESSAGING_IDENTITIES]の、開発版の組の名前。 */
private val DEV_IDENTITY = "dev"

/** Native Messagingのホストのmainのクラス。 */
private val NATIVE_MESSAGING_HOST_MAIN_CLASS = "to.sava.cloudmarksmultiplatform.HostMainKt"

/**
 * Native Messagingのホストを、ChromeとFirefoxに登録するJSON。
 *
 * タスクの実行のときに呼ぶ。スクリプトのほかの値を参照しないので、構成のキャッシュに載せられる。
 */
private object NativeMessagingHostManifests {
    /** ホストを登録するJSONの`description`。 */
    private const val DESCRIPTION = "Cloud Marks native messaging host"

    /**
     * [identities]（`identities.json`）の[variant]（`distributed`か`dev`）の組で、ホストを登録するJSONを
     * Chrome用の[chromeManifest]とFirefox用の[firefoxManifest]に書く。JSONの`path`（ホストを起動するファイル）は[hostPath]。
     * 各JSONは、その組の拡張だけを受け付ける（`allowed_origins`・`allowed_extensions`にその組のIDを1つ）。
     */
    fun write(identities: File, variant: String, hostPath: String, chromeManifest: File, firefoxManifest: File) {
        fun field(name: String): String = identityField(identities, variant, name)

        val common = linkedMapOf<String, Any>(
            "name" to field("hostName"),
            "description" to DESCRIPTION,
            "path" to hostPath,
            "type" to "stdio",
        )
        val chromeOrigin = "chrome-extension://${field("chromeExtensionId")}/"
        chromeManifest.writeText(JsonOutput.toJson(common + ("allowed_origins" to listOf(chromeOrigin))) + "\n")
        firefoxManifest.writeText(JsonOutput.toJson(common + ("allowed_extensions" to listOf(field("geckoId")))) + "\n")
    }

    /** [identities]（`identities.json`）の[variant]の組のホストの名前。 */
    fun hostName(identities: File, variant: String): String = identityField(identities, variant, "hostName")

    private fun identityField(identities: File, variant: String, name: String): String {
        val identity = (JsonSlurper().parse(identities) as? Map<*, *>)?.get(variant) as? Map<*, *>
            ?: throw GradleException("${identities.name} に $variant の組が無い")
        return identity[name] as? String ?: throw GradleException("${identities.name} の $variant に $name が無い")
    }
}

/**
 * ホストを起動するJVMの引数。
 *
 * - ホストは1つのメッセージを処理して終わるため、JITのコンパイルをC1までにとどめて起動を軽くする
 * - JVMのログ（unified logging）は、warningとerrorを既定で標準出力へ書く。標準出力は応答だけに使うので、
 *   既定の出力を止め、warning以上を標準エラーへ書く
 */
private val HOST_JVM_ARGS = listOf("-XX:TieredStopAtLevel=1", "-Xlog:disable", "-Xlog:all=warning:stderr")

/** `run`と同じJDK。JetBrains Runtimeがあればそれを、無ければ同じバージョンの別のJDKを使う。 */
private val desktopRuntime: Provider<JavaLauncher> =
    jetBrainsRuntime.fold(onSuccess = { launcher -> provider { launcher } }, onFailure = { sameVersionRuntime })

// 開発版のNative Messagingのホストを書き出す。ホストを起動するバッチと、ChromeとFirefoxにホストを登録するJSON。
// ホストの名前と受け付ける拡張は、`identities.json`の開発版の組。バッチは`jpackage.app-path`を渡さないので、
// ホストは開発版として動き、開発版のデータの場所を使う。
// バッチは、ビルドしたクラスとGradleのキャッシュにある依存をそのまま使う。レジストリには登録しない。
// クラスパスは、cmd.exeの1行の上限（8191文字）を超えるので、バッチに書かず、javaの引数のファイル（`@ファイル`）に書く。
tasks.register("writeDevNativeMessagingHost") {
    group = "application"
    description = "Writes a launcher and manifests for a development native messaging host. Does not register them."

    val classpath = sourceSets.main.get().runtimeClasspath
    val javaExecutable = desktopRuntime.map { it.executablePath.asFile.absolutePath }
    val outputDirectory = layout.buildDirectory.dir("native-messaging-host")
    val identities = NATIVE_MESSAGING_IDENTITIES
    val variant = DEV_IDENTITY
    val mainClass = NATIVE_MESSAGING_HOST_MAIN_CLASS
    val hostJvmArgs = HOST_JVM_ARGS
    val enableNativeAccess = ENABLE_NATIVE_ACCESS

    inputs.files(classpath).withPropertyName("classpath")
    inputs.property("javaExecutable", javaExecutable)
    inputs.file(identities).withPathSensitivity(PathSensitivity.NONE).withPropertyName("identities")
    outputs.dir(outputDirectory)

    doLast {
        val directory = outputDirectory.get().asFile
        directory.mkdirs()
        // javaは引数のファイルを、ANSIのコードページ（`native.encoding`）で読む
        val nativeCharset = System.getProperty("native.encoding")?.let { charset(it) } ?: Charsets.UTF_8

        // 引数のファイルの中では、引用符で囲んだ`\`がエスケープになるため、パスの区切りを`/`にする
        val argumentFileName = "cloud-marks-host.args"
        val classpathText = classpath.files.joinToString(File.pathSeparator) { it.absolutePath.replace('\\', '/') }
        directory.resolve(argumentFileName).writeText("-cp \"$classpathText\"\r\n", nativeCharset)

        // 引数のファイルは、バッチと同じディレクトリ（`%~dp0`）から探す。mainは引数を使わないので、バッチの引数は渡さない。
        // バッチの中の`%`は、`%%`と書かないと環境変数の参照になる
        val launcher = directory.resolve("cloud-marks-host.bat")
        val command = listOf("\"${javaExecutable.get().replace("%", "%%")}\"", enableNativeAccess) + hostJvmArgs +
            listOf("@\"%~dp0$argumentFileName\"", mainClass)
        // cmd.exeはバッチを、コンソールのコードページ（既定はOEMのコードページ）で読む。ASCIIでないことがあるのはJDKのパスだけで、
        // OEMとANSIのコードページが同じ環境なら、引数のファイルと同じ文字コードで書けば読める
        launcher.writeText("@echo off\r\n${command.joinToString(" ")}\r\n", nativeCharset)

        NativeMessagingHostManifests.write(
            identities = identities,
            variant = variant,
            hostPath = launcher.absolutePath,
            chromeManifest = directory.resolve("chrome.json"),
            firefoxManifest = directory.resolve("firefox.json"),
        )
    }
}

/** [NATIVE_MESSAGING_IDENTITIES]の、配布版の組の名前。 */
private val DISTRIBUTED_IDENTITY = "distributed"

/** 配布物に入れるホストの起動口の名前（拡張子を除く）。ランチャーのexeと設定ファイルの名前になる。 */
private val HOST_LAUNCHER_NAME = "cloud-marks-host"

/** 配布物の`createDistributable`の出力。 */
private val distributableDirectory = layout.buildDirectory.dir("compose/binaries/main/app/$cloudMarksPackageName")

/**
 * ホストの起動口の設定ファイル（`cloud-marks-host.cfg`）を、アプリの設定ファイルから作る。
 *
 * タスクの実行のときに呼ぶ。スクリプトのほかの値を参照しないので、構成のキャッシュに載せられる。
 */
private object HostLauncherConfig {
    private const val APPLICATION_SECTION = "[Application]"
    private const val JAVA_OPTIONS_SECTION = "[JavaOptions]"
    private const val MAIN_CLASS_KEY = "app.mainclass="
    private const val JAVA_OPTION_KEY = "java-options="

    /** ホストが使わない、アプリの画面に係る起動引数の接頭辞。 */
    private val APP_ONLY_OPTION_PREFIXES = listOf("-Dcompose.", "-Dskiko.")

    /** cfgの改行。jpackageはCRLFで書く。 */
    private const val LINE_SEPARATOR = "\r\n"

    /**
     * アプリの設定ファイル[source]を写して[destination]に書く。ただし、`[Application]`の`app.mainclass`を[mainClass]にし、
     * `[JavaOptions]`からホストに要らない画面の起動引数を除いて[hostJvmArgs]を足す。クラスパスなどそれ以外の行は写すだけ。
     */
    fun write(source: File, destination: File, mainClass: String, hostJvmArgs: List<String>) {
        val output = mutableListOf<String>()
        var section = ""
        var mainClassReplaced = false
        var javaOptionsSeen = false
        val keptJavaOptions = mutableSetOf<String>()

        fun appendHostJvmArgs() {
            hostJvmArgs.filterNot { it in keptJavaOptions }.forEach { output += "$JAVA_OPTION_KEY$it" }
        }

        for (line in source.readLines(Charsets.UTF_8)) {
            if (line.startsWith("[")) {
                if (section == JAVA_OPTIONS_SECTION) appendHostJvmArgs()
                section = line.trim()
                if (section == JAVA_OPTIONS_SECTION) javaOptionsSeen = true
                output += line
                continue
            }
            when {
                section == APPLICATION_SECTION && line.startsWith(MAIN_CLASS_KEY) -> {
                    output += "$MAIN_CLASS_KEY$mainClass"
                    mainClassReplaced = true
                }
                section == JAVA_OPTIONS_SECTION && line.startsWith(JAVA_OPTION_KEY) -> {
                    val option = line.removePrefix(JAVA_OPTION_KEY)
                    if (APP_ONLY_OPTION_PREFIXES.none { option.startsWith(it) }) {
                        output += line
                        keptJavaOptions += option
                    }
                }
                else -> output += line
            }
        }
        if (!mainClassReplaced) throw GradleException("${source.name} の $APPLICATION_SECTION に ${MAIN_CLASS_KEY.removeSuffix("=")} が無い")
        if (section == JAVA_OPTIONS_SECTION) {
            appendHostJvmArgs()
        } else if (!javaOptionsSeen) {
            output += JAVA_OPTIONS_SECTION
            appendHostJvmArgs()
        }

        destination.parentFile.mkdirs()
        destination.writeText(output.joinToString(LINE_SEPARATOR, postfix = LINE_SEPARATOR), Charsets.UTF_8)
    }
}

// `createDistributable`が作るアプリの設定ファイルから、ホストの起動口の設定ファイルを作る。
// ホストの起動口は、ランチャーのexe（`CloudMarks.exe`）の複製で、同じ名前の設定ファイルを読む。
// 設定ファイルは、mainのクラスをホストのものにし、ホストのJVMの引数を足す。クラスパスの行は写す。
val generateHostLauncher = tasks.register("generateHostLauncher") {
    group = "compose desktop"
    description = "Writes the launcher config of the native messaging host from the distributable's app config."
    dependsOn("createDistributable")

    val appConfig = distributableDirectory.map { it.file("app/$cloudMarksPackageName.cfg") }
    val hostConfig = layout.buildDirectory.file("host-launcher/$HOST_LAUNCHER_NAME.cfg")
    val mainClass = NATIVE_MESSAGING_HOST_MAIN_CLASS
    val hostJvmArgs = listOf(ENABLE_NATIVE_ACCESS) + HOST_JVM_ARGS

    inputs.file(appConfig).withPropertyName("appConfig")
    inputs.property("mainClass", mainClass)
    inputs.property("hostJvmArgs", hostJvmArgs)
    outputs.file(hostConfig)

    doLast {
        HostLauncherConfig.write(appConfig.get().asFile, hostConfig.get().asFile, mainClass, hostJvmArgs)
    }
}

// 配布版のNative Messagingのホストを登録するJSONを書く。ホストの起動口は、JSONと同じ場所にあるので`path`は相対にする。
val writeDistributedNativeMessagingManifests = tasks.register("writeDistributedNativeMessagingManifests") {
    group = "compose desktop"
    description = "Writes the manifests that register the distributed native messaging host with Chrome and Firefox."

    val outputDirectory = layout.buildDirectory.dir("host-manifests")
    val identities = NATIVE_MESSAGING_IDENTITIES
    val variant = DISTRIBUTED_IDENTITY
    val hostPath = "$HOST_LAUNCHER_NAME.exe"
    val chromeManifestName = "$HOST_LAUNCHER_NAME.chrome.json"
    val firefoxManifestName = "$HOST_LAUNCHER_NAME.firefox.json"

    inputs.file(identities).withPathSensitivity(PathSensitivity.NONE).withPropertyName("identities")
    inputs.property("hostPath", hostPath)
    outputs.dir(outputDirectory)

    doLast {
        val directory = outputDirectory.get().asFile
        directory.mkdirs()
        NativeMessagingHostManifests.write(
            identities = identities,
            variant = variant,
            hostPath = hostPath,
            chromeManifest = directory.resolve(chromeManifestName),
            firefoxManifest = directory.resolve(firefoxManifestName),
        )
    }
}

/** `stageInstallerFiles`の出力。インストーラに入れるファイルを揃える場所。 */
private val stagedInstallerDirectory = layout.buildDirectory.dir("compose/binaries/main/staged/$cloudMarksPackageName")

/** インストーラに入れる、配布版のChromeの拡張。`apps/extension`の`npm run build`の出力。開発版（`dist/dev`）は入れない。 */
private val chromeExtensionDirectory = rootProject.file("../extension/dist/chrome")

private val MISSING_CHROME_EXTENSION =
    "配布版のChromeの拡張（apps/extension/dist/chrome/manifest.json）が無い。" +
        "先に apps/extension で `npm run build` を実行する。"

/**
 * インストーラに入れる、署名済みのFirefoxの拡張（.xpi）。`-Pcloudmarks.firefoxExtension=<.xpiのパス>`で渡したときだけ入れる。
 * 相対パスは`apps/cm`から解決する。
 */
private val firefoxExtensionFile: File? =
    providers.gradleProperty("cloudmarks.firefoxExtension").orNull?.let { rootProject.file(it) }

/** インストールしたフォルダの中の、Firefoxの拡張のファイルの名前。 */
private val FIREFOX_EXTENSION_FILE_NAME = "cloudmarks-firefox.xpi"

/** インストールしたフォルダの直下に置く、このリポジトリのライセンスと、配布物に入る第三者のライブラリの表記。 */
private val licenseFiles = listOf("LICENSE", "THIRD-PARTY-NOTICES.md").map { rootProject.file("../../$it") }

// インストーラに入れるファイルを揃える。`createDistributable`の出力に、ホストの起動口（ランチャーのexeの複製と
// その設定ファイル）と、ホストを登録するJSONを足す。ホストの起動口とJSONはexeと同じ場所に置く。
// 拡張は`extension\chrome\`（配布版のChromeの拡張）と`extension\firefox\`（渡されたときの署名済みの.xpi）に置く。
// リポジトリのライセンスと第三者のライブラリの表記は、exeと同じ場所に置く。
// Gradleはnpmを呼ばない。Chromeの拡張は先にビルドしておく。
val stageInstallerFiles = tasks.register<Sync>("stageInstallerFiles") {
    group = "compose desktop"
    description = "Stages the installer's files: the distributable, the native messaging host launcher and manifests, " +
        "the browser extensions, and the license and third-party notices."
    dependsOn("createDistributable")

    val launcherName = "$cloudMarksPackageName.exe"
    val hostLauncherName = "$HOST_LAUNCHER_NAME.exe"
    val chromeExtension = chromeExtensionDirectory
    val missingChromeExtension = MISSING_CHROME_EXTENSION
    val firefoxExtension = firefoxExtensionFile
    val firefoxExtensionName = FIREFOX_EXTENSION_FILE_NAME
    val licenses = licenseFiles

    into(stagedInstallerDirectory)
    from(distributableDirectory)
    from(distributableDirectory.map { it.file(launcherName) }) {
        rename { hostLauncherName }
    }
    from(generateHostLauncher) {
        into("app")
    }
    from(writeDistributedNativeMessagingManifests)
    from(chromeExtension) {
        into("extension/chrome")
    }
    firefoxExtension?.let { xpi ->
        from(xpi) {
            into("extension/firefox")
            rename { firefoxExtensionName }
        }
    }
    from(licenses)

    // 写す元が無くてもSyncは失敗しないので、写す前に確かめる。
    doFirst {
        if (!chromeExtension.resolve("manifest.json").isFile) throw GradleException(missingChromeExtension)
        if (firefoxExtension != null && !firefoxExtension.isFile) {
            throw GradleException("-Pcloudmarks.firefoxExtension に渡したファイルが無い: $firefoxExtension")
        }
        licenses.firstOrNull { !it.isFile }?.let { missing ->
            throw GradleException("インストーラに入れるファイルが無い: $missing")
        }
    }
}

// WindowsのインストーラをInno Setupでビルドする。jpackageのMSIは更新とアンインストールのたびに
// インストール先を中身ごと消す。入れるファイルは`stageInstallerFiles`の出力。インストーラが
// ChromeとFirefoxに登録するホストの名前は、`identities.json`の配布版の組から、タスクの実行のときに読む。
tasks.register<Exec>("packageInnoSetup") {
    group = "compose desktop"
    description = "Builds the Windows installer with Inno Setup. Runs on Windows only."
    dependsOn(stageInstallerFiles)

    val script = layout.projectDirectory.file("installer/cloudmarks.iss")
    val staged = stagedInstallerDirectory
    val installerDir = layout.buildDirectory.dir("compose/binaries/main/inno")
    val packageName = cloudMarksPackageName
    val versionName = cloudMarksVersionName
    val fileVersion = cloudMarksPackageVersion
    val identities = NATIVE_MESSAGING_IDENTITIES
    val variant = DISTRIBUTED_IDENTITY
    val missingCompiler = MISSING_INNO_SETUP
    val byteOrderMark = UTF8_BYTE_ORDER_MARK
    val missingByteOrderMark = MISSING_BYTE_ORDER_MARK
    val compiler = providers.provider { findInnoSetupCompiler()?.absolutePath }

    inputs.file(script)
    inputs.dir(staged)
    inputs.file(identities).withPathSensitivity(PathSensitivity.NONE).withPropertyName("identities")
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
            "/DSourceDir=${staged.get().asFile.absolutePath}",
            "/DHostName=${NativeMessagingHostManifests.hostName(identities, variant)}",
            "/DOutputDir=${installerDir.get().asFile.absolutePath}",
            script.asFile.absolutePath,
        )
    }
}
