# Cloud Marks（cm）

Kotlin Multiplatform / Compose Multiplatform で、Android と Windows Desktop を1つのコードからビルドする。

## モジュール

| モジュール | 受け持ち |
|---|---|
| `:shared` | 両方で使うコード。ターゲットは `jvm()` と `android {}`。`jvmShared` は Android とデスクトップで共有する JVM の実装を置く中間ソースセットで、S3 へのアクセスと、画面（エントリポイントは `CloudMarksApp()`）とその状態、状態を持つコントローラの組み立て（`createCloudMarksController()`）を置く |
| `:androidApp` | Android アプリ（`CloudMarksApplication` で、ファイルの場所・暗号化の仕組み・時計・ブラウザで開く仕組みなどを `createCloudMarksController()` に渡して、プロセスに 1 つのコントローラを組み立て、`MainActivity` の `CloudMarksApp()` に渡す。画面を閉じても、コントローラは閉じない） |
| `:desktopApp` | Windows デスクトップアプリ（`main.kt` で、ファイルの場所・暗号化の仕組み・時計・古いファイルの削除を行うこと・ほかのプロセスが S3 へ書いた知らせ・ブラウザで開く仕組みを `createCloudMarksController()` に渡してコントローラを組み立て、ウィンドウの `CloudMarksApp()` に渡す。ウィンドウを閉じたらコントローラを閉じる）。Native Messaging のホスト（`HostMain.kt`）と、配布物のビルドもここ |

## コードの地図

直したいことから、触るファイルを探すための案内。仕様は `docs/` にあり、コードはそれに従う。

### `shared` のソースセット

`shared/src` は、コンパイルされる先のプラットフォームでソースセットを分ける。どのソースセットでも、ファイルは
`to.sava.cloudmarksmultiplatform` の下の同じ名前のパッケージに置く（たとえば `app/` は `jvmShared`・`jvmMain`・`androidMain` のどれにもある）。

| ソースセット | コンパイルされる先 | 置くもの |
|---|---|---|
| `commonMain` | Android とデスクトップ | JVM の API を使わないコード。S3 上の形式の読み書き（`storage/`）と、データを変える操作の適用（`operations/`） |
| `jvmShared` | Android とデスクトップ | 両方で使う JVM のコード。画面、コントローラ、S3 へのアクセス、書き込みの手順、設定、アプリの更新の共通の部分 |
| `jvmMain` | デスクトップ | デスクトップのアプリと Native Messaging のホストだけが使うコード |
| `androidMain` | Android | Android のアプリだけが使うコード |

- `jvmShared` は Android でもコンパイルされるので、Android で使えない JVM の API は使わない。
  たとえば `files/Utf8FileRead.kt` は、Android では新しい API レベルにしか無い `Files.readString` の代わりに置いてある
- プラットフォームで違う部分は、次の 2 通りで分ける
  - 画面の小さな違い（タッチの画面の振る舞い、ドラッグして移せるか、スクロールバー、システムの戻る操作、文言の一部）は、
    `jvmShared` の `ui/` に `expect` を置き、`actual` を `jvmMain` の `<名前>.jvm.kt` と `androidMain` の `<名前>.android.kt` に置く
  - それより大きな違い（ファイルの場所、認証情報の暗号化、ブラウザで開くこと、ほかのアプリで開くことと共有すること、
    アプリの更新）は、`jvmShared` に型（`AppFiles`・`SecretProtector`・`BrowserOpener`・`ItemSharer`・`AppUpdater`）を置き、
    実装を `jvmMain` と `androidMain` に置く。各アプリのエントリポイントが実装を作り、`createCloudMarksController()` と
    `CloudMarksApp()` に渡す（上の「モジュール」）。`ItemSharer` は Android だけが作る

テストのソースセットは次のとおり。走らせ方は下の「テスト」にある。

| ソースセット | 走らせるタスク | 置くもの |
|---|---|---|
| `commonTest` | `:shared:jvmTest` と `:shared:testAndroidHostTest` の両方 | `commonMain` のテスト |
| `jvmSharedTest` | 両方のタスクに含まれる | `jvmTest` と `androidHostTest` の両方で使う部品（アプリの更新のテストの、偽の HTTP の応答など） |
| `jvmTest` | `:shared:jvmTest` | `jvmShared` と `jvmMain` のテスト。画面のテスト（`ui/`）と、`contracts/` の見本を読むテストもここ |
| `androidHostTest` | `:shared:testAndroidHostTest` | `androidMain` のテスト。端末もエミュレータも使わず、開発機の JVM で走る |

