import { readFileSync } from "node:fs"
import path from "node:path"

/** `contracts/native-messaging/` の見本を読む。テストだけが使う。 */
export function contract(file: string): unknown {
  const full = path.resolve(import.meta.dirname, "../../../contracts/native-messaging", file)
  return JSON.parse(readFileSync(full, "utf8"))
}
