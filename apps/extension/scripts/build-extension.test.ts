import { mkdtemp, readdir, readFile, rm } from "node:fs/promises"
import os from "node:os"
import path from "node:path"
import { afterAll, beforeAll, describe, expect, it } from "vitest"
import { RELEASE_REPOSITORY } from "../src/firefox-release"
import { buildManifest } from "../src/manifest"
import { VARIANTS, type Variant } from "../src/variants"
import { buildExtension, LICENSE_FILES, outputDirectory, REPOSITORY_ROOT, TARGETS, VARIANT_NAMES } from "./build-extension"

const VERSION = "1.2.3"

/** 4 つの拡張を作る時間の上限（ミリ秒）。 */
const BUILD_TIMEOUT = 60_000

let outRoot: string

beforeAll(async () => {
  outRoot = await mkdtemp(path.join(os.tmpdir(), "cloud-marks-extension-"))
  await buildExtension(outRoot, VERSION, "silent")
}, BUILD_TIMEOUT)

afterAll(async () => {
  await rm(outRoot, { recursive: true, force: true })
})

/** 配布版なら開発版、開発版なら配布版。 */
function otherVariant(variant: Variant): Variant {
  return variant === "distributed" ? "dev" : "distributed"
}

/** ディレクトリ `dir` の直下の .js をすべてつなげたもの。 */
async function scriptsIn(dir: string): Promise<string> {
  const names = (await readdir(dir)).filter((name) => name.endsWith(".js"))
  const contents = await Promise.all(names.map((name) => readFile(path.join(dir, name), "utf8")))
  return contents.join("\n")
}

describe("buildExtension", () => {
  it("配布版は <出力先>/chrome と firefox、開発版は <出力先>/dev/chrome と dev/firefox に、それぞれの manifest を書く", async () => {
    expect(outputDirectory(outRoot, "distributed", "chrome")).toBe(path.join(outRoot, "chrome"))
    expect(outputDirectory(outRoot, "dev", "firefox")).toBe(path.join(outRoot, "dev", "firefox"))
    for (const variant of VARIANT_NAMES) {
      for (const target of TARGETS) {
        const text = await readFile(path.join(outputDirectory(outRoot, variant, target), "manifest.json"), "utf8")
        expect(JSON.parse(text)).toEqual(buildManifest(target, VERSION, variant))
      }
    }
  })

  it("スクリプトに、配布版なら配布版の、開発版なら開発版のホストの名前・呼び出しの URL のホスト・拡張の名前を埋め込む", async () => {
    for (const variant of VARIANT_NAMES) {
      const identity = VARIANTS[variant]
      for (const target of TARGETS) {
        const directory = outputDirectory(outRoot, variant, target)
        const background = await readFile(path.join(directory, "background.js"), "utf8")
        const popup = await readFile(path.join(directory, "popup.js"), "utf8")
        expect(background).toContain(JSON.stringify(identity.hostName))
        expect(background).toContain(JSON.stringify(identity.callUrlHost))
        // ツールバーのボタンの説明を元に戻すのはポップアップ
        expect(popup).toContain(JSON.stringify(identity.extensionName))
      }
    }
  })

  it("どのスクリプトにも、もう一方のホストの名前・呼び出しの URL のホスト・拡張の名前と、埋め込む前の名前が残らない", async () => {
    for (const variant of VARIANT_NAMES) {
      const other = VARIANTS[otherVariant(variant)]
      for (const target of TARGETS) {
        const scripts = await scriptsIn(outputDirectory(outRoot, variant, target))
        expect(scripts).not.toContain(JSON.stringify(other.hostName))
        expect(scripts).not.toContain(other.callUrlHost)
        expect(scripts).not.toContain(JSON.stringify(other.extensionName))
        expect(scripts).not.toContain("CMM_")
      }
    }
  })

  it("どのスクリプトにも、ビルドだけが使うリリースの URL（GitHub の releases/download）が入らない", async () => {
    for (const variant of VARIANT_NAMES) {
      for (const target of TARGETS) {
        const scripts = await scriptsIn(outputDirectory(outRoot, variant, target))
        expect(scripts).not.toContain("releases/download")
        expect(scripts).not.toContain(RELEASE_REPOSITORY)
      }
    }
  })

  it("4 つの拡張の直下に、リポジトリの直下の LICENSE と THIRD-PARTY-NOTICES.md を、同じ中身で写す", async () => {
    expect(LICENSE_FILES).toEqual(["LICENSE", "THIRD-PARTY-NOTICES.md"])
    for (const name of LICENSE_FILES) {
      const original = await readFile(path.join(REPOSITORY_ROOT, name))
      for (const variant of VARIANT_NAMES) {
        for (const target of TARGETS) {
          const copied = await readFile(path.join(outputDirectory(outRoot, variant, target), name))
          expect(copied.equals(original), `${variant} の ${target} の ${name}`).toBe(true)
        }
      }
    }
  })
})

describe("buildExtension のライセンスのファイル", () => {
  let emptyDirectory: string
  let missingOutRoot: string

  beforeAll(async () => {
    emptyDirectory = await mkdtemp(path.join(os.tmpdir(), "cloud-marks-extension-licenses-"))
    missingOutRoot = await mkdtemp(path.join(os.tmpdir(), "cloud-marks-extension-missing-"))
  })

  afterAll(async () => {
    await rm(emptyDirectory, { recursive: true, force: true })
    await rm(missingOutRoot, { recursive: true, force: true })
  })

  it("写すファイルが無ければ、そのパスを挙げて失敗し、拡張を作らない", async () => {
    await expect(buildExtension(missingOutRoot, VERSION, "silent", emptyDirectory)).rejects.toThrow(
      `拡張に入れるファイルが無い: ${path.join(emptyDirectory, "LICENSE")}`,
    )
    expect(await readdir(missingOutRoot)).toEqual([])
  })
})
