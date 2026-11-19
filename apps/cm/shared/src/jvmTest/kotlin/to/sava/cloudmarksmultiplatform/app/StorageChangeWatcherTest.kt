package to.sava.cloudmarksmultiplatform.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.desktopAppFilesIn
import to.sava.cloudmarksmultiplatform.files.replaceFileAtomically
import java.nio.file.ClosedWatchServiceException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.WatchService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 変化を待つ上限。Windows の見張りは遅れることがあるので、長めにとる。
 * 知らせが来るはずの待ちはこの上限までしか待たず、来た時点で進む。
 */
private val NOTIFICATION_WAIT_LIMIT = 10.seconds

/** テストで使う、続けて来た変化をまとめる間隔。 */
private val TEST_DEBOUNCE = 500.milliseconds

/** 知らせが来ないことを確かめるときに待つ時間。まとめる間隔より長くとる。 */
private val NO_NOTIFICATION_WAIT = 2.seconds

/** 見張る対象のファイルの名前。 */
private const val WATCHED_FILE_NAME = "storage-changed"

/** ログに書く、見張る対象のファイルの呼び名。 */
private const val WATCHED_FILE_DESCRIPTION = "見張りのテストのファイル"

/** 続けて書くときの回数。 */
private const val CONSECUTIVE_WRITES = 5

class StorageChangeWatcherTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /**
     * 見張りを始めて、見張りが開いてから [block] を走らせ、終わったら収集を取り消して、開いた [WatchService] を返す。
     * 知らせは [block] に渡す Channel に溜まる。
     */
    private suspend fun watching(
        file: Path,
        log: (String) -> Unit = {},
        block: suspend (received: Channel<Unit>) -> Unit,
    ): WatchService = coroutineScope {
        val received = Channel<Unit>(Channel.UNLIMITED)
        val opened = CompletableDeferred<WatchService>()
        val collecting = launch {
            watchFileChanges(file, WATCHED_FILE_DESCRIPTION, log, debounce = TEST_DEBOUNCE, onWatching = { opened.complete(it) })
                .collect { received.send(Unit) }
        }
        try {
            val watchService = withTimeout(NOTIFICATION_WAIT_LIMIT) { opened.await() }
            block(received)
            watchService
        } finally {
            collecting.cancelAndJoin()
        }
    }

    /** 知らせが来るのを待つ。上限までに来なければ失敗する。 */
    private suspend fun Channel<Unit>.awaitNotification() {
        withTimeout(NOTIFICATION_WAIT_LIMIT) { receive() }
    }

    /** 知らせが来ないことを確かめる。 */
    private suspend fun Channel<Unit>.assertNoNotification() {
        delay(NO_NOTIFICATION_WAIT)
        assertTrue(tryReceive().isFailure, "知らせが来た")
    }

    private fun writeFile(file: Path) {
        replaceFileAtomically(file, "${System.nanoTime()}\n".encodeToByteArray())
    }

    // 見張っているファイルを置き換えると、知らせが来る
    @Test
    fun replacingWatchedFileNotifies() = runTest {
        val file = temporaryFolder.root.toPath().resolve(WATCHED_FILE_NAME)

        withContext(Dispatchers.Default) {
            watching(file) { received ->
                writeFile(file)

                received.awaitNotification()
            }
        }
    }

    // 続けて置き換えても、知らせは 1 回にまとまる
    @Test
    fun consecutiveWritesAreMergedIntoOneNotification() = runTest {
        val file = temporaryFolder.root.toPath().resolve(WATCHED_FILE_NAME)

        withContext(Dispatchers.Default) {
            watching(file) { received ->
                repeat(CONSECUTIVE_WRITES) { writeFile(file) }

                received.awaitNotification()
                received.assertNoNotification()
            }
        }
    }

    // 同じディレクトリの別の名前のファイルを書いても、知らせは来ない。そのあと見張っているファイルを書けば来る
    @Test
    fun otherFileInDirectoryDoesNotNotify() = runTest {
        val directory = temporaryFolder.root.toPath()
        val file = directory.resolve(WATCHED_FILE_NAME)

        withContext(Dispatchers.Default) {
            watching(file) { received ->
                writeFile(directory.resolve("other-file"))
                received.assertNoNotification()

                writeFile(file)

                received.awaitNotification()
            }
        }
    }

    // ディレクトリが無ければ作ってから見張り、そこに書けば知らせが来る
    @Test
    fun missingDirectoryIsCreated() = runTest {
        val directory = temporaryFolder.root.toPath().resolve("missing").resolve("state")
        val file = directory.resolve(WATCHED_FILE_NAME)

        withContext(Dispatchers.Default) {
            watching(file) { received ->
                assertTrue(Files.isDirectory(directory))

                writeFile(file)

                received.awaitNotification()
            }
        }
    }

    // 収集を取り消すと見張りを閉じる（閉じた見張りから取り出そうとすると失敗する）
    @Test
    fun cancellingCollectionClosesWatchService() = runTest {
        val file = temporaryFolder.root.toPath().resolve(WATCHED_FILE_NAME)

        val watchService = withContext(Dispatchers.Default) {
            watching(file) {}
        }

        assertFailsWith<ClosedWatchServiceException> { watchService.poll() }
    }

    // 見張りを始められない（ディレクトリを作れない）ときは、ログに書き、知らせを出さずに終わる
    @Test
    fun failureToStartIsLoggedAndEndsWithoutNotification() = runTest {
        val notDirectory = temporaryFolder.newFile().toPath()
        val file = notDirectory.resolve(WATCHED_FILE_NAME)
        val logs = mutableListOf<String>()

        val notifications = withContext(Dispatchers.Default) {
            withTimeout(NOTIFICATION_WAIT_LIMIT) { watchFileChanges(file, WATCHED_FILE_DESCRIPTION, { logs += it }).toList() }
        }

        assertEquals(emptyList(), notifications)
        assertEquals(1, logs.size)
        assertFalse(logs.single().contains(notDirectory.toString()), "ログにパスを含めない")
    }

    // 見張りを始められないときのログは、渡した見張るファイルの呼び名で始まる
    @Test
    fun failureLogStartsWithFileDescription() = runTest {
        val file = temporaryFolder.newFile().toPath().resolve(WATCHED_FILE_NAME)
        val logs = mutableListOf<String>()

        withContext(Dispatchers.Default) {
            withTimeout(NOTIFICATION_WAIT_LIMIT) { watchFileChanges(file, WATCHED_FILE_DESCRIPTION, { logs += it }).toList() }
        }

        assertTrue(logs.single().startsWith("${WATCHED_FILE_DESCRIPTION}を見張れなかった: "), logs.single())
    }

    // S3 へ書いたことの知らせを見張り始められないときのログは、「S3 へ書いたことを知らせるファイル」と書く
    @Test
    fun storageChangesFailureLogNamesStorageChangedFile() = runTest {
        val directory = temporaryFolder.root.toPath()
        Files.writeString(directory.resolve("state"), "記録のディレクトリの場所にあるファイル")
        val logs = mutableListOf<String>()

        withContext(Dispatchers.Default) {
            withTimeout(NOTIFICATION_WAIT_LIMIT) { watchStorageChanges(desktopAppFilesIn(directory)) { logs += it }.toList() }
        }

        assertTrue(logs.single().startsWith("S3 へ書いたことを知らせるファイルを見張れなかった: "), logs.single())
    }
}
