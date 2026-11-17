/** ポートの片側。runtime.connect と runtime.onConnect の両方で使う。 */
export interface ExtensionPort {
  name: string
  postMessage(message: unknown): void
  disconnect(): void
  onMessage: { addListener(listener: (message: unknown) => void): void }
  onDisconnect: { addListener(listener: () => void): void }
}

export interface QueriedTab {
  url?: string
  title?: string
}

/**
 * 拡張が使う WebExtension API のうち、Promise を返す形だけを並べたもの。
 * コールバックの形は使わない。
 */
export interface ExtensionApi {
  runtime: {
    sendNativeMessage(host: string, message: unknown): Promise<unknown>
    connect(connectInfo: { name: string }): ExtensionPort
    onConnect: { addListener(listener: (port: ExtensionPort) => void): void }
  }
  tabs: {
    query(queryInfo: { highlighted: boolean; currentWindow: boolean }): Promise<QueriedTab[]>
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
