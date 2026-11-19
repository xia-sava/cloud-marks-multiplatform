package to.sava.cloudmarksmultiplatform.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import to.sava.cloudmarksmultiplatform.AppFiles
import to.sava.cloudmarksmultiplatform.files.describeIoFailure
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchService
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** 続けて来たファイルの変化を、1 回の知らせにまとめる間隔。最後の変化からこれだけ待っても次が来なければ、知らせを出す。 */
private val STORAGE_CHANGE_DEBOUNCE: Duration = 300.milliseconds

/** ログに書く、[AppFiles.storageChangedFile] の呼び名。 */
private const val STORAGE_CHANGED_FILE_DESCRIPTION = "S3 へ書いたことを知らせるファイル"

/**
 * [AppFiles.storageChangedFile] の作成と変更を見張り、変わるたびに知らせを出す Flow。
 *
 * Native Messaging のホストが S3 へ書いたあとに、そのファイルを置き換える。続けて来た変化は [STORAGE_CHANGE_DEBOUNCE] でまとめて 1 回にする。
 * ファイルの親のディレクトリが無ければ作ってから見張る。見張りを始められなければ、理由を [log] に書き、知らせを出さずに終わる。
 * 収集が取り消されたら、見張りを閉じる。
 *
 * @param log 見張りを始められなかったときと、見張りを続けられなくなったときに書く。パスは含めない
 */
fun watchStorageChanges(appFiles: AppFiles, log: (String) -> Unit): Flow<Unit> =
    watchFileChanges(appFiles.storageChangedFile, STORAGE_CHANGED_FILE_DESCRIPTION, log)

/**
 * [file] の作成と変更を見張り、変わるたびに知らせを出す Flow（[watchStorageChanges]、
 * [to.sava.cloudmarksmultiplatform.window.watchShowWindowRequests]）。同じディレクトリの別の名前のファイルの変化は知らせない。
 *
 * 見張りを開くことと、変化を待つこと、[log] を呼ぶことは [Dispatchers.IO] で行う。
 *
 * @param fileDescription ログに書く、見張るファイルの呼び名（「S3 へ書いたことを知らせるファイル」など）
 * @param debounce 続けて来た変化を 1 回にまとめる間隔
 * @param onWatching 見張りを開いたあと、変化を待ち始める前に、開いた [WatchService] を渡して呼ぶ
 */
@OptIn(FlowPreview::class)
internal fun watchFileChanges(
    file: Path,
    fileDescription: String,
    log: (String) -> Unit,
    debounce: Duration = STORAGE_CHANGE_DEBOUNCE,
    onWatching: (WatchService) -> Unit = {},
): Flow<Unit> =
    fileChanges(file, fileDescription, log, onWatching).flowOn(Dispatchers.IO).debounce(debounce)

/** [file] の作成と変更のたびに知らせを出す。まとめることはしない。 */
private fun fileChanges(
    file: Path,
    fileDescription: String,
    log: (String) -> Unit,
    onWatching: (WatchService) -> Unit,
): Flow<Unit> = flow {
    val directory = file.toAbsolutePath().parent
    val watchService = openWatchService(directory, fileDescription, log) ?: return@flow
    watchService.use {
        onWatching(watchService)
        while (true) {
            val key = runInterruptible { watchService.take() }
            // 溢れたときは、どの変化が失われたか分からないので、変わったとする
            val changed = key.pollEvents().any { event -> event.kind() == OVERFLOW || event.context() == file.fileName }
            val watching = key.reset()
            if (changed) emit(Unit)
            if (!watching) {
                log("${fileDescription}の見張りを続けられなくなった")
                return@flow
            }
        }
    }
}

/**
 * [directory] を作ってから、その中の作成と変更を見張る [WatchService] を開く。開けなければ、見張るファイルの呼び名 [fileDescription] と
 * 理由を [log] に書き、null を返す。
 */
private fun openWatchService(directory: Path, fileDescription: String, log: (String) -> Unit): WatchService? =
    try {
        Files.createDirectories(directory)
        directory.fileSystem.newWatchService().apply {
            try {
                directory.register(this, ENTRY_CREATE, ENTRY_MODIFY)
            } catch (e: Throwable) {
                close()
                throw e
            }
        }
    } catch (e: IOException) {
        log("${fileDescription}を見張れなかった: ${describeIoFailure(e)}")
        null
    }
