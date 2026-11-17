import type { BrowserTab, ExtensionApi, TabChangeInfo, TabCreateProperties } from "./api"
import {
  interpretTakeCommandResponse,
  isValidToken,
  readCallToken,
  takeCommandMessage,
  type CommandTab,
  type OpenTabsCommand,
} from "./command"
import { LoadQueue } from "./load-queue"
import { PLACEHOLDER_FILE } from "./manifest"
import { movedFromPlaceholder, placeholderUrl } from "./placeholder-page"
import { HOST_NAME } from "./protocol"

/**
 * 1 本の読み込みを待つ上限（ミリ秒）。過ぎたら次のタブへ進む。
 * Chrome はイベントも API の呼び出しも無い service worker を 30 秒で止めるので、それより短くする。
 */
export const LOAD_TIMEOUT_MS = 15_000

/** タブのウィンドウと、ウィンドウの中での位置。 */
export interface TabPosition {
  windowId: number
  index: number
}

/** 作るタブ 1 つ。`url` は本来の URL、`properties` は tabs.create に渡すもの。 */
export interface NewTabPlan {
  url: string
  properties: TabCreateProperties
}

/**
 * コマンドのタブを作る順番と位置を決める。どれも仮のページで作り、先頭のタブだけを表示する。
 * 呼び出しの URL のタブの位置が分かれば、そのウィンドウの、そのタブのすぐ後ろに順に並べる。
 * 分からなければ位置を指定しない。
 */
export function planNewTabs(
  callTab: TabPosition | undefined,
  tabs: readonly CommandTab[],
  pageUrl: string,
): NewTabPlan[] {
  return tabs.map((tab, i) => {
    const properties: TabCreateProperties = { url: placeholderUrl(pageUrl, tab), active: i === 0 }
    if (callTab !== undefined) {
      properties.windowId = callTab.windowId
      properties.index = callTab.index + 1 + i
    }
    return { url: tab.url, properties }
  })
}

/** バックグラウンドが本来の URL を読み込ませるタブの数。loadAhead が 0 ならすべて。 */
export function backgroundLoadCount(command: OpenTabsCommand): number {
  return command.loadAhead === 0 ? command.tabs.length : command.loadAhead
}

/**
 * コマンドで作ったタブに、先頭から loadAtOnce 本ずつ本来の URL を読み込ませる。
 * 1 本の読み込みが終わるか、LOAD_TIMEOUT_MS がたつか、タブが閉じられたら次へ進む。
 * 順番が来る前に仮のページが自分で移ったタブは、済んだものとして飛ばす。
 */
class BackgroundLoading {
  private readonly queue: LoadQueue
  private readonly urls = new Map<number, string>()
  private readonly timers = new Map<number, ReturnType<typeof setTimeout>>()
  private started = false

  constructor(
    private readonly api: ExtensionApi,
    private readonly pageUrl: string,
    loadAtOnce: number,
    private readonly onEmpty: (loading: BackgroundLoading) => void,
  ) {
    this.queue = new LoadQueue(loadAtOnce)
  }

  add(tabId: number, url: string): void {
    this.urls.set(tabId, url)
    this.queue.add(tabId)
  }

  start(): void {
    this.started = true
    this.advance()
  }

  tabUpdated(tabId: number, changeInfo: TabChangeInfo, tab: BrowserTab): void {
    if (!this.queue.has(tabId)) return
    // 仮のページから移る前の変化（作った直後の URL、仮のページの読み込みの終わり）は数えない。
    if (!movedFromPlaceholder(tab.url, this.pageUrl)) return
    if (changeInfo.status === "complete") {
      this.finish(tabId)
      return
    }
    if (changeInfo.url !== undefined) {
      this.queue.skip(tabId)
      this.advance()
    }
  }

  tabRemoved(tabId: number): void {
    this.finish(tabId)
  }

  private finish(tabId: number): void {
    if (!this.queue.has(tabId)) return
    clearTimeout(this.timers.get(tabId))
    this.timers.delete(tabId)
    this.queue.finish(tabId)
    this.advance()
  }

  private advance(): void {
    if (!this.started) return
    for (const tabId of this.queue.next()) this.load(tabId)
    if (this.queue.isEmpty) this.onEmpty(this)
  }

  private load(tabId: number): void {
    this.timers.set(tabId, setTimeout(() => this.finish(tabId), LOAD_TIMEOUT_MS))
    const url = this.urls.get(tabId) as string
    this.api.tabs.update(tabId, { url }).catch((error: unknown) => {
      console.error("タブに本来の URL を読み込ませられなかった", error)
      this.finish(tabId)
    })
  }
}

