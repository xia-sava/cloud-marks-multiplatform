package to.sava.cloudmarksmultiplatform.ui

import to.sava.cloudmarksmultiplatform.app.SettingsStatus
import to.sava.cloudmarksmultiplatform.app.WriteOutcome
import to.sava.cloudmarksmultiplatform.operations.NotAppliedReason
import to.sava.cloudmarksmultiplatform.operations.OperationResult
import to.sava.cloudmarksmultiplatform.operations.OperationStatus
import to.sava.cloudmarksmultiplatform.operations.RenameFolder
import to.sava.cloudmarksmultiplatform.settings.DisplayScale
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

    // 読むだけにしている失敗の文は、読むだけにしていることを伝える文と同じ
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

    // 設定を読めなかった理由は、詳細を本文から分ける。設定がまだ無いときと読めたときは詳細が無い。
    // 認証情報を復号できなかったときは、暗号化の仕組みが返した文を詳細に出さない
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
        assertNull(describeSettingsReadProblem(SettingsReadResult.Undecryptable("復号に失敗"))?.detail)
        assertNull(describeSettingsReadProblem(SettingsReadResult.NotFound)?.detail)
        assertEquals(
            "データを保存する S3 のバケットと、アクセスキーを入力してください。",
            describeSettingsReadProblem(SettingsReadResult.NotFound)?.text,
        )
    }

    // 保存されているアクセスキーを復号できなかったときのデスクトップの文は、別のユーザーや別の PC で保存した設定は使えないことを添える。
    // 詳細は無い
    @Test
    fun undecryptableTextOnDesktop() {
        assertEquals(
            UiMessage(
                "保存されているアクセスキーを読み込めませんでした。" +
                    "別のユーザーや別の PC で保存した設定は使えないため、もう一度入力して保存してください。",
            ),
            describeSettingsReadProblem(SettingsReadResult.Undecryptable("復号に失敗")),
        )
    }

    // 入力しているときは文が無い
    @Test
    fun editingHasNoMessage() {
        assertNull(describeSettingsStatus(SettingsStatus.Editing))
    }

    // 設定のファイルを書けなかったときは、詳細を本文から分ける
    @Test
    fun writeFailureSeparatesDetail() {
        assertEquals(
            UiMessage("設定を保存できませんでした。", "ディスクが満杯"),
            describeSettingsStatus(SettingsStatus.WriteFailed("ディスクが満杯")),
        )
    }

    // 暗号化の仕組みが使えなかったときは、保存しなかったことだけを伝え、詳細は無い
    @Test
    fun protectionUnavailableHasTextOnly() {
        assertEquals(
            UiMessage("この環境ではアクセスキーを暗号化できないため、保存しませんでした。"),
            describeSettingsStatus(SettingsStatus.ProtectionUnavailable),
        )
    }

    // 詳細の無い状態は、詳細が null
    @Test
    fun statusesWithoutDetailHaveNullDetail() {
        val statuses = listOf(
            SettingsStatus.Incomplete(setOf(SettingsField.REGION)),
            SettingsStatus.Saving,
            SettingsStatus.ProtectionUnavailable,
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

    // 書けて伝えることが無ければ、文が無い
    @Test
    fun writtenHasNoMessage() {
        assertNull(describeWriteOutcome(WriteOutcome.Written))
    }

    // 書けなかったときは、保存できなかったことの次の行に S3 の失敗の文を続け、詳細は失敗のものを使う
    @Test
    fun failedWriteContinuesWithFailureText() {
        val message = describeWriteOutcome(WriteOutcome.Failed(SyncFailure.Unreachable("SocketTimeoutException")))

        assertEquals(
            "変更を保存できませんでした。\nS3 に接続できませんでした。ネットワークの状態を確認してください。",
            message?.text,
        )
        assertEquals("SocketTimeoutException", message?.detail)
    }

    // 適用しなかった操作があるときと、書けたか確かめられなかったときは、それぞれの文だけで詳細が無い
    @Test
    fun notAppliedAndUnconfirmedHaveTextOnly() {
        val notApplied = WriteOutcome.NotApplied(
            listOf(OperationResult(RenameFolder("folder", "名前"), OperationStatus.NotApplied(NotAppliedReason.TargetNotFound))),
        )

        assertEquals(UiMessage("対象が見つからなかったため、一部の変更を反映できませんでした。"), describeWriteOutcome(notApplied))
        assertEquals(
            UiMessage("変更を保存できたかどうかを確認できませんでした。再読み込みして確認してください。"),
            describeWriteOutcome(WriteOutcome.Unconfirmed(SyncFailure.Unreachable("SocketTimeoutException"))),
        )
    }

    // 項目を消したあとのお知らせは、消した数を入れる
    @Test
    fun deletedItemsNoticeHasCount() {
        assertEquals("3 件の項目を削除しました。", deletedItemsNotice(3))
    }

    // 2 つ以上の項目をドラッグしているあいだの札は、項目の数を入れる
    @Test
    fun draggedItemsLabelHasCount() {
        assertEquals("3 件の項目", draggedItemsLabel(3))
    }

    // 表示の大きさの段は、百分率の数字に % を付けて出す
    @Test
    fun displayScaleLabelIsPercent() {
        assertEquals(listOf("80%", "90%", "100%", "110%", "125%", "150%", "175%", "200%"), DisplayScale.STEPS.map(::displayScaleLabel))
        assertEquals("表示の大きさ", DISPLAY_SCALE_LABEL)
    }

    // フォルダの削除の確認は、フォルダの名前をかぎ括弧で囲む
    @Test
    fun deleteFolderConfirmationQuotesName() {
        assertEquals(
            "「フォルダ A」と、その中のフォルダと項目をすべて削除します。この操作は元に戻せません。",
            deleteFolderConfirmation("フォルダ A"),
        )
    }
}
