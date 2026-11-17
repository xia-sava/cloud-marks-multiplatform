package to.sava.cloudmarksmultiplatform.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import to.sava.cloudmarksmultiplatform.AppFiles
import to.sava.cloudmarksmultiplatform.browse.BrowseState
import to.sava.cloudmarksmultiplatform.browse.MoveDestination
import to.sava.cloudmarksmultiplatform.browse.MoveSubject
import to.sava.cloudmarksmultiplatform.browse.SelectionMode
import to.sava.cloudmarksmultiplatform.browse.canMove
import to.sava.cloudmarksmultiplatform.browse.selectedItemsSubject
import to.sava.cloudmarksmultiplatform.browser.Browser
import to.sava.cloudmarksmultiplatform.browser.BrowserOpenResult
import to.sava.cloudmarksmultiplatform.browser.BrowserOpener
import to.sava.cloudmarksmultiplatform.browser.BrowserTab
import to.sava.cloudmarksmultiplatform.files.describeIoFailure
import to.sava.cloudmarksmultiplatform.logging.FileLog
import to.sava.cloudmarksmultiplatform.operations.ChangeItemTitle
import to.sava.cloudmarksmultiplatform.operations.CreateFolder
import to.sava.cloudmarksmultiplatform.operations.DeleteFolder
import to.sava.cloudmarksmultiplatform.operations.DeleteItems
import to.sava.cloudmarksmultiplatform.operations.ItemRef
import to.sava.cloudmarksmultiplatform.operations.MoveFolder
import to.sava.cloudmarksmultiplatform.operations.MoveItems
import to.sava.cloudmarksmultiplatform.operations.Operation
import to.sava.cloudmarksmultiplatform.operations.RenameFolder
import to.sava.cloudmarksmultiplatform.operations.TreeState
import to.sava.cloudmarksmultiplatform.settings.ConnectionSettings
import to.sava.cloudmarksmultiplatform.settings.DeviceSettingsFile
import to.sava.cloudmarksmultiplatform.settings.DisplayScale
import to.sava.cloudmarksmultiplatform.settings.SecretProtector
import to.sava.cloudmarksmultiplatform.settings.SettingsFile
import to.sava.cloudmarksmultiplatform.settings.SettingsForm
import to.sava.cloudmarksmultiplatform.settings.SettingsFormResult
import to.sava.cloudmarksmultiplatform.settings.SettingsReadResult
import to.sava.cloudmarksmultiplatform.settings.SettingsWriteResult
import to.sava.cloudmarksmultiplatform.settings.TabLoading
import to.sava.cloudmarksmultiplatform.settings.toSettings
import to.sava.cloudmarksmultiplatform.storage.Item
import to.sava.cloudmarksmultiplatform.storage.newId
import to.sava.cloudmarksmultiplatform.sync.CreateManifestResult
import to.sava.cloudmarksmultiplatform.sync.FolderReadResult
import to.sava.cloudmarksmultiplatform.sync.SyncFailure
import to.sava.cloudmarksmultiplatform.sync.TreeReadResult
import to.sava.cloudmarksmultiplatform.sync.WriteResult
import to.sava.cloudmarksmultiplatform.sync.connectionCacheDirectory
import to.sava.cloudmarksmultiplatform.sync.writeConfirming
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.time.Clock
import kotlin.time.TimeSource

/** マニフェストを読む関数。読んだマニフェストのツリーから、項目を一緒に読むフォルダを決める関数を受け取る。 */
private typealias TreeRead = suspend (foldersToLoad: (TreeState) -> Set<String>) -> TreeReadResult