### `desktopApp` と `androidApp`

組み立ての流れは上の「モジュール」にある。Kotlin のファイルは、どちらも `src/main/kotlin/to/sava/cloudmarksmultiplatform/` の下に置く。

| ファイル | 受け持ち |
|---|---|
| `desktopApp` の `main.kt` | デスクトップのアプリのエントリポイント。多重起動を防ぎ、コントローラとアプリの更新を組み立て、記録した位置と大きさでウィンドウを開く。検索を開くキーの操作（Ctrl+F）は、ウィンドウで受ける |
| `desktopApp` の `HostMain.kt` | Native Messaging のホストのエントリポイント。処理は `shared` の `host/` にある |
| `desktopApp` の `DesktopVersion.kt` | ビルド（`generateVersionProperties`）が書いたリソースから、バージョンを読む |
| `desktopApp/build.gradle.kts` | 配布物、ホストの起動口とホストを登録する JSON、開発版のホスト、インストーラに入れるファイル、インストーラを作るタスク（下の「開発版のホスト」「配布物のビルド」） |
| `desktopApp/installer/cloudmarks.iss` | Inno Setup のインストーラの定義 |
| `desktopApp/icons/cloudmarks.ico`・`desktopApp/src/main/resources/icon.png` | exe とインストーラのアイコンと、ウィンドウのアイコン |
| `androidApp` の `CloudMarksApplication.kt` | プロセスに 1 つのコントローラとアプリの更新を組み立てる |
| `androidApp` の `MainActivity.kt` | 画面を出す。前面に戻ったことと、操作を受け付けられるようになったことを知らせる |
| `androidApp` の `UpdateFileProvider.kt`・`src/main/res/xml/update_paths.xml` | ダウンロードした APK を端末のインストーラへ渡す FileProvider |
| `androidApp/src/main/AndroidManifest.xml`・`src/main/res/xml/data_extraction_rules.xml` | 権限と FileProvider の宣言。認証情報を端末から出さないよう、バックアップと端末間の移行からデータを除く |
| `androidApp/src/main/res/` のほかのファイル | アプリの名前（`values/strings.xml`）、テーマ、ランチャーのアイコン |
| `androidApp/build.gradle.kts` | 署名、FileProvider の authority の確認（`verifyFileProviders`）、ライセンスを APK に入れること（下の「配布物のビルド」） |

### `shared` のパッケージ

