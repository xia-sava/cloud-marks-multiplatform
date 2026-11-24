# Cloud Marks ブラウザ拡張

Chrome と Firefox の両方で動く WebExtension（Manifest V3）。ツールバーのボタンを押すとポップアップが開き、現在のウィンドウで選択しているタブの URL とタイトルを、Native Messaging でデスクトップ側のホストへ渡す。また、デスクトップのアプリからのコマンドをホストを通じて受け取り、そのとおりにタブを開く（`docs/native-messaging.md` の「アプリからのコマンド（ブラウザで開く）」）。ポップアップの「Cloud Marks を開く」のボタンでは、ホストにデスクトップのアプリを開かせる（同じ文書の「アプリを開く」）。S3 や保存形式のロジックは拡張に持たない。

## ビルドとテスト

Node.js 26（CI と同じバージョン）と、それに付く npm で作る。

```sh
npm ci                # package-lock.json のとおりに依存を入れる
npm run build         # 配布版の dist/chrome と dist/firefox、開発版の dist/dev/chrome と dist/dev/firefox を作る
npm test              # vitest
npm run typecheck     # tsc --noEmit
npm run lint:firefox  # 配布版と開発版の Firefox の拡張の web-ext lint（先に npm run build）
```

`lint:firefox` は、AMO が自分で配る拡張（unlisted）に行う検査と同じく `--self-hosted` を付け、警告も失敗にする。

AMO の署名に出すのは、`npm run build` が作る `dist/firefox` の中身。署名に添えるソースは、リポジトリの `apps/extension` と、
テストと更新情報が読む `contracts/native-messaging` と、リポジトリ直下の `LICENSE` と `THIRD-PARTY-NOTICES.md` をまとめたもの
（`.github/workflows/extension-release.yml`）。
ビルドが `apps/extension` の外から読むのは、リポジトリ直下の `LICENSE` と `THIRD-PARTY-NOTICES.md` だけ。どちらも 4 つの拡張の直下に
写す（`scripts/build-extension.ts`）。無ければビルドが失敗する。

### Building from the source archive

For AMO reviewers. The source archive contains `apps/extension` (this directory), `contracts/native-messaging`,
which only the tests and the release scripts read, and `LICENSE` and `THIRD-PARTY-NOTICES.md` at the repository
root, which the build copies into each extension. The build bundles the TypeScript sources in `src/` with esbuild
(without minification) and copies the HTML, CSS, icons and these two files.

1. Install Node.js 26 (the version our CI uses) and the npm that comes with it.
2. Extract the archive, keeping its directory layout. The build reads `LICENSE` and `THIRD-PARTY-NOTICES.md`
   two levels above `apps/extension`.
3. In `apps/extension`, run `npm ci`, then `npm run build`.
4. The submitted Firefox extension is the content of `apps/extension/dist/firefox`.

## 配布版と開発版

`npm run build` は、配布版と開発版の両方を作る。開発版は名前（ツールバーのボタンの説明にも使う）が `Cloud Marks (dev)` で、
拡張の ID（Chrome の `key`、Firefox の gecko の ID）、Native Messaging のホストの名前、呼び出しの URL のホストが配布版と違う。
どれも `src/variants.ts` にあり、`contracts/native-messaging/identities.json` と同じ値（`docs/native-messaging.md` の「配布版と開発版」）。

ホストの名前、呼び出しの URL のホスト、拡張の名前は、ビルドが esbuild の `define` で埋め込む。テストには `vitest.config.ts` が
配布版の値を埋め込む。`define` を付けずにビルドした拡張は、読み込んだときに ReferenceError で止まる。
開発版の拡張は開発版のアプリとホストとだけ、配布版の拡張は配布版のアプリとホストとだけやり取りする。

配布版の Firefox の拡張だけが `update_url`（`src/firefox-release.ts`）を持ち、GitHub の `extension-latest` のリリースに置いた
更新情報（`updates.json`）で新しいバージョンを確かめる。更新情報は `npm run updates-json -- <署名済みの .xpi> <書き出す updates.json>` が書く。

## 読み込み方

先に `npm run build` を実行する。開発中は開発版を読み込む（開発版のホストは `apps/cm/README.md` の「開発版のホスト」）。

