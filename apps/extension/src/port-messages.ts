import { isFolderRef, type FolderRef } from "./last-folder"
import { isRecord, type AddRequest, type Outcome } from "./protocol"

/** ポップアップがバックグラウンドへつなぐポートの名前。 */
export const POPUP_PORT_NAME = "popup"

/**
 * ポップアップからバックグラウンドへ：この要求を送ってほしい。
 * `destination` は、要求の `folder` のフォルダの ID と名前。追加が成功したら前回の追加先として覚える。
 * 要求に `folder` を付けないときは持たない。
 */
export interface SubmitMessage {
  type: "submit"
  request: AddRequest
  destination?: FolderRef
}

/** バックグラウンドからポップアップへ：送った結果。 */
export interface ResultMessage {
  type: "result"
  outcome: Outcome
}

export function isSubmitMessage(value: unknown): value is SubmitMessage {
  if (!isRecord(value) || value.type !== "submit" || !isRecord(value.request)) return false
  return value.destination === undefined || isFolderRef(value.destination)
}

export function isResultMessage(value: unknown): value is ResultMessage {
  if (!isRecord(value) || value.type !== "result" || !isRecord(value.outcome)) return false
  const { ok, message, code } = value.outcome
  if (ok === true) return true
  return ok === false && typeof message === "string" && (code === undefined || typeof code === "string")
}
