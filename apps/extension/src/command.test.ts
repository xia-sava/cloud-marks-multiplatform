import { describe, expect, it } from "vitest"
import {
  interpretTakeCommandResponse,
  isValidToken,
  readCallToken,
  readOpenTabsCommand,
  takeCommandMessage,
} from "./command"
import { MESSAGES } from "./messages"
import { contract } from "./test-support"

const TOKEN = "0123456789abcdef0123456789abcdef"

const command = {
  type: "openTabs",
  tabs: [{ url: "https://example.com/", title: "Example" }],
  loadAtOnce: 3,
  loadAhead: 20,
}

describe("readCallToken", () => {
  it("呼び出しの URL から token を取り出す", () => {
    expect(readCallToken(`https://cmm.invalid/knock?token=${TOKEN}`)).toBe(TOKEN)
  })

  it("token の形は問わず、無ければ空文字にする", () => {
    expect(readCallToken("https://cmm.invalid/knock?token=xyz")).toBe("xyz")
    expect(readCallToken("https://cmm.invalid/knock?token=")).toBe("")
    expect(readCallToken("https://cmm.invalid/knock")).toBe("")
    expect(readCallToken("https://cmm.invalid/knock?other=1")).toBe("")
  })

  it("呼び出しの URL でなければ undefined", () => {
    for (const url of [
      "",
      "not a url",
      "about:blank",
      "chrome://newtab/",
      `http://cmm.invalid/knock?token=${TOKEN}`,
      `https://cmm.invalid/knock/?token=${TOKEN}`,
      `https://cmm.invalid/other?token=${TOKEN}`,
      `https://example.com/knock?token=${TOKEN}`,
      `https://sub.cmm.invalid/knock?token=${TOKEN}`,
      `https://cmm.invalid:8443/knock?token=${TOKEN}`,
    ]) {
      expect(readCallToken(url), url).toBeUndefined()
    }
  })
})

describe("isValidToken", () => {
  it("小文字の 16 進で 32 文字なら合う", () => {
    expect(isValidToken(TOKEN)).toBe(true)
    expect(isValidToken("ffffffffffffffffffffffffffffffff")).toBe(true)
  })

  it("長さ、大文字、16 進でない文字、前後の余分があれば合わない", () => {
    for (const token of [
      "",
      TOKEN.slice(1),
      `${TOKEN}0`,
      TOKEN.toUpperCase(),
      "0123456789abcdef0123456789abcdeg",
      ` ${TOKEN}`,
      `${TOKEN}\n`,
    ]) {
      expect(isValidToken(token), JSON.stringify(token)).toBe(false)
    }
  })
})

describe("takeCommandMessage", () => {
  it("見本と同じ JSON になる", () => {
    expect(JSON.parse(JSON.stringify(takeCommandMessage(TOKEN)))).toEqual(contract("take-command.json"))
  })
})

describe("readOpenTabsCommand", () => {
  it("知っている欄だけを写す", () => {
    const value = { ...command, extra: 1, tabs: [{ url: "https://example.com/", title: "", x: 2 }] }
    expect(readOpenTabsCommand(value)).toEqual({ ...command, tabs: [{ url: "https://example.com/", title: "" }] })
  })

  it("形が合わなければ undefined", () => {
    for (const value of [
      null,
      "openTabs",
      { ...command, type: "closeTabs" },
      { ...command, type: undefined },
      { ...command, tabs: [] },
      { ...command, tabs: "x" },
      { ...command, tabs: [null] },
      { ...command, tabs: [{ url: "", title: "A" }] },
      { ...command, tabs: [{ url: 1, title: "A" }] },
      { ...command, tabs: [{ url: "https://example.com/" }] },
      { ...command, tabs: [{ url: "https://example.com/", title: null }] },
      { ...command, tabs: [...command.tabs, { url: "", title: "" }] },
      { ...command, loadAtOnce: 0 },
      { ...command, loadAtOnce: 1.5 },
      { ...command, loadAtOnce: "3" },
      { ...command, loadAtOnce: undefined },
      { ...command, loadAhead: -1 },
      { ...command, loadAhead: 1.5 },
      { ...command, loadAhead: "20" },
      { ...command, loadAhead: null },
      { ...command, loadAhead: undefined },
      { ...command, loadAhead: Number.NaN },
    ]) {
      expect(readOpenTabsCommand(value), JSON.stringify(value)).toBeUndefined()
    }
  })

  it("loadAhead が 0 なら合う（すべてのタブを順に読み込む）", () => {
    expect(readOpenTabsCommand({ ...command, loadAhead: 0 })).toEqual({ ...command, loadAhead: 0 })
  })
})

describe("interpretTakeCommandResponse", () => {
  it("コマンドの見本を、コマンドと解釈する", () => {
    expect(interpretTakeCommandResponse(contract("response-command.json"))).toEqual({
      kind: "command",
      command: {
        type: "openTabs",
        tabs: [
          { url: "https://example.com/", title: "Example" },
          { url: "https://example.org/articles/1", title: "" },
        ],
        loadAtOnce: 3,
        loadAhead: 20,
      },
    })
  })

  it("コマンドが無いときの見本を、コマンドが無いと解釈する", () => {
    expect(interpretTakeCommandResponse(contract("response-no-command.json"))).toEqual({ kind: "noCommand" })
  })

  it("成功でも command が欠けているか形が合わなければ、形の合わないコマンドにする", () => {
    expect(interpretTakeCommandResponse({ ok: true })).toEqual({ kind: "invalidCommand" })
    expect(interpretTakeCommandResponse({ ok: true, command: { ...command, tabs: [] } })).toEqual({
      kind: "invalidCommand",
    })
  })

  it("失敗の応答は error.message を持つ失敗にする", () => {
    expect(interpretTakeCommandResponse(contract("response-error.json"))).toEqual({
      kind: "failed",
      message: "S3 に接続できませんでした。",
    })
  })

  it("形が合わない応答は、やり取りできない旨の失敗にする", () => {
    for (const response of [undefined, null, "ok", {}, { ok: "true", command }]) {
      expect(interpretTakeCommandResponse(response)).toEqual({ kind: "failed", message: MESSAGES.communicationFailed })
    }
  })
})
