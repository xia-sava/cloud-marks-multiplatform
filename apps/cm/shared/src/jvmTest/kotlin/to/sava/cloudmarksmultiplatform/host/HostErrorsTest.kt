package to.sava.cloudmarksmultiplatform.host

import to.sava.cloudmarksmultiplatform.operations.AddFolder
import to.sava.cloudmarksmultiplatform.operations.NotAppliedReason
import to.sava.cloudmarksmultiplatform.operations.OperationResult
import to.sava.cloudmarksmultiplatform.operations.OperationStatus
import to.sava.cloudmarksmultiplatform.operations.toTreeState
import to.sava.cloudmarksmultiplatform.storage.StorageProblem
import to.sava.cloudmarksmultiplatform.sync.LoadedTree
import to.sava.cloudmarksmultiplatform.sync.ROOT_ID
import to.sava.cloudmarksmultiplatform.sync.SyncFailure
import to.sava.cloudmarksmultiplatform.sync.WriteResult
import to.sava.cloudmarksmultiplatform.sync.initialManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

private val PROBLEM = StorageProblem(2, "id が UUID の形でない")

private const val NOT_CONFIGURED_MESSAGE = "S3 の設定がまだありません。Cloud Marks のアプリで、保存先とアクセスキーを設定してください。"
private const val SETTINGS_UNREADABLE_MESSAGE = "アプリの設定を読み込めませんでした。Cloud Marks のアプリで、設定を保存し直してください。"
private const val UNREACHABLE_MESSAGE = "S3 に接続できませんでした。ネットワークの状態を確認してください。"
private const val FORBIDDEN_MESSAGE =
    "S3 へのアクセスが拒否されました。Cloud Marks のアプリで、バケット名、プレフィックス、アクセスキーの権限を確認してください。"
private const val UPDATE_REQUIRED_MESSAGE = "データの形式が新しいため、追加できません。Cloud Marks のアプリを更新してください。"
private const val MANIFEST_CORRUPT_MESSAGE = "保存先のデータが壊れているため、追加できません。"
private const val MANIFEST_NOT_FOUND_MESSAGE =
    "この保存先にはまだデータがありません。Cloud Marks のアプリの設定画面で保存し直すと、新しく作成できます。"
private const val MANIFEST_DELETED_MESSAGE = "保存先のデータが見つかりません。削除された可能性があります。"
private const val FOLDER_CORRUPT_MESSAGE = "追加先のフォルダのデータが壊れているため、追加できません。"
private const val BUSY_MESSAGE = "S3 が混み合っているため、追加を終えられませんでした。しばらくしてからやり直してください。"
private const val UNCONFIRMED_MESSAGE =
    "追加できたかどうかを確認できませんでした。もう一度追加すると、同じものが二重に入ることがあります。先に Cloud Marks のアプリで確認してください。"
private const val INVALID_REQUEST_MESSAGE =
    "追加する内容を受け付けられませんでした。拡張機能とアプリのバージョンが合っていない可能性があります。両方を最新にしてください。"
private const val LAUNCH_FAILED_MESSAGE = "Cloud Marks を開けませんでした。スタートメニューから起動してください。"
private const val DEV_HOST_LAUNCH_MESSAGE = "開発版の Cloud Marks は、拡張機能から開けません。"
private const val STORAGE_OTHER_MESSAGE = "S3 とのやり取りでエラーが起きたため、追加できませんでした。"
private const val INTERNAL_MESSAGE = "エラーが起きたため、追加できませんでした。"

