import { describe, expect, it, vi } from "vitest"
import type { ExtensionApi } from "./api"
import { MESSAGES } from "./messages"
import { listFoldersMessage } from "./folders"
import { HOST_NAME, type AddRequest, type FoldersOutcome } from "./protocol"
import { sendRequest } from "./send"

const request: AddRequest = {
  protocol: 1,
  type: "addBookmarks",
  tabs: [{ url: "https://example.com/", title: "A" }],
}

function apiWith(sendNativeMessage: () => Promise<unknown>) {
  const spy = vi.fn(sendNativeMessage)
  const api = { runtime: { sendNativeMessage: spy } } as unknown as ExtensionApi
  return { api, spy }
}

describe("sendRequest", () => {
  it("ホストの名前へ要求をそのまま送り、成功の応答を成功にする", async () => {
    const { api, spy } = apiWith(async () => ({ ok: true }))
    await expect(sendRequest(api, request)).resolves.toEqual({ ok: true })
    expect(spy).toHaveBeenCalledWith(HOST_NAME, request)
  })

  it("失敗の応答の message を使う", async () => {
    const { api } = apiWith(async () => ({ ok: false, error: { code: "busy", message: "混み合っています。" } }))
    await expect(sendRequest(api, request)).resolves.toEqual({ ok: false, message: "混み合っています。" })
  })

  it("interpret を渡すと、応答をそれで結果にする", async () => {
    const response = { ok: false, error: { code: "busy", message: "混み合っています。" } }
    const { api } = apiWith(async () => response)
    const interpret = vi.fn((_response: unknown) => ({ ok: false as const, message: "差し替えた文" }))
    await expect(sendRequest(api, request, interpret)).resolves.toEqual({ ok: false, message: "差し替えた文" })
    expect(interpret).toHaveBeenCalledWith(response)
  })

  it("interpret を渡さない呼び出しの結果は、FoldersOutcome として使えない", async () => {
    const { api } = apiWith(async () => ({ ok: true }))
    // @ts-expect-error interpret を渡さないと、結果は Outcome になる
    const outcome: FoldersOutcome = await sendRequest(api, listFoldersMessage())
    expect(outcome).toEqual({ ok: true })
  })

  it("ホストが見つからない誤りは、見つからない旨にする", async () => {
    const { api } = apiWith(async () => {
      throw new Error("Specified native messaging host not found.")
    })
    await expect(sendRequest(api, request)).resolves.toEqual({ ok: false, message: MESSAGES.hostNotFound })
  })

  it("ほかの誤りと、応答が無いことは、やり取りできない旨にする", async () => {
    const thrown = apiWith(async () => {
      throw new Error("Native host has exited.")
    })
    await expect(sendRequest(thrown.api, request)).resolves.toEqual({
      ok: false,
      message: MESSAGES.communicationFailed,
    })
    const empty = apiWith(async () => undefined)
    await expect(sendRequest(empty.api, request)).resolves.toEqual({
      ok: false,
      message: MESSAGES.communicationFailed,
    })
  })
})
