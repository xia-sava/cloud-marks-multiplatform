package to.sava.cloudmarksmultiplatform.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.AppFiles
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.GetFault
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.Request
import to.sava.cloudmarksmultiplatform.settings.CREDENTIALS
import to.sava.cloudmarksmultiplatform.settings.ConnectionSettings
import to.sava.cloudmarksmultiplatform.settings.FakeSecretProtector
import to.sava.cloudmarksmultiplatform.settings.SETTINGS
import to.sava.cloudmarksmultiplatform.settings.SecretProtector
import to.sava.cloudmarksmultiplatform.settings.SettingsField
import to.sava.cloudmarksmultiplatform.settings.SettingsFile
import to.sava.cloudmarksmultiplatform.settings.SettingsForm
import to.sava.cloudmarksmultiplatform.settings.SettingsReadResult
import to.sava.cloudmarksmultiplatform.settings.SettingsWriteResult
import to.sava.cloudmarksmultiplatform.storage.itemsFileKey
import to.sava.cloudmarksmultiplatform.storage.manifestKey
import to.sava.cloudmarksmultiplatform.sync.A_FILE_ID
import to.sava.cloudmarksmultiplatform.sync.A_ITEMS
import to.sava.cloudmarksmultiplatform.sync.FOLDER_A
import to.sava.cloudmarksmultiplatform.sync.FOLDER_B
import to.sava.cloudmarksmultiplatform.sync.FolderReadResult
import to.sava.cloudmarksmultiplatform.sync.InMemorySyncCache
import to.sava.cloudmarksmultiplatform.sync.LoadedTree
import to.sava.cloudmarksmultiplatform.sync.ROOT_ID
import to.sava.cloudmarksmultiplatform.sync.ROOT_ITEMS
import to.sava.cloudmarksmultiplatform.sync.StorageSync
import to.sava.cloudmarksmultiplatform.sync.SyncFailure
import to.sava.cloudmarksmultiplatform.sync.addFolderAsOtherWriter
import to.sava.cloudmarksmultiplatform.sync.connectionCacheDirectory
import to.sava.cloudmarksmultiplatform.sync.fileId
import to.sava.cloudmarksmultiplatform.sync.folderId
import to.sava.cloudmarksmultiplatform.sync.initialManifest
import to.sava.cloudmarksmultiplatform.sync.item
import to.sava.cloudmarksmultiplatform.sync.replaceItemsAsOtherWriter
import to.sava.cloudmarksmultiplatform.sync.seed
import to.sava.cloudmarksmultiplatform.sync.storedManifest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TestTimeSource

/** SETTINGS と CREDENTIALS の値をすべて入れた入力。 */
private val FILLED_FORM = SettingsForm(
    region = SETTINGS.region,
    bucket = SETTINGS.bucket,
    prefix = SETTINGS.prefix,
    accessKeyId = CREDENTIALS.accessKeyId,
    secret = CREDENTIALS.secretAccessKey,
)

/** 接続先を替えるときの、別のバケット。 */
private const val OTHER_BUCKET = "other-bucket"

/** 起きたことの記録（Fixture.events）の、接続を閉じたこと。 */
private const val CLOSED = "closed"

/** 起きたことの記録（Fixture.events）の、止めた GET が取り消されたこと。 */
private const val CANCELLED = "cancelled"

/** B に項目を持たせるときの、B の項目ファイルの ID。 */
private val B_FILE_ID = fileId(3)

/** B に項目を持たせるときの、B の項目。 */
private val B_ITEMS = listOf(item(4))