class HostErrorsTest {
    // S3 の読み書きの失敗は、種類ごとに決まった code と文言になる
    @Test
    fun syncFailuresMapToCodeAndMessage() {
        val cases: List<Triple<SyncFailure, String, String>> = listOf(
            Triple(SyncFailure.Unreachable("SocketTimeoutException"), "storageUnavailable", UNREACHABLE_MESSAGE),
            Triple(SyncFailure.Forbidden, "storageUnavailable", FORBIDDEN_MESSAGE),
            Triple(SyncFailure.ReadOnly(version = 2, readableBy = 1), "updateRequired", UPDATE_REQUIRED_MESSAGE),
            Triple(SyncFailure.UpdateRequired(version = 2, readableBy = 2), "updateRequired", UPDATE_REQUIRED_MESSAGE),
            Triple(SyncFailure.ManifestCorrupt(PROBLEM), "dataBroken", MANIFEST_CORRUPT_MESSAGE),
            Triple(SyncFailure.ManifestNotFound, "dataBroken", MANIFEST_NOT_FOUND_MESSAGE),
            Triple(SyncFailure.ManifestDeleted, "dataBroken", MANIFEST_DELETED_MESSAGE),
            Triple(SyncFailure.FolderCorrupt(mapOf(ROOT_ID to PROBLEM)), "dataBroken", FOLDER_CORRUPT_MESSAGE),
            Triple(SyncFailure.Busy, "busy", BUSY_MESSAGE),
            Triple(SyncFailure.Other("HTTP 400"), "internal", STORAGE_OTHER_MESSAGE),
        )

        cases.forEach { (failure, code, message) ->
            val error = hostErrorOf(failure)

            assertEquals(code to message, error.code.value to error.message, failure.toString())
        }
    }

    // S3 の読み書き以外の失敗も、決まった code と文言になる
    @Test
    fun otherErrorsHaveCodeAndMessage() {
        val cases = listOf(
            SETTINGS_MISSING_ERROR to ("notConfigured" to NOT_CONFIGURED_MESSAGE),
            SETTINGS_UNREADABLE_ERROR to ("notConfigured" to SETTINGS_UNREADABLE_MESSAGE),
            UNCONFIRMED_ERROR to ("unconfirmed" to UNCONFIRMED_MESSAGE),
            INVALID_REQUEST_ERROR to ("invalidRequest" to INVALID_REQUEST_MESSAGE),
            LAUNCH_FAILED_ERROR to ("launchFailed" to LAUNCH_FAILED_MESSAGE),
            DEV_HOST_LAUNCH_ERROR to ("launchFailed" to DEV_HOST_LAUNCH_MESSAGE),
            INTERNAL_ERROR to ("internal" to INTERNAL_MESSAGE),
        )

        cases.forEach { (error, expected) -> assertEquals(expected, error.code.value to error.message) }
    }

    // 書けて、適用できなかった操作が無ければ、成功の応答になる。適用済みだった操作も成功に数える
    @Test
    fun successIsOk() {
        val results = listOf(
            OperationResult(ADD_FOLDER, OperationStatus.Applied),
            OperationResult(ADD_FOLDER, OperationStatus.AlreadyApplied),
        )

        assertEquals(HostResponse.Ok, hostResponseOf(WriteResult.Success(TREE, results)))
    }

    // 書けても、適用できなかった操作があれば、そのほかの失敗にする
    @Test
    fun notAppliedOperationIsInternal() {
        val results = listOf(OperationResult(ADD_FOLDER, OperationStatus.NotApplied(NotAppliedReason.EmptyName)))

        assertEquals(HostResponse.Failed(INTERNAL_ERROR), hostResponseOf(WriteResult.Success(TREE, results)))
    }

    // 書けなければ、失敗の種類の応答になる
    @Test
    fun failedUsesFailureKind() {
        assertEquals(HostResponse.Failed(FORBIDDEN_ERROR), hostResponseOf(WriteResult.Failed(SyncFailure.Forbidden)))
    }

    // 確かめられなかったときは、中の失敗の種類によらず unconfirmed になる
    @Test
    fun unconfirmedIgnoresFailureKind() {
        listOf(SyncFailure.Busy, SyncFailure.Unreachable("HTTP 500"), SyncFailure.Forbidden).forEach { failure ->
            assertEquals(HostResponse.Failed(UNCONFIRMED_ERROR), hostResponseOf(WriteResult.Unconfirmed(failure)), failure.toString())
        }
    }

    private companion object {
        val ADD_FOLDER = AddFolder("folder", "name", emptyList(), Instant.parse("2026-11-16T12:34:56.789Z"))
        val TREE = initialManifest().let { LoadedTree(it, "\"etag\"", it.toTreeState(), emptyMap()) }
    }
}
