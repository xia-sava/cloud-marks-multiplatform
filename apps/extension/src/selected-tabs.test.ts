import { afterEach, describe, expect, it, vi } from "vitest"
import { selectedTabs } from "./selected-tabs"

function stubTabsQuery(tabs: Partial<chrome.tabs.Tab>[]) {
  const query = vi.fn().mockResolvedValue(tabs)
  vi.stubGlobal("chrome", { tabs: { query } })
  return query
}

describe("selectedTabs", () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it("現在のウィンドウの選択タブを問い合わせ、url の無いタブを除いて url と title を返す", async () => {
    const query = stubTabsQuery([
      { id: 1, url: "https://example.com/a", title: "A" },
      { id: 2, title: "url なし" },
      { id: 3, url: "https://example.com/c" },
    ])

    const result = await selectedTabs()

    expect(query).toHaveBeenCalledTimes(1)
    expect(query).toHaveBeenCalledWith({ highlighted: true, currentWindow: true })
    expect(result).toEqual([
      { url: "https://example.com/a", title: "A" },
      { url: "https://example.com/c", title: "" },
    ])
  })
})
