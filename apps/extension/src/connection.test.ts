import { describe, expect, it, vi } from "vitest"
import type { ExtensionApi, ExtensionPort } from "./api"
import { handleConnection } from "./connection"
import { FAILURE_STORAGE_KEY } from "./failure-state"
import { MESSAGES } from "./messages"
import { POPUP_PORT_NAME } from "./port-messages"
import type { AddRequest } from "./protocol"

const request: AddRequest = {
  protocol: 1,
  type: "addBookmarks",
  tabs: [{ url: "https://example.com/", title: "A" }],
}

function fakeApi(response: () => Promise<unknown>) {
  const store: Record<string, unknown> = {}
  const badge = { text: "" }
  const title = { value: "Cloud Marks" }
  const api = {
    runtime: { sendNativeMessage: vi.fn(response) },
    storage: {
      session: {
        get: async (key: string) => (key in store ? { [key]: store[key] } : {}),
        set: async (items: Record<string, unknown>) => void Object.assign(store, items),
        remove: async (key: string) => void delete store[key],
      },
    },
    action: {
      setBadgeText: async (details: { text: string }) => void (badge.text = details.text),
      setTitle: async (details: { title: string }) => void (title.value = details.title),
    },
  } as unknown as ExtensionApi
  return { api, store, badge, title }
}

function fakePort(name = POPUP_PORT_NAME) {
  let onMessage: (message: unknown) => void | Promise<void> = () => {}
  let onDisconnect: () => void = () => {}
  const posted: unknown[] = []
  const port = {
    name,
    postMessage: vi.fn((message: unknown) => void posted.push(message)),
    disconnect: vi.fn(),
    onMessage: { addListener: (listener: typeof onMessage) => void (onMessage = listener) },
    onDisconnect: { addListener: (listener: () => void) => void (onDisconnect = listener) },
  }
  return {
    port: port as unknown as ExtensionPort,
    mock: port,
    posted,
    submit: () => onMessage({ type: "submit", request }),
    send: (message: unknown) => onMessage(message),
    disconnect: () => onDisconnect(),
  }
}

describe("handleConnection", () => {
  it("つながったままなら成功をポートへ渡し、何も残さない", async () => {
    const { api, store, badge } = fakeApi(async () => ({ ok: true }))
    const p = fakePort()
    handleConnection(api, p.port)
    await p.submit()
    expect(api.runtime.sendNativeMessage).toHaveBeenCalledTimes(1)
    expect(p.posted).toEqual([{ type: "result", outcome: { ok: true } }])
    expect(store).toEqual({})
    expect(badge.text).toBe("")
  })

  it("つながったままなら失敗をポートへ渡し、バッジも付けない", async () => {
    const { api, store, badge } = fakeApi(async () => ({ ok: false, error: { code: "busy", message: "失敗" } }))
    const p = fakePort()
    handleConnection(api, p.port)
    await p.submit()
    expect(p.posted).toEqual([{ type: "result", outcome: { ok: false, message: "失敗" } }])
    expect(store).toEqual({})
    expect(badge.text).toBe("")
  })

  it("ポップアップが閉じたあとの失敗は、バッジと説明と storage.session に残す", async () => {
    let resolve: (value: unknown) => void = () => {}
    const { api, store, badge, title } = fakeApi(() => new Promise((r) => (resolve = r)))
    const p = fakePort()
    handleConnection(api, p.port)
    const pending = p.submit()
    p.disconnect()
    resolve({ ok: false, error: { code: "busy", message: "失敗" } })
    await pending
    expect(p.posted).toEqual([])
    expect(store[FAILURE_STORAGE_KEY]).toBe("失敗")
    expect(badge.text).toBe("!")
    expect(title.value).toBe(MESSAGES.failureActionTitle)
  })

  it("ポップアップが閉じたあとの成功は、何もしない", async () => {
    let resolve: (value: unknown) => void = () => {}
    const { api, store, badge } = fakeApi(() => new Promise((r) => (resolve = r)))
    const p = fakePort()
    handleConnection(api, p.port)
    const pending = p.submit()
    p.disconnect()
    resolve({ ok: true })
    await pending
    expect(p.posted).toEqual([])
    expect(store).toEqual({})
    expect(badge.text).toBe("")
  })

  it("知らない名前のポートと、要求でないメッセージは扱わない", async () => {
    const { api } = fakeApi(async () => ({ ok: true }))
    const other = fakePort("other")
    handleConnection(api, other.port)
    await other.submit()
    const p = fakePort()
    handleConnection(api, p.port)
    await p.send({ type: "unknown" })
    await p.send(null)
    expect(api.runtime.sendNativeMessage).not.toHaveBeenCalled()
  })
})
