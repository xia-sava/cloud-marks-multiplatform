package to.sava.cloudmarksmultiplatform.update

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 開発版のバージョンに添える、ビルドの時刻の書式。 */
private val BUILD_STAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

/**
 * 開発版のバージョンの表示。開発版のバージョンは既定の値のまま変わらないので、そのままではローカルのビルドが入れ替わったかを
 * 見分けられない。ビルドの時刻 [buildEpochMillis]（エポックからのミリ秒）を、端末のタイムゾーンの時刻で
 * `<バージョン> (dev-yyyyMMdd_HHmmss)` と添える。時刻が分からなければ `<バージョン> (dev)`。
 */
fun devVersionName(versionName: String, buildEpochMillis: Long?): String {
    val stamp = buildEpochMillis
        ?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(BUILD_STAMP_FORMATTER) }
        ?: return "$versionName (dev)"
    return "$versionName (dev-$stamp)"
}
