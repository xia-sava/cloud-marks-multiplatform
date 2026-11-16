/** manifest.json を書き出す対象のブラウザ。 */
export type BrowserTarget = "chrome" | "firefox"

export interface ExtensionManifest {
  manifest_version: 3
  name: string
  version: string
  permissions: string[]
  action: Record<string, never>
  background: {
    service_worker?: string
    scripts?: string[]
    type: "module"
  }
  browser_specific_settings?: {
    gecko: {
      id: string
      strict_min_version: string
    }
  }
}

/** バンドル後の background スクリプトのファイル名（dist/<target>/ 直下）。 */
export const BACKGROUND_FILE = "background.js"

export const GECKO_ID = "cloud-marks-multiplatform@sava.to"

// background の "type": "module" が使える最初の版。
export const FIREFOX_MIN_VERSION = "112.0"

/**
 * 共通部分にブラウザごとの差分を重ねた manifest を返す。
 * Chrome は background に service_worker を、Firefox は scripts を使う
 * （Firefox の MV3 は service worker を使えない）。
 */
export function buildManifest(target: BrowserTarget, version: string): ExtensionManifest {
  const common = {
    manifest_version: 3,
    name: "Cloud Marks",
    version,
    permissions: ["tabs", "nativeMessaging"],
    action: {},
  } as const

  if (target === "chrome") {
    return {
      ...common,
      permissions: [...common.permissions],
      background: { service_worker: BACKGROUND_FILE, type: "module" },
    }
  }

  return {
    ...common,
    permissions: [...common.permissions],
    background: { scripts: [BACKGROUND_FILE], type: "module" },
    browser_specific_settings: {
      gecko: { id: GECKO_ID, strict_min_version: FIREFOX_MIN_VERSION },
    },
  }
}
