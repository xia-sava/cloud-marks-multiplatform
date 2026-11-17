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
import { MESSAGES, tabCountText } from "./messages"
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
const nameInput = element<HTMLInputElement>("name")
const nameLabel = element<HTMLElement>("name-label")
const okButton = element<HTMLButtonElement>("ok")
const cancelButton = element<HTMLButtonElement>("cancel")
const statusText = element<HTMLElement>("status")
const errorText = element<HTMLElement>("error")
const radios = Array.from(form.querySelectorAll<HTMLInputElement>('input[name="action"]'))

element("label-add-folder").textContent = MESSAGES.addFolder
element("label-add-bookmarks").textContent = MESSAGES.addBookmarks
okButton.textContent = MESSAGES.ok
cancelButton.textContent = MESSAGES.cancel

async function showPreviousFailure(): Promise<void> {
  const message = await takeFailure(api)
  if (message === undefined) return
  element("previous-failure-heading").textContent = MESSAGES.previousFailure
  element("previous-failure-message").textContent = message
  element("previous-failure").hidden = false
}

async function main(): Promise<void> {
  const [tabs] = await Promise.all([selectedTabs(), showPreviousFailure()])
  const tabCount = tabs.length

  let action: AddAction = defaultAction(tabCount)
  let values = initialNameValues(new Date(), tabs)
  let sending = false

  element("tab-count").textContent = tabCount === 0 ? MESSAGES.noTabs : tabCountText(tabCount)

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

  function setSending(value: boolean): void {
    sending = value
    controls.disabled = value
    statusText.textContent = value ? MESSAGES.sending : ""
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

  function submit(): void {
    if (sending || !canSubmit(action, nameInput.value, tabCount)) return
    remember()
    element("previous-failure").hidden = true
    errorText.textContent = ""
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
    })
    port.onDisconnect.addListener(() => {
      if (settled) return
      settled = true
      fail(MESSAGES.communicationFailed)
    })

    const name = nameInput.disabled ? "" : nameInput.value
    const message: SubmitMessage = { type: "submit", request: buildRequest(action, name, tabs) }
    port.postMessage(message)
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
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && !event.isComposing) window.close()
  })

  render()
  focusEntry()
}

await main()
