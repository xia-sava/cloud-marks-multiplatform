package to.sava.cloudmarksmultiplatform.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.favicon.FakeFaviconSource
import to.sava.cloudmarksmultiplatform.favicon.NoFavicons
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CloudMarksControllerFaviconTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // 渡した favicon を返す仕組みを、そのまま公開する
    @Test
    fun exposesTheGivenFaviconSource() = runTest {
        val favicons = FakeFaviconSource()

        val fixture = ControllerFixture(this, temporaryFolder, favicons = favicons)

        assertSame(favicons, fixture.controller.favicons)
    }

    // 渡さなければ、何も取りに行かない NoFavicons
    @Test
    fun faviconSourceDefaultsToNoFavicons() = runTest {
        val fixture = ControllerFixture(this, temporaryFolder)

        assertSame(NoFavicons, fixture.controller.favicons)
    }

    // コントローラを閉じると、閉じられる favicon を返す仕組み（ストア）も閉じる
    @Test
    fun closingClosesTheFaviconSource() = runTest {
        val favicons = FakeFaviconSource()
        val fixture = ControllerFixture(this, temporaryFolder, favicons = favicons)
        assertFalse(favicons.closed)

        fixture.controller.close()

        assertTrue(favicons.closed)
    }

    // 閉じられない favicon を返す仕組み（NoFavicons）でも、コントローラは閉じられる
    @Test
    fun closingWithoutClosableFaviconSourceSucceeds() = runTest {
        ControllerFixture(this, temporaryFolder).controller.close()
    }
}
