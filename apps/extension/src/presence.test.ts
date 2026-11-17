import { afterEach, describe, expect, it, vi } from "vitest"
import type { ExtensionApi } from "./api"
import { MESSAGES } from "./messages"
import { announcePresence, extensionPresentMessage } from "./presence"
import { HOST_NAME } from "./protocol"
import { contract } from "./test-support"

function apiWith(sendNativeMessage: () => Promise<unknown>) {
  const spy = vi.fn(sendNativeMessage)
  const api = { runtime: { sendNativeMessage: spy } } as unknown as ExtensionApi
  return { api, spy }
}

describe("extensionPresentMessage", () => {
  it("見本と同じ JSON になる", () => {
    expect(JSON.parse(JSON.stringify(extensionPresentMessage()))).toEqual(contract("extension-present.json"))
  })
})

describe("announcePresence", () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it("ホストの名前へ知らせを送り、成功なら console に何も出さない", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {})
    const { api, spy } = apiWith(async () => contract("response-ok.json"))
    await announcePresence(api)
    expect(spy).toHaveBeenCalledWith(HOST_NAME, extensionPresentMessage())
    expect(warn).not.toHaveBeenCalled()
  })

  it("送れなかったときは console に出すだけで、失敗を投げない", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {})
    const { api } = apiWith(async () => {
      throw new Error("Specified native messaging host not found.")
    })
    await expect(announcePresence(api)).resolves.toBeUndefined()
    expect(warn).toHaveBeenCalledTimes(1)
    expect(warn.mock.calls[0]).toContain(MESSAGES.hostNotFound)
  })

  it("失敗の応答も console に出すだけ", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {})
    const { api } = apiWith(async () => contract("response-error.json"))
    await expect(announcePresence(api)).resolves.toBeUndefined()
    expect(warn).toHaveBeenCalledTimes(1)
    expect(warn.mock.calls[0]).toContain("S3 に接続できませんでした。")
  })
})
