package to.sava.cloudmarksmultiplatform.ui

import to.sava.cloudmarksmultiplatform.app.SettingsStatus
import to.sava.cloudmarksmultiplatform.settings.SettingsField
import to.sava.cloudmarksmultiplatform.settings.SettingsReadResult
import to.sava.cloudmarksmultiplatform.storage.StorageProblem
import to.sava.cloudmarksmultiplatform.sync.SyncFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class MessagesTest {
    private val problem = StorageProblem(3, "version が整数でない")

    // Unreachable の詳細は、本文に混ぜず、詳細に入れる
    @Test
    fun unreachableKeepsDetailOutOfText() {
        val message = describeSyncFailure(SyncFailure.Unreachable("SocketTimeoutException"))

        assertEquals("S3 に接続できませんでした。ネットワークの状態を確認してください。", message.text)
        assertEquals("SocketTimeoutException", message.detail)
        assertFalse(message.text.contains("SocketTimeoutException"))
    }

    // マニフェストが壊れている失敗の詳細は、壊れている箇所
    @Test
    fun manifestCorruptDetailIsProblem() {
        val message = describeSyncFailure(SyncFailure.ManifestCorrupt(problem))

        assertEquals("保存先のデータが壊れているため、読み込めません。", message.text)
        assertEquals("3 行目: version が整数でない", message.detail)
    }

    // そのほかの失敗の詳細は、失敗が持つ detail
    @Test
    fun otherDetailIsDetail() {
        val message = describeSyncFailure(SyncFailure.Other("IllegalStateException"))

        assertEquals("S3 とのやり取りでエラーが起きました。", message.text)
        assertEquals("IllegalStateException", message.detail)
    }

    // 詳細を持たない失敗は、詳細が null
    @Test
    fun failuresWithoutDetailHaveNullDetail() {
        val failures = listOf(
            SyncFailure.Forbidden,
            SyncFailure.ReadOnly(version = 2, readableBy = 1),
            SyncFailure.UpdateRequired(version = 2, readableBy = 2),
            SyncFailure.ManifestNotFound,
            SyncFailure.ManifestDeleted,
            SyncFailure.FolderCorrupt(mapOf("folder" to problem)),
            SyncFailure.Busy,
        )

        failures.forEach { failure -> assertNull(describeSyncFailure(failure).detail, failure.toString()) }
    }

    // 読むだけにしている失敗の文は、読むだけにしている知らせと同じ
    @Test
    fun readOnlyTextIsReadOnlyNotice() {
        assertEquals(READ_ONLY_NOTICE, describeSyncFailure(SyncFailure.ReadOnly(version = 2, readableBy = 1)).text)
    }

    // 壊れたフォルダの詳細は、壊れている箇所
    @Test
    fun brokenFolderDetailIsProblem() {
        val message = describeBrokenFolder(problem)

        assertEquals("このフォルダのデータが壊れているため、表示できません。", message.text)
        assertEquals("3 行目: version が整数でない", message.detail)
    }

    // 設定を読めなかった理由は、詳細を本文から分ける。設定がまだ無いときと読めたときは詳細が無い
    @Test
    fun settingsReadProblemSeparatesDetail() {
        assertEquals(
            UiMessage("設定ファイルを読み込めませんでした。もう一度入力して保存してください。", "不正な JSON"),
            describeSettingsReadProblem(SettingsReadResult.Corrupt("不正な JSON")),
        )
        assertEquals(
            UiMessage("設定ファイルを読み込めませんでした。", "アクセスが拒否された"),
            describeSettingsReadProblem(SettingsReadResult.Failed("アクセスが拒否された")),
        )
        assertEquals("復号に失敗", describeSettingsReadProblem(SettingsReadResult.Undecryptable("復号に失敗"))?.detail)
        assertNull(describeSettingsReadProblem(SettingsReadResult.NotFound)?.detail)
        assertEquals(
            "データを保存する S3 のバケットと、アクセスキーを入力してください。",
            describeSettingsReadProblem(SettingsReadResult.NotFound)?.text,
        )
    }

    // 入力しているときは文が無い
    @Test
    fun editingHasNoMessage() {
        assertNull(describeSettingsStatus(SettingsStatus.Editing))
    }

    // 保存の失敗は、詳細を本文から分ける
    @Test
    fun saveFailuresSeparateDetail() {
        assertEquals(
            UiMessage("この環境ではアクセスキーを暗号化できないため、保存しませんでした。", "DPAPI が使えない"),
            describeSettingsStatus(SettingsStatus.ProtectionUnavailable("DPAPI が使えない")),
        )
        assertEquals(
            UiMessage("設定を保存できませんでした。", "ディスクが満杯"),
            describeSettingsStatus(SettingsStatus.WriteFailed("ディスクが満杯")),
        )
    }

    // 詳細の無い状態は、詳細が null
    @Test
    fun statusesWithoutDetailHaveNullDetail() {
        val statuses = listOf(
            SettingsStatus.Incomplete(setOf(SettingsField.REGION)),
            SettingsStatus.Saving,
            SettingsStatus.ManifestNotFound,
            SettingsStatus.CreatingManifest,
        )

        statuses.forEach { status -> assertNull(describeSettingsStatus(status)?.detail, status.toString()) }
    }

    // 保存はできたが読めない状態は、保存したことの次の行に S3 の失敗の文を続け、詳細は失敗のものを使う
    @Test
    fun connectionFailedContinuesWithFailureText() {
        val message = describeSettingsStatus(SettingsStatus.ConnectionFailed(SyncFailure.Unreachable("UnknownHostException")))

        assertEquals(
            "設定は保存しましたが、S3 からデータを読み込めませんでした。\nS3 に接続できませんでした。ネットワークの状態を確認してください。",
            message?.text,
        )
        assertEquals("UnknownHostException", message?.detail)
    }
}
