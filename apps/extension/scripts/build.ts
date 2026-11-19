import { readFile } from "node:fs/promises"
import path from "node:path"
import { buildExtension } from "./build-extension"

const root = path.resolve(import.meta.dirname, "..")

const pkg = JSON.parse(await readFile(path.join(root, "package.json"), "utf8")) as { version: string }

await buildExtension(path.join(root, "dist"), pkg.version)
