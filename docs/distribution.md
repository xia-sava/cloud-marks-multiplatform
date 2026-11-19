# 配布と更新

アプリ（Windows のデスクトップと Android）と拡張（Chrome と Firefox）を配る仕組みと、配ったものを更新する仕組み。
配布物は GitHub Actions が組み、GitHub Releases に公開する。リリースの手順は `.claude/skills/release/SKILL.md` にある。

## 配るもの

| 配るもの | ファイル | 作るワークフロー |
|---|---|---|
| Windows のインストーラ | `cloudmarks-setup.exe` | `.github/workflows/release.yml` |
| Android の APK | `cloudmarks.apk` | `.github/workflows/release.yml` |
| Chrome の拡張 | インストーラが入れるフォルダ（`<インストール先>\extension\chrome`） | `.github/workflows/release.yml`（インストーラに入れる） |
| Firefox の拡張 | AMO で署名した `.xpi` | `.github/workflows/extension-release.yml` |

- インストーラは、デスクトップのアプリ、Native Messaging のホスト、Chrome の拡張のフォルダ、Firefox の `.xpi` を入れる（下の「インストーラ」）。
  `<インストール先>` は `%LOCALAPPDATA%\Programs\CloudMarks`
- Chrome の拡張は、タグのコミットから作った配布版（`apps/extension/dist/chrome`）。リリースには単独で置かない。Chrome ウェブストアには出さない
- Firefox の拡張は、AMO の自分で配る拡張（unlisted）として署名をもらう。AMO のストアのページは作らない。インストーラには、
  `extension-latest` のリリースの `.xpi` を入れる

## 置き場所

GitHub のリポジトリ `xia-sava/cloud-marks-multiplatform` のリリースに置く。ファイルの URL は
`https://github.com/xia-sava/cloud-marks-multiplatform/releases/download/<タグ>/<ファイルの名前>`。

| リリースのタグ | 置くファイル | 使いみち |
|---|---|---|
| `vX.Y.Z` | `cloudmarks-setup.exe`、`cloudmarks.apk` | どのバージョンで何を配ったかの記録。GitHub の「Latest」はこのリリースに付ける |
| `latest` | `cloudmarks-setup.exe`、`cloudmarks.apk`、`latest.json`、`latest.json.sig` | アプリの更新。README のインストールの案内 |
| `extension-vX.Y.Z` | `cloudmarks-firefox-X.Y.Z.xpi` | Firefox の更新（`updates.json` の `update_link` が指す） |
| `extension-latest` | `cloudmarks-firefox.xpi`、`updates.json` | Firefox の更新（`update_url`）。`release.yml` がインストーラに入れる `.xpi` |

- `vX.Y.Z` と `extension-vX.Y.Z` はバージョンごとのリリースで、リリースのたびに新しく作る。`latest` と `extension-latest` は
  固定のリリースで、リリースのたびに中のファイルだけを置き換える。固定のリリースは prerelease にし、「Latest」を付けない
- 固定のリリースのファイルは、配布物を先に、更新情報（`latest.json`・`latest.json.sig`・`updates.json`）を最後に置き換える。
  更新情報が、まだ置いていない配布物を指すあいだを作らないため
- タグ `latest` と `extension-latest` は、最初にリリースを作ったときのコミット（その時点の既定のブランチの先頭）を指したまま動かさない。
  `GITHUB_TOKEN` は、タグの前後のコミットのあいだに `.github/workflows` の変更があると、タグを動かせない（403 になる）ため。
  アプリと Firefox は、タグの指すコミットではなくリリースに置いたファイルを読むので、配る中身はタグの指す先によらず替えられる
- リポジトリの設定で immutable releases（公開したリリースを変えられなくする設定）を有効にしない。固定のリリースのファイルを
  置き換えられなくなる

## 配ったら変えられないもの

配ったアプリと拡張は、次の値を前提に動く。変えると、それより前に配ったものが更新を受け取れなくなるか、ホストとやり取りできなくなる。

- **固定のリリースのタグと URL。** リポジトリの名前（`UpdateChecker.kt` と `firefox-release.ts` の `RELEASE_REPOSITORY`）、
  タグ `latest`・`extension-latest`、`releases/download/<タグ>/<ファイルの名前>` の形。アプリは、更新情報と配布物の URL をこの形で自分で組む
