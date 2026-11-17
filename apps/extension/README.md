# Cloud Marks ブラウザ拡張

Chrome と Firefox の両方で動く WebExtension（Manifest V3）。ツールバーのボタンを押すとポップアップが開き、現在のウィンドウで選択しているタブの URL とタイトルを、Native Messaging でデスクトップ側のホストへ渡す。S3 や保存形式のロジックは拡張に持たない。

## ビルドとテスト

```sh
npm install
npm run build      # dist/chrome と dist/firefox を作る
npm test           # vitest
npm run typecheck  # tsc --noEmit
```

## 読み込み方

先に `npm run build` を実行する。

- Chrome: `chrome://extensions` を開き、デベロッパーモードを有効にして「パッケージ化されていない拡張機能を読み込む」から `dist/chrome` を選ぶ。
- Firefox: `about:debugging#/runtime/this-firefox` を開き、「一時的なアドオンを読み込む」から `dist/firefox/manifest.json` を選ぶ。Firefox 115 以降が必要。
