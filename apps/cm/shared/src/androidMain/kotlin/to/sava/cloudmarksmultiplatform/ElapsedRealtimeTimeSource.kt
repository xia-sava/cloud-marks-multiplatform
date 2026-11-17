package to.sava.cloudmarksmultiplatform

import android.os.SystemClock
import kotlin.time.AbstractLongTimeSource
import kotlin.time.DurationUnit

/**
 * 端末の休止中も進むモノトニッククロック。端末を起動してからの経過（`SystemClock.elapsedRealtimeNanos()`）を読む。
 * `TimeSource.Monotonic`（`System.nanoTime()`）は、Android では休止中に止まる。
 */
object ElapsedRealtimeTimeSource : AbstractLongTimeSource(DurationUnit.NANOSECONDS) {
    override fun read(): Long = SystemClock.elapsedRealtimeNanos()
}
