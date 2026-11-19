---
name: release
description: Cloud Marks のバージョンを上げて配る。拡張のタグ（extension-vX.Y.Z）とアプリのタグ（vX.Y.Z）を打って GitHub Actions に配布物を作らせる手順、打つ前と走ったあとに確かめること、失敗したときの参照先。「バージョンを上げて」「リリースして」「配布して」と言われたときに使う。
---

# リリースする

タグを push すると、GitHub Actions が配布物を組んで GitHub Releases に公開する。main への push では検証（`cm.yml`・`extension.yml`）
だけが走り、配布物は出ない。仕様は `docs/distribution.md`。

| タグ | ワークフロー | 公開するもの |
|---|---|---|
| `extension-vX.Y.Z` | `.github/workflows/extension-release.yml` | Firefox の拡張（AMO で署名した `.xpi` と `updates.json`） |
| `vX.Y.Z` | `.github/workflows/release.yml` | インストーラ、APK、更新情報 |

**main とタグの push、ワークフローの走らせ直しや手動実行、リリースやタグの削除は、他人から見える操作。依頼済みでも、実行する直前に、
何をするかを書き出して利用者の確認を取る。**

## バージョンの決め方

- **`versionName`**：タグ名から先頭の `v` を除いたもの。人が決める。`MAJOR.MINOR.PATCH` の形に限る（接尾辞の付いたタグは通らない）
- **`versionCode`**：`apps/cm` と `apps/extension` に触れたコミットの数。ワークフローが数える。人は決めない
- **拡張のバージョン**：`apps/extension/package.json` の `version`。人が決め、`extension-vX.Y.Z` の `X.Y.Z` と同じにする

## 打つ前に確かめる

1. **main が push 済みで、`cm.yml` と `extension.yml` が緑であること。**

   ```sh
   gh run list --branch main --limit 5
   ```

2. **タグを打つコミットが `origin/main` の先頭で、作業ツリーがクリーンであること。** `git status -sb` の 1 行目が
   `## main...origin/main` だけ（ahead も behind も無い）で、ほかの行が無いこと。`git rev-parse` の 2 行が同じであること。

   ```sh
   git fetch origin
   git status -sb
   git rev-parse HEAD origin/main
   ```

3. **検証が通ること。**

   ```sh
   (cd apps/cm && ./gradlew check :shared:compileAndroidMain :androidApp:compileDebugKotlin)
   (cd apps/extension && npm run typecheck && npm test && npm run build && npm run lint:firefox)
   ```

4. **配布中のバージョンを確かめて、次のバージョンを決める。**

   ```sh
   gh release list --limit 10
   ```

5. **`versionCode` が、配布中の `latest.json` のものより大きくなること。** 等しいか小さければ、`release.yml` が止まる。
   `versionCode` は `apps/cm` と `apps/extension` に触れたコミットの数で、ワークフローや `contracts/` だけの直しは数えない。
   1 行目（このコミットの `versionCode`）が、2 行目（配布中のもの）より大きいことを確かめる。`curl` が 404 で失敗したら、
   まだ何も配っていない（最初のリリース）。

   ```sh
   git rev-list --count HEAD -- apps/cm apps/extension
   curl -fsSL https://github.com/xia-sava/cloud-marks-multiplatform/releases/download/latest/latest.json |
     jq '[.desktop.versionCode, .android.versionCode] | max'
   ```

6. **拡張を変えたかどうか。** 変えていれば、`package.json` の `version` を上げたコミットが要り、拡張のリリースを先に行う。

   ```sh
   git log --oneline "$(git describe --tags --abbrev=0 --match 'extension-v*')..HEAD" -- apps/extension
   ```

   拡張のリリースがまだ一度も無ければ、`git describe` は失敗する。そのときは最初の拡張のリリースから始める。

7. **依存を足したり、バージョンを上げたりしたら、`THIRD-PARTY-NOTICES.md` も直したこと**（`docs/distribution.md` の「第三者のライブラリの表記」）。

## 打つ

