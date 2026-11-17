import { getApi } from "./api"

export interface SelectedTab {
  url: string
  title: string
}

/**
 * 現在のウィンドウで選択（highlighted）されているタブの url と title を返す。
 * url を持たないタブは除く。title が無いタブの title は空文字にする。
 */
export async function selectedTabs(): Promise<SelectedTab[]> {
  const tabs = await getApi().tabs.query({ highlighted: true, currentWindow: true })
  const result: SelectedTab[] = []
  for (const tab of tabs) {
    if (!tab.url) continue
    result.push({ url: tab.url, title: tab.title ?? "" })
  }
  return result
}
