package to.sava.cloudmarksmultiplatform.update

import kotlinx.coroutines.flow.StateFlow

/**
 * アプリの更新。確認・ダウンロード・確かめること・インストーラへ渡すことを内に持ち、画面には状態 [state] と操作だけを見せる。
 *
 * 更新を確かめるのは、利用者が操作したときだけ（起動したときには確かめない）。操作の関数はメインのスレッドから呼ぶ。
 * 受け付けない状態で呼ばれた操作は、何もしない。使い終えたら [close] を呼ぶ。
 */
interface AppUpdater : AutoCloseable {
    /** 更新の状態。 */
    val state: StateFlow<UpdateState>

    /** 更新を確認する。受け付けるのは [UpdateState.canCheck] のときだけ。 */
    fun check()

    /**
     * 確認で分かった新しいバージョンをダウンロードし、SHA-256 を確かめる。受け付けるのは [UpdateState.canDownload] のときだけ。
     * 確かめたあとは、インストールしてよいかの返事を待つ（[UpdateInstall.ReadyToApply]）か、そのままインストーラへ渡す
     * （[UpdateInstall.Launching]）。どちらになるかはプラットフォームで違う。
     */
    fun download()

    /**
     * インストールしてよいかの返事を待っている（[UpdateInstall.ReadyToApply]）配布物をインストーラへ渡し、[onReadyToExit] を呼ぶ。
     * 呼ばれた側は、そこでアプリを終える。返事を待つ段階を通らないプラットフォームでは何もしない。
     */
    fun apply(onReadyToExit: () -> Unit)

    /**
     * インストールしてよいかの返事を待つのをやめるか（[UpdateInstall.ReadyToApply]）、止まった理由（[UpdateInstall.Failed]）を閉じて、
     * ダウンロードを始める前に戻す。ダウンロードと確かめている最中は受け付けない。
     */
    fun cancel()

    /** 前の起動で更新を適用できなかった知らせ（[UpdateState.applyFailedVersionName]）を閉じる。 */
    fun dismissApplyFailure()

    /**
     * 画面が操作を受け付けられる状態に戻ったことを知らせる。端末のインストーラの確認から戻ったことを知るのに使う。
     * 確認が出ているあいだ、画面は止まらず操作を受け付けなくなるだけのことがあるため、画面が見えるようになったとき（`onStart`）ではなく、
     * 操作を受け付けられるようになったとき（`onResume`）に呼ぶ。
     */
    fun onResumed()

    /** 確認とダウンロードを取り消し、持っているものを閉じる。 */
    override fun close()
}