- **ファイルの名前。** `latest.json`、`latest.json.sig`、`cloudmarks-setup.exe`、`cloudmarks.apk`、`updates.json`
- **更新情報の公開鍵。** `UpdateInfoSignature.kt` の `UPDATE_INFO_PUBLIC_KEY`。替えると、前の鍵を持つアプリが署名を確かめられなくなる
- **更新情報と署名の形式**（下の「更新情報と署名」）
- **プラットフォームの項目の形。** アプリは、更新情報の自分の項目（`desktop` か `android`）だけを読む。別の項目を足してもよいが、
  `desktop` と `android` の欄の名前と型は変えない
- **Firefox の `update_url` と gecko の ID**（`cloud-marks-multiplatform@sava.to`）。入れた Firefox の拡張は、`update_url` から
  gecko の ID の項目を読み続ける
- **Chrome の拡張の `key`**（`contracts/native-messaging/identities.json`）。拡張の ID は `key` から決まり、ホストはこの ID の拡張だけを受け付ける
- **Native Messaging のホストの名前**（`to.sava.cloudmarksmultiplatform`）。Firefox の拡張はアプリと別に更新されるので、
  新しい拡張も古い拡張も、同じ名前でホストを呼ぶ
- **呼び出しの URL のホスト**（`identities.json` の `callUrlHost`。配布版は `cmm.invalid`）。アプリはこのホストの URL でブラウザを起動し、
  拡張はこのホストの URL を開いたタブだけを見つける（`docs/native-messaging.md` の「アプリからのコマンド（ブラウザで開く）」）。
  Firefox の拡張とアプリは別に更新されるので、新旧のどちらも同じホストを使う
- **インストーラの `AppId`、exe の名前（`CloudMarks.exe`）、インストール先（`%LOCALAPPDATA%\Programs\CloudMarks`）。** `AppId` を変えると、
  新しいインストーラが別のアプリとして並べて入る。更新を適用するスクリプトは、動いていたアプリの exe を同じパスで起動し直す。
  Chrome はインストール先のフォルダから拡張を読み込み、レジストリはインストール先のパスでホストを登録する JSON を指す
- **デスクトップのアプリのデータを置くディレクトリ**（`%LOCALAPPDATA%\CloudMarks`）。更新したアプリとホストが、前の設定と認証情報を読む
- **Android のアプリケーションの ID（`to.sava.cloudmarksmultiplatform`）と署名の鍵。** 端末は、同じ ID で同じ鍵の APK でなければ
  上書きの更新を断る。`release.yml` は、APK の署名の証明書の SHA-256 を期待値と比べる

## 更新情報と署名

アプリは、固定のリリース `latest` の更新情報（`latest.json`）と署名（`latest.json.sig`）を読んで、新しいバージョンがあるかを確かめる。
`release.yml` の `update-info` ジョブが、配布物から更新情報を書き（`.github/scripts/write-update-info.sh`）、署名する。
読み手は `apps/cm/shared` の `update/`（`UpdateChecker.kt`・`UpdateInfo.kt`・`UpdateInfoSignature.kt`）。

### 更新情報（`latest.json`）

```json
{"desktop":{"versionCode":58,"versionName":"1.2.3","sha256":"<小文字の 16 進で 64 文字>"},"android":{"versionCode":58,"versionName":"1.2.3","sha256":"<小文字の 16 進で 64 文字>"}}
```

| 欄 | 型 | 内容 |
|---|---|---|
| `desktop` | オブジェクト | デスクトップの項目 |
| `android` | オブジェクト | Android の項目 |
| `<項目>.versionCode` | 整数 | 配布しているバージョンの、新旧の比較に使う数。1 以上（下の「バージョン」） |
| `<項目>.versionName` | 文字列 | 配布しているバージョンの名前。画面に出す。空白だけではいけない |
| `<項目>.sha256` | 文字列 | 配布物の SHA-256。小文字の 16 進で 64 文字 |

- BOM の無い UTF-8 の JSON のオブジェクト
- 読み手は、署名を確かめてから更新情報を読む（下の「署名」）
- 読み手は、自分のプラットフォームの項目だけを読む。ほかのキーは、形が合わなくても読まない。自分の項目が無いか `null` なら、
  読めなかったものとして扱う
- 読み手は、知らない欄を無視する。項目の中の知らない欄も同じ
- `versionCode` は JSON の整数の字句で書く。読み手は、文字列にした数、小数、指数の形を読まない。`sha256` の大文字も読まない
- 配布物の URL は載せない。アプリは、固定のリリース `latest` の配布物の名前（`cloudmarks-setup.exe`・`cloudmarks.apk`）から URL を組む。
  更新情報が決められるのは、バージョンと SHA-256 だけ
