import { describe, expect, it, vi } from "vitest"
import type { ExtensionApi } from "./api"
import { choicesFromFolders, folderOptions, initialChoices, listFolders, listFoldersMessage } from "./folders"
import { MESSAGES } from "./messages"
import { HOST_NAME, type FolderEntry } from "./protocol"
import { contract } from "./test-support"

const ROOT = "0b8f6a52-1c3d-4e5f-8a9b-0c1d2e3f4a5b"
const DOCS = "7d4c1e2a-3b5f-4a6d-9e8c-1f2a3b4c5d6e"
const LATER = "2e9a7b31-4c6d-4f8e-a0b1-c2d3e4f5a6b7"
const WORK = "5f1b3c4d-6e7a-4b8c-9d0e-a1b2c3d4e5f6"

const SAMPLE_FOLDERS: FolderEntry[] = [
  { id: ROOT, parent: null, name: "" },
  { id: DOCS, parent: ROOT, name: "資料" },
  { id: LATER, parent: DOCS, name: "あとで読む" },
  { id: WORK, parent: ROOT, name: "仕事" },
]

function apiWith(sendNativeMessage: () => Promise<unknown>) {
  const spy = vi.fn(sendNativeMessage)
  const api = { runtime: { sendNativeMessage: spy } } as unknown as ExtensionApi
  return { api, spy }
}

describe("listFoldersMessage", () => {
  it("見本と同じ JSON になる", () => {
    expect(JSON.parse(JSON.stringify(listFoldersMessage()))).toEqual(contract("list-folders.json"))
  })
})

describe("listFolders", () => {
  it("ホストの名前へ一覧の要求を送り、見本の応答をフォルダの並びにする", async () => {
    const { api, spy } = apiWith(async () => contract("response-folders.json"))
    await expect(listFolders(api)).resolves.toEqual({ ok: true, folders: SAMPLE_FOLDERS })
    expect(spy).toHaveBeenCalledTimes(1)
    expect(spy).toHaveBeenCalledWith(HOST_NAME, listFoldersMessage())
  })

  it("失敗の応答は、失敗にする", async () => {
    const { api } = apiWith(async () => contract("response-error.json"))
    await expect(listFolders(api)).resolves.toEqual({ ok: false, message: "S3 に接続できませんでした。" })
  })

  it("成功でも folders が無い応答は、やり取りできない旨の失敗にする", async () => {
    const { api } = apiWith(async () => contract("response-ok.json"))
    await expect(listFolders(api)).resolves.toEqual({ ok: false, message: MESSAGES.communicationFailed })
  })

  it("送れなかったときは、失敗にする", async () => {
    const { api } = apiWith(async () => {
      throw new Error("Specified native messaging host not found.")
    })
    await expect(listFolders(api)).resolves.toEqual({ ok: false, message: MESSAGES.hostNotFound })
  })
})

describe("folderOptions", () => {
  it("folders の順に、ルートは トップ、ほかは深さと同じ数の全角スペースを名前の前に置く", () => {
    expect(folderOptions(SAMPLE_FOLDERS)).toEqual([
      { id: ROOT, name: "", label: "トップ" },
      { id: DOCS, name: "資料", label: "　資料" },
      { id: LATER, name: "あとで読む", label: "　　あとで読む" },
      { id: WORK, name: "仕事", label: "　仕事" },
    ])
  })

  it("ルートだけの一覧は トップ だけになる", () => {
    expect(folderOptions([SAMPLE_FOLDERS[0]])).toEqual([{ id: ROOT, name: "", label: "トップ" }])
  })
})

describe("choicesFromFolders", () => {
  const last = { id: LATER, name: "あとで読む" }

  it("選んでいるフォルダが一覧にあれば、前回の追加先より先にそれを選ぶ", () => {
    const choices = choicesFromFolders(SAMPLE_FOLDERS, WORK, last)
    expect(choices.options).toHaveLength(4)
    expect(choices.selectedIndex).toBe(3)
  })

  it("選んでいるフォルダが無い（初回）とき、前回の追加先が一覧にあればそれを選ぶ", () => {
    expect(choicesFromFolders(SAMPLE_FOLDERS, undefined, last).selectedIndex).toBe(2)
  })

  it("選んでいるフォルダが一覧に無ければ、前回の追加先を選ぶ", () => {
    expect(choicesFromFolders(SAMPLE_FOLDERS, "gone", last).selectedIndex).toBe(2)
  })

  it("選んでいるフォルダも前回の追加先も一覧に無ければ、ルートフォルダを選ぶ", () => {
    expect(choicesFromFolders(SAMPLE_FOLDERS, "gone", { id: "gone2", name: "消えた" }).selectedIndex).toBe(0)
  })

  it("選んでいるフォルダも覚えたものも無ければ、ルートフォルダを選ぶ", () => {
    expect(choicesFromFolders(SAMPLE_FOLDERS, undefined, undefined).selectedIndex).toBe(0)
  })

  it("選んでいるフォルダが無く、前回の追加先がルートフォルダなら、ルートを選ぶ", () => {
    expect(choicesFromFolders(SAMPLE_FOLDERS, undefined, { id: ROOT, name: "" }).selectedIndex).toBe(0)
  })
})

describe("initialChoices", () => {
  it("覚えたものが無ければ、フォルダを指定しない トップ だけにする", () => {
    expect(initialChoices(undefined)).toEqual({
      options: [{ id: undefined, name: "", label: "トップ" }],
      selectedIndex: 0,
    })
  })

  it("前回の追加先だけを、その名前で出す", () => {
    expect(initialChoices({ id: DOCS, name: "資料" })).toEqual({
      options: [{ id: DOCS, name: "資料", label: "資料" }],
      selectedIndex: 0,
    })
  })

  it("前回の追加先がルートフォルダなら、トップ と出す", () => {
    expect(initialChoices({ id: ROOT, name: "" })).toEqual({
      options: [{ id: ROOT, name: "", label: "トップ" }],
      selectedIndex: 0,
    })
  })
})
