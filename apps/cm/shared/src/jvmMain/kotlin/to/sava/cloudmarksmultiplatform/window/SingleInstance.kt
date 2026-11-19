package to.sava.cloudmarksmultiplatform.window

import kotlinx.coroutines.flow.Flow
import to.sava.cloudmarksmultiplatform.app.watchFileChanges
import to.sava.cloudmarksmultiplatform.files.describeIoFailure
import to.sava.cloudmarksmultiplatform.files.replaceFileAtomically
import java.awt.Frame
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.WatchService
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** 続けて来たウィンドウを前に出させる知らせを、1 回にまとめる間隔。 */
private val SHOW_WINDOW_DEBOUNCE: Duration = 100.milliseconds

/** ログに書く、ウィンドウを前に出させる知らせのファイルの呼び名。 */
private const val SHOW_WINDOW_FILE_DESCRIPTION = "ウィンドウを前に出させる知らせのファイル"

/** 多重起動を防ぐロックを取ろうとした結果（[acquireInstanceLock]）。 */
sealed interface InstanceLockResult {
    /**
     * ロックを取れた。開いたロックのファイルとロックを持つ。
     *
     * GC で閉じられるとロックが外れるので、[close] するまで参照を保つ。閉じないままプロセスが終わっても、ロックは外れる。
     */
    class Acquired internal constructor(
        private val channel: FileChannel,
        private val lock: FileLock,
    ) : InstanceLockResult {
        /**
         * ロックのファイルを閉じて、ロックを外す。閉じられなければ、理由を [log] に書く。何度呼んでもよく、2 回目からは何もしない。
         *
         * @param log 閉じられなかったときに書く。パスは含めない
         */
        fun close(log: (String) -> Unit) {
            try {
                channel.close()
            } catch (e: IOException) {
                log("多重起動を防ぐロックのファイルを閉じられなかった: ${describeIoFailure(e)}")
            }
        }
    }

    /**
     * ほかのプロセスか、同じプロセスのほかの箇所が、ロックを持っている。ロックのファイルがあるアプリのデータを置くディレクトリを、
     * 既に起動しているアプリが使っている。
     */
    data object AlreadyRunning : InstanceLockResult

    /**
     * ロックのファイルを開けなかったか、ロックを取ろうとして I/O に失敗した。既に起動しているかどうかは分からない。
     *
     * @property reason 後から調べるための短い文。パスは含めない
     */
    class Failed internal constructor(val reason: String) : InstanceLockResult
}

/**
 * 多重起動を防ぐロックのファイル [file] を開いて、OS のロックを取る。親のディレクトリが無ければ作る。
 *
 * 既に起動しているかは、ファイルがあるかではなく、ほかのプロセスがロックを持っているかで決める。ロックを持っていたプロセスが終わっていれば、
 * ファイルが残っていても取れる。ロックのファイルの中身は使わない。
 *
 * 開くこととロックを取ることはブロックする。
 *
 * @param file 多重起動を防ぐロックのファイル（[to.sava.cloudmarksmultiplatform.AppFiles.instanceLockFile]）
 */
fun acquireInstanceLock(file: Path): InstanceLockResult {
    val channel = try {
        Files.createDirectories(file.toAbsolutePath().parent)
        FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    } catch (e: IOException) {
        return InstanceLockResult.Failed(describeIoFailure(e))
    }
    val lock = try {
        channel.tryLock()
    } catch (e: OverlappingFileLockException) {
        null
    } catch (e: IOException) {
        closeWithoutLock(channel)
        return InstanceLockResult.Failed(describeIoFailure(e))
    }
    if (lock == null) {
        closeWithoutLock(channel)
        return InstanceLockResult.AlreadyRunning
    }
    return InstanceLockResult.Acquired(channel, lock)
}

/** ロックを取らなかった [channel] を閉じる。結果はロックを取ろうとした時点で決まっているので、閉じられなくても変えない。 */
private fun closeWithoutLock(channel: FileChannel) {
    try {
        channel.close()
    } catch (e: IOException) {
        // 閉じられなかったハンドルは、プロセスが終わるときに OS が閉じる
    }
}

/**
 * 既に起動しているアプリに、ウィンドウを前に出させる。知らせのファイル [file] を、いまの時刻を中身にして書き換える。
 * 書き換えられなければ、理由を [log] に書く。書き換えはブロックする。
 *
 * @param file ウィンドウを前に出させる知らせのファイル（[to.sava.cloudmarksmultiplatform.AppFiles.showWindowFile]）。親のディレクトリが無ければ作る
 * @param log 書き換えられなかったときに書く。パスは含めない
 */
fun requestShowWindow(file: Path, log: (String) -> Unit) {
    try {
        replaceFileAtomically(file, "${Clock.System.now()}\n".encodeToByteArray())
    } catch (e: IOException) {
        log("${SHOW_WINDOW_FILE_DESCRIPTION}を書き換えられなかった: ${describeIoFailure(e)}")
    }
}

/**
 * ウィンドウを前に出させる知らせのファイル [file] の作成と変更を見張り、知らせを受けるたびに出す Flow。
 * 続けて来た知らせは [SHOW_WINDOW_DEBOUNCE] で 1 回にまとめる。
 *
 * ファイルの親のディレクトリが無ければ作ってから見張る。見張りを始められなければ、理由を [log] に書き、知らせを出さずに終わる。
 * 収集が取り消されたら、見張りを閉じる。
 *
 * @param file ウィンドウを前に出させる知らせのファイル（[to.sava.cloudmarksmultiplatform.AppFiles.showWindowFile]）
 * @param log 見張りを始められなかったときと、見張りを続けられなくなったときに書く。パスは含めない
 */
fun watchShowWindowRequests(file: Path, log: (String) -> Unit): Flow<Unit> =
    watchShowWindowRequests(file, log, onWatching = {})

/**
 * 見張りを開いたことを [onWatching] で受け取れる [watchShowWindowRequests]。
 *
 * @param onWatching 見張りを開いたあと、変化を待ち始める前に、開いた [WatchService] を渡して呼ぶ
 */
internal fun watchShowWindowRequests(
    file: Path,
    log: (String) -> Unit,
    onWatching: (WatchService) -> Unit,
): Flow<Unit> =
    watchFileChanges(file, SHOW_WINDOW_FILE_DESCRIPTION, log, SHOW_WINDOW_DEBOUNCE, onWatching)

/**
 * ウィンドウの状態 [extendedState]（[Frame.getExtendedState] の値）から、最小化だけを解いた状態。知らせを受けてウィンドウを
 * 前に出すときに使う。最大化していたものは、最大化したまま戻る。最小化していなければ、そのまま返す。
 */
fun deiconifiedExtendedState(extendedState: Int): Int = extendedState and Frame.ICONIFIED.inv()
