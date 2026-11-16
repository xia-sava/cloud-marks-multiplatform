import { selectedTabs } from "./selected-tabs"

chrome.action.onClicked.addListener(async () => {
  const tabs = await selectedTabs()
  // TODO: Native Messaging のメッセージ形式（contracts/native-messaging/）が確定したら、
  // tabs をその形にしてホストへ送る処理をここに追加する。
  void tabs
})
