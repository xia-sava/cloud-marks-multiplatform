package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.favicon.FakeFaviconSource
import to.sava.cloudmarksmultiplatform.favicon.Favicon
import to.sava.cloudmarksmultiplatform.favicon.testImage
import to.sava.cloudmarksmultiplatform.sync.ROOT_ITEMS
import to.sava.cloudmarksmultiplatform.sync.item
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** favicon の描く一辺。 */
private val FAVICON_DRAW_SIZE = 16.dp

/** 行の左の、favicon を置く場所の幅。フォルダの行のアイコンと同じ。 */
private val FAVICON_SLOT_SIZE = 24.dp

/** favicon を置く場所とタイトルの間隔。 */
private val FAVICON_TITLE_GAP = 12.dp

/** 縦の中央の位置の比べ方で許す、画素の違い。テキストの行の高さの端数で、1 画素までずれる。 */
private const val CENTER_TOLERANCE = 1f

/** どの項目のタイトルにも含まれる語。 */
private const val ALL_TITLES = "title"

/** 項目 [n]（[item]）の URL。favicon を返す仕組みに渡す。 */
private fun urlOf(n: Int): String = item(n).url

/** ルートフォルダの項目すべての favicon の状態を [favicon] にした、偽の favicon を返す仕組み。 */
private fun faviconsOfRootItems(favicon: Favicon): FakeFaviconSource =
    FakeFaviconSource().also { source -> ROOT_ITEMS.forEach { source.set(it.url, favicon) } }

