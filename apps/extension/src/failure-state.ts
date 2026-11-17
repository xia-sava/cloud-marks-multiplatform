import type { ExtensionApi } from "./api"
import { FAILURE_BADGE_TEXT, MESSAGES } from "./messages"

/** 閉じたあとの失敗の文言を `storage.session` に入れるキー。 */
export const FAILURE_STORAGE_KEY = "lastFailure"

/** ポップアップが閉じていて失敗したとき、ボタンに印を付け、文言を取っておく。 */
export async function recordFailure(api: ExtensionApi, message: string): Promise<void> {
  await api.storage.session.set({ [FAILURE_STORAGE_KEY]: message })
  await api.action.setBadgeText({ text: FAILURE_BADGE_TEXT })
  await api.action.setTitle({ title: MESSAGES.failureActionTitle })
}

/**
 * 取っておいた失敗の文言を返し、ボタンの印と説明を元に戻して、文言を消す。
 * 無ければ何も変えず undefined を返す。
 */
export async function takeFailure(api: ExtensionApi): Promise<string | undefined> {
  const stored = await api.storage.session.get(FAILURE_STORAGE_KEY)
  const message = stored[FAILURE_STORAGE_KEY]
  if (typeof message !== "string") return undefined
  await api.action.setBadgeText({ text: "" })
  await api.action.setTitle({ title: MESSAGES.defaultActionTitle })
  await api.storage.session.remove(FAILURE_STORAGE_KEY)
  return message
}
