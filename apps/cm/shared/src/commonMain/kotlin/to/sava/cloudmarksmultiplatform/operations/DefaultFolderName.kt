package to.sava.cloudmarksmultiplatform.operations

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

private val FOLDER_NAME_FORMAT = LocalDateTime.Format {
    year()
    char('-')
    monthNumber()
    char('-')
    day()
    char(' ')
    hour()
    char(':')
    minute()
    char(':')
    second()
}

/**
 * フォルダとして追加するときの名前の既定。[timeZone] のローカル時刻の `YYYY-MM-DD HH:mm:ss`（例：`2026-11-16 21:30:15`）。
 * 小数部は含めない。
 */
fun defaultFolderName(instant: Instant, timeZone: TimeZone = TimeZone.currentSystemDefault()): String =
    FOLDER_NAME_FORMAT.format(instant.toLocalDateTime(timeZone))
