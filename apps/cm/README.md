# Cloud Marks（cm）

Kotlin Multiplatform / Compose Multiplatform で、Android と Windows Desktop を1つのコードからビルドする。

## モジュール

| モジュール | 受け持ち |
|---|---|
| `:shared` | 両方で使うコード。ターゲットは `jvm()` と `android {}`。画面の入口は `commonMain` の `App()`。`jvmShared` は Android とデスクトップで共有する JVM の実装を置く中間ソースセットで、S3 へのアクセスを置く |
| `:androidApp` | Android アプリ（`MainActivity` から `App()` を呼ぶ） |
| `:desktopApp` | Windows デスクトップアプリ（`main.kt` のウィンドウから `App()` を呼ぶ）。配布物のビルドもここ |

## 開く

IntelliJ IDEA で `apps/cm` を開く。

## ビルドと起動

デスクトップ（`shared` の `jvm()` と `:desktopApp`）は JDK 25 でコンパイルし、JetBrains Runtime 25 で動かす。
版は `gradle/libs.versions.toml` の `desktop-jdk` が持つ。Android の側は JDK 21 のデーモンのままビルドする。

- Android: `./gradlew :androidApp:assembleDebug` でデバッグ版の APK をビルドする
- Desktop: `./gradlew :desktopApp:run` で起動する

## テスト

エントリポイントは `./gradlew check`。`:shared:jvmTest` と `:shared:testAndroidHostTest` の両方を含む。
絞って走らせるなら `./gradlew :shared:jvmTest :shared:testAndroidHostTest` と名指しする。

**`./gradlew test` は `:shared` のテストを1つも走らせない。** `:shared` に `test` という名前のタスクが
無く、実行されるのは `:androidApp:test` と `:desktopApp:test` だけ。**緑に見えて何も確かめていない。**

`:shared:jvmTest` は `contracts/storage` と `contracts/native-messaging` を入力として宣言している。

## 配布物のビルド

Windows のインストーラは Inno Setup でビルドする（`winget install JRSoftware.InnoSetup`）。

```
./gradlew :desktopApp:packageInnoSetup -Pcloudmarks.versionName=1.2.3 -Pcloudmarks.requireJetBrainsRuntime
```

- バージョンは `-Pcloudmarks.versionName`（数字3つの書式）で渡す。Android はこれに加えて `-Pcloudmarks.versionCode` を受け取る。渡さない手元のビルドは `0.0.0` と `1`。
- `-Pcloudmarks.requireJetBrainsRuntime` を付けると、JetBrains Runtime 25 が無いときに失敗する。付けなければ同じ版の別の JDK で動かす。
- 出力は `desktopApp/build/compose/binaries/main/inno/cloudmarks-setup.exe`。

Android の配布用の署名には、環境変数 `CLOUDMARKS_RELEASE_KEYSTORE`・`CLOUDMARKS_RELEASE_KEYSTORE_PASSWORD`・
`CLOUDMARKS_RELEASE_KEY_ALIAS`・`CLOUDMARKS_RELEASE_KEY_PASSWORD` を使う。`CLOUDMARKS_RELEASE_KEYSTORE` が無ければ署名なしで組む。
