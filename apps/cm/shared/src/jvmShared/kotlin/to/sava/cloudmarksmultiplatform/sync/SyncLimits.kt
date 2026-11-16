package to.sava.cloudmarksmultiplatform.sync

import to.sava.cloudmarksmultiplatform.remote.PutObjectResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/** 目録とフォルダのファイルの `Content-Type`。 */
internal const val STORAGE_CONTENT_TYPE = "application/gzip"

/**
 * 1 つの PUT を送り直す回数の上限。フォルダのファイルで、ファイルの ID を振り直して置き直すのもこの回数に数える。
 * 目録の PUT を送ったあとの目録の GET を、S3 につながらないときに送り直す回数の上限も、これを使う。
 *
 * - `409` は同時に走った書き込みとの衝突で、その書き込みが終われば解けるので、間を空けて数回送り直せば足りる
 * - 結果が分からないものは、SDK が `5xx` やタイムアウトを自分で送り直したうえでなお続いたもので、
 *   さらに続けて起きることはまれ
 *
 * 上限を超えたときの失敗は [resendsExhausted] のとおり。
 */
internal const val MAX_RESENDS = 3

/**
 * 1 回の書き込みで、操作を適用し直す回数の上限。
 *
 * 適用し直すのは、ほかの書き手が先に目録を書き換えていたとき、目録の PUT が届かなかったとき、手順 3 から手順 4 までが
 * 長すぎたとき。どれも続けて起きることはまれ。
 */
internal const val MAX_REAPPLIES = 3

/**
 * 送り直しと適用し直しの前に空ける間の、1 回目の長さ。2 回目からは倍ずつ延ばす。
 *
 * 同時に走った書き込みが終わるのを待つための間で、1 つの PUT の送り直しを上限まで繰り返しても、待ちは合わせて 3.5 秒に収まる。
 */
internal val FIRST_RETRY_DELAY = 500.milliseconds

/** 手順 3 の最初の PUT から手順 4 を送るまでの経過の上限。古いファイルの削除の猶予（24 時間）より十分に短い。 */
internal val MAX_FILES_TO_MANIFEST = 1.hours

/**
 * 古いファイルの削除の猶予。目録から参照されなくなったフォルダのファイルを、最終更新からこの長さを過ぎるまで消さない。
 * 書き込みの手順 3 でファイルを置いてから手順 4 で目録を書き換えるまで（[MAX_FILES_TO_MANIFEST] 以内）のファイルを消さないための長さ。
 */
internal val STALE_FILE_GRACE_PERIOD = 24.hours

/** [count] 回目（1 から数える）の送り直し・適用し直しの前に空ける間。 */
internal fun retryDelay(count: Int): Duration = FIRST_RETRY_DELAY * (1 shl (count - 1))

/**
 * PUT の送り直しが上限を超えたときの失敗。最後の応答が結果の分からないものなら、S3 につながらないとする。
 * `409` と `412` なら、上限を超えたとする。
 */
internal fun resendsExhausted(last: PutObjectResult): SyncFailure =
    if (last is PutObjectResult.Unknown) SyncFailure.Unreachable(last.detail) else SyncFailure.Busy
