import { describe, expect, it, vi } from "vitest"
import type { ExtensionApi } from "./api"
import { MESSAGES } from "./messages"
import { openApp, openAppMessage } from "./open-app"
import { HOST_NAME } from "./protocol"
import { contract } from "./test-support"

/** 古いアプリ（0.1.2 まで）のホストが、知らない `type` に返す応答。 */
const OLD_HOST_INVALID_REQUEST = {
  ok: false,
  error: {
    code: "invalidRequest",
    message:
      "追加する内容を受け付けられませんでした。拡張機能とアプリのバージョンが合っていない可能性があります。両方を最新にしてください。",
  },
}

function apiWith(sendNativeMessage: () => Promise<unknown>) {
  const spy = vi.fn(sendNativeMessage)
  const api = { runtime: { sendNativeMessage: spy } } as unknown as ExtensionApi
  return { api, spy }
}

describe("openAppMessage", () => {
  it("見本と同じ JSON になる", () => {
    expect(JSON.parse(JSON.stringify(openAppMessage()))).toEqual(contract("open-app.json"))
  })
})

describe("openApp", () => {
  it("ホストの名前へアプリを開く要求を送り、成功の応答を成功にする", async () => {
    const { api, spy } = apiWith(async () => contract("response-ok.json"))
    await expect(openApp(api)).resolves.toEqual({ ok: true })
    expect(spy).toHaveBeenCalledTimes(1)
    expect(spy).toHaveBeenCalledWith(HOST_NAME, openAppMessage())
  })

  it("失敗の応答の message をそのまま使う", async () => {
    const { api } = apiWith(async () => contract("response-error.json"))
    await expect(openApp(api)).resolves.toEqual({ ok: false, message: "S3 に接続できませんでした。" })
  })

  it("launchFailed の応答も、message をそのまま使う", async () => {
    for (const message of [
      "Cloud Marks を開けませんでした。スタートメニューから起動してください。",
      "開発版の Cloud Marks は、拡張機能から開けません。",
    ]) {
      const { api } = apiWith(async () => ({ ok: false, error: { code: "launchFailed", message } }))
      await expect(openApp(api)).resolves.toEqual({ ok: false, message })
    }
  })

  it("古いアプリのホストの invalidRequest は、アプリの更新を促す文にする", async () => {
    const { api } = apiWith(async () => OLD_HOST_INVALID_REQUEST)
    await expect(openApp(api)).resolves.toEqual({
      ok: false,
      message:
        "この機能を使うには、Cloud Marks のアプリを更新してください。アプリの設定の画面の「アプリの更新」から更新できます。",
    })
  })

  it("ホストが見つからない誤りは、見つからない旨にする", async () => {
    const { api } = apiWith(async () => {
      throw new Error("Specified native messaging host not found.")
    })
    await expect(openApp(api)).resolves.toEqual({ ok: false, message: MESSAGES.hostNotFound })
  })

  it("ほかの送信の誤りと、形の合わない応答は、やり取りできない旨にする", async () => {
    const thrown = apiWith(async () => {
      throw new Error("Native host has exited.")
    })
    await expect(openApp(thrown.api)).resolves.toEqual({ ok: false, message: MESSAGES.communicationFailed })
    const empty = apiWith(async () => undefined)
    await expect(openApp(empty.api)).resolves.toEqual({ ok: false, message: MESSAGES.communicationFailed })
  })
})
