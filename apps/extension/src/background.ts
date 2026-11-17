import { getApi } from "./api"
import { handleConnection } from "./connection"
import { CommandHandler } from "./open-tabs"
import { announcePresence } from "./presence"

const api = getApi()
const commands = new CommandHandler(api)

api.runtime.onConnect.addListener((port) => handleConnection(api, port))
api.runtime.onInstalled.addListener(() => void announcePresence(api))
api.runtime.onStartup.addListener(() => {
  void announcePresence(api)
  void commands.scanOpenTabs()
})
api.tabs.onUpdated.addListener((tabId, changeInfo, tab) => void commands.tabUpdated(tabId, changeInfo, tab))
api.tabs.onRemoved.addListener((tabId) => commands.tabRemoved(tabId))
