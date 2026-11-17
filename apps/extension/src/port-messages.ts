import { isRecord, type AddRequest, type Outcome } from "./protocol"

/** ポップアップがバックグラウンドへつなぐポートの名前。 */
export const POPUP_PORT_NAME = "popup"

/** ポップアップからバックグラウンドへ：この要求を送ってほしい。 */
export interface SubmitMessage {
  type: "submit"
  request: AddRequest
}

/** バックグラウンドからポップアップへ：送った結果。 */
export interface ResultMessage {
  type: "result"
  outcome: Outcome
}

export function isSubmitMessage(value: unknown): value is SubmitMessage {
  return isRecord(value) && value.type === "submit" && isRecord(value.request)
}

export function isResultMessage(value: unknown): value is ResultMessage {
  if (!isRecord(value) || value.type !== "result" || !isRecord(value.outcome)) return false
  const { ok, message } = value.outcome
  return ok === true || (ok === false && typeof message === "string")
}
