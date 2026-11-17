package to.sava.cloudmarksmultiplatform.ui

import to.sava.cloudmarksmultiplatform.browser.Browser
import kotlin.test.Test
import kotlin.test.assertEquals

class BrowserMenuOrderTest {
    private val chrome = Browser("Google Chrome", "c:\\chrome.exe", isDefault = false, opensMultipleItems = true)
    private val edge = Browser("Microsoft Edge", "c:\\msedge.exe", isDefault = false, opensMultipleItems = false)
    private val firefox = Browser("Mozilla Firefox", "c:\\firefox.exe", isDefault = true, opensMultipleItems = true)
    private val brave = Browser("brave", "c:\\brave.exe", isDefault = false, opensMultipleItems = false)

    // 1 つを開くときはどれも押せるので、既定のブラウザを先頭に、残りを大文字小文字によらない名前の順に並べる
    @Test
    fun defaultFirstThenByName() {
        val ordered = browsersInMenuOrder(listOf(edge, firefox, chrome, brave), itemCount = 1)

        assertEquals(listOf(firefox, brave, chrome, edge), ordered)
    }

    // 2 つ以上を開くときは、まとめて開けないブラウザを最後に名前の順で並べる
    @Test
    fun disabledLastWhenOpeningMany() {
        val ordered = browsersInMenuOrder(listOf(edge, firefox, chrome, brave), itemCount = 2)

        assertEquals(listOf(firefox, chrome, brave, edge), ordered)
    }

    // 既定のブラウザでも、まとめて開けないなら最後に並べる
    @Test
    fun disabledDefaultGoesLast() {
        val defaultEdge = edge.copy(isDefault = true)

        val ordered = browsersInMenuOrder(listOf(defaultEdge, firefox.copy(isDefault = false), chrome), itemCount = 3)

        assertEquals(listOf(chrome, firefox.copy(isDefault = false), defaultEdge), ordered)
    }
}
