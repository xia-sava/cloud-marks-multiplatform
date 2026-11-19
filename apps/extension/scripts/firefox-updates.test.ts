import { createHash } from "node:crypto"
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises"
import os from "node:os"
import path from "node:path"
import { afterEach, beforeEach, describe, expect, it } from "vitest"
import { FIREFOX_UPDATE_URL, releaseAssetUrl, versionedReleaseTag, versionedXpiFile } from "../src/firefox-release"
import { buildManifest } from "../src/manifest"
import { contract } from "../src/test-support"
import { distributedGeckoId, firefoxUpdates, sha256Of, writeFirefoxUpdates } from "./firefox-updates"

const VERSION = "1.2.3"

const SHA256 = "0123456789abcdef".repeat(4)

let directory: string

beforeEach(async () => {
  directory = await mkdtemp(path.join(os.tmpdir(), "cloud-marks-firefox-updates-"))
})

afterEach(async () => {
  await rm(directory, { recursive: true, force: true })
})

describe("firefoxUpdates", () => {
  it("gecko の ID をキーに、バージョン・バージョンごとのリリースの .xpi の URL・sha256: の付いたハッシュを 1 件だけ載せる", () => {
    expect(firefoxUpdates("addon@example.invalid", VERSION, SHA256)).toEqual({
      addons: {
        "addon@example.invalid": {
          updates: [
            {
              version: VERSION,
              update_link:
                "https://github.com/xia-sava/cloud-marks-multiplatform/releases/download/extension-v1.2.3/cloudmarks-firefox-1.2.3.xpi",
              update_hash: `sha256:${SHA256}`,
            },
          ],
        },
      },
    })
  })

  it("update_link は、update_url と同じリポジトリの、バージョンごとのリリースの .xpi を指す", () => {
    const [update] = firefoxUpdates("addon@example.invalid", VERSION, SHA256).addons["addon@example.invalid"].updates
    expect(update.update_link).toBe(releaseAssetUrl(versionedReleaseTag(VERSION), versionedXpiFile(VERSION)))
    expect(new URL(update.update_link).pathname.split("/").slice(1, 3)).toEqual(
      new URL(FIREFOX_UPDATE_URL).pathname.split("/").slice(1, 3),
    )
  })

  it.each([
    ["大文字の 16 進", SHA256.toUpperCase()],
    ["63 文字", SHA256.slice(1)],
    ["sha256: の付いたもの", `sha256:${SHA256}`],
  ])("SHA-256 が小文字の 16 進で 64 文字でないもの（%s）は断る", (_, sha256) => {
    expect(() => firefoxUpdates("addon@example.invalid", VERSION, sha256)).toThrow()
  })
})

describe("distributedGeckoId", () => {
  it("identities.json の配布版の gecko の ID を読み、それは配布版の Firefox の manifest の ID と同じ", async () => {
    const geckoId = await distributedGeckoId()
    expect(geckoId).toBe((contract("identities.json") as { distributed: { geckoId: string } }).distributed.geckoId)
    expect(geckoId).toBe(buildManifest("firefox", VERSION, "distributed").browser_specific_settings?.gecko.id)
  })

  it("配布版の組に gecko の ID が無ければ失敗する", async () => {
    const identities = path.join(directory, "identities.json")
    await writeFile(identities, JSON.stringify({ distributed: { hostName: "x" }, dev: { geckoId: "dev@example.invalid" } }))
    await expect(distributedGeckoId(identities)).rejects.toThrow("geckoId")
  })
})

describe("writeFirefoxUpdates", () => {
  it(".xpi の SHA-256 を小文字の 16 進で update_hash に入れ、整形した JSON を改行で終えて書く", async () => {
    const xpi = path.join(directory, "signed.xpi")
    const output = path.join(directory, "updates.json")
    const content = Buffer.from("signed extension")
    await writeFile(xpi, content)
    const identities = path.join(directory, "identities.json")
    await writeFile(identities, JSON.stringify({ distributed: { geckoId: "addon@example.invalid" } }))

    const written = await writeFirefoxUpdates(xpi, VERSION, output, identities)

    const expectedSha256 = createHash("sha256").update(content).digest("hex")
    expect(await sha256Of(xpi)).toBe(expectedSha256)
    expect(written).toEqual(firefoxUpdates("addon@example.invalid", VERSION, expectedSha256))
    const text = await readFile(output, "utf8")
    expect(text).toBe(JSON.stringify(written, null, 2) + "\n")
    expect(written.addons["addon@example.invalid"].updates[0].update_hash).toMatch(/^sha256:[0-9a-f]{64}$/)
  })
})
