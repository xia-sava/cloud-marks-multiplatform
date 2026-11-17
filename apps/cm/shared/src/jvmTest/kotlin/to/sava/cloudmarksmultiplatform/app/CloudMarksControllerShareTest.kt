package to.sava.cloudmarksmultiplatform.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.browse.SelectionMode
import to.sava.cloudmarksmultiplatform.browser.BrowserOpenResult
import to.sava.cloudmarksmultiplatform.browser.FakeBrowserOpener
import to.sava.cloudmarksmultiplatform.browser.FakeItemSharer
import to.sava.cloudmarksmultiplatform.browser.ItemShareResult
import to.sava.cloudmarksmultiplatform.sync.ROOT_ITEMS
import to.sava.cloudmarksmultiplatform.sync.item
import to.sava.cloudmarksmultiplatform.sync.seed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// runCurrent が ExperimentalCoroutinesApi であるため。
@OptIn(ExperimentalCoroutinesApi::class)
class CloudMarksControllerShareTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** マニフェストと保存済みの設定を置いて起動したフィクスチャ。 */
    private fun TestScope.started(itemSharer: FakeItemSharer? = FakeItemSharer()): ControllerFixture {
        val fixture = ControllerFixture(
            this,
            temporaryFolder,
            browserOpener = FakeBrowserOpener(browsers = emptyList()),
            itemSharer = itemSharer,
        )
        fixture.store.seed()
        fixture.storeSettings()
        fixture.controller.start()
        runCurrent()
        return fixture
    }

    /** ほかのアプリで開くことと、共有すること。 */
    private val handOvers: List<Pair<String, (CloudMarksController) -> Unit>> = listOf(
        "ほかのアプリで開く" to CloudMarksController::openSelectedItemInOtherApp,
        "共有" to CloudMarksController::shareSelectedItem,
    )

    // 項目をほかのアプリで開く仕組みを持つかは、渡した仕組みのとおりに状態に出す（その他のオプションのボタンを出すかを決める）
    @Test
    fun sharesItemsFollowsSharer() = runTest {
        assertTrue(ControllerFixture(this, temporaryFolder, itemSharer = FakeItemSharer()).state.sharesItems)
        assertFalse(ControllerFixture(this, temporaryFolder).state.sharesItems)
    }

    // 選んでいる 1 つの項目の URL を、ほかのアプリで開き、選ぶのをやめる
    @Test
    fun openInOtherAppUsesSelectedItemAndClearsSelection() = runTest {
        val fixture = started()
        fixture.controller.selectItem(ROOT_ITEMS[1].id, SelectionMode.TOGGLE)

        fixture.controller.openSelectedItemInOtherApp()
        runCurrent()

        assertEquals(listOf(ROOT_ITEMS[1].url), fixture.sharer.openedUrls)
        assertEquals(emptyList(), fixture.sharer.shared)
        assertEquals(emptySet(), fixture.state.browse.selectedItemIds)
        assertNull(fixture.state.browserNotice)
    }

    // 選んでいる 1 つの項目の URL とタイトルを共有し、選ぶのをやめる
    @Test
    fun shareUsesSelectedItemAndClearsSelection() = runTest {
        val fixture = started()
        fixture.controller.selectItem(ROOT_ITEMS[0].id, SelectionMode.TOGGLE)

        fixture.controller.shareSelectedItem()
        runCurrent()

        assertEquals(listOf(ROOT_ITEMS[0].url to ROOT_ITEMS[0].title), fixture.sharer.shared)
        assertEquals(emptyList(), fixture.sharer.openedUrls)
        assertEquals(emptySet(), fixture.state.browse.selectedItemIds)
    }

    // 受け取れるアプリが無ければ、そのことを知らせる。ほかの理由で出せなければ、出せなかったことを知らせる。
    // 渡し始めるときに、前に知らせたことを消す
    @Test
    fun failuresAreNoticed() = runTest {
        val cases = listOf(
            ItemShareResult.NoApp to BrowserNotice.NoAppForItem,
            ItemShareResult.Failed to BrowserNotice.ShareFailed,
            ItemShareResult.Shown to null,
        )
        handOvers.forEach { (name, handOver) ->
            cases.forEach { (result, notice) ->
                val fixture = started()
                fixture.browserOpener.defaultResult = BrowserOpenResult.LaunchFailed(detail = null)
                fixture.controller.openItem(ROOT_ITEMS[1].id)
                runCurrent()
                assertEquals(BrowserNotice.DefaultBrowserLaunchFailed, fixture.state.browserNotice)
                fixture.sharer.result = result
                fixture.controller.selectItem(ROOT_ITEMS[0].id, SelectionMode.TOGGLE)

                handOver(fixture.controller)
                runCurrent()

                assertEquals(notice, fixture.state.browserNotice, "$name, $result")
            }
        }
    }

    // 選んでいる項目が 1 つだけでなければ、何もせず、選んでいる項目も変えない
    @Test
    fun nothingHappensUnlessExactlyOneIsSelected() = runTest {
        handOvers.forEach { (name, handOver) ->
            val fixture = started()

            handOver(fixture.controller)
            ROOT_ITEMS.forEach { fixture.controller.selectItem(it.id, SelectionMode.TOGGLE) }
            handOver(fixture.controller)
            runCurrent()

            assertEquals(emptyList(), fixture.sharer.openedUrls, name)
            assertEquals(emptyList(), fixture.sharer.shared, name)
            assertEquals(ROOT_ITEMS.map { it.id }.toSet(), fixture.state.browse.selectedItemIds, name)
        }
    }

    // 項目を指定してほかのアプリで開くと、選んでいる項目ではなく指定した項目の URL を開き、選んでいる項目は変えない。
    // 一覧を出せたら、その項目を最後に開いた項目として覚える
    @Test
    fun openItemInOtherAppUsesGivenItemAndKeepsSelection() = runTest {
        val fixture = started()
        fixture.controller.selectItem(ROOT_ITEMS[0].id, SelectionMode.TOGGLE)

        fixture.controller.openItemInOtherApp(ROOT_ITEMS[1].id)
        runCurrent()

        assertEquals(listOf(ROOT_ITEMS[1].url), fixture.sharer.openedUrls)
        assertEquals(setOf(ROOT_ITEMS[0].id), fixture.state.browse.selectedItemIds)
        assertEquals(ROOT_ITEMS[1].id, fixture.state.lastOpenedItemId)
    }

    // 項目を指定して共有すると、選んでいる項目ではなく指定した項目の URL とタイトルを渡し、選んでいる項目も、最後に開いた項目も変えない
    @Test
    fun shareItemUsesGivenItemAndKeepsSelection() = runTest {
        val fixture = started()
        fixture.controller.selectItem(ROOT_ITEMS[1].id, SelectionMode.TOGGLE)

        fixture.controller.shareItem(ROOT_ITEMS[0].id)
        runCurrent()

        assertEquals(listOf(ROOT_ITEMS[0].url to ROOT_ITEMS[0].title), fixture.sharer.shared)
        assertEquals(setOf(ROOT_ITEMS[1].id), fixture.state.browse.selectedItemIds)
        assertNull(fixture.state.lastOpenedItemId)
    }

    // 項目を指定して渡すときも、受け取れるアプリが無いか、ほかの理由で出せなければ知らせる
    @Test
    fun itemHandOverFailuresAreNoticed() = runTest {
        val handOvers: List<Pair<String, (CloudMarksController) -> Unit>> = listOf(
            "ほかのアプリで開く" to { it.openItemInOtherApp(ROOT_ITEMS[0].id) },
            "共有" to { it.shareItem(ROOT_ITEMS[0].id) },
        )
        val cases = listOf(ItemShareResult.NoApp to BrowserNotice.NoAppForItem, ItemShareResult.Failed to BrowserNotice.ShareFailed)
        handOvers.forEach { (name, handOver) ->
            cases.forEach { (result, notice) ->
                val fixture = started()
                fixture.sharer.result = result

                handOver(fixture.controller)
                runCurrent()

                assertEquals(notice, fixture.state.browserNotice, "$name, $result")
            }
        }
    }

    // 開いているフォルダの読めた項目に無い ID を指定したときと、項目をほかのアプリで開く仕組みが無いときは、何もしない
    @Test
    fun itemHandOverNeedsLoadedItemAndSharer() = runTest {
        val withSharer = started()
        withSharer.controller.openItemInOtherApp(item(99).id)
        withSharer.controller.shareItem(item(99).id)
        runCurrent()
        assertEquals(emptyList(), withSharer.sharer.openedUrls)
        assertEquals(emptyList(), withSharer.sharer.shared)

        val withoutSharer = started(itemSharer = null)
        withoutSharer.controller.openItemInOtherApp(ROOT_ITEMS[0].id)
        withoutSharer.controller.shareItem(ROOT_ITEMS[0].id)
        runCurrent()
        assertNull(withoutSharer.state.browserNotice)
        assertNull(withoutSharer.state.lastOpenedItemId)
    }

    // 項目をほかのアプリで開く仕組みが無ければ、何もせず、選んでいる項目も変えない
    @Test
    fun nothingHappensWithoutSharer() = runTest {
        handOvers.forEach { (name, handOver) ->
            val fixture = started(itemSharer = null)
            fixture.controller.selectItem(ROOT_ITEMS[0].id, SelectionMode.TOGGLE)

            handOver(fixture.controller)
            runCurrent()

            assertEquals(setOf(ROOT_ITEMS[0].id), fixture.state.browse.selectedItemIds, name)
            assertNull(fixture.state.browserNotice, name)
        }
    }
}
