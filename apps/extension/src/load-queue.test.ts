import { describe, expect, it } from "vitest"
import { LoadQueue } from "./load-queue"

function queueOf(loadAtOnce: number, tabIds: number[]): LoadQueue {
  const queue = new LoadQueue(loadAtOnce)
  for (const tabId of tabIds) queue.add(tabId)
  return queue
}

describe("LoadQueue", () => {
  it("先頭から loadAtOnce 本ずつ始め、枠が空かなければ何も始めない", () => {
    const queue = queueOf(2, [1, 2, 3, 4, 5])
    expect(queue.next()).toEqual([1, 2])
    expect(queue.next()).toEqual([])
  })

  it("1 本済むごとに、次の 1 本を順に始める", () => {
    const queue = queueOf(2, [1, 2, 3, 4, 5])
    queue.next()
    queue.finish(2)
    expect(queue.next()).toEqual([3])
    queue.finish(1)
    queue.finish(3)
    expect(queue.next()).toEqual([4, 5])
    expect(queue.isEmpty).toBe(false)
    queue.finish(4)
    queue.finish(5)
    expect(queue.next()).toEqual([])
    expect(queue.isEmpty).toBe(true)
  })

  it("タブが loadAtOnce 本より少なければ、全部をいちどに始める", () => {
    expect(queueOf(3, [1, 2]).next()).toEqual([1, 2])
  })

  it("待っているタブが済んだ（閉じられた）ら、順番から外す", () => {
    const queue = queueOf(1, [1, 2, 3])
    queue.next()
    queue.finish(2)
    queue.finish(1)
    expect(queue.next()).toEqual([3])
  })

  it("待っているタブが自分で移ったら飛ばし、枠は空けない", () => {
    const queue = queueOf(1, [1, 2, 3])
    queue.skip(1)
    expect(queue.next()).toEqual([2])
    queue.skip(3)
    expect(queue.next()).toEqual([])
    queue.finish(2)
    expect(queue.isEmpty).toBe(true)
  })

  it("読み込み中のタブは、自分で移っても外さない", () => {
    const queue = queueOf(1, [1, 2])
    queue.next()
    queue.skip(1)
    expect(queue.has(1)).toBe(true)
    expect(queue.next()).toEqual([])
  })

  it("知らないタブや済んだタブを済ませても、何も変わらない", () => {
    const queue = queueOf(1, [1, 2])
    queue.next()
    queue.finish(9)
    queue.skip(9)
    expect(queue.next()).toEqual([])
    queue.finish(1)
    queue.finish(1)
    expect(queue.next()).toEqual([2])
  })

  it("has は待っているタブと読み込み中のタブで true", () => {
    const queue = queueOf(1, [1, 2])
    queue.next()
    expect(queue.has(1)).toBe(true)
    expect(queue.has(2)).toBe(true)
    expect(queue.has(3)).toBe(false)
    queue.finish(1)
    expect(queue.has(1)).toBe(false)
  })

  it("タブが無ければ空", () => {
    const queue = new LoadQueue(3)
    expect(queue.isEmpty).toBe(true)
    expect(queue.next()).toEqual([])
  })
})
