import type { CommandTab } from "./command"

/** 表示されているかを知らせるページ。`document` が当たる。 */
export interface PageVisibility {
  readonly visibilityState: string
  addEventListener(type: "visibilitychange", listener: () => void): void
  removeEventListener(type: "visibilitychange", listener: () => void): void
}

/** 仮のページの URL。本来の URL とタイトルをクエリに持つ。`pageUrl` は仮のページそのものの URL。 */
export function placeholderUrl(pageUrl: string, tab: CommandTab): string {
  const query = new URLSearchParams({ url: tab.url, title: tab.title })
  return `${pageUrl}?${query}`
}

/** 仮のページのクエリから本来のタブを読む。URL が無いか空なら undefined。 */
export function readPlaceholderTab(search: string): CommandTab | undefined {
  const query = new URLSearchParams(search)
  const url = query.get("url")
  if (url === null || url === "") return undefined
  return { url, title: query.get("title") ?? "" }
}

/** URL が仮のページのものか。クエリとフラグメントは問わない。 */
export function isPlaceholderUrl(url: string, pageUrl: string): boolean {
  const end = url.search(/[?#]/)
  return (end === -1 ? url : url.slice(0, end)) === pageUrl
}

// 仮のページで作ったタブが、仮のページを読み込む前に持つ URL。
// Firefox では作った直後のタブが about:blank で、その読み込みが止められると about:blank のまま読み込みが終わりうる。
const URLS_BEFORE_PLACEHOLDER = ["", "about:blank"]

/**
 * 仮のページで作ったタブが、仮のページから本来の URL などへ移ったか。
 * URL が分からない、空、about:blank、仮のページのものなら、移っていない。
 */
export function movedFromPlaceholder(url: string | undefined, pageUrl: string): boolean {
  if (url === undefined || URLS_BEFORE_PLACEHOLDER.includes(url)) return false
  return !isPlaceholderUrl(url, pageUrl)
}

/** 仮のページの `document.title`。本来のタイトルが空なら URL にする。 */
export function placeholderTitle(tab: CommandTab): string {
  return tab.title === "" ? tab.url : tab.title
}

/**
 * ページが表示されたときに `move` を 1 回だけ呼ぶ。
 * 呼んだ時点で表示されていれば、すぐに呼ぶ。
 */
export function moveWhenVisible(page: PageVisibility, move: () => void): void {
  if (page.visibilityState === "visible") {
    move()
    return
  }
  const listener = () => {
    if (page.visibilityState !== "visible") return
    page.removeEventListener("visibilitychange", listener)
    move()
  }
  page.addEventListener("visibilitychange", listener)
}