/**
 * 画面の状態を持ち、利用者の操作に応じて、設定の読み書きと S3 の読み書きを進める。
 *
 * 状態は [state] で出す。[scope] にはメインのスレッドで動くものを渡し、操作の関数はメインのスレッドから呼ぶ。
 * [start] は [scope] で走らせるだけなので、画面を出す前に別のスレッドから呼んでもよい。
 * 使い終えたら [close] を呼ぶ。[scope] の取り消しは渡した側が行う。
 *
 * S3 からマニフェストを読み直すのは、[start] で設定を読めたとき、[reload] を呼んだとき、[storageChanges] で知らせを受けたとき、
 * 設定を保存したとき、マニフェストを作ろうとして既にあったとき、開いたフォルダとブラウザで開くフォルダの項目ファイルが `404` を
 * 返したときだけ。
 * フォルダを開いたときはマニフェストを GET せず、読んであるツリーからそのフォルダの項目ファイルだけを読む。
 *
 * データを変える操作（`docs/operations.md`）は、一度に 1 つだけ書く。書いているあいだは、ほかの操作も読み直しも受け付けない。
 * 書けたら書いたあとのツリーに替え、マニフェストは GET し直さない。
 *
 * [deletesStaleFiles] なら、起動して初めてツリーを読めたときに、前に古いファイルの削除を終えてから間隔を過ぎていれば、
 * 裏で古いファイルの削除を走らせる。結果はログにだけ書く。
 *
 * 項目をブラウザで開くことは、データを変えないので、書いている最中も、読むだけのツリーでも受け付ける。
 *
 * @param connect 設定から S3 への接続を作る。[ioDispatcher] で呼ぶ
 * @param log 利用者への誤りとしては見せない失敗と、古いファイルの削除の結果を書く。認証情報は渡さない
 * @param storageChanges ほかのプロセスが S3 へ書いた知らせ。[start] から集め、知らせを受けるたびに [reload] を呼ぶ。
 * [reload] が何もしないとき（メインの画面でないとき・読んでいる最中・書いている最中・接続が無いとき）の知らせは捨てる。
 * 知らせを出さない Flow を渡してもよい
 * @param deletesStaleFiles 古いファイルの削除を行うか。デスクトップのアプリだけが行う
 * @param browserOpener 項目をブラウザで開く仕組み
 * @param ioDispatcher 設定のファイルと端末ごとの設定のファイルの読み書き、接続を作ること、ローカルキャッシュのディレクトリを消すこと、
 * 古いファイルの削除の記録の読み書きに使う
 * @param clock 古いファイルの削除を前に終えてからの経過を測る、端末の時計
 */
