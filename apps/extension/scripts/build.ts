import { build } from "esbuild"
import { copyFile, mkdir, readFile, rm, writeFile } from "node:fs/promises"
import path from "node:path"
import {
  BACKGROUND_FILE,
  buildManifest,
  ICON_SIZES,
  iconPath,
  PLACEHOLDER_FILE,
  POPUP_FILE,
  type BrowserTarget,
} from "../src/manifest"

const root = path.resolve(import.meta.dirname, "..")
const targets: BrowserTarget[] = ["chrome", "firefox"]

const pkg = JSON.parse(await readFile(path.join(root, "package.json"), "utf8")) as { version: string }

for (const target of targets) {
  const outDir = path.join(root, "dist", target)
  await rm(outDir, { recursive: true, force: true })
  await mkdir(outDir, { recursive: true })

  // background と popup と仮のページは ES モジュールとして読み込まれるので ES モジュールで出力する。
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
    logLevel: "info",
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

  const manifest = buildManifest(target, pkg.version)
  await writeFile(path.join(outDir, "manifest.json"), JSON.stringify(manifest, null, 2) + "\n")
}
