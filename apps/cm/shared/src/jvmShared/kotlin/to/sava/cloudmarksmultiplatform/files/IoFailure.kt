package to.sava.cloudmarksmultiplatform.files

import java.io.IOException
import java.nio.file.FileSystemException

/**
 * I/O の失敗を、後から調べるための短い文にする。
 *
 * 例外の種類と、ファイルシステムの失敗ならその理由を使う。ファイルシステムの失敗のメッセージはパスを含むため使わない。
 */
internal fun describeIoFailure(e: IOException): String =
    listOfNotNull(e::class.simpleName, if (e is FileSystemException) e.reason else e.message)
        .joinToString(": ")
