import { createHash } from "node:crypto"
import { describe, expect, it } from "vitest"
import {
  BACKGROUND_FILE,
  buildManifest,
  CHROME_EXTENSION_ID,
  CHROME_KEY,
  FIREFOX_MIN_VERSION,
  GECKO_ID,
  POPUP_FILE,
} from "./manifest"
import { MESSAGES } from "./messages"

const PERMISSIONS = ["tabs", "nativeMessaging", "storage"]

describe("buildManifest", () => {
  it("共通部分は両ブラウザで同じ", () => {
    for (const target of ["chrome", "firefox"] as const) {
      const manifest = buildManifest(target, "1.2.3")
      expect(manifest.manifest_version).toBe(3)
      expect(manifest.name).toBe("Cloud Marks")
      expect(manifest.version).toBe("1.2.3")
      expect(manifest.permissions).toEqual(PERMISSIONS)
      expect(manifest.action).toEqual({ default_popup: "popup.html", default_title: "Cloud Marks" })
      expect(manifest.action.default_popup).toBe(POPUP_FILE)
      expect(manifest.action.default_title).toBe(MESSAGES.defaultActionTitle)
      expect(manifest.background.type).toBe("module")
    }
  })

  it("Chrome は service_worker と key を持ち、scripts と gecko を持たない", () => {
    const manifest = buildManifest("chrome", "1.0.0")
    expect(manifest.background).toEqual({ service_worker: BACKGROUND_FILE, type: "module" })
    expect(manifest.background).not.toHaveProperty("scripts")
    expect(manifest).not.toHaveProperty("browser_specific_settings")
    expect(manifest.key).toBe(CHROME_KEY)
  })

  it("CHROME_KEY から決まる拡張の ID が CHROME_EXTENSION_ID と一致する", () => {
    // ID は公開鍵（DER）の SHA-256 の先頭 16 バイトを、16 進の 0〜f を a〜p に置き換えたもの。
    const der = Buffer.from(CHROME_KEY, "base64")
    const hex = createHash("sha256").update(der).digest("hex").slice(0, 32)
    const id = Array.from(hex, (digit) => String.fromCharCode("a".charCodeAt(0) + parseInt(digit, 16))).join("")
    expect(id).toBe(CHROME_EXTENSION_ID)
    expect(CHROME_EXTENSION_ID).toBe("laeogpkfjeccgfkedalbkcbenakkefpl")
  })

  it("Firefox は scripts と gecko を持ち、service_worker と key を持たない", () => {
    const manifest = buildManifest("firefox", "1.0.0")
    expect(manifest.background).toEqual({ scripts: [BACKGROUND_FILE], type: "module" })
    expect(manifest.background).not.toHaveProperty("service_worker")
    expect(manifest).not.toHaveProperty("key")
    expect(manifest.browser_specific_settings).toEqual({
      gecko: { id: GECKO_ID, strict_min_version: FIREFOX_MIN_VERSION },
    })
  })

  it("呼び出しごとに permissions と action を共有しない", () => {
    const a = buildManifest("chrome", "1.0.0")
    const b = buildManifest("firefox", "1.0.0")
    a.permissions.push("x")
    a.action.default_title = "x"
    expect(b.permissions).toEqual(PERMISSIONS)
    expect(b.action.default_title).toBe("Cloud Marks")
  })
})
