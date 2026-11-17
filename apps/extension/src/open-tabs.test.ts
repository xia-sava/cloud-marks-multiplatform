import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import type { BrowserTab, ExtensionApi, TabCreateProperties } from "./api"
import { takeCommandMessage, type OpenTabsCommand } from "./command"
import { backgroundLoadCount, CommandHandler, LOAD_TIMEOUT_MS, planNewTabs } from "./open-tabs"
import { isPlaceholderUrl, readPlaceholderTab } from "./placeholder-page"
import { HOST_NAME } from "./protocol"
import { contract } from "./test-support"

const PAGE_URL = "chrome-extension://abcdefghijklmnopabcdefghijklmnop/placeholder.html"
const TOKEN = "0123456789abcdef0123456789abcdef"
const CALL_URL = `https://cmm.invalid/knock?token=${TOKEN}`

function realUrl(i: number): string {
  return `https://example.com/${i}`
}

function openTabsCommand(count: number, loadAtOnce: number, loadAhead: number): OpenTabsCommand {
  const tabs = Array.from({ length: count }, (_, i) => ({ url: realUrl(i), title: `T${i}` }))
  return { type: "openTabs", tabs, loadAtOnce, loadAhead }
}

/** タブを Map で持つ偽のブラウザ。tabs.onUpdated と tabs.onRemoved は、テストが handler へ直に渡す。 */
function fakeBrowser(respond: () => Promise<unknown>) {
  const tabs = new Map<number, BrowserTab>()
  let nextId = 100
  const hooks = { afterCreate: (_tab: BrowserTab) => {} }
  const mock = {
    runtime: {
      sendNativeMessage: vi.fn(async (_host: string, _message: unknown) => respond()),
      getURL: (file: string) => PAGE_URL.replace("placeholder.html", file),
    },
    tabs: {
      query: vi.fn(async (_queryInfo: object) => [...tabs.values()].map((tab) => ({ ...tab }))),
      get: vi.fn(async (tabId: number) => {
        const tab = tabs.get(tabId)
        if (tab === undefined) throw new Error(`No tab with id: ${tabId}.`)
        return { ...tab }
      }),
      create: vi.fn(async (properties: TabCreateProperties) => {
        const tab = {
          id: nextId++,
          windowId: properties.windowId ?? 1,
          index: properties.index ?? tabs.size,
          url: properties.url,
        }
        tabs.set(tab.id, tab)
        hooks.afterCreate(tab)
        return { ...tab }
      }),
      update: vi.fn(async (tabId: number, properties: { url: string }) => {
        const tab = tabs.get(tabId)
        if (tab === undefined) throw new Error(`No tab with id: ${tabId}.`)
        tab.url = properties.url
        return { ...tab }
      }),
      remove: vi.fn(async (tabId: number) => {
        if (!tabs.delete(tabId)) throw new Error(`No tab with id: ${tabId}.`)
      }),
    },
  }
  const api = mock as unknown as ExtensionApi
  return {
    api,
    mock,
    hooks,
    handler: new CommandHandler(api),
    addTab(tab: BrowserTab): BrowserTab {
      tabs.set(tab.id as number, tab)
      return { ...tab }
    },
    updatedIds: () => mock.tabs.update.mock.calls.map(([tabId]) => tabId),
  }
}

function withCommand(command: OpenTabsCommand) {
  return fakeBrowser(async () => ({ ok: true, command }))
}

const callTab: BrowserTab = { id: 1, windowId: 3, index: 2, url: CALL_URL }

/** tabs.onUpdated が渡すタブ。位置は確かめないので決め打ちにする。 */
function tabWith(id: number, url: string): BrowserTab {
  return { id, windowId: 3, index: 0, url }
}

/** 待っている Promise と、時間が 0 の timer を片付ける。 */
async function settle(): Promise<void> {
  await vi.advanceTimersByTimeAsync(0)
}

