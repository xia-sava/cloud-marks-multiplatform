import { describe, expect, it } from "vitest"
import { MESSAGES } from "./messages"
import { classifySendFailure, interpretFoldersResponse, interpretResponse } from "./protocol"
import { contract } from "./test-support"

describe("interpretResponse", () => {
  it("成功の見本を成功と解釈する", () => {
    expect(interpretResponse(contract("response-ok.json"))).toEqual({ ok: true })
  })

  it("失敗の見本を、error.message をそのまま持つ失敗と解釈する", () => {
    expect(interpretResponse(contract("response-error.json"))).toEqual({
      ok: false,
      message: "S3 に接続できませんでした。",
    })
  })

  it("知らない code でも message を使う", () => {
    expect(interpretResponse({ ok: false, error: { code: "somethingNew", message: "新しい失敗" } })).toEqual({
      ok: false,
      message: "新しい失敗",
    })
  })

  it("code が無くても、型が違っても、空でない message を使う", () => {
    expect(interpretResponse({ ok: false, error: { message: "x" } })).toEqual({ ok: false, message: "x" })
    expect(interpretResponse({ ok: false, error: { code: 1, message: "x" } })).toEqual({ ok: false, message: "x" })
  })

  it("folderNotFound の失敗の見本を、code を持つ失敗と解釈する", () => {
    expect(interpretResponse(contract("response-folder-not-found.json"))).toEqual({
      ok: false,
      message: "追加先のフォルダが見つかりませんでした。追加先を選び直してください。",
      code: "folderNotFound",
    })
  })

  it("folderNotFound の失敗だけ、結果に code を持たせる", () => {
    expect(interpretResponse({ ok: false, error: { code: "busy", message: "x" } })).not.toHaveProperty("code")
  })

  it("形が合わない応答は、やり取りできない旨の失敗にする", () => {
    const failure = { ok: false, message: MESSAGES.communicationFailed }
    for (const response of [
      undefined,
      null,
      "ok",
      42,
      {},
      { ok: "true" },
      { ok: false },
      { ok: false, error: null },
      { ok: false, error: { code: "x" } },
      { ok: false, error: { code: "x", message: "" } },
      { ok: false, error: { code: "x", message: 1 } },
    ]) {
      expect(interpretResponse(response)).toEqual(failure)
    }
  })
})

describe("interpretFoldersResponse", () => {
  const root = { id: "r", parent: null, name: "" }
  const child = { id: "c", parent: "r", name: "資料" }
  const failure = { ok: false, message: MESSAGES.communicationFailed }

  it("成功の見本を、フォルダの並びと解釈する", () => {
    expect(interpretFoldersResponse(contract("response-folders.json"))).toEqual({
      ok: true,
      folders: (contract("response-folders.json") as { folders: unknown[] }).folders,
    })
  })

  it("失敗の見本を、error.message をそのまま持つ失敗と解釈する", () => {
    expect(interpretFoldersResponse(contract("response-error.json"))).toEqual({
      ok: false,
      message: "S3 に接続できませんでした。",
    })
  })

  it("知らない欄は写さない", () => {
    expect(interpretFoldersResponse({ ok: true, folders: [{ ...root, extra: 1 }] })).toEqual({
      ok: true,
      folders: [root],
    })
  })

  it("folders が取り決めの形でない成功の応答は、やり取りできない旨の失敗にする", () => {
    for (const folders of [
      undefined,
      null,
      {},
      [],
      [null],
      [{ id: "r", parent: null }],
      [{ id: "r", name: "" }],
      [{ id: "", parent: null, name: "" }],
      [{ id: 1, parent: null, name: "" }],
      [{ id: "r", parent: 1, name: "" }],
      [{ id: "r", parent: null, name: 1 }],
      [child],
      [root, { id: "x", parent: null, name: "" }],
      [root, { id: "c", parent: "unknown", name: "資料" }],
      [root, { id: "c", parent: "later", name: "a" }, { id: "later", parent: "r", name: "b" }],
    ]) {
      expect(interpretFoldersResponse({ ok: true, folders })).toEqual(failure)
    }
    expect(interpretFoldersResponse({ ok: true })).toEqual(failure)
  })

  it("形が合わない応答は、やり取りできない旨の失敗にする", () => {
    for (const response of [undefined, null, "ok", {}, { ok: false }]) {
      expect(interpretFoldersResponse(response)).toEqual(failure)
    }
  })
})

describe("classifySendFailure", () => {
  it("ホストが登録されていないときは、見つからない旨にする", () => {
    expect(classifySendFailure(new Error("Specified native messaging host not found."))).toBe(MESSAGES.hostNotFound)
    expect(classifySendFailure(new Error("No such native application to.sava.cloudmarksmultiplatform"))).toBe(
      MESSAGES.hostNotFound,
    )
    expect(classifySendFailure({ message: "Specified native messaging host not found." })).toBe(MESSAGES.hostNotFound)
    expect(classifySendFailure("No such native application x")).toBe(MESSAGES.hostNotFound)
  })

  it("それ以外の失敗は、やり取りできない旨にする", () => {
    for (const error of [
      new Error("Native host has exited."),
      new Error("Access to the specified native messaging host is forbidden."),
      new Error("Error when communicating with the native messaging host."),
      new Error(""),
      undefined,
      null,
      42,
    ]) {
      expect(classifySendFailure(error)).toBe(MESSAGES.communicationFailed)
    }
  })
})
