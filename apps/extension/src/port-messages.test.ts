import { describe, expect, it } from "vitest"
import { isResultMessage, isSubmitMessage } from "./port-messages"

describe("isResultMessage", () => {
  it("成功と、message を持つ失敗を受け取る", () => {
    expect(isResultMessage({ type: "result", outcome: { ok: true } })).toBe(true)
    expect(isResultMessage({ type: "result", outcome: { ok: false, message: "失敗" } })).toBe(true)
  })

  it("code を持つ失敗を受け取る", () => {
    expect(isResultMessage({ type: "result", outcome: { ok: false, message: "失敗", code: "folderNotFound" } })).toBe(true)
  })

  it("code が文字列でなければ受け取らない", () => {
    expect(isResultMessage({ type: "result", outcome: { ok: false, message: "失敗", code: 1 } })).toBe(false)
    expect(isResultMessage({ type: "result", outcome: { ok: false, message: "失敗", code: null } })).toBe(false)
  })

  it("形が合わないものは受け取らない", () => {
    for (const value of [
      null,
      {},
      { type: "result" },
      { type: "result", outcome: null },
      { type: "result", outcome: { ok: false } },
      { type: "result", outcome: { ok: false, message: 1 } },
      { type: "other", outcome: { ok: true } },
    ]) {
      expect(isResultMessage(value)).toBe(false)
    }
  })
})

describe("isSubmitMessage", () => {
  const request = { protocol: 1, type: "addBookmarks", tabs: [] }

  it("要求だけの送信と、追加先を持つ送信を受け取る", () => {
    expect(isSubmitMessage({ type: "submit", request })).toBe(true)
    expect(isSubmitMessage({ type: "submit", request, destination: { id: "f1", name: "資料" } })).toBe(true)
    expect(isSubmitMessage({ type: "submit", request, destination: { id: "r", name: "" } })).toBe(true)
  })

  it("形が合わない追加先を持つ送信は受け取らない", () => {
    for (const destination of [null, "f1", {}, { id: "f1" }, { id: "", name: "" }, { id: 1, name: "a" }]) {
      expect(isSubmitMessage({ type: "submit", request, destination })).toBe(false)
    }
  })

  it("要求でないものは受け取らない", () => {
    for (const value of [null, {}, { type: "submit" }, { type: "submit", request: null }, { type: "other", request }]) {
      expect(isSubmitMessage(value)).toBe(false)
    }
  })
})
