import { createHash } from "node:crypto"
import { describe, expect, it } from "vitest"
import { CALL_URL_HOST } from "./command"
import { BACKGROUND_FILE, iconPath, POPUP_FILE } from "./extension-files"
import { FIREFOX_UPDATE_URL } from "./firefox-release"
import { buildManifest, FIREFOX_MIN_VERSION } from "./manifest"
import { HOST_NAME } from "./protocol"
import { contract } from "./test-support"
import { VARIANTS, type Variant } from "./variants"

const PERMISSIONS = ["tabs", "nativeMessaging", "storage"]

const ICONS = {
  "16": "icons/icon-16.png",
  "32": "icons/icon-32.png",
  "48": "icons/icon-48.png",
  "128": "icons/icon-128.png",
}

const VARIANT_NAMES: Variant[] = ["distributed", "dev"]

const EXTENSION_NAMES: Record<Variant, string> = { distributed: "Cloud Marks", dev: "Cloud Marks (dev)" }

/** `contracts/native-messaging/identities.json` の、`variant` が配布版か開発版かの組。 */
function identityOf(variant: Variant): Record<string, unknown> {
  return (contract("identities.json") as Record<Variant, Record<string, unknown>>)[variant]
}

/** Chrome の拡張の ID。公開鍵（DER）の SHA-256 の先頭 16 バイトを、16 進の 0〜f を a〜p に置き換えたもの。 */
function chromeExtensionIdOf(key: string): string {
  const hex = createHash("sha256").update(Buffer.from(key, "base64")).digest("hex").slice(0, 32)
  return Array.from(hex, (digit) => String.fromCharCode("a".charCodeAt(0) + parseInt(digit, 16))).join("")
}

