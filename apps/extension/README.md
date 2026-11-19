# Cloud Marks ブラウザ拡張

Chrome と Firefox の両方で動く WebExtension（Manifest V3）。ツールバーのボタンを押すとポップアップが開き、現在のウィンドウで選択しているタブの URL とタイトルを、Native Messaging でデスクトップ側のホストへ渡す。また、デスクトップのアプリからのコマンドをホストを通じて受け取り、そのとおりにタブを開く（`docs/native-messaging.md` の「アプリからのコマンド（ブラウザで開く）」）。S3 や保存形式のロジックは拡張に持たない。

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
