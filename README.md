# Cloud Marks

ブラウザのブックマークとは別に、URL のリストを端末間でやり取りする。
S3 を正本とし、Android・Windows のアプリとブラウザ拡張から使う。

## 動作環境

| | 必要なもの |
|---|---|
| Windows のアプリ | Windows 10 または 11（x64） |
| Android のアプリ | Android 11 以降 |
| ブラウザ拡張 | Chrome、または Firefox 142 以降。Windows のアプリと同じ PC に入れる |
| 保存先 | AWS の S3 のバケット |

## インストール

配布物は GitHub の [`latest` のリリース](https://github.com/xia-sava/cloud-marks-multiplatform/releases/tag/latest)に置いてある。

### Windows

`cloudmarks-setup.exe` をダウンロードして実行する。インストーラにコードの署名をしていないので、Windows の SmartScreen が
「Windows によって PC が保護されました」と警告する。「詳細情報」を押し、「実行」で進める。

管理者権限は要らない。アプリは `%LOCALAPPDATA%\Programs\CloudMarks` に入り、スタートメニューに「Cloud Marks」ができる。
インストーラは、ブラウザ拡張とやり取りする仕組み（Native Messaging のホスト）を Chrome と Firefox に登録する。

Chrome の拡張は、ストアからではなく、インストールしたフォルダから読み込む。

1. Chrome で `chrome://extensions` を開き、右上のデベロッパーモードを有効にする
2. 「パッケージ化されていない拡張機能を読み込む」から `%LOCALAPPDATA%\Programs\CloudMarks\extension\chrome` を選ぶ

Firefox の拡張は、署名済みの `.xpi` から入れる。入れたあとは、Firefox が自分で更新する。

Firefox で `about:addons` を開き、歯車のメニューの「ファイルからアドオンをインストール」で
`%LOCALAPPDATA%\Programs\CloudMarks\extension\firefox\cloudmarks-firefox.xpi` を選ぶ。
[`extension-latest` のリリース](https://github.com/xia-sava/cloud-marks-multiplatform/releases/tag/extension-latest)の
`cloudmarks-firefox.xpi` を Firefox で開いても入れられる。

### Android

1. 端末で `cloudmarks.apk` をダウンロードして開く。ストアの外のアプリなので、ダウンロードに使ったブラウザなどからの
   インストールを許可するか（「この提供元のアプリを許可」）を、端末が尋ねる。Play プロテクトが警告を出すこともある
2. 端末のインストーラの画面で、インストールする

## 使いはじめる

アプリを初めて起動すると、設定の画面が開く。端末ごとに、データを置く S3 の接続先を入れる。

1. 「リージョン」「バケット名」「プレフィックス」「アクセスキー ID」「シークレットアクセスキー」を入れて、「保存」を押す。
   プレフィックスは空欄でもよい（バケットの直下に置く）
2. その場所にまだデータが無ければ、「保存しました。この保存先にはまだデータがありません。新しく作成しますか？」と出る。
   「作成」を押すと、新しく作る。2 台目からは、同じ接続先を入れれば、同じデータを読む

アクセスキーに要る S3 の権限は、[`docs/storage-format.md` の「権限」](docs/storage-format.md#権限)の表のとおり。
新しくデータを作るとき（その場所にまだデータが無いとき）は、`s3:ListBucket` も要る。無いと、データが無いことをアプリが見分けられず
（S3 が `404` ではなく `403` を返す）、「新しく作成しますか？」が出ない。
設定の画面の「以前の状態に戻す」を使うには、バケットのバージョニングを有効にしておく（同じ文書の「バージョニングとの関係」）。

ブラウザ拡張は、Windows のアプリの設定を使う。ブラウザのツールバーの Cloud Marks のボタンを押すと、選んでいるタブを、
フォルダかブックマークとして保存先に追加できる。

## 更新

アプリの更新は、設定の画面の「アプリの更新」から行う。「更新を確認」を押し、新しいバージョンがあれば「ダウンロード」を押す。

- Windows では、ダウンロードのあとの「今すぐインストール」で、Cloud Marks をいったん終了し、更新が終わったら起動し直す
- Android では、ダウンロードのあとに端末のインストーラの画面が開く。初めてのときは、Cloud Marks からのインストールを許可するか
  （「この提供元のアプリを許可」）を、端末が尋ねる
- Chrome の拡張のファイルは、アプリの更新で新しくなる。更新したら、`chrome://extensions` で Cloud Marks の再読み込みのボタンを押す
- Firefox の拡張は、アプリとは別に、Firefox が更新する

## アンインストール

- Windows では、Windows の設定の「アプリ」から Cloud Marks をアンインストールする。設定、認証情報、キャッシュ、ログは
  `%LOCALAPPDATA%\CloudMarks` に残る。要らなければ、このフォルダを消す
- アンインストールすると、Chrome が拡張を読み込んでいたフォルダも消える。Chrome の拡張は `chrome://extensions` で、Firefox の拡張は
  `about:addons` で外す
- S3 のデータは、どのアプリをアンインストールしても消えない

## 開発

```text
apps/
├─ cm/          Cloud Marks アプリ（Kotlin Multiplatform / Compose Multiplatform, Android + Windows Desktop）
└─ extension/   選択したタブをアプリへ渡すブラウザ拡張（TypeScript, Chrome + Firefox）
contracts/
├─ storage/             S3 上の形式の見本
└─ native-messaging/    拡張とアプリのあいだのメッセージの見本
docs/                   仕様
```

- `apps/cm` は IntelliJ IDEA または Android Studio で開く。ビルドとテストは `apps/cm/README.md`
- `apps/extension` は WebStorm で開く。ビルドとテストは `apps/extension/README.md`
- テストなどのコマンドはルートの `justfile` にまとめてある（`just test`）
- 仕様は `docs/` にある
  - `docs/storage-format.md` — S3 上の形式
  - `docs/operations.md` — データを変える操作
  - `docs/native-messaging.md` — 拡張とアプリのあいだのメッセージ
  - `docs/distribution.md` — 配布と更新、リリースの手順

## ライセンス

[MIT License](LICENSE)。アプリに入れている第三者のライブラリの表記と、アイコンの出典（Flaticon のアイコン、CC BY 3.0）は
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) にある。
