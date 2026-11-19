# Cloud Marks（cm）

Kotlin Multiplatform / Compose Multiplatform で、Android と Windows Desktop を1つのコードからビルドする。

## モジュール

| モジュール | 受け持ち |
|---|---|
| `:shared` | 両方で使うコード。ターゲットは `jvm()` と `android {}`。`jvmShared` は Android とデスクトップで共有する JVM の実装を置く中間ソースセットで、S3 へのアクセスと、画面（エントリポイントは `CloudMarksApp()`）とその状態、状態を持つコントローラの組み立て（`createCloudMarksController()`）を置く |
| `:androidApp` | Android アプリ（`CloudMarksApplication` で、ファイルの場所・暗号化の仕組み・時計・ブラウザで開く仕組みなどを `createCloudMarksController()` に渡して、プロセスに 1 つのコントローラを組み立て、`MainActivity` の `CloudMarksApp()` に渡す。画面を閉じても、コントローラは閉じない） |
| `:desktopApp` | Windows デスクトップアプリ（`main.kt` で、ファイルの場所・暗号化の仕組み・時計・古いファイルの削除を行うこと・ほかのプロセスが S3 へ書いた知らせ・ブラウザで開く仕組みを `createCloudMarksController()` に渡してコントローラを組み立て、ウィンドウの `CloudMarksApp()` に渡す。ウィンドウを閉じたらコントローラを閉じる）。Native Messaging のホスト（`HostMain.kt`）と、配布物のビルドもここ |

## 開く

IntelliJ IDEA で `apps/cm` を開く。

## ビルドと起動

デスクトップ（`shared` の `jvm()` と `:desktopApp`）は JDK 25 でコンパイルし、JetBrains Runtime 25 で動かす。
バージョンは `gradle/libs.versions.toml` の `desktop-jdk` が持つ。Android の側は JDK 21 のデーモンのままビルドする。

- Android: `./gradlew :androidApp:assembleDebug` でデバッグ版の APK をビルドする
- Desktop: `./gradlew :desktopApp:run` で起動する

## 配布版と開発版

デスクトップのアプリとホストは、配布物のランチャーから起動したときだけ配布版になる。IDEA から直に起動したとき、
`:desktopApp:run`、`writeDevNativeMessagingHost` が書き出したホストは開発版になる。見分けるのは `shared` の `AppPath`。
開発版はデータの場所やホストの名前が配布版と違うので、同じ PC に並べて使える。

`createDistributable` が作る配布物と `:desktopApp:runDistributable` も、配布物のランチャーから起動するので配布版として動き、
`%LOCALAPPDATA%\CloudMarks` を使う。

| | 配布版 | 開発版 |
|---|---|---|
| データの場所 | `%LOCALAPPDATA%\CloudMarks` | `%LOCALAPPDATA%\CloudMarks-dev` |
| ウィンドウのタイトル | `Cloud Marks 1.2.3` | `Cloud Marks 1.2.3 (dev-<ビルドの時刻>)` |
| Native Messaging のホストの名前 | `to.sava.cloudmarksmultiplatform` | `to.sava.cloudmarksmultiplatform.dev` |
| 呼び出しの URL のホスト | `cmm.invalid` | `cmm-dev.invalid` |

- ホストの名前と拡張の ID は `contracts/native-messaging/identities.json` にある（`docs/native-messaging.md` の「配布版と開発版」）
- バージョンは `-Pcloudmarks.versionName` で渡したもの（渡さなければ `0.0.0`）。ビルドの時刻は `yyyyMMdd_HHmmss` の形で、
  `:desktopApp` と `:shared` のクラスを読み込んだ場所の更新時刻のうち新しいほう。jar ならその更新時刻、ディレクトリなら
  その下のクラスファイルの更新時刻のうち最も新しいもの

### 開発版のホスト

`./gradlew :desktopApp:writeDevNativeMessagingHost` が、開発版のホストを `desktopApp/build/native-messaging-host/` に書き出す。
書き出すのは、ホストを起動するバッチ `cloud-marks-host.bat` と、ホストを登録する JSON の `chrome.json`（Chrome 用）と
`firefox.json`（Firefox 用）。JSON は開発版の拡張だけを受け付ける。

レジストリには登録しないので、次のキーの既定の値に JSON のパスを書く。

- Chrome: `HKCU\Software\Google\Chrome\NativeMessagingHosts\to.sava.cloudmarksmultiplatform.dev` に `chrome.json` のパス
- Firefox: `HKCU\Software\Mozilla\NativeMessagingHosts\to.sava.cloudmarksmultiplatform.dev` に `firefox.json` のパス

ブラウザには開発版の拡張（`apps/extension` の `dist/dev/chrome` と `dist/dev/firefox`）を読み込む。

