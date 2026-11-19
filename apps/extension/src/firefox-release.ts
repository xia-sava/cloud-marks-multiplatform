/**
 * 配布版の Firefox の拡張を配る GitHub のリポジトリ。配布版の manifest の update_url と、更新情報（updates.json）の
 * update_link はこのリポジトリのリリースを指す。リリースのワークフロー（`.github/workflows/extension-release.yml`）は、
 * このリポジトリで動いたときだけ公開する。
 */
export const RELEASE_REPOSITORY = "xia-sava/cloud-marks-multiplatform"

/** 最新のバージョンの .xpi と更新情報を置く、固定のリリースのタグ。 */
export const LATEST_RELEASE_TAG = "extension-latest"

/** 更新情報のファイルの名前。 */
export const UPDATES_FILE = "updates.json"

/** バージョン `version` のリリースのタグ。 */
export function versionedReleaseTag(version: string): string {
  return `extension-v${version}`
}

/** バージョン `version` のリリースに置く、署名済みの .xpi のファイルの名前。 */
export function versionedXpiFile(version: string): string {
  return `cloudmarks-firefox-${version}.xpi`
}

/** リリース `tag` に置いたファイル `file` をダウンロードする URL。 */
export function releaseAssetUrl(tag: string, file: string): string {
  return `https://github.com/${RELEASE_REPOSITORY}/releases/download/${tag}/${file}`
}

/**
 * 配布版の Firefox の拡張の manifest の update_url。Firefox はここから更新情報を読み、新しいバージョンを確かめる。
 * 入れた拡張はこの URL を使い続けるので、配ったあとは変えられない。
 */
export const FIREFOX_UPDATE_URL = releaseAssetUrl(LATEST_RELEASE_TAG, UPDATES_FILE)
