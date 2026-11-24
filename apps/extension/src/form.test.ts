import { describe, expect, it } from "vitest"
import {
  buildRequest,
  canSubmit,
  defaultAction,
  defaultFolderName,
  initialNameValues,
  nameField,
  nameFieldText,
  rememberName,
} from "./form"
import { contract } from "./test-support"

describe("defaultAction", () => {
  it("タブが 1 つ以下ならブックマーク、2 つ以上ならフォルダ", () => {
    expect(defaultAction(0)).toBe("addBookmarks")
    expect(defaultAction(1)).toBe("addBookmarks")
    expect(defaultAction(2)).toBe("addFolder")
    expect(defaultAction(5)).toBe("addFolder")
  })
})

describe("defaultFolderName", () => {
  it("ローカル時刻をゼロ埋めの YYYY-MM-DD HH:mm:ss にする", () => {
    expect(defaultFolderName(new Date(2026, 0, 2, 3, 4, 5))).toBe("2026-01-02 03:04:05")
    expect(defaultFolderName(new Date(2026, 10, 16, 21, 30, 15))).toBe("2026-11-16 21:30:15")
  })
})

describe("nameField", () => {
  it("フォルダは「名前」で入力できる", () => {
    expect(nameField("addFolder", 1)).toEqual({ label: "名前", editable: true })
    expect(nameField("addFolder", 3)).toEqual({ label: "名前", editable: true })
  })

  it("タブが 1 つのブックマークは「タイトル」で入力できる", () => {
    expect(nameField("addBookmarks", 1)).toEqual({ label: "タイトル", editable: true })
  })

  it("タブが 2 つ以上のブックマークは入力できない", () => {
    expect(nameField("addBookmarks", 2)).toEqual({ label: "タイトル", editable: false })
  })
})

describe("名前の欄の値", () => {
  const tabs = [{ url: "https://example.com/", title: "Example" }]
  const now = new Date(2026, 10, 16, 21, 30, 15)

  it("既定はフォルダが時刻、ブックマークがタブのタイトル", () => {
    const values = initialNameValues(now, tabs)
    expect(nameFieldText(values, "addFolder", 1)).toBe("2026-11-16 21:30:15")
    expect(nameFieldText(values, "addBookmarks", 1)).toBe("Example")
  })

  it("タブが 2 つ以上ならブックマークのタイトルの既定は空で、欄には案内を出す", () => {
    const values = initialNameValues(now, [...tabs, { url: "https://example.org/", title: "Org" }])
    expect(values.bookmarkTitle).toBe("")
    expect(nameFieldText(values, "addBookmarks", 2)).toBe("それぞれのタブのタイトルを使います。")
  })

  it("切り替える前の入力を種類ごとに覚えていて、戻すと戻る", () => {
    let values = initialNameValues(now, tabs)
    values = rememberName(values, "addBookmarks", 1, "書き換えたタイトル")
    values = rememberName(values, "addFolder", 1, "自分で付けた名前")
    expect(nameFieldText(values, "addBookmarks", 1)).toBe("書き換えたタイトル")
    expect(nameFieldText(values, "addFolder", 1)).toBe("自分で付けた名前")
  })

  it("入力できない欄の文字列は覚えない", () => {
    const values = initialNameValues(now, tabs)
    expect(rememberName(values, "addBookmarks", 2, "それぞれのタブのタイトルを使います。")).toBe(values)
  })
})

describe("canSubmit", () => {
  it("フォルダは、名前が空なら押せない（空白だけなら押せる）", () => {
    expect(canSubmit("addFolder", "", 2)).toBe(false)
    expect(canSubmit("addFolder", " ", 2)).toBe(true)
    expect(canSubmit("addFolder", " a ", 2)).toBe(true)
  })

  it("ブックマークは名前の欄が空でも押せる", () => {
    expect(canSubmit("addBookmarks", "", 1)).toBe(true)
    expect(canSubmit("addBookmarks", "それぞれのタブのタイトルを使います。", 2)).toBe(true)
  })

  it("タブが無ければ押せない", () => {
    expect(canSubmit("addFolder", "a", 0)).toBe(false)
    expect(canSubmit("addBookmarks", "a", 0)).toBe(false)
  })
})

describe("buildRequest", () => {
  it("フォルダの要求は見本と同じ JSON になる", () => {
    const tabs = [
      { url: "https://example.com/articles/1", title: "記事のタイトル" },
      { url: "https://example.org/", title: "" },
    ]
    const request = buildRequest("addFolder", "2026-11-16 21:30:15", tabs, "7d4c1e2a-3b5f-4a6d-9e8c-1f2a3b4c5d6e")
    expect(JSON.parse(JSON.stringify(request))).toEqual(contract("add-folder.json"))
  })

  it("追加先を渡さなければ、folder を持たない", () => {
    const tabs = [{ url: "https://example.com/", title: "A" }]
    expect(buildRequest("addFolder", "名前", tabs)).not.toHaveProperty("folder")
    expect(buildRequest("addBookmarks", "A", tabs)).not.toHaveProperty("folder")
    expect(buildRequest("addBookmarks", "", [...tabs, ...tabs])).not.toHaveProperty("folder")
  })

  it("ブックマークの要求も、追加先を folder に入れる", () => {
    const one = [{ url: "https://example.com/", title: "A" }]
    expect(buildRequest("addBookmarks", "B", one, "f1")).toEqual({
      protocol: 1,
      type: "addBookmarks",
      folder: "f1",
      tabs: [{ url: "https://example.com/", title: "B" }],
    })
    const two = [...one, { url: "https://example.org/", title: "C" }]
    expect(buildRequest("addBookmarks", "", two, "f1")).toMatchObject({ type: "addBookmarks", folder: "f1", tabs: two })
  })

  it("タブが 1 つのブックマークの要求は、名前の欄の値をタイトルにして見本と同じ JSON になる", () => {
    const tabs = [{ url: "https://example.net/page", title: "元のタイトル" }]
    const request = buildRequest("addBookmarks", "名前の欄で書き換えたタイトル", tabs)
    expect(JSON.parse(JSON.stringify(request))).toEqual(contract("add-bookmarks.json"))
  })

  it("タブが 2 つ以上のブックマークは各タブのタイトルをそのまま送り、name を持たない", () => {
    const tabs = [
      { url: "https://example.com/", title: "A" },
      { url: "https://example.org/", title: "" },
    ]
    const request = buildRequest("addBookmarks", "それぞれのタブのタイトルを使います。", tabs)
    expect(request).toEqual({ protocol: 1, type: "addBookmarks", tabs })
    expect(request).not.toHaveProperty("name")
  })

  it("タブが 1 つのブックマークで名前の欄が空なら、タイトルは空文字になる", () => {
    const request = buildRequest("addBookmarks", "", [{ url: "https://example.com/", title: "A" }])
    expect(request).toEqual({ protocol: 1, type: "addBookmarks", tabs: [{ url: "https://example.com/", title: "" }] })
  })

  it("フォルダの名前は入力のまま送る", () => {
    const request = buildRequest("addFolder", "  名前 ", [{ url: "https://example.com/", title: "A" }])
    expect(request).toMatchObject({ type: "addFolder", name: "  名前 " })
  })
})
