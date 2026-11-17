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
| `add-folder.json` | フォルダを追加（2 つのタブ。空のタイトルを含む） |
| `add-bookmarks.json` | ブックマークを追加（1 つのタブ。タイトルは名前の欄の値） |
| `extension-present.json` | 拡張が入っていることの知らせ |
| `take-command.json` | コマンドを受け取る |
| `response-ok.json` | 成功の応答 |
| `response-command.json` | コマンドを受け取ったときの成功の応答（2 つのタブ。空のタイトルを含む）。`command` の中身は、アプリが書くコマンドのファイルの見本も兼ねる |
| `response-no-command.json` | コマンドが無いときの成功の応答 |
| `response-error.json` | 失敗の応答 |
