#!/usr/bin/env bash
# 更新情報の署名を確かめる公開鍵を、アプリのソースから読んで標準出力へ出す。
#
# 公開鍵はアプリのソースの定数 UPDATE_INFO_PUBLIC_KEY（UpdateInfoSignature.kt）にだけ書き、
# リリースのワークフローはここから読む。定数が読めないとき（kotlin-constant.sh）、値が ECDSA P-256 の公開鍵
# （X.509 SubjectPublicKeyInfo の DER を base64 にしたもの）として読めないときは失敗する。
#
# 使い方: update-info-public-key.sh <UpdateInfoSignature.kt のパス>
set -euo pipefail
shopt -s inherit_errexit

if [ "$#" -ne 1 ]; then
  echo "使い方: $0 <UpdateInfoSignature.kt のパス>" >&2
  exit 2
fi
source_file=$1
constant_name=UPDATE_INFO_PUBLIC_KEY
script_directory=$(dirname "${BASH_SOURCE[0]}")

key=$(bash "$script_directory/kotlin-constant.sh" "$source_file" "$constant_name")
if ! [[ "$key" =~ ^[A-Za-z0-9+/]+=*$ ]]; then
  echo "${constant_name} が base64 の文字列でない" >&2
  exit 1
fi

if ! details=$(printf '%s' "$key" | base64 -d 2>/dev/null | openssl pkey -pubin -inform DER -noout -text 2>/dev/null); then
  echo "${constant_name} を公開鍵（X.509 SubjectPublicKeyInfo の DER の base64）として読めない" >&2
  exit 1
fi
if ! grep -q 'prime256v1' <<<"$details"; then
  echo "${constant_name} が ECDSA P-256 の公開鍵でない" >&2
  exit 1
fi

printf '%s\n' "$key"
