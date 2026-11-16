package to.sava.cloudmarksmultiplatform.files

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** 書くときの一時ファイルの名前の末尾。 */
private const val TEMPORARY_FILE_SUFFIX = ".tmp"

/**
 * 同じディレクトリの一時ファイルに [bytes] を書いてから、[path] を置き換える。失敗したら一時ファイルを消す。
 *
 * 一時ファイルの中身は、置き換える前にディスクへ書き出す。親のディレクトリが無ければ作る。
 * 置き換えは原子的なので、ほかのプロセスが [path] を読んでも、置き換える前か後の中身のどちらかが読める。
 * 書き込みはブロックする。呼び出し側が I/O 用のスレッドで呼ぶ。
 *
 * Windows では、置き換え先を別のハンドルが開いていると、置き換えが [java.nio.file.AccessDeniedException] で失敗しうる。
 * そのとき [path] は置き換える前の中身のままで壊れず、一時ファイルも残らない。
 *
 * @throws IOException 書けなかった。一時ファイルを消せなかったときは、その失敗が suppressed に添えられる
 */
internal fun replaceFileAtomically(path: Path, bytes: ByteArray) {
    val directory = path.toAbsolutePath().parent
    Files.createDirectories(directory)
    val temporary = Files.createTempFile(directory, "${path.fileName}.", TEMPORARY_FILE_SUFFIX)
    try {
        FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
            ByteBuffer.wrap(bytes).let { buffer ->
                while (buffer.hasRemaining()) channel.write(buffer)
            }
            channel.force(true)
        }
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: IOException) {
        discard(temporary, e)
        throw e
    }
}

/** 一時ファイルを消す。消せなければ、その失敗を [cause] に添える。 */
private fun discard(temporary: Path, cause: IOException) {
    try {
        Files.deleteIfExists(temporary)
    } catch (e: IOException) {
        cause.addSuppressed(e)
    }
}
