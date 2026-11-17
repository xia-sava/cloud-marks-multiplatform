/**
 * コマンドで作ったタブを、先頭から決まった本数ずつ読み込む順番を決める。タブは ID で表す。
 * どのタブを読み込み中にするかを決めるだけで、ブラウザの API には触れない。
 */
export class LoadQueue {
  private readonly waiting: number[] = []
  private readonly loading = new Set<number>()

  constructor(private readonly loadAtOnce: number) {}

  /** 待っているタブの最後に足す。 */
  add(tabId: number): void {
    this.waiting.push(tabId)
  }

  /** 空いている分だけ、待っているタブを先頭から読み込み中にし、その ID を返す。 */
  next(): number[] {
    const started: number[] = []
    while (this.loading.size < this.loadAtOnce && this.waiting.length > 0) {
      const tabId = this.waiting.shift() as number
      this.loading.add(tabId)
      started.push(tabId)
    }
    return started
  }

  /** タブが済んだ（読み込みが終わった、時間がたった、閉じられた）。待っていても読み込み中でも外す。 */
  finish(tabId: number): void {
    this.loading.delete(tabId)
    this.removeWaiting(tabId)
  }

  /** 待っているタブが、読み込ませる前に自分で本来の URL へ移った。読み込み中のタブは外さない。 */
  skip(tabId: number): void {
    this.removeWaiting(tabId)
  }

  has(tabId: number): boolean {
    return this.loading.has(tabId) || this.waiting.includes(tabId)
  }

  get isEmpty(): boolean {
    return this.loading.size === 0 && this.waiting.length === 0
  }

  private removeWaiting(tabId: number): void {
    const index = this.waiting.indexOf(tabId)
    if (index !== -1) this.waiting.splice(index, 1)
  }
}