- Chrome: `chrome://extensions` を開き、デベロッパーモードを有効にして「パッケージ化されていない拡張機能を読み込む」から `dist/dev/chrome` を選ぶ。
- Firefox: `about:debugging#/runtime/this-firefox` を開き、「一時的なアドオンを読み込む」から `dist/dev/firefox/manifest.json` を選ぶ。Firefox 142 以降が必要。

配布版を読み込むときは、`dist/chrome` と `dist/firefox/manifest.json` を選ぶ。

## コードの地図

直したいことから、触るファイルを探すための案内。拡張の振る舞いとホストとのメッセージは `docs/native-messaging.md` が決める。
`src/` は拡張に入るコード、`scripts/` はビルドと配布のスクリプト。

### ファイル

| ファイル | 受け持ち |
|---|---|
| `src/popup.html`・`src/popup.css` | ポップアップの部品と見た目。文言は持たず、`popup.ts` が入れる |
| `src/popup.ts` | ポップアップの振る舞い。追加の要求はバックグラウンドへ渡し、フォルダの一覧（追加先の欄）とアプリを開く要求はバックグラウンドを通さずにホストへ送る |
| `src/form.ts` | ポップアップのフォームの決まり。既定の動作、名前の欄、OK を押せるか、ホストへ送る追加の要求の組み立て（追加先の `folder` を含む） |
| `src/folders.ts` | フォルダの一覧の要求（`listFolders`）を送る。追加先の欄の選択肢の組み立てと、選ぶものの決め方 |
| `src/last-folder.ts` | 前回の追加先（フォルダの ID と名前）を `storage.local` に覚え、読む |
| `src/selected-tabs.ts` | 追加の対象にする、いまのウィンドウで選択しているタブ |
| `src/background.ts` | バックグラウンドの入口。ポップアップからのポート、インストールとブラウザの起動、タブの変化を受ける |
| `src/connection.ts` | ポップアップからの追加の要求をホストへ送り、結果をポップアップへ返す。ポップアップが閉じていて失敗したら、`failure-state.ts` に残す。成功したら、追加先を `last-folder.ts` で覚えてから結果を返す（ポップアップが閉じていても） |
| `src/port-messages.ts` | ポップアップとバックグラウンドのあいだのポートの名前と、受け渡すメッセージの形（追加の要求と追加先、結果） |
| `src/failure-state.ts` | ポップアップが閉じたあとの失敗を、ツールバーのボタンの `!` と説明に残し、次に開いたときに取り出す |
| `src/protocol.ts` | ホストとやり取りするメッセージの型、ホストの名前、応答の解釈（`listFolders` の `folders` を含む）、送れなかったときの文 |
| `src/send.ts` | `runtime.sendNativeMessage` でホストへ送り、応答を（差し替えた解釈で）結果にする |
| `src/presence.ts` | 拡張が入っていることの知らせ（`extensionPresent`）を送る |
| `src/command.ts` | アプリからのコマンド。呼び出しの URL からトークンを読み、`takeCommand` の応答をコマンドとして読む |
| `src/open-tabs.ts` | 呼び出しの URL を開いたタブを見つけ、ホストからコマンドを受け取り、そのとおりにタブを開く |
| `src/load-queue.ts` | コマンドで開いたタブに、本来の URL を読み込ませる順番 |
| `src/placeholder.html`・`src/placeholder.css`・`src/placeholder.ts` | 仮のページ。表示されたら本来の URL へ移る |
| `src/placeholder-page.ts` | 仮のページの URL の組み立てと読み取り、仮のページから移ったかの判定 |
| `src/open-app.ts` | アプリを開く要求（`openApp`）を送る。`invalidRequest` は、アプリの更新を促す文にする |
| `src/messages.ts` | ポップアップとツールバーのボタンに出す文言 |
| `src/manifest.ts` | ブラウザ（Chrome・Firefox）と、配布版・開発版ごとの `manifest.json` の中身。権限もここ |
| `src/variants.ts` | 配布版と開発版の名前と ID、ビルドが埋め込む値（上の「配布版と開発版」） |
| `src/firefox-release.ts` | 配布版の Firefox の拡張の `update_url` と、更新情報と `.xpi` を置く GitHub のリリース |
| `src/extension-files.ts` | 拡張の中のファイルの名前とアイコンのパス。ビルド、manifest、拡張のコードで共通 |
| `src/api.ts` | 拡張が使う WebExtension の API の型（Promise を返す形だけ）と、`browser` と `chrome` の選び分け |
| `src/test-support.ts` | テストが `contracts/native-messaging/` の見本を読む |
| `scripts/build.ts` | `npm run build` の入口。`package.json` の `version` で拡張を作る |
| `scripts/build-extension.ts` | 配布版と開発版の、Chrome と Firefox の拡張を作る。esbuild でまとめ、HTML・CSS・アイコン（`icons/`）・ライセンスを写し、`manifest.json` を書く |
| `scripts/firefox-updates.ts` | Firefox の更新情報（`updates.json`）の中身。gecko の ID は `contracts/native-messaging/identities.json` から読む |
| `scripts/updates-json.ts` | `npm run updates-json` の入口。署名済みの `.xpi` から `updates.json` を書く |
| `src/*.test.ts`・`scripts/*.test.ts` | vitest のテスト。対象のファイルと同じ場所に置く |

