# CLAUDE.md

@AGENTS.md

## 2つのアプリにまたがる変更

Native Messaging のメッセージの形を変えるときは、拡張と `apps/cm` の両方を1つの変更として扱う。
形の見本は `contracts/native-messaging/` にある。両方のテストがこれを読むため、形の食い違いはテストの失敗として現れる。

S3 上の形式を変えるときは、`contracts/storage/` の見本も同じ変更で直す。

## リリース

バージョンを上げて配るときは、スキル `release`（`.claude/skills/release/SKILL.md`）の手順に従う。

## テストのエントリポイント

- **`./gradlew test` は `:shared` のテストを1つも走らせない。** `:shared` に `test` という名前のタスクが無く、
  実行されるのは `:androidApp:test` と `:desktopApp:test` だけ。コードは `:shared` にあるため、**緑に見えて
  何も確かめていない**
- **エントリポイントは `./gradlew check`。** `:shared:jvmTest` と `:shared:testAndroidHostTest` の両方を含む
- 画面のテストは `:shared:jvmTest` の `ui/` にあり、`runComposeUiTest` で窓を出さずに描いて操作する。
  アプリを起動しなくても走らせてよい
- `check` は Android 側のコンパイル（`:shared:compileAndroidMain`・`:androidApp:compileDebugKotlin`）も含む
- **Gradle は up-to-date だと黙って走らせ直さない。** テストを壊して判別力を確かめた後などは `--rerun-tasks` を
  付け、`shared/build/test-results/*/TEST-*.xml` の `failures="0"` まで見る
- `apps/extension` は `npm test` と `npm run typecheck`。配る形まで見るなら `npm run build` と `npm run lint:firefox` を足す

## 確かめるときの注意

- **Gradle を 2 本同時に走らせない。** 同じプロジェクトで並べて走らせると、テストの出力のファイルがぶつかって
  `:shared:jvmTest` が `EOFException` などで落ちる。ロック待ちにならずに失敗するので、テストの失敗と見分けにくい。
  サブエージェントに検分を頼むあいだに自分でテストを走らせるなら、検分のほうでは Gradle を走らせない
- **`:desktopApp:run` が動いているあいだは、同じプロジェクトのほかの Gradle のビルドが待たされる。** ビルドとテストは
  アプリを閉じてから走らせる。開発版を 2 つ同時に起動したいとき（多重起動の防止を確かめるときなど）は、2 つ目を
  Gradle を通さず、`run` タスクと同じ Java・JVM の引数・クラスパスで直に起動する
- **ホストがアプリを起動する経路は、開発版では確かめられない。** 開発版のホストは起動しない作りなので、配布物を組んで
  入れるか、リリースを出して確かめる
