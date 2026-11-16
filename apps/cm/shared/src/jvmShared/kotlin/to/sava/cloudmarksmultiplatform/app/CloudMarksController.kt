package to.sava.cloudmarksmultiplatform.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.sava.cloudmarksmultiplatform.AppFiles
import to.sava.cloudmarksmultiplatform.browse.BrowseState
import to.sava.cloudmarksmultiplatform.files.describeIoFailure
import to.sava.cloudmarksmultiplatform.logging.FileLog
import to.sava.cloudmarksmultiplatform.operations.TreeState
import to.sava.cloudmarksmultiplatform.settings.ConnectionSettings
import to.sava.cloudmarksmultiplatform.settings.SecretProtector
import to.sava.cloudmarksmultiplatform.settings.SettingsFile
import to.sava.cloudmarksmultiplatform.settings.SettingsForm
import to.sava.cloudmarksmultiplatform.settings.SettingsFormResult
import to.sava.cloudmarksmultiplatform.settings.SettingsReadResult
import to.sava.cloudmarksmultiplatform.settings.SettingsWriteResult
import to.sava.cloudmarksmultiplatform.settings.toSettings
import to.sava.cloudmarksmultiplatform.sync.CreateManifestResult
import to.sava.cloudmarksmultiplatform.sync.FolderReadResult
import to.sava.cloudmarksmultiplatform.sync.SyncFailure
import to.sava.cloudmarksmultiplatform.sync.TreeReadResult
import to.sava.cloudmarksmultiplatform.sync.connectionCacheDirectory
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.time.TimeSource

/** マニフェストを読む関数。読んだマニフェストのツリーから、項目を一緒に読むフォルダを決める関数を受け取る。 */
private typealias TreeRead = suspend (foldersToLoad: (TreeState) -> Set<String>) -> TreeReadResult

/**
 * 画面の状態を持ち、利用者の操作に応じて、設定の読み書きと S3 の読みを進める。
 *
 * 状態は [state] で出す。[scope] にはメインのスレッドで動くものを渡し、操作の関数はメインのスレッドから呼ぶ。
 * [start] は [scope] で走らせるだけなので、画面を出す前に別のスレッドから呼んでもよい。
 * 使い終えたら [close] を呼ぶ。[scope] の取り消しは渡した側が行う。
 *
 * S3 からマニフェストを読み直すのは、[start] で設定を読めたとき、[reload] を呼んだとき、設定を保存したとき、
 * マニフェストを作ろうとして既にあったとき、開いたフォルダの項目ファイルが `404` を返したときだけ。
 * フォルダを開いたときはマニフェストを GET せず、読んであるツリーからそのフォルダの項目ファイルだけを読む。
 *
 * @param connect 設定から S3 への接続を作る。[ioDispatcher] で呼ぶ
 * @param log 利用者への誤りとしては見せない失敗を書く。認証情報は渡さない
 * @param ioDispatcher 設定のファイルの読み書き、接続を作ること、ローカルキャッシュのディレクトリを消すことに使う
 */
