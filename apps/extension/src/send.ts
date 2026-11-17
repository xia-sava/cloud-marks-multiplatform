import type { ExtensionApi } from "./api"
import {
  classifySendFailure,
  HOST_NAME,
  interpretResponse,
  type AddRequest,
  type ExtensionPresentMessage,
  type Outcome,
} from "./protocol"

/** 要求をホストへ送り、応答を結果にする。 */
export async function sendRequest(api: ExtensionApi, request: AddRequest | ExtensionPresentMessage): Promise<Outcome> {
  let response: unknown
  try {
    response = await api.runtime.sendNativeMessage(HOST_NAME, request)
  } catch (error) {
    return { ok: false, message: classifySendFailure(error) }
  }
  return interpretResponse(response)
}
