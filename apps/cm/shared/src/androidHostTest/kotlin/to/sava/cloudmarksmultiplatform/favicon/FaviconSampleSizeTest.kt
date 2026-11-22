package to.sava.cloudmarksmultiplatform.favicon

import kotlin.test.Test
import kotlin.test.assertEquals

class FaviconSampleSizeTest {
    // inSampleSize は、間引いたあとの長辺が 64 画素を下回らない、いちばん大きい 2 の冪。長辺が 128 画素に満たなければ間引かない
    @Test
    fun sampleSizeKeepsLongerSideAtLeastLimit() {
        listOf(
            (16 to 16) to 1,
            (64 to 64) to 1,
            (127 to 50) to 1,
            (128 to 128) to 2,
            (50 to 255) to 2,
            (256 to 256) to 4,
            (512 to 256) to 8,
            (1000 to 10) to 8,
            (1024 to 1024) to 16,
        ).forEach { (size, expected) ->
            assertEquals(expected, faviconSampleSize(size.first, size.second), size.toString())
        }
    }
}
