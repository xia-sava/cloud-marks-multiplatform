import type { ExtensionApi } from "./api"
import {
  classifySendFailure,
  HOST_NAME,
  interpretResponse,
  type AddRequest,
  type ExtensionPresentMessage,
  type OpenAppMessage,
  type Outcome,
} from "./protocol"

/**
 * 要求をホストへ送り、応答を `interpret` で結果にする。
 * 送れなかったときは、`classifySendFailure` の文を持つ失敗にする。
 */
export async function sendRequest(
  api: ExtensionApi,
  request: AddRequest | ExtensionPresentMessage | OpenAppMessage,
  interpret: (response: unknown) => Outcome = interpretResponse,
): Promise<Outcome> {
  let response: unknown
  try {
    response = await api.runtime.sendNativeMessage(HOST_NAME, request)
  } catch (error) {
    return { ok: false, message: classifySendFailure(error) }
  }
  return interpret(response)
}