- アプリは、自分の項目の `versionCode` が動いているアプリの `versionCode` より大きければ、新しいバージョンがあると伝える

### 署名（`latest.json.sig`）

- 受け取った `latest.json` のバイト列そのものへの、`SHA256withECDSA`（ECDSA P-256）の署名。DER の署名を base64 にしたテキスト
- 書き手は、改行を入れない 1 行で書く（`base64 -w0`）。読み手は、空白と改行をすべて除いてから読む
- 公開鍵は、X.509 SubjectPublicKeyInfo の DER を base64 にしたもの。アプリのソースの `UPDATE_INFO_PUBLIC_KEY` にだけ書き、
  ワークフローはそこから読む（`.github/scripts/update-info-public-key.sh`）
- 署名が無い、読めない、合わないときは、更新情報を読まない。整形し直すなどしてバイト列が変わると、署名は合わなくなる

### 大きさの上限

| 受け取るもの | 上限 | 定数 |
|---|---|---|
| 更新情報と署名 | それぞれ 64 KiB | `ReleaseDownload.kt` の `MAX_UPDATE_INFO_BYTES` |
| 配布物（インストーラと APK） | 200 MiB | `ReleaseDownload.kt` の `MAX_RELEASE_BYTES` |

- アプリは、応答の大きさ（Content-Length）が上限を超えていれば読まない。受け取った量が上限を超えたところでも、止めて失敗にする
- `write-update-info.sh` は、上限より大きい配布物があれば、更新情報を書かずに止まる。配っても、アプリが受け取れないため
- 画面には、ダウンロードの途中で止める操作は無い

## バージョン

- **`versionName`** は、タグ名から先頭の `v` を除いたもの。`MAJOR.MINOR.PATCH` の形でなければ、`release.yml` が止まる
  （接尾辞の付いたタグも断る）
- **`versionCode`** は、`apps/cm` と `apps/extension` に触れたコミットの数（`git rev-list --count HEAD -- apps/cm apps/extension`）。
  デスクトップと Android で同じ値を使い、人は決めない。拡張だけを直したバージョンも出せるよう、拡張のコミットも数える
- ワークフローや `contracts/` だけを直したコミットでは、`versionCode` は増えない。前のリリースのあとにそうしたコミットしか無ければ、
  次のリリースは止まる
- `release.yml` は、公開の前に、`versionCode` が配布中の `latest.json` のものより増えていることを確かめ、増えていなければ止まる。
  配布中の更新情報が無ければ（404）、最初のリリースとして進む
- 同じコミットにタグを打ち直しても `versionCode` は変わらない。そのコミットの `latest.json` を公開したあとなら、止まる。
  配ったものを直すときは、直したコミットで新しいバージョンを出す。
  Android は `versionCode` が下がる上書きを断り、アプリは自分より大きい `versionCode` しか案内しないので、配ったバージョンを下げる手段は無い
- 拡張のバージョンは、`apps/extension/package.json` の `version`。タグ `extension-vX.Y.Z` の `X.Y.Z` と同じでなければ、
  `extension-release.yml` が止まる。AMO は同じバージョンに 2 度署名しないので、拡張を出すたびに上げる
- Firefox の拡張が求める Firefox の最小のバージョンは 142.0（manifest の `strict_min_version`）。`updates.json` の項目は
  `strict_min_version` を持たない。最小のバージョンを上げるときは、`updates.json` の項目にも足す。足さないと、新しい最小のバージョンより
  古い Firefox にも、新しいバージョンが案内される

## インストーラ

Inno Setup で組む（`apps/cm/desktopApp/installer/cloudmarks.iss`）。入れるファイルは `:desktopApp:stageInstallerFiles` が揃える
（`apps/cm/README.md` の「配布物のビルド」）。

- 管理者権限は要らない。インストール先は `%LOCALAPPDATA%\Programs\CloudMarks` で、利用者に選ばせない。スタートメニューに
  「Cloud Marks」を作る
- **インストーラは人手なしで完走する。** アプリの更新は、インストーラを `/VERYSILENT /SUPPRESSMSGBOXES /NORESTART` で走らせる。
  インストーラに画面や確認を足すときも、この引数で誰も答えずに終わるようにする。そのため、管理者権限を求めない。終わりにアプリを
  起動するかは、人が操作したときだけ尋ねる