describe("planNewTabs", () => {
  const tabs = [
    { url: "https://example.com/", title: "Example" },
    { url: "https://example.org/", title: "" },
    { url: "https://example.net/", title: "Net" },
  ]

  it("呼び出しの URL のタブのウィンドウの、そのタブのすぐ後ろに順に並べ、先頭だけを表示する", () => {
    const plans = planNewTabs({ windowId: 7, index: 4 }, tabs, PAGE_URL)
    expect(plans.map((plan) => plan.properties.windowId)).toEqual([7, 7, 7])
    expect(plans.map((plan) => plan.properties.index)).toEqual([5, 6, 7])
    expect(plans.map((plan) => plan.properties.active)).toEqual([true, false, false])
  })

  it("どのタブも仮のページで作り、仮のページは本来の URL とタイトルを持つ", () => {
    const plans = planNewTabs({ windowId: 7, index: 4 }, tabs, PAGE_URL)
    expect(plans.map((plan) => plan.url)).toEqual(tabs.map((tab) => tab.url))
    for (const [i, plan] of plans.entries()) {
      expect(isPlaceholderUrl(plan.properties.url, PAGE_URL)).toBe(true)
      expect(readPlaceholderTab(new URL(plan.properties.url).search)).toEqual(tabs[i])
    }
  })

  it("呼び出しの URL のタブの位置が分からなければ、位置を指定しない", () => {
    const plans = planNewTabs(undefined, tabs, PAGE_URL)
    for (const plan of plans) {
      expect(plan.properties).not.toHaveProperty("windowId")
      expect(plan.properties).not.toHaveProperty("index")
    }
    expect(plans.map((plan) => plan.properties.active)).toEqual([true, false, false])
  })
})

describe("backgroundLoadCount", () => {
  it("loadAhead が 0 ならタブすべて、0 でなければ loadAhead", () => {
    expect(backgroundLoadCount(openTabsCommand(5, 3, 0))).toBe(5)
    expect(backgroundLoadCount(openTabsCommand(5, 3, 2))).toBe(2)
    expect(backgroundLoadCount(openTabsCommand(5, 3, 20))).toBe(20)
  })
})

