package to.sava.cloudmarksmultiplatform.window

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.awt.Frame
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * 知らせや別のプロセスを待つ上限。Windows の見張りは遅れることがあり、別のプロセスの起動にも時間がかかるので、長めにとる。
 * 来るはずの待ちはこの上限までしか待たず、来た時点で進む。
 */
private val WAIT_LIMIT = 10.seconds

/** 知らせが 1 回きりで、続けて来ないことを確かめるときに待つ時間。まとめる間隔より長くとる。 */
private val NO_MORE_NOTIFICATION_WAIT = 1.seconds

class SingleInstanceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val stateDirectory: Path by lazy { temporaryFolder.root.toPath().resolve("state") }
    private val lockFile: Path by lazy { stateDirectory.resolve("instance.lock") }
    private val showWindowFile: Path by lazy { stateDirectory.resolve("show-window") }

    /** 取れたロックなら外す。 */
    private fun InstanceLockResult.releaseIfAcquired() {
        (this as? InstanceLockResult.Acquired)?.close {}
    }

    /**
     * [file] のロックを別のプロセスに取らせ、取れるのを待ってから [block] を走らせる。終わったら、そのプロセスにロックを外させて終わらせる。
     */
    private fun withLockHeldByAnotherProcess(file: Path, block: () -> Unit) {
        val classPath = listOf(InstanceLockHolder::class.java, Unit::class.java)
            .map { Path.of(assertNotNull(it.protectionDomain?.codeSource?.location).toURI()).toString() }
            .joinToString(File.pathSeparator)
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(java, "-cp", classPath, InstanceLockHolder::class.java.name, file.toString())
            .redirectErrorStream(true)
            .start()
        try {
            val firstLine = CompletableFuture
                .supplyAsync { process.inputStream.bufferedReader().readLine() }
                .get(WAIT_LIMIT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            assertEquals(InstanceLockHolder.LOCK_HELD_LINE, firstLine)
            block()
        } finally {
            // 標準入力を閉じると、ロックを外して終わる
            process.outputStream.close()
            if (!process.waitFor(WAIT_LIMIT.inWholeMilliseconds, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        }
    }

    // 誰もロックを持っていなければ、取れる
    @Test
    fun firstAcquisitionSucceeds() {
        val result = acquireInstanceLock(lockFile)

        assertIs<InstanceLockResult.Acquired>(result)
        result.close {}
    }

    // ロックを持ったまま、もう一度取ろうとすると「既に起動している」
    @Test
    fun secondAcquisitionWhileHeldIsAlreadyRunning() {
        val first = assertIs<InstanceLockResult.Acquired>(acquireInstanceLock(lockFile))
        try {
            val second = acquireInstanceLock(lockFile)

            second.releaseIfAcquired()
            assertIs<InstanceLockResult.AlreadyRunning>(second)
        } finally {
            first.close {}
        }
    }

    // ほかのプロセスがロックを持っていれば「既に起動している」
    @Test
    fun lockHeldByAnotherProcessIsAlreadyRunning() {
        Files.createDirectories(stateDirectory)

        withLockHeldByAnotherProcess(lockFile) {
            val result = acquireInstanceLock(lockFile)

            result.releaseIfAcquired()
            assertIs<InstanceLockResult.AlreadyRunning>(result)
        }
    }

    // 持っていたロックを閉じたあとは、また取れる
    @Test
    fun acquisitionAfterCloseSucceeds() {
        assertIs<InstanceLockResult.Acquired>(acquireInstanceLock(lockFile)).close {}

        val result = acquireInstanceLock(lockFile)

        assertIs<InstanceLockResult.Acquired>(result)
        result.close {}
    }

    // 取れたロックは 2 回閉じてもよい。2 回目も投げず、ログにも書かない
    @Test
    fun closingTwiceIsAllowed() {
        val result = assertIs<InstanceLockResult.Acquired>(acquireInstanceLock(lockFile))
        val logs = mutableListOf<String>()

        result.close { logs += it }
        result.close { logs += it }

        assertEquals(emptyList(), logs)
    }

    // ロックのファイルが残っていても、ロックを持つものがいなければ取れる
    @Test
    fun leftoverFileWithoutHolderCanBeAcquired() {
        Files.createDirectories(stateDirectory)
        Files.writeString(lockFile, "前の起動で残ったファイル")

        val result = acquireInstanceLock(lockFile)

        assertIs<InstanceLockResult.Acquired>(result)
        result.close {}
    }

    // 親のディレクトリが無ければ作ってから取る
    @Test
    fun missingParentDirectoryIsCreated() {
        val file = temporaryFolder.root.toPath().resolve("missing").resolve("state").resolve("instance.lock")

        val result = acquireInstanceLock(file)

        assertIs<InstanceLockResult.Acquired>(result)
        result.close {}
        assertTrue(Files.isRegularFile(file))
    }

    // ロックのファイルを開けない（その場所にディレクトリがある）ときは「取れなかった」。理由にパスを含めない
    @Test
    fun unopenablePathIsFailed() {
        Files.createDirectories(lockFile)

        val result = acquireInstanceLock(lockFile)

        result.releaseIfAcquired()
        val failed = assertIs<InstanceLockResult.Failed>(result)
        assertFalse(failed.reason.contains(lockFile.toString()), "理由にパスを含めない")
    }

    // 知らせのファイルを書き換えると、見張りの Flow に知らせが 1 回届く
    @Test
    fun requestReachesWatcherOnce() = runTest {
        val logs = mutableListOf<String>()

        withContext(Dispatchers.Default) {
            val received = Channel<Unit>(Channel.UNLIMITED)
            val opened = CompletableDeferred<Unit>()
            val collecting = launch {
                watchShowWindowRequests(showWindowFile, { logs += it }, onWatching = { opened.complete(Unit) })
                    .collect { received.send(Unit) }
            }
            try {
                withTimeout(WAIT_LIMIT) { opened.await() }

                requestShowWindow(showWindowFile) { logs += it }

                withTimeout(WAIT_LIMIT) { received.receive() }
                delay(NO_MORE_NOTIFICATION_WAIT)
                assertTrue(received.tryReceive().isFailure, "知らせが 2 回以上来た")
            } finally {
                collecting.cancelAndJoin()
            }
        }
        assertEquals(emptyList(), logs)
    }

    // 知らせのファイルを書き換えられない（親の場所にファイルがある）ときは、ログに 1 回書く。パスは含めない
    @Test
    fun requestFailureIsLogged() {
        val notDirectory = temporaryFolder.newFile().toPath()
        val logs = mutableListOf<String>()

        requestShowWindow(notDirectory.resolve("show-window")) { logs += it }

        assertEquals(1, logs.size)
        assertTrue(logs.single().startsWith("ウィンドウを前に出させる知らせのファイルを書き換えられなかった: "), logs.single())
        assertFalse(logs.single().contains(notDirectory.toString()), "ログにパスを含めない")
    }

    // 最小化していれば、最小化だけを解く。最大化していたものは最大化したまま、そうでなければ通常の状態に戻る
    @Test
    fun deiconifiedExtendedStateClearsOnlyIconified() {
        mapOf(
            Frame.ICONIFIED or Frame.MAXIMIZED_BOTH to Frame.MAXIMIZED_BOTH,
            Frame.ICONIFIED to Frame.NORMAL,
        ).forEach { (state, expected) ->
            assertEquals(expected, deiconifiedExtendedState(state), "extendedState=$state")
        }
    }

    // 最小化していなければ、そのまま
    @Test
    fun deiconifiedExtendedStateKeepsNotIconified() {
        listOf(Frame.NORMAL, Frame.MAXIMIZED_BOTH, Frame.MAXIMIZED_HORIZ, Frame.MAXIMIZED_VERT).forEach { state ->
            assertEquals(state, deiconifiedExtendedState(state), "extendedState=$state")
        }
    }
}