| パッケージ | ソースセット | 受け持ち |
|---|---|---|
| 直下（`to.sava.cloudmarksmultiplatform`） | `jvmShared`・`jvmMain`・`androidMain` | アプリが端末に置くファイルの場所（`AppFiles.kt` と、`DesktopAppFiles.kt`・`AndroidAppFiles.kt`）、配布版か開発版かの見分け（`AppPath.kt`）、デスクトップのウィンドウのタイトル（`DesktopWindowTitle.kt`・`DesktopBuildTime.kt`）、Android の休止中も進む時計（`ElapsedRealtimeTimeSource.kt`） |
| `ui/` | `jvmShared`（`actual` は `jvmMain`・`androidMain`） | Compose の画面。入口は `CloudMarksApp.kt`。メインの画面のツリーとリスト、項目の検索の結果（`SearchResultsPane.kt`）と、デスクトップの検索を開くキーの操作（`SearchShortcut.kt`）、設定の画面、以前の状態に戻す画面、ダイアログとメニュー、ドラッグして移すこと、画面の文言（`Messages.kt`）、項目の行に描く favicon（`ItemFavicon.kt`。favicon を返す仕組みを渡す `LocalFaviconSource` もここ）と、favicon が無いときの地球儀のアイコン（`GlobeIcon.kt`） |
| `app/` | `jvmShared`・`jvmMain`・`androidMain` | 画面の状態（`AppState.kt`・`WriteState.kt`）と、利用者の操作に応じて設定と S3 の読み書きを進めるコントローラ（`CloudMarksController.kt`。`createCloudMarksController()` が `favicon/` のストアを作って持たせる）。S3 から読み直すきっかけの知らせは、デスクトップが `StorageChangeWatcher.kt`、Android が `ForegroundReturns.kt`・`NetworkAvailability.kt` |
| `browse/` | `jvmShared` | メインの画面で開いているフォルダと選んでいる項目（`BrowseState.kt`）、ツリーの行の並び（`FolderTree.kt`）、項目の検索：照らし合わせと結果（`ItemSearch.kt`）、移すものと移動先、ドラッグを落とす位置（`MoveSubject.kt`・`MoveDrop.kt`）。Compose を使わない |
| `operations/` | `commonMain` | データを変える操作の型（`Operations.kt`）と、ツリーの状態への適用（`ApplyOperations.kt`）。`docs/operations.md` の規則を実装する |
| `storage/` | `commonMain`・`jvmShared` | マニフェストと項目ファイルの型、JSONL の読み書きと検証、キーの組み立て（`StorageKeys.kt`）。gzip の圧縮と展開は `jvmShared` の `StorageGzip.kt` |
| `remote/` | `jvmShared` | S3 のオブジェクトを読み書きする型（`ObjectStore.kt`）と、AWS SDK による実装（`S3ObjectStore.kt`）。応答を結果の値にする対応（`S3Results.kt`）と、一覧のページング（`ListPaging.kt`） |
| `sync/` | `jvmShared` | `docs/storage-format.md` の手順の実装。ツリーの読み込み（`TreeReader.kt`）、書き込みの手順（`WriteProcedure.kt`・`OperationWriter.kt`）、ローカルキャッシュ、マニフェストを新しく作ること、以前の状態に戻すこと、古いファイルの削除。まとめる入口は `StorageSync.kt` |
| `settings/` | `jvmShared`・`jvmMain`・`androidMain` | S3 の接続の設定と端末ごとの設定（表示の大きさ、まとめてブラウザで開くときのタブの読み込み方）と、そのファイルの読み書き。認証情報の暗号化は、デスクトップが DPAPI（`WindowsDpapiProtector.kt`）、Android が Android Keystore（`AndroidKeystoreProtector.kt`） |
| `browser/` | `jvmShared`・`jvmMain`・`androidMain` | 項目をブラウザで開くこと（`BrowserOpener.kt` と、`DesktopBrowserOpener.kt`・`AndroidBrowserOpener.kt`）と、Android の、ほかのアプリで開くことと共有すること（`ItemSharer.kt`・`AndroidItemSharer.kt`）。デスクトップの、入っているブラウザの一覧（`InstalledBrowsers.kt`・`WindowsRegistry.kt`）、拡張へのコマンドのファイル（`OpenTabsCommand.kt`・`OpenTabsCommandFiles.kt`）、拡張が入っていると分かったブラウザの記録もここ |
| `favicon/` | `jvmShared`（`actual` は `jvmMain`・`androidMain`） | 項目の URL のオリジンごとの favicon。入口は `FaviconStore.kt`（取得、端末のキャッシュ、状態。画面が受け取る型と、何も取りに行かない `NoFavicons` は `Favicon.kt`）。取り先の順番は `FaviconFetcher.kt`、オリジンへの正規化と、外部の取り先へ渡してよいホストの判定は `FaviconOrigin.kt`、HTML の `<link>` を拾うのは `FaviconLinks.kt`、キャッシュの期限は `FaviconExpiry.kt`、キャッシュのファイルの形は `FaviconCache.kt`。画像を読んで縮めるのは、デスクトップが `Favicon.jvm.kt`、Android が `Favicon.android.kt` |
| `update/` | `jvmShared`・`jvmMain`・`androidMain` | アプリの更新。確認、署名の検証、ダウンロードと SHA-256 の確認は `jvmShared`（入口は `AppUpdater.kt`）、インストーラへ渡すところはデスクトップ（`DesktopUpdater.kt`・`DesktopUpdateInstaller.kt`）と Android（`AndroidUpdater.kt`・`AndroidUpdateInstaller.kt`） |
| `host/` | `jvmMain` | Native Messaging のホスト。メッセージの読み書き（`NativeMessageFrame.kt`）、要求の解釈と、`folder` を操作の親・追加先にすること（`HostRequest.kt` の `toOperation`）、処理（`NativeMessagingHost.kt`。マニフェストだけを読むフォルダの一覧と、追加先が無いときは何も書かないこともここ）、応答と、拡張が画面に出す失敗の文、追加先が無いことの判定（`HostResponse.kt`・`HostErrors.kt`）、デスクトップのアプリの起動（`AppLaunch.kt`） |
| `window/` | `jvmMain` | デスクトップのアプリの多重起動を防ぐこと（`SingleInstance.kt`）と、ウィンドウの位置と大きさの記録（`WindowGeometryFile.kt`・`WindowGeometryState.kt`） |
| `files/` | `jvmShared`・`jvmMain` | ファイルを原子的に置き換える書き込み、UTF-8 での読み込み、I/O の失敗をログに書く短い文にすること |
| `logging/` | `jvmShared` | ログのファイル（`FileLog.kt`）。利用者には見せない。認証情報と、項目のタイトルや URL は書かない |

