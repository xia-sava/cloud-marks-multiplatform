import { MESSAGES } from "./messages"

// ビルドが埋め込むホストの名前（`variantDefines`）。
declare const CMM_HOST_NAME: string

/** Native Messaging のホストの名前。配布版の拡張は配布版の、開発版の拡張は開発版のホストとやり取りする。 */
export const HOST_NAME: string = CMM_HOST_NAME

export interface RequestTab {
  url: string
  title: string
}

/** `folder` は追加先のフォルダの ID。無ければホストがルートフォルダにする。 */
export interface AddFolderRequest {
  protocol: 1
  type: "addFolder"
  folder?: string
  name: string
  tabs: RequestTab[]
}

export interface AddBookmarksRequest {
  protocol: 1
  type: "addBookmarks"
  folder?: string
  tabs: RequestTab[]
}

export type AddRequest = AddFolderRequest | AddBookmarksRequest

/** このブラウザに拡張が入っていることを知らせる。 */
export interface ExtensionPresentMessage {
  protocol: 1
  type: "extensionPresent"
}

/** フォルダの一覧を受け取る。 */
export interface ListFoldersMessage {
  protocol: 1
  type: "listFolders"
}

/** アプリからのコマンドを受け取る。 */
export interface TakeCommandMessage {
  protocol: 1
  type: "takeCommand"
  token: string
}

/** デスクトップのアプリを開く。起動していなければ起動し、起動していればそのウィンドウを前に出す。 */
export interface OpenAppMessage {
  protocol: 1
  type: "openApp"
}

/**
 * 失敗の応答の `error.code`（`docs/native-messaging.md` の「ホストが返すもの」）。
 * ホストはここに無い値を返すこともある。
 */
export type HostErrorCode =
  | "notConfigured"
  | "storageUnavailable"
  | "updateRequired"
  | "dataBroken"
  | "folderNotFound"
  | "busy"
  | "unconfirmed"
  | "invalidRequest"
  | "launchFailed"
  | "internal"

/**
 * 失敗の結果。画面にそのまま出す文を持つ。
 * `code` は、画面が文を出すほかに振る舞いを変える失敗（`folderNotFound`）のときだけ持つ。
 */
export interface Failure {
  ok: false
  message: string
  code?: HostErrorCode
}

/** 追加などの結果。 */
export type Outcome = { ok: true } | Failure

/** `listFolders` の応答のフォルダ。`parent` はルートフォルダだけが `null`。 */
export interface FolderEntry {
  id: string
  parent: string | null
  name: string
}

/** `listFolders` の結果。 */
export type FoldersOutcome = { ok: true; folders: FolderEntry[] } | Failure

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null
}

/**
 * ホストの応答を結果にする。
 * 失敗は、空でない `error.message` をそのまま使う。`error.code` の有無や値は問わない。
 * `error.code` が `folderNotFound` のときだけ、結果にも `code` を持たせる。
 * 形が合わない応答は、やり取りできなかった失敗にする。
 */
export function interpretResponse(response: unknown): Outcome {
  if (isRecord(response)) {
    if (response.ok === true) return { ok: true }
    if (response.ok === false && isRecord(response.error)) {
      const { code, message } = response.error
      if (typeof message === "string" && message !== "") {
        const folderNotFound: HostErrorCode = "folderNotFound"
        return code === folderNotFound ? { ok: false, message, code: folderNotFound } : { ok: false, message }
      }
    }
  }
  return { ok: false, message: MESSAGES.communicationFailed }
}

function readFolderEntry(value: unknown): FolderEntry | undefined {
  if (!isRecord(value)) return undefined
  const { id, parent, name } = value
  if (typeof id !== "string" || id === "" || typeof name !== "string") return undefined
  if (parent !== null && typeof parent !== "string") return undefined
  return { id, parent, name }
}

/**
 * `folders` が取り決めの形なら、知っている欄だけを写した並びを返す。合わなければ undefined。
 * 先頭だけがルートフォルダ（`parent` が `null`）で、ほかのフォルダの親は、それより前に現れる。
 */
function readFolders(value: unknown): FolderEntry[] | undefined {
  if (!Array.isArray(value) || value.length === 0) return undefined
  const folders: FolderEntry[] = []
  const seen = new Set<string>()
  for (const item of value) {
    const folder = readFolderEntry(item)
    if (folder === undefined) return undefined
    if ((folder.parent === null) !== (folders.length === 0)) return undefined
    if (folder.parent !== null && !seen.has(folder.parent)) return undefined
    seen.add(folder.id)
    folders.push(folder)
  }
  return folders
}

/**
 * `listFolders` の応答を結果にする。
 * 失敗は `interpretResponse` と同じ。成功の応答でも、`folders` が取り決めの形でなければ、
 * やり取りできなかった失敗にする。
 */
export function interpretFoldersResponse(response: unknown): FoldersOutcome {
  const outcome = interpretResponse(response)
  if (!outcome.ok) return outcome
  const folders = readFolders((response as Record<string, unknown>).folders)
  if (folders === undefined) return { ok: false, message: MESSAGES.communicationFailed }
  return { ok: true, folders }
}

// ホストが登録されていないときに sendNativeMessage が返す誤りの書き出し。
const HOST_NOT_FOUND_PREFIXES = ["specified native messaging host not found", "no such native application"]

function errorText(error: unknown): string {
  if (typeof error === "string") return error
  if (isRecord(error) && typeof error.message === "string") return error.message
  return ""
}

/**
 * sendNativeMessage の失敗を、画面に出す文にする。
 * ホストが登録されていないときだけ見つからない旨にし、ほかは一括してやり取りできない旨にする。
 */
export function classifySendFailure(error: unknown): string {
  const text = errorText(error).trim().toLowerCase()
  if (HOST_NOT_FOUND_PREFIXES.some((prefix) => text.startsWith(prefix))) {
    return MESSAGES.hostNotFound
  }
  return MESSAGES.communicationFailed
}
