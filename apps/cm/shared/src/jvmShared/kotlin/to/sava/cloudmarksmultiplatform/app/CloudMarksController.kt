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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
import to.sava.cloudmarksmultiplatform.browser.ItemShareResult
import to.sava.cloudmarksmultiplatform.browser.ItemSharer
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
import to.sava.cloudmarksmultiplatform.sync.LoadedRevision
import to.sava.cloudmarksmultiplatform.sync.LoadedTree
import to.sava.cloudmarksmultiplatform.sync.RestoreResult
import to.sava.cloudmarksmultiplatform.sync.Revision
import to.sava.cloudmarksmultiplatform.sync.RevisionFailure
import to.sava.cloudmarksmultiplatform.sync.RevisionListResult
import to.sava.cloudmarksmultiplatform.sync.RevisionReadResult
import to.sava.cloudmarksmultiplatform.sync.SyncFailure
import to.sava.cloudmarksmultiplatform.sync.TreeReadResult
import to.sava.cloudmarksmultiplatform.sync.WriteResult
import to.sava.cloudmarksmultiplatform.sync.connectionCacheDirectory
import to.sava.cloudmarksmultiplatform.sync.connectionName
import to.sava.cloudmarksmultiplatform.sync.writeConfirming
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.time.Clock
import kotlin.time.TimeSource

/** マニフェストを読む関数。読んだマニフェストのツリーから、項目を一緒に読むフォルダを決める関数を受け取る。 */
private typealias TreeRead = suspend (foldersToLoad: (TreeState) -> Set<String>) -> TreeReadResult

/** 読んであるツリーからフォルダの項目を読むとき（開いているフォルダと、検索のために読むフォルダ）に、同時に読むフォルダの数の上限。 */
private const val MAX_CONCURRENT_FOLDER_READS = 4