class CloudMarksController(
    appFiles: AppFiles,
    protector: SecretProtector,
    private val connect: (ConnectionSettings) -> Connection,
    private val log: (String) -> Unit,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val settingsFile = SettingsFile(appFiles.settingsFile, protector)
    private val cacheRoot: Path = appFiles.cacheDirectory

    private val mutableState = MutableStateFlow(AppState())

    /** 画面全体の状態。 */
    val state: StateFlow<AppState> = mutableState.asStateFlow()

    /** 保存済みの設定。シークレットを含むので、画面の状態には出さない。読めていなければ null。 */
    private var storedSettings: ConnectionSettings? = null

    /** いまの S3 への接続。設定を読めていなければ null。 */
    private var active: ActiveConnection? = null

    /**
     * S3 への接続と、それを使う処理を走らせるスコープ。スコープは [scope] の子で、1 つの処理の失敗がほかを止めない。
     * 接続を替えるときと閉じるときは、スコープを取り消してから接続を閉じる。
     */
    private class ActiveConnection(val connection: Connection, val scope: CoroutineScope)

    /**
     * 設定を読む。読めたら S3 への接続を作ってメインの画面を出し、マニフェストとルートフォルダの項目を読む。
     * 設定がまだ無いか読めなければ、設定の画面を出して理由を見せる。
     */
    fun start() {
        scope.launch {
            val read = withContext(ioDispatcher) { settingsFile.read() }
            if (read !is SettingsReadResult.Found) {
                update { it.copy(screen = Screen.SETTINGS, settings = SettingsScreenState(readProblem = read)) }
                return@launch
            }
            storedSettings = read.settings
            val connection = replaceConnection(read.settings)
            update {
                it.copy(
                    screen = Screen.MAIN,
                    settings = storedSettingsScreen(read.settings),
                )
            }
            readTree(connection)
        }
    }

    /**
     * 戻る。設定の画面ならメインの画面へ戻り、入力した値を捨てて保存済みの設定から作り直す。
     * メインの画面ならドロワーを閉じるか、開いたフォルダの履歴を戻る。
     */
    fun back() {
        update { current ->
            val back = current.back()
            if (current.screen == Screen.SETTINGS && back.screen == Screen.MAIN) {
                back.copy(settings = editingStoredSettings(back.settings))
            } else {
                back
            }
        }
        loadCurrentFolder()
    }

    /** ツリーを出し入れする。広い画面ではツリーの枠を、狭い画面ではドロワーを。 */
    fun toggleTreePane() {
        updateBrowse { it.toggleTreePane() }
    }

    /** ドロワーを閉じる。ドロワーが画面の操作で閉じたときにも呼ぶ。 */
    fun closeDrawer() {
        updateBrowse { it.closeDrawer() }
    }

    /** 画面の幅の区分を受け取る。 */
    fun setWideLayout(wide: Boolean) {
        updateBrowse { it.withWideLayout(wide) }
    }

    /** ツリーのノードを開く、または閉じる。 */
    fun toggleExpanded(folderId: String) {
        updateBrowse { it.toggleExpanded(folderId) }
    }

    /** フォルダを開き、まだ読んでいなければ、そのフォルダの項目を読む。 */
    fun openFolder(folderId: String) {
        updateBrowse { it.openFolder(folderId) }
        loadCurrentFolder()
    }

    /** 開いているフォルダの項目を選ぶ。 */
    fun selectItem(itemId: String) {
        updateBrowse { it.selectItem(itemId) }
    }

    /** マニフェストを読み直す。開いているフォルダが残っていれば、その項目も一緒に読む。読んでいる最中なら何もしない。 */
    fun reload() {
        val connection = active ?: return
        if (!state.value.canReload) return
        readTree(connection)
    }

    /** 設定の画面を出す。入力は保存済みの設定に戻し、シークレットの欄は空にする。 */
    fun openSettings() {
        update {
            if (it.screen != Screen.MAIN) return@update it
            it.copy(screen = Screen.SETTINGS, browse = it.browse.closeDrawer(), settings = editingStoredSettings(it.settings))
        }
    }

    /** 設定の画面の入力を [form] に替える。保存の最中は受け付けない。 */
    fun editSettings(form: SettingsForm) {
        updateSettings { if (it.status.busy) it else it.copy(form = form, status = SettingsStatus.Editing) }
    }

    /**
     * 入力した設定を保存し、続けてマニフェストを読む。
     *
     * 入力が足りなければ保存しない。シークレットの欄が空なら、保存済みのものを使う。暗号化の仕組みが使えなければ、
     * 保存を断って理由を見せる。保存できたら、読んであったツリーを捨ててから S3 への接続を作り直し（前の S3 のクライアントは閉じる）、
     * 接続先（バケットかプレフィックス）が替わっていれば、前の接続先のローカルキャッシュのディレクトリを消す。
     * マニフェストを読めたらメインの画面へ戻り、マニフェストが無ければマニフェストを作れるようにし、読めなければ理由を見せる。
     */
    fun saveSettings() {
        val current = state.value.settings
        if (current.status.busy) return
        val settings = when (val input = current.form.toSettings(storedSettings)) {
            is SettingsFormResult.Incomplete -> {
                updateSettings { it.copy(status = SettingsStatus.Incomplete(input.fields)) }
                return
            }
            is SettingsFormResult.Complete -> input.settings
        }
        updateSettings { it.copy(status = SettingsStatus.Saving) }
        scope.launch { save(settings) }
    }

    /** マニフェストが無いと分かったあとで、マニフェストを新しく作る。作れたらメインの画面へ戻る。 */
    fun createManifest() {
        val connection = active ?: return
        if (state.value.settings.status != SettingsStatus.ManifestNotFound) return
        updateSettings { it.copy(status = SettingsStatus.CreatingManifest) }
        connection.scope.launch {
            when (val created = connection.connection.sync.createManifest()) {
                is CreateManifestResult.Created -> showConnectionResult(TreeReadResult.Readable(created.tree))
                CreateManifestResult.AlreadyExists -> checkConnection(connection)
                is CreateManifestResult.Failed ->
                    updateSettings { it.copy(status = SettingsStatus.ConnectionFailed(created.failure)) }
            }
        }
    }

    /** 走っている読み書きを取り消し、S3 のクライアントを閉じる。 */
    override fun close() {
        active?.let { previous ->
            previous.scope.cancel()
            previous.connection.close()
        }
        active = null
    }

    private suspend fun save(settings: ConnectionSettings) {
        when (val written = withContext(ioDispatcher) { settingsFile.write(settings) }) {
            is SettingsWriteResult.ProtectionUnavailable -> {
                updateSettings { it.copy(status = SettingsStatus.ProtectionUnavailable(written.detail)) }
                return
            }
            is SettingsWriteResult.Failed -> {
                updateSettings { it.copy(status = SettingsStatus.WriteFailed(written.detail)) }
                return
            }
            SettingsWriteResult.Success -> Unit
        }
        val previous = storedSettings
        storedSettings = settings
        update {
            it.copy(
                browse = it.browse.cleared(),
                settings = storedSettingsScreen(settings).copy(status = SettingsStatus.Saving),
            )
        }
        val connection = replaceConnection(settings)
        previous?.let { deletePreviousCache(it, settings) }
        checkConnection(connection)
    }

    /** マニフェストとルートフォルダの項目を読み、結果を設定の画面に見せる。 */
    private fun checkConnection(connection: ActiveConnection) {
        readTree(connection, show = ::showConnectionResult)
    }

    /** マニフェストを読んだ結果 [result] をツリーに入れ、読めたらメインの画面へ戻る。読めなければ、設定の画面に理由を見せる。 */
    private fun showConnectionResult(result: TreeReadResult) {
        update { current ->
            val withTree = current.copy(browse = current.browse.withTreeRead(result))
            when (val failure = (result as? TreeReadResult.Failed)?.failure) {
                null -> withTree.copy(screen = Screen.MAIN, settings = withTree.settings.copy(status = SettingsStatus.Editing))
                SyncFailure.ManifestNotFound ->
                    withTree.copy(settings = withTree.settings.copy(status = SettingsStatus.ManifestNotFound))
                else -> withTree.copy(settings = withTree.settings.copy(status = SettingsStatus.ConnectionFailed(failure)))
            }
        }
    }

    /**
     * [read] でマニフェストを読み、開いているフォルダ（残っていなければルートフォルダ）の項目を一緒に読んで、結果を [show] に渡す。
     * マニフェストを読んでいる最中の状態にするのは、呼んだときにする。そのため、読み直すボタンを続けて押しても、2 回は読まない。
     *
     * @param read マニフェストを読む
     * @param show 読んだ結果を見せる
     */
    private fun readTree(
        connection: ActiveConnection,
        read: TreeRead = connection.connection.sync::read,
        show: (TreeReadResult) -> Unit = ::showTree,
    ) {
        updateBrowse { it.startLoading() }
        connection.scope.launch {
            show(read { tree -> setOf(state.value.browse.folderToOpenIn(tree)) })
        }
    }

    /** マニフェストを読んだ結果 [result] をツリーに入れ、開いているフォルダの項目を読む。 */
    private fun showTree(result: TreeReadResult) {
        updateBrowse { it.withTreeRead(result) }
        loadCurrentFolder()
    }

    /**
     * 開いているフォルダの項目をまだ読んでいなければ、読んであるツリーから読む。マニフェストは GET しない。
     *
     * 項目ファイルが `404` を返したら、マニフェストを一度だけ読み直し（[reload] と同じくツリーを替え、開いているノード・
     * 開いていたフォルダ・選んでいた項目を保つ）、そのあと開いているフォルダの項目を読む。読み直したマニフェストがまだ同じファイルを
     * 指していれば、そのフォルダは壊れているとし、もう読み直さない。
     */
    private fun loadCurrentFolder() {
        val connection = active ?: return
        val browse = state.value.browse
        val tree = browse.tree ?: return
        val folderId = browse.folderToLoad() ?: return
        updateBrowse { it.withFolderLoading(folderId) }
        connection.scope.launch {
            val result = connection.connection.sync.readFolder(tree, folderId)
            updateBrowse { it.withFolderRead(tree.eTag, folderId, result) }
            if (result is FolderReadResult.Missing) {
                readTree(connection, read = { foldersToLoad ->
                    connection.connection.sync.readAfterMissing(tree, folderId, foldersToLoad)
                })
            }
        }
    }

    /** いまの接続を、走っている処理を取り消してから閉じ、[settings] の接続を作る。 */
    private suspend fun replaceConnection(settings: ConnectionSettings): ActiveConnection {
        active?.let { previous ->
            active = null
            previous.scope.coroutineContext.job.cancelAndJoin()
            previous.connection.close()
        }
        val connection = withContext(ioDispatcher) { connect(settings) }
        val connectionScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        return ActiveConnection(connection, connectionScope).also { active = it }
    }

    /**
     * 接続先が [previous] から [settings] へ替わっていれば、[previous] のローカルキャッシュのディレクトリを消す。
     * 消せなければログに書き、利用者には見せない。
     */
    @OptIn(ExperimentalPathApi::class)
    private suspend fun deletePreviousCache(previous: ConnectionSettings, settings: ConnectionSettings) {
        val previousDirectory = connectionCacheDirectory(cacheRoot, previous.bucket, previous.prefix)
        if (previousDirectory == connectionCacheDirectory(cacheRoot, settings.bucket, settings.prefix)) return
        withContext(ioDispatcher) {
            try {
                previousDirectory.deleteRecursively()
            } catch (e: IOException) {
                log("前の接続先のローカルキャッシュを消せなかった: ${describeIoFailure(e)}")
            }
        }
    }

    /** 保存済みの設定 [settings] を開いたときの、設定の画面。 */
    private fun storedSettingsScreen(settings: ConnectionSettings): SettingsScreenState =
        SettingsScreenState(form = SettingsForm.of(settings), hasStoredSettings = true, canReturn = true)

    /** 入力を保存済みの設定から作り直した [settings]。シークレットの欄は空にし、保存の結果を消す。 */
    private fun editingStoredSettings(settings: SettingsScreenState): SettingsScreenState =
        settings.copy(form = SettingsForm.of(storedSettings), status = SettingsStatus.Editing)

    private fun update(transform: (AppState) -> AppState) {
        mutableState.update(transform)
    }

    private fun updateBrowse(transform: (BrowseState) -> BrowseState) {
        update { it.copy(browse = transform(it.browse)) }
    }

    private fun updateSettings(transform: (SettingsScreenState) -> SettingsScreenState) {
        update { it.copy(settings = transform(it.settings)) }
    }
}

