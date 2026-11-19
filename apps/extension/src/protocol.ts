import { MESSAGES } from "./messages"

// ビルドが埋め込むホストの名前（`variantDefines`）。
declare const CMM_HOST_NAME: string

/** Native Messaging のホストの名前。配布版の拡張は配布版の、開発版の拡張は開発版のホストとやり取りする。 */
export const HOST_NAME: string = CMM_HOST_NAME

export interface RequestTab {
  url: string
  title: string
}

export interface AddFolderRequest {
  protocol: 1
  type: "addFolder"
  name: string
  tabs: RequestTab[]
}

export interface AddBookmarksRequest {
  protocol: 1
  type: "addBookmarks"
  tabs: RequestTab[]
}

export type AddRequest = AddFolderRequest | AddBookmarksRequest

/** このブラウザに拡張が入っていることを知らせる。 */
export interface ExtensionPresentMessage {
  protocol: 1
  type: "extensionPresent"
}

/** アプリからのコマンドを受け取る。 */
export interface TakeCommandMessage {
  protocol: 1
  type: "takeCommand"
  token: string
}

/** 追加の結果。失敗のときは画面にそのまま出す文を持つ。 */
export type Outcome = { ok: true } | { ok: false; message: string }

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null
}

/**
 * ホストの応答を結果にする。
 * 失敗は、空でない `error.message` をそのまま使う。`error.code` の有無や値は問わない。
 * 形が合わない応答は、やり取りできなかった失敗にする。
 */
export function interpretResponse(response: unknown): Outcome {
  if (isRecord(response)) {
    if (response.ok === true) return { ok: true }
    if (response.ok === false && isRecord(response.error)) {
      const { message } = response.error
      if (typeof message === "string" && message !== "") return { ok: false, message }
    }
  }
  return { ok: false, message: MESSAGES.communicationFailed }
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
