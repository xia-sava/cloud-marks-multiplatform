package to.sava.cloudmarksmultiplatform.ui

import kotlinx.datetime.TimeZone
import to.sava.cloudmarksmultiplatform.app.BrowserNotice
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
import to.sava.cloudmarksmultiplatform.sync.ManifestCounts
import to.sava.cloudmarksmultiplatform.sync.RevisionFailure
import to.sava.cloudmarksmultiplatform.sync.SyncFailure
import to.sava.cloudmarksmultiplatform.update.UpdateCheckFailure
import to.sava.cloudmarksmultiplatform.update.UpdateInstallFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Instant

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

    // 以前の状態に戻せたときも、結果の帯の文は無い（お知らせで伝える）
    @Test
    fun restoredHasNoMessage() {
        assertNull(describeWriteOutcome(WriteOutcome.Restored(Instant.parse("2026-11-17T05:08:00Z"))))
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

    // 消した項目を溜めたときのお知らせと、溜めた削除があるあいだの帯は、項目の数を入れる
    @Test
    fun pendingDeleteMessagesHaveCount() {
        assertEquals("3 件の項目を削除しました。ネットワークにつながったら保存先に反映します。", pendingDeletedItemsNotice(3))
        assertEquals("削除した 3 件の項目は、まだ保存先に反映していません。ネットワークにつながったら反映します。", pendingDeletesNotice(3))
    }

    // ほかのアプリで開くか共有しようとして、受け取れるアプリが無ければそのことを、ほかの理由で出せなければ既定のブラウザを起動できなかった
    // ときと同じ文を出す。どちらも詳細は無い
    @Test
    fun shareNoticesHaveTextOnly() {
        assertEquals(UiMessage("この URL を開けるアプリがありません。"), describeBrowserNotice(BrowserNotice.NoAppForItem))
        assertEquals(UiMessage("ブラウザを起動できませんでした。"), describeBrowserNotice(BrowserNotice.ShareFailed))
    }

    // タッチの画面の、項目を選んでいるあいだの AppBar の題は、選んでいる数を入れる
    @Test
    fun selectionTitleHasCount() {
        assertEquals("3 件を選択中", selectionTitle(3))
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

    // 戻せる時点の日時は、月・日・時・分を 0 埋めの 2 桁にする
    @Test
    fun revisionTimeIsZeroPadded() {
        assertEquals("2026/03/04 05:06", formatRevisionTime(Instant.parse("2026-03-04T05:06:07Z"), TimeZone.UTC))
        assertEquals("2026/11/17 14:08", formatRevisionTime(Instant.parse("2026-11-17T14:08:59Z"), TimeZone.UTC))
    }

    // 戻せる時点の日時は、渡したタイムゾーンのローカル時刻で出し、日付が繰り上がる
    @Test
    fun revisionTimeFollowsTimeZone() {
        assertEquals(
            "2026/11/18 00:05",
            formatRevisionTime(Instant.parse("2026-11-17T15:05:00Z"), TimeZone.of("Asia/Tokyo")),
        )
        assertEquals(
            "2027/01/01 00:00",
            formatRevisionTime(Instant.parse("2026-12-31T15:00:00Z"), TimeZone.of("Asia/Tokyo")),
        )
    }

    // 以前の状態に戻す画面の、定数の文
    @Test
    fun revisionConstants() {
        assertEquals("以前の状態に戻す", REVISIONS_TITLE)
        assertEquals("保存先のデータを、過去のある時点の状態に戻します。", REVISIONS_DESCRIPTION)
        assertEquals("戻せる以前の状態がありません。", NO_REVISIONS_NOTICE)
        assertEquals("この時点の状態に戻しますか？", RESTORE_CONFIRMATION_TITLE)
        assertEquals("戻す", RESTORE_LABEL)
        assertEquals("以前の状態に戻しています…", RESTORING_NOTICE)
    }

    // 一覧を読めなかったときは、そのことの次の行に S3 の失敗の文を続け、詳細は失敗のものを使う
    @Test
    fun revisionListFailureContinuesWithFailureText() {
        val message = describeRevisionListFailure(SyncFailure.Unreachable("SocketTimeoutException"))

        assertEquals(
            "以前の状態の一覧を読み込めませんでした。\nS3 に接続できませんでした。ネットワークの状態を確認してください。",
            message.text,
        )
        assertEquals("SocketTimeoutException", message.detail)
    }

    // 選んだ時点を読めなかったときは、そのことの次の行に S3 の失敗の文を続け、詳細は失敗のものを使う
    @Test
    fun revisionReadSyncFailureContinuesWithFailureText() {
        val message = describeRevisionReadFailure(RevisionFailure.Sync(SyncFailure.Other("IllegalStateException")))

        assertEquals("この時点のデータを読み込めませんでした。\nS3 とのやり取りでエラーが起きました。", message.text)
        assertEquals("IllegalStateException", message.detail)
    }

    // 戻せなかったときは、そのことの次の行に S3 の失敗の文を続け、詳細は失敗のものを使う
    @Test
    fun restoreSyncFailureContinuesWithFailureText() {
        val message = describeRestoreFailure(RevisionFailure.Sync(SyncFailure.Unreachable("SocketTimeoutException")))

        assertEquals(
            "以前の状態に戻せませんでした。\nS3 に接続できませんでした。ネットワークの状態を確認してください。",
            message.text,
        )
        assertEquals("SocketTimeoutException", message.detail)
    }

    // 詳細の行を持たない S3 の失敗は、読み込みでも戻すときでも詳細が null
    @Test
    fun revisionSyncFailureWithoutDetailHasNullDetail() {
        val failure = RevisionFailure.Sync(SyncFailure.Forbidden)

        assertNull(describeRevisionReadFailure(failure).detail)
        assertNull(describeRestoreFailure(failure).detail)
    }

    // 残っていない・形式が新しい失敗は、読み込みでも戻すときでも同じ専用の文を単独で出し、詳細は無い
    @Test
    fun incompleteAndUpdateRequiredHaveTextOnly() {
        val incomplete = UiMessage("この時点のデータの一部が残っていないため、戻せません。")
        val updateRequired = UiMessage("この時点のデータは形式が新しいため、戻せません。アプリを更新してください。")

        assertEquals(incomplete, describeRevisionReadFailure(RevisionFailure.Incomplete))
        assertEquals(incomplete, describeRestoreFailure(RevisionFailure.Incomplete))
        assertEquals(updateRequired, describeRevisionReadFailure(RevisionFailure.UpdateRequired))
        assertEquals(updateRequired, describeRestoreFailure(RevisionFailure.UpdateRequired))
    }

    // 壊れている失敗は、読み込みでも戻すときでも専用の文を単独で出し、詳細は壊れている箇所
    @Test
    fun corruptRevisionDetailIsProblem() {
        val expected = UiMessage("この時点のデータが壊れているため、戻せません。", "3 行目: version が整数でない")

        assertEquals(expected, describeRevisionReadFailure(RevisionFailure.Corrupt(problem)))
        assertEquals(expected, describeRestoreFailure(RevisionFailure.Corrupt(problem)))
    }

    // 以前の状態に戻す確認の本文は、日時を先頭に入れる
    @Test
    fun restoreConfirmationHasTime() {
        assertEquals(
            "2026/11/17 14:08 の状態に戻します。それより後の変更は、すべて取り消されます。",
            restoreConfirmation("2026/11/17 14:08"),
        )
    }

    // フォルダと項目の数の行は、その時点と現在で頭が違い、数を入れる
    @Test
    fun countsLinesHaveCounts() {
        val counts = ManifestCounts(folders = 3, items = 42)

        assertEquals("この時点：フォルダ 3 個、項目 42 件", revisionCountsLine(counts))
        assertEquals("現在：フォルダ 3 個、項目 42 件", currentCountsLine(counts))
    }

    // 保存したアプリの行は、android・desktop・host の 3 つだけ文を返し、知らない値は null
    @Test
    fun savedByLineKnowsThreeApps() {
        assertEquals("保存したアプリ：Android 版", savedByLine("android"))
        assertEquals("保存したアプリ：デスクトップ版", savedByLine("desktop"))
        assertEquals("保存したアプリ：ブラウザの拡張機能", savedByLine("host"))
        assertNull(savedByLine("ios"))
        assertNull(savedByLine(""))
    }

    // 戻せたお知らせは、日時を先頭に入れる
    @Test
    fun restoredNoticeHasTime() {
        assertEquals("2026/11/17 14:08 の状態に戻しました。", restoredNotice("2026/11/17 14:08"))
    }

    // 「アプリの更新」の節の、定数の文
    @Test
    fun updateConstants() {
        assertEquals("アプリの更新", UPDATE_SECTION_TITLE)
        assertEquals("更新を確認", CHECK_UPDATE_LABEL)
        assertEquals("確認しています…", CHECKING_UPDATE_NOTICE)
        assertEquals("最新のバージョンです。", UP_TO_DATE_NOTICE)
        assertEquals("ダウンロード", DOWNLOAD_UPDATE_LABEL)
        assertEquals("ダウンロードしています…", DOWNLOADING_UPDATE_NOTICE)
        assertEquals("ダウンロードしたファイルを確認しています…", VERIFYING_UPDATE_NOTICE)
        assertEquals("インストールすると Cloud Marks をいったん終了し、更新が終わったら起動し直します。", RESTART_TO_APPLY_NOTICE)
        assertEquals("今すぐインストール", APPLY_UPDATE_LABEL)
        assertEquals("インストールを始めます。", LAUNCHING_INSTALLER_NOTICE)
        assertEquals("開発版では、更新をインストールできません。", DEV_BUILD_UPDATE_NOTICE)
        assertEquals("キャンセル", CANCEL_LABEL)
        assertEquals("閉じる", CLOSE_LABEL)
    }

    // 動いているバージョンの行と、新しいバージョンがあることの文は、バージョンを入れる
    @Test
    fun updateVersionLinesHaveVersion() {
        assertEquals("現在のバージョンは 1.2.3 です。", currentVersionNotice("1.2.3"))
        assertEquals("現在のバージョンは 0.0.0 (dev-20261118_120000) です。", currentVersionNotice("0.0.0 (dev-20261118_120000)"))
        assertEquals("新しいバージョン 1.3.0 があります。", updateAvailableNotice("1.3.0"))
    }

    // 更新を確認できなかった理由は、理由ごとの文だけで、詳細は無い
    @Test
    fun updateCheckFailuresHaveTextOnly() {
        assertEquals(UiMessage("更新情報を取得できませんでした。"), describeUpdateCheckFailure(UpdateCheckFailure.UNREACHABLE))
        assertEquals(UiMessage("更新情報の署名を確認できませんでした。"), describeUpdateCheckFailure(UpdateCheckFailure.SIGNATURE_UNVERIFIED))
        assertEquals(UiMessage("更新情報を読み取れませんでした。"), describeUpdateCheckFailure(UpdateCheckFailure.UNREADABLE))
    }

    // インストーラへ渡せなかった理由は、理由ごとの文だけで、詳細は無い
    @Test
    fun updateInstallFailuresHaveTextOnly() {
        assertEquals(UiMessage("更新をダウンロードできませんでした。"), describeUpdateInstallFailure(UpdateInstallFailure.DOWNLOAD_FAILED))
        assertEquals(
            UiMessage("ダウンロードしたファイルの確認に失敗しました。"),
            describeUpdateInstallFailure(UpdateInstallFailure.VERIFICATION_FAILED),
        )
        assertEquals(UiMessage("更新をインストールできませんでした。"), describeUpdateInstallFailure(UpdateInstallFailure.HAND_OVER_FAILED))
    }

    // 前の起動で更新を適用できなかった知らせは、バージョンを入れ、やり直せる場所を添える
    @Test
    fun updateApplyFailedNoticeHasVersion() {
        assertEquals(
            "バージョン 1.3.0 への更新を適用できませんでした。設定の画面の「アプリの更新」からやり直せます。",
            updateApplyFailedNotice("1.3.0"),
        )
    }
}