- Native Messaging のホストを Chrome と Firefox に登録する（`docs/native-messaging.md` の「ホストの登録」）。アンインストールで登録を消す
- 更新のインストールは、`app`・`runtime`・`extension` のフォルダを消してから入れ直す。前のバージョンにあって新しいバージョンに無いファイルを
  残さないため
- インストール先の直下に、リポジトリのライセンス（`LICENSE`）と第三者のライブラリの表記（`THIRD-PARTY-NOTICES.md`）を入れる。
  JetBrains Runtime のライセンスと表記は `runtime\legal` に入る
- アンインストールが消すのは、インストール先のファイルとホストの登録だけ。アプリのデータ（`%LOCALAPPDATA%\CloudMarks`。設定、認証情報、
  キャッシュ、ログ）は残る
- アンインストールすると、Chrome が拡張を読み込んでいたフォルダ（`<インストール先>\extension\chrome`）も消える。Chrome の拡張は、
  利用者が Chrome の拡張の画面で外す
- インストーラにはコードの署名をしていない。Windows の SmartScreen が警告を出す

## 第三者のライブラリの表記

`THIRD-PARTY-NOTICES.md` に、デスクトップのアプリと Android のアプリに入る第三者のライブラリ（名前、ライセンス、著作権者、URL）と、
Apache License 2.0 の NOTICE の中身と、ライセンスの本文を置く。アイコンの出典（Flaticon のアイコン、CC BY 3.0）も置く。

- インストーラはインストール先の直下に、APK は assets の直下（`assets/LICENSE`・`assets/THIRD-PARTY-NOTICES.md`）に、拡張は
  4 つの出力（配布版と開発版の Chrome と Firefox）の直下に、`LICENSE` と `THIRD-PARTY-NOTICES.md` を入れる。インストーラの分は
  `:desktopApp:stageInstallerFiles`、APK の分は `:androidApp` の `copyLicenseAssets`、拡張の分は `apps/extension/scripts/build-extension.ts`
  が写す。どれも、ファイルが無ければビルドが止まる。アプリと拡張の画面には出さない
- 拡張のビルドが `apps/extension` の外から読むのは、この 2 つのファイルだけ。`extension-release.yml` が AMO に添えるソースの zip にも入れる
- 対象は、`apps/cm` の `:desktopApp` の `runtimeClasspath` と、`:androidApp` の `releaseRuntimeClasspath`。依存を足したとき、
  バージョンを上げたときは、表記も直す
- Skiko の中の Skia とそのライブラリは、Skiko のバージョンが使う Skia の `DEPS` で版を確かめる。Skiko を上げたときは、表と本文も見直す
- 拡張は第三者のパッケージを入れない（esbuild がまとめるのは `apps/extension/src` だけ）。拡張のコードが npm のパッケージを読むようにしたら、
  表記に足す
- アイコン（`assets/icon/`）は、Windows のアプリ、Android のアプリ、拡張のすべてに入る。CC BY 3.0 は、配るものに作者、出典、ライセンスの URL、
  変えたことを示すよう求める

## アプリの更新

設定の画面の「アプリの更新」の節で、利用者が「更新を確認」を押したときだけ確かめる。起動したときには確かめない。

- 更新をダウンロードしてインストールできるのは、配布版だけ。開発版（デスクトップの `:desktopApp:run` など、Android のデバッグビルド）では、
  確認はできるが、ダウンロードとインストールのボタンを押せない
- 更新情報と配布物は https でだけ取る

### デスクトップ

1. 「ダウンロード」で、配布物を `%LOCALAPPDATA%\CloudMarks\updates` の下のダウンロードごとのフォルダに受け取り、SHA-256 を更新情報と比べる
2. 「今すぐインストール」で、SHA-256 をもう一度比べてから、更新を適用する PowerShell のスクリプトに渡し、アプリを終了する。
   保存先への書き込みの最中は押せない
3. スクリプトは、アプリ（JVM とランチャー）の終了を待ってから、インストーラを人手なしで走らせる。結果を
   `%LOCALAPPDATA%\CloudMarks\logs\update-apply.log` に書き足し、成功したらアプリを起動し直す
4. 適用できなかったときは、次に起動したときに、メインの画面の帯で知らせる

