# contracts/native-messaging

ブラウザ拡張（`apps/extension`）とデスクトップ側のホスト（`apps/cm`）のあいだで Native Messaging により
やり取りするメッセージ（`docs/native-messaging.md`）の見本を置く。

- 拡張のテストは、送るメッセージがこの見本と同じ形であること、受け取った応答を解釈できることを確かめる
- `apps/cm` のテストは、この見本を受け取って解釈できること、返すメッセージがこの見本と同じ形であることを確かめる

形を変えるときは、拡張と `apps/cm` の両方を同じ変更で直す。

## 見本

見本は読みやすさのため整形している。実際のメッセージは、整形の有無によらず同じ JSON として扱う。

| ファイル | 内容 |
|---|---|
| `add-folder.json` | フォルダを追加（追加先を指定。2 つのタブ。空のタイトルを含む） |
| `add-bookmarks.json` | ブックマークを追加（追加先を指定しない。1 つのタブ。タイトルは名前の欄の値） |
| `list-folders.json` | フォルダの一覧を受け取る |
| `extension-present.json` | 拡張が入っていることの知らせ |
| `take-command.json` | コマンドを受け取る |
| `open-app.json` | アプリを開く |
| `response-ok.json` | 成功の応答 |
| `response-command.json` | コマンドを受け取ったときの成功の応答（2 つのタブ。空のタイトルを含む）。`command` の中身は、アプリが書くコマンドのファイルの見本も兼ねる |
| `response-no-command.json` | コマンドが無いときの成功の応答 |
| `response-folders.json` | フォルダの一覧の成功の応答（ルートフォルダと、2 段の深さのフォルダ） |
| `response-error.json` | 失敗の応答 |
| `response-folder-not-found.json` | 追加先のフォルダが無いときの失敗の応答。拡張はこの `code` で一覧を読み直す |

## 配布版と開発版の名前

`identities.json` は、配布版（`distributed`）と開発版（`dev`）のそれぞれについて、ホストの名前、拡張の ID と `key`、
呼び出しの URL のホストを持つ（`docs/native-messaging.md` の「配布版と開発版」）。メッセージの見本ではない。

| 欄 | 内容 |
|---|---|
| `hostName` | Native Messaging のホストの名前 |
| `chromeExtensionId` | Chrome の拡張の ID。`chromeKey` から決まる |
| `chromeKey` | Chrome の拡張の `manifest.json` の `key`（公開鍵の DER を base64 にしたもの） |
| `geckoId` | Firefox の拡張の ID（`browser_specific_settings.gecko.id`） |
| `callUrlHost` | 呼び出しの URL のホスト |

- 拡張のテストは、配布版と開発版の名前と manifest がこのファイルと一致すること、配布版の拡張には配布版の、開発版の拡張には
  開発版の名前だけをビルドが埋め込むことを確かめる
- `apps/cm` のテストは、アプリが使う呼び出しの URL がこのファイルと一致することを確かめる。ホストを登録する JSON は、
  Gradle がこのファイルを読んで書く