// runCurrent が ExperimentalCoroutinesApi であるため。
@OptIn(ExperimentalCoroutinesApi::class)
class CloudMarksControllerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** テストの中で、場合ごとに別のディレクトリを使うための番号。 */
    private var directoryCount = 0

    /**
     * 一時ディレクトリの下のファイルの場所、偽のストレージ、偽の暗号化と、それらを使うコントローラ。
     *
     * 接続は、作るたびに [connected] に、閉じるたびに [closed] に、その設定を記録する。接続は [addStore] で登録した
     * バケットならそのストレージを、それ以外は [store] を使う。
     * コントローラの処理は [scope] の `backgroundScope` で走らせ、`runCurrent` で進める（`advanceUntilIdle` は
     * `backgroundScope` の処理を待たない）。どの処理も仮想の時間を進めないので、`runCurrent` で終わりまで進む。
     */
    private inner class Fixture(scope: TestScope, val protector: SecretProtector = FakeSecretProtector()) {
        val directory: Path = temporaryFolder.root.toPath().resolve("case-${directoryCount++}")
        val appFiles = AppFiles(directory.resolve("settings.json"), directory.resolve("cache"), directory.resolve("logs"))
        val store = FakeObjectStore()
        private val storesByBucket = mutableMapOf<String, FakeObjectStore>()
        val connected = mutableListOf<ConnectionSettings>()
        val closed = mutableListOf<ConnectionSettings>()
        val logs = mutableListOf<String>()

        /** 接続を閉じたこと（[CLOSED]）と、[stopNextGet] で止めた GET が取り消されたこと（[CANCELLED]）。起きた順。 */
        val events = mutableListOf<String>()

        /** 接続を作る直前に走らせる。 */
        var onConnect: () -> Unit = {}

        val controller = CloudMarksController(
            appFiles = appFiles,
            protector = protector,
            connect = { settings ->
                onConnect()
                connected += settings
                val sync = StorageSync(storesByBucket[settings.bucket] ?: store, InMemorySyncCache(), "desktop", TestTimeSource())
                Connection(sync) {
                    closed += settings
                    events += CLOSED
                }
            },
            log = { logs += it },
            scope = scope.backgroundScope,
            ioDispatcher = StandardTestDispatcher(scope.testScheduler),
        )

        val state: AppState get() = controller.state.value

        val settingsFile: SettingsFile get() = SettingsFile(appFiles.settingsFile, FakeSecretProtector())

        /** 保存済みの設定として [settings] を置く。 */
        fun storeSettings(settings: ConnectionSettings = SETTINGS) {
            assertEquals(SettingsWriteResult.Success, settingsFile.write(settings))
        }

        /** バケット [bucket] の接続が使う、[store] とは別のストレージを作る。 */
        fun addStore(bucket: String): FakeObjectStore = FakeObjectStore().also { storesByBucket[bucket] = it }

        val isManifest: (String) -> Boolean = { key -> key == manifestKey(store.prefix) }

        /** [store] の、フォルダ [folderId] の項目ファイル [fileId] のキー。 */
        fun itemsKey(folderId: String, fileId: String): String = itemsFileKey(store.prefix, folderId, fileId)

        /**
         * [store] の、[key] に合う次の GET を止める。返したものを完了させると進む。
         * 止めているあいだに取り消されたら、[events] に [CANCELLED] を記録する。
         */
        fun stopNextGet(key: (String) -> Boolean): CompletableDeferred<Unit> {
            val gate = CompletableDeferred<Unit>()
            store.onNextGet(
                GetFault.RunBefore {
                    try {
                        gate.await()
                    } catch (cancellation: CancellationException) {
                        events += CANCELLED
                        throw cancellation
                    }
                },
                key,
            )
            return gate
        }
    }

    /** 場合ごとの、フィクスチャの用意と、結果 [T] の確かめ方。 */
    private data class Case<T>(val arrange: (Fixture) -> Unit, val check: (T) -> Unit)

    // 設定がまだ無いとき・読めないときは、最初に設定の画面を出し、読めない理由を持つ。メインの画面へは戻れず、接続も作らない
    @Test
    fun unreadableSettingsShowSettingsScreen() = runTest {
        val cases = listOf(
            // まだ無い
            Case<SettingsReadResult?>(arrange = {}) { problem -> assertEquals(SettingsReadResult.NotFound, problem) },
            // 壊れている
            Case(arrange = { fixture ->
                Files.createDirectories(fixture.directory)
                Files.writeString(fixture.appFiles.settingsFile, "not json")
            }) { problem -> assertIs<SettingsReadResult.Corrupt>(problem) },
            // 復号できない（別のユーザーが暗号化した）
            Case(arrange = { fixture ->
                SettingsFile(fixture.appFiles.settingsFile, FakeSecretProtector(owner = 2)).write(SETTINGS)
            }) { problem -> assertIs<SettingsReadResult.Undecryptable>(problem) },
        )
        cases.forEach { (arrange, assertProblem) ->
            val fixture = Fixture(this)
            arrange(fixture)

            fixture.controller.start()
            runCurrent()

            assertEquals(Screen.SETTINGS, fixture.state.screen)
            assertProblem(fixture.state.settings.readProblem)
            assertFalse(fixture.state.settings.canReturn)
            assertFalse(fixture.state.canGoBack)
            assertEquals(emptyList(), fixture.connected)
        }
    }

    // 組み立てたコントローラは、渡した場所の設定のファイルを、渡した暗号化の仕組みで読む
    // （別のユーザーが暗号化した設定は復号できないとして、設定の画面を出す）
    @Test
    fun createdControllerReadsSettingsWithGivenProtector() = runTest {
        val directory = temporaryFolder.root.toPath().resolve("created")
        val appFiles = AppFiles(directory.resolve("settings.json"), directory.resolve("cache"), directory.resolve("logs"))
        SettingsFile(appFiles.settingsFile, FakeSecretProtector(owner = 2)).write(SETTINGS)
        val controller = createCloudMarksController(appFiles, FakeSecretProtector(), TestTimeSource(), "desktop", backgroundScope)

        controller.start()
        val state = controller.state.first { it.screen != Screen.STARTING }

        assertEquals(Screen.SETTINGS, state.screen)
        assertIs<SettingsReadResult.Undecryptable>(state.settings.readProblem)
    }

    // 設定を読めたら、その設定で接続を作り、メインの画面でマニフェストとルートフォルダの項目を出す。
    // 設定の画面の入力には保存済みの値が入り、シークレットの欄は空
    @Test
    fun readableSettingsShowTree() = runTest {
        val fixture = Fixture(this).apply {
            store.seed()
            storeSettings()
        }

        fixture.controller.start()
        runCurrent()

        val browse = fixture.state.browse
        assertEquals(Screen.MAIN, fixture.state.screen)
        assertEquals(listOf(SETTINGS), fixture.connected)
        assertEquals(initialManifest(), browse.tree?.manifest)
        assertEquals(ROOT_ID, browse.currentFolderId)
        assertEquals(FolderReadResult.Loaded(ROOT_ITEMS), browse.currentItems)
        assertEquals(SettingsForm.of(SETTINGS), fixture.state.settings.form)
        assertTrue(fixture.state.settings.hasStoredSettings)
    }

    // フォルダを開くと、マニフェストを GET せず、そのフォルダの項目ファイルだけを読む
    @Test
    fun openingFolderReadsOnlyItsFile() = runTest {
        val fixture = Fixture(this).apply {
            store.seed()
            storeSettings()
        }
        fixture.controller.start()
        runCurrent()
        val requestsBefore = fixture.store.requests.size

        fixture.controller.openFolder(FOLDER_A)
        runCurrent()

        assertEquals(FolderReadResult.Loaded(A_ITEMS), fixture.state.browse.currentItems)
        assertEquals(
            listOf(Request.Get(fixture.itemsKey(FOLDER_A, A_FILE_ID), ifNoneMatch = null)),
            fixture.store.requests.drop(requestsBefore),
        )
    }

    // 開いたフォルダの項目ファイルが 404 なら、マニフェストを読み直してツリーを替え、読み直したマニフェストが指す新しい項目ファイルを読む
    @Test
    fun missingFolderFileRereadsManifestAndReadsNewFile() = runTest {
        val fixture = Fixture(this).apply {
            store.seed()
            storeSettings()
        }
        fixture.controller.start()
        runCurrent()
        val eTag = assertNotNull(fixture.state.browse.tree).eTag
        val oldKey = fixture.itemsKey(FOLDER_A, A_FILE_ID)
        val newFileId = fileId(20)
        val newItems = listOf(item(30), item(31))
        // ほかの書き手が A の中身を替え、古いファイルは古いファイルの削除で消えた
        fixture.store.replaceItemsAsOtherWriter(FOLDER_A, newFileId, newItems)
        fixture.store.remove(oldKey)
        val requestsBefore = fixture.store.requests.size

        fixture.controller.openFolder(FOLDER_A)
        runCurrent()

        val browse = fixture.state.browse
        assertEquals(FOLDER_A, browse.currentFolderId)
        assertEquals(FolderReadResult.Loaded(newItems), browse.currentItems)
        assertEquals(fixture.store.storedManifest(), browse.tree?.manifest)
        assertFalse(browse.loading)
        assertEquals(
            listOf(
                Request.Get(oldKey, ifNoneMatch = null),
                Request.Get(manifestKey(fixture.store.prefix), ifNoneMatch = eTag),
                Request.Get(fixture.itemsKey(FOLDER_A, newFileId), ifNoneMatch = null),
            ),
            fixture.store.requests.drop(requestsBefore),
        )
    }

    // 読み直したマニフェストがまだ同じファイルを指していれば、そのフォルダは壊れているとし、マニフェストを 1 回読み直すだけで終える。
    // 開き直しても読み直さない
    @Test
    fun missingFolderFileStillReferencedAfterRereadIsBroken() = runTest {
        val fixture = Fixture(this).apply {
            store.seed()
            storeSettings()
        }
        fixture.controller.start()
        runCurrent()
        val eTag = assertNotNull(fixture.state.browse.tree).eTag
        val key = fixture.itemsKey(FOLDER_A, A_FILE_ID)
        fixture.store.remove(key)
        val requestsBefore = fixture.store.requests.size

        fixture.controller.openFolder(FOLDER_A)
        runCurrent()

        assertIs<FolderReadResult.Broken>(fixture.state.browse.currentItems)
        assertFalse(fixture.state.browse.loading)
        val expectedRequests = listOf(
            Request.Get(key, ifNoneMatch = null),
            Request.Get(manifestKey(fixture.store.prefix), ifNoneMatch = eTag),
        )
        assertEquals(expectedRequests, fixture.store.requests.drop(requestsBefore))

        fixture.controller.back()
        fixture.controller.openFolder(FOLDER_A)
        runCurrent()

        assertEquals(expectedRequests, fixture.store.requests.drop(requestsBefore))
    }

    // 項目ファイルの 404 のあとのマニフェストの読み直しが失敗したら、ツリーを残して失敗を持ち、そのフォルダは読めなかったとする。
    // マニフェストを読み直し続けない
    @Test
    fun failedRereadAfterMissingFolderFileShowsFailure() = runTest {
        val fixture = Fixture(this).apply {
            store.seed()
            storeSettings()
        }
        fixture.controller.start()
        runCurrent()
        val tree = fixture.state.browse.tree
        fixture.store.remove(fixture.itemsKey(FOLDER_A, A_FILE_ID))
        fixture.store.onNextGet(GetFault.ServerError, key = fixture.isManifest)
        val requestsBefore = fixture.store.requests.size

        fixture.controller.openFolder(FOLDER_A)
        runCurrent()

        val failure = SyncFailure.Unreachable("HTTP 500")
        val browse = fixture.state.browse
        assertEquals(FolderReadResult.Failed(failure), browse.currentItems)
        assertEquals(failure, browse.failure)
        assertEquals(tree, browse.tree)
        assertFalse(browse.loading)
        // A の項目ファイルの GET と、マニフェストの GET の 1 回ずつ
        assertEquals(2, fixture.store.requests.drop(requestsBefore).size)
    }

    // 項目ファイルの 404 のあとのマニフェストの読み直しでも、読み直すボタンと同じく、開いているノード・開いていたフォルダ・
    // 履歴・選んでいた項目を保つ（B を開いて読み込みを止めているあいだに、A へ戻って項目を選ぶ）
    @Test
    fun rereadAfterMissingFolderFileKeepsBrowseState() = runTest {
        val fixture = Fixture(this).apply {
            store.seed()
            store.replaceItemsAsOtherWriter(FOLDER_B, B_FILE_ID, B_ITEMS)
            storeSettings()
        }
        fixture.controller.start()
        runCurrent()
        fixture.controller.openFolder(FOLDER_A)
        runCurrent()
        // ほかの書き手が B の中身を替え、古いファイルは古いファイルの削除で消えた
        val oldBKey = fixture.itemsKey(FOLDER_B, B_FILE_ID)
        fixture.store.replaceItemsAsOtherWriter(FOLDER_B, fileId(21), listOf(item(5)))
        fixture.store.remove(oldBKey)
        val gate = fixture.stopNextGet { it == oldBKey }
        fixture.controller.openFolder(FOLDER_B)
        runCurrent()
        fixture.controller.back()
        fixture.controller.selectItem(A_ITEMS.first().id)
        fixture.controller.toggleExpanded(FOLDER_B)

        gate.complete(Unit)
        runCurrent()

        val after = fixture.state.browse
        assertEquals(fixture.store.storedManifest(), after.tree?.manifest)
        assertEquals(FOLDER_A, after.currentFolderId)
        assertEquals(listOf(ROOT_ID), after.history)
        assertEquals(setOf(ROOT_ID, FOLDER_B), after.expandedFolders)
        assertEquals(A_ITEMS.first().id, after.selectedItemId)
        assertEquals(FolderReadResult.Loaded(A_ITEMS), after.currentItems)
        assertFalse(after.loading)
    }

    // 読み直すと、マニフェストを条件付きで GET し、開いていたフォルダを開いたまま、その項目を一緒に読む（ローカルキャッシュにあれば GET しない）
    @Test
    fun reloadRereadsManifestAndKeepsOpenFolder() = runTest {
        val fixture = Fixture(this).apply {
            store.seed()
            storeSettings()
        }
        fixture.controller.start()
        runCurrent()
        fixture.controller.openFolder(FOLDER_A)
        runCurrent()
        val eTag = assertNotNull(fixture.state.browse.tree).eTag
        val requestsBefore = fixture.store.requests.size

        fixture.controller.reload()
        runCurrent()

        assertEquals(listOf(Request.Get(manifestKey(fixture.store.prefix), ifNoneMatch = eTag)), fixture.store.requests.drop(requestsBefore))
        assertEquals(FOLDER_A, fixture.state.browse.currentFolderId)
        assertEquals(FolderReadResult.Loaded(A_ITEMS), fixture.state.browse.currentItems)
        assertFalse(fixture.state.browse.loading)
    }

    // 読み直しが失敗したら、前に読めたツリーと開いていたフォルダの項目を残し、失敗を持つ。また読み直せる
    @Test
    fun failedReloadKeepsTreeAndHoldsFailure() = runTest {
        val fixture = Fixture(this).apply {
            store.seed()
            storeSettings()
        }
        fixture.controller.start()
        runCurrent()
        fixture.controller.openFolder(FOLDER_A)
        runCurrent()
        val tree = fixture.state.browse.tree
        fixture.store.onNextGet(GetFault.ServerError, key = fixture.isManifest)

        fixture.controller.reload()
        runCurrent()

        val browse = fixture.state.browse
        assertEquals(tree, browse.tree)
        assertEquals(FOLDER_A, browse.currentFolderId)
        assertEquals(FolderReadResult.Loaded(A_ITEMS), browse.currentItems)
        assertEquals(SyncFailure.Unreachable("HTTP 500"), browse.failure)
        assertFalse(browse.loading)
        assertTrue(fixture.state.canReload)
    }

    // 保存すると設定を書き、続けてマニフェストを読んで結果を見せる。読めたらメインの画面へ戻る。マニフェストが無ければ作れるようにする。
    // 権限が無い・繋がらない・そのほか（壊れている）は、設定の画面に失敗として見せる
    @Test
    fun saveShowsManifestReadResult() = runTest {
        val cases = listOf(
            // 読めた：メインの画面へ戻ってツリーを出す
            Case<AppState>(arrange = { fixture -> fixture.store.seed() }) { state ->
                assertEquals(Screen.MAIN, state.screen)
                assertEquals(SettingsStatus.Editing, state.settings.status)
                assertEquals(initialManifest(), state.browse.tree?.manifest)
            },
            // マニフェストが無い
            Case(arrange = {}) { state ->
                assertEquals(Screen.SETTINGS, state.screen)
                assertEquals(SettingsStatus.ManifestNotFound, state.settings.status)
            },
            // 権限が無い
            Case(arrange = { fixture -> fixture.store.onNextGet(GetFault.Forbidden, key = fixture.isManifest) }) { state ->
                assertEquals(Screen.SETTINGS, state.screen)
                assertEquals(SettingsStatus.ConnectionFailed(SyncFailure.Forbidden), state.settings.status)
            },
            // 繋がらない
            Case(arrange = { fixture -> fixture.store.onNextGet(GetFault.ServerError, key = fixture.isManifest) }) { state ->
                assertEquals(Screen.SETTINGS, state.screen)
                assertEquals(SettingsStatus.ConnectionFailed(SyncFailure.Unreachable("HTTP 500")), state.settings.status)
            },
            // そのほか（マニフェストが壊れている）
            Case(arrange = { fixture ->
                fixture.store.overwrite(manifestKey(fixture.store.prefix), "not gzip".toByteArray())
            }) { state ->
                assertEquals(Screen.SETTINGS, state.screen)
                val failed = assertIs<SettingsStatus.ConnectionFailed>(state.settings.status)
                assertIs<SyncFailure.ManifestCorrupt>(failed.failure)
            },
        )
        cases.forEachIndexed { index, (arrange, assertState) ->
            val fixture = Fixture(this)
            fixture.controller.start()
            runCurrent()
            arrange(fixture)

            fixture.controller.editSettings(FILLED_FORM)
            fixture.controller.saveSettings()
            runCurrent()

            assertState(fixture.state)
            assertEquals(SettingsReadResult.Found(SETTINGS), fixture.settingsFile.read(), "場合 $index")
            assertEquals(listOf(SETTINGS), fixture.connected, "場合 $index")
            assertTrue(fixture.state.settings.canReturn, "場合 $index")
        }
    }

    // マニフェストが無いと分かったあとで「作成」を押すと、マニフェストを作り、メインの画面へ戻ってそのツリーを出す
    @Test
    fun createsManifestWhenMissing() = runTest {
        val fixture = Fixture(this)
        fixture.controller.start()
        runCurrent()
        fixture.controller.editSettings(FILLED_FORM)
        fixture.controller.saveSettings()
        runCurrent()
        assertEquals(SettingsStatus.ManifestNotFound, fixture.state.settings.status)

        fixture.controller.createManifest()
        runCurrent()

        assertEquals(Screen.MAIN, fixture.state.screen)
        assertEquals(SettingsStatus.Editing, fixture.state.settings.status)
        val tree = assertNotNull(fixture.state.browse.tree)
        assertEquals(fixture.store.storedManifest(), tree.manifest)
        assertEquals(tree.state.rootId, fixture.state.browse.currentFolderId)
    }

    // 接続先（バケットかプレフィックス）を変えて保存したら、前の接続先のローカルキャッシュのディレクトリを消す。
    // 前後の / だけが違うプレフィックスや、リージョンだけの変更では消さない。どの場合も接続は作り直し、前の接続を閉じる
    @Test
    fun changingTargetDeletesPreviousCache() = runTest {
        val cases: List<Pair<ConnectionSettings, Boolean>> = listOf(
            SETTINGS.copy(bucket = OTHER_BUCKET) to true,
            SETTINGS.copy(prefix = "other_prefix") to true,
            SETTINGS.copy(prefix = "/${SETTINGS.prefix}/") to false,
            SETTINGS.copy(region = "us-east-1") to false,
        )
        cases.forEach { (changed, deleted) ->
            val fixture = Fixture(this).apply {
                store.seed()
                storeSettings()
            }
            val previousCache = connectionCacheDirectory(fixture.appFiles.cacheDirectory, SETTINGS.bucket, SETTINGS.prefix)
            Files.createDirectories(previousCache.resolve("items"))
            Files.write(previousCache.resolve("manifest"), byteArrayOf(1))
            Files.write(previousCache.resolve("items").resolve("a".repeat(64)), byteArrayOf(2))
            fixture.controller.start()
            runCurrent()

            fixture.controller.openSettings()
            fixture.controller.editSettings(SettingsForm.of(changed))
            fixture.controller.saveSettings()
            runCurrent()

            assertEquals(!deleted, previousCache.exists(), "$changed")
            assertEquals(listOf(SETTINGS, changed), fixture.connected, "$changed")
            assertEquals(listOf(SETTINGS), fixture.closed, "$changed")
            assertEquals(Screen.MAIN, fixture.state.screen, "$changed")
            assertEquals(emptyList(), fixture.logs, "$changed")
        }
    }

    // 設定を保存して接続を作り直すときは、読んであったツリーを捨ててから新しい接続を作る（新しい接続と前のツリーが同時に存在しない）
    @Test
    fun savingClearsTreeBeforeConnecting() = runTest {
        val fixture = Fixture(this).apply {
            store.seed()
            storeSettings()
        }
        fixture.controller.start()
        runCurrent()
        val treesWhenConnecting = mutableListOf<LoadedTree?>()
        fixture.onConnect = { treesWhenConnecting += fixture.state.browse.tree }

        fixture.controller.openSettings()
        fixture.controller.editSettings(SettingsForm.of(SETTINGS.copy(bucket = OTHER_BUCKET)))
        fixture.controller.saveSettings()
        runCurrent()

        assertEquals(listOf<LoadedTree?>(null), treesWhenConnecting)
    }

    // マニフェストか項目ファイルの GET を止めたまま接続先を変えて保存すると、止めた読み込みを取り消し終えてから前の接続を閉じる。
    // 止めた読み込みが進めるようになっても、前の接続の結果は状態に入らず、読み込み中の表示も残らない
    @Test
    fun replacingConnectionCancelsStoppedReadBeforeClosing() = runTest {
        val cases: List<Pair<String, (Fixture) -> CompletableDeferred<Unit>>> = listOf(
            // 読み直して、マニフェストの GET を止める
            "マニフェスト" to { fixture ->
                fixture.stopNextGet(fixture.isManifest).also { fixture.controller.reload() }
            },
            // フォルダを開いて、項目ファイルの GET を止める
            "フォルダ" to { fixture ->
                val key = fixture.itemsKey(FOLDER_A, A_FILE_ID)
                fixture.stopNextGet { it == key }.also { fixture.controller.openFolder(FOLDER_A) }
            },
        )
        cases.forEach { (name, stopRead) ->
            val fixture = Fixture(this).apply {
                store.seed()
                storeSettings()
            }
            val otherStore = fixture.addStore(OTHER_BUCKET).apply {
                seed()
                addFolderAsOtherWriter(folderId(9))
            }
            fixture.controller.start()
            runCurrent()
            val gate = stopRead(fixture)
            runCurrent()

            fixture.controller.openSettings()
            fixture.controller.editSettings(SettingsForm.of(SETTINGS.copy(bucket = OTHER_BUCKET)))
            fixture.controller.saveSettings()
            runCurrent()
            gate.complete(Unit)
            runCurrent()

            assertEquals(listOf(CANCELLED, CLOSED), fixture.events, name)
            val browse = fixture.state.browse
            assertEquals(Screen.MAIN, fixture.state.screen, name)
            assertEquals(otherStore.storedManifest(), browse.tree?.manifest, name)
            assertEquals(ROOT_ID, browse.currentFolderId, name)
            assertFalse(browse.loading, name)
            assertEquals(emptySet(), browse.loadingFolders, name)
        }
    }

    // 読み込みを止めたまま閉じると、接続を閉じ、止めた読み込みを取り消す。止めた読み込みが進めるようになっても、状態は変わらない
    @Test
    fun closingWhileReadingClosesConnectionAndFreezesState() = runTest {
        val fixture = Fixture(this).apply {
            store.seed()
            storeSettings()
        }
        fixture.controller.start()
        runCurrent()
        val gate = fixture.stopNextGet(fixture.isManifest)
        fixture.controller.reload()
        runCurrent()

        fixture.controller.close()
        val closedState = fixture.state
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf(SETTINGS), fixture.closed)
        assertTrue(CANCELLED in fixture.events)
        assertEquals(closedState, fixture.state)
    }

    // 保存済みの設定を開くと、シークレットの欄は空で、画面の状態は保存済みのシークレットを持たない。
    // 空のまま保存したら、保存済みのシークレットを使う
    @Test
    fun emptySecretSavesStoredSecret() = runTest {
        val fixture = Fixture(this).apply {
            store.seed()
            storeSettings()
        }
        fixture.controller.start()
        runCurrent()

        fixture.controller.openSettings()
        assertEquals("", fixture.state.settings.form.secret)
        assertTrue(fixture.state.settings.hasStoredSettings)

        fixture.controller.editSettings(fixture.state.settings.form.copy(bucket = OTHER_BUCKET))
        fixture.controller.saveSettings()
        runCurrent()

        val expected = SETTINGS.copy(bucket = OTHER_BUCKET)
        assertEquals(SettingsReadResult.Found(expected), fixture.settingsFile.read())
        assertEquals(expected, fixture.connected.last())
        assertEquals("", fixture.state.settings.form.secret)
    }

    // 入力したシークレットが状態に載っているとき（暗号化が使えず保存を断られた後）も、状態を文字列にするとシークレットは出ない
    @Test
    fun stateStringHidesTypedSecret() = runTest {
        val fixture = Fixture(this, protector = FakeSecretProtector(available = false))
        fixture.controller.start()
        runCurrent()

        fixture.controller.editSettings(FILLED_FORM)
        fixture.controller.saveSettings()
        runCurrent()

        assertIs<SettingsStatus.ProtectionUnavailable>(fixture.state.settings.status)
        assertEquals(CREDENTIALS.secretAccessKey, fixture.state.settings.form.secret)
        assertFalse(CREDENTIALS.secretAccessKey in fixture.state.toString())
    }

    // 設定の画面から保存せずに戻ると、入力した値（シークレットを含む）を捨て、保存済みの設定から作り直す
    @Test
    fun backFromSettingsDiscardsInput() = runTest {
        val fixture = Fixture(this).apply {
            store.seed()
            storeSettings()
        }
        fixture.controller.start()
        runCurrent()
        fixture.controller.openSettings()
        fixture.controller.editSettings(FILLED_FORM.copy(bucket = OTHER_BUCKET, secret = "typed-secret"))

        fixture.controller.back()

        assertEquals(Screen.MAIN, fixture.state.screen)
        assertEquals(SettingsForm.of(SETTINGS), fixture.state.settings.form)
        assertEquals(SettingsStatus.Editing, fixture.state.settings.status)
    }

    // 暗号化の仕組みが使えなければ、保存を断って理由を見せる。ファイルは書かず、接続も作らない
    @Test
    fun refusesToSaveWithoutProtection() = runTest {
        val fixture = Fixture(this, protector = FakeSecretProtector(available = false))
        fixture.controller.start()
        runCurrent()

        fixture.controller.editSettings(FILLED_FORM)
        fixture.controller.saveSettings()
        runCurrent()

        assertIs<SettingsStatus.ProtectionUnavailable>(fixture.state.settings.status)
        assertEquals(Screen.SETTINGS, fixture.state.screen)
        assertFalse(fixture.appFiles.settingsFile.exists())
        assertEquals(emptyList(), fixture.connected)
    }

    // 入力が足りなければ、足りない欄を見せて保存しない
    @Test
    fun incompleteFormIsNotSaved() = runTest {
        val fixture = Fixture(this)
        fixture.controller.start()
        runCurrent()

        fixture.controller.editSettings(FILLED_FORM.copy(bucket = "", secret = ""))
        fixture.controller.saveSettings()
        runCurrent()

        assertEquals(SettingsStatus.Incomplete(setOf(SettingsField.BUCKET, SettingsField.SECRET)), fixture.state.settings.status)
        assertFalse(fixture.appFiles.settingsFile.exists())
        assertEquals(emptyList(), fixture.connected)
    }
}
