#!/usr/bin/env bash
# Kotlin のソースの定数（`const val <名前>[: 型] = <値>`）の値を、標準出力へ出す。
#
# アプリとリリースのワークフローの両方が使う値は、アプリのソースにだけ書き、ワークフローはここから読む。
# 読める値は、定義の行の = の後ろか、そこが空なら次の行に書いた、次のどちらか。
# - 文字列のリテラル（エスケープと文字列テンプレートを含まないもの）: 中身を出す
# - 整数の式: 整数のリテラル（末尾の L と桁区切りの _ を含む）と、同じファイルの整数の定数を * でつないだもの: 値を計算して出す
# 定義が 1 つに決まらないとき、値がこの形でないときは失敗する。
#
# 使い方: kotlin-constant.sh <Kotlin のファイル> <定数の名前>
set -euo pipefail
shopt -s inherit_errexit

# 定数をたどる深さの上限。定数が互いを参照し合っていても止まる。
max_depth=8

# 文字列のリテラル。中身に " と \ と $ を含まない。
string_literal='^"([^"\$]*)"$'

# 整数の式の項（整数のリテラルか定数の名前）と、項を * でつないだ式。
integer_term='([0-9][0-9_]*L?|[A-Za-z_][A-Za-z0-9_]*)'
integer_expression="^${integer_term}([[:space:]]*\\*[[:space:]]*${integer_term})*\$"

if [ "$#" -ne 2 ] || [ -z "$2" ]; then
  echo "使い方: $0 <Kotlin のファイル> <定数の名前>" >&2
  exit 2
fi
source_file=$1
if [ ! -f "$source_file" ]; then
  echo "ファイルが無い: ${source_file}" >&2
  exit 1
fi

# $1 の前後の空白を除く。
trim() {
  local text=$1
  text="${text#"${text%%[![:space:]]*}"}"
  printf '%s' "${text%"${text##*[![:space:]]}"}"
}

# 定数 $1 の定義の右辺を出す。行末のコメントは除く。
right_hand_side() {
  local name=$1
  local definition="const val ${name}([^A-Za-z0-9_]|\$)"
  local definitions
  definitions=$(grep -cE "$definition" "$source_file" || true)
  if [ "$definitions" != "1" ]; then
    echo "${source_file} に ${name} の定義が 1 つでない（${definitions} 個）" >&2
    return 1
  fi
  local value
  value=$(awk -v definition="$definition" '
    !found && $0 ~ definition {
      found = 1
      sub(/^[^=]*=/, "")
      if ($0 !~ /^[ \t\r]*$/) { print; exit }
      next
    }
    found { print; exit }
  ' "$source_file")
  value=$(sed -e 's#[[:space:]]//.*$##' <<<"$value")
  trim "$value"
}

# 定数 $1 の値を出す。$2 はたどった深さ。
constant_value() {
  local name=$1
  local depth=$2
  if [ "$depth" -gt "$max_depth" ]; then
    echo "${source_file} の ${name} をたどりきれない（${max_depth} 段を超える）" >&2
    return 1
  fi
  local value
  value=$(right_hand_side "$name")
  if [[ "$value" =~ $string_literal ]]; then
    printf '%s\n' "${BASH_REMATCH[1]}"
    return 0
  fi
  integer_value "$name" "$value" "$depth"
}

# 定数 $1 の右辺 $2 を、整数の式として計算して出す。$3 はたどった深さ。
integer_value() {
  local name=$1
  local expression=$2
  local depth=$3
  if ! [[ "$expression" =~ $integer_expression ]]; then
    echo "${source_file} の ${name} の値（${expression}）を読めない。文字列のリテラルか、整数と定数を * でつないだ式で書く" >&2
    return 1
  fi
  local -a terms
  IFS='*' read -r -a terms <<<"$expression"
  local product=1
  local term
  local factor
  for term in "${terms[@]}"; do
    term=$(trim "$term")
    if [[ "$term" =~ ^[0-9][0-9_]*L?$ ]]; then
      factor=${term//_/}
      factor=${factor%L}
    elif [[ "$term" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
      factor=$(constant_value "$term" $((depth + 1)))
      if ! [[ "$factor" =~ ^[0-9]+$ ]]; then
        echo "${source_file} の ${name} の式にある ${term} が整数でない（${factor}）" >&2
        return 1
      fi
    else
      echo "${source_file} の ${name} の式にある ${term} を読めない" >&2
      return 1
    fi
    product=$((product * 10#$factor))
  done
  printf '%s\n' "$product"
}

constant_value "$2" 0