- 古いダウンロードのフォルダは、起動したときと、更新を確認したときに消す

### Android

1. 「ダウンロード」で、APK をアプリのキャッシュの `updates` に受け取り、SHA-256 とパッケージ名を確かめる
2. 確かめたら、端末のインストーラに渡す。インストールするかは、利用者が端末のインストーラの画面で決める。初めてのときは、
   Cloud Marks からのインストールを許可するか（「この提供元のアプリを許可」）を、端末が利用者に尋ねる
3. 端末は、同じ署名の鍵の APK でなければ上書きの更新を断る

## 拡張の更新

### Chrome

- Chrome の拡張は、インストーラが入れたフォルダ（`<インストール先>\extension\chrome`）を、デベロッパーモードで読み込んで使う。
  拡張の ID は manifest の `key` で決まる
- アプリの更新でインストーラが入れ直すと、フォルダの中のファイルは新しいバージョンに替わる。ただし、Chrome を起動し直すか、
  拡張の画面で読み込み直すまで、Chrome は新しい manifest を読まないことがある。権限を足したバージョンを出すときは、拡張の画面で
  読み込み直すよう利用者に案内する

### Firefox

- 配布版の Firefox の拡張は、manifest の `update_url`
  （`https://github.com/xia-sava/cloud-marks-multiplatform/releases/download/extension-latest/updates.json`）から更新情報を読み、
  Firefox が自分で更新する。開発版の拡張は `update_url` を持たない
- `updates.json` は、gecko の ID ごとに、最新のバージョンの項目を 1 つだけ持つ。項目の `update_link` はバージョンごとのリリースの `.xpi`、
  `update_hash` はその SHA-256（`sha256:` に小文字の 16 進を続けたもの）。書くのは `apps/extension/scripts/firefox-updates.ts`
- Firefox の拡張は、アプリと別に更新される。新しい拡張と古いホスト、古い拡張と新しいホストが組み合わさる。Native Messaging の
  メッセージの互換は、`docs/native-messaging.md` の決まりに従う（ホストは表に無い欄を無視し、知らない `protocol` には `invalidRequest` を返す。
  拡張は知らない `code` を受けても `message` を出す）

## リリースの手順

アプリと拡張は、別のタグで別のワークフローがリリースする。

| タグ | ワークフロー | 公開するもの |
|---|---|---|
| `extension-vX.Y.Z` | `extension-release.yml` | Firefox の拡張（`extension-vX.Y.Z` と `extension-latest` のリリース） |
| `vX.Y.Z` | `release.yml` | インストーラと APK と更新情報（`vX.Y.Z` と `latest` のリリース） |

1. 拡張を変えたら、`apps/extension/package.json` の `version` を上げ、先に `extension-vX.Y.Z` を打って拡張のリリースを終える
2. そのあとで、アプリの `vX.Y.Z` を打つ。`release.yml` は `extension-latest` の `.xpi` をインストーラに入れる。タグを打った回は、
   `.xpi` を取れないとき、`.xpi` のバージョンがタグのコミットの `package.json` の `version` と違うときに止まる
3. 拡張を変えていなければ、`vX.Y.Z` だけを打つ

- タグは、前のリリースが終わってから打つ。同じワークフローで待てるのは 1 つだけで、待っているあいだに次が来ると、待っていた方は取り消される
- 公開するのは、公開（public）のリポジトリで、アプリと拡張が更新を問い合わせるリポジトリと同じときだけ。非公開のリポジトリでは、
  タグを打った回が止まる
- `release.yml` は、公開したあとに、アプリと同じ URL から更新情報と署名と配布物を取り直し、署名と SHA-256 を確かめる。アプリの読み手
  （`UpdateCheckerGitHubTest`）でも読めることを確かめる。`extension-release.yml` も、Firefox と同じ URL から `updates.json` と `.xpi` を
  取り直して確かめる

### 手動実行

- `release.yml` は、手動実行（workflow_dispatch）でも走る。インストーラと APK を組んで成果物として 3 日残すだけで、リリースには公開しない。
  非公開のリポジトリでも走る
- 手動で実行した回の `versionName` は `dev-<コミットの短い ID>`、`versionCode` はタグを打った回と同じ数え方。手動で実行した回の
  配布物を入れた端末には、同じコミットから出したリリースは届かない（`versionCode` が同じ）
- 手動で実行した回は、`extension-latest` の `.xpi` を取れないときや、そのバージョンが `package.json` と違うときも、警告を出して進む。
  取れなければ、Firefox の拡張を入れずに組む
