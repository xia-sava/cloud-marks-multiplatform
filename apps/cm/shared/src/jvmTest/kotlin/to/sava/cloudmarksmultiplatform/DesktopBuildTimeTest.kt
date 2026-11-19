package to.sava.cloudmarksmultiplatform

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 置いたファイルの更新時刻（エポックからのミリ秒）。 */
private const val MODIFIED = 1_700_000_000_000L

/** [MODIFIED] より新しい更新時刻。 */
private const val NEWER = MODIFIED + 60_000L

/** [NEWER] よりさらに新しい更新時刻。 */
private const val NEWEST = NEWER + 60_000L

class DesktopBuildTimeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** [directory] の下の [relative] に、更新時刻が [millis] のファイルを置く。 */
    private fun place(directory: Path, relative: String, millis: Long): Path =
        directory.resolve(relative).also { file ->
            Files.createDirectories(file.parent)
            Files.writeString(file, "")
            Files.setLastModifiedTime(file, FileTime.fromMillis(millis))
        }

    // jar のファイルは、そのファイルの更新時刻
    @Test
    fun jarUsesFileTime() {
        val jar = place(temporaryFolder.root.toPath(), "shared-jvm.jar", MODIFIED)

        assertEquals(MODIFIED, lastModifiedMillisOf(jar.toUri().toURL()))
    }

    // ディレクトリは、深さを問わずその下のクラスファイルの更新時刻のうち最も新しいもの。クラスファイルでないファイルは見ない
    @Test
    fun directoryUsesNewestClassFile() {
        val classes = temporaryFolder.newFolder("classes").toPath()
        place(classes, "to/sava/A.class", MODIFIED)
        place(classes, "to/sava/deep/B.class", NEWER)
        place(classes, "META-INF/main.kotlin_module", NEWEST)

        assertEquals(NEWER, lastModifiedMillisOf(classes.toUri().toURL()))
    }

    // ファイルが無いとき、ディレクトリの下にクラスファイルが無いとき、ローカルのファイルを指さないときは null
    @Test
    fun unresolvableLocationIsNull() {
        val empty = temporaryFolder.newFolder("empty").toPath()
        place(empty, "META-INF/MANIFEST.MF", MODIFIED)
        val cases = listOf(
            temporaryFolder.root.toPath().resolve("missing.jar").toUri().toURL(),
            empty.toUri().toURL(),
            URI.create("https://example.com/shared-jvm.jar").toURL(),
        )
        cases.forEach { assertNull(lastModifiedMillisOf(it), it.toString()) }
    }

    // ビルドの時刻は、渡したクラスと shared のクラスを読み込んだ場所の、更新時刻の新しいほう
    @Test
    fun buildTimeIsNewestOfAppClassAndShared() {
        val app = assertNotNull(DesktopBuildTimeTest::class.java.protectionDomain?.codeSource?.location)
        val shared = assertNotNull(AppPath::class.java.protectionDomain?.codeSource?.location)
        val expected = maxOf(assertNotNull(lastModifiedMillisOf(app)), assertNotNull(lastModifiedMillisOf(shared)))

        assertEquals(expected, desktopBuildEpochMillis(DesktopBuildTimeTest::class.java))
    }
}
