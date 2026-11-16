import { describe, expect, it } from "vitest"
import { BACKGROUND_FILE, buildManifest, FIREFOX_MIN_VERSION, GECKO_ID } from "./manifest"

describe("buildManifest", () => {
  it("共通部分は両ブラウザで同じ", () => {
    for (const target of ["chrome", "firefox"] as const) {
      const manifest = buildManifest(target, "1.2.3")
      expect(manifest.manifest_version).toBe(3)
      expect(manifest.name).toBe("Cloud Marks")
      expect(manifest.version).toBe("1.2.3")
      expect(manifest.permissions).toEqual(["tabs", "nativeMessaging"])
      expect(manifest.action).toEqual({})
      expect(manifest.background.type).toBe("module")
    }
  })

  it("Chrome は service_worker を持ち、scripts と gecko を持たない", () => {
    const manifest = buildManifest("chrome", "1.0.0")
    expect(manifest.background).toEqual({ service_worker: BACKGROUND_FILE, type: "module" })
    expect(manifest.background).not.toHaveProperty("scripts")
    expect(manifest).not.toHaveProperty("browser_specific_settings")
  })

  it("Firefox は scripts と gecko を持ち、service_worker を持たない", () => {
    const manifest = buildManifest("firefox", "1.0.0")
    expect(manifest.background).toEqual({ scripts: [BACKGROUND_FILE], type: "module" })
    expect(manifest.background).not.toHaveProperty("service_worker")
    expect(manifest.browser_specific_settings).toEqual({
      gecko: { id: GECKO_ID, strict_min_version: FIREFOX_MIN_VERSION },
    })
  })

  it("呼び出しごとに permissions を共有しない", () => {
    const a = buildManifest("chrome", "1.0.0")
    const b = buildManifest("firefox", "1.0.0")
    a.permissions.push("x")
    expect(b.permissions).toEqual(["tabs", "nativeMessaging"])
  })
})
