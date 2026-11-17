import { MESSAGES } from "./messages"

/** manifest.json を書き出す対象のブラウザ。 */
export type BrowserTarget = "chrome" | "firefox"

export interface ExtensionManifest {
  manifest_version: 3
  name: string
  version: string
  permissions: string[]
  action: {
    default_popup: string
    default_title: string
  }
  key?: string
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

/** バンドル後のポップアップのファイル名（dist/<target>/ 直下）。 */
export const POPUP_FILE = "popup.html"

/** Chrome の拡張の ID。`CHROME_KEY` から決まり、Native Messaging のホストの登録（allowed_origins）が指す。 */
export const CHROME_EXTENSION_ID = "laeogpkfjeccgfkedalbkcbenakkefpl"

/** Chrome の拡張の ID を `CHROME_EXTENSION_ID` に固定する公開鍵。 */
export const CHROME_KEY =
  "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA3ExUFJw8hZbKkln26D2v7f0GjWjKIkPWpfYTognWITCUlTpqYkgo8dqIy2x8wkMSRxSKs9HxdhZgAoxwXCGnTBljYFRfiyH3NcQLbXA1NsnsM1t4RpJN02wyvyURcLBqNHE6im6XGHn0gTcXU+dXe6kZOSQeUcuxRBK+C0ffERhkTRO63+MPaR/iPwgdRcY7Mg2GqdRNQgyAD8ZnoXsDIWjLwc15oZVjNa6NcnbVKyaLJHkdKerY2to7X9MLDGSUHZ4ucOjUzV+OGb4LxAR3mcczus5JXDhenU8zvw618nCizXd7UUpSSEmfI0ZOpUqnnHJHR/G7gi/FMlNThi7JawIDAQAB"

// background の "type": "module" と storage.session が使える最初のバージョン（storage.session は 115）。
export const FIREFOX_MIN_VERSION = "115.0"

/**
 * 共通部分にブラウザごとの差分を加えた manifest を返す。
 * Chrome は background に service_worker を、Firefox は scripts を使う
 * （Firefox の MV3 は service worker を使えない）。
 */
export function buildManifest(target: BrowserTarget, version: string): ExtensionManifest {
  const common = {
    manifest_version: 3,
    name: "Cloud Marks",
    version,
    permissions: ["tabs", "nativeMessaging", "storage"],
    action: { default_popup: POPUP_FILE, default_title: MESSAGES.defaultActionTitle },
  } as const

  if (target === "chrome") {
    return {
      ...common,
      permissions: [...common.permissions],
      action: { ...common.action },
      key: CHROME_KEY,
      background: { service_worker: BACKGROUND_FILE, type: "module" },
    }
  }

  return {
    ...common,
    permissions: [...common.permissions],
    action: { ...common.action },
    background: { scripts: [BACKGROUND_FILE], type: "module" },
    browser_specific_settings: {
      gecko: { id: GECKO_ID, strict_min_version: FIREFOX_MIN_VERSION },
    },
  }
}
