import { build, type LogLevel } from "esbuild"
import { copyFile, mkdir, rm, stat, writeFile } from "node:fs/promises"
import path from "node:path"
import { BACKGROUND_FILE, ICON_SIZES, iconPath, PLACEHOLDER_FILE, POPUP_FILE } from "../src/extension-files"
import { buildManifest, type BrowserTarget } from "../src/manifest"
import { variantDefines, type Variant } from "../src/variants"

/** 拡張のディレクトリ（apps/extension）。 */
const root = path.resolve(import.meta.dirname, "..")

/** リポジトリの直下。拡張に入れるライセンスのファイルを置く。 */
export const REPOSITORY_ROOT = path.resolve(root, "../..")

/**
 * 拡張の直下に写す、リポジトリのライセンスと、拡張に入るもの（アイコン）の出典を含む第三者の表記。
 * リポジトリの直下から写す。
 */
export const LICENSE_FILES = ["LICENSE", "THIRD-PARTY-NOTICES.md"] as const

export const TARGETS: readonly BrowserTarget[] = ["chrome", "firefox"]

export const VARIANT_NAMES: readonly Variant[] = ["distributed", "dev"]

/**
 * ブラウザ `target` 向けの、`variant` が配布版か開発版かの拡張の出力先。
 * 配布版は `<outRoot>/<target>`、開発版は `<outRoot>/dev/<target>`。
 */
export function outputDirectory(outRoot: string, variant: Variant, target: BrowserTarget): string {
  return variant === "distributed" ? path.join(outRoot, target) : path.join(outRoot, "dev", target)
}

/**
 * 配布版と開発版の、Chrome と Firefox 向けの拡張を `outRoot` の下に作る。拡張のバージョンは `version`。
 * esbuild のログは `logLevel` まで出す。
 * 各拡張の直下には、`licenseDirectory` の `LICENSE_FILES` を写す。どれかが無ければ、何も作らずに失敗する。
 */
export async function buildExtension(
  outRoot: string,
  version: string,
  logLevel: LogLevel = "info",
  licenseDirectory: string = REPOSITORY_ROOT,
): Promise<void> {
  const licenses = await licenseFilesIn(licenseDirectory)
  for (const variant of VARIANT_NAMES) {
    for (const target of TARGETS) {
      await buildVariant(outputDirectory(outRoot, variant, target), version, variant, target, logLevel, licenses)
    }
  }
}

/** `directory` の `LICENSE_FILES` のパス。どれかがファイルとして無ければ、そのパスを挙げて失敗する。 */
async function licenseFilesIn(directory: string): Promise<string[]> {
  const files = LICENSE_FILES.map((name) => path.join(directory, name))
  for (const file of files) {
    const isFile = await stat(file).then(
      (stats) => stats.isFile(),
      (error: NodeJS.ErrnoException) => {
        if (error.code === "ENOENT") return false
        throw error
      },
    )
    if (!isFile) throw new Error(`拡張に入れるファイルが無い: ${file}`)
  }
  return files
}

async function buildVariant(
  outDir: string,
  version: string,
  variant: Variant,
  target: BrowserTarget,
  logLevel: LogLevel,
  licenses: readonly string[],
): Promise<void> {
  await rm(outDir, { recursive: true, force: true })
  await mkdir(outDir, { recursive: true })

  // background と popup と仮のページは ES モジュールとして読み込まれるので ES モジュールで出力する。
  // 配布版には配布版の、開発版には開発版の、ホストの名前・呼び出しの URL のホスト・拡張の名前を define で埋め込む。
  await build({
    entryPoints: {
      [path.parse(BACKGROUND_FILE).name]: path.join(root, "src", "background.ts"),
      popup: path.join(root, "src", "popup.ts"),
      [path.parse(PLACEHOLDER_FILE).name]: path.join(root, "src", "placeholder.ts"),
    },
    outdir: outDir,
    bundle: true,
    format: "esm",
    platform: "browser",
    target: "es2022",
    sourcemap: false,
    define: variantDefines(variant),
    logLevel,
  })

  await copyFile(path.join(root, "src", "popup.html"), path.join(outDir, POPUP_FILE))
  await copyFile(path.join(root, "src", "popup.css"), path.join(outDir, "popup.css"))
  await copyFile(path.join(root, "src", "placeholder.html"), path.join(outDir, PLACEHOLDER_FILE))
  await copyFile(path.join(root, "src", "placeholder.css"), path.join(outDir, "placeholder.css"))

  for (const size of ICON_SIZES) {
    const destination = path.join(outDir, iconPath(size))
    await mkdir(path.dirname(destination), { recursive: true })
    await copyFile(path.join(root, iconPath(size)), destination)
  }

  for (const license of licenses) {
    await copyFile(license, path.join(outDir, path.basename(license)))
  }

  const manifest = buildManifest(target, version, variant)
  await writeFile(path.join(outDir, "manifest.json"), JSON.stringify(manifest, null, 2) + "\n")
}