拡張を変えたときは、先に拡張のタグを打ち、`extension-release.yml` が終わってからアプリのタグを打つ。`release.yml` は、
`extension-latest` の `.xpi` のバージョンが、タグのコミットの `package.json` の `version` と違えば止まる。

```sh
git tag extension-vX.Y.Z
git push origin extension-vX.Y.Z
gh run list --workflow extension-release.yml --limit 1
gh run watch <run の ID> --exit-status
```

AMO の署名の承認を待つので、30 分ほどかかることがある。成功を確かめてから、アプリのタグを打つ。

```sh
git tag vX.Y.Z
git push origin vX.Y.Z
gh run list --workflow release.yml --limit 1
gh run watch <run の ID> --exit-status
```

拡張を変えていなければ、アプリのタグだけを打つ。どちらのワークフローも、前のリリースが終わってから次のタグを打つ。

## 走ったあとに確かめる

1. すべてのジョブが成功していること。最後の `verify-published` が、アプリや Firefox と同じ URL から取り直して確かめている。

   ```sh
   gh run view <run の ID>
   ```

2. リリースに配布物が載っていること。

   ```sh
   gh release view vX.Y.Z --json assets --jq '.assets[].name'
   gh release view latest --json assets --jq '.assets[] | [.name, .updatedAt] | @tsv'
   gh release view extension-latest --json assets --jq '.assets[] | [.name, .updatedAt] | @tsv'
   ```

   - `vX.Y.Z` に `cloudmarks-setup.exe` と `cloudmarks.apk`
   - `latest` の `cloudmarks-setup.exe`・`cloudmarks.apk`・`latest.json`・`latest.json.sig` が、今回の時刻に替わっている
   - 拡張も出したなら、`extension-vX.Y.Z` に `cloudmarks-firefox-X.Y.Z.xpi`、`extension-latest` の `cloudmarks-firefox.xpi` と
     `updates.json` が今回の時刻に替わっている

3. 配布中の更新情報のバージョンが、今回のものであること。

   ```sh
   curl -fsSL https://github.com/xia-sava/cloud-marks-multiplatform/releases/download/latest/latest.json
   ```

4. 実機で更新が届くこと。前のバージョンを入れた端末で、設定の画面の「アプリの更新」の「更新を確認」を押し、
   「新しいバージョン X.Y.Z があります。」と出ることを、利用者に確かめてもらう。

## 失敗したとき

`docs/distribution.md` の「失敗したときのやり直し」に従う。ジョブのどこで止まったかで、走らせ直せるか、新しいバージョンを出すかが変わる。
AMO の承認が時間切れになったときは、同じ節の手順で手で公開する。走らせ直し、タグの削除、手での公開も、実行する前に利用者の確認を取る。

## 配布せずに試す

`release.yml` を手動実行すると、インストーラと APK を組んで成果物として 3 日残すだけで、リリースには触れない。

```sh
gh workflow run release.yml --ref main
gh run list --workflow release.yml --limit 1
gh run download <run の ID> --name desktop --name android
```

- `versionName` は `dev-<コミットの短い ID>`。`versionCode` はタグを打った回と同じ数え方なので、手動で実行した回の配布物を入れた端末には、
  同じコミットから出したリリースは届かない
- `extension-release.yml` は手動実行を持たない

## 気をつけること

- **タグの打ち直しは効かない。** 同じコミットなら `versionCode` が変わらないので、そのコミットの `latest.json` を公開したあとなら、
  `release.yml` が止まる。配ったものを直すときは、直したコミットで新しいバージョンを出す
- **配ったバージョンを下げる手段は無い。** 問題が出たら、戻すのではなく、直したバージョンを出す
- **固定のリリース（`latest`・`extension-latest`）のタグと置き場所は動かさない。** 配ったアプリと拡張は、その URL から更新を取る。
  リポジトリの immutable releases も有効にしない
- **AMO は同じバージョンに 2 度署名しない。** 拡張のタグを打つ前に、`package.json` の `version` が新しいことを確かめる
- **非公開のリポジトリでは、タグを打った回は止まる。** 公開にしてからタグを打つ