### 直すときの入口

#### ポップアップの見た目や文言

- 部品は `src/popup.html`、見た目は `src/popup.css`、振る舞いは `src/popup.ts` と `src/form.ts`
- 文言は `src/messages.ts` にあり、`popup.ts` が部品に入れる。ホストが返す失敗の文は拡張では持たず、ホストが作る
  （`apps/cm` の `shared` の `host/HostErrors.kt`）
- ポップアップの振る舞いと、拡張が出す文の一部は `docs/native-messaging.md` の「拡張の振る舞い」に書いてある。変えたら、こちらも直す
- フォームの決まりのテストは `src/form.test.ts`
- 追加先の欄（選択肢の並べ方と、選ぶフォルダの決め方）の決まりは `src/folders.ts`、そのテストは `src/folders.test.ts`

#### ホストへ送るメッセージを足す

- まず `docs/native-messaging.md` の「拡張から送るもの」に種類を足し、`contracts/native-messaging/` に見本を足して、
  その `README.md` の表にも載せる
- 拡張では、`src/protocol.ts` にメッセージの型を足し、`src/send.ts` の送れる要求に加え、送る処理を置く
  （`src/presence.ts` や `src/open-app.ts`、応答の形が違うなら `src/folders.ts` に倣う）。テストで、送るメッセージが見本と同じ形であることを確かめる
- ホストの側も同じ変更で直す（`apps/cm/README.md` の「ホストが受けるメッセージを足す」）。形が食い違えば、拡張と `apps/cm` の
  どちらかのテストが失敗する
- 古いホストとの組み合わせに気を付ける。Firefox の拡張はアプリと別に更新されるので、新しい拡張が古いホストに送ることがある。
  種類を足すだけなら `protocol` は上げず、古いホストはその種類を `invalidRequest` で断る。拡張は `invalidRequest` を受けたら、
  アプリの更新を促す（`src/open-app.ts`）。リリースはアプリを先に出す（`docs/distribution.md` の「拡張の更新」「リリースの手順」）

#### manifest や権限を変える

- `manifest.json` はファイルとして置かず、`src/manifest.ts` が組み立て、`scripts/build-extension.ts` が 4 つの出力に書く。
  権限も `src/manifest.ts` にある
- 配布版と開発版で違う値（拡張の名前、Chrome の `key`、Firefox の gecko の ID）は `src/variants.ts` にあり、
  `contracts/native-messaging/identities.json` と同じにする（`src/manifest.test.ts` が比べる）
- Chrome の `key`、gecko の ID、`update_url` は、配ったら変えられない（`docs/distribution.md` の「配ったら変えられないもの」）
- 権限を足したバージョンを出すときは、Chrome の拡張を読み込み直すよう利用者に案内する（`docs/distribution.md` の「拡張の更新」）。
  Firefox の最小のバージョンを上げるときは、`updates.json` の項目にも足す（同じ文書の「バージョン」）
- 変えたら `npm run build` のあとに `npm run lint:firefox` を通す（上の「ビルドとテスト」）
