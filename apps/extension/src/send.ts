import type { ExtensionApi } from "./api"
import {
  classifySendFailure,
  HOST_NAME,
  interpretResponse,
  type AddRequest,
  type ExtensionPresentMessage,
  type Failure,
  type ListFoldersMessage,
  type OpenAppMessage,
  type Outcome,
} from "./protocol"

type HostRequest = AddRequest | ExtensionPresentMessage | OpenAppMessage | ListFoldersMessage

/**
 * 要求をホストへ送り、応答を `interpret` で結果にする。`interpret` を渡さなければ `interpretResponse`。
 * 送れなかったときは、`classifySendFailure` の文を持つ失敗にする。
 */
export async function sendRequest(api: ExtensionApi, request: HostRequest): Promise<Outcome>
export async function sendRequest<R extends { ok: boolean }>(
  api: ExtensionApi,
  request: HostRequest,
  interpret: (response: unknown) => R,
): Promise<R | Failure>
export async function sendRequest(
  api: ExtensionApi,
  request: HostRequest,
  interpret: (response: unknown) => { ok: boolean } = interpretResponse,
): Promise<{ ok: boolean } | Failure> {
  let response: unknown
  try {
    response = await api.runtime.sendNativeMessage(HOST_NAME, request)
  } catch (error) {
    return { ok: false, message: classifySendFailure(error) }
  }
  return interpret(response)
}
