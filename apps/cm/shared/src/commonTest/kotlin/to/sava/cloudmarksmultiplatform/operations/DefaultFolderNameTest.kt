package to.sava.cloudmarksmultiplatform.operations

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class DefaultFolderNameTest {
    private val tokyo = TimeZone.of("Asia/Tokyo")
    private val newYork = TimeZone.of("America/New_York")

    // ローカル時刻の YYYY-MM-DD HH:mm:ss。UTC と他のタイムゾーンで、日付をまたぐ場合とゼロ埋めを確かめる
    @Test
    fun formatsLocalTimeInGivenTimeZone() {
        val cases = listOf(
            Triple("2026-11-16T12:30:15Z", TimeZone.UTC, "2026-11-16 12:30:15"),
            Triple("2026-11-16T12:30:15Z", tokyo, "2026-11-16 21:30:15"),
            Triple("2026-11-16T20:00:00Z", tokyo, "2026-11-17 05:00:00"),
            Triple("2026-11-16T03:00:00Z", newYork, "2026-11-15 22:00:00"),
            Triple("2026-01-02T03:04:05Z", TimeZone.UTC, "2026-01-02 03:04:05"),
            Triple("2026-12-31T15:00:00Z", tokyo, "2027-01-01 00:00:00"),
        )

        cases.forEach { (instant, timeZone, expected) ->
            assertEquals(expected, defaultFolderName(Instant.parse(instant), timeZone), "$instant $timeZone")
        }
    }

    // うるう日：うるう年の 2 月 29 日は存在し、平年の同じ時刻は 3 月 1 日になる
    @Test
    fun handlesLeapDay() {
        assertEquals("2024-02-29 05:00:00", defaultFolderName(Instant.parse("2024-02-28T20:00:00Z"), tokyo))
        assertEquals("2023-03-01 05:00:00", defaultFolderName(Instant.parse("2023-02-28T20:00:00Z"), tokyo))
    }

    // 小数部（ミリ秒）は名前に出ない
    @Test
    fun ignoresFractionOfSecond() {
        assertEquals(
            "2026-11-16 12:30:15",
            defaultFolderName(Instant.parse("2026-11-16T12:30:15.999Z"), TimeZone.UTC),
        )
    }
}
