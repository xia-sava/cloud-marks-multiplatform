package to.sava.cloudmarksmultiplatform.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

// runCurrent が ExperimentalCoroutinesApi であるため。
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundReturnsTest {
    /** [source] の知らせを集め始め、受け取った知らせの数を返す関数を返す。 */
    private fun TestScope.collecting(source: ForegroundReturns): () -> Int {
        var count = 0
        backgroundScope.launch { source.returns.collect { count++ } }
        runCurrent()
        return { count }
    }

    // 初めての onStart（起動して画面を出したとき）は知らせず、2 回目からは呼ぶたびに知らせる
    @Test
    fun firstStartIsNotNotified() = runTest {
        val returns = ForegroundReturns()
        val received = collecting(returns)

        returns.onActivityStart()
        runCurrent()
        assertEquals(0, received())

        returns.onActivityStart()
        runCurrent()
        assertEquals(1, received())

        returns.onActivityStart()
        runCurrent()
        assertEquals(2, received())
    }

    // 集めている側がまだ受け取っていない知らせは、1 つにまとめる
    @Test
    fun pendingReturnsAreMerged() = runTest {
        val returns = ForegroundReturns()
        val received = collecting(returns)
        returns.onActivityStart()

        returns.onActivityStart()
        returns.onActivityStart()
        runCurrent()

        assertEquals(1, received())
    }

    // 集めている側が無いあいだの知らせは捨て、あとから集め始めても受け取らない
    @Test
    fun returnsWithoutCollectorAreDropped() = runTest {
        val returns = ForegroundReturns()
        returns.onActivityStart()
        returns.onActivityStart()

        val received = collecting(returns)
        runCurrent()

        assertEquals(0, received())
    }
}