class CloudMarksController(
    appFiles: AppFiles,
    protector: SecretProtector,
    private val connect: (ConnectionSettings) -> Connection,
    private val log: (String) -> Unit,
    private val scope: CoroutineScope,
    private val storageChanges: Flow<Unit>,
    deletesStaleFiles: Boolean,
    private val browserOpener: BrowserOpener,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    clock: Clock = Clock.System,
) : AutoCloseable {
    private val settingsFile = SettingsFile(appFiles.settingsFile, protector)
    private val deviceSettingsFile = DeviceSettingsFile(appFiles.deviceSettingsFile, log)
    private val cacheRoot: Path = appFiles.cacheDirectory

    /** 端末ごとの設定のファイルへの書き込みを、1 つずつ順に行う。 */
    private val deviceSettingsWriting = Mutex()

    /** 古いファイルの削除。行わないなら null。 */
    private val staleFileDeletion: StaleFileDeletion? =
        if (deletesStaleFiles) StaleFileDeletion(appFiles.stateDirectory, clock, log, ioDispatcher) else null

    /** 起動してから、古いファイルの削除を走らせるかを確かめたか。初めてツリーを読めたときに確かめる。 */
    private var staleFilesChecked = false

    private val mutableState = MutableStateFlow(AppState(opensMultipleItems = browserOpener.opensMultipleItems))

    /** 画面全体の状態。 */
    val state: StateFlow<AppState> = mutableState.asStateFlow()

    /** 保存済みの設定。シークレットを含むので、画面の状態には出さない。読めていなければ null。 */
    private var storedSettings: ConnectionSettings? = null

    /** いまの S3 への接続。設定を読めていなければ null。 */
    private var active: ActiveConnection? = null

    /** [storageChanges] を集めて [reload] を呼ぶ処理。[start] で走り始め、[close] で取り消す。 */
    private val storageChangeWatching: Job = scope.launch(start = CoroutineStart.LAZY) { storageChanges.collect { reload() } }

    /**
     * S3 への接続と、その設定、それを使う処理を走らせるスコープ。スコープは [scope] の子で、1 つの処理の失敗がほかを止めない。
     * 接続を替えるときと閉じるときは、スコープを取り消してから接続を閉じる。
     */
    private class ActiveConnection(val connection: Connection, val settings: ConnectionSettings, val scope: CoroutineScope)

    /**
     * 端末ごとの設定と設定を読む。表示の大きさとタブの読み込み方は読めたものにし、読めなければ既定のままにする。
     * 設定を読めたら S3 への接続を作ってメインの画面を出し、マニフェストとルートフォルダの項目を読む。
     * 設定がまだ無いか読めなければ、設定の画面を出して理由を見せる。認証情報を復号できなかったときは、復号できなかった理由をログにだけ書く。
     * あわせて [storageChanges] を集め始め、PC に入っているブラウザを読む（[refreshBrowsers]）。
     */
    fun start() {
        storageChangeWatching.start()
        refreshBrowsers()
        scope.launch {
            val device = withContext(ioDispatcher) { deviceSettingsFile.read() }
            update { it.copy(displayScale = device.displayScale, tabLoading = device.tabLoading) }
            val read = withContext(ioDispatcher) { settingsFile.read() }
            if (read !is SettingsReadResult.Found) {
                if (read is SettingsReadResult.Undecryptable) log("保存されている認証情報を復号できなかった: ${read.detail}")
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

    /** 開いているフォルダの項目 [itemId] を、[mode] の選び方で選ぶ。 */
    fun selectItem(itemId: String, mode: SelectionMode) {
        updateBrowse { it.selectItem(itemId, mode) }
    }

    /** 選んでいる項目を外す。 */
    fun clearSelection() {
        updateBrowse { it.clearSelection() }
    }

    /**
     * 選んでいる項目を消す。選んでいる項目が無ければ何もしない。
     * 書けたら、消した項目を元の位置へ戻せるようにする（[undoDelete]）。
     */
    fun deleteSelectedItems() {
        val browse = state.value.browse
        val folderId = browse.currentFolderId ?: return
        val deletedIds = browse.selectedItems.map { it.id }.toSet()
        if (deletedIds.isEmpty()) return
        writeOperations(
            listOf(DeleteItems(deletedIds.map { ItemRef(folderId, it) })),
            undoOnSuccess = UndoableDelete(restoringInsertions(folderId, browse.currentLoadedItems, deletedIds)),
        )
    }

    /** 選んでいる項目を、フォルダ [destinationId] の項目の末尾へ、表示の並び順に移す。選んでいる項目が無ければ何もしない。 */
    fun moveSelectedItems(destinationId: String) {
        val subject = state.value.browse.selectedItemsSubject ?: return
        moveItems(subject.folderId, subject.itemIds, destinationId)
    }

    /**
     * フォルダ [folderId] の項目 [itemIds] を、フォルダ [destinationId] の項目の [beforeItemId] の前（null なら末尾）へ、[itemIds] の順に移す。
     * 同じフォルダの中なら並べ替えになる。[itemIds] が空なら何もしない。
     */
    fun moveItems(folderId: String, itemIds: List<String>, destinationId: String, beforeItemId: String? = null) {
        if (itemIds.isEmpty()) return
        writeOperations(listOf(MoveItems(itemIds.map { ItemRef(folderId, it) }, destinationId, before = beforeItemId)))
    }

    /**
     * [subject] を [destination] へ移す。項目なら [moveItems]、フォルダなら [moveFolder] で、[MoveDestination.At] なら指定した位置へ、
     * [MoveDestination.Into] なら移動先の末尾へ移す。読んであるツリーで移せなければ（[canMove]）、何もしない。
     */
    fun moveTo(subject: MoveSubject, destination: MoveDestination) {
        if (!state.value.browse.canMove(subject, destination)) return
        val beforeId = (destination as? MoveDestination.At)?.beforeId
        when (subject) {
            is MoveSubject.Items -> moveItems(subject.folderId, subject.itemIds, destination.folderId, beforeId)
            is MoveSubject.Folder -> moveFolder(subject.folderId, destination.folderId, beforeId)
        }
    }

    /** 開いているフォルダの項目 [itemId] のタイトルを [title] に変える。タイトルは空でもよい。 */
    fun renameItem(itemId: String, title: String) {
        val folderId = state.value.browse.currentFolderId ?: return
        writeOperations(listOf(ChangeItemTitle(ItemRef(folderId, itemId), title)))
    }

    /** フォルダ [parentId] の子のフォルダの末尾に、名前が [name] のフォルダを作る。名前が空なら適用されない（[WriteOutcome.NotApplied]）。 */
    fun createFolder(parentId: String, name: String) {
        writeOperations(listOf(CreateFolder(folderId = newId(), parentId = parentId, name = name, before = null)))
    }

    /** フォルダ [folderId] の名前を [name] に変える。名前が空なら適用されない（[WriteOutcome.NotApplied]）。 */
    fun renameFolder(folderId: String, name: String) {
        writeOperations(listOf(RenameFolder(folderId, name)))
    }

    /**
     * フォルダ [folderId] を子孫ごと、フォルダ [newParentId] の子のフォルダの [beforeFolderId] の前（null なら末尾）へ移す。
     * 同じ親の中なら並べ替えになる。
     */
    fun moveFolder(folderId: String, newParentId: String, beforeFolderId: String? = null) {
        writeOperations(listOf(MoveFolder(folderId, newParentId, before = beforeFolderId)))
    }

    /** フォルダ [folderId] を、子孫のフォルダとそれらの項目ごと消す。元に戻せない。 */
    fun deleteFolder(folderId: String) {
        writeOperations(listOf(DeleteFolder(folderId)))
    }

    /**
     * 直前の項目の削除を、消した項目を元の位置へ挿入して戻す。書き始めたら true を返す。
     * 元に戻せる削除が無いか、操作を受け付けないとき（[AppState.canWrite]）は何もせず、false を返す。元に戻せる削除は残る。
     * 書けなかったか確かめられなかったときは、同じ削除をもう一度戻せるようにする。挿入は ID が同じなら適用済みになるので、二重にはならない。
     */
    fun undoDelete(): Boolean {
        val undoable = state.value.write.undoableDelete ?: return false
        return writeOperations(undoable.restore, undoOnFailure = undoable)
    }

    /** 元に戻すお知らせを閉じる。直前の項目の削除は、もう戻せなくなる。 */
    fun dismissUndo() {
        updateWrite { it.copy(undoableDelete = null) }
    }

    /** 直前の操作の結果のお知らせを閉じる。 */
    fun dismissWriteOutcome() {
        updateWrite { it.copy(outcome = null) }
    }

    /** マニフェストを読んだときの失敗の帯を閉じる（[BrowseState.dismissFailure]）。 */
    fun dismissReadFailure() {
        updateBrowse { it.dismissFailure() }
    }

    /**
     * マニフェストを読み直す。開いているフォルダが残っていれば、その項目も一緒に読む。
     * メインの画面でないとき、マニフェストを読んでいる最中、操作を書いている最中、接続が無いときは何もしない。
     */
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

    /**
     * 画面全体の表示の大きさを [scale] にする。すぐに状態へ入れ、端末ごとの設定のファイルに書く（保存の操作を要しない）。
     * いまと同じなら何もしない。書けなければログに書き、利用者には見せない。
     * 続けて替えても、書き込みは 1 つずつ順に行い、それぞれ書くときの端末ごとの設定を書くので、最後に替えた値がファイルに残る。
     */
    fun setDisplayScale(scale: DisplayScale) {
        if (state.value.displayScale == scale) return
        update { it.copy(displayScale = scale) }
        writeDeviceSettings()
    }

    /**
     * 2 つ以上の項目をまとめてブラウザで開くときの、タブの読み込み方を [tabLoading] にする。[setDisplayScale] と同じく、
     * すぐに状態へ入れ、端末ごとの設定のファイルに書く（保存の操作を要しない）。いまと同じなら何もしない。
     */
    fun setTabLoading(tabLoading: TabLoading) {
        if (state.value.tabLoading == tabLoading) return
        update { it.copy(tabLoading = tabLoading) }
        writeDeviceSettings()
    }

    /**
     * PC に入っているブラウザを読み直し、状態に入れる。起動したときと、項目とフォルダのメニューを開くたびに呼ぶ。
     * 拡張機能を入れてブラウザを起動し直したあと、アプリを起動し直さなくても、そのブラウザでまとめて開けるようにする。
     */
    fun refreshBrowsers() {
        scope.launch {
            val browsers = browserOpener.browsers()
            update { it.copy(browsers = browsers) }
        }
    }

    /**
     * 開いているフォルダの項目 [itemId] を、既定のブラウザで開く。開いているフォルダの読めた項目に無ければ何もしない。
     * 選んでいる項目は変えない。
     *
     * PC に入っているブラウザ（[AppState.browsers]）に既定のブラウザがあれば、メニューでそのブラウザを選んだときと同じく
     * [openInBrowser] で開く。無ければ、Windows に既定のブラウザで開かせる（[BrowserOpener.openInDefaultBrowser]）。
     * 起動できなければ知らせる。
     */
    fun openItem(itemId: String) {
        val item = state.value.browse.currentLoadedItems.firstOrNull { it.id == itemId } ?: return
        state.value.browsers.firstOrNull { it.isDefault }?.let { browser ->
            openInBrowser(browser, listOf(item))
            return
        }
        updateBrowserNotice(null)
        scope.launch {
            if (browserOpener.openInDefaultBrowser(item.url) is BrowserOpenResult.LaunchFailed) {
                updateBrowserNotice(BrowserNotice.DefaultBrowserLaunchFailed)
            }
        }
    }

    /** 選んでいる項目を、表示の並び順に [browser] で開く（[openInBrowser]）。 */
    fun openSelectedItems(browser: Browser) {
        openInBrowser(browser, state.value.browse.selectedItems)
    }

    /**
     * フォルダ [folderId] の直下の項目（子孫のフォルダの項目は含めない）を、表示の並び順に [browser] で開く（[openInBrowser]）。
     *
     * 項目を読んであればそれを使い、読んでいなければ、読んであるツリーからそのフォルダの項目を読む（マニフェストは GET しない）。
     * 項目ファイルが `404` を返したら、開いているフォルダと同じく、マニフェストを一度だけ読み直してから読む。
     * 読めなかったか、項目ファイルが壊れていれば知らせる。マニフェストで項目が無いフォルダは、何もしない。
     */
    fun openFolderItems(folderId: String, browser: Browser) {
        val browse = state.value.browse
        val tree = browse.tree ?: return
        if ((tree.state.folders[folderId]?.count ?: 0) == 0) return
        (browse.folderReads[folderId] as? FolderReadResult.Loaded)?.let { loaded ->
            openInBrowser(browser, loaded.items)
            return
        }
        val connection = active ?: return
        connection.scope.launch {
            when (val read = connection.connection.sync.readFolder(tree, folderId)) {
                is FolderReadResult.Loaded -> openInBrowser(browser, read.items)
                is FolderReadResult.Broken -> updateBrowserNotice(BrowserNotice.FolderBroken(read.problem))
                is FolderReadResult.Failed -> updateBrowserNotice(BrowserNotice.FolderReadFailed(read.failure))
                FolderReadResult.Missing -> readTree(
                    connection,
                    read = { foldersToLoad ->
                        connection.connection.sync.readAfterMissing(tree, folderId) { reread -> foldersToLoad(reread) + folderId }
                    },
                    show = { result ->
                        showTree(result)
                        openFolderItemsOf(result, folderId, browser)
                    },
                )
            }
        }
    }

    /** ブラウザで開こうとして知らせたことを閉じる。 */
    fun dismissBrowserNotice() {
        updateBrowserNotice(null)
    }

    /** 設定の画面の入力を [form] に替える。保存の最中は受け付けない。 */
    fun editSettings(form: SettingsForm) {
        updateSettings { if (it.status.busy) it else it.copy(form = form, status = SettingsStatus.Editing) }
    }

    /**
     * 入力した設定を保存し、続けてマニフェストを読む。
     *
     * 入力が足りなければ保存しない。シークレットの欄が空なら、保存済みのものを使う。暗号化の仕組みが使えなければ、
     * 保存を断ってそのことを見せ、使えない理由はログにだけ書く。保存できたら、読んであったツリーと、操作の書き込みの結果と元に戻せる削除を捨ててから
     * S3 への接続を作り直し（前の S3 のクライアントは閉じ、書いている最中の操作は取り消す）、
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

    /** [storageChanges] を集める処理と走っている読み書きを取り消し、S3 のクライアントを閉じる。 */
    override fun close() {
        storageChangeWatching.cancel()
        active?.let { previous ->
            previous.scope.cancel()
            previous.connection.close()
        }
        active = null
    }

    private suspend fun save(settings: ConnectionSettings) {
        when (val written = withContext(ioDispatcher) { settingsFile.write(settings) }) {
            is SettingsWriteResult.ProtectionUnavailable -> {
                log("認証情報を暗号化できなかったため、設定を保存しなかった: ${written.detail}")
                updateSettings { it.copy(status = SettingsStatus.ProtectionUnavailable) }
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
                write = WriteState(),
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
     * 読み書きできるツリーを読めたら、古いファイルの削除を走らせるかを確かめる（[deleteStaleFilesOnce]）。
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
            val result = read { tree -> setOf(state.value.browse.folderToOpenIn(tree)) }
            show(result)
            if (result is TreeReadResult.Readable) deleteStaleFilesOnce(connection)
        }
    }

    /**
     * 起動してから初めて呼んだときだけ、前に古いファイルの削除を終えてから間隔を過ぎていれば、[connection] で裏で走らせる。
     * 古いファイルの削除を行わないなら何もしない。
     */
    private fun deleteStaleFilesOnce(connection: ActiveConnection) {
        val deletion = staleFileDeletion ?: return
        if (staleFilesChecked) return
        staleFilesChecked = true
        connection.scope.launch { deletion.runIfDue(connection.settings, connection.connection.sync) }
    }

    /**
     * [operations] を 1 回の書き込みとして書き始め、書き始めたら true を返す。操作を受け付けないとき（[AppState.canWrite]）は何もせず、
     * false を返す。
     *
     * 書き始めるときに、前の操作の結果と、元に戻せる削除を消す。書けたら、書いたあとのツリーに替え（マニフェストは GET し直さない）、
     * 開いているフォルダの項目を、読んであるものを引き継いだうえで足りなければ読み、[undoOnSuccess] を元に戻せる削除にする。
     * 書けなかったか確かめられなかったときは、ツリーは書く前のままにし、[undoOnFailure] を元に戻せる削除にする。
     *
     * @param undoOnSuccess 書けたときに元に戻せるようにする削除。無ければ null
     * @param undoOnFailure 書けなかったか確かめられなかったときに元に戻せるようにする削除。無ければ null
     */
    private fun writeOperations(
        operations: List<Operation>,
        undoOnSuccess: UndoableDelete? = null,
        undoOnFailure: UndoableDelete? = null,
    ): Boolean {
        val connection = active ?: return false
        if (!state.value.canWrite) return false
        updateWrite { WriteState(writing = true) }
        connection.scope.launch {
            showWriteResult(connection.connection.sync.writeConfirming(operations), undoOnSuccess, undoOnFailure)
        }
        return true
    }

    /** 操作を書いた結果 [result] を状態に入れる。書けたら、書いたあとのツリーに替え、開いているフォルダの項目を読む。 */
    private fun showWriteResult(result: WriteResult, undoOnSuccess: UndoableDelete?, undoOnFailure: UndoableDelete?) {
        when (result) {
            is WriteResult.Success -> {
                val notices = result.results.filter { it.hasNoticeForUser }
                update {
                    it.copy(
                        browse = it.browse.withWrittenTree(result.tree),
                        write = WriteState(
                            outcome = if (notices.isEmpty()) WriteOutcome.Written else WriteOutcome.NotApplied(notices),
                            undoableDelete = undoOnSuccess,
                        ),
                    )
                }
                loadCurrentFolder()
            }
            is WriteResult.Unconfirmed ->
                updateWrite { WriteState(outcome = WriteOutcome.Unconfirmed(result.failure), undoableDelete = undoOnFailure) }
            is WriteResult.Failed ->
                updateWrite { WriteState(outcome = WriteOutcome.Failed(result.failure), undoableDelete = undoOnFailure) }
        }
    }

    /** マニフェストを読んだ結果 [result] をツリーに入れ、開いているフォルダの項目を読む。読めたら、直前の操作の結果を消す。 */
    private fun showTree(result: TreeReadResult) {
        update { current ->
            val read = current.copy(browse = current.browse.withTreeRead(result))
            if (result is TreeReadResult.Failed) read else read.copy(write = read.write.copy(outcome = null))
        }
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
        return ActiveConnection(connection, settings, connectionScope).also { active = it }
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

    /** いまの端末ごとの設定を、端末ごとの設定のファイルに書く。書き込みは 1 つずつ順に行う。 */
    private fun writeDeviceSettings() {
        scope.launch {
            withContext(ioDispatcher) {
                deviceSettingsWriting.withLock { deviceSettingsFile.write(state.value.deviceSettings) }
            }
        }
    }

    /**
     * [items] を、その並びで [browser] で開く。項目が無ければ何もしない。2 つ以上で、[browser] がまとめて開けなければ何もしない
     * （メニューでは押せない）。開き始めるときに、前に知らせたことを消す。
     * 起動できなかったか、まとめて開くコマンドが拡張機能に届かなかったかもしれなければ知らせる。
     */
    private fun openInBrowser(browser: Browser, items: List<Item>) {
        if (items.isEmpty()) return
        if (items.size > 1 && !browser.opensMultipleItems) return
        updateBrowserNotice(null)
        val tabs = items.map { BrowserTab(url = it.url, title = it.title) }
        val tabLoading = state.value.tabLoading
        scope.launch {
            when (val result = browserOpener.openInBrowser(browser, tabs, tabLoading)) {
                BrowserOpenResult.Opened -> Unit
                is BrowserOpenResult.LaunchFailed -> updateBrowserNotice(BrowserNotice.LaunchFailed(browser.name, result.detail))
                BrowserOpenResult.CommandUndelivered -> updateBrowserNotice(BrowserNotice.CommandUndelivered(browser.name))
            }
        }
    }

    /**
     * 項目ファイルが `404` を返したあとにマニフェストを読み直した結果 [result] から、フォルダ [folderId] の項目を [browser] で開く。
     * 読めなかったか、そのフォルダが壊れていれば知らせる。読み直したマニフェストにそのフォルダが無ければ、何もしない。
     */
    private fun openFolderItemsOf(result: TreeReadResult, folderId: String, browser: Browser) {
        val tree = when (result) {
            is TreeReadResult.Readable -> result.tree
            is TreeReadResult.ReadOnly -> result.tree
            is TreeReadResult.Failed -> {
                updateBrowserNotice(BrowserNotice.FolderReadFailed(result.failure))
                return
            }
        }
        tree.brokenFolders[folderId]?.let { problem ->
            updateBrowserNotice(BrowserNotice.FolderBroken(problem))
            return
        }
        tree.state.items[folderId]?.let { items -> openInBrowser(browser, items) }
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

    private fun updateWrite(transform: (WriteState) -> WriteState) {
        update { it.copy(write = transform(it.write)) }
    }

    private fun updateBrowserNotice(notice: BrowserNotice?) {
        update { it.copy(browserNotice = notice) }
    }
}

/**
 * [CloudMarksController] を組み立てる。Android とデスクトップのエントリポイントが、プラットフォームごとに違うもの（ファイルの場所、
 * 認証情報を暗号化する仕組み、時計、マニフェストの `updatedBy`、ほかのプロセスが S3 へ書いた知らせ、古いファイルの削除を行うか、
 * 項目をブラウザで開く仕組み）を渡して呼ぶ。ログは [AppFiles.logDirectory] のファイルに書く。
 *
 * 呼んだ側が、作ったコントローラを [CloudMarksController.start] で動かし始め、使い終えたら [CloudMarksController.close] で閉じる。
 *
 * @param appFiles 設定のファイル・ローカルキャッシュ・ログ・次の起動へ持ち越す記録・端末ごとの設定のファイルの場所
 * @param protector 認証情報を暗号化する仕組み
 * @param timeSource 書き込みの手順 3 から手順 4 までの経過を測る、モノトニッククロック
 * @param updatedBy 書き込むマニフェストのヘッダーの `updatedBy`。`"desktop"` か `"android"`
 * @param storageChanges ほかのプロセスが S3 へ書いた知らせの Flow を作る関数。このコントローラのログに書く関数を受け取る。
 * デスクトップのアプリはファイルの変化を見張る Flow を作り、Android は知らせを出さない Flow を作る
 * @param deletesStaleFiles 古いファイルの削除を行うか。デスクトップのアプリは行い、Android は行わない
 * @param browserOpener 項目をブラウザで開く仕組みを作る関数。このコントローラのログに書く関数を受け取る
 * @param scope メインのスレッドで動くスコープ。取り消しは呼んだ側が行う
 */
fun createCloudMarksController(
    appFiles: AppFiles,
    protector: SecretProtector,
    timeSource: TimeSource,
    updatedBy: String,
    storageChanges: (log: (String) -> Unit) -> Flow<Unit>,
    deletesStaleFiles: Boolean,
    browserOpener: (log: (String) -> Unit) -> BrowserOpener,
    scope: CoroutineScope,
): CloudMarksController {
    val log = FileLog(appFiles.logDirectory)
    return CloudMarksController(
        appFiles = appFiles,
        protector = protector,
        connect = { settings -> openS3Connection(settings, appFiles.cacheDirectory, updatedBy, timeSource, log::write) },
        log = log::write,
        scope = scope,
        storageChanges = storageChanges(log::write),
        deletesStaleFiles = deletesStaleFiles,
        browserOpener = browserOpener(log::write),
    )
}
