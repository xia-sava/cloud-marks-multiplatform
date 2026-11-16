# Cloud Marks

ブラウザのブックマークとは別に、URL のリストを端末間でやり取りする。
S3 を正本とし、Android・Windows のアプリとブラウザ拡張から使う。

## 構成

```text
apps/
├─ cm/          Cloud Marks アプリ（Kotlin Multiplatform / Compose Multiplatform, Android + Windows Desktop）
└─ extension/   選択したタブをアプリへ渡すブラウザ拡張（TypeScript, Chrome + Firefox）
contracts/
├─ storage/             S3 上の形式の見本
└─ native-messaging/    拡張とアプリのあいだのメッセージの見本
```

各アプリの詳細は `apps/cm/README.md`、`apps/extension/README.md` を参照。

## ローカル開発

- `apps/cm` は IntelliJ IDEA または Android Studio で開く
- `apps/extension` は WebStorm で開く
- テストなどのコマンドはルートの `justfile` にまとめてある（`just test`）
