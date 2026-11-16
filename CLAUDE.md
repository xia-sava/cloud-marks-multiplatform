# CLAUDE.md

@AGENTS.md

## 2つのアプリにまたがる変更

Native Messaging のメッセージの形を変えるときは、拡張と `apps/cm` の両方を1つの変更として扱う。
形の見本は `contracts/native-messaging/` にある。両方のテストがこれを読むため、形の食い違いはテストの失敗として現れる。

S3 上の形式を変えるときは、`contracts/storage/` の見本も同じ変更で直す。

## テストのエントリポイント

- **`./gradlew test` は `:shared` のテストを1つも走らせない。** `:shared` に `test` という名前のタスクが無く、
  実行されるのは `:androidApp:test` と `:desktopApp:test` だけ。コードは `:shared` にあるため、**緑に見えて
  何も確かめていない**
- **エントリポイントは `./gradlew check`。** `:shared:jvmTest` と `:shared:testAndroidHostTest` の両方を含む
- `check` は androidMain のコンパイルを含まない。Android 側のコンパイルまで見るなら
  `:shared:compileAndroidMain :androidApp:compileDebugKotlin` を足す
- **Gradle は up-to-date だと黙って走らせ直さない。** テストを壊して判別力を確かめた後などは `--rerun-tasks` を
  付け、`shared/build/test-results/*/TEST-*.xml` の `failures="0"` まで見る
- `apps/extension` は `npm test` と `npm run typecheck`
