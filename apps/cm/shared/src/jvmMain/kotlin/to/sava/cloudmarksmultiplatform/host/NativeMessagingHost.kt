package to.sava.cloudmarksmultiplatform.host

import to.sava.cloudmarksmultiplatform.AppFiles
import to.sava.cloudmarksmultiplatform.AppPath
import to.sava.cloudmarksmultiplatform.app.Connection
import to.sava.cloudmarksmultiplatform.app.openS3Connection
import to.sava.cloudmarksmultiplatform.files.describeIoFailure
import to.sava.cloudmarksmultiplatform.files.replaceFileAtomically
import to.sava.cloudmarksmultiplatform.settings.ConnectionSettings
import to.sava.cloudmarksmultiplatform.settings.SecretProtector
import to.sava.cloudmarksmultiplatform.settings.SettingsFile
import to.sava.cloudmarksmultiplatform.settings.SettingsForm
import to.sava.cloudmarksmultiplatform.settings.SettingsFormResult
import to.sava.cloudmarksmultiplatform.settings.SettingsReadResult
import to.sava.cloudmarksmultiplatform.settings.toSettings
import to.sava.cloudmarksmultiplatform.storage.newId
import to.sava.cloudmarksmultiplatform.sync.InMemorySyncCache
import to.sava.cloudmarksmultiplatform.sync.SyncCache
import to.sava.cloudmarksmultiplatform.sync.WriteResult
import to.sava.cloudmarksmultiplatform.sync.writeConfirming
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.TimeSource

/** Native Messaging のホストが書き込むマニフェストのヘッダーの `updatedBy`。 */
internal const val HOST_UPDATED_BY = "host"

/** ホストがログの行の頭に付ける語。同じログのファイルに書くアプリの行と見分ける。 */
internal const val HOST_LOG_PREFIX = "Native Messaging のホスト: "

/** 設定から S3 への接続を作る関数。引数は、キャッシュを渡す [openS3Connection] と同じ。 */
internal typealias OpenConnection =
    (settings: ConnectionSettings, cache: SyncCache, updatedBy: String, timeSource: TimeSource) -> Connection

/**
 * Native Messaging のホスト。拡張から届いたメッセージを 1 つ処理し、応答を返す（`docs/native-messaging.md`）。
 *
 * 拡張が入っていることの知らせ（`extensionPresent`）は、ブラウザの実行ファイルを [AppFiles.browsersWithExtensionFile] に書き足す。
 * コマンドの受け取り（`takeCommand`）は、[AppFiles.commandDirectory] のコマンドのファイルを読んで消し、中身を返す。
 * アプリを開く要求（`openApp`）は、ホストの起動口と同じディレクトリのデスクトップのアプリ（[appLauncherBesideHost]）を、
 * このプロセスのジョブから抜けて起動する。この 3 つは、アプリの設定を読まず、S3 に接続しない。
 *
 * 追加は、要求を検証し、アプリの設定を読み、操作を組み立てて 1 回の書き込みとして S3 へ書く。書けたかどうかを確かめられなければ、
 * 同じ操作でもう一度だけ書く。マニフェストのヘッダーの `updatedBy` は [HOST_UPDATED_BY]。
 * キャッシュはメモリの中だけに持ち、ローカルキャッシュは読みも書きもしない。
 * 書けたとき、または書けたかどうかを確かめられなかったときは、[AppFiles.storageChangedFile] を書き換えて、
 * デスクトップのアプリに知らせる。書き換えられなくても、ログに書くだけで応答は変えない。
 *
 * 失敗は、その詳細を [log] に書く。認証情報と、要求の中の URL・タイトルは書かない。
 *
 * @param appFiles アプリと同じファイルの場所。設定のファイル、S3 へ書いたことを知らせるファイル、
 * 拡張が入っているブラウザの記録、コマンドのディレクトリを使う
 * @param protector 設定の認証情報を復号する仕組み。アプリと同じもの
 * @param openConnection 設定から S3 への接続を作る
 * @param clock 追加した日時と、S3 へ書いたことを知らせるファイルの中身に使う時計
 * @param generateId フォルダと項目の ID を振る
 * @param ancestorExecutables ホストを起動したプロセスの祖先の実行ファイルのパス。拡張が入っていることの知らせで、ブラウザを見つけるのに使う
 * @param ownExecutable このプロセス自身の実行ファイルのパス。祖先のうち、同じ名前のものはブラウザではないとして飛ばす
 * @param hostExecutable ホストの起動口（`cloud-marks-host.exe`）のパス。開発版のホストでは null で、デスクトップのアプリを起動しない
 * @param launchApp デスクトップのアプリの実行ファイルを、このプロセスのジョブから抜けて起動する
 */
