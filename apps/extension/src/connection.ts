import type { ExtensionApi, ExtensionPort } from "./api"
import { recordFailure } from "./failure-state"
import { isSubmitMessage, POPUP_PORT_NAME, type ResultMessage } from "./port-messages"
import { sendRequest } from "./send"

/**
 * ポップアップからのポートを受け、要求をホストへ送る。
 * 結果が出たときポートがつながっていれば結果を渡す。切れていて失敗だったら、
 * 次にポップアップを開いたときに出せるよう、ボタンの印と文言を残す。成功なら何もしない。
 */
export function handleConnection(api: ExtensionApi, port: ExtensionPort): void {
  if (port.name !== POPUP_PORT_NAME) return

  let connected = true
  port.onDisconnect.addListener(() => {
    connected = false
  })

  port.onMessage.addListener(async (message) => {
    if (!isSubmitMessage(message)) return
    const outcome = await sendRequest(api, message.request)

    if (connected) {
      const result: ResultMessage = { type: "result", outcome }
      port.postMessage(result)
      return
    }
    if (!outcome.ok) await recordFailure(api, outcome.message)
  })
}