/**
 * 呼び出しの URL を開いたタブを見つけて、ホストからコマンドを受け取り、そのとおりにタブを開く。
 * 呼び出しの URL のタブは閉じる。同じトークンは 1 回だけ扱う。
 * トークンの形が合わなければ、ホストへ送らず、見つけるたびにそのタブを閉じる。
 */
export class CommandHandler {
  private readonly handledTokens = new Set<string>()
  private readonly loadings = new Set<BackgroundLoading>()
  private readonly pageUrl: string

  constructor(private readonly api: ExtensionApi) {
    this.pageUrl = api.runtime.getURL(PLACEHOLDER_FILE)
  }

  /** 開いているタブから呼び出しの URL を探して扱う。 */
  async scanOpenTabs(): Promise<void> {
    let tabs: BrowserTab[]
    try {
      tabs = await this.api.tabs.query({})
    } catch (error) {
      console.error("開いているタブを調べられなかった", error)
      return
    }
    await Promise.all(tabs.map((tab) => this.handleTab(tab)))
  }

  /** tabs.onUpdated を受ける。 */
  tabUpdated(tabId: number, changeInfo: TabChangeInfo, tab: BrowserTab): Promise<void> {
    for (const loading of [...this.loadings]) loading.tabUpdated(tabId, changeInfo, tab)
    return this.handleTab(tab)
  }

  /** tabs.onRemoved を受ける。 */
  tabRemoved(tabId: number): void {
    for (const loading of [...this.loadings]) loading.tabRemoved(tabId)
  }

  /** タブが呼び出しの URL を開いていれば、コマンドを受け取って開き、そのタブを閉じる。 */
  async handleTab(tab: BrowserTab): Promise<void> {
    if (tab.id === undefined || tab.url === undefined) return
    const token = readCallToken(tab.url)
    if (token === undefined) return
    if (!isValidToken(token)) {
      console.error("呼び出しの URL のトークンが取り決めに合わない")
      await this.closeCallTab(tab.id)
      return
    }
    if (this.handledTokens.has(token)) return
    this.handledTokens.add(token)

    const command = await this.takeCommand(token)
    if (command !== undefined) await this.openTabs(tab.id, command)
    await this.closeCallTab(tab.id)
  }

  private async takeCommand(token: string): Promise<OpenTabsCommand | undefined> {
    let response: unknown
    try {
      response = await this.api.runtime.sendNativeMessage(HOST_NAME, takeCommandMessage(token))
    } catch (error) {
      console.error("ホストからコマンドを受け取れなかった", error)
      return undefined
    }

    const result = interpretTakeCommandResponse(response)
    switch (result.kind) {
      case "command":
        return result.command
      case "noCommand":
        console.warn("ホストから受け取れるコマンドが無い（知らないトークン、受け取り済み、または中身が使えない）")
        return undefined
      case "invalidCommand":
        console.error("コマンドの形が取り決めに合わない")
        return undefined
      case "failed":
        console.error("ホストからコマンドを受け取れなかった", result.message)
        return undefined
    }
  }

  private async openTabs(callTabId: number, command: OpenTabsCommand): Promise<void> {
    const callTab = await this.api.tabs.get(callTabId).catch((error: unknown) => {
      console.error("呼び出しの URL のタブを調べられなかった", error)
      return undefined
    })
    const plans = planNewTabs(callTab, command.tabs, this.pageUrl)
    const loading = this.createLoading(command.loadAtOnce)
    const loadCount = backgroundLoadCount(command)
    let queued = 0

    for (const plan of plans) {
      try {
        const created = await this.api.tabs.create(plan.properties)
        if (created.id !== undefined && queued < loadCount) {
          loading.add(created.id, plan.url)
          queued++
        }
      } catch (error) {
        console.error("タブを作れなかった", error)
      }
    }
    loading.start()
  }

  private createLoading(loadAtOnce: number): BackgroundLoading {
    const loading = new BackgroundLoading(this.api, this.pageUrl, loadAtOnce, (done) => this.loadings.delete(done))
    this.loadings.add(loading)
    return loading
  }

  private async closeCallTab(tabId: number): Promise<void> {
    try {
      await this.api.tabs.remove(tabId)
    } catch (error) {
      console.error("呼び出しの URL のタブを閉じられなかった", error)
    }
  }
}
