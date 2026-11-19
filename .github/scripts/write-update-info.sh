#!/usr/bin/env bash
# 配布物から更新情報（latest.json）を書き、標準出力へ出す。
#
# 形はアプリの読み方（apps/cm/shared/src/jvmShared/kotlin/to/sava/cloudmarksmultiplatform/update/UpdateInfo.kt の
# decodePlatformRelease）に合わせる。キーは desktop と android で、それぞれに次の 3 つを置く。
# 配布物の URL は載せない（アプリが決まった形で組む）。
# - versionCode: 更新の新旧の比較に使う 1 以上の整数。JSON の整数で書く（文字列にしない）
# - versionName: バージョンの文字列。空白だけのものは断る
# - sha256: 配布物の SHA-256。小文字の 16 進で 64 文字
#
# 配布物の大きさが、アプリが受け取る上限（ReleaseDownload.kt の MAX_RELEASE_BYTES）を超えるときは止める。
# アプリはそれより大きい配布物をダウンロードの途中で断るので、公開しても更新できない。
#
# 使い方: write-update-info.sh <versionCode> <versionName> <インストーラ（cloudmarks-setup.exe）> <APK（cloudmarks.apk）>
set -euo pipefail
shopt -s inherit_errexit

if [ "$#" -ne 4 ]; then
  echo "使い方: $0 <versionCode> <versionName> <インストーラ> <APK>" >&2
  exit 2
fi
version_code=$1
version_name=$2
installer=$3
apk=$4

script_directory=$(dirname "${BASH_SOURCE[0]}")
release_download_source="$script_directory/../../apps/cm/shared/src/jvmShared/kotlin/to/sava/cloudmarksmultiplatform/update/ReleaseDownload.kt"

if ! [[ "$version_code" =~ ^[1-9][0-9]*$ ]]; then
  echo "versionCode は 1 以上の整数で渡す（受け取った値: ${version_code}）" >&2
  exit 1
fi
if [ -z "${version_name//[[:space:]]/}" ]; then
  echo "versionName が空" >&2
  exit 1
fi

max_release_bytes=$(bash "$script_directory/kotlin-constant.sh" "$release_download_source" MAX_RELEASE_BYTES)

# 配布物 $1 の大きさを確かめ、SHA-256 を小文字の 16 進で出す。
release_sha256() {
  local file=$1
  local size
  local digest
  if [ ! -f "$file" ]; then
    echo "配布物が無い: ${file}" >&2
    return 1
  fi
  size=$(wc -c < "$file")
  size=${size//[[:space:]]/}
  if [ "$size" -gt "$max_release_bytes" ]; then
    echo "${file} の大きさ ${size} バイトが、アプリが受け取る上限の ${max_release_bytes} バイトを超える" >&2
    return 1
  fi
  digest=$(sha256sum "$file" | cut -d' ' -f1)
  if ! [[ "$digest" =~ ^[0-9a-f]{64}$ ]]; then
    echo "${file} の SHA-256 が小文字の 16 進で 64 文字にならない: ${digest}" >&2
    return 1
  fi
  printf '%s' "$digest"
}

desktop_sha256=$(release_sha256 "$installer")
android_sha256=$(release_sha256 "$apk")

jq -n \
  --argjson versionCode "$version_code" \
  --arg versionName "$version_name" \
  --arg desktopSha256 "$desktop_sha256" \
  --arg androidSha256 "$android_sha256" \
  '{
    desktop: { versionCode: $versionCode, versionName: $versionName, sha256: $desktopSha256 },
    android: { versionCode: $versionCode, versionName: $versionName, sha256: $androidSha256 }
  }'
