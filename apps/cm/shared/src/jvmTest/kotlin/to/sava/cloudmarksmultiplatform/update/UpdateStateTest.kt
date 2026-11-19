package to.sava.cloudmarksmultiplatform.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateStateTest {
    private val available = UpdateCheck.Available(RELEASED_VERSION_NAME, DESKTOP_SHA256)

    /** ダウンロードからインストーラへ渡すまでの途中の段階。 */
    private val inProgressStages = listOf(
        UpdateInstall.Downloading(receivedBytes = 0, totalBytes = 0),
        UpdateInstall.Verifying,
        UpdateInstall.ReadyToApply,
        UpdateInstall.Launching,
    )

    // 全体の大きさが分からなければ、進み具合は無い
    @Test
    fun fractionIsUnknownWithoutTotal() {
        assertNull(UpdateInstall.Downloading(receivedBytes = 100, totalBytes = 0).fraction)
    }

    // 進み具合は、受け取った量の全体に対する割合
    @Test
    fun fractionIsReceivedOverTotal() {
        assertEquals(0.5f, UpdateInstall.Downloading(receivedBytes = 50, totalBytes = 100).fraction)
    }

    // 受け取った量が全体を超えていても、進み具合は 1 を超えない
    @Test
    fun fractionNeverExceedsWhole() {
        assertEquals(1f, UpdateInstall.Downloading(receivedBytes = 150, totalBytes = 100).fraction)
    }

    // ダウンロードからインストーラへ渡すまでは途中で、止まったものは途中でない
    @Test
    fun stagesUpToHandOverAreInProgress() {
        inProgressStages.forEach { assertTrue(it.inProgress, it.toString()) }
        UpdateInstallFailure.entries.forEach { assertFalse(UpdateInstall.Failed(it).inProgress, it.toString()) }
    }

    // 確認は、確認している最中と、ダウンロードからインストーラへ渡すまでの途中だけ受け付けない。開発版でも、止まったあとでも受け付ける
    @Test
    fun checkIsRefusedOnlyWhileCheckingOrInProgress() {
        assertTrue(UpdateState(DISTRIBUTED_VERSION).canCheck)
        assertTrue(UpdateState(DEV_VERSION).canCheck)
        assertTrue(UpdateState(DISTRIBUTED_VERSION, check = available, install = UpdateInstall.Failed(UpdateInstallFailure.DOWNLOAD_FAILED)).canCheck)
        assertFalse(UpdateState(DISTRIBUTED_VERSION, check = UpdateCheck.Checking).canCheck)
        inProgressStages.forEach { stage ->
            assertFalse(UpdateState(DISTRIBUTED_VERSION, check = available, install = stage).canCheck, stage.toString())
        }
    }

    // ダウンロードは、配布版で新しいバージョンがあると分かっていて、途中でないときだけ受け付ける。止まったあとはやり直せる
    @Test
    fun downloadNeedsDistributedAvailableAndIdle() {
        assertTrue(UpdateState(DISTRIBUTED_VERSION, check = available).canDownload)
        assertTrue(UpdateState(DISTRIBUTED_VERSION, check = available, install = UpdateInstall.Failed(UpdateInstallFailure.VERIFICATION_FAILED)).canDownload)
        assertFalse(UpdateState(DEV_VERSION, check = available).canDownload)
        listOf(UpdateCheck.NotChecked, UpdateCheck.Checking, UpdateCheck.UpToDate, UpdateCheck.Failed(UpdateCheckFailure.UNREACHABLE))
            .forEach { check -> assertFalse(UpdateState(DISTRIBUTED_VERSION, check = check).canDownload, check.toString()) }
        inProgressStages.forEach { stage ->
            assertFalse(UpdateState(DISTRIBUTED_VERSION, check = available, install = stage).canDownload, stage.toString())
        }
    }
}
