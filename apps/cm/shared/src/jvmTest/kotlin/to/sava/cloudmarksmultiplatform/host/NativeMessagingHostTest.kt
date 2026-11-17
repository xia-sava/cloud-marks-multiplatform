package to.sava.cloudmarksmultiplatform.host

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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
import to.sava.cloudmarksmultiplatform.storage.Item
import to.sava.cloudmarksmultiplatform.storage.manifestKey
import to.sava.cloudmarksmultiplatform.sync.FixedClock
import to.sava.cloudmarksmultiplatform.sync.InMemorySyncCache
import to.sava.cloudmarksmultiplatform.sync.MAX_RESENDS
import to.sava.cloudmarksmultiplatform.sync.NOW
import to.sava.cloudmarksmultiplatform.sync.ROOT_ID
import to.sava.cloudmarksmultiplatform.sync.ROOT_ITEMS
import to.sava.cloudmarksmultiplatform.sync.StorageSync
import to.sava.cloudmarksmultiplatform.sync.SyncCache
import to.sava.cloudmarksmultiplatform.sync.puts
import to.sava.cloudmarksmultiplatform.sync.seed
import to.sava.cloudmarksmultiplatform.sync.storedItems
import to.sava.cloudmarksmultiplatform.sync.storedManifest
import java.nio.file.Files
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
     */
    private inner class Fixture(protector: SecretProtector = FakeSecretProtector()) {
        val appFiles = desktopAppFilesIn(temporaryFolder.newFolder().toPath())
        val store = FakeObjectStore().apply { seed() }
        val logs = mutableListOf<String>()
        val opened = mutableListOf<OpenedConnection>()
        var closed = 0

        /** 接続を作る前に走らせる。 */
        var onOpen: () -> Unit = {}

        /** ホストを起動したプロセスの祖先の実行ファイルのパス。親から順。 */
        var ancestors: List<String?> = emptyList()

        /** ホスト自身の実行ファイルのパス。 */
        var own: String? = null

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

    // フォルダとして追加すると、成功を返し、マニフェストの updatedBy を host にして、ルートフォルダの子のフォルダの末尾に
    // 見本の順で項目を入れる。追加した日時は時計の現在、ID はホストが振る。接続は 1 回作って閉じる
    @Test
    fun addFolderIsWritten() = runTest {
        val fixture = Fixture().apply { storeSettings() }

        val response = fixture.respond(contractBytes("add-folder.json"))

        assertEquals(HostResponse.Ok, response)
        val manifest = fixture.store.storedManifest()
        assertEquals("host", manifest.header.updatedBy)
        val folder = manifest.folders.last()
        assertEquals(listOf(hostId(1), ROOT_ID, "2026-11-16 21:30:15"), listOf(folder.id, folder.parent, folder.name))
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

    // ブックマークとして追加すると、ルートフォルダの項目の末尾に足す
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