/**
 * [CloudMarksController] を組み立てる。Android とデスクトップのエントリポイントが、プラットフォームごとに違うもの（ファイルの場所、
 * 認証情報を暗号化する仕組み、時計、マニフェストの `updatedBy`）を渡して呼ぶ。ログは [AppFiles.logDirectory] のファイルに書く。
 *
 * 呼んだ側が、作ったコントローラを [CloudMarksController.start] で動かし始め、使い終えたら [CloudMarksController.close] で閉じる。
 *
 * @param appFiles 設定のファイル・ローカルキャッシュ・ログの場所
 * @param protector 認証情報を暗号化する仕組み
 * @param timeSource 書き込みの手順 3 から手順 4 までの経過を測る、モノトニッククロック
 * @param updatedBy 書き込むマニフェストのヘッダーの `updatedBy`。`"desktop"` か `"android"`
 * @param scope メインのスレッドで動くスコープ。取り消しは呼んだ側が行う
 */
fun createCloudMarksController(
    appFiles: AppFiles,
    protector: SecretProtector,
    timeSource: TimeSource,
    updatedBy: String,
    scope: CoroutineScope,
): CloudMarksController {
    val log = FileLog(appFiles.logDirectory)
    return CloudMarksController(
        appFiles = appFiles,
        protector = protector,
        connect = { settings -> openS3Connection(settings, appFiles.cacheDirectory, updatedBy, timeSource, log::write) },
        log = log::write,
        scope = scope,
    )
}
