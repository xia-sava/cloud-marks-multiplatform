# Native Messaging の取り決め

ブラウザ拡張（`apps/extension`）が、選択したタブをデスクトップ側のホストへ渡すための取り決め。
拡張は S3 に触らず、保存形式のロジックも持たない。ホストが S3 へ書く。

## 経路

- ホストの名前は `to.sava.cloudmarksmultiplatform`
- 拡張は 1 回の追加ごとに `runtime.sendNativeMessage` でメッセージを 1 つ送る。ブラウザがホストを起動し、
  ホストはメッセージを 1 つ読み、応答を 1 つ返して終わる。デスクトップのアプリが起動していなくても追加できる
- メッセージは、4 バイトの長さ（ネイティブのバイト順。Windows ではリトルエンディアン）と、それに続く UTF-8 の JSON
- ホストの応答は 1 MB を超えない（Chrome の上限）
- ホストはデスクトップのアプリと同じ配布物に入り、同じ PC のアプリの設定（S3 の接続先と認証情報）を使う
- ホストは `docs/operations.md` の操作を、`docs/storage-format.md` の書き込みの手順で S3 へ書く。目録の見出しの `updatedBy` は `"host"`

## ホストの登録

インストーラが、ホストの manifest を置き、レジストリに登録する。

| ブラウザ | レジストリのキー | 受け付ける拡張 |
|---|---|---|
| Chrome | `HKCU\Software\Google\Chrome\NativeMessagingHosts\to.sava.cloudmarksmultiplatform` | `allowed_origins` に `chrome-extension://<拡張の ID>/` |
| Firefox | `HKCU\Software\Mozilla\NativeMessagingHosts\to.sava.cloudmarksmultiplatform` | `allowed_extensions` に `cloud-marks-multiplatform@sava.to` |

- Chrome の拡張の ID は配り方で変わる（manifest の `key` から決まるものと、ストアが振るもの）。`allowed_origins` には複数の ID を並べて書けるようにしておく

## 拡張から送るもの

```json
{"protocol":1,"type":"addFolder","name":"2026-11-16 21:30:15","tabs":[{"url":"https://example.com/","title":"Example"}]}
```

| 欄 | 型 | 内容 |
|---|---|---|
| `protocol` | 整数 | 取り決めの版。`1` |
| `type` | 文字列 | `"addFolder"`（フォルダを追加）か `"addBookmarks"`（ブックマークを追加） |
| `name` | 文字列 | `addFolder` のときだけ持つ。フォルダの名前。空でない |
| `tabs` | 配列 | タブの並び。1 つ以上 |
| `tabs[].url` | 文字列 | 空でない |
| `tabs[].title` | 文字列 | 空でもよい |

- `addFolder` は `docs/operations.md` の「フォルダとして追加」、`addBookmarks` は「ブックマークとして追加」になる
- 項目とフォルダの ID、追加した日時はホストが振る
- ホストは、`protocol` が知らない版のとき、`type` が知らないとき、欄が欠けているときや上の決まりに合わないときは、
  何も書かずに `invalidRequest` を返す

## ホストが返すもの

成功：

```json
{"ok":true}
```

失敗：

```json
{"ok":false,"error":{"code":"storageUnavailable","message":"S3 につながらない"}}
```

| 欄 | 型 | 内容 |
|---|---|---|
| `ok` | 真偽値 | 追加が S3 に書けたか |
| `error.code` | 文字列 | 失敗の種類。下の表 |
| `error.message` | 文字列 | 画面に出す文。ホストが作り、拡張はそのまま出す |

| `code` | 内容 |
|---|---|
| `notConfigured` | アプリで S3 の接続先や認証情報が設定されていない |
| `storageUnavailable` | S3 につながらない、または権限が無い |
| `updateRequired` | データの形式の版がホストより新しく、書けない（`docs/storage-format.md` の「形式の版」） |
| `dataBroken` | 目録かフォルダのファイルが壊れている、目録がまだ無い、または目録が消されている |
| `busy` | 書き込みの送り直しや適用し直しが、回数の上限を超えた |
| `unconfirmed` | 目録を書き換えたかどうかを確かめられなかった。書き換えが済んでいることもある |
| `invalidRequest` | 送られたメッセージが、この取り決めに合わない |
| `internal` | そのほかの失敗 |

拡張は、知らない `code` を受けても `message` を出す。

ホストは、目録を書き換えたかどうかを確かめられないとき、同じ操作（同じ ID）で書き込みをやり直す。届いていた分は
適用済みになり、二重にはならない。やり直しても確かめられなければ `unconfirmed` を返す。利用者がもう一度追加すると、
ホストが新しい ID を振るため二重になりうる。`message` はそのことを伝える。

## 拡張の振る舞い

ツールバーのボタンを押すと、ポップアップを出す。

- 対象は、いまのウィンドウで選択しているタブ（`tabs.query({highlighted: true, currentWindow: true})`）。URL の無いタブは除く
- 動作はラジオボタンで「フォルダを追加」「ブックマークを追加」から選ぶ。既定は、タブが 1 つなら「ブックマークを追加」、
  2 つ以上なら「フォルダを追加」
- 名前の欄を 1 つ持つ
  - 「フォルダを追加」：フォルダの名前。既定は、いまのローカル時刻で `YYYY-MM-DD HH:mm:ss`。空なら OK を押せない
  - 「ブックマークを追加」でタブが 1 つ：項目のタイトル。既定はタブのタイトル。送るときは、この値をそのタブの `title` にする
  - 「ブックマークを追加」でタブが 2 つ以上：入力できない。各タブのタイトルをそのまま使う
- ボタンは OK と Cancel。Enter で OK、Esc で Cancel
- OK で送り、成功したらポップアップを閉じる。失敗したら閉じずに `error.message` を出す