/**
 * 画面の状態を持ち、利用者の操作に応じて、設定の読み書きと S3 の読み書きを進める。
 *
 * 状態は [state] で出す。[scope] にはメインのスレッドで動くものを渡し、操作の関数はメインのスレッドから呼ぶ。
 * [start] は [scope] で走らせるだけなので、画面を出す前に別のスレッドから呼んでもよい。
 * 使い終えたら [close] を呼ぶ。[scope] の取り消しは渡した側が行う。
 *
 * S3 からマニフェストを読み直すのは、[start] で設定を読めたとき、[reload] を呼んだとき、[storageChanges] で知らせを受けたとき、
 * 設定を保存したとき、マニフェストを作ろうとして既にあったとき、開いたフォルダとブラウザで開くフォルダと検索のために読むフォルダの
 * 項目ファイルが `404` を返したときだけ。
 * フォルダを開いたときはマニフェストを GET せず、読んであるツリーからそのフォルダの項目ファイルだけを読む。
 * 検索を開いているあいだは、項目を読んでいないフォルダの項目ファイルを、読んであるツリーからすべて読む（[loadFolders]）。
 *
 * データを変える操作（`docs/operations.md`）は、一度に 1 つだけ書く。書いているあいだは、ほかの操作も読み直しも受け付けない。
 * 書けたら書いたあとのツリーに替え、マニフェストは GET し直さない。
 *
 * 項目の削除（選んでいる項目の削除、開いているフォルダの項目を指定した削除と、フォルダと項目の組を指定した削除）が S3 につながらないために
 * 書けなかったら（[isUnreachable]）、その削除を端末に溜め、接続先ごとの
 * 溜めた削除の記録のファイル（[pendingDeletesFile]）に書く。ほかの失敗では溜めず、ほかの操作も溜めない。溜めた削除の項目は、
 * リストとフォルダの件数から消して見せる（[BrowseState.pendingDeletes]）。
 * 溜めた削除は、起動したときと設定を保存したとき、読み直したとき（[reload] と [storageChanges]）にマニフェストを読み書きできるツリーとして
 * 読めたあとと、ほかの操作を書く前に、すべてまとめて 1 回の書き込みとして書く。ほかの操作を書く前に書けなければ、その操作は書かない。
 * 書けたら溜めた削除を空にし、記録のファイルを消す。S3 につながらなければ溜めたままにし、失敗としては知らせない。
 * ほかの理由で書けなければ溜めたままにし、操作を書けなかったときと同じく結果に出す。
 *
 * 以前の状態に戻すこと（`docs/storage-format.md` の「以前の状態に戻す」）は、設定の画面から開く画面（[Screen.REVISIONS]）で行う。
 * 戻せる時点の一覧と、選んだ時点のマニフェストは、その画面を開いたときと時点を選んだときに読み、その画面を離れたら読み込みを取り消して、
 * 結果を入れない。戻すことはデータを変える操作と同じく扱い、一度に 1 つだけ書く。戻しているあいだは、ほかの操作も読み直しも、
 * その画面を離れることも受け付けない。戻す前には溜めた削除を書き、書けなければ戻さない。戻せたらメインの画面へ移り、
 * 戻したあとのツリーに替える（マニフェストは GET し直さない）。
 *
 * [deletesStaleFiles] なら、起動して初めてツリーを読めたときに、前に古いファイルの削除を終えてから間隔を過ぎていれば、
 * 裏で古いファイルの削除を走らせる。結果はログにだけ書く。
 *
 * 項目をブラウザで開くこと、ほかのアプリで開くこと、共有することは、データを変えないので、書いている最中も、読むだけのツリーでも受け付ける。
 * 項目を 1 つだけ開けたら（既定のブラウザ・選んだブラウザ・ほかのアプリで）、その項目を最後に開いた項目（[AppState.lastOpenedItemId]）として
 * 覚え、最後に開いた項目の記録のファイルに書く。開けなかったとき、2 つ以上をまとめて開いたとき、共有したときは替えない。
 *
 * @param connect 設定から S3 への接続を作る。[ioDispatcher] で呼ぶ
 * @param log 利用者への誤りとしては見せない失敗と、古いファイルの削除の結果を書く。認証情報は渡さない
 * @param storageChanges S3 から読み直すきっかけの知らせ（ほかのプロセスが S3 へ書いたこと、Android では画面が前面に戻ったことと、
 * ネットワークにつながったこと）。[start] から集め、知らせを受けるたびに [reload] と同じく読み直し、溜めた削除を書く。
 * ただし、直前の操作の結果は消さない。
 * [reload] が何もしないとき（メインの画面でないとき・読んでいる最中・書いている最中・接続が無いとき）の知らせは捨てる。
 * 知らせを出さない Flow を渡してもよい
 * @param deletesStaleFiles 古いファイルの削除を行うか。デスクトップのアプリだけが行う
 * @param browserOpener 項目をブラウザで開く仕組み
 * @param itemSharer 項目をほかのアプリで開く仕組みと、共有する仕組み。持たないプラットフォームでは null
 * @param ioDispatcher 設定のファイルと端末ごとの設定のファイルの読み書き、接続を作ること、ローカルキャッシュのディレクトリを消すこと、
 * 古いファイルの削除の記録と最後に開いた項目の記録と溜めた削除の記録の読み書きに使う
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
    private val itemSharer: ItemSharer?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    clock: Clock = Clock.System,
) : AutoCloseable {
    private val settingsFile = SettingsFile(appFiles.settingsFile, protector)
    private val deviceSettingsFile = DeviceSettingsFile(appFiles.deviceSettingsFile, log)
    private val lastOpenedItemFile = LastOpenedItemFile(appFiles.lastOpenedItemFile, log)
    private val cacheRoot: Path = appFiles.cacheDirectory
    private val stateDirectory: Path = appFiles.stateDirectory

    /** 端末ごとの設定のファイルへの書き込みを、1 つずつ順に行う。 */
    private val deviceSettingsWriting = Mutex()

    /** 溜めた削除の記録のファイルの読み書きと削除を、始めた順に 1 つずつ行う。 */
    private val pendingDeletesWriting = Mutex()

    /** 最後に開いた項目の記録のファイルへの書き込みを、1 つずつ順に行う。 */
    private val lastOpenedItemWriting = Mutex()

    /** 読んであるツリーからフォルダの項目を読むことを、同時に [MAX_CONCURRENT_FOLDER_READS] 本までにする（[loadFolders]）。 */
    private val folderReading = Semaphore(MAX_CONCURRENT_FOLDER_READS)

    /** 古いファイルの削除。行わないなら null。 */
    private val staleFileDeletion: StaleFileDeletion? =
        if (deletesStaleFiles) StaleFileDeletion(appFiles.stateDirectory, clock, log, ioDispatcher) else null

    /** 起動してから、古いファイルの削除を走らせるかを確かめたか。初めてツリーを読めたときに確かめる。 */
    private var staleFilesChecked = false

    /** 以前の状態に戻す画面のための、戻せる時点の一覧か選んだ時点のマニフェストの読み込み。まだ走らせていなければ null。 */
    private var revisionReading: Job? = null

    private val mutableState = MutableStateFlow(
        AppState(opensMultipleItems = browserOpener.opensMultipleItems, sharesItems = itemSharer != null),
    )

    /** 画面全体の状態。 */
    val state: StateFlow<AppState> = mutableState.asStateFlow()

    /** 保存済みの設定。シークレットを含むので、画面の状態には出さない。読めていなければ null。 */
    private var storedSettings: ConnectionSettings? = null

    /** いまの S3 への接続。設定を読めていなければ null。 */
    private var active: ActiveConnection? = null

    /**
     * [storageChanges] を集めて、知らせのたびに読み直す処理（[reread]。直前の操作の結果は消さない）。[start] で走り始め、[close] で取り消す。
     */
    private val storageChangeWatching: Job =
        scope.launch(start = CoroutineStart.LAZY) { storageChanges.collect { reread(clearsWriteOutcome = false) } }

    /**
     * S3 への接続と、その設定、それを使う処理を走らせるスコープ。スコープは [scope] の子で、1 つの処理の失敗がほかを止めない。
     * 接続を替えるときと閉じるときは、スコープを取り消してから接続を閉じる。
     */
    private class ActiveConnection(val connection: Connection, val settings: ConnectionSettings, val scope: CoroutineScope)

    /**
     * 端末ごとの設定と、最後に開いた項目の記録と、設定を読む。表示の大きさとタブの読み込み方は読めたものにし、読めなければ既定のままにする。
     * 最後に開いた項目は、記録を読めなければ覚えていないものとする。
     * 設定を読めたら S3 への接続を作り、その接続先の溜めた削除の記録を読んでメインの画面を出し、マニフェストとルートフォルダの項目を読む。
     * 読み書きできるツリーとして読めたら、溜めた削除を書く（[flushPendingDeletes]）。
     * 設定がまだ無いか読めなければ、設定の画面を出して理由を見せる。認証情報を復号できなかったときは、復号できなかった理由をログにだけ書く。
     * あわせて [storageChanges] を集め始め、この端末に入っているブラウザを読む（[refreshBrowsers]）。
     */
    fun start() {
        storageChangeWatching.start()
        refreshBrowsers()
        scope.launch {
            val (device, lastOpenedItemId) = withContext(ioDispatcher) { deviceSettingsFile.read() to lastOpenedItemFile.read() }
            update {
                it.copy(displayScale = device.displayScale, tabLoading = device.tabLoading, lastOpenedItemId = lastOpenedItemId)
            }
            val read = withContext(ioDispatcher) { settingsFile.read() }
            if (read !is SettingsReadResult.Found) {
                if (read is SettingsReadResult.Undecryptable) log("保存されている認証情報を復号できなかった: ${read.detail}")
                update { it.copy(screen = Screen.SETTINGS, settings = SettingsScreenState(readProblem = read)) }
                return@launch
            }
            storedSettings = read.settings
            val connection = replaceConnection(read.settings)
            val pending = readPendingDeletes(read.settings)
            update {
                it.copy(
                    screen = Screen.MAIN,
                    browse = it.browse.withPendingDeletes(pending),
                    settings = storedSettingsScreen(read.settings),
                )
            }
            readTree(connection, appliesPendingDeletes = true)
        }
    }

    /**
     * 戻る。設定の画面ならメインの画面へ戻り、入力した値を捨てて保存済みの設定から作り直す。
     * 以前の状態に戻す画面なら設定の画面へ戻り（入力した値は残す）、戻せる時点の一覧か選んだ時点のマニフェストを読んでいれば取り消す。
     * 戻している最中は何もしない（[AppState.canGoBack]）。
     * メインの画面ならドロワーを閉じる。ドロワーが閉じていれば検索を閉じる。検索も閉じていれば、開いたフォルダの履歴を戻る。
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
        if (state.value.screen != Screen.REVISIONS) cancelRevisionReading()
        loadFolders()
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

    /** フォルダを開き、まだ読んでいなければ、そのフォルダの項目を読む。検索を開いていれば閉じる（[BrowseState.openFolder]）。 */
    fun openFolder(folderId: String) {
        updateBrowse { it.openFolder(folderId) }
        loadFolders()
    }

    /**
     * 項目 [ref] が入っているフォルダを開き、その項目だけを選ぶ（[BrowseState.openFolderOf]）。検索を閉じる。
     * そのフォルダの項目をまだ読んでいなければ読む。
     */
    fun openFolderOf(ref: ItemRef) {
        updateBrowse { it.openFolderOf(ref) }
        loadFolders()
    }

    /**
     * 検索を開き（[BrowseState.openSearch]）、項目を読んでいないフォルダの項目をすべて読む（[loadFolders]）。
     * 前に読めなかった（失敗した）フォルダも読み直す。
     */
    fun openSearch() {
        updateBrowse { it.openSearch() }
        loadFolders()
    }

    /** 検索の入力した語を [query] にする。検索を閉じていれば何もしない。 */
    fun setSearchQuery(query: String) {
        updateBrowse { it.withSearchQuery(query) }
    }

    /**
     * 検索を閉じる。開いているフォルダの項目をまだ読んでいなければ読む。
     * 検索のために読むフォルダのうち、順番を待っていて、まだ読み始めていないものは読まない（[loadFolder]）。
     * 読み始めていた（GET を送った）読み込みは取り消さず、読めた結果は使う。
     */
    fun closeSearch() {
        updateBrowse { it.closeSearch() }
        loadFolders()
    }

    /** 開いているフォルダの項目 [itemId] を、[mode] の選び方で選ぶ。 */
    fun selectItem(itemId: String, mode: SelectionMode) {
        updateBrowse { it.selectItem(itemId, mode) }
    }

    /** 選んでいる項目を外す。 */
    fun clearSelection() {
        updateBrowse { it.clearSelection() }
    }

    /** 選んでいる項目を消す（[deleteItems]）。選んでいる項目が無ければ何もしない。 */
    fun deleteSelectedItems() {
        deleteItems(state.value.browse.selectedItemIds)
    }

    /**
     * 開いているフォルダの読めた項目のうち、ID が [itemIds] にあるものを消す（[deleteLoadedItems]）。消すものが無ければ何もしない。
     * 選んでいる項目は変えない。書けたら元に戻せる削除にし、S3 につながらないために書けなければ、溜めた削除にする。
     */
    fun deleteItems(itemIds: Set<String>) {
        val folderId = state.value.browse.currentFolderId ?: return
        deleteLoadedItems(folderId, itemIds)
    }

    /**
     * 項目 [ref] を消す（[deleteLoadedItems]）。開いているフォルダでないフォルダの項目も消せる。そのフォルダの読めた項目
     * （[BrowseState.loadedItemsOf]。溜めた削除の項目は除く）に無ければ何もしない。選んでいる項目は変えない。
     * 書けたら元に戻せる削除にし、S3 につながらないために書けなければ、溜めた削除にする。
     */
    fun deleteItem(ref: ItemRef) {
        deleteLoadedItems(ref.folderId, setOf(ref.itemId))
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

    /** 開いているフォルダの項目 [itemId] のタイトルを [title] に変える。開いているフォルダと [itemId] の組で `renameItem(ref, title)` を呼ぶ。 */
    fun renameItem(itemId: String, title: String) {
        currentItemRef(itemId)?.let { renameItem(it, title) }
    }

    /**
     * 項目 [ref] のタイトルを [title] に変える。タイトルは空でもよい。開いているフォルダでないフォルダの項目も変えられる。
     * そのフォルダの読めた項目（[BrowseState.loadedItemOf]。溜めた削除の項目は除く）に無ければ何もしない。
     */
    fun renameItem(ref: ItemRef, title: String) {
        if (state.value.browse.loadedItemOf(ref) == null) return
        writeOperations(listOf(ChangeItemTitle(ref, title)))
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
     * 直前の項目の削除を戻す。戻し始めたら true を返す。
     * 元に戻せる削除が無いか、操作を受け付けないとき（[AppState.canWrite]）は何もせず、false を返す。元に戻せる削除は残る。
     *
     * S3 に書けた削除なら、消した項目を元の位置へ挿入して戻す。書けなかったか確かめられなかったときは、同じ削除をもう一度戻せるようにする。
     * 挿入は ID が同じなら適用済みになるので、二重にはならない。
     * 溜めた削除（[UndoableDelete.pending]）なら、その回に溜めた対象を溜めた削除から外し、溜めた削除の記録のファイルを書き直す。
     * S3 には書かない。外した項目は、リストとフォルダの件数にまた出る。
     */
    fun undoDelete(): Boolean {
        val undoable = state.value.write.undoableDelete ?: return false
        val pendingTargets = undoable.pendingTargets ?: return writeOperations(undoable.restore, undoOnFailure = undoable)
        val connection = active ?: return false
        if (!state.value.canWrite) return false
        update {
            it.copy(
                browse = it.browse.withPendingDeletes(it.browse.pendingDeletes - pendingTargets),
                write = it.write.copy(undoableDelete = null),
            )
        }
        storePendingDeletes(connection.settings)
        return true
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
     * マニフェストを読み直す。開いているフォルダが残っていれば、その項目も一緒に読む。読めたら、直前の操作の結果を消す。
     * 読み書きできるツリーとして読めたら、溜めた削除を書く（[flushPendingDeletes]）。
     * メインの画面でないとき、マニフェストを読んでいる最中、操作を書いている最中、接続が無いときは何もしない。
     */
    fun reload() {
        reread(clearsWriteOutcome = true)
    }

    /** 設定の画面を出す。入力は保存済みの設定に戻し、シークレットの欄は空にする。 */
    fun openSettings() {
        update {
            if (it.screen != Screen.MAIN) return@update it
            it.copy(screen = Screen.SETTINGS, browse = it.browse.closeDrawer(), settings = editingStoredSettings(it.settings))
        }
    }

    /**
     * 設定の画面から、以前の状態に戻す画面を開き、戻せる時点の一覧を読む。その画面の状態は初めの値から始める。
     * 設定の画面でないとき、以前の状態に戻すことを受け付けないとき（[AppState.canRestore]）、接続が無いときは何もしない。
     */
    fun openRevisions() {
        val connection = active ?: return
        val current = state.value
        if (current.screen != Screen.SETTINGS || !current.canRestore) return
        update { it.copy(screen = Screen.REVISIONS, revisions = RevisionsScreenState()) }
        startRevisionReading(connection) { showRevisionList(connection.connection.sync.listRevisions()) }
    }

    /**
     * 以前の状態に戻す画面で、戻せる時点 [revision] を選び、そのマニフェストを読む。読み始めるときに、前に伝えたことを消す。
     * 読めたら戻すかを確かめる状態にし、読めなければそのことを伝える。
     * 以前の状態に戻す画面で一覧を読めているときだけ受け付け、時点を選んでいるあいだ（読んでいる・確かめている・戻している）と、
     * 接続が無いときは何もしない。
     */
    fun selectRevision(revision: Revision) {
        val connection = active ?: return
        val current = state.value
        if (current.screen != Screen.REVISIONS || current.revisions.list !is RevisionListState.Listed) return
        if (current.revisions.selection != null) return
        updateRevisions { it.copy(selection = RevisionSelection.Loading(revision), notice = null) }
        startRevisionReading(connection) { showRevisionRead(revision, connection.connection.sync.readRevision(revision)) }
    }

    /** 戻すかを確かめるのをやめる。確かめているときだけ閉じ、選んだ時点を読んでいる最中と戻している最中は何もしない。 */
    fun dismissRevisionSelection() {
        updateRevisions { revisions ->
            if (revisions.selection is RevisionSelection.Confirming) revisions.copy(selection = null) else revisions
        }
    }

    /**
     * 確かめている時点へ戻す。以前の状態に戻す画面で確かめていて、以前の状態に戻すことを受け付けるとき（[AppState.canRestore]）だけ受け付ける。
     *
     * 戻し始めるときに、前の操作の結果と元に戻せる削除を消し、書いている最中の状態にする。溜めた削除があれば先に書き
     * （[writePendingDeletes]）、書けなければ戻さず、溜めた削除を書いた結果を戻した結果として扱う。
     * 戻せたら、メインの画面へ移り、戻したあとのツリーに替え（マニフェストは GET し直さない）、開いているフォルダと検索のために読む
     * フォルダの項目を、読んであるものを引き継いだうえで足りなければ読む（[loadFolders]）。戻せたことは結果（[WriteOutcome.Restored]）で伝える。
     * 戻せなければ、以前の状態に戻す画面に残ってそのことを伝える。戻せたかどうかを確かめられなければ、メインの画面へ移り、
     * 操作を確かめられなかったときと同じく結果に出す。ツリーは戻す前のまま。
     * メインの画面へ移るときは、以前の状態に戻す画面の状態を初めの値にし、設定の画面の入力を保存済みの設定から作り直す。
     */
    fun restoreSelectedRevision() {
        val connection = active ?: return
        val current = state.value
        val loaded = (current.revisions.selection as? RevisionSelection.Confirming)?.loaded ?: return
        if (current.screen != Screen.REVISIONS || !current.canRestore) return
        update {
            it.copy(revisions = it.revisions.copy(selection = RevisionSelection.Restoring(loaded)), write = WriteState(writing = true))
        }
        connection.scope.launch {
            val result = writePendingDeletes(connection)?.let(::restoreResultOfPendingDeletes)
                ?: connection.connection.sync.restoreRevision(loaded)
            showRestoreResult(loaded, result)
        }
    }

    /** 以前の状態に戻す画面で伝えたことを閉じる。 */
    fun dismissRevisionNotice() {
        updateRevisions { it.copy(notice = null) }
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
     * この端末に入っているブラウザを読み直し、状態に入れる。起動したときと、項目とフォルダのメニューを開くたびに呼ぶ。
     * 拡張機能を入れてブラウザを起動し直したあと、アプリを起動し直さなくても、そのブラウザでまとめて開けるようにする。
     */
    fun refreshBrowsers() {
        scope.launch {
            val browsers = browserOpener.browsers()
            update { it.copy(browsers = browsers) }
        }
    }

    /** 開いているフォルダの項目 [itemId] を、既定のブラウザで開く。開いているフォルダと [itemId] の組で `openItem(ref)` を呼ぶ。 */
    fun openItem(itemId: String) {
        currentItemRef(itemId)?.let { openItem(it) }
    }

    /**
     * 項目 [ref] を、既定のブラウザで開く。開いているフォルダでないフォルダの項目も開ける。そのフォルダの読めた項目
     * （[BrowseState.loadedItemOf]。溜めた削除の項目は除く）に無ければ何もしない。選んでいる項目は変えない。
     *
     * この端末に入っているブラウザ（[AppState.browsers]）に既定のブラウザがあれば、メニューでそのブラウザを選んだときと同じく
     * [openInBrowser] で開く。無ければ、ブラウザを調べずに既定のブラウザで開く（[BrowserOpener.openInDefaultBrowser]）。
     * 開けたら、その項目を最後に開いた項目として覚える（[rememberOpenedItem]）。起動できなければ知らせる。
     */
    fun openItem(ref: ItemRef) {
        val item = state.value.browse.loadedItemOf(ref) ?: return
        state.value.browsers.firstOrNull { it.isDefault }?.let { browser ->
            openInBrowser(browser, listOf(item))
            return
        }
        updateBrowserNotice(null)
        scope.launch {
            when (browserOpener.openInDefaultBrowser(item.url)) {
                BrowserOpenResult.Opened -> rememberOpenedItem(item.id)
                is BrowserOpenResult.LaunchFailed -> updateBrowserNotice(BrowserNotice.DefaultBrowserLaunchFailed)
                BrowserOpenResult.CommandUndelivered -> Unit
            }
        }
    }

    /** 選んでいる項目を、表示の並び順に [browser] で開く（[openInBrowser]）。 */
    fun openSelectedItems(browser: Browser) {
        openInBrowser(browser, state.value.browse.selectedItems)
    }

    /**
     * 項目 [ref] を [browser] で開く（[openInBrowser]）。開いているフォルダでないフォルダの項目も開ける。そのフォルダの読めた項目
     * （[BrowseState.loadedItemOf]。溜めた削除の項目は除く）に無ければ何もしない。選んでいる項目は変えない。
     */
    fun openItem(ref: ItemRef, browser: Browser) {
        val item = state.value.browse.loadedItemOf(ref) ?: return
        openInBrowser(browser, listOf(item))
    }

    /**
     * フォルダ [folderId] の直下の項目（子孫のフォルダの項目は含めない）を、表示の並び順に [browser] で開く（[openInBrowser]）。
     *
     * 項目を読んであればそれを使い、読んでいなければ、読んであるツリーからそのフォルダの項目を読む（マニフェストは GET しない）。
     * 項目ファイルが `404` を返したら、開いているフォルダと同じく、マニフェストを一度だけ読み直してから読む。
     * 読めなかったか、項目ファイルが壊れていれば知らせる。項目が無いフォルダ（溜めた削除の項目は数えない。[BrowseState.itemCountOf]）は、
     * 何もしない。溜めた削除の項目は開かない。
     */
    fun openFolderItems(folderId: String, browser: Browser) {
        val browse = state.value.browse
        val tree = browse.tree ?: return
        if (browse.itemCountOf(folderId) == 0) return
        (browse.folderReads[folderId] as? FolderReadResult.Loaded)?.let { loaded ->
            openInBrowser(browser, browse.shownItems(folderId, loaded.items))
            return
        }
        val connection = active ?: return
        connection.scope.launch {
            when (val read = connection.connection.sync.readFolder(tree, folderId)) {
                is FolderReadResult.Loaded -> openInBrowser(browser, state.value.browse.shownItems(folderId, read.items))
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

    /**
     * 選んでいる項目が 1 つだけなら、選ぶのをやめてから、その項目を `openItemInOtherApp(itemId)` で開く（[handOverSelectedItem]）。
     */
    fun openSelectedItemInOtherApp() {
        handOverSelectedItem(::openItemInOtherApp)
    }

    /**
     * 選んでいる項目が 1 つだけなら、選ぶのをやめてから、その項目を `shareItem(itemId)` で共有する（[handOverSelectedItem]）。
     */
    fun shareSelectedItem() {
        handOverSelectedItem(::shareItem)
    }

    /**
     * 開いているフォルダの項目 [itemId] を、開けるアプリの一覧を出して開く。開いているフォルダと [itemId] の組で
     * `openItemInOtherApp(ref)` を呼ぶ。
     */
    fun openItemInOtherApp(itemId: String) {
        currentItemRef(itemId)?.let { openItemInOtherApp(it) }
    }

    /**
     * 項目 [ref] を、開けるアプリの一覧を出して開く（[ItemSharer.openInOtherApp]）。開いているフォルダでないフォルダの項目も開ける。
     * 選んでいる項目は変えない。一覧を出せたら、その項目を最後に開いた項目として覚える（[rememberOpenedItem]）。
     * 受け取れるアプリが無いか、ほかの理由で開けなければ知らせる（[handOverItem]）。
     */
    fun openItemInOtherApp(ref: ItemRef) {
        handOverItem(ref, onShown = { item -> rememberOpenedItem(item.id) }) { sharer, item -> sharer.openInOtherApp(item.url) }
    }

    /** 開いているフォルダの項目 [itemId] を共有する。開いているフォルダと [itemId] の組で `shareItem(ref)` を呼ぶ。 */
    fun shareItem(itemId: String) {
        currentItemRef(itemId)?.let { shareItem(it) }
    }

    /**
     * 項目 [ref] の URL を、タイトルを添えて、共有する先のアプリの一覧を出して渡す（[ItemSharer.share]）。開いているフォルダでない
     * フォルダの項目も渡せる。選んでいる項目は変えない。最後に開いた項目は替えない。受け取れるアプリが無いか、ほかの理由で渡せなければ
     * 知らせる（[handOverItem]）。
     */
    fun shareItem(ref: ItemRef) {
        handOverItem(ref) { sharer, item -> sharer.share(item.url, item.title) }
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
     * 保存を断ってそのことを見せ、使えない理由はログにだけ書く。保存できたら、読んであったツリーと溜めた削除と、操作の書き込みの結果と
     * 元に戻せる削除を捨ててから S3 への接続を作り直し（前の S3 のクライアントは閉じ、書いている最中の操作は取り消す）、
     * 接続先（バケットかプレフィックス）が替わっていれば、前の接続先のローカルキャッシュのディレクトリと溜めた削除の記録のファイルを消す。
     * そのあと、新しい接続先の溜めた削除の記録を読む。
     * マニフェストを読めたらメインの画面へ戻り（読み書きできるツリーなら、溜めた削除を書く）、マニフェストが無ければマニフェストを作れるようにし、
     * 読めなければ理由を見せる。
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
        previous?.let { discardPreviousTarget(it, settings) }
        val pending = readPendingDeletes(settings)
        updateBrowse { it.withPendingDeletes(pending) }
        checkConnection(connection)
    }

    /** マニフェストとルートフォルダの項目を読み、結果を設定の画面に見せる。読み書きできるツリーとして読めたら、溜めた削除を書く。 */
    private fun checkConnection(connection: ActiveConnection) {
        readTree(connection, show = ::showConnectionResult, appliesPendingDeletes = true)
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
     * マニフェストを読み直す（[reload] と [storageChanges] の知らせ）。開いているフォルダが残っていれば、その項目も一緒に読む。
     * 読めて、[clearsWriteOutcome] なら、直前の操作の結果を消す。読み書きできるツリーとして読めたら、溜めた削除を書く。
     * メインの画面でないとき、マニフェストを読んでいる最中、操作を書いている最中、接続が無いときは何もしない。
     */
    private fun reread(clearsWriteOutcome: Boolean) {
        val connection = active ?: return
        if (!state.value.canReload) return
        readTree(connection, show = { result -> showTree(result, clearsWriteOutcome) }, appliesPendingDeletes = true)
    }

    /**
     * [read] でマニフェストを読み、開いているフォルダ（残っていなければルートフォルダ）の項目を一緒に読んで、結果を [show] に渡す。
     * マニフェストを読んでいる最中の状態にするのは、呼んだときにする。そのため、読み直すボタンを続けて押しても、2 回は読まない。
     * 読み書きできるツリーを読めたら、古いファイルの削除を走らせるかを確かめ（[deleteStaleFilesOnce]）、[appliesPendingDeletes] なら、
     * 続けて溜めた削除を書く（[flushPendingDeletes]）。
     *
     * @param read マニフェストを読む
     * @param show 読んだ結果を見せる
     * @param appliesPendingDeletes 読み書きできるツリーを読めたあとに、溜めた削除を書くか
     */
    private fun readTree(
        connection: ActiveConnection,
        read: TreeRead = connection.connection.sync::read,
        show: (TreeReadResult) -> Unit = ::showTree,
        appliesPendingDeletes: Boolean = false,
    ) {
        updateBrowse { it.startLoading() }
        connection.scope.launch {
            val result = read { tree -> setOf(state.value.browse.folderToOpenIn(tree)) }
            show(result)
            if (result !is TreeReadResult.Readable) return@launch
            deleteStaleFilesOnce(connection)
            if (appliesPendingDeletes) flushPendingDeletes(connection)
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

    /** 開いているフォルダの項目 [itemId] と、そのフォルダの組。フォルダを開いていなければ null。 */
    private fun currentItemRef(itemId: String): ItemRef? = state.value.browse.currentFolderId?.let { ItemRef(it, itemId) }

    /**
     * フォルダ [folderId] の読めた項目（[BrowseState.loadedItemsOf]。溜めた削除の項目は除く）のうち、ID が [itemIds] にあるものを消す。
     * 消すものが無ければ何もしない。選んでいる項目は変えない。
     * 書けたら、消した項目を元の位置へ戻せるようにする（[undoDelete]）。S3 につながらないために書けなければ（[isUnreachable]）、
     * その削除を溜め（[pendDeletes]）、溜めた削除として元に戻せるようにする。
     */
    private fun deleteLoadedItems(folderId: String, itemIds: Set<String>) {
        val itemsBefore = state.value.browse.loadedItemsOf(folderId)
        val deletedIds = itemsBefore.map { it.id }.filter { it in itemIds }.toSet()
        if (deletedIds.isEmpty()) return
        val targets = deletedIds.map { ItemRef(folderId, it) }
        writeOperations(
            listOf(DeleteItems(targets)),
            undoOnSuccess = UndoableDelete(restoringInsertions(folderId, itemsBefore, deletedIds)),
            pendsWhenUnreachable = targets.toSet(),
        )
    }

    /**
     * [operations] を 1 回の書き込みとして書き始め、書き始めたら true を返す。操作を受け付けないとき（[AppState.canWrite]）は何もせず、
     * false を返す。
     *
     * 書き始めるときに、前の操作の結果と、元に戻せる削除を消す。溜めた削除があれば、先にそれを書き（[writePendingDeletes]）、
     * 書けなければ [operations] は書かず、溜めた削除を書いた結果を [operations] の結果として扱う。
     * 書けたら、書いたあとのツリーに替え（マニフェストは GET し直さない）、開いているフォルダと検索のために読むフォルダの項目を、
     * 読んであるものを引き継いだうえで足りなければ読み（[loadFolders]）、[undoOnSuccess] を元に戻せる削除にする。
     * [pendsWhenUnreachable] があり、S3 につながらないために書けなければ（[isUnreachable]）、それを溜め（[pendDeletes]）、
     * [undoOnSuccess] を、その回に溜めた削除として元に戻せるようにする。
     * そのほかで書けなかったか確かめられなかったときは、ツリーは書く前のままにし、[undoOnFailure] を元に戻せる削除にする。
     *
     * @param undoOnSuccess 書けたときに元に戻せるようにする削除。無ければ null
     * @param undoOnFailure 書けなかったか確かめられなかったときに元に戻せるようにする削除。無ければ null
     * @param pendsWhenUnreachable S3 につながらないために書けなかったときに溜める、項目の削除の対象。溜めないなら null
     */
    private fun writeOperations(
        operations: List<Operation>,
        undoOnSuccess: UndoableDelete? = null,
        undoOnFailure: UndoableDelete? = null,
        pendsWhenUnreachable: Set<ItemRef>? = null,
    ): Boolean {
        val connection = active ?: return false
        if (!state.value.canWrite) return false
        updateWrite { WriteState(writing = true) }
        connection.scope.launch {
            val result = writePendingDeletes(connection)?.takeUnless { it is WriteResult.Success }
                ?: connection.connection.sync.writeConfirming(operations)
            if (pendsWhenUnreachable != null && isUnreachable(result)) {
                pendDeletes(connection, pendsWhenUnreachable, undoOnSuccess, result)
            } else {
                showWriteResult(result, undoOnSuccess, undoOnFailure)
            }
        }
        return true
    }

    /**
     * 溜めた削除があり、操作を受け付けるとき（[AppState.canWrite]）は、書いている最中の状態にして溜めた削除を書く（[writePendingDeletes]）。
     * 直前の操作の結果と元に戻せる削除は残す。S3 につながらないために書けなければ（[isUnreachable]）結果を替えず、
     * ほかの理由で書けなければ、操作を書けなかったときと同じく結果に出す。
     */
    private suspend fun flushPendingDeletes(connection: ActiveConnection) {
        if (state.value.browse.pendingDeletes.isEmpty() || !state.value.canWrite) return
        updateWrite { it.copy(writing = true) }
        val result = writePendingDeletes(connection)
        updateWrite { write -> write.copy(writing = false, outcome = result?.let(::failureOutcomeOf) ?: write.outcome) }
    }

    /** 溜めた削除を書いた結果 [result] のうち、結果に出す失敗。書けたときと、S3 につながらないために書けなかったときは null。 */
    private fun failureOutcomeOf(result: WriteResult): WriteOutcome? =
        when (result) {
            is WriteResult.Success -> null
            is WriteResult.Failed -> WriteOutcome.Failed(result.failure).takeUnless { isUnreachable(result) }
            is WriteResult.Unconfirmed -> WriteOutcome.Unconfirmed(result.failure).takeUnless { isUnreachable(result) }
        }

    /**
     * 溜めた削除があれば、それをすべてまとめて 1 回の書き込みとして書き、結果を返す。溜めた削除が無ければ何もせず null を返す。
     *
     * 書けたら、書いたあとのツリーに替え（[BrowseState.withWrittenTree]）、書いた対象を溜めた削除から外して記録のファイルを書き直し
     * （空になればファイルを消す）、開いているフォルダと検索のために読むフォルダの項目を、足りなければ読む（[loadFolders]）。
     * 元に戻せる削除が、書いた対象を溜めた削除なら、
     * S3 に書けた削除にする（[UndoableDelete.afterApplying]）。書けなければ溜めたままにし、理由をログに書く。
     * 書いている最中の状態と結果は替えない。
     */
    private suspend fun writePendingDeletes(connection: ActiveConnection): WriteResult? {
        val targets = state.value.browse.pendingDeletes
        if (targets.isEmpty()) return null
        val result = connection.connection.sync.writeConfirming(listOf(DeleteItems(targets.toList())))
        if (result !is WriteResult.Success) {
            log("溜めた削除を書けなかった: $result")
            return result
        }
        update {
            it.copy(
                browse = it.browse.withWrittenTree(result.tree).withPendingDeletes(it.browse.pendingDeletes - targets),
                write = it.write.copy(undoableDelete = it.write.undoableDelete?.afterApplying(targets)),
            )
        }
        storePendingDeletes(connection.settings)
        loadFolders()
        return result
    }

    /**
     * 項目の削除の対象 [targets] を、S3 につながらないために書けなかった（[result]）ものとして溜める。溜めた削除に足して記録のファイルに書き、
     * 書いている最中の状態を終える。結果は出さず、[undoable] を、その回に溜めた削除として元に戻せるようにする。理由はログにだけ書く。
     */
    private fun pendDeletes(connection: ActiveConnection, targets: Set<ItemRef>, undoable: UndoableDelete?, result: WriteResult) {
        log("S3 につながらないため、項目の削除を溜めた: $result")
        update {
            it.copy(
                browse = it.browse.withPendingDeletes(it.browse.pendingDeletes + targets),
                write = WriteState(undoableDelete = undoable?.copy(pendingTargets = targets)),
            )
        }
        storePendingDeletes(connection.settings)
    }

    /**
     * 操作を書いた結果 [result] を状態に入れる。書けたら、書いたあとのツリーに替え、開いているフォルダと検索のために読むフォルダの項目を、
     * 足りなければ読む（[loadFolders]）。
     */
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
                loadFolders()
            }
            is WriteResult.Unconfirmed ->
                updateWrite { WriteState(outcome = WriteOutcome.Unconfirmed(result.failure), undoableDelete = undoOnFailure) }
            is WriteResult.Failed ->
                updateWrite { WriteState(outcome = WriteOutcome.Failed(result.failure), undoableDelete = undoOnFailure) }
        }
    }

    /**
     * 以前の状態に戻す画面のための読み込み [read] を、[connection] で走らせる。その画面を離れたら取り消せるよう、走らせたものを覚える
     * （[cancelRevisionReading]）。前に走らせたものが残っていれば、先に取り消す。
     */
    private fun startRevisionReading(connection: ActiveConnection, read: suspend () -> Unit) {
        revisionReading?.cancel()
        revisionReading = connection.scope.launch { read() }
    }

    /** 以前の状態に戻す画面のための読み込みが走っていれば、取り消す。 */
    private fun cancelRevisionReading() {
        revisionReading?.cancel()
        revisionReading = null
    }

    /** 戻せる時点の一覧を読んだ結果 [result] を入れる。以前の状態に戻す画面で、一覧を読んでいる最中のときだけ入れる。 */
    private fun showRevisionList(result: RevisionListResult) {
        val list = when (result) {
            is RevisionListResult.Listed -> RevisionListState.Listed(result.revisions)
            is RevisionListResult.Failed -> RevisionListState.Failed(result.failure)
        }
        update { current ->
            if (current.screen != Screen.REVISIONS || current.revisions.list != RevisionListState.Loading) return@update current
            current.copy(revisions = current.revisions.copy(list = list))
        }
    }

    /**
     * 戻せる時点 [revision] のマニフェストを読んだ結果 [result] を入れる。読めたら戻すかを確かめる状態にし、読めなければそのことを伝える。
     * 以前の状態に戻す画面で、[revision] を読んでいる最中のときだけ入れる。
     */
    private fun showRevisionRead(revision: Revision, result: RevisionReadResult) {
        update { current ->
            if (current.screen != Screen.REVISIONS || current.revisions.selection != RevisionSelection.Loading(revision)) {
                return@update current
            }
            val revisions = when (result) {
                is RevisionReadResult.Loaded -> current.revisions.copy(selection = RevisionSelection.Confirming(result.revision))
                is RevisionReadResult.Failed ->
                    current.revisions.copy(selection = null, notice = RevisionNotice.ReadFailed(result.failure))
            }
            current.copy(revisions = revisions)
        }
    }

    /** 戻す前に溜めた削除を書いた結果 [result] を、戻した結果として扱うもの。書けたら null（続けて戻す）。 */
    private fun restoreResultOfPendingDeletes(result: WriteResult): RestoreResult? =
        when (result) {
            is WriteResult.Success -> null
            is WriteResult.Failed -> RestoreResult.Failed(RevisionFailure.Sync(result.failure))
            is WriteResult.Unconfirmed -> RestoreResult.Unconfirmed(result.failure)
        }

    /** 時点 [loaded] へ戻した結果 [result] を状態に入れる（[restoreSelectedRevision]）。 */
    private fun showRestoreResult(loaded: LoadedRevision, result: RestoreResult) {
        when (result) {
            is RestoreResult.Success -> {
                update {
                    it.leftRevisions().copy(
                        browse = it.browse.withWrittenTree(result.tree),
                        write = WriteState(outcome = WriteOutcome.Restored(loaded.revision.lastModified)),
                    )
                }
                loadFolders()
            }
            is RestoreResult.Failed -> update {
                it.copy(
                    revisions = it.revisions.copy(selection = null, notice = RevisionNotice.RestoreFailed(result.failure)),
                    write = WriteState(),
                )
            }
            is RestoreResult.Unconfirmed -> update {
                it.leftRevisions().copy(write = WriteState(outcome = WriteOutcome.Unconfirmed(result.failure)))
            }
        }
    }

    /** 以前の状態に戻す画面からメインの画面へ移った状態。その画面の状態を初めの値にし、設定の画面の入力を保存済みの設定から作り直す。 */
    private fun AppState.leftRevisions(): AppState =
        copy(screen = Screen.MAIN, revisions = RevisionsScreenState(), settings = editingStoredSettings(settings))

    /**
     * マニフェストを読んだ結果 [result] をツリーに入れ、開いているフォルダと検索のために読むフォルダの項目を、足りなければ読む
     * （[loadFolders]）。読めて、[clearsWriteOutcome] なら、直前の操作の結果を消す。
     */
    private fun showTree(result: TreeReadResult, clearsWriteOutcome: Boolean = true) {
        update { current ->
            val read = current.copy(browse = current.browse.withTreeRead(result))
            if (result is TreeReadResult.Failed || !clearsWriteOutcome) read else read.copy(write = read.write.copy(outcome = null))
        }
        loadFolders()
    }

    /**
     * 開いているフォルダの項目（[BrowseState.folderToLoad]）と、検索を開いていれば、検索のために読む必要があるフォルダの項目
     * （[BrowseState.foldersToLoadForSearch]）を、読んであるツリーから読む。マニフェストは GET しない。読む必要が無ければ何もしない。
     *
     * 読むフォルダは、すぐに読んでいる最中にする。読むのは開いているフォルダから順に、同時に [MAX_CONCURRENT_FOLDER_READS] 本までにする。
     * 順番を待っていたフォルダは、読み始めるとき（[loadFolder]）に読む必要が無くなっていれば読まない。ツリーが替わっていれば、
     * 替わったツリーで読む。検索を閉じていて、開いているフォルダでもなければ、読まずに読んでいる最中から外す。
     * 読み始めていた（GET を送った）読み込みは、検索を閉じても取り消さず、読めた結果は使う。
     * 項目ファイルが `404` を返したら、マニフェストを読み直す（[rereadAfterMissing]）。
     */
    private fun loadFolders() {
        val connection = active ?: return
        val browse = state.value.browse
        val tree = browse.tree ?: return
        val searchFolderIds = if (browse.search != null) browse.foldersToLoadForSearch() else emptyList()
        val folderIds = (listOfNotNull(browse.folderToLoad()) + searchFolderIds).distinct()
        if (folderIds.isEmpty()) return
        updateBrowse { current -> folderIds.fold(current, BrowseState::withFolderLoading) }
        folderIds.forEach { folderId -> connection.scope.launch { loadFolder(connection, tree, folderId) } }
    }

    /**
     * ツリー [tree] で、フォルダ [folderId] の項目を読み、結果を入れる（[BrowseState.withFolderRead]）。同時に読むフォルダが
     * 上限（[folderReading]）に達していれば、ほかのフォルダを読み終えるまで待つ。
     *
     * 読み始めるときに、次のどちらかなら読まない。
     * - ツリーが [tree] から替わっている（マニフェストが変わらない読み直しで替わったときも）。替わったツリーで読むので、状態は替えない
     * - 検索を閉じていて、[folderId] が開いているフォルダでもない。読まずに読んでいる最中から外す（[BrowseState.withoutFolderLoading]）
     *
     * 項目ファイルが `404` を返したら、マニフェストを読み直す（[rereadAfterMissing]）。
     */
    private suspend fun loadFolder(connection: ActiveConnection, tree: LoadedTree, folderId: String) {
        val result = folderReading.withPermit {
            val browse = state.value.browse
            if (browse.tree !== tree) return
            if (browse.search == null && folderId != browse.currentFolderId) {
                updateBrowse { it.withoutFolderLoading(folderId) }
                return
            }
            connection.connection.sync.readFolder(tree, folderId)
        }
        updateBrowse { it.withFolderRead(tree.eTag, folderId, result) }
        if (result is FolderReadResult.Missing) rereadAfterMissing(connection, tree, folderId)
    }

    /**
     * ツリー [tree] でフォルダ [folderId] の項目ファイルが `404` を返したあとに、マニフェストを一度だけ読み直す（[reload] と同じく
     * ツリーを替え、開いているノード・開いていたフォルダ・選んでいた項目を保つ）。読み直したマニフェストのツリーで、開いているフォルダと
     * [folderId] の項目を一緒に読む。読み直したマニフェストがまだ同じファイルを指していれば、そのフォルダは壊れているとし、
     * もう読み直さない。
     *
     * 読み直したマニフェストを読めた時点で項目ファイルが壊れていたか `404` を返していたフォルダ（[FolderReadResult.Broken] か
     * [FolderReadResult.Missing]）も一緒に読み、壊れていたことを持ち越す。`404` を返していたフォルダの結果は、読み直したツリーに
     * 引き継がれない（[BrowseState.withTreeRead]）ので、一緒に読む必要がある。
     *
     * マニフェストを読んでいる最中は、新たに読み直さない。読み直したツリーで、まだ読めていないフォルダは、そのあとに読む（[loadFolders]）。
     * ツリーのマニフェストの ETag が [tree] から替わっていたときも、替わったツリーで読むので、読み直さない（ETag が同じなら、
     * [BrowseState.withFolderRead] が結果を入れるので、読み直す）。
     */
    private fun rereadAfterMissing(connection: ActiveConnection, tree: LoadedTree, folderId: String) {
        val browse = state.value.browse
        if (browse.loading || browse.tree?.eTag != tree.eTag) return
        readTree(connection, read = { foldersToLoad ->
            connection.connection.sync.readAfterMissing(tree, folderId) { reread ->
                val unreadable = state.value.browse.folderReads
                    .filterValues { it is FolderReadResult.Broken || it is FolderReadResult.Missing }.keys
                foldersToLoad(reread) + folderId + unreadable
            }
        })
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
     * 接続先が [previous] から [settings] へ替わっていれば、[previous] のローカルキャッシュのディレクトリと、溜めた削除の記録のファイルを消す。
     * 消せなければログに書き、利用者には見せない。
     */
    @OptIn(ExperimentalPathApi::class)
    private suspend fun discardPreviousTarget(previous: ConnectionSettings, settings: ConnectionSettings) {
        if (connectionName(previous.bucket, previous.prefix) == connectionName(settings.bucket, settings.prefix)) return
        withContext(ioDispatcher) {
            try {
                connectionCacheDirectory(cacheRoot, previous.bucket, previous.prefix).deleteRecursively()
            } catch (e: IOException) {
                log("前の接続先のローカルキャッシュを消せなかった: ${describeIoFailure(e)}")
            }
        }
        val previousPendingDeletes = pendingDeletesFileOf(previous)
        pendingDeletesWriting.withLock { withContext(ioDispatcher) { previousPendingDeletes.write(emptySet()) } }
    }

    /** 接続先 [settings] の、溜めた削除の記録のファイル。 */
    private fun pendingDeletesFileOf(settings: ConnectionSettings): PendingDeletesFile =
        PendingDeletesFile(pendingDeletesFile(stateDirectory, settings.bucket, settings.prefix), log)

    /** 接続先 [settings] の溜めた削除の記録を読む。先に始めた記録のファイルへの書き込みが終わってから読む。 */
    private suspend fun readPendingDeletes(settings: ConnectionSettings): Set<ItemRef> {
        val file = pendingDeletesFileOf(settings)
        return pendingDeletesWriting.withLock { withContext(ioDispatcher) { file.read() } }
    }

    /**
     * いまの溜めた削除を、接続先 [settings] の溜めた削除の記録のファイルに書く（空ならファイルを消す）。書く中身は呼んだときに決め、
     * 書き込みは呼んだ順に 1 つずつ行う。書けなければログに書き、利用者には見せない。
     */
    private fun storePendingDeletes(settings: ConnectionSettings) {
        val file = pendingDeletesFileOf(settings)
        val targets = state.value.browse.pendingDeletes
        scope.launch { pendingDeletesWriting.withLock { withContext(ioDispatcher) { file.write(targets) } } }
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
     * 項目 [itemId] を最後に開いた項目として、すぐに状態へ入れ、最後に開いた項目の記録のファイルに書く。いまと同じなら何もしない。
     * 書けなければログに書き、利用者には見せない。続けて替えても、書き込みは 1 つずつ順に行い、それぞれ書くときの最後に開いた項目を書くので、
     * 最後に替えた項目がファイルに残る。
     */
    private fun rememberOpenedItem(itemId: String) {
        if (state.value.lastOpenedItemId == itemId) return
        update { it.copy(lastOpenedItemId = itemId) }
        scope.launch {
            withContext(ioDispatcher) {
                lastOpenedItemWriting.withLock { state.value.lastOpenedItemId?.let(lastOpenedItemFile::write) }
            }
        }
    }

    /**
     * [items] を、その並びで [browser] で開く。項目が無ければ何もしない。2 つ以上で、[browser] がまとめて開けなければ何もしない
     * （メニューでは押せない）。開き始めるときに、前に知らせたことを消す。
     * 1 つだけを開けたら、その項目を最後に開いた項目として覚える（[rememberOpenedItem]）。2 つ以上を開いたときは替えない。
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
                BrowserOpenResult.Opened -> items.singleOrNull()?.let { rememberOpenedItem(it.id) }
                is BrowserOpenResult.LaunchFailed -> updateBrowserNotice(BrowserNotice.LaunchFailed(browser.name, result.detail))
                BrowserOpenResult.CommandUndelivered -> updateBrowserNotice(BrowserNotice.CommandUndelivered(browser.name))
            }
        }
    }

    /**
     * 選んでいる 1 つの項目の ID を [handOver] に渡す。項目をほかのアプリで開く仕組みが無いか、選んでいる項目が 1 つだけでなければ
     * 何もしない。渡す前に、選ぶのをやめる。
     */
    private fun handOverSelectedItem(handOver: (itemId: String) -> Unit) {
        if (itemSharer == null) return
        val item = state.value.browse.selectedItems.singleOrNull() ?: return
        updateBrowse { it.clearSelection() }
        handOver(item.id)
    }

    /**
     * 項目 [ref] を、[handOver] でほかのアプリへ渡す。項目をほかのアプリで開く仕組みが無いか、そのフォルダの読めた項目
     * （[BrowseState.loadedItemOf]。溜めた削除の項目は除く）に無ければ何もしない。選んでいる項目は変えない。渡し始めるときに、
     * 前に知らせたことを消す。受け取るアプリの一覧を出せたら、渡した項目で [onShown] を呼ぶ。受け取れるアプリが無いか、
     * ほかの理由で渡せなければ知らせる。
     */
    private fun handOverItem(
        ref: ItemRef,
        onShown: (Item) -> Unit = {},
        handOver: suspend (ItemSharer, Item) -> ItemShareResult,
    ) {
        val sharer = itemSharer ?: return
        val item = state.value.browse.loadedItemOf(ref) ?: return
        updateBrowserNotice(null)
        scope.launch {
            when (handOver(sharer, item)) {
                ItemShareResult.Shown -> onShown(item)
                ItemShareResult.NoApp -> updateBrowserNotice(BrowserNotice.NoAppForItem)
                ItemShareResult.Failed -> updateBrowserNotice(BrowserNotice.ShareFailed)
            }
        }
    }

    /**
     * 項目ファイルが `404` を返したあとにマニフェストを読み直した結果 [result] から、フォルダ [folderId] の項目を [browser] で開く。
     * 溜めた削除の項目は開かない。読めなかったか、そのフォルダが壊れていれば知らせる。読み直したマニフェストにそのフォルダが無ければ、
     * 何もしない。
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
        tree.state.items[folderId]?.let { items -> openInBrowser(browser, state.value.browse.shownItems(folderId, items)) }
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

    private fun updateRevisions(transform: (RevisionsScreenState) -> RevisionsScreenState) {
        update { it.copy(revisions = transform(it.revisions)) }
    }

    private fun updateBrowserNotice(notice: BrowserNotice?) {
        update { it.copy(browserNotice = notice) }
    }
}

/**
 * [CloudMarksController] を組み立てる。Android とデスクトップのエントリポイントが、プラットフォームごとに違うもの（ファイルの場所、
 * 認証情報を暗号化する仕組み、時計、マニフェストの `updatedBy`、S3 から読み直すきっかけの知らせ、古いファイルの削除を行うか、
 * 項目をブラウザで開く仕組み、項目をほかのアプリで開く仕組みと共有する仕組み）を渡して呼ぶ。ログは [AppFiles.logDirectory] のファイルに書く。
 *
 * 呼んだ側が、作ったコントローラを [CloudMarksController.start] で動かし始め、使い終えたら [CloudMarksController.close] で閉じる。
 *
 * @param appFiles 設定のファイル・ローカルキャッシュ・ログ・次の起動へ持ち越す記録・端末ごとの設定のファイルの場所
 * @param protector 認証情報を暗号化する仕組み
 * @param timeSource 書き込みの手順 3 から手順 4 までの経過を測る、モノトニッククロック。Android は端末の休止中も進む時計
 * （`SystemClock.elapsedRealtimeNanos()` を基にしたもの）を渡す。`TimeSource.Monotonic`（`System.nanoTime()`）は休止中に止まりうる
 * @param updatedBy 書き込むマニフェストのヘッダーの `updatedBy`。`"desktop"` か `"android"`
 * @param storageChanges S3 から読み直すきっかけの知らせの Flow を作る関数。このコントローラのログに書く関数を受け取る。
 * デスクトップのアプリは、ほかのプロセスが S3 へ書いたことを知らせるファイルの変化を見張る Flow を作り、Android は、画面が前面に戻ったこと
 * （起動して初めて画面を出したときは知らせない）と、既定のネットワークにつながったことを合わせて知らせる Flow を返す
 * @param deletesStaleFiles 古いファイルの削除を行うか。デスクトップのアプリは行い、Android は行わない
 * @param browserOpener 項目をブラウザで開く仕組みを作る関数。このコントローラのログに書く関数を受け取る
 * @param itemSharer 項目をほかのアプリで開く仕組みと共有する仕組みを作る関数。このコントローラのログに書く関数を受け取る。
 * Android は渡し、デスクトップのアプリは null を渡す
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
    itemSharer: ((log: (String) -> Unit) -> ItemSharer)?,
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
        itemSharer = itemSharer?.invoke(log::write),
    )
}