- `extension-release.yml` は手動実行を持たない。AMO は同じバージョンに 2 度署名しないため

## 失敗したときのやり直し

### アプリのリリース（`release.yml`）

ジョブは `version`・`check` → `desktop`・`android` → `update-info` → `release` → `verify-published` の順に進む（`version` と `check` は並んで走る）。

- **`release` より前で止まったとき：** 何も公開していない。一時的な失敗なら、失敗したジョブを走らせ直す。直すコミットが要るなら、
  止まったタグを消し、直したコミットに同じバージョンのタグを打ってよい。止まったタグを残したまま新しいバージョンのタグを打つと、
  リリースノートは前の `v*` のタグから書くので、止まったタグから先のコミットだけが載る
- **`release` で止まったとき：** バージョンごとのリリースや `latest` の配布物だけが替わり、`latest.json` が古いままのことがある。
  そのあいだ、更新しようとしたアプリは、ダウンロードしたファイルの確認に失敗する。失敗したジョブを走らせ直す（同じ成果物を置き直し、
  確かめ直す）。ジョブのあいだで渡す成果物は 1 日で消える。消えていて、`latest` の `latest.json` がまだ前のバージョンなら、全体を走らせ直す
  （配布物を組み直し、更新情報も書き直す）。`latest.json` まで替わっていれば、全体を走らせ直しても `version` で止まるので、
  直したコミットで新しいバージョンを出す
- **`verify-published` で止まったとき：** `latest.json` まで替わっている。全体を走らせ直すと、`version` の「`versionCode` が増えていない」で
  止まる。一時的な失敗なら、`verify-published` だけを走らせ直す。公開したものが壊れていれば、直したコミットで新しいバージョンを出す

### 拡張のリリース（`extension-release.yml`）

ジョブは `version` → `sign` → `release` → `verify-published` の順に進む。

- **署名に出す前で止まったとき**（テスト、ビルド、`lint:firefox` など）**：** AMO には何も出していない。直してからタグを打ち直す。
  AMO に出していないバージョンなら、止まったタグを消して、直したコミットに同じバージョンのタグを打ってよい
- **署名の承認を待つ時間を過ぎたとき**（手での審査に回ったときなど）**：** AMO に出したバージョンは残り、承認されれば AMO の管理画面から
  署名済みの `.xpi` をダウンロードできる。AMO は同じバージョンに 2 度署名しないので、`sign` を走らせ直しても断られる。承認されたら、
  次の手順で手で公開する
  1. タグのコミットを checkout する（`updates.json` に書くバージョンは `package.json` から読む）
  2. 管理画面からダウンロードした `.xpi` の名前を `cloudmarks-firefox-X.Y.Z.xpi` にする。名前を変え忘れると、`updates.json` の
     `update_link` が 404 になる
  3. 載せる前に、`.xpi` の manifest を見る。バージョンが `X.Y.Z`、gecko の ID が `cloud-marks-multiplatform@sava.to`、`update_url` が
     「拡張の更新」の「Firefox」の URL であることと、AMO の署名（`META-INF/mozilla.rsa`）があることを確かめる
  4. `extension-vX.Y.Z` のリリースを `--verify-tag --latest=false` で作り、`.xpi` を載せる
  5. `extension-latest` のリリースに、`cloudmarks-firefox.xpi` の名前で載せる（置き換える）。`extension-latest` のリリースがまだ無ければ、
     `--prerelease --latest=false` で作る。説明のバージョンは `gh release edit` で今回のものに直す
  6. `npm run updates-json` で書いた `updates.json` を、最後に `extension-latest` のリリースに載せる（置き換える）
  7. 載せたあとに、`update_url` から `updates.json` を取り直し、今回のバージョンの項目の `update_link` の `.xpi` の SHA-256 が
     `update_hash` と合うことを確かめる
  8. 元のブランチへ戻る

  ```sh
  git checkout extension-vX.Y.Z
  work=$(mktemp -d)
  cp <ダウンロードした .xpi> "$work/cloudmarks-firefox-X.Y.Z.xpi"
  unzip -p "$work/cloudmarks-firefox-X.Y.Z.xpi" manifest.json |
    jq -r '.version, .browser_specific_settings.gecko.id, .browser_specific_settings.gecko.update_url'
  unzip -Z1 "$work/cloudmarks-firefox-X.Y.Z.xpi" | grep -xF 'META-INF/mozilla.rsa'
  gh release create extension-vX.Y.Z "$work/cloudmarks-firefox-X.Y.Z.xpi" --verify-tag \
    --title "Firefox extension X.Y.Z" --notes "<リリースノート>" --latest=false
  cp "$work/cloudmarks-firefox-X.Y.Z.xpi" "$work/cloudmarks-firefox.xpi"
  gh release upload extension-latest "$work/cloudmarks-firefox.xpi" --clobber
  # extension-latest のリリースがまだ無いときは、upload の代わりに次で作る
  # gh release create extension-latest "$work/cloudmarks-firefox.xpi" --title "Latest Firefox extension" --notes "<説明>" --prerelease --latest=false
  gh release edit extension-latest \
    --notes "Firefox の拡張の最新のバージョン（X.Y.Z）です。Firefox は、このリリースに置いた更新情報で新しいバージョンを確かめます。"
  (cd apps/extension && npm ci && npm run updates-json -- "$work/cloudmarks-firefox-X.Y.Z.xpi" "$work/updates.json")
  gh release upload extension-latest "$work/updates.json" --clobber
  curl -fsSL -o "$work/published-updates.json" \
    https://github.com/xia-sava/cloud-marks-multiplatform/releases/download/extension-latest/updates.json
  jq -r '.addons["cloud-marks-multiplatform@sava.to"].updates[] | select(.version == "X.Y.Z") | .update_link, .update_hash' \
    "$work/published-updates.json"
  curl -fsSL -o "$work/published.xpi" <上で出た update_link>
  echo "sha256:$(sha256sum "$work/published.xpi" | cut -d' ' -f1)"   # 上で出た update_hash と同じであること
  git switch -
  ```

  - 何も配っていないあいだ（`extension-latest` のリリースがまだ無いとき）は、手で公開する代わりに、`package.json` のバージョンを上げて
    タグを打ち直してもよい
