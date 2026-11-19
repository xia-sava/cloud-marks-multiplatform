import type { ExtensionApi } from "./api"
import { MESSAGES } from "./messages"
import { interpretResponse, isRecord, type HostErrorCode, type OpenAppMessage, type Outcome } from "./protocol"
import { sendRequest } from "./send"

/** 取り決めに合わない要求の `error.code`。`openApp` を知らない古いホストは、この要求にこれを返す。 */
const INVALID_REQUEST_CODE: HostErrorCode = "invalidRequest"

export function openAppMessage(): OpenAppMessage {
  return { protocol: 1, type: "openApp" }
}

function isInvalidRequestFailure(response: unknown): boolean {
  if (!isRecord(response) || response.ok !== false || !isRecord(response.error)) return false
  return response.error.code === INVALID_REQUEST_CODE
}

/**
 * `openApp` の応答を結果にする。
 * `invalidRequest` の失敗は、アプリが古くて `openApp` を知らないものとして、アプリの更新を促す文にする。
 * ほかは `interpretResponse` と同じく、`error.message` をそのまま使う。
 */
export function interpretOpenAppResponse(response: unknown): Outcome {
  if (isInvalidRequestFailure(response)) return { ok: false, message: MESSAGES.appUpdateRequired }
  return interpretResponse(response)
}

/** デスクトップのアプリを開くよう、ホストへ送る。 */
export async function openApp(api: ExtensionApi): Promise<Outcome> {
  return sendRequest(api, openAppMessage(), interpretOpenAppResponse)
}