## テスト

エントリポイントは `./gradlew check`。`:shared:jvmTest` と `:shared:testAndroidHostTest` の両方を含む。
絞って走らせるなら `./gradlew :shared:jvmTest :shared:testAndroidHostTest` と名指しする。

**`./gradlew test` は `:shared` のテストを1つも走らせない。** `:shared` に `test` という名前のタスクが
無く、実行されるのは `:androidApp:test` と `:desktopApp:test` だけ。**緑に見えて何も確かめていない。**

`:shared:jvmTest` は `contracts/storage` と `contracts/native-messaging` を入力として宣言している。

## 配布物のビルド

Windows のインストーラは Inno Setup でビルドする（`winget install JRSoftware.InnoSetup`）。
インストーラには拡張も入れるので、先に `apps/extension` で `npm run build` を実行しておく（ルートの `just cm-installer` は、
拡張を作ってからインストーラを組む）。

```
./gradlew :desktopApp:packageInnoSetup -Pcloudmarks.versionName=1.2.3 -Pcloudmarks.requireJetBrainsRuntime
```

- バージョンは `-Pcloudmarks.versionName` で渡す。リリースでは数字3つの書式（`1.2.3`）、`release.yml` を手動で実行した回は
  `dev-<コミットの短い ID>` を渡す。数字3つの書式でないときは、インストーラのファイルのバージョン（`FileVersion`）と jpackage の
  `packageVersion` を `0.0.0` にし、アプリが画面に出すバージョンとインストーラの `AppVersion` は渡した文字列のままにする。
  デスクトップと Android は、これに加えて更新の新旧の比較に使う
  `-Pcloudmarks.versionCode`（1 以上の整数）を受け取る。渡さないローカルのビルドでは、それぞれ `0.0.0` と `1` になる。
  `versionCode` が 1 以上の整数でなければ、どちらのビルドも失敗する。
- `-Pcloudmarks.requireJetBrainsRuntime` を付けると、JetBrains Runtime 25 が無いときに失敗する。付けなければ同じバージョンの別の JDK で動かす。
- 出力は `desktopApp/build/compose/binaries/main/inno/cloudmarks-setup.exe`。

`:desktopApp:stageInstallerFiles` は、インストーラに入れるファイルを `desktopApp/build/compose/binaries/main/staged/CloudMarks/` に揃える。
`createDistributable` の出力に、Native Messaging のホストの起動口を足したもの。
起動口は `CloudMarks.exe` の複製の `cloud-marks-host.exe` と、`HostMainKt` を起動する `app\cloud-marks-host.cfg`（`generateHostLauncher` が作る）。
ホストを登録する `cloud-marks-host.chrome.json`・`cloud-marks-host.firefox.json` も、配布版の名前で `cloud-marks-host.exe` と同じ場所に置く。
配布版の Chrome の拡張（`apps/extension/dist/chrome`）を `extension\chrome\` に写す。無ければ失敗する。
署名済みの Firefox の拡張は、`-Pcloudmarks.firefoxExtension=<.xpi のパス>` を渡したときだけ `extension\firefox\cloudmarks-firefox.xpi` に写す。
リポジトリのライセンス（`LICENSE`）と第三者のライブラリの表記（`THIRD-PARTY-NOTICES.md`）を、`CloudMarks.exe` と同じ場所に写す。無ければ失敗する。

`:desktopApp:packageInnoSetup` は `stageInstallerFiles` の出力をそのままインストーラに入れる。
ホストの名前は `contracts/native-messaging/identities.json` の配布版の組から読み、`ISCC` に `/DHostName` で渡す。
インストーラは `HKCU` の Chrome と Firefox の `NativeMessagingHosts\<ホストの名前>` の既定の値に、ホストを登録する JSON のパスを書く。
アンインストールでこの 2 つのキーを消す。`%LOCALAPPDATA%\CloudMarks` のデータは消さない（`docs/native-messaging.md` の「ホストの登録」）。

APK には、リポジトリのライセンス（`LICENSE`）と第三者のライブラリの表記（`THIRD-PARTY-NOTICES.md`）を、assets の直下に入れる
（`:androidApp` の `copyLicenseAssets`。無ければ失敗する）。アプリの画面には出さない。

Android の配布用の署名には、環境変数 `CLOUDMARKS_RELEASE_KEYSTORE`・`CLOUDMARKS_RELEASE_KEYSTORE_PASSWORD`・
`CLOUDMARKS_RELEASE_KEY_ALIAS`・`CLOUDMARKS_RELEASE_KEY_PASSWORD` を使う。`CLOUDMARKS_RELEASE_KEYSTORE` が無ければ署名なしで組む。