internal class NativeMessagingHost(
    private val appFiles: AppFiles,
    protector: SecretProtector,
    private val log: (String) -> Unit,
    private val openConnection: OpenConnection = ::openS3Connection,
    private val clock: Clock = Clock.System,
    private val generateId: () -> String = ::newId,
    private val ancestorExecutables: AncestorExecutables = ::processAncestorExecutables,
    private val ownExecutable: () -> String? = ::processOwnExecutable,
    private val hostExecutable: () -> String? = { AppPath.verified },
    private val launchApp: LaunchOutsideJob = ::launchOutsideJob,
) {
    private val settingsFile = SettingsFile(appFiles.settingsFile, protector)

    /**
     * [input] からメッセージを 1 つ読んで処理し、応答を返す。メッセージが長さの上限を超えているか、途中で切れていれば、
     * 取り決めに合わないとする。予期しない例外（[Exception]）は、そのほかの失敗にしてログに書く。取り消しはそのまま伝える。
     */
    suspend fun respond(input: InputStream): HostResponse =
        try {
            when (val frame = readMessage(input)) {
                is FrameReadResult.Message -> handle(frame.body)
                is FrameReadResult.TooLarge -> failed(INVALID_REQUEST_ERROR, "メッセージの長さ（${frame.length} バイト）が上限を超えた")
                FrameReadResult.Truncated -> failed(INVALID_REQUEST_ERROR, "メッセージが途中で切れた")
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            failed(INTERNAL_ERROR, "予期しない失敗: ${describeUnexpectedFailure(e)}")
        }

    /** メッセージの本体 [body] の要求を処理する。 */
    private suspend fun handle(body: ByteArray): HostResponse {
        val request = when (val parsed = parseHostRequest(body)) {
            is HostRequestParseResult.Valid -> parsed.request
            is HostRequestParseResult.Invalid -> return failed(INVALID_REQUEST_ERROR, "要求を受け付けなかった: ${parsed.reason}")
        }
        return when (request) {
            is HostRequest.Add -> add(request)
            HostRequest.ExtensionPresent -> recordExtensionPresent()
            is HostRequest.TakeCommand -> HostResponse.CommandTaken(takeCommandFile(appFiles.commandDirectory, request.token, log))
            HostRequest.OpenApp -> openApp()
        }
    }

    /**
     * デスクトップのアプリを起動する。起動したプロセスの終わりも、ウィンドウが出ることも待たない。設定は読まない。
     *
     * 開発版のホストは起動せず、[DEV_HOST_LAUNCH_ERROR] を返す。起動できなければ [LAUNCH_FAILED_ERROR] を返す。
     * 予期しない例外もここで受けて [LAUNCH_FAILED_ERROR] にする。外側の [respond] が返す [INTERNAL_ERROR] は、追加の失敗の文。
     */
    private fun openApp(): HostResponse =
        try {
            when (val executable = hostExecutable()) {
                null -> failed(DEV_HOST_LAUNCH_ERROR, "開発版のホストなので、デスクトップのアプリを起動しなかった")
                else -> launchResponseOf(launchApp(appLauncherBesideHost(executable)))
            }
        } catch (e: Exception) {
            failed(LAUNCH_FAILED_ERROR, "デスクトップのアプリを起動できなかった: ${describeUnexpectedFailure(e)}")
        }

    /** デスクトップのアプリを起動した結果 [result] を、応答にする。ジョブに残したまま起動したときは、ログに書く。 */
    private fun launchResponseOf(result: ProcessLaunchResult): HostResponse =
        when (result) {
            is ProcessLaunchResult.Launched -> {
                if (!result.brokeAwayFromJob) {
                    log(HOST_LOG_PREFIX + "ジョブから抜けられなかったので、デスクトップのアプリをホストのジョブに残したまま起動した")
                }
                HostResponse.Ok
            }
            is ProcessLaunchResult.Failed ->
                failed(LAUNCH_FAILED_ERROR, "デスクトップのアプリを起動できなかった: ${describeLaunchFailure(result)}")
        }

    /** ブラウザの実行ファイルを、拡張が入っているブラウザの記録に書き足す。見つからなければ、ログに書くだけにする。設定は読まない。 */
    private fun recordExtensionPresent(): HostResponse {
        when (val executable = findBrowserExecutable(ancestorExecutables(), ownExecutable())) {
            null -> log(HOST_LOG_PREFIX + "ホストを起動したブラウザの実行ファイルを見つけられなかった")
            else -> recordBrowserWithExtension(appFiles.browsersWithExtensionFile, executable, log)
        }
        return HostResponse.Ok
    }

    /** 追加の要求 [request] を、S3 へ書く。 */
    private suspend fun add(request: HostRequest.Add): HostResponse {
        val settings = when (val read = settingsFile.read()) {
            is SettingsReadResult.Found ->
                read.settings.takeIf { it.isComplete() } ?: return failed(SETTINGS_MISSING_ERROR, "設定に未入力の項目がある")
            SettingsReadResult.NotFound -> return failed(SETTINGS_MISSING_ERROR, "設定のファイルが無い")
            is SettingsReadResult.Corrupt, is SettingsReadResult.Undecryptable, is SettingsReadResult.Failed ->
                return failed(SETTINGS_UNREADABLE_ERROR, "設定を読めなかった: $read")
        }
        val operation = request.toOperation(added = clock.now(), generateId = generateId)
        val result = openConnection(settings, InMemorySyncCache(), HOST_UPDATED_BY, TimeSource.Monotonic).use { connection ->
            connection.sync.writeConfirming(listOf(operation))
        }
        if (result !is WriteResult.Failed) notifyStorageChanged()
        return when (val response = hostResponseOf(result)) {
            is HostResponse.Failed -> failed(response.error, describeWriteFailure(result))
            HostResponse.Ok, is HostResponse.CommandTaken -> response
        }
    }

    /** [AppFiles.storageChangedFile] を、いまの時刻を中身にして書き換える。書き換えられなければ、ログに書く。 */
    private fun notifyStorageChanged() {
        try {
            replaceFileAtomically(appFiles.storageChangedFile, "${clock.now()}\n".encodeToByteArray())
        } catch (e: IOException) {
            log(HOST_LOG_PREFIX + "S3 へ書いたことを知らせるファイルを書き換えられなかった: ${describeIoFailure(e)}")
        }
    }

    /** [detail] をログに書き、[error] の失敗の応答を返す。 */
    private fun failed(error: HostError, detail: String): HostResponse {
        log("$HOST_LOG_PREFIX$detail")
        return HostResponse.Failed(error)
    }
}

/** アプリの設定の画面で入力が要る欄が、どれも埋まっているか。 */
private fun ConnectionSettings.isComplete(): Boolean =
    SettingsForm.of(this).toSettings(this) is SettingsFormResult.Complete

/** 失敗の応答になった書き込みの結果 [result] を、ログの文にする。操作は URL とタイトルを含むので、結果の状態だけを書く。 */
private fun describeWriteFailure(result: WriteResult): String =
    when (result) {
        is WriteResult.Success -> "適用できなかった操作がある: ${result.results.map { it.status }}"
        is WriteResult.Failed -> "追加を書けなかった: ${result.failure}"
        is WriteResult.Unconfirmed -> "追加を書けたかどうかを確かめられなかった: ${result.failure}"
    }

/** 起動の失敗 [failure] を、ログの文にする。失敗の値はどれもパスを含まない。 */
private fun describeLaunchFailure(failure: ProcessLaunchResult.Failed): String =
    when (val lastError = failure.lastError) {
        null -> "${failure.function} を呼べなかった（${failure.reason}）"
        else -> "${failure.function} が失敗した（GetLastError: 0x%08X）".format(lastError)
    }

/**
 * 予期しない例外 [e] を、ログの文にする。I/O の失敗は、パスを含まない説明（[describeIoFailure]）にする。
 * それ以外は例外のクラス名だけにする。例外のメッセージは、接続先や要求の中身を含みうるため使わない。
 */
private fun describeUnexpectedFailure(e: Exception): String =
    if (e is IOException) describeIoFailure(e) else e.javaClass.name
