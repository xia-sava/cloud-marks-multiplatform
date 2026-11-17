import { interpretResponse, isRecord, type TakeCommandMessage } from "./protocol"

// 呼び出しの URL のオリジンとパス。呼び出しの URL は `https://cmm.invalid/knock?token=<token>` の形。
const CALL_URL_ORIGIN = "https://cmm.invalid"
const CALL_URL_PATH = "/knock"

const TOKEN_PATTERN = /^[0-9a-f]{32}$/

export interface CommandTab {
  url: string
  title: string
}

/** アプリからのコマンド「タブを開く」。 */
export interface OpenTabsCommand {
  type: "openTabs"
  tabs: CommandTab[]
  loadAtOnce: number
  loadAhead: number
}

/** takeCommand の応答を解釈した結果。 */
export type TakeCommandResult =
  | { kind: "command"; command: OpenTabsCommand }
  | { kind: "noCommand" }
  | { kind: "invalidCommand" }
  | { kind: "failed"; message: string }

/**
 * URL が呼び出しの URL なら、その `token` を返す。`token` が無ければ空文字を返す。
 * 呼び出しの URL でなければ undefined を返す。トークンの形はここでは確かめない。
 */
export function readCallToken(url: string): string | undefined {
  let parsed: URL
  try {
    parsed = new URL(url)
  } catch {
    return undefined
  }
  if (parsed.origin !== CALL_URL_ORIGIN || parsed.pathname !== CALL_URL_PATH) return undefined
  return parsed.searchParams.get("token") ?? ""
}

/** トークンが小文字の 16 進で 32 文字か。 */
export function isValidToken(token: string): boolean {
  return TOKEN_PATTERN.test(token)
}

export function takeCommandMessage(token: string): TakeCommandMessage {
  return { protocol: 1, type: "takeCommand", token }
}

function isPositiveInteger(value: unknown): value is number {
  return Number.isInteger(value) && (value as number) >= 1
}

function isNonNegativeInteger(value: unknown): value is number {
  return Number.isInteger(value) && (value as number) >= 0
}

function readCommandTab(value: unknown): CommandTab | undefined {
  if (!isRecord(value)) return undefined
  const { url, title } = value
  if (typeof url !== "string" || url === "" || typeof title !== "string") return undefined
  return { url, title }
}

/** コマンドの形に合えば、知っている欄だけを写したコマンドを返す。合わなければ undefined。 */
export function readOpenTabsCommand(value: unknown): OpenTabsCommand | undefined {
  if (!isRecord(value) || value.type !== "openTabs") return undefined
  const { tabs, loadAtOnce, loadAhead } = value
  if (!Array.isArray(tabs) || tabs.length === 0) return undefined
  if (!isPositiveInteger(loadAtOnce) || !isNonNegativeInteger(loadAhead)) return undefined

  const commandTabs: CommandTab[] = []
  for (const tab of tabs) {
    const commandTab = readCommandTab(tab)
    if (commandTab === undefined) return undefined
    commandTabs.push(commandTab)
  }
  return { type: "openTabs", tabs: commandTabs, loadAtOnce, loadAhead }
}

/**
 * takeCommand の応答を解釈する。
 * 失敗の応答と形が合わない応答は、`interpretResponse` と同じ文を持つ失敗にする。
 */
export function interpretTakeCommandResponse(response: unknown): TakeCommandResult {
  const outcome = interpretResponse(response)
  if (!outcome.ok) return { kind: "failed", message: outcome.message }

  const { command } = response as Record<string, unknown>
  if (command === null) return { kind: "noCommand" }
  const parsed = readOpenTabsCommand(command)
  if (parsed === undefined) return { kind: "invalidCommand" }
  return { kind: "command", command: parsed }
}
