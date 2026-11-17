import { MESSAGES } from "./messages"
import type { AddRequest, RequestTab } from "./protocol"

export type AddAction = "addFolder" | "addBookmarks"

/** 名前の欄のラベルと、入力できるか。 */
export interface NameField {
  label: string
  editable: boolean
}

/** 動作ごとに覚えておく名前の欄の値。 */
export interface NameValues {
  folderName: string
  bookmarkTitle: string
}

/** 「ブックマークを追加」でタブが 2 つ以上のとき、名前の欄は入力できない。 */
function usesEachTabTitle(action: AddAction, tabCount: number): boolean {
  return action === "addBookmarks" && tabCount >= 2
}

/** タブが 1 つならブックマーク、2 つ以上ならフォルダを既定にする。 */
export function defaultAction(tabCount: number): AddAction {
  return tabCount >= 2 ? "addFolder" : "addBookmarks"
}

function pad2(value: number): string {
  return String(value).padStart(2, "0")
}

/** ローカル時刻の `YYYY-MM-DD HH:mm:ss`。 */
export function defaultFolderName(now: Date): string {
  const date = `${now.getFullYear()}-${pad2(now.getMonth() + 1)}-${pad2(now.getDate())}`
  const time = `${pad2(now.getHours())}:${pad2(now.getMinutes())}:${pad2(now.getSeconds())}`
  return `${date} ${time}`
}

export function initialNameValues(now: Date, tabs: readonly RequestTab[]): NameValues {
  return {
    folderName: defaultFolderName(now),
    bookmarkTitle: tabs.length === 1 ? tabs[0].title : "",
  }
}

export function nameField(action: AddAction, tabCount: number): NameField {
  if (action === "addFolder") return { label: MESSAGES.folderNameLabel, editable: true }
  return { label: MESSAGES.bookmarkTitleLabel, editable: !usesEachTabTitle(action, tabCount) }
}

/** 欄に入れる文字列。入力できないときは、各タブのタイトルを使う旨。 */
export function nameFieldText(values: NameValues, action: AddAction, tabCount: number): string {
  if (action === "addFolder") return values.folderName
  if (usesEachTabTitle(action, tabCount)) return MESSAGES.eachTabTitle
  return values.bookmarkTitle
}

/** 欄の入力を、いまの動作の値として覚える。入力できない欄の文字列は覚えない。 */
export function rememberName(values: NameValues, action: AddAction, tabCount: number, input: string): NameValues {
  if (action === "addFolder") return { ...values, folderName: input }
  if (usesEachTabTitle(action, tabCount)) return values
  return { ...values, bookmarkTitle: input }
}

/** OK を押せるか。 */
export function canSubmit(action: AddAction, name: string, tabCount: number): boolean {
  if (tabCount === 0) return false
  if (action === "addFolder") return name !== ""
  return true
}

/** フォームの値とタブから、ホストへ送る要求を組み立てる。名前は入力のまま使う。 */
export function buildRequest(action: AddAction, name: string, tabs: readonly RequestTab[]): AddRequest {
  if (action === "addFolder") {
    return { protocol: 1, type: "addFolder", name, tabs: tabs.map(copyTab) }
  }
  if (tabs.length === 1) {
    return { protocol: 1, type: "addBookmarks", tabs: [{ url: tabs[0].url, title: name }] }
  }
  return { protocol: 1, type: "addBookmarks", tabs: tabs.map(copyTab) }
}

function copyTab(tab: RequestTab): RequestTab {
  return { url: tab.url, title: tab.title }
}
