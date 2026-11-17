import { build } from "esbuild"
import { copyFile, mkdir, readFile, rm, writeFile } from "node:fs/promises"
import path from "node:path"
import { BACKGROUND_FILE, buildManifest, POPUP_FILE, type BrowserTarget } from "../src/manifest"

const root = path.resolve(import.meta.dirname, "..")
const targets: BrowserTarget[] = ["chrome", "firefox"]

const pkg = JSON.parse(await readFile(path.join(root, "package.json"), "utf8")) as { version: string }

for (const target of targets) {
  const outDir = path.join(root, "dist", target)
  await rm(outDir, { recursive: true, force: true })
  await mkdir(outDir, { recursive: true })

  // background と popup は ES モジュールとして読み込まれるので ES モジュールで出力する。
  await build({
    entryPoints: {
      [path.parse(BACKGROUND_FILE).name]: path.join(root, "src", "background.ts"),
      popup: path.join(root, "src", "popup.ts"),
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

  const manifest = buildManifest(target, pkg.version)
  await writeFile(path.join(outDir, "manifest.json"), JSON.stringify(manifest, null, 2) + "\n")
}