### 直すときの入口

#### 画面の見た目や文言を直す

- 画面は `ui/` にある。`CloudMarksApp.kt` が、AppBar、画面の切り替え、広い画面と狭い画面での並べ方を持つ。
  ツリーは `FolderTreePane.kt`、リストは `FolderListPane.kt`、検索の結果は `SearchResultsPane.kt`（項目の行 `ItemRow` はリストと共有する。
  行の左に、`ItemFavicon.kt` の favicon を描く）、
  設定の画面は `SettingsScreen.kt` と `UpdateSection.kt`、以前の状態に戻す画面は `RevisionsScreen.kt`、ダイアログは `OperationDialogs.kt`、
  メニューは `ContextMenus.kt`
- 文言は `ui/Messages.kt` に集めてある。デスクトップと Android で違う文は `expect` にし、`Messages.jvm.kt` と
  `Messages.android.kt` に置く。設定の画面の欄の名前など一部は、それを描くファイルに直に書いてある
- 色はテーマから取る。明るいテーマと暗いテーマは、`CloudMarksApp.kt` が端末の設定に合わせて選ぶ
- 画面の文言は、ルートの `README.md`（「使いはじめる」「更新」）、`docs/storage-format.md`（「オフラインのとき」）、
  `docs/distribution.md`（「アプリの更新」）も引いている。拡張の文（`apps/extension/src/messages.ts`）も、設定の画面の
  「アプリの更新」の節を名前で指している。文言を変えたら、こちらも直す
- テストは `jvmTest` の `ui/` に足す。窓を出さずに `runComposeUiTest` で描いて操作する（起動の手順は `ScreenTestSupport.kt`）。
  文言のテストは `MessagesTest.kt` と、Android の分の `androidHostTest` の `AndroidMessagesTest.kt`

#### データを変える操作を足す

- 操作の入力と適用の規則は、`docs/operations.md` に足す
- 操作の型は `operations/Operations.kt` に足す。適用の前に項目を読み込んでおくフォルダも、同じファイルで決める。
  適用は `operations/ApplyOperations.kt`。作るものの ID と追加した日時は操作に持たせ、再適用しても同じ結果にする
  （`docs/operations.md` の「原則」）
- S3 へは `sync/OperationWriter.kt` が、`Operations.kt` と `ApplyOperations.kt` を使って書く。操作を足しても `sync/` は直さなくてよい
- 利用者の操作から操作を組み立てて書くのは `app/CloudMarksController.kt`。画面の入口は `ui/OperationActions.kt`（AppBar・
  リスト・ツリー・検索の結果からの操作）、`ui/OperationDialogs.kt`、`ui/ContextMenus.kt`
- 拡張からの追加のように、ホストが受けて書く操作なら、`host/HostRequest.kt` と `docs/native-messaging.md` も直す
  （下の「ホストが受けるメッセージを足す」）
- テストは、適用を `commonTest` の `operations/` に、コントローラからの書き込みを `jvmTest` の `app/` に足す

#### S3 への読み書きを変える

- S3 上の形式は `docs/storage-format.md` が決め、見本は `contracts/storage/` にある。形式を変えるときは、文書と見本と実装を
  1 つの変更で直す（`contracts/storage/README.md`）
- 形式の読み書きと検証は `storage/`、S3 への個々の要求は `remote/`（HTTP の状態から結果への対応は `S3Results.kt`）、
  書き込みの手順・ローカルキャッシュ・以前の状態に戻すこと・古いファイルの削除は `sync/`
- ホストも同じ `sync/` を通して書く。変えると、アプリとホストの両方の振る舞いが変わる
- 失敗の扱いと一覧の読み方は、ルートの `AGENTS.md` の「扱いの注意」に従う
- テストは `jvmTest` の `remote/`・`sync/`・`storage/` と、`commonTest` の `storage/`。S3 の代わりに `remote/FakeObjectStore.kt` を使う。
  実際の S3 で確かめる `S3ObjectStoreIntegrationTest.kt` は、ファイルの先頭に挙げた環境変数があるときだけ走る

#### ホストが受けるメッセージを足す

