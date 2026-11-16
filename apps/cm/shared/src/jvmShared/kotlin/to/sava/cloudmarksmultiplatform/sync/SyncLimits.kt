package to.sava.cloudmarksmultiplatform.sync

import to.sava.cloudmarksmultiplatform.remote.PutObjectResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/** マニフェストと項目ファイルの `Content-Type`。 */
internal const val STORAGE_CONTENT_TYPE = "application/gzip"

/**
 * 1 つの PUT を再送する回数の上限。項目ファイルで、ファイルの ID を振り直して置き直すのもこの回数に数える。
 * マニフェストの PUT を送ったあとのマニフェストの GET を、S3 につながらないときに再送する回数の上限も、これを使う。
 *
 * - `409` は同時に走った書き込みとの衝突で、その書き込みが終われば解けるので、間を空けて数回再送すれば足りる
 * - 結果が不明のものは、SDK が `5xx` やタイムアウトを自分で再送したうえでなお続いたもので、
 *   さらに続けて起きることはまれ
 *
 * 上限を超えたときの失敗は [resendsExhausted] のとおり。
 */
internal const val MAX_RESENDS = 3

/**
 * 1 回の書き込みで、操作を再適用する回数の上限。
 *
 * 再適用するのは、ほかの書き手が先にマニフェストを書き換えていたとき、マニフェストの PUT が届かなかったとき、
 * 手順 3 から手順 4 までが長すぎたとき。どれも続けて起きることはまれ。
 */
internal const val MAX_REAPPLIES = 3

/**
 * 再送と再適用の前に空ける間の、1 回目の長さ。2 回目からは倍ずつ延ばす。
 *
 * 同時に走った書き込みが終わるのを待つための間で、1 つの PUT の再送を上限まで繰り返しても、待ちは合わせて 3.5 秒に収まる。
 */
internal val FIRST_RETRY_DELAY = 500.milliseconds

/** 手順 3 の最初の PUT から手順 4 を送るまでの経過の上限。古いファイルの削除の猶予（24 時間）より十分に短い。 */
internal val MAX_FILES_TO_MANIFEST = 1.hours

/**
 * 古いファイルの削除の猶予。マニフェストから参照されなくなった項目ファイルを、最終更新からこの長さを過ぎるまで消さない。
 * 書き込みの手順 3 でファイルを置いてから手順 4 でマニフェストを書き換えるまで（[MAX_FILES_TO_MANIFEST] 以内）の
 * ファイルを消さないための長さ。
 */
internal val STALE_FILE_GRACE_PERIOD = 24.hours

/** [count] 回目（1 から数える）の再送・再適用の前に空ける間。 */
internal fun retryDelay(count: Int): Duration = FIRST_RETRY_DELAY * (1 shl (count - 1))

/**
 * PUT の再送が上限を超えたときの失敗。最後の応答が結果の不明なものなら、S3 につながらないとする。
 * `409` と `412` なら、上限を超えたとする。
 */
internal fun resendsExhausted(last: PutObjectResult): SyncFailure =
    if (last is PutObjectResult.Unknown) SyncFailure.Unreachable(last.detail) else SyncFailure.Busy
