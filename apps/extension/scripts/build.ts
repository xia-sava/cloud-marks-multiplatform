import { build } from "esbuild"
import { mkdir, readFile, rm, writeFile } from "node:fs/promises"
import path from "node:path"
import { BACKGROUND_FILE, buildManifest, type BrowserTarget } from "../src/manifest"

const root = path.resolve(import.meta.dirname, "..")
const targets: BrowserTarget[] = ["chrome", "firefox"]

const pkg = JSON.parse(await readFile(path.join(root, "package.json"), "utf8")) as { version: string }

for (const target of targets) {
  const outDir = path.join(root, "dist", target)
  await rm(outDir, { recursive: true, force: true })
  await mkdir(outDir, { recursive: true })

  // background は type: "module" で読み込まれるので ES モジュールで出力する。
  await build({
    entryPoints: [path.join(root, "src", "background.ts")],
    outfile: path.join(outDir, BACKGROUND_FILE),
    bundle: true,
    format: "esm",
    platform: "browser",
    target: "es2022",
    sourcemap: false,
    logLevel: "info",
  })

  const manifest = buildManifest(target, pkg.version)
  await writeFile(path.join(outDir, "manifest.json"), JSON.stringify(manifest, null, 2) + "\n")
}
