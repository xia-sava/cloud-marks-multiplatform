package to.sava.cloudmarksmultiplatform.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.DeleteFault
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.GetFault
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.ListFault
import to.sava.cloudmarksmultiplatform.remote.FakeObjectStore.Request
import to.sava.cloudmarksmultiplatform.settings.SETTINGS
import to.sava.cloudmarksmultiplatform.sync.A_ITEMS
import to.sava.cloudmarksmultiplatform.sync.FOLDER_A
import to.sava.cloudmarksmultiplatform.sync.fileId
import to.sava.cloudmarksmultiplatform.sync.itemsFileBody
import to.sava.cloudmarksmultiplatform.sync.seed
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** 端末の時計が返す、いまの時刻。 */
private val NOW: Instant = Instant.parse("2026-11-16T12:00:00Z")

/** 古いファイルの削除の猶予（24 時間）を過ぎた古さ。古いファイルの最終更新と、前に削除を終えた時刻に使う。 */
private val LONG_AGO = 25.hours

/** 接続先の、古いファイルの削除を最後に終えた時刻を記録するファイル。 */
private val ControllerFixture.recordFile: Path
    get() = lastStaleFileDeletionFile(appFiles.stateDirectory, SETTINGS.bucket, SETTINGS.prefix)

/** 前に古いファイルの削除を終えた時刻として、[time] を記録しておく。 */
private fun ControllerFixture.record(time: Instant) {
    Files.createDirectories(recordFile.parent)
    Files.writeString(recordFile, "$time\n")
}

/** 記録してある、古いファイルの削除を最後に終えた時刻。記録が無ければ null。 */
private fun ControllerFixture.recorded(): Instant? =
    recordFile.takeIf { it.exists() }?.let { Instant.parse(Files.readString(it).trim()) }

/** 古いファイルの削除のための一覧を読んだ回数。 */
private val ControllerFixture.listRequests: Int get() = store.requests.count { it is Request.ListPage }

/** マニフェストから参照されず、猶予を過ぎた A の項目ファイルを置き、そのキーを返す。 */
private fun ControllerFixture.putStaleFile(): String =
    itemsKey(FOLDER_A, fileId(40)).also { key ->
        store.overwrite(key, itemsFileBody(FOLDER_A, A_ITEMS), lastModified = store.serverTime - LONG_AGO)
    }

// runCurrent が ExperimentalCoroutinesApi であるため。
@OptIn(ExperimentalCoroutinesApi::class)
class StaleFileDeletionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /**
     * 古いファイルの削除を行うかを [deletesStaleFiles] で渡し、時計を [NOW] にしたフィクスチャ。
     * マニフェストと保存済みの設定を置き、[arrange] のあとに起動する。
     */
    private fun TestScope.started(deletesStaleFiles: Boolean = true, arrange: (ControllerFixture) -> Unit = {}): ControllerFixture {
        val fixture = ControllerFixture(this, temporaryFolder, deletesStaleFiles = deletesStaleFiles, clock = TestClock(NOW))
        fixture.store.seed()
        fixture.storeSettings()
        arrange(fixture)
        fixture.controller.start()
        runCurrent()
        return fixture
    }

    // 起動して最初にツリーを読めたとき、前に削除を終えてから 24 時間以上たっていれば（記録が無ければたっているとする）、
    // 古いファイルを消し、その時刻を記録する。24 時間たっていなければ消さず、記録も替えない。結果はログにだけ書き、画面には出さない
    @Test
    fun deletesWhenIntervalHasPassed() = runTest {
        val cases: List<Pair<Instant?, Boolean>> = listOf(
            null to true,
            NOW - 24.hours to true,
            NOW - 23.hours to false,
        )
        cases.forEach { (last, runs) ->
            lateinit var staleKey: String
            val fixture = started { fixture ->
                staleKey = fixture.putStaleFile()
                last?.let { fixture.record(it) }
            }

            assertEquals(if (runs) 1 else 0, fixture.listRequests, "$last")
            assertEquals(!runs, staleKey in fixture.store.keys, "$last")
            assertEquals(if (runs) NOW else last, fixture.recorded(), "$last")
            assertEquals(if (runs) 1 else 0, fixture.logs.size, "$last")
            assertNull(fixture.state.browse.failure, "$last")
        }
    }

    // 記録が日時として読めないときは、間隔を過ぎているとして走らせ、記録を書き直す。読めなかったことと削除の結果をログに書く
    @Test
    fun unparsableRecordRunsDeletion() = runTest {
        lateinit var staleKey: String
        val fixture = started { fixture ->
            staleKey = fixture.putStaleFile()
            Files.createDirectories(fixture.recordFile.parent)
            Files.writeString(fixture.recordFile, "not a date\n")
        }

        assertEquals(1, fixture.listRequests)
        assertTrue(staleKey !in fixture.store.keys)
        assertEquals(NOW, fixture.recorded())
        assertEquals(2, fixture.logs.size)
        assertTrue("日時として読めない" in fixture.logs.first())
        assertNull(fixture.state.browse.failure)
    }

    // 削除が失敗したら（一覧が 403、消せなかったファイルがある）、時刻を記録しない。結果はログにだけ書き、画面には出さない
    @Test
    fun failedDeletionIsNotRecorded() = runTest {
        val cases: List<Pair<String, (ControllerFixture) -> Unit>> = listOf(
            "一覧が 403" to { it.store.onNextList(ListFault.Forbidden) },
            "消せなかったファイルがある" to { it.store.onNextDelete(DeleteFault.Forbidden) },
        )
        cases.forEach { (name, fault) ->
            val fixture = started { fixture ->
                fixture.putStaleFile()
                fixture.record(NOW - LONG_AGO)
                fault(fixture)
            }

            assertEquals(1, fixture.listRequests, name)
            assertEquals(NOW - LONG_AGO, fixture.recorded(), name)
            assertEquals(1, fixture.logs.size, name)
            assertNull(fixture.state.browse.failure, name)
        }
    }

    // 古いファイルの削除を走らせるかは、起動して最初にツリーを読めたときだけ確かめる。読み直しても、もう一度は走らせない
    @Test
    fun checksOnlyOnFirstTreeRead() = runTest {
        val fixture = started { it.store.onNextList(ListFault.Forbidden) }
        assertEquals(1, fixture.listRequests)

        fixture.controller.reload()
        runCurrent()

        assertEquals(1, fixture.listRequests)
    }

    // 起動して最初のツリーの読みが失敗したら走らせず、そのあと初めてツリーを読めたときに走らせる
    @Test
    fun checksWhenTreeIsFirstReadAfterFailure() = runTest {
        val fixture = started { it.store.onNextGet(GetFault.ServerError, key = it.isManifest) }
        assertEquals(0, fixture.listRequests)

        fixture.controller.reload()
        runCurrent()

        assertEquals(1, fixture.listRequests)
        assertEquals(NOW, fixture.recorded())
    }

    // 古いファイルの削除を行わない（Android と同じ）なら、記録が無くても走らせず、記録も書かない
    @Test
    fun disabledDeletionNeverRuns() = runTest {
        val fixture = started(deletesStaleFiles = false)

        assertEquals(0, fixture.listRequests)
        assertNull(fixture.recorded())
        assertEquals(emptyList(), fixture.logs)
    }
}
