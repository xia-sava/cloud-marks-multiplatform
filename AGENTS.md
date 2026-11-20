# AGENTS.md

Cloud Marks（CM）の monorepo。URL のリストを端末間でやり取りする。ブラウザのブックマークとは同期しない。

## 最初に読むもの

- `docs/storage-format.md` — S3 上の形式（マニフェストと項目ファイル、書き込みの手順、古いファイルの削除）
- `docs/operations.md` — データを変える操作と、再適用するときの規則
- `docs/native-messaging.md` — 拡張とホストのあいだのメッセージ、ホストの登録、拡張のポップアップ
- `docs/distribution.md` — 配布と更新（置き場所、配ったら変えられないもの、更新情報の形式、リリースの手順、失敗したときのやり直し）

## 構成

- `apps/cm` — Kotlin Multiplatform / Compose Multiplatform（Android + Windows Desktop）。IDEA で開く。
  保存形式、S3 へのアクセス、追加・移動・削除などの操作は `shared` に置き、両方のアプリで共有する
- `apps/extension` — Chrome と Firefox の WebExtension（TypeScript）。WebStorm で開く。
  選択したタブを Native Messaging でデスクトップ側のホストへ渡し、アプリからのコマンドを受け取ってタブを開き、アプリを開く要求を送る。
  保存形式のロジックを持たない
- `contracts/storage` — S3 上の形式の見本。`apps/cm` のテストが読む
- `contracts/native-messaging` — 拡張とホストのあいだのメッセージの見本。拡張と `apps/cm` の両方のテストが読む。
  配布版と開発版の名前の一覧（`identities.json`）は、`apps/cm` の Gradle のビルドも読む

TypeScript と Gradle のビルドは統合しない。開発コマンドのエントリポイントはルートの `justfile` に置く。

どのファイルが何を受け持つかは、`apps/cm/README.md` と `apps/extension/README.md` の「コードの地図」にある。

## 作るときの決まり

- **desktop と Android で共有できる処理は `shared` に置く。** プラットフォームで違うのは、差し替え口（`expect`/`actual` や
  引数で渡す関数）の中だけにする。同じ処理を 2 か所に写さない
- **画面の文言は、ふつうのアプリの言い方で書く。** です・ます調で、内部の用語（マニフェスト、項目ファイル、ホストなど）を
  利用者に見せない。新しい文言は、アプリは `Messages.kt`、拡張は `messages.ts` に置く（アプリの画面には、描くファイルに
  直に書いた短い文言もある）。新しい文言は案を作って確認を取ってから入れる
- **画面に描く色は、すべてテーマから取る。** アプリと拡張はダークモードに対応している。明るいテーマで見えるだけでは足りない。
  Material でない部品（デスクトップの `VerticalScrollbar` など）は既定の色が決め打ちなので、テーマの色を渡す
- **用語は `docs/` の呼び名を使う。** コード、コメント、文書、コミットメッセージのどれでも同じ。同じものを別の言葉で呼ばない
- **コミットメッセージの件名は、日本語の命令形で簡潔に書く**

## 同期の前提

- S3 が正本。Android・デスクトップ・ホストは対等に S3 を読み書きする。中継するサーバーは置かない
- 書き手どうしの排他は S3 の条件付き書き込み（`If-Match` / `If-None-Match`）で行う

## 扱いの注意

- S3 の認証情報を拡張に持たせない。拡張は S3 にアクセスしない
- 認証情報を平文で置かない。Android は端末のバックアップと移行から設定を除く
- S3 の一覧（ListObjectsV2）は必ずページングで最後まで読む
- S3 への読み書きの失敗を握り潰さない。失敗したら、成功したものとして扱わず、失敗として伝える
- S3 への接続の確認に `ListBuckets` を使わない。対象のバケットとプレフィックスへの操作だけで確かめる

## リリース

手順は `.claude/skills/release/SKILL.md` にある。タグの push はリリースを公開する操作なので、打つ前に利用者の確認を取る。
