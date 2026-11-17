import { describe, expect, it, vi } from "vitest"
import {
  isPlaceholderUrl,
  movedFromPlaceholder,
  moveWhenVisible,
  placeholderTitle,
  placeholderUrl,
  readPlaceholderTab,
  type PageVisibility,
} from "./placeholder-page"

const PAGE_URL = "chrome-extension://abcdefghijklmnopabcdefghijklmnop/placeholder.html"

function fakePage(visibilityState: string) {
  const listeners = new Set<() => void>()
  const page = {
    visibilityState,
    addEventListener: (_type: string, listener: () => void) => void listeners.add(listener),
    removeEventListener: (_type: string, listener: () => void) => void listeners.delete(listener),
  }
  return {
    page: page as PageVisibility,
    listeners,
    change(state: string) {
      page.visibilityState = state
      for (const listener of [...listeners]) listener()
    },
  }
}

describe("placeholderUrl と readPlaceholderTab", () => {
  it("組み立てた URL のクエリから、本来の URL とタイトルを読み戻せる", () => {
    for (const tab of [
      { url: "https://example.com/", title: "Example" },
      { url: "https://example.org/a?b=c&d=e#f", title: "" },
      { url: "https://example.net/パス?q=日本語 & 記号+%20", title: "タイトル & = ? # % +" },
    ]) {
      const built = new URL(placeholderUrl(PAGE_URL, tab))
      expect(built.href.startsWith(`${PAGE_URL}?`)).toBe(true)
      expect(readPlaceholderTab(built.search)).toEqual(tab)
    }
  })

  it("クエリは url と title だけを持つ", () => {
    const built = new URL(placeholderUrl(PAGE_URL, { url: "https://example.com/", title: "A" }))
    expect([...built.searchParams.keys()]).toEqual(["url", "title"])
  })

  it("url が無いか空なら undefined、title が無ければ空文字", () => {
    expect(readPlaceholderTab("")).toBeUndefined()
    expect(readPlaceholderTab("?title=A")).toBeUndefined()
    expect(readPlaceholderTab("?url=&title=A")).toBeUndefined()
    expect(readPlaceholderTab("?url=https%3A%2F%2Fexample.com%2F")).toEqual({ url: "https://example.com/", title: "" })
  })
})

describe("isPlaceholderUrl", () => {
  it("仮のページの URL なら、クエリとフラグメントによらず true", () => {
    expect(isPlaceholderUrl(PAGE_URL, PAGE_URL)).toBe(true)
    expect(isPlaceholderUrl(placeholderUrl(PAGE_URL, { url: "https://example.com/", title: "A" }), PAGE_URL)).toBe(true)
    expect(isPlaceholderUrl(`${PAGE_URL}#x`, PAGE_URL)).toBe(true)
  })

  it("ほかの URL なら false", () => {
    expect(isPlaceholderUrl("https://example.com/", PAGE_URL)).toBe(false)
    expect(isPlaceholderUrl(`${PAGE_URL}x?url=a`, PAGE_URL)).toBe(false)
    expect(isPlaceholderUrl(PAGE_URL.replace("placeholder", "popup"), PAGE_URL)).toBe(false)
    expect(isPlaceholderUrl("https://example.com/?u=" + PAGE_URL, PAGE_URL)).toBe(false)
  })
})

describe("movedFromPlaceholder", () => {
  it("URL が分からない、空、about:blank、仮のページのものなら、移っていない", () => {
    const placeholder = placeholderUrl(PAGE_URL, { url: "https://example.com/", title: "A" })
    for (const url of [undefined, "", "about:blank", PAGE_URL, placeholder]) {
      expect(movedFromPlaceholder(url, PAGE_URL), String(url)).toBe(false)
    }
  })

  it("ほかの URL なら、移った", () => {
    for (const url of ["https://example.com/", "about:reader?url=https://example.com/"]) {
      expect(movedFromPlaceholder(url, PAGE_URL), url).toBe(true)
    }
  })
})

describe("placeholderTitle", () => {
  it("本来のタイトルを使い、空なら URL にする", () => {
    expect(placeholderTitle({ url: "https://example.com/", title: "Example" })).toBe("Example")
    expect(placeholderTitle({ url: "https://example.com/", title: "" })).toBe("https://example.com/")
  })
})

describe("moveWhenVisible", () => {
  it("表示されていれば、すぐに移り、visibilitychange を待たない", () => {
    const { page, listeners } = fakePage("visible")
    const move = vi.fn()
    moveWhenVisible(page, move)
    expect(move).toHaveBeenCalledTimes(1)
    expect(listeners.size).toBe(0)
  })

  it("隠れていれば、表示されたときに 1 回だけ移る", () => {
    const fake = fakePage("hidden")
    const move = vi.fn()
    moveWhenVisible(fake.page, move)
    expect(move).not.toHaveBeenCalled()

    fake.change("hidden")
    expect(move).not.toHaveBeenCalled()

    fake.change("visible")
    expect(move).toHaveBeenCalledTimes(1)
    expect(fake.listeners.size).toBe(0)

    fake.change("hidden")
    fake.change("visible")
    expect(move).toHaveBeenCalledTimes(1)
  })
})