describe("buildManifest", () => {
  it("共通部分は両ブラウザで同じ。拡張の名前とツールバーのボタンの説明は、配布版が Cloud Marks、開発版が Cloud Marks (dev)", () => {
    for (const variant of VARIANT_NAMES) {
      for (const target of ["chrome", "firefox"] as const) {
        const manifest = buildManifest(target, "1.2.3", variant)
        expect(manifest.manifest_version).toBe(3)
        expect(manifest.name).toBe(EXTENSION_NAMES[variant])
        expect(manifest.version).toBe("1.2.3")
        expect(manifest.permissions).toEqual(PERMISSIONS)
        expect(manifest.action).toEqual({
          default_popup: "popup.html",
          default_title: EXTENSION_NAMES[variant],
          default_icon: ICONS,
        })
        expect(manifest.action.default_popup).toBe(POPUP_FILE)
        expect(manifest.background.type).toBe("module")
      }
    }
  })

  it("拡張とツールバーのボタンのアイコンに、16・32・48・128 px の icons/icon-<size>.png を指定する", () => {
    for (const target of ["chrome", "firefox"] as const) {
      const manifest = buildManifest(target, "1.0.0", "distributed")
      expect(manifest.icons).toEqual(ICONS)
      expect(manifest.action.default_icon).toEqual(ICONS)
    }
    expect(iconPath(48)).toBe("icons/icon-48.png")
  })

  it("仮のページはウェブのページから開けないよう、web_accessible_resources を持たない", () => {
    for (const target of ["chrome", "firefox"] as const) {
      expect(buildManifest(target, "1.0.0", "distributed")).not.toHaveProperty("web_accessible_resources")
    }
  })

  it("Chrome は service_worker と、配布版なら配布版の、開発版なら開発版の key を持ち、scripts と gecko を持たない", () => {
    for (const variant of VARIANT_NAMES) {
      const manifest = buildManifest("chrome", "1.0.0", variant)
      expect(manifest.background).toEqual({ service_worker: BACKGROUND_FILE, type: "module" })
      expect(manifest.background).not.toHaveProperty("scripts")
      expect(manifest).not.toHaveProperty("browser_specific_settings")
      expect(manifest.key).toBe(VARIANTS[variant].chromeKey)
    }
  })

  it("Firefox は scripts と、配布版なら配布版の、開発版なら開発版の gecko の ID を持ち、service_worker と key を持たない", () => {
    for (const variant of VARIANT_NAMES) {
      const manifest = buildManifest("firefox", "1.0.0", variant)
      expect(manifest.background).toEqual({ scripts: [BACKGROUND_FILE], type: "module" })
      expect(manifest.background).not.toHaveProperty("service_worker")
      expect(manifest).not.toHaveProperty("key")
      expect(manifest.browser_specific_settings?.gecko.id).toBe(VARIANTS[variant].geckoId)
      expect(manifest.browser_specific_settings?.gecko.strict_min_version).toBe(FIREFOX_MIN_VERSION)
      expect(manifest.browser_specific_settings).not.toHaveProperty("gecko_android")
    }
  })

  it("Firefox の最小のバージョンは、data_collection_permissions をデスクトップと Android の両方で使える 142.0", () => {
    expect(FIREFOX_MIN_VERSION).toBe("142.0")
  })

  it("Firefox は配布版も開発版も、送るデータの種類に browsingActivity だけを必須として持つ", () => {
    for (const variant of VARIANT_NAMES) {
      const manifest = buildManifest("firefox", "1.0.0", variant)
      expect(manifest.browser_specific_settings?.gecko.data_collection_permissions).toEqual({
        required: ["browsingActivity"],
      })
    }
  })

  it("配布版の Firefox だけが、extension-latest のリリースの updates.json を指す update_url を持つ", () => {
    const distributed = buildManifest("firefox", "1.0.0", "distributed")
    expect(distributed.browser_specific_settings).toEqual({
      gecko: {
        id: VARIANTS.distributed.geckoId,
        strict_min_version: FIREFOX_MIN_VERSION,
        update_url:
          "https://github.com/xia-sava/cloud-marks-multiplatform/releases/download/extension-latest/updates.json",
        data_collection_permissions: { required: ["browsingActivity"] },
      },
    })
    expect(distributed.browser_specific_settings?.gecko.update_url).toBe(FIREFOX_UPDATE_URL)

    const dev = buildManifest("firefox", "1.0.0", "dev")
    expect(dev.browser_specific_settings?.gecko).not.toHaveProperty("update_url")
    expect(dev.browser_specific_settings).toEqual({
      gecko: {
        id: VARIANTS.dev.geckoId,
        strict_min_version: FIREFOX_MIN_VERSION,
        data_collection_permissions: { required: ["browsingActivity"] },
      },
    })
  })

  it("Chrome は配布版も開発版も、update_url と data_collection_permissions を持たない", () => {
    for (const variant of VARIANT_NAMES) {
      const text = JSON.stringify(buildManifest("chrome", "1.0.0", variant))
      expect(text).not.toContain("update_url")
      expect(text).not.toContain("data_collection_permissions")
    }
  })

  it("呼び出しごとに permissions と action と icons を共有しない", () => {
    const a = buildManifest("chrome", "1.0.0", "distributed")
    const b = buildManifest("firefox", "1.0.0", "dev")
    a.permissions.push("x")
    a.action.default_title = "x"
    a.action.default_icon["16"] = "x"
    a.icons["16"] = "x"
    expect(b.permissions).toEqual(PERMISSIONS)
    expect(b.action.default_title).toBe("Cloud Marks (dev)")
    expect(b.action.default_icon).toEqual(ICONS)
    expect(b.icons).toEqual(ICONS)
  })

  it("呼び出しごとに data_collection_permissions を共有しない", () => {
    const a = buildManifest("firefox", "1.0.0", "distributed")
    const b = buildManifest("firefox", "1.0.0", "dev")
    a.browser_specific_settings?.gecko.data_collection_permissions.required.push("x")
    expect(b.browser_specific_settings?.gecko.data_collection_permissions).toEqual({ required: ["browsingActivity"] })
    expect(buildManifest("firefox", "1.0.0", "distributed").browser_specific_settings?.gecko.data_collection_permissions)
      .toEqual({ required: ["browsingActivity"] })
  })

  it("icons と action.default_icon は同じものを共有しない", () => {
    const manifest = buildManifest("chrome", "1.0.0", "distributed")
    manifest.icons["16"] = "x"
    expect(manifest.action.default_icon).toEqual(ICONS)
  })
})

describe("VARIANTS", () => {
  it("配布版も開発版も、key から決まる Chrome の拡張の ID が宣言した ID と一致する", () => {
    for (const variant of VARIANT_NAMES) {
      expect(chromeExtensionIdOf(VARIANTS[variant].chromeKey)).toBe(VARIANTS[variant].chromeExtensionId)
    }
  })

  it("配布版も開発版も、ホストの名前・Chrome の拡張の ID と key・gecko の ID・呼び出しの URL のホストが identities.json と一致する", () => {
    for (const variant of VARIANT_NAMES) {
      const { extensionName, ...identity } = VARIANTS[variant]
      expect(identity).toEqual(identityOf(variant))
      expect(extensionName).toBe(EXTENSION_NAMES[variant])
    }
  })

  it("配布版と開発版で、ホストの名前・拡張の ID・呼び出しの URL のホストが違う", () => {
    const { distributed, dev } = VARIANTS
    expect(dev.hostName).not.toBe(distributed.hostName)
    expect(dev.chromeExtensionId).not.toBe(distributed.chromeExtensionId)
    expect(dev.geckoId).not.toBe(distributed.geckoId)
    expect(dev.callUrlHost).not.toBe(distributed.callUrlHost)
  })

  it("テストでは、vitest.config.ts が埋め込んだ配布版のホストの名前と呼び出しの URL のホストを使う", () => {
    expect(HOST_NAME).toBe(VARIANTS.distributed.hostName)
    expect(CALL_URL_HOST).toBe(VARIANTS.distributed.callUrlHost)
  })
})
