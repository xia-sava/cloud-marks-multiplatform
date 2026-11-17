import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
    implementation(libs.kotlinx.coroutinesAndroid)
}

// バージョンは配布時に -Pcloudmarks.versionCode / -Pcloudmarks.versionName で渡す。
// 渡されないローカルのビルドは既定値を使う。
val cloudMarksVersionCode = providers.gradleProperty("cloudmarks.versionCode").getOrElse("1")
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
        versionCode = cloudMarksVersionCode.toInt()
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
