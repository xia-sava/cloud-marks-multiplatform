import { readFile } from "node:fs/promises"
import path from "node:path"
import { writeFirefoxUpdates } from "./firefox-updates"

// 配布版の Firefox の拡張の更新情報（updates.json）を書く。バージョンは package.json の version。
//
//   npm run updates-json -- <署名済みの .xpi> <書き出す updates.json>
//
// 相対パスは apps/extension から解決する。
const root = path.resolve(import.meta.dirname, "..")

const args = process.argv.slice(2)
if (args.length !== 2) {
  console.error("使い方: npm run updates-json -- <署名済みの .xpi> <書き出す updates.json>")
  process.exit(2)
}
const [xpi, output] = args.map((arg) => path.resolve(arg))

const pkg = JSON.parse(await readFile(path.join(root, "package.json"), "utf8")) as { version: string }

const updates = await writeFirefoxUpdates(xpi, pkg.version, output)
console.log(JSON.stringify(updates, null, 2))
