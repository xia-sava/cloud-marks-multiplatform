import type { ExtensionApi } from "./api"
import { isRecord } from "./protocol"

/** 前回の追加先を `storage.local` に入れるキー。 */
export const LAST_FOLDER_STORAGE_KEY = "lastFolder"

/** 追加先のフォルダ。`name` はフォルダの名前で、ルートフォルダは空。 */
export interface FolderRef {
  id: string
  name: string
}

export function isFolderRef(value: unknown): value is FolderRef {
  return isRecord(value) && typeof value.id === "string" && value.id !== "" && typeof value.name === "string"
}

/** 前回の追加先を覚える。 */
export async function rememberLastFolder(api: ExtensionApi, folder: FolderRef): Promise<void> {
  await api.storage.local.set({ [LAST_FOLDER_STORAGE_KEY]: { id: folder.id, name: folder.name } })
}

/** 覚えている前回の追加先を返す。無いか、形が合わなければ undefined。 */
export async function readLastFolder(api: ExtensionApi): Promise<FolderRef | undefined> {
  const stored = await api.storage.local.get(LAST_FOLDER_STORAGE_KEY)
  const value = stored[LAST_FOLDER_STORAGE_KEY]
  return isFolderRef(value) ? { id: value.id, name: value.name } : undefined
}