- メッセージの形は `docs/native-messaging.md` が決め、見本は `contracts/native-messaging/` にある。拡張と `apps/cm` の両方を
  1 つの変更で直す（拡張の側は `apps/extension/README.md` の「コードの地図」）
- 要求の解釈は `host/HostRequest.kt`、処理は `host/NativeMessagingHost.kt`、応答の形は `host/HostResponse.kt`、
  失敗の文は `host/HostErrors.kt`。標準出力は応答のほかに使わない（`host/HostEntryPoint.kt`）
- 種類を足すだけなら、`protocol` は上げない。古いホストは知らない種類を `invalidRequest` で断る（`docs/native-messaging.md` の
  「拡張から送るもの」）
- テストは `jvmTest` の `host/` に足す。`contracts/native-messaging/` の見本を読ませて確かめる（見本は `ContractsTestSupport.kt` が探す）

#### アプリの更新の振る舞いを変える

- 振る舞いは `docs/distribution.md` の「アプリの更新」、更新情報と署名の形式は同じ文書の「更新情報と署名」にある。
  配ったら変えられない値（リポジトリの名前、タグ、ファイルの名前、公開鍵）は同じ文書の「配ったら変えられないもの」で、
  `update/UpdateChecker.kt` と `update/UpdateInfoSignature.kt` が持つ
- 確認、ダウンロード、SHA-256 の確認と状態は `jvmShared` の `update/`（入口は `AppUpdater.kt`、デスクトップと Android で同じ流れは `BaseAppUpdater.kt`）。
  インストーラへ渡すところは、デスクトップが `jvmMain` の `DesktopUpdater.kt`・`DesktopUpdateInstaller.kt`（更新を適用する
  PowerShell のスクリプトもここで書く）、Android が `androidMain` の `AndroidUpdater.kt`・`AndroidUpdateInstaller.kt`
- 画面は `ui/UpdateSection.kt`（設定の画面の「アプリの更新」の節）
- 動いているアプリのバージョンは、デスクトップが `desktopApp` の `DesktopVersion.kt`、Android が `update/AndroidAppVersion.kt` で読む
- テストは `jvmTest` と `androidHostTest` の `update/` に足す。HTTP は `jvmSharedTest` の偽の応答で動かす

#### デスクトップだけの処理

- `shared` の `jvmMain` に置く。組み立てて渡すのは `desktopApp` の `main.kt`（ホストは `HostMain.kt`）
- Windows の API は FFM（`java.lang.foreign`）で呼ぶ（DPAPI の `settings/WindowsDpapiProtector.kt`、レジストリの
  `browser/WindowsRegistry.kt`、プロセスの起動の `host/AppLaunch.kt`）。`jvmTest` と、デスクトップのアプリとホストは、
  ネイティブの関数の呼び出しを許して起動する（`shared/build.gradle.kts`・`desktopApp/build.gradle.kts`）
- Android でも要る処理は `jvmMain` に書かず、`jvmShared` に置く。プラットフォームで違う部分だけを、上の「`shared` のソースセット」の
  2 通りで分ける
- 配布版と開発版で分けるもの（データの場所、ホストの名前、呼び出しの URL のホスト）は、下の「配布版と開発版」にある
- テストは `jvmTest` に足す

#### Android だけの処理

- `shared` の `androidMain` に置く。組み立てて渡すのは `androidApp` の `CloudMarksApplication.kt`、画面を出すのは `MainActivity.kt`
- 権限、FileProvider、バックアップから除くものは、`androidApp` の `AndroidManifest.xml` と `src/main/res/xml/`
- 画面の振る舞いの違い（タッチの画面、システムの戻る操作、ドラッグして移さないこと）は、`ui/` の `TouchScreen.kt`・`SystemBack.kt`・
  `DragToMove.kt` の `expect` と、その `.android.kt` で分ける
- テストは `androidHostTest` に足す。タッチの画面の振る舞いは、`jvmTest` の画面のテストでも確かめられる（`ScreenTestSupport.kt`）

## 開く

IntelliJ IDEA で `apps/cm` を開く。

## ビルドと起動

デスクトップ（`shared` の `jvm()` と `:desktopApp`）は JDK 25 でコンパイルし、JetBrains Runtime 25 で動かす。
バージョンは `gradle/libs.versions.toml` の `desktop-jdk` が持つ。Gradle のデーモンも JetBrains Runtime 25 で動かし、
Android の側はそのデーモンの JDK でビルドする。デーモンの JDK は `gradle/gradle-daemon-jvm.properties` が持ち、`desktop-jdk` と揃える。

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
