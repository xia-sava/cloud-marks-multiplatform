import { getApi } from "./api"
import { takeFailure } from "./failure-state"
import {
  buildRequest,
  canSubmit,
  defaultAction,
  initialNameValues,
  nameField,
  nameFieldText,
  rememberName,
  type AddAction,
} from "./form"
import { choicesFromFolders, initialChoices, listFolders, type FolderChoices } from "./folders"
import { readLastFolder } from "./last-folder"
import { MESSAGES, tabCountText } from "./messages"
import { openApp } from "./open-app"
import { isResultMessage, POPUP_PORT_NAME, type SubmitMessage } from "./port-messages"
import { selectedTabs } from "./selected-tabs"

function element<T extends HTMLElement>(id: string): T {
  const found = document.getElementById(id)
  if (!found) throw new Error(`#${id} が popup.html に無い`)
  return found as T
}

const api = getApi()

const form = element<HTMLFormElement>("form")
const controls = element<HTMLFieldSetElement>("controls")
const folderSelect = element<HTMLSelectElement>("folder")
const folderStatus = element<HTMLElement>("folder-status")
const nameInput = element<HTMLInputElement>("name")
const nameLabel = element<HTMLElement>("name-label")
const okButton = element<HTMLButtonElement>("ok")
const cancelButton = element<HTMLButtonElement>("cancel")
const statusText = element<HTMLElement>("status")
const errorText = element<HTMLElement>("error")
const openAppButton = element<HTMLButtonElement>("open-app")
const radios = Array.from(form.querySelectorAll<HTMLInputElement>('input[name="action"]'))

element("label-add-folder").textContent = MESSAGES.addFolder
element("label-add-bookmarks").textContent = MESSAGES.addBookmarks
element("folder-label").textContent = MESSAGES.folderLabel
okButton.textContent = MESSAGES.ok
cancelButton.textContent = MESSAGES.cancel
openAppButton.textContent = MESSAGES.openApp

async function showPreviousFailure(): Promise<void> {
  const message = await takeFailure(api)
  if (message === undefined) return
  element("previous-failure-heading").textContent = MESSAGES.previousFailure
  element("previous-failure-message").textContent = message
  element("previous-failure").hidden = false
}

async function main(): Promise<void> {
  const [tabs, lastFolder] = await Promise.all([selectedTabs(), readLastFolder(api), showPreviousFailure()])
  const tabCount = tabs.length

  let action: AddAction = defaultAction(tabCount)
  let values = initialNameValues(new Date(), tabs)
  let sending = false
  let folderChoices: FolderChoices = initialChoices(lastFolder)
  let folderLoadCount = 0

  element("tab-count").textContent = tabCount === 0 ? MESSAGES.noTabs : tabCountText(tabCount)

  function renderFolders(): void {
    folderSelect.replaceChildren(
      ...folderChoices.options.map((option) => {
        const optionElement = document.createElement("option")
        optionElement.textContent = option.label
        return optionElement
      }),
    )
    folderSelect.selectedIndex = folderChoices.selectedIndex
    folderSelect.disabled = tabCount === 0
  }

  // フォルダの一覧を受け取り、追加先の欄を並べ直す。受け取れなかったら、欄をそのままにして文を出す。
  // 続けて呼ばれたときは、最後の呼び出しの結果だけを使う。
  async function loadFolders(): Promise<void> {
    const loadNumber = ++folderLoadCount
    folderStatus.textContent = MESSAGES.loadingFolders
    const outcome = await listFolders(api)
    if (loadNumber !== folderLoadCount) return
    if (!outcome.ok) {
      folderStatus.textContent = MESSAGES.foldersLoadFailed
      return
    }
    const selectedId = folderChoices.options[folderSelect.selectedIndex]?.id
    folderChoices = choicesFromFolders(outcome.folders, selectedId, lastFolder)
    renderFolders()
    folderStatus.textContent = ""
  }

  function render(): void {
    const field = nameField(action, tabCount)
    for (const radio of radios) radio.checked = radio.value === action
    nameLabel.textContent = field.label
    for (const radio of radios) radio.disabled = tabCount === 0
    nameInput.disabled = !field.editable || tabCount === 0
    nameInput.value = nameFieldText(values, action, tabCount)
    updateOk()
  }

  function updateOk(): void {
    okButton.disabled = !canSubmit(action, nameInput.value, tabCount)
  }

  function remember(): void {
    values = rememberName(values, action, tabCount, nameInput.value)
  }

  // 送っているあいだは、追加の操作もアプリを開くボタンも無効にし、`status` を状態の文にする。
  function setSending(value: boolean, status: string = MESSAGES.sending): void {
    sending = value
    controls.disabled = value
    openAppButton.disabled = value
    statusText.textContent = value ? status : ""
    if (!value) focusEntry()
  }

  // 名前の欄に入力できないときは、Enter で OK を押せるよう OK にフォーカスを置く。
  function focusEntry(): void {
    const target = nameInput.disabled ? okButton : nameInput
    target.focus()
  }

  function fail(message: string): void {
    setSending(false)
    errorText.textContent = message
  }

  function clearFailures(): void {
    element("previous-failure").hidden = true
    errorText.textContent = ""
  }

  function submit(): void {
    if (sending || !canSubmit(action, nameInput.value, tabCount)) return
    remember()
    clearFailures()
    setSending(true)

    const port = api.runtime.connect({ name: POPUP_PORT_NAME })
    let settled = false
    port.onMessage.addListener((message) => {
      if (!isResultMessage(message)) return
      settled = true
      port.disconnect()
      if (message.outcome.ok) {
        window.close()
        return
      }
      fail(message.outcome.message)
      if (message.outcome.code === "folderNotFound") void loadFolders()
    })
    port.onDisconnect.addListener(() => {
      if (settled) return
      settled = true
      fail(MESSAGES.communicationFailed)
    })

    const name = nameInput.disabled ? "" : nameInput.value
    const folder = folderChoices.options[folderSelect.selectedIndex]
    const message: SubmitMessage = {
      type: "submit",
      request: buildRequest(action, name, tabs, folder.id),
      ...(folder.id === undefined ? {} : { destination: { id: folder.id, name: folder.name } }),
    }
    port.postMessage(message)
  }

  // ポップアップから直接ホストへ送る。結果の前にポップアップが閉じたら、何も残さない。
  async function requestOpenApp(): Promise<void> {
    if (sending) return
    clearFailures()
    setSending(true, MESSAGES.openingApp)
    const outcome = await openApp(api)
    if (outcome.ok) {
      window.close()
      return
    }
    fail(outcome.message)
    openAppButton.focus()
  }

  for (const radio of radios) {
    radio.addEventListener("change", () => {
      if (!radio.checked) return
      remember()
      action = radio.value as AddAction
      render()
    })
  }
  nameInput.addEventListener("input", updateOk)
  form.addEventListener("submit", (event) => {
    event.preventDefault()
    submit()
  })
  cancelButton.addEventListener("click", () => window.close())
  openAppButton.addEventListener("click", () => void requestOpenApp())
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && !event.isComposing) window.close()
  })

  renderFolders()
  render()
  focusEntry()
  void loadFolders()
}

await main()
