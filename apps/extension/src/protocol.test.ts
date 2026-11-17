import { readFileSync } from "node:fs"
import path from "node:path"
import { describe, expect, it } from "vitest"
import { MESSAGES } from "./messages"
import { classifySendFailure, interpretResponse } from "./protocol"

function contract(file: string): unknown {
  const full = path.resolve(import.meta.dirname, "../../../contracts/native-messaging", file)
  return JSON.parse(readFileSync(full, "utf8"))
}

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
