import type { ExtensionApi } from "./api"
import type { ExtensionPresentMessage } from "./protocol"
import { sendRequest } from "./send"

export function extensionPresentMessage(): ExtensionPresentMessage {
  return { protocol: 1, type: "extensionPresent" }
}

/** このブラウザに拡張が入っていることをホストへ知らせる。失敗は console に出すだけにする。 */
export async function announcePresence(api: ExtensionApi): Promise<void> {
  const outcome = await sendRequest(api, extensionPresentMessage())
  if (!outcome.ok) console.warn("拡張が入っていることをホストへ知らせられなかった", outcome.message)
}