- **AMO にバージョンを作ったあとで、ソースを送るところ（`--upload-source-code`）だけで失敗したとき：** 同じバージョンはもう AMO にあるので、
  `sign` を走らせ直しても断られる。AMO の管理画面から、そのバージョンにソースを添える。ソースは、`extension-release.yml` と同じく
  タグのコミットの `apps/extension` と `contracts/native-messaging` をまとめた zip
  （`git archive --format=zip --output <zip> extension-vX.Y.Z -- apps/extension contracts/native-messaging`）。
  署名が済んで、管理画面から署名済みの `.xpi` をダウンロードできるようになっていれば、上の手順で手で公開する
- **`release` か `verify-published` で止まったとき：** `sign` の成果物（署名済みの `.xpi` と `updates.json`）は 7 日残るので、失敗したジョブを
  走らせ直す。7 日を過ぎたら署名し直せないので、上の手順で手で公開する（`.xpi` は AMO の管理画面からダウンロードする）

## secrets

`release.yml` と `extension-release.yml` は、リポジトリの secrets を読む。secrets は、それを使うステップにだけ渡す。

| 名前 | 中身 | 使うジョブ |
|---|---|---|
| `UPDATE_INFO_SIGNING_KEY` | 更新情報に署名する ECDSA P-256 の秘密鍵（PEM）。対になる公開鍵が `UPDATE_INFO_PUBLIC_KEY` | `release.yml` の `update-info` |
| `ANDROID_KEYSTORE_BASE64` | APK に署名する鍵のキーストアを base64 にしたもの | `release.yml` の `android` |
| `ANDROID_KEYSTORE_PASSWORD` | キーストアのパスワード | `release.yml` の `android` |
| `ANDROID_KEY_PASSWORD` | キーストアの中の鍵のパスワード。鍵の別名（`cloudmarks`）は秘密ではないので、ワークフローに書く | `release.yml` の `android` |
| `AMO_JWT_ISSUER` | AMO の API の鍵の発行者（JWT issuer） | `extension-release.yml` の `sign` |
| `AMO_JWT_SECRET` | AMO の API の鍵の秘密（JWT secret） | `extension-release.yml` の `sign` |

- secrets が無ければ、そのジョブは足りない名前を挙げて止まる
- 署名の鍵を持つジョブは、リリースを書き換える権限を持たない。リリースを書き換える権限（`contents: write`）を持つのは、それぞれの
  `release` ジョブだけ
