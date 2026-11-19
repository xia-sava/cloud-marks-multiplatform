import { BACKGROUND_FILE, ICON_SIZES, iconPath, POPUP_FILE } from "./extension-files"
import { FIREFOX_UPDATE_URL } from "./firefox-release"
import { VARIANTS, type Variant } from "./variants"

/** manifest.json を書き出す対象のブラウザ。 */
export type BrowserTarget = "chrome" | "firefox"

/** Firefox の拡張が集めて送るデータの種類（manifest の `browser_specific_settings.gecko.data_collection_permissions`）。 */
export interface DataCollectionPermissions {
  required: string[]
}

export interface ExtensionManifest {
  manifest_version: 3
  name: string
  version: string
  permissions: string[]
  icons: Record<string, string>
  action: {
    default_popup: string
    default_title: string
    default_icon: Record<string, string>
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
      update_url?: string
      data_collection_permissions: DataCollectionPermissions
    }
  }
}

/** manifest の icons と action.default_icon に書く、大きさごとのアイコン。 */
function iconSet(): Record<string, string> {
  return Object.fromEntries(ICON_SIZES.map((size) => [String(size), iconPath(size)]))
}

/**
 * Firefox の最小のバージョン。data_collection_permissions（組み込みのデータ収集の同意）は、デスクトップの Firefox では 140、
 * Android の Firefox では 142 から使える。Android の Firefox は Native Messaging を持たないので gecko_android は置かず、
 * Android の最小のバージョンもこの値になる。142 より前にすると、`web-ext lint` が Android で使えないと警告する。
 * background の "type": "module" と storage.session（115）も、このバージョンで使える。
 */
export const FIREFOX_MIN_VERSION = "142.0"

/**
 * Firefox の拡張が送るデータの種類。拡張は、選んだタブの URL とタイトル（閲覧の履歴に当たる）を、
 * Native Messaging でデスクトップのアプリへ渡す。AMO のポリシーは、ネイティブのアプリへ送るデータにも当てはまる。
 */
export const FIREFOX_REQUIRED_DATA_COLLECTION = ["browsingActivity"] as const

/**
 * 共通部分にブラウザごとの差分を加えた、`variant` が配布版か開発版かの拡張の manifest を返す。
 * 拡張の名前（ツールバーのボタンの説明にも使う）、Chrome の key、Firefox の gecko の ID は、
 * 配布版と開発版で違う（`VARIANTS`）。
 * Chrome は background に service_worker を、Firefox は scripts を使う
 * （Firefox の MV3 は service worker を使えない）。
 * 配布版の Firefox の拡張だけが update_url を持ち、GitHub のリリースに置いた更新情報で更新される。
 * 開発版はファイルから読み込むので持たない。Chrome の拡張はインストーラで入れたフォルダから読み込むので持たない。
 */
export function buildManifest(target: BrowserTarget, version: string, variant: Variant): ExtensionManifest {
  const identity = VARIANTS[variant]
  const common = {
    manifest_version: 3,
    name: identity.extensionName,
    version,
    permissions: ["tabs", "nativeMessaging", "storage"],
    icons: iconSet(),
    action: { default_popup: POPUP_FILE, default_title: identity.extensionName, default_icon: iconSet() },
  } as const

  if (target === "chrome") {
    return {
      ...common,
      permissions: [...common.permissions],
      action: { ...common.action },
      key: identity.chromeKey,
      background: { service_worker: BACKGROUND_FILE, type: "module" },
    }
  }

  return {
    ...common,
    permissions: [...common.permissions],
    action: { ...common.action },
    background: { scripts: [BACKGROUND_FILE], type: "module" },
    browser_specific_settings: {
      gecko: {
        id: identity.geckoId,
        strict_min_version: FIREFOX_MIN_VERSION,
        ...(variant === "distributed" ? { update_url: FIREFOX_UPDATE_URL } : {}),
        data_collection_permissions: { required: [...FIREFOX_REQUIRED_DATA_COLLECTION] },
      },
    },
  }
}
