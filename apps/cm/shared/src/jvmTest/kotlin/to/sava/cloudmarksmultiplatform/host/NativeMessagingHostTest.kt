package to.sava.cloudmarksmultiplatform.host

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.app.Connection
import to.sava.cloudmarksmultiplatform.desktopAppFilesIn
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.GetFault
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.PutFault
import to.sava.cloudmarksmultiplatform.settings.CREDENTIALS
import to.sava.cloudmarksmultiplatform.settings.ConnectionSettings
import to.sava.cloudmarksmultiplatform.settings.FakeSecretProtector
import to.sava.cloudmarksmultiplatform.settings.SETTINGS
import to.sava.cloudmarksmultiplatform.settings.SecretProtector
import to.sava.cloudmarksmultiplatform.settings.SettingsFile
import to.sava.cloudmarksmultiplatform.settings.SettingsWriteResult
import to.sava.cloudmarksmultiplatform.storage.FolderEntry
import to.sava.cloudmarksmultiplatform.storage.Item
import to.sava.cloudmarksmultiplatform.storage.Manifest
import to.sava.cloudmarksmultiplatform.storage.encodeManifestGzip
import to.sava.cloudmarksmultiplatform.storage.itemsFileKey
import to.sava.cloudmarksmultiplatform.storage.manifestKey
import to.sava.cloudmarksmultiplatform.storage.newId
import to.sava.cloudmarksmultiplatform.sync.A_FILE_ID
import to.sava.cloudmarksmultiplatform.sync.A_ITEMS
import to.sava.cloudmarksmultiplatform.sync.FOLDER_A
import to.sava.cloudmarksmultiplatform.sync.FOLDER_B
import to.sava.cloudmarksmultiplatform.sync.FixedClock
import to.sava.cloudmarksmultiplatform.sync.InMemorySyncCache
import to.sava.cloudmarksmultiplatform.sync.MAX_RESENDS
import to.sava.cloudmarksmultiplatform.sync.NOW
import to.sava.cloudmarksmultiplatform.sync.ROOT_FILE_ID
import to.sava.cloudmarksmultiplatform.sync.ROOT_ID
import to.sava.cloudmarksmultiplatform.sync.ROOT_ITEMS
import to.sava.cloudmarksmultiplatform.sync.StorageSync
import to.sava.cloudmarksmultiplatform.sync.SyncCache
import to.sava.cloudmarksmultiplatform.sync.fileId
import to.sava.cloudmarksmultiplatform.sync.initialManifest
import to.sava.cloudmarksmultiplatform.sync.item
import to.sava.cloudmarksmultiplatform.sync.puts
import to.sava.cloudmarksmultiplatform.sync.replaceItemsAsOtherWriter
import to.sava.cloudmarksmultiplatform.sync.seed
import to.sava.cloudmarksmultiplatform.sync.storedItems
import to.sava.cloudmarksmultiplatform.sync.storedManifest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.time.TimeSource

/** 追加した項目の `added`。時計の現在（[NOW]）を、ミリ秒未満で切り捨てたもの。 */
private val WRITTEN_ADDED: Instant = Instant.parse("2026-11-16T12:34:56.789Z")

/** S3 へ書いたことを知らせるファイルに書かれる中身。時計の現在（[NOW]）。 */
private val STORAGE_CHANGED_TEXT = "$NOW\n"

/** 見本 `add-folder.json` の `folder`。 */
private val CONTRACT_FOLDER: String = jsonOf(contractBytes("add-folder.json")).jsonObject.getValue("folder").jsonPrimitive.content

/** 見本の `folder` のフォルダ（ルートフォルダの子。項目なし）を足した、ホストのテストが置くマニフェスト。 */
private fun hostManifest(): Manifest =
    initialManifest().let { it.copy(folders = it.folders + FolderEntry(CONTRACT_FOLDER, ROOT_ID, "資料", null, 0)) }

/** 追加の要求の本体。[folder] が null なら `folder` の欄を付けない。 */
private fun addBody(type: String, folder: String?, withName: Boolean = false): ByteArray =
    buildJsonObject {
        put("protocol", 1)
        put("type", type)
        if (folder != null) put("folder", folder)
        if (withName) put("name", "n")
        putJsonArray("tabs") { addJsonObject { put("url", "https://example.org/x"); put("title", "t") } }
    }.toString().encodeToByteArray()

/** ホストが接続を作るときに受け取ったもの。 */
private class OpenedConnection(
    val settings: ConnectionSettings,
    val cache: SyncCache,
    val updatedBy: String,
    val timeSource: TimeSource,
)

class NativeMessagingHostTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /**
     * 一時ディレクトリの下のファイルの場所、偽のストレージ、偽の暗号化と、それらを使うホスト。
     *
     * 接続は、ホストが渡したキャッシュ・`updatedBy`・時計をそのまま使い、S3 の代わりに [store] へ読み書きする。
     * 作るたびに [opened] に記録し、閉じるたびに [closed] を数える。時計は [FixedClock]、ID は [hostId] を 1 から順に振る。
     * デスクトップのアプリは起動せず、起動しようとした実行ファイルを [launched] に記録して [launch] の結果を返す。
     */
    private inner class Fixture(protector: SecretProtector = FakeSecretProtector()) {
        val appFiles = desktopAppFilesIn(temporaryFolder.newFolder().toPath())
        val store = FakeObjectStore().apply { seed(hostManifest()) }
        val logs = mutableListOf<String>()
        val opened = mutableListOf<OpenedConnection>()
        var closed = 0

        /** 接続を作る前に走らせる。 */
        var onOpen: () -> Unit = {}

        /** ホストを起動したプロセスの祖先の実行ファイルのパス。親から順。 */
        var ancestors: List<String?> = emptyList()

        /** ホスト自身の実行ファイルのパス。 */
        var own: String? = null

        /** 配布物のインストール先。空白を含む名前で、ディレクトリもファイルも作らない。 */
        val installDirectory: Path = temporaryFolder.newFolder().toPath().resolve("Cloud Marks")

        /** ホストの起動口のパス。null なら開発版のホスト。 */
        var hostExecutable: String? = installDirectory.resolve("cloud-marks-host.exe").toString()

        /** デスクトップのアプリを起動する代わり。 */
        var launch: (Path) -> ProcessLaunchResult = { ProcessLaunchResult.Launched(brokeAwayFromJob = true) }

        /** ホストが起動しようとした実行ファイル。 */
        val launched = mutableListOf<Path>()

        val host = NativeMessagingHost(
            appFiles = appFiles,
            protector = protector,
            log = { logs += it },
            openConnection = { settings, cache, updatedBy, timeSource ->
                onOpen()
                opened += OpenedConnection(settings, cache, updatedBy, timeSource)
                Connection(StorageSync(store, cache, updatedBy, timeSource, clock = FixedClock, wait = {})) { closed += 1 }
            },
            clock = FixedClock,
            generateId = SequentialIds()::next,
            ancestorExecutables = { ancestors.asSequence() },
            ownExecutable = { own },
            hostExecutable = { hostExecutable },
            launchApp = { executable ->
                // Path は Iterable<Path> でもあるので、+= ではなく add で 1 つ足す
                launched.add(executable)
                launch(executable)
            },
        )

        val isManifest: (String) -> Boolean = { key -> key == manifestKey(store.prefix) }

        /** 保存済みの設定として [settings] を置く。 */
        fun storeSettings(settings: ConnectionSettings = SETTINGS) {
            assertEquals(SettingsWriteResult.Success, SettingsFile(appFiles.settingsFile, FakeSecretProtector()).write(settings))
        }

        suspend fun respond(body: ByteArray): HostResponse = host.respond(inputOf(body))

        /** S3 へ書いたことを知らせるファイルの中身。無ければ null。 */
        fun storageChangedText(): String? = appFiles.storageChangedFile.takeIf { Files.exists(it) }?.readText()

        /** 偽のストレージへのリクエストも、接続を作ることも、S3 へ書いたことを知らせることもしていない。 */
        fun assertNothingWritten(name: String) {
            assertEquals(emptyList(), store.requests, name)
            assertEquals(emptyList(), opened.map { it.settings }, name)
            assertEquals(null, storageChangedText(), name)
        }
    }

    // フォルダとして追加すると、成功を返し、マニフェストの updatedBy を host にして、見本の `folder` のフォルダの子のフォルダの末尾に
    // 見本の順で項目を入れる。追加した日時は時計の現在、ID はホストが振る。接続は 1 回作って閉じる
    @Test
    fun addFolderIsWritten() = runTest {
        val fixture = Fixture().apply { storeSettings() }

        val response = fixture.respond(contractBytes("add-folder.json"))

        assertEquals(HostResponse.Ok, response)
        val manifest = fixture.store.storedManifest()
        assertEquals("host", manifest.header.updatedBy)
        val folder = manifest.folders.last()
        assertEquals(listOf(hostId(1), CONTRACT_FOLDER, "2026-11-16 21:30:15"), listOf(folder.id, folder.parent, folder.name))
        assertEquals(
            listOf(
                Item(hostId(2), "https://example.com/articles/1", "記事のタイトル", WRITTEN_ADDED),
                Item(hostId(3), "https://example.org/", "", WRITTEN_ADDED),
            ),
            fixture.store.storedItems(manifest, hostId(1)),
        )
        assertEquals(1, fixture.opened.size)
        assertEquals(1, fixture.closed)
        assertEquals(emptyList(), fixture.logs)
    }

    // `folder` を持たないフォルダとして追加する要求は、ルートフォルダの子のフォルダの末尾に作る
    @Test
    fun addFolderWithoutFolderGoesUnderRoot() = runTest {
        val fixture = Fixture().apply { storeSettings() }

        val response = fixture.respond(addBody("addFolder", folder = null, withName = true))

        assertEquals(HostResponse.Ok, response)
        val folder = fixture.store.storedManifest().folders.last()
        assertEquals(listOf(hostId(1), ROOT_ID, "n"), listOf(folder.id, folder.parent, folder.name))
    }

    // ブックマークとして追加すると、`folder` を持たない見本は、ルートフォルダの項目の末尾に足す
    @Test
    fun addBookmarksIsWritten() = runTest {
        val fixture = Fixture().apply { storeSettings() }

        val response = fixture.respond(contractBytes("add-bookmarks.json"))

        assertEquals(HostResponse.Ok, response)
        val manifest = fixture.store.storedManifest()
        assertEquals("host", manifest.header.updatedBy)
        assertEquals(
            ROOT_ITEMS + Item(hostId(1), "https://example.net/page", "名前の欄で書き換えたタイトル", WRITTEN_ADDED),
            fixture.store.storedItems(manifest, ROOT_ID),
        )
    }

    // `folder` を持つブックマークの追加は、そのフォルダの項目の末尾に足す。項目を持たないフォルダにも足せる。
    // ほかのフォルダの項目は変わらない
    @Test
    fun addBookmarksToGivenFolderIsWritten() = runTest {
        val fixture = Fixture().apply { storeSettings() }

        assertEquals(HostResponse.Ok, fixture.respond(addBody("addBookmarks", FOLDER_A)))
        assertEquals(HostResponse.Ok, fixture.respond(addBody("addBookmarks", CONTRACT_FOLDER)))

        val manifest = fixture.store.storedManifest()
        assertEquals(
            A_ITEMS + Item(hostId(1), "https://example.org/x", "t", WRITTEN_ADDED),
            fixture.store.storedItems(manifest, FOLDER_A),
        )
        assertEquals(
            listOf(Item(hostId(2), "https://example.org/x", "t", WRITTEN_ADDED)),
            fixture.store.storedItems(manifest, CONTRACT_FOLDER),
        )
        assertEquals(ROOT_ITEMS, fixture.store.storedItems(manifest, ROOT_ID))
    }

    // `folder` のフォルダが無ければ、何も書かず（PUT もマニフェストの変更も、S3 へ書いたことを知らせるファイルの書き換えも無い）、
    // folderNotFound を返す。ログには URL もタイトルも書かない
    @Test
    fun missingFolderIsFolderNotFoundAndWritesNothing() = runTest {
        listOf(
            "addFolder" to addBody("addFolder", hostId(99), withName = true),
            "addBookmarks" to addBody("addBookmarks", hostId(99)),
        ).forEach { (name, body) ->
            val fixture = Fixture().apply { storeSettings() }
            val before = fixture.store.storedManifest()

            val response = fixture.respond(body)

            assertEquals(HostResponse.Failed(FOLDER_NOT_FOUND_ERROR), response, name)
            assertEquals(
                jsonOf(contractBytes("response-folder-not-found.json")),
                jsonOf(encodeHostResponse(response)),
                name,
            )
            assertEquals(emptyList(), fixture.store.puts { true }, name)
            assertEquals(before, fixture.store.storedManifest(), name)
            assertEquals(null, fixture.storageChangedText(), name)
            val log = fixture.logs.single()
            assertTrue(log.startsWith(HOST_LOG_PREFIX), log)
            assertFalse(log.contains("https://example.org/x"), log)
        }
    }

    // マニフェストの PUT が 412 になって再適用するあいだに、ほかの書き手が追加先のフォルダ（親）を消したときは、
    // 再適用で適用できず、folderNotFound を返す。マニフェストの PUT は 412 の 1 回だけで、ほかの書き手のマニフェストが残り、
    // S3 へ書いたことを知らせるファイルは書き換えない
    @Test
    fun folderDeletedByOtherWriterIsFolderNotFound() = runTest {
        listOf(
            "addFolder" to addBody("addFolder", CONTRACT_FOLDER, withName = true),
            "addBookmarks" to addBody("addBookmarks", CONTRACT_FOLDER),
        ).forEach { (name, body) ->
            val fixture = Fixture().apply { storeSettings() }
            fixture.store.onNextPut(
                PutFault.RunBefore {
                    val current = fixture.store.storedManifest()
                    val withoutFolder = current.copy(
                        header = current.header.copy(commit = newId(), updatedBy = "android"),
                        folders = current.folders.filter { it.id != CONTRACT_FOLDER },
                    )
                    fixture.store.overwrite(manifestKey(fixture.store.prefix), encodeManifestGzip(withoutFolder))
                },
                key = fixture.isManifest,
            )

            val response = fixture.respond(body)

            assertEquals(HostResponse.Failed(FOLDER_NOT_FOUND_ERROR), response, name)
            assertEquals(1, fixture.store.puts(fixture.isManifest).size, name)
            assertEquals(null, fixture.storageChangedText(), name)
            val manifest = fixture.store.storedManifest()
            assertEquals("android", manifest.header.updatedBy, name)
            assertEquals(listOf(ROOT_ID, FOLDER_A, FOLDER_B), manifest.folders.map { it.id }, name)
        }
    }

    // 再適用するあいだに、ほかの書き手が追加先のフォルダに項目を足したときは、再適用で追加先の末尾に足し、
    // ほかの書き手の項目も残す。マニフェストの PUT は 412 の分と合わせて 2 回
    @Test
    fun itemAddedByOtherWriterToDestinationIsKept() = runTest {
        val fixture = Fixture().apply { storeSettings() }
        val othersItem = item(50)
        fixture.store.onNextPut(
            PutFault.RunBefore { fixture.store.replaceItemsAsOtherWriter(FOLDER_A, fileId(50), A_ITEMS + othersItem) },
            key = fixture.isManifest,
        )

        val response = fixture.respond(addBody("addBookmarks", FOLDER_A))

        assertEquals(HostResponse.Ok, response)
        assertEquals(2, fixture.store.puts(fixture.isManifest).size)
        assertEquals(
            A_ITEMS + othersItem + Item(hostId(1), "https://example.org/x", "t", WRITTEN_ADDED),
            fixture.store.storedItems(fixture.store.storedManifest(), FOLDER_A),
        )
        assertEquals(ROOT_ITEMS, fixture.store.storedItems(fixture.store.storedManifest(), ROOT_ID))
        assertEquals(STORAGE_CHANGED_TEXT, fixture.storageChangedText())
    }

    // 接続は、保存済みの設定と、メモリの中だけのキャッシュ、updatedBy の host、モノトニッククロックで作る
    @Test
    fun connectionUsesMemoryCacheAndHostUpdatedBy() = runTest {
        val fixture = Fixture().apply { storeSettings() }

        fixture.respond(contractBytes("add-folder.json"))

        val opened = fixture.opened.single()
        assertEquals(SETTINGS, opened.settings)
        assertIs<InMemorySyncCache>(opened.cache)
        assertEquals(HOST_UPDATED_BY, opened.updatedBy)
        assertSame(TimeSource.Monotonic, opened.timeSource)
    }

    // 書けたら、S3 へ書いたことを知らせるファイルを、時計の現在を中身にして書き換える
    @Test
    fun successRewritesStorageChangedFile() = runTest {
        val fixture = Fixture().apply { storeSettings() }
        Files.createDirectories(fixture.appFiles.stateDirectory)
        fixture.appFiles.storageChangedFile.writeText("2026-11-01T00:00:00Z\n")

        fixture.respond(contractBytes("add-bookmarks.json"))

        assertEquals(STORAGE_CHANGED_TEXT, fixture.storageChangedText())
    }

    // S3 へ書いたことを知らせるファイルを置くディレクトリが無ければ作る
    @Test
    fun storageChangedFileCreatesDirectory() = runTest {
        val fixture = Fixture().apply { storeSettings() }

        fixture.respond(contractBytes("add-bookmarks.json"))

        assertEquals(STORAGE_CHANGED_TEXT, fixture.storageChangedText())
    }

    // S3 へ書いたことを知らせるファイルを書き換えられなくても、ログに書くだけで、成功を返す
    @Test
    fun storageChangedFileFailureKeepsOk() = runTest {
        val fixture = Fixture().apply { storeSettings() }
        // ディレクトリを置くべき場所にファイルがあるので、ディレクトリを作れない
        Files.writeString(fixture.appFiles.stateDirectory, "")

        val response = fixture.respond(contractBytes("add-bookmarks.json"))

        assertEquals(HostResponse.Ok, response)
        assertTrue(fixture.logs.single().startsWith(HOST_LOG_PREFIX), fixture.logs.toString())
    }

    // 書けたかどうかを確かめられなければ、同じ操作（同じ ID）でもう一度だけ書く。先の書き込みが届いていれば、やり直しでは
    // 適用済みになり、フォルダは 1 つだけできて、成功を返す
    @Test
    fun unconfirmedWriteIsRetriedOnce() = runTest {
        val fixture = Fixture().apply { storeSettings() }
        fixture.store.onNextPut(
            PutFault.WriteThenUnknown {
                repeat(MAX_RESENDS + 1) { fixture.store.onNextGet(GetFault.ServerError, key = fixture.isManifest) }
            },
            key = fixture.isManifest,
        )

        val response = fixture.respond(contractBytes("add-folder.json"))

        assertEquals(HostResponse.Ok, response)
        assertEquals(1, fixture.store.storedManifest().folders.count { it.id == hostId(1) })
        assertEquals(1, fixture.store.puts(fixture.isManifest).size)
        assertEquals(STORAGE_CHANGED_TEXT, fixture.storageChangedText())
        assertEquals(emptyList(), fixture.logs)
    }

    // やり直しも書けなければ、確かめられなかったことを返す。書き換えが済んでいることもあるので、知らせるファイルは書き換える
    @Test
    fun retryThatDoesNotSucceedIsUnconfirmed() = runTest {
        val fixture = Fixture().apply { storeSettings() }
        fixture.store.onNextPut(
            PutFault.WriteThenUnknown {
                repeat(MAX_RESENDS + 1) { fixture.store.onNextGet(GetFault.ServerError, key = fixture.isManifest) }
                // やり直しの手順 1
                fixture.store.onNextGet(GetFault.Forbidden, key = fixture.isManifest)
            },
            key = fixture.isManifest,
        )

        val response = fixture.respond(contractBytes("add-folder.json"))

        assertEquals(HostResponse.Failed(UNCONFIRMED_ERROR), response)
        assertEquals(STORAGE_CHANGED_TEXT, fixture.storageChangedText())
        assertTrue(fixture.logs.single().contains("Forbidden"), fixture.logs.toString())
    }

    // 書けなければ、失敗の種類の応答を返し、知らせるファイルは書き換えない。ログには失敗の中身を書き、URL・タイトル・認証情報は書かない
    @Test
    fun failedWriteIsReportedWithoutSecrets() = runTest {
        val fixture = Fixture().apply { storeSettings() }
        fixture.store.onNextPut(PutFault.Forbidden, key = fixture.isManifest)

        val response = fixture.respond(contractBytes("add-folder.json"))

        assertEquals(HostResponse.Failed(FORBIDDEN_ERROR), response)
        assertEquals(null, fixture.storageChangedText())
        val log = fixture.logs.single()
        assertTrue(log.startsWith(HOST_LOG_PREFIX) && log.contains("Forbidden"), log)
        listOf("https://example.com/articles/1", "記事のタイトル", CREDENTIALS.accessKeyId, CREDENTIALS.secretAccessKey).forEach {
            assertFalse(log.contains(it), "ログに $it が含まれる: $log")
        }
    }

    // マニフェストがまだ無ければ、作らずに dataBroken を返す
    @Test
    fun missingManifestIsNotCreated() = runTest {
        val fixture = Fixture().apply { storeSettings() }
        fixture.store.remove(manifestKey(fixture.store.prefix))

        val response = fixture.respond(contractBytes("add-folder.json"))

        assertEquals(HostResponse.Failed(MANIFEST_NOT_FOUND_ERROR), response)
        assertEquals(emptyList(), fixture.store.puts { true })
        assertEquals(null, fixture.storageChangedText())
    }

    /** 見本 `response-folders.json` と同じフォルダの並びのマニフェスト。 */
    private fun contractFoldersManifest(): Manifest {
        val folders = jsonOf(contractBytes("response-folders.json")).jsonObject.getValue("folders").jsonArray.map { it.jsonObject }
        return initialManifest().copy(
            folders = folders.map { folder ->
                FolderEntry(
                    id = folder.getValue("id").jsonPrimitive.content,
                    parent = folder.getValue("parent").jsonPrimitive.contentOrNull,
                    name = folder.getValue("name").jsonPrimitive.content,
                    items = null,
                    count = 0,
                )
            },
        )
    }

    // フォルダの一覧は、マニフェストのフォルダの行を、マニフェストの順のまま返す。応答は見本と JSON として同じ。
    // マニフェストだけを GET し、項目ファイルは読まず、何も書かず、S3 へ書いたことを知らせるファイルも書き換えない。
    // 接続は、メモリの中だけのキャッシュで作って閉じる
    @Test
    fun listFoldersReturnsManifestFoldersInOrder() = runTest {
        val fixture = Fixture().apply { storeSettings() }
        fixture.store.seed(contractFoldersManifest())

        val response = fixture.respond(contractBytes("list-folders.json"))

        assertEquals(jsonOf(contractBytes("response-folders.json")), jsonOf(encodeHostResponse(response)))
        assertEquals(listOf(FakeObjectStore.Request.Get(manifestKey(fixture.store.prefix), null)), fixture.store.requests)
        assertEquals(null, fixture.storageChangedText())
        assertIs<InMemorySyncCache>(fixture.opened.single().cache)
        assertEquals(HOST_UPDATED_BY, fixture.opened.single().updatedBy)
        assertEquals(1, fixture.closed)
        assertEquals(emptyList(), fixture.logs)
    }

    // フォルダの一覧は、項目ファイルが壊れていても、無くても返せる
    @Test
    fun listFoldersDoesNotReadItemsFiles() = runTest {
        val fixture = Fixture().apply { storeSettings() }
        fixture.store.remove(itemsFileKey(fixture.store.prefix, ROOT_ID, ROOT_FILE_ID))
        fixture.store.remove(itemsFileKey(fixture.store.prefix, FOLDER_A, A_FILE_ID))

        val response = assertIs<HostResponse.Folders>(fixture.respond(contractBytes("list-folders.json")))

        assertEquals(
            listOf(ROOT_ID, FOLDER_A, FOLDER_B, CONTRACT_FOLDER),
            response.folders.map { it.id },
        )
        assertEquals(listOf(null, ROOT_ID, ROOT_ID, ROOT_ID), response.folders.map { it.parent })
        assertEquals(listOf("", "A", "B", "資料"), response.folders.map { it.name })
    }

    // フォルダの一覧を読めないときは、追加と同じ失敗の応答を返し、S3 へ書いたことを知らせるファイルは書き換えない。
    // 設定が無いときも同じ
    @Test
    fun listFoldersFailuresMatchAdd() = runTest {
        val cases = listOf<Pair<String, (Fixture) -> Unit>>(
            "マニフェストが無い" to { it.store.remove(manifestKey(it.store.prefix)) },
            "権限が無い" to { it.store.onNextGet(GetFault.Forbidden, key = it.isManifest) },
            "つながらない" to { it.store.onNextGet(GetFault.ServerError, key = it.isManifest) },
            "形式のバージョンが新しい" to { it.store.seed(initialManifest(version = 2, readableBy = 1)) },
        )
        val expected = listOf(MANIFEST_NOT_FOUND_ERROR, FORBIDDEN_ERROR, UNREACHABLE_ERROR, UPDATE_REQUIRED_ERROR)

        cases.zip(expected).forEach { (case, error) ->
            val (name, arrange) = case
            val fixture = Fixture().apply { storeSettings() }
            arrange(fixture)

            assertEquals(HostResponse.Failed(error), fixture.respond(contractBytes("list-folders.json")), name)
            assertEquals(null, fixture.storageChangedText(), name)
            assertEquals(emptyList(), fixture.store.puts { true }, name)
            assertTrue(fixture.logs.single().startsWith(HOST_LOG_PREFIX), name)
        }
    }

    // フォルダの一覧の要求も、設定のファイルが無ければ notConfigured を返し、接続を作らない
    @Test
    fun listFoldersWithoutSettingsIsNotConfigured() = runTest {
        val fixture = Fixture()

        assertEquals(HostResponse.Failed(SETTINGS_MISSING_ERROR), fixture.respond(contractBytes("list-folders.json")))
        assertEquals(emptyList(), fixture.opened.map { it.settings })
        assertEquals(emptyList(), fixture.store.requests)
    }

    // フォルダの一覧の応答が 1 MiB を超えるときは、一覧を返さず internal を返す。ちょうど上限までなら返す
    @Test
    fun listFoldersOverResponseLimitIsInternal() = runTest {
        val fixture = Fixture().apply { storeSettings() }

        fun manifestWithNameBytes(nameLength: Int): Manifest =
            initialManifest().let { base ->
                base.copy(folders = base.folders + FolderEntry(CONTRACT_FOLDER, ROOT_ID, "x".repeat(nameLength), null, 0))
            }

        val overhead = encodeHostResponse(HostResponse.Folders(manifestWithNameBytes(0).folders.map { HostFolder(it.id, it.parent, it.name) })).size
        fixture.store.seed(manifestWithNameBytes(MAX_RESPONSE_BYTES - overhead))
        assertIs<HostResponse.Folders>(fixture.respond(contractBytes("list-folders.json")))

        fixture.store.seed(manifestWithNameBytes(MAX_RESPONSE_BYTES - overhead + 1))
        assertEquals(HostResponse.Failed(INTERNAL_ERROR), fixture.respond(contractBytes("list-folders.json")))
        assertTrue(fixture.logs.single().startsWith(HOST_LOG_PREFIX), fixture.logs.toString())
    }

    // 取り決めに合わない要求は、設定を読まず、接続も作らず、何も書かずに invalidRequest を返す
    @Test
    fun invalidRequestsWriteNothing() = runTest {
        val protector = FakeSecretProtector()
        val fixture = Fixture(protector).apply { storeSettings() }

        INVALID_REQUESTS.forEach { (name, json) ->
            assertEquals(HostResponse.Failed(INVALID_REQUEST_ERROR), fixture.respond(json.encodeToByteArray()), name)
            fixture.assertNothingWritten(name)
        }
        assertEquals(emptyList(), protector.entropies)
        assertEquals(emptyList(), fixture.launched)
        assertEquals(INVALID_REQUESTS.size, fixture.logs.size)
    }

    // 拡張が入っていることの知らせは、仲介のプロセスを飛ばした最初の祖先のパスを小文字にして記録に書き、成功を返す。
    // 設定を読まず、接続を作らず、S3 へ書いたことを知らせるファイルも書き換えない
    @Test
    fun extensionPresentRecordsBrowserWithoutSettings() = runTest {
        val protector = FakeSecretProtector()
        val fixture = Fixture(protector).apply { storeSettings() }
        fixture.ancestors = listOf("""C:\Windows\System32\cmd.exe""", """C:\Chrome\Chrome.exe""", """C:\Windows\explorer.exe""")

        val response = fixture.respond(contractBytes("extension-present.json"))

        assertEquals(HostResponse.Ok, response)
        assertEquals("""{"browsers":["c:\\chrome\\chrome.exe"]}""", fixture.appFiles.browsersWithExtensionFile.readText())
        fixture.assertNothingWritten("extensionPresent")
        assertEquals(emptyList(), protector.entropies)
        assertEquals(emptyList(), fixture.logs)
    }

    // 配布物のランチャーが自分を子として起動し直すときは、自分と同じ名前の祖先を飛ばして、その上のブラウザを記録する
    @Test
    fun extensionPresentSkipsLauncherAncestors() = runTest {
        val fixture = Fixture()
        fixture.own = """C:\App\CloudMarks.exe"""
        fixture.ancestors = listOf("""C:\App\CloudMarks.exe""", """C:\Windows\System32\cmd.exe""", """C:\Chrome\chrome.exe""")

        assertEquals(HostResponse.Ok, fixture.respond(contractBytes("extension-present.json")))
        assertEquals("""{"browsers":["c:\\chrome\\chrome.exe"]}""", fixture.appFiles.browsersWithExtensionFile.readText())
    }

    // 拡張が入っていることの知らせを重ねて受けても、同じブラウザは 1 つだけ。壊れた記録は書き直す
    @Test
    fun extensionPresentDoesNotDuplicateAndRepairsRecord() = runTest {
        val fixture = Fixture()
        fixture.ancestors = listOf("""C:\Chrome\chrome.exe""")

        fixture.respond(contractBytes("extension-present.json"))
        fixture.respond(contractBytes("extension-present.json"))
        assertEquals("""{"browsers":["c:\\chrome\\chrome.exe"]}""", fixture.appFiles.browsersWithExtensionFile.readText())

        fixture.appFiles.browsersWithExtensionFile.writeText("壊れた")
        fixture.ancestors = listOf("""C:\Firefox\firefox.exe""")
        assertEquals(HostResponse.Ok, fixture.respond(contractBytes("extension-present.json")))
        assertEquals("""{"browsers":["c:\\firefox\\firefox.exe"]}""", fixture.appFiles.browsersWithExtensionFile.readText())
    }

    // ブラウザの実行ファイルを見つけられなくても、ログに書くだけで成功を返し、記録は作らない
    @Test
    fun extensionPresentWithoutBrowserIsOk() = runTest {
        val cases = listOf(
            "祖先が仲介だけ" to listOf<String?>("""C:\Windows\System32\cmd.exe""", "java.exe"),
            "祖先が無い" to emptyList(),
            "実行ファイルが分からない" to listOf<String?>(null, """C:\Chrome\chrome.exe"""),
        )

        cases.forEach { (name, ancestors) ->
            val fixture = Fixture().also {
                it.own = """C:\Java\bin\java.exe"""
                it.ancestors = ancestors
            }

            assertEquals(HostResponse.Ok, fixture.respond(contractBytes("extension-present.json")), name)
            assertFalse(Files.exists(fixture.appFiles.browsersWithExtensionFile), name)
            assertTrue(fixture.logs.single().startsWith(HOST_LOG_PREFIX), name)
        }
    }

    // 記録を書けなくても、ログに書くだけで成功を返す
    @Test
    fun extensionPresentWriteFailureIsOk() = runTest {
        val fixture = Fixture()
        fixture.ancestors = listOf("""C:\Chrome\chrome.exe""")
        // ディレクトリを置くべき場所にファイルがあるので、ディレクトリを作れない
        Files.writeString(fixture.appFiles.stateDirectory, "")

        assertEquals(HostResponse.Ok, fixture.respond(contractBytes("extension-present.json")))
        assertEquals(1, fixture.logs.size)
    }

    // コマンドの受け取りは、見本のトークンのファイルを読んで消し、中身を command にして返す。応答は見本と JSON として同じ。
    // 設定を読まず、接続を作らない
    @Test
    fun takeCommandReturnsAndDeletesCommand() = runTest {
        val protector = FakeSecretProtector()
        val fixture = Fixture(protector).apply { storeSettings() }
        val contract = jsonOf(contractBytes("response-command.json"))
        val file = fixture.appFiles.commandDirectory.resolve("$TOKEN.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, contract.jsonObject.getValue("command").toString())

        val response = fixture.respond(contractBytes("take-command.json"))

        assertEquals(contract, jsonOf(encodeHostResponse(response)))
        assertFalse(Files.exists(file))
        fixture.assertNothingWritten("takeCommand")
        assertEquals(emptyList(), protector.entropies)
        assertEquals(emptyList(), fixture.logs)
    }

    // ファイルが無い（知らないトークン、受け取り済み）ときは、command を null にして返す。同じトークンを 2 回受け取ると 2 回目は null
    @Test
    fun takeCommandWithoutFileIsNull() = runTest {
        val fixture = Fixture()
        val noCommand = jsonOf(contractBytes("response-no-command.json"))

        assertEquals(noCommand, jsonOf(encodeHostResponse(fixture.respond(contractBytes("take-command.json")))))

        val file = fixture.appFiles.commandDirectory.resolve("$TOKEN.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, """{"type":"openTabs","tabs":[{"url":"u","title":""}],"loadAtOnce":1,"loadAhead":1}""")
        assertTrue(assertIs<HostResponse.CommandTaken>(fixture.respond(contractBytes("take-command.json"))).command != null)
        assertEquals(noCommand, jsonOf(encodeHostResponse(fixture.respond(contractBytes("take-command.json")))))
        assertEquals(emptyList(), fixture.logs)
    }

    // コマンドの形に合わないファイルは、消さずに command を null にして返し、ログに書く
    @Test
    fun takeCommandWithInvalidFileIsNullAndKept() = runTest {
        val fixture = Fixture()
        val file = fixture.appFiles.commandDirectory.resolve("$TOKEN.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, """{"type":"openTabs","tabs":[],"loadAtOnce":1,"loadAhead":1}""")

        val response = fixture.respond(contractBytes("take-command.json"))

        assertEquals(HostResponse.CommandTaken(null), response)
        assertTrue(Files.exists(file))
        assertTrue(fixture.logs.single().startsWith(HOST_LOG_PREFIX), fixture.logs.toString())
        assertFalse(fixture.logs.single().contains(TOKEN), fixture.logs.single())
    }

    // トークンが決まりに合わなければ、コマンドのディレクトリのファイルに触らず、invalidRequest を返す
    @Test
    fun invalidTokenDoesNotTouchCommandFiles() = runTest {
        val fixture = Fixture()
        val file = fixture.appFiles.commandDirectory.resolve("$TOKEN.json")
        val outside = fixture.appFiles.commandDirectory.parent.resolve("outside.json")
        val command = """{"type":"openTabs","tabs":[{"url":"u","title":""}],"loadAtOnce":1,"loadAhead":1}"""
        Files.createDirectories(file.parent)
        Files.writeString(file, command)
        Files.writeString(outside, command)
        val tokens = listOf(TOKEN.uppercase(), "../outside", "..\\outside", TOKEN.dropLast(1), "${TOKEN}0", "$TOKEN\n")

        tokens.forEach { token ->
            val body = """{"protocol":1,"type":"takeCommand","token":${JsonPrimitive(token)}}""".encodeToByteArray()

            assertEquals(HostResponse.Failed(INVALID_REQUEST_ERROR), fixture.respond(body), token)
        }

        assertEquals(command, file.readText())
        assertEquals(command, outside.readText())
        assertEquals(tokens.size, fixture.logs.size)
    }

    // アプリを開く要求は、ホストの起動口と同じディレクトリの CloudMarks.exe を 1 回だけ起動し、見本の成功の応答を返す。
    // 設定のファイルが無くても成功し、接続を作らず、何も書かず、ログにも書かない
    @Test
    fun openAppLaunchesAppBesideHost() = runTest {
        val fixture = Fixture()

        val response = fixture.respond(contractBytes("open-app.json"))

        assertEquals(jsonOf(contractBytes("response-ok.json")), jsonOf(encodeHostResponse(response)))
        assertEquals(listOf(fixture.installDirectory.resolve("CloudMarks.exe")), fixture.launched)
        fixture.assertNothingWritten("openApp")
        assertEquals(emptyList(), fixture.logs)
    }

    // アプリを開く要求は、保存済みの設定があっても読まない
    @Test
    fun openAppDoesNotReadSettings() = runTest {
        val protector = FakeSecretProtector()
        val fixture = Fixture(protector).apply { storeSettings() }

        assertEquals(HostResponse.Ok, fixture.respond(contractBytes("open-app.json")))
        assertEquals(emptyList(), protector.entropies)
        assertEquals(emptyList(), fixture.opened.map { it.settings })
    }

    // 開発版のホストは、アプリを起動せず、開発版では開けないことを launchFailed で返す
    @Test
    fun openAppFromDevHostIsLaunchFailed() = runTest {
        val fixture = Fixture().apply { hostExecutable = null }

        val response = fixture.respond(contractBytes("open-app.json"))

        assertEquals(HostResponse.Failed(DEV_HOST_LAUNCH_ERROR), response)
        assertEquals(emptyList(), fixture.launched)
        assertTrue(fixture.logs.single().startsWith(HOST_LOG_PREFIX), fixture.logs.toString())
    }

    // 起動できなければ、起動できなかったことを launchFailed で返す。ログには失敗した関数と GetLastError の値か呼べなかった理由を書き、
    // パスは書かない
    @Test
    fun openAppLaunchFailureIsLaunchFailed() = runTest {
        val cases = listOf(
            ProcessLaunchResult.Failed(CREATE_PROCESS, lastError = 2) to "GetLastError: 0x00000002",
            ProcessLaunchResult.Failed(CREATE_PROCESS, lastError = null, reason = "UnsupportedOperationException") to
                "UnsupportedOperationException",
        )

        cases.forEach { (failure, detail) ->
            val fixture = Fixture().apply { launch = { failure } }

            val response = fixture.respond(contractBytes("open-app.json"))

            assertEquals(HostResponse.Failed(LAUNCH_FAILED_ERROR), response, detail)
            val log = fixture.logs.single()
            assertTrue(log.startsWith(HOST_LOG_PREFIX) && log.contains(CREATE_PROCESS) && log.contains(detail), log)
            assertFalse(log.contains(fixture.installDirectory.toString()), log)
        }
    }

    // 起動の途中の予期しない例外も launchFailed で返す（internal は追加の失敗の文）。ログには例外のクラス名だけを書く
    @Test
    fun openAppExceptionIsLaunchFailed() = runTest {
        val cases: List<Pair<String, (Fixture) -> Unit>> = listOf(
            "起動の関数が投げる" to { it.launch = { executable -> throw IllegalStateException("$executable を起動できない") } },
            "起動口のパスを読めない" to { it.hostExecutable = "cloud-marks-host${Char(0)}.exe" },
        )

        cases.forEach { (name, arrange) ->
            val fixture = Fixture().also(arrange)

            val response = fixture.respond(contractBytes("open-app.json"))

            assertEquals(HostResponse.Failed(LAUNCH_FAILED_ERROR), response, name)
            val log = fixture.logs.single()
            assertTrue(log.startsWith(HOST_LOG_PREFIX) && log.contains("Exception"), "$name: $log")
            assertFalse(log.contains(fixture.installDirectory.toString()) || log.contains("cloud-marks-host"), "$name: $log")
        }
    }

    // ジョブから抜けられずに起動したときも成功を返し、ジョブに残したまま起動したことをログに 1 行書く
    @Test
    fun openAppInsideJobIsLogged() = runTest {
        val fixture = Fixture().apply { launch = { ProcessLaunchResult.Launched(brokeAwayFromJob = false) } }

        val response = fixture.respond(contractBytes("open-app.json"))

        assertEquals(HostResponse.Ok, response)
        val log = fixture.logs.single()
        assertTrue(log.startsWith(HOST_LOG_PREFIX) && log.contains("ジョブに残したまま"), log)
        assertFalse(log.contains(fixture.installDirectory.toString()), log)
    }

    // 長さが上限を超えたメッセージと、途中で切れたメッセージは、設定を読まず、接続も作らず、何も書かずに invalidRequest を返す
    @Test
    fun brokenFramesWriteNothing() = runTest {
        val protector = FakeSecretProtector()
        val fixture = Fixture(protector).apply { storeSettings() }
        val body = contractBytes("add-folder.json")
        val cases = listOf(
            "長さが上限を超えた" to byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) + body,
            "本体の途中で切れた" to framed(body).copyOf(Int.SIZE_BYTES + body.size - 1),
            "何も届かない" to ByteArray(0),
        )

        cases.forEach { (name, bytes) ->
            assertEquals(HostResponse.Failed(INVALID_REQUEST_ERROR), fixture.host.respond(bytes.inputStream()), name)
            fixture.assertNothingWritten(name)
        }
        assertEquals(emptyList(), protector.entropies)
    }

    // 設定のファイルが無いとき、未入力の項目があるときは、接続を作らずに、設定がまだ無いことを返す
    @Test
    fun missingSettingsAreNotConfigured() = runTest {
        val cases: List<Pair<String, (Fixture) -> Unit>> = listOf(
            "設定のファイルが無い" to {},
            "リージョンが空白だけ" to { it.storeSettings(SETTINGS.copy(region = " ")) },
            "バケットが空" to { it.storeSettings(SETTINGS.copy(bucket = "")) },
            "アクセスキー ID が空" to { it.storeSettings(SETTINGS.copy(credentials = CREDENTIALS.copy(accessKeyId = ""))) },
            "シークレットが空" to { it.storeSettings(SETTINGS.copy(credentials = CREDENTIALS.copy(secretAccessKey = ""))) },
        )

        cases.forEach { (name, arrange) ->
            val fixture = Fixture().also(arrange)

            assertEquals(HostResponse.Failed(SETTINGS_MISSING_ERROR), fixture.respond(contractBytes("add-folder.json")), name)
            fixture.assertNothingWritten(name)
        }
    }

    // 設定のファイルが壊れているとき、アクセスキーを復号できないときは、接続を作らずに、設定を読めないことを返す
    @Test
    fun unreadableSettingsAreNotConfigured() = runTest {
        val cases: List<Pair<String, () -> Fixture>> = listOf(
            "JSON として読めない" to {
                Fixture().apply {
                    Files.createDirectories(appFiles.settingsFile.parent)
                    appFiles.settingsFile.writeText("{")
                }
            },
            "ほかのユーザーが暗号化した" to { Fixture(FakeSecretProtector(owner = 2)).apply { storeSettings() } },
        )

        cases.forEach { (name, arrange) ->
            val fixture = arrange()

            assertEquals(HostResponse.Failed(SETTINGS_UNREADABLE_ERROR), fixture.respond(contractBytes("add-folder.json")), name)
            fixture.assertNothingWritten(name)
            assertTrue(fixture.logs.single().startsWith(HOST_LOG_PREFIX), name)
        }
    }

    // 予期しない例外は、そのほかの失敗にして、例外のクラス名をログに書く。例外のメッセージは書かない
    @Test
    fun unexpectedExceptionIsInternal() = runTest {
        val fixture = Fixture().apply { storeSettings() }
        fixture.onOpen = { throw IllegalStateException("接続を作れない") }

        val response = fixture.respond(contractBytes("add-folder.json"))

        assertEquals(HostResponse.Failed(INTERNAL_ERROR), response)
        val log = fixture.logs.single()
        assertTrue(log.contains("java.lang.IllegalStateException"), log)
        assertFalse(log.contains("接続を作れない"), log)
        assertEquals(null, fixture.storageChangedText())
    }

    // 取り消しは、失敗の応答にせず、そのまま伝える
    @Test
    fun cancellationIsNotSwallowed() = runTest {
        val fixture = Fixture().apply { storeSettings() }
        fixture.onOpen = { throw CancellationException("取り消した") }

        assertFailsWith<CancellationException> { fixture.respond(contractBytes("add-folder.json")) }
        assertEquals(emptyList(), fixture.logs)
    }
}
