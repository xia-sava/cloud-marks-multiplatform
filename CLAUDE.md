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
- `check` は androidMain のコンパイルを含まない。Android 側のコンパイルまで見るなら
  `:shared:compileAndroidMain :androidApp:compileDebugKotlin` を足す
- **Gradle は up-to-date だと黙って走らせ直さない。** テストを壊して判別力を確かめた後などは `--rerun-tasks` を
  付け、`shared/build/test-results/*/TEST-*.xml` の `failures="0"` まで見る
- `apps/extension` は `npm test` と `npm run typecheck`
