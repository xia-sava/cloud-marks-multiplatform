/** 画面とツールバーのボタンに出す文言。 */
export const MESSAGES = {
  addFolder: "フォルダを追加",
  addBookmarks: "ブックマークを追加",
  folderNameLabel: "名前",
  bookmarkTitleLabel: "タイトル",
  eachTabTitle: "それぞれのタブのタイトルを使います。",
  ok: "OK",
  cancel: "キャンセル",
  sending: "追加しています…",
  noTabs: "追加できるタブがありません。",
  hostNotFound: "Cloud Marks のアプリが見つかりません。アプリをインストールしてから、もう一度お試しください。",
  communicationFailed: "Cloud Marks のアプリとやり取りできませんでした。",
  previousFailure: "前回は追加できませんでした。",
  failureActionTitle: "追加できませんでした。クリックすると理由を表示します。",
  defaultActionTitle: "Cloud Marks",
} as const

/** 失敗したままポップアップが閉じられたときにツールバーのボタンへ付ける文字。 */
export const FAILURE_BADGE_TEXT = "!"

/** 対象のタブの数を知らせる文。 */
export function tabCountText(count: number): string {
  return `${count} 個のタブを追加します。`
}
