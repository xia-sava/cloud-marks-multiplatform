// 拡張の中のファイルの名前。ビルド（scripts/build-extension.ts）と manifest、拡張のコード（open-tabs.ts）が使う。
// 拡張のコードが読み込むので、ここには値だけを置き、ほかのモジュールを読まない。

/** バンドル後の background スクリプトのファイル名（dist/<target>/ 直下）。 */
export const BACKGROUND_FILE = "background.js"

/** バンドル後のポップアップのファイル名（dist/<target>/ 直下）。 */
export const POPUP_FILE = "popup.html"

/** バンドル後の仮のページのファイル名（dist/<target>/ 直下）。 */
export const PLACEHOLDER_FILE = "placeholder.html"

/** アイコンの大きさ（px）。 */
export const ICON_SIZES = [16, 32, 48, 128] as const

/** アイコンのファイルの、拡張の直下からのパス。ビルドが icons/ を dist/<target>/icons/ に写す。 */
export function iconPath(size: number): string {
  return `icons/icon-${size}.png`
}
