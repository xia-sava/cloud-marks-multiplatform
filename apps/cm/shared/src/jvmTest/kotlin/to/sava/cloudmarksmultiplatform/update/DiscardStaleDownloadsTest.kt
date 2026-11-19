package to.sava.cloudmarksmultiplatform.update

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** いま（エポックからのミリ秒）。消すかどうかは、これとディレクトリの最終更新の差で決まる。 */
private const val NOW = 1_000_000_000_000L

/** 置いたままと見なすまでの時間。既定と同じ長さで確かめる。 */
private val STALE_AFTER = 10.minutes

private const val MILLIS_PER_MINUTE = 60_000L

class DiscardStaleDownloadsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val logs = CopyOnWriteArrayList<String>()

    /** 更新のディレクトリ。 */
    private val updateDirectory: Path by lazy { temporaryFolder.root.toPath().resolve("updates") }

    /** 更新のディレクトリの下の、[minutesAgo] 分前に最後に更新したことにした、名前が [name] のディレクトリ。配布物を 1 つ置く。 */
    private fun directory(name: String, minutesAgo: Long, withSetup: Boolean = true): Path =
        updateDirectory.resolve(name).createDirectories().also { directory ->
            if (withSetup) directory.resolve("cloudmarks-setup.exe").writeText("setup")
            Files.setLastModifiedTime(directory, FileTime.fromMillis(NOW - minutesAgo * MILLIS_PER_MINUTE))
        }

    private fun discard() = discardStaleDownloads(updateDirectory, { logs += it }, STALE_AFTER) { NOW }

    // インストーラへ渡した配布物は、渡した側では消せない。置いたままのダウンロードは、ここで中身ごと消す
    @Test
    fun discardsStaleDownload() {
        val stale = directory("download-123", minutesAgo = 30)

        discard()

        assertFalse(stale.exists())
        assertTrue(logs.isEmpty(), logs.toString())
    }

    // できたばかりのものは残す。スクリプトはアプリの終了を待ってからインストーラを走らせるので、これから使われるものかもしれない
    @Test
    fun keepsFreshDownload() {
        val fresh = directory("download-456", minutesAgo = 1)

        discard()

        assertTrue(fresh.resolve("cloudmarks-setup.exe").exists())
    }

    // 配布物を消せてもディレクトリが残ることがある。中身の有無で分けない
    @Test
    fun discardsEmptyStaleDirectory() {
        val empty = directory("download-789", minutesAgo = 30, withSetup = false)

        discard()

        assertFalse(empty.exists())
    }

    // ダウンロードのディレクトリの名前でないものは、古くても消さない
    @Test
    fun keepsWhatItDidNotCreate() {
        val other = directory("something-else", minutesAgo = 30)
        val file = updateDirectory.resolve("download-file").also { it.writeText("not a directory") }
        Files.setLastModifiedTime(file, FileTime.fromMillis(NOW - 30 * MILLIS_PER_MINUTE))

        discard()

        assertTrue(other.exists())
        assertTrue(file.exists())
    }

    // 更新のディレクトリがまだ無ければ、何もしない
    @Test
    fun doesNothingWithoutUpdateDirectory() {
        discard()

        assertFalse(updateDirectory.exists())
        assertTrue(logs.isEmpty(), logs.toString())
    }

    // ダウンロードのディレクトリは、更新のディレクトリの下に、ダウンロードの名前で新しく作る
    @Test
    fun createsNewDownloadDirectoryEachTime() {
        val first = createDownloadDirectory(updateDirectory)
        val second = createDownloadDirectory(updateDirectory)

        assertTrue(first != second)
        listOf(first, second).forEach { directory ->
            assertTrue(Files.isDirectory(directory))
            assertTrue(directory.parent == updateDirectory, directory.toString())
            assertTrue(directory.fileName.toString().startsWith("download-"), directory.toString())
        }
    }
}
