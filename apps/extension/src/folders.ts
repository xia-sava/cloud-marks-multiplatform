import type { ExtensionApi } from "./api"
import type { FolderRef } from "./last-folder"
import { MESSAGES } from "./messages"
import { interpretFoldersResponse, type FolderEntry, type FoldersOutcome, type ListFoldersMessage } from "./protocol"
import { sendRequest } from "./send"

/** 階層を表す字下げ。`<select>` の中は CSS で字下げできないため、全角スペースを名前の前に置く。 */
const INDENT = "　"

/**
 * 追加先の欄の選択肢。`id` が undefined のものは、フォルダを指定しない（ルートフォルダになる）。
 * `name` はフォルダの名前で、ルートフォルダは空。
 */
export interface FolderOption {
  id: string | undefined
  name: string
  label: string
}

/** 追加先の欄の選択肢と、選んでいるものの位置。 */
export interface FolderChoices {
  options: FolderOption[]
  selectedIndex: number
}

export function listFoldersMessage(): ListFoldersMessage {
  return { protocol: 1, type: "listFolders" }
}

/** フォルダの一覧を、ポップアップから直接ホストへ送って受け取る。 */
export async function listFolders(api: ExtensionApi): Promise<FoldersOutcome> {
  return sendRequest(api, listFoldersMessage(), interpretFoldersResponse)
}

function labelOf(name: string, depth: number): string {
  if (depth === 0) return MESSAGES.rootFolder
  return INDENT.repeat(depth) + name
}

/**
 * フォルダの一覧を、`folders` の順のまま選択肢にする。
 * ルートフォルダは `トップ`、ほかは名前の前に、ルートフォルダからの深さ（ルートフォルダの子が 1）と同じ数の全角スペースを置く。
 */
export function folderOptions(folders: readonly FolderEntry[]): FolderOption[] {
  const depths = new Map<string, number>()
  return folders.map((folder) => {
    const depth = folder.parent === null ? 0 : (depths.get(folder.parent) ?? 0) + 1
    depths.set(folder.id, depth)
    return { id: folder.id, name: folder.name, label: labelOf(folder.name, depth) }
  })
}

/**
 * 一覧が届いたあとの選択肢。選んでいるフォルダ（`selectedId`）が一覧にあればそれを、
 * 無ければ前回の追加先を、それも無ければルートフォルダを選ぶ。
 * `selectedId` は、選んでいるフォルダが無いとき（初回の読み込み）は undefined。
 */
export function choicesFromFolders(
  folders: readonly FolderEntry[],
  selectedId: string | undefined,
  last: FolderRef | undefined,
): FolderChoices {
  const options = folderOptions(folders)
  for (const id of [selectedId, last?.id]) {
    const index = id === undefined ? -1 : options.findIndex((option) => option.id === id)
    if (index >= 0) return { options, selectedIndex: index }
  }
  const rootIndex = folders.findIndex((folder) => folder.parent === null)
  return { options, selectedIndex: Math.max(rootIndex, 0) }
}

/**
 * 一覧が届くまでの選択肢。前回の追加先だけを出す。覚えたものが無ければ、フォルダを指定しない `トップ` だけを出す。
 */
export function initialChoices(last: FolderRef | undefined): FolderChoices {
  if (last === undefined) return { options: [{ id: undefined, name: "", label: MESSAGES.rootFolder }], selectedIndex: 0 }
  const label = last.name === "" ? MESSAGES.rootFolder : last.name
  return { options: [{ id: last.id, name: last.name, label }], selectedIndex: 0 }
}
