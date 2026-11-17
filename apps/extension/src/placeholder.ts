import { moveWhenVisible, placeholderTitle, readPlaceholderTab } from "./placeholder-page"

function element<T extends HTMLElement>(id: string): T {
  const found = document.getElementById(id)
  if (!found) throw new Error(`#${id} が placeholder.html に無い`)
  return found as T
}

const tab = readPlaceholderTab(location.search)
if (tab !== undefined) {
  document.title = placeholderTitle(tab)
  element("title").textContent = tab.title
  const link = element<HTMLAnchorElement>("url")
  link.href = tab.url
  link.textContent = tab.url
  moveWhenVisible(document, () => location.replace(tab.url))
}
