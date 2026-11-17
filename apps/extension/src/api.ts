/** ポートの片側。runtime.connect と runtime.onConnect の両方で使う。 */
export interface ExtensionPort {
  name: string
  postMessage(message: unknown): void
  disconnect(): void
  onMessage: { addListener(listener: (message: unknown) => void): void }
  onDisconnect: { addListener(listener: () => void): void }
}

export interface BrowserTab {
  id?: number
  windowId: number
  index: number
  url?: string
  title?: string
}

/** tabs.onUpdated が渡す変化。使う欄だけを並べる。 */
export interface TabChangeInfo {
  status?: string
  url?: string
}

export interface TabCreateProperties {
  url: string
  active: boolean
  windowId?: number
  index?: number
}

/**
 * 拡張が使う WebExtension API のうち、Promise を返す形だけを並べたもの。
 * コールバックの形は使わない。
 */
export interface ExtensionApi {
  runtime: {
    sendNativeMessage(host: string, message: unknown): Promise<unknown>
    connect(connectInfo: { name: string }): ExtensionPort
    getURL(path: string): string
    onConnect: { addListener(listener: (port: ExtensionPort) => void): void }
    onInstalled: { addListener(listener: () => void): void }
    onStartup: { addListener(listener: () => void): void }
  }
  tabs: {
    query(queryInfo: { highlighted?: boolean; currentWindow?: boolean }): Promise<BrowserTab[]>
    get(tabId: number): Promise<BrowserTab>
    create(createProperties: TabCreateProperties): Promise<BrowserTab>
    update(tabId: number, updateProperties: { url: string }): Promise<BrowserTab | undefined>
    remove(tabId: number): Promise<void>
    onUpdated: {
      addListener(listener: (tabId: number, changeInfo: TabChangeInfo, tab: BrowserTab) => void): void
    }
    onRemoved: { addListener(listener: (tabId: number) => void): void }
  }
  storage: {
    session: {
      get(key: string): Promise<Record<string, unknown>>
      set(items: Record<string, unknown>): Promise<void>
      remove(key: string): Promise<void>
    }
  }
  action: {
    setBadgeText(details: { text: string }): Promise<void>
    setTitle(details: { title: string }): Promise<void>
  }
}

/**
 * API の名前空間を返す。Firefox は `browser`、Chrome は `chrome` を使う。
 * 呼ぶたびに引くので、テストでグローバルを差し替えられる。
 */
export function getApi(): ExtensionApi {
  const scope = globalThis as { browser?: unknown; chrome?: unknown }
  return (scope.browser ?? scope.chrome) as ExtensionApi
}
