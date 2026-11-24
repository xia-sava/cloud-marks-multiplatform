import { describe, expect, it } from "vitest"
import type { ExtensionApi } from "./api"
import { LAST_FOLDER_STORAGE_KEY, readLastFolder, rememberLastFolder } from "./last-folder"

function fakeApi(initial: Record<string, unknown> = {}) {
  const store: Record<string, unknown> = { ...initial }
  const api = {
    storage: {
      local: {
        get: async (key: string) => (key in store ? { [key]: store[key] } : {}),
        set: async (items: Record<string, unknown>) => void Object.assign(store, items),
      },
    },
  } as unknown as ExtensionApi
  return { api, store }
}

describe("last folder", () => {
  it("覚えた追加先を、storage.local から読める", async () => {
    const { api, store } = fakeApi()
    await rememberLastFolder(api, { id: "f1", name: "資料" })
    expect(store[LAST_FOLDER_STORAGE_KEY]).toEqual({ id: "f1", name: "資料" })
    await expect(readLastFolder(api)).resolves.toEqual({ id: "f1", name: "資料" })
  })

  it("ルートフォルダの空の名前も覚える", async () => {
    const { api } = fakeApi()
    await rememberLastFolder(api, { id: "r", name: "" })
    await expect(readLastFolder(api)).resolves.toEqual({ id: "r", name: "" })
  })

  it("何も覚えていなければ undefined", async () => {
    await expect(readLastFolder(fakeApi().api)).resolves.toBeUndefined()
  })

  it.each([
    ["文字列", "x"],
    ["null", null],
    ["空のオブジェクト", {}],
    ["name が無い", { id: "f" }],
    ["id が空", { id: "", name: "a" }],
    ["id が数", { id: 1, name: "a" }],
    ["name が数", { id: "f", name: 1 }],
  ])("形が合わないもの（%s）は無いものとして扱う", async (_label, value) => {
    const { api } = fakeApi({ [LAST_FOLDER_STORAGE_KEY]: value })
    await expect(readLastFolder(api)).resolves.toBeUndefined()
  })
})
