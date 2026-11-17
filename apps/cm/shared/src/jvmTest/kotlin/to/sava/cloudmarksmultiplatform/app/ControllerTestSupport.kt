package to.sava.cloudmarksmultiplatform.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.AppFiles
import to.sava.cloudmarksmultiplatform.browser.FakeBrowserOpener
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.GetFault
import to.sava.cloudmarksmultiplatform.settings.ConnectionSettings
import to.sava.cloudmarksmultiplatform.settings.FakeSecretProtector
import to.sava.cloudmarksmultiplatform.settings.SETTINGS
import to.sava.cloudmarksmultiplatform.settings.SecretProtector
import to.sava.cloudmarksmultiplatform.settings.SettingsFile
import to.sava.cloudmarksmultiplatform.settings.SettingsWriteResult
import to.sava.cloudmarksmultiplatform.storage.itemsFileKey
import to.sava.cloudmarksmultiplatform.storage.manifestKey
import to.sava.cloudmarksmultiplatform.sync.InMemorySyncCache
import to.sava.cloudmarksmultiplatform.sync.StorageSync
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TestTimeSource

/** 起きたことの記録（ControllerFixture.events）の、接続を閉じたこと。 */
internal const val CLOSED = "closed"

/** 起きたことの記録（ControllerFixture.events）の、止めた GET が取り消されたこと。 */
internal const val CANCELLED = "cancelled"

/** 差し替えられる端末の時計。[now] が返す時刻を [current] で替える。 */
internal class TestClock(var current: Instant) : Clock {
    override fun now(): Instant = current
}

/**
 * 一時ディレクトリの下のファイルの場所、偽のストレージ、偽の暗号化と、それらを使うコントローラ。
 *
 * 接続は、作るたびに [connected] に、閉じるたびに [closed] に、その設定を記録する。接続は [addStore] で登録した
 * バケットならそのストレージを、それ以外は [store] を使う。
 * 再送と再適用の前の待ちは時間を使わないので、どの処理も仮想の時間を進めない。
 *
 * @param scope コントローラの処理を走らせるスコープ
 * @param ioDispatcher コントローラが、設定のファイルの読み書きなどに使う
 * @param temporaryFolder この中に、フィクスチャごとに新しいディレクトリを作る
 * @param deletesStaleFiles コントローラが古いファイルの削除を行うか
 * @param clock 古いファイルの削除の経過を測る時計
 * @param directory ファイルを置くディレクトリ。前のフィクスチャと同じものを渡すと、そのファイルを読むコントローラを作り直せる。
 * 渡さなければ、[temporaryFolder] の中に新しく作る
 * @param browserOpener コントローラが項目をブラウザで開く仕組み。ブラウザを起動せず、頼まれたことを記録する
 */
internal class ControllerFixture(
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
    temporaryFolder: TemporaryFolder,
    val protector: SecretProtector = FakeSecretProtector(),
    deletesStaleFiles: Boolean = false,
    clock: Clock = Clock.System,
    val directory: Path = temporaryFolder.newFolder().toPath(),
    val browserOpener: FakeBrowserOpener = FakeBrowserOpener(),
) {
    /**
     * コントローラの処理を [scope] の `backgroundScope` で走らせ、`runCurrent` で進める（`advanceUntilIdle` は
     * `backgroundScope` の処理を待たない）。どの処理も仮想の時間を進めないので、`runCurrent` で終わりまで進む。
     */
    constructor(
        scope: TestScope,
        temporaryFolder: TemporaryFolder,
        protector: SecretProtector = FakeSecretProtector(),
        deletesStaleFiles: Boolean = false,
        clock: Clock = Clock.System,
        directory: Path = temporaryFolder.newFolder().toPath(),
        browserOpener: FakeBrowserOpener = FakeBrowserOpener(),
    ) : this(
        scope = scope.backgroundScope,
        ioDispatcher = StandardTestDispatcher(scope.testScheduler),
        temporaryFolder = temporaryFolder,
        protector = protector,
        deletesStaleFiles = deletesStaleFiles,
        clock = clock,
        directory = directory,
        browserOpener = browserOpener,
    )

    val appFiles = AppFiles(
        settingsFile = directory.resolve("settings.json"),
        cacheDirectory = directory.resolve("cache"),
        logDirectory = directory.resolve("logs"),
        stateDirectory = directory.resolve("state"),
        deviceSettingsFile = directory.resolve("device.json"),
        commandDirectory = directory.resolve("commands"),
    )
    val store = FakeObjectStore()
    private val storesByBucket = mutableMapOf<String, FakeObjectStore>()
    val connected = mutableListOf<ConnectionSettings>()
    val closed = mutableListOf<ConnectionSettings>()
    val logs = mutableListOf<String>()

    /** 接続を閉じたこと（[CLOSED]）と、[stopNextGet] で止めた GET が取り消されたこと（[CANCELLED]）。起きた順。 */
    val events = mutableListOf<String>()

    /** 接続を作る直前に走らせる。 */
    var onConnect: () -> Unit = {}

    /** コントローラへ渡す、ほかのプロセスが S3 へ書いた知らせ。流すと知らせを出したことになる。 */
    val storageChanges = MutableSharedFlow<Unit>()

    val controller = CloudMarksController(
        appFiles = appFiles,
        protector = protector,
        connect = { settings ->
            onConnect()
            connected += settings
            val sync = StorageSync(
                store = storesByBucket[settings.bucket] ?: store,
                cache = InMemorySyncCache(),
                updatedBy = "desktop",
                timeSource = TestTimeSource(),
                wait = {},
            )
            Connection(sync) {
                closed += settings
                events += CLOSED
            }
        },
        log = { logs += it },
        scope = scope,
        storageChanges = storageChanges,
        deletesStaleFiles = deletesStaleFiles,
        browserOpener = browserOpener,
        ioDispatcher = ioDispatcher,
        clock = clock,
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
