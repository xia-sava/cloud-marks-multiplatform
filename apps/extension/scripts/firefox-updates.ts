import { createHash } from "node:crypto"
import { readFile, writeFile } from "node:fs/promises"
import path from "node:path"
import { releaseAssetUrl, versionedReleaseTag, versionedXpiFile } from "../src/firefox-release"

/** 配布版と開発版の名前の一覧（`contracts/native-messaging/identities.json`）。 */
export const IDENTITIES_FILE = path.resolve(import.meta.dirname, "../../../contracts/native-messaging/identities.json")

/** 更新情報の、1 つのバージョンの項目。 */
export interface FirefoxUpdate {
  version: string
  /** 署名済みの .xpi をダウンロードする URL。 */
  update_link: string
  /** `update_link` の .xpi の SHA-256。`sha256:` に小文字の 16 進を続けたもの。 */
  update_hash: string
}

/**
 * 配布版の Firefox の拡張の update_url に置く更新情報（updates.json）。キーは gecko の ID
 * （https://extensionworkshop.com/documentation/manage/updating-your-extension/）。
 */
export interface FirefoxUpdates {
  addons: Record<string, { updates: FirefoxUpdate[] }>
}

/** SHA-256 の小文字の 16 進。 */
const SHA256_HEX = /^[0-9a-f]{64}$/

/**
 * gecko の ID が `geckoId` の拡張の、バージョン `version` だけを載せた更新情報。.xpi はバージョンごとのリリースに置いたもので、
 * その SHA-256 が `sha256`（小文字の 16 進）。
 */
export function firefoxUpdates(geckoId: string, version: string, sha256: string): FirefoxUpdates {
  if (!SHA256_HEX.test(sha256)) {
    throw new Error(`SHA-256 は小文字の 16 進で 64 文字にする（受け取った値: ${sha256}）`)
  }
  return {
    addons: {
      [geckoId]: {
        updates: [
          {
            version,
            update_link: releaseAssetUrl(versionedReleaseTag(version), versionedXpiFile(version)),
            update_hash: `sha256:${sha256}`,
          },
        ],
      },
    },
  }
}

/** 名前の一覧 `identitiesFile` の、配布版の組の gecko の ID。 */
export async function distributedGeckoId(identitiesFile: string = IDENTITIES_FILE): Promise<string> {
  const identities = JSON.parse(await readFile(identitiesFile, "utf8")) as { distributed?: { geckoId?: unknown } }
  const geckoId = identities.distributed?.geckoId
  if (typeof geckoId !== "string" || geckoId === "") {
    throw new Error(`${identitiesFile} の distributed に geckoId が無い`)
  }
  return geckoId
}

/** ファイル `file` の SHA-256 の小文字の 16 進。 */
export async function sha256Of(file: string): Promise<string> {
  return createHash("sha256").update(await readFile(file)).digest("hex")
}

/**
 * 署名済みの .xpi `xpi`（バージョン `version`）の更新情報を `output` に書き、書いたものを返す。
 * gecko の ID は、名前の一覧 `identitiesFile` の配布版の組から読む。
 */
export async function writeFirefoxUpdates(
  xpi: string,
  version: string,
  output: string,
  identitiesFile: string = IDENTITIES_FILE,
): Promise<FirefoxUpdates> {
  const updates = firefoxUpdates(await distributedGeckoId(identitiesFile), version, await sha256Of(xpi))
  await writeFile(output, JSON.stringify(updates, null, 2) + "\n")
  return updates
}
