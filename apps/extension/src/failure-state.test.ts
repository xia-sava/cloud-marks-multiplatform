import { describe, expect, it } from "vitest"
import type { ExtensionApi } from "./api"
import { FAILURE_STORAGE_KEY, recordFailure, takeFailure } from "./failure-state"

function fakeApi() {
  const store: Record<string, unknown> = {}
  const badge = { text: "" }
  const title = { value: "Cloud Marks" }
  const api = {
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

describe("failure state", () => {
  it("失敗を記録すると、バッジと説明を変え、文言を storage.session に入れる", async () => {
    const { api, store, badge, title } = fakeApi()
    await recordFailure(api, "S3 に接続できませんでした。")
    expect(badge.text).toBe("!")
    expect(title.value).toBe("追加できませんでした。クリックすると理由を表示します。")
    expect(store[FAILURE_STORAGE_KEY]).toBe("S3 に接続できませんでした。")
  })

  it("取り出すと文言を返し、バッジと説明を元に戻して、storage.session から消す", async () => {
    const { api, store, badge, title } = fakeApi()
    await recordFailure(api, "失敗")
    await expect(takeFailure(api)).resolves.toBe("失敗")
    expect(badge.text).toBe("")
    expect(title.value).toBe("Cloud Marks")
    expect(store).toEqual({})
    await expect(takeFailure(api)).resolves.toBeUndefined()
  })

  it("記録が無ければ何も変えない", async () => {
    const { api, badge, title } = fakeApi()
    badge.text = "x"
    title.value = "y"
    await expect(takeFailure(api)).resolves.toBeUndefined()
    expect(badge.text).toBe("x")
    expect(title.value).toBe("y")
  })
})