describe("CommandHandler", () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.spyOn(console, "error").mockImplementation(() => {})
    vi.spyOn(console, "warn").mockImplementation(() => {})
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  describe("呼び出しの URL のタブを見つける", () => {
    it("呼び出しの URL でないタブは扱わない", async () => {
      const { handler, mock } = withCommand(openTabsCommand(1, 1, 1))
      await handler.tabUpdated(5, { url: "https://example.com/" }, tabWith(5, "https://example.com/"))
      await handler.handleTab({ id: 6, windowId: 1, index: 1 })
      expect(mock.runtime.sendNativeMessage).not.toHaveBeenCalled()
      expect(mock.tabs.remove).not.toHaveBeenCalled()
    })

    it("tabs.onUpdated で見つけたら、トークンを添えてコマンドを受け取る", async () => {
      const { handler, mock, addTab } = fakeBrowser(async () => contract("response-no-command.json"))
      const tab = addTab(callTab)
      await handler.tabUpdated(1, { status: "complete" }, tab)
      expect(mock.runtime.sendNativeMessage).toHaveBeenCalledWith(HOST_NAME, takeCommandMessage(TOKEN))
      expect(mock.tabs.remove).toHaveBeenCalledWith(1)
    })

    it("起動したときは、開いているタブから探す", async () => {
      const { handler, mock, addTab } = fakeBrowser(async () => contract("response-no-command.json"))
      addTab({ id: 5, windowId: 1, index: 0, url: "https://example.com/" })
      addTab(callTab)
      await handler.scanOpenTabs()
      expect(mock.tabs.query).toHaveBeenCalledWith({})
      expect(mock.runtime.sendNativeMessage).toHaveBeenCalledTimes(1)
      expect(mock.tabs.remove.mock.calls).toEqual([[1]])
    })

    it("同じトークンは 1 回だけ扱う", async () => {
      const { handler, mock, addTab } = fakeBrowser(async () => contract("response-no-command.json"))
      const tab = addTab(callTab)
      await Promise.all([
        handler.tabUpdated(1, { url: CALL_URL }, tab),
        handler.tabUpdated(1, { status: "complete" }, tab),
        handler.scanOpenTabs(),
      ])
      await handler.handleTab({ ...tab, id: 9 })
      expect(mock.runtime.sendNativeMessage).toHaveBeenCalledTimes(1)
      expect(mock.tabs.remove.mock.calls).toEqual([[1]])
    })
  })

  describe("コマンドを受け取れないとき", () => {
    it("トークンの形が合わなければ、ホストへ送らず、同じトークンでも見つけるたびにそのタブを閉じる", async () => {
      const { handler, mock, addTab } = withCommand(openTabsCommand(1, 1, 1))
      const invalidUrl = "https://cmm.invalid/knock?token=NOT-A-TOKEN"
      await handler.handleTab(addTab({ ...callTab, id: 1, url: invalidUrl }))
      await handler.handleTab(addTab({ ...callTab, id: 2, url: invalidUrl }))
      await handler.handleTab(addTab({ ...callTab, id: 3, url: "https://cmm.invalid/knock" }))
      await handler.handleTab(addTab({ ...callTab, id: 4, url: "https://cmm.invalid/knock?token=" }))
      expect(mock.runtime.sendNativeMessage).not.toHaveBeenCalled()
      expect(mock.tabs.create).not.toHaveBeenCalled()
      expect(mock.tabs.remove.mock.calls).toEqual([[1], [2], [3], [4]])
      expect(console.error).toHaveBeenCalled()
    })

    it.each([
      ["コマンドが無い", async () => contract("response-no-command.json")],
      ["失敗の応答", async () => contract("response-error.json")],
      ["形の合わない応答", async () => ({ ok: "yes" })],
      ["形の合わないコマンド", async () => ({ ok: true, command: { type: "openTabs", tabs: [] } })],
      [
        "送信の失敗",
        async () => {
          throw new Error("Specified native messaging host not found.")
        },
      ],
    ])("%s なら、タブを作らず、呼び出しの URL のタブを閉じて console に出す", async (_name, respond) => {
      const { handler, mock, addTab } = fakeBrowser(respond)
      await handler.handleTab(addTab(callTab))
      expect(mock.tabs.create).not.toHaveBeenCalled()
      expect(mock.tabs.update).not.toHaveBeenCalled()
      expect(mock.tabs.remove.mock.calls).toEqual([[1]])
      const logged = vi.mocked(console.error).mock.calls.length + vi.mocked(console.warn).mock.calls.length
      expect(logged).toBeGreaterThan(0)
    })

    it("呼び出しの URL のタブが先に閉じられていても、失敗を投げない", async () => {
      const { handler, mock } = fakeBrowser(async () => contract("response-no-command.json"))
      await expect(handler.handleTab(callTab)).resolves.toBeUndefined()
      expect(mock.tabs.remove).toHaveBeenCalledWith(1)
      expect(console.error).toHaveBeenCalled()
    })
  })

  describe("タブを開く", () => {
    it("見本のコマンドで、呼び出しの URL のタブのすぐ後ろに仮のページを順に作り、先頭を表示して、呼び出しの URL のタブを閉じる", async () => {
      const { handler, mock, addTab } = fakeBrowser(async () => contract("response-command.json"))
      await handler.handleTab(addTab(callTab))

      const created = mock.tabs.create.mock.calls.map(([properties]) => properties)
      expect(created.map(({ windowId, index, active }) => ({ windowId, index, active }))).toEqual([
        { windowId: 3, index: 3, active: true },
        { windowId: 3, index: 4, active: false },
      ])
      expect(created.map((properties) => readPlaceholderTab(new URL(properties.url).search))).toEqual([
        { url: "https://example.com/", title: "Example" },
        { url: "https://example.org/articles/1", title: "" },
      ])

      expect(mock.tabs.remove.mock.calls).toEqual([[1]])
      const lastCreate = Math.max(...mock.tabs.create.mock.invocationCallOrder)
      expect(mock.tabs.remove.mock.invocationCallOrder[0]).toBeGreaterThan(lastCreate)
    })

    it("呼び出しの URL のタブを調べられなければ、位置を指定せずに作る", async () => {
      const { handler, mock } = withCommand(openTabsCommand(2, 1, 5))
      await handler.handleTab(callTab)
      const created = mock.tabs.create.mock.calls.map(([properties]) => properties)
      expect(created).toHaveLength(2)
      for (const properties of created) {
        expect(properties).not.toHaveProperty("windowId")
        expect(properties).not.toHaveProperty("index")
      }
      expect(created.map((properties) => properties.active)).toEqual([true, false])
    })

    it("作れないタブがあっても、残りのタブを作って呼び出しの URL のタブを閉じる", async () => {
      const { handler, mock, addTab } = withCommand(openTabsCommand(3, 3, 5))
      const create = mock.tabs.create.getMockImplementation()!
      mock.tabs.create.mockImplementationOnce(async () => {
        throw new Error("No window with id: 3.")
      })
      mock.tabs.create.mockImplementation(create)
      await handler.handleTab(addTab(callTab))
      expect(mock.tabs.create).toHaveBeenCalledTimes(3)
      expect(mock.tabs.remove.mock.calls).toEqual([[1]])
      expect(console.error).toHaveBeenCalled()
    })
  })

  describe("少しずつ読み込む", () => {
    it("先頭から loadAtOnce 本に本来の URL を読み込ませる", async () => {
      const { handler, mock, addTab } = withCommand(openTabsCommand(5, 2, 5))
      await handler.handleTab(addTab(callTab))
      expect(mock.tabs.update.mock.calls).toEqual([
        [100, { url: realUrl(0) }],
        [101, { url: realUrl(1) }],
      ])
    })

    it("読み込みが終わる、タブが閉じられる、時間がたつ、のどれでも次の 1 本へ進む", async () => {
      const { handler, mock, addTab, updatedIds } = withCommand(openTabsCommand(5, 2, 5))
      await handler.handleTab(addTab(callTab))

      await handler.tabUpdated(100, { status: "complete" }, tabWith(100, realUrl(0)))
      expect(updatedIds()).toEqual([100, 101, 102])

      handler.tabRemoved(101)
      expect(updatedIds()).toEqual([100, 101, 102, 103])

      await vi.advanceTimersByTimeAsync(LOAD_TIMEOUT_MS - 1)
      expect(updatedIds()).toEqual([100, 101, 102, 103])
      await vi.advanceTimersByTimeAsync(1)
      expect(updatedIds()).toEqual([100, 101, 102, 103, 104])
      expect(mock.tabs.update).toHaveBeenLastCalledWith(104, { url: realUrl(4) })
    })

    it("読み込みが終わったタブの時間切れでは進まない", async () => {
      const { handler, addTab, updatedIds } = withCommand(openTabsCommand(3, 1, 5))
      await handler.handleTab(addTab(callTab))
      await handler.tabUpdated(100, { status: "complete" }, tabWith(100, realUrl(0)))
      expect(updatedIds()).toEqual([100, 101])

      await vi.advanceTimersByTimeAsync(LOAD_TIMEOUT_MS - 1)
      expect(updatedIds()).toEqual([100, 101])
    })

    // 作った直後のタブが持つ URL（Firefox の about:blank など）と、仮のページの URL。
    const urlsBeforeMoving: [string, (placeholder: string) => string][] = [
      ["仮のページの URL", (placeholder) => placeholder],
      ["about:blank", () => "about:blank"],
      ["空の URL", () => ""],
    ]

    it.each(urlsBeforeMoving)("読み込み中のタブに、%s のままの完了の知らせが来ても進まない", async (_name, urlOf) => {
      const { handler, mock, addTab, updatedIds } = withCommand(openTabsCommand(3, 1, 5))
      await handler.handleTab(addTab(callTab))
      const url = urlOf(mock.tabs.create.mock.calls[0][0].url)
      await handler.tabUpdated(100, { status: "complete" }, tabWith(100, url))
      expect(updatedIds()).toEqual([100])
    })

    it.each(urlsBeforeMoving)("待っているタブに、%s のままの知らせが来ても順番から外さない", async (_name, urlOf) => {
      const { handler, mock, addTab, updatedIds } = withCommand(openTabsCommand(3, 1, 5))
      await handler.handleTab(addTab(callTab))
      const url = urlOf(mock.tabs.create.mock.calls[1][0].url)
      await handler.tabUpdated(101, { status: "loading", url }, tabWith(101, url))
      await handler.tabUpdated(101, { status: "complete" }, tabWith(101, url))
      expect(updatedIds()).toEqual([100])

      await handler.tabUpdated(100, { status: "complete" }, tabWith(100, realUrl(0)))
      expect(updatedIds()).toEqual([100, 101])
    })

    it("順番が来る前に仮のページが自分で移ったタブは、済んだものとして読み込ませない", async () => {
      const { handler, addTab, updatedIds } = withCommand(openTabsCommand(3, 1, 5))
      await handler.handleTab(addTab(callTab))
      expect(updatedIds()).toEqual([100])

      await handler.tabUpdated(101, { status: "loading", url: realUrl(1) }, tabWith(101, realUrl(1)))
      expect(updatedIds()).toEqual([100])

      await handler.tabUpdated(100, { status: "complete" }, tabWith(100, realUrl(0)))
      expect(updatedIds()).toEqual([100, 102])
    })

    it("タブを作っている間に仮のページが自分で移ったタブも、読み込ませない", async () => {
      const { handler, hooks, addTab, updatedIds } = withCommand(openTabsCommand(3, 1, 5))
      hooks.afterCreate = (tab) => {
        if (tab.id !== 101) return
        void handler.tabUpdated(100, { url: realUrl(0) }, tabWith(100, realUrl(0)))
      }
      await handler.handleTab(addTab(callTab))
      expect(updatedIds()).toEqual([101])

      await handler.tabUpdated(101, { status: "complete" }, tabWith(101, realUrl(1)))
      expect(updatedIds()).toEqual([101, 102])
    })

    it("読み込み中のタブの URL が変わっても、読み込みが終わるまで次へ進まない", async () => {
      const { handler, addTab, updatedIds } = withCommand(openTabsCommand(3, 1, 5))
      await handler.handleTab(addTab(callTab))
      await handler.tabUpdated(100, { status: "loading", url: realUrl(0) }, tabWith(100, realUrl(0)))
      expect(updatedIds()).toEqual([100])
    })

    it("読み込ませられなかったタブは済んだものとして、次へ進む", async () => {
      const { handler, mock, addTab, updatedIds } = withCommand(openTabsCommand(3, 1, 5))
      mock.tabs.update.mockImplementationOnce(async () => {
        throw new Error("Illegal URL")
      })
      await handler.handleTab(addTab(callTab))
      await settle()
      expect(updatedIds()).toEqual([100, 101])
      expect(console.error).toHaveBeenCalled()
    })

    it("loadAhead が 2 なら、先頭の 2 本だけを読み込ませ、3 本目以降は読み込ませない", async () => {
      const { handler, mock, addTab, updatedIds } = withCommand(openTabsCommand(4, 1, 2))
      await handler.handleTab(addTab(callTab))
      await vi.advanceTimersByTimeAsync(LOAD_TIMEOUT_MS * 4)
      expect(mock.tabs.create).toHaveBeenCalledTimes(4)
      expect(updatedIds()).toEqual([100, 101])
      expect(vi.getTimerCount()).toBe(0)
    })

    it("loadAhead が 0 なら、すべてのタブを順に読み込ませる", async () => {
      const { handler, addTab, updatedIds } = withCommand(openTabsCommand(4, 2, 0))
      await handler.handleTab(addTab(callTab))
      expect(updatedIds()).toEqual([100, 101])
      await vi.advanceTimersByTimeAsync(LOAD_TIMEOUT_MS)
      expect(updatedIds()).toEqual([100, 101, 102, 103])
    })

    it("loadAhead がタブの数より多ければ、すべてのタブを順に読み込ませる", async () => {
      const { handler, addTab, updatedIds } = withCommand(openTabsCommand(3, 1, 20))
      await handler.handleTab(addTab(callTab))
      await vi.advanceTimersByTimeAsync(LOAD_TIMEOUT_MS * 3)
      expect(updatedIds()).toEqual([100, 101, 102])
    })

    it("全部済んだら、そのあとの知らせでは何も読み込ませない", async () => {
      const { handler, addTab, updatedIds } = withCommand(openTabsCommand(2, 2, 5))
      await handler.handleTab(addTab(callTab))
      handler.tabRemoved(100)
      handler.tabRemoved(101)
      expect(vi.getTimerCount()).toBe(0)

      await handler.tabUpdated(100, { status: "complete" }, tabWith(100, realUrl(0)))
      await vi.advanceTimersByTimeAsync(LOAD_TIMEOUT_MS)
      expect(updatedIds()).toEqual([100, 101])
      expect(vi.getTimerCount()).toBe(0)
    })

    it("読み込みが終わって全部済んだら、時間切れを待つ timer を残さない", async () => {
      const { handler, addTab, updatedIds } = withCommand(openTabsCommand(2, 1, 5))
      await handler.handleTab(addTab(callTab))
      await handler.tabUpdated(100, { status: "complete" }, tabWith(100, realUrl(0)))
      await handler.tabUpdated(101, { status: "complete" }, tabWith(101, realUrl(1)))
      expect(updatedIds()).toEqual([100, 101])
      expect(vi.getTimerCount()).toBe(0)
    })
  })
})