/** 画像の favicon（[FAVICON_IMAGE_TAG]）の要素。行が子の意味を 1 つにまとめるので、まとめない木で探す。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.faviconImages() = onAllNodesWithTag(FAVICON_IMAGE_TAG, useUnmergedTree = true)

/** 地球儀のアイコン（[FAVICON_GLOBE_TAG]）の要素。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.faviconGlobes() = onAllNodesWithTag(FAVICON_GLOBE_TAG, useUnmergedTree = true)

/** マージせずに見た、文が [text] の要素のうち [index] 番目の、左端（根の座標）。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.textLeft(text: String, index: Int = 0): Float =
    onAllNodesWithText(text, useUnmergedTree = true)[index].fetchSemanticsNode().boundsInRoot.left

/** マージせずに見た、文が [text] の要素のうち [index] 番目の、縦の中央（根の座標）。 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.textCenterY(text: String, index: Int = 0): Float =
    onAllNodesWithText(text, useUnmergedTree = true)[index].fetchSemanticsNode().boundsInRoot.center.y

@OptIn(ExperimentalTestApi::class)
class ItemFaviconScreenTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // 取れた favicon（Found）は、項目の行ごとに画像として、16dp で描く。地球儀は出さない
    @Test
    fun foundFaviconDrawsImageForEachItem() {
        val favicons = faviconsOfRootItems(Favicon.Found(testImage("found")))

        runCloudMarksScreenTest(temporaryFolder, favicons = favicons) {
            faviconImages().assertCountEquals(ROOT_ITEMS.size)
            faviconGlobes().assertCountEquals(0)
            faviconImages()[0].assertWidthIsEqualTo(FAVICON_DRAW_SIZE).assertHeightIsEqualTo(FAVICON_DRAW_SIZE)
        }
    }

    // favicon が無い（Missing）項目の行には、16dp の地球儀を出す。画像は出さない
    @Test
    fun missingFaviconDrawsGlobeForEachItem() {
        val favicons = faviconsOfRootItems(Favicon.Missing)

        runCloudMarksScreenTest(temporaryFolder, favicons = favicons) {
            faviconGlobes().assertCountEquals(ROOT_ITEMS.size)
            faviconImages().assertCountEquals(0)
            faviconGlobes()[0].assertWidthIsEqualTo(FAVICON_DRAW_SIZE).assertHeightIsEqualTo(FAVICON_DRAW_SIZE)
        }
    }

    // 読み込み中（Loading）は、画像も地球儀も描かない
    @Test
    fun loadingFaviconDrawsNothing() {
        val favicons = faviconsOfRootItems(Favicon.Loading)

        runCloudMarksScreenTest(temporaryFolder, favicons = favicons) {
            faviconImages().assertCountEquals(0)
            faviconGlobes().assertCountEquals(0)
        }
    }

    // 何も渡さなければ（NoFavicons。どの URL も Missing）、地球儀を出す
    @Test
    fun defaultSourceDrawsGlobe() = runCloudMarksScreenTest(temporaryFolder) {
        faviconGlobes().assertCountEquals(ROOT_ITEMS.size)
        faviconImages().assertCountEquals(0)
    }

    // 行は、自分の URL の favicon の状態を描く。ほかの URL の状態は影響せず、自分の URL の状態があとから替わったら、描き直す
    @Test
    fun eachRowDrawsFaviconOfItsOwnUrl() {
        val favicons = FakeFaviconSource()
        favicons.set(urlOf(1), Favicon.Found(testImage("one")))

        runCloudMarksScreenTest(temporaryFolder, favicons = favicons) {
            faviconImages().assertCountEquals(1)
            faviconGlobes().assertCountEquals(0)

            favicons.set(urlOf(2), Favicon.Missing)
            waitForIdle()
            faviconImages().assertCountEquals(1)
            faviconGlobes().assertCountEquals(1)

            favicons.set(urlOf(1), Favicon.Missing)
            waitForIdle()
            faviconImages().assertCountEquals(0)
            faviconGlobes().assertCountEquals(2)
        }
    }

    // リストの項目のタイトルの始まりは、同じリストのフォルダの名前の始まりと揃う（favicon を置く場所が、フォルダのアイコンと同じ幅）
    @Test
    fun listItemTitleAlignsWithFolderName() {
        val favicons = faviconsOfRootItems(Favicon.Found(testImage("found")))

        runCloudMarksScreenTest(temporaryFolder, favicons = favicons) {
            // ツリーの行が先に並ぶので、リストの行は 2 番目
            assertEquals(textLeft("A", index = 1), textLeft(item(1).title))
        }
    }

    // favicon は、タイトルの左の 24dp の場所の中央（場所の左端から 4dp）に置く。縦の中央は、タイトルと揃う
    @Test
    fun faviconSitsLeftOfTitleCenteredInSlot() {
        val favicons = FakeFaviconSource().also { it.set(urlOf(1), Favicon.Found(testImage("one"))) }

        runCloudMarksScreenTest(temporaryFolder, favicons = favicons) {
            val image = faviconImages()[0].fetchSemanticsNode().boundsInRoot
            val titleLeft = textLeft(item(1).title)
            val slotLeft = titleLeft - FAVICON_TITLE_GAP.value - FAVICON_SLOT_SIZE.value

            assertEquals(slotLeft + (FAVICON_SLOT_SIZE.value - FAVICON_DRAW_SIZE.value) / 2, image.left)
            assertEquals(textCenterY(item(1).title), image.center.y, CENTER_TOLERANCE)
        }
    }

    // 無いあいだ（Missing）は、一定の間隔で favicon を返す仕組みに問い直す。間隔より前には問い直さない
    @Test
    fun missingFaviconIsRecheckedEveryInterval() {
        val favicons = faviconsOfRootItems(Favicon.Missing)

        runCloudMarksScreenTest(temporaryFolder, favicons = favicons) {
            val initial = favicons.callsOf(urlOf(1))

            mainClock.advanceTimeBy(MISSING_FAVICON_RECHECK_INTERVAL.inWholeMilliseconds / 2)
            waitForIdle()
            assertEquals(initial, favicons.callsOf(urlOf(1)))

            mainClock.advanceTimeBy(MISSING_FAVICON_RECHECK_INTERVAL.inWholeMilliseconds)
            waitForIdle()
            assertTrue(favicons.callsOf(urlOf(1)) > initial)
        }
    }

    // 取れている（Found）あいだは、時間がたっても問い直さない
    @Test
    fun foundFaviconIsNotRechecked() {
        val favicons = faviconsOfRootItems(Favicon.Found(testImage("found")))

        runCloudMarksScreenTest(temporaryFolder, favicons = favicons) {
            val initial = favicons.callsOf(urlOf(1))

            mainClock.advanceTimeBy(MISSING_FAVICON_RECHECK_INTERVAL.inWholeMilliseconds * 3)
            waitForIdle()

            assertEquals(initial, favicons.callsOf(urlOf(1)))
        }
    }

    // 検索の結果にも favicon を描き、フォルダの表記の行は、タイトルの始まりに揃える
    @Test
    fun searchResultsDrawFaviconAndIndentCaption() {
        val favicons = FakeFaviconSource().also { source -> (1..3).forEach { source.set(urlOf(it), Favicon.Missing) } }

        runCloudMarksScreenTest(temporaryFolder, favicons = favicons) {
            search(ALL_TITLES)

            faviconGlobes().assertCountEquals(3)
            // 項目 3 は A のフォルダにあり、フォルダの表記の行の文は「A」
            val caption = onAllNodesWithText("A", useUnmergedTree = true).fetchSemanticsNodes().last().boundsInRoot.left
            assertEquals(textLeft(item(3).title), caption)
        }
    }
}
