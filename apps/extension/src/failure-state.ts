import type { ExtensionApi } from "./api"
import { FAILURE_BADGE_TEXT, MESSAGES } from "./messages"

// ビルドが埋め込む拡張の名前（`variantDefines`）。
declare const CMM_EXTENSION_NAME: string

/** ツールバーのボタンの、ふだんの説明。拡張の名前で、manifest の action.default_title と同じ。 */
const DEFAULT_ACTION_TITLE: string = CMM_EXTENSION_NAME

/** 閉じたあとの失敗の文言を `storage.session` に入れるキー。 */
export const FAILURE_STORAGE_KEY = "lastFailure"

/** ポップアップが閉じていて失敗したとき、ボタンにバッジ（`!`）を付け、文言を取っておく。 */
export async function recordFailure(api: ExtensionApi, message: string): Promise<void> {
  await api.storage.session.set({ [FAILURE_STORAGE_KEY]: message })
  await api.action.setBadgeText({ text: FAILURE_BADGE_TEXT })
  await api.action.setTitle({ title: MESSAGES.failureActionTitle })
}

/**
 * 取っておいた失敗の文言を返し、ボタンのバッジを外して説明を元に戻し、文言を消す。
 * 無ければ何も変えず undefined を返す。
 */
export async function takeFailure(api: ExtensionApi): Promise<string | undefined> {
  const stored = await api.storage.session.get(FAILURE_STORAGE_KEY)
  const message = stored[FAILURE_STORAGE_KEY]
  if (typeof message !== "string") return undefined
  await api.action.setBadgeText({ text: "" })
  await api.action.setTitle({ title: DEFAULT_ACTION_TITLE })
  await api.storage.session.remove(FAILURE_STORAGE_KEY)
  return message
}
