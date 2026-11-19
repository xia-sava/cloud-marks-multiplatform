package to.sava.cloudmarksmultiplatform

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppPathTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val installDirectory: File by lazy { temporaryFolder.newFolder("install") }
    private val executable: File by lazy { installDirectory.resolve("CloudMarks.exe").apply { writeText("") } }
    private val runtime: File by lazy { installDirectory.resolve("runtime").apply { mkdirs() } }

    // 実行ファイルのディレクトリの配下にランタイムがあれば、配布物として入っている姿なので使う
    @Test
    fun acceptsExecutableWhoseDirectoryHoldsRuntime() {
        assertTrue(AppPath.isTrusted(executable.absolutePath, runtime.absolutePath))
    }

    // インストール先の外のランタイムで動いているなら、そのパスは配布物のものではない（システムプロパティは差し替えられる）
    @Test
    fun rejectsRuntimeOutsideInstallDirectory() {
        val elsewhere = temporaryFolder.newFolder("elsewhere")

        assertFalse(AppPath.isTrusted(executable.absolutePath, elsewhere.absolutePath))
    }

    // 実在しないパスは使わない
    @Test
    fun rejectsMissingPath() {
        assertFalse(AppPath.isTrusted(installDirectory.resolve("missing.exe").absolutePath, runtime.absolutePath))
    }

    // 実行ファイルでなくディレクトリを渡されても使わない
    @Test
    fun rejectsDirectory() {
        assertFalse(AppPath.isTrusted(installDirectory.absolutePath, runtime.absolutePath))
    }

    // 値が無いか空なら使わない
    @Test
    fun rejectsEmptyOrMissingValue() {
        val cases = listOf(
            null to runtime.absolutePath,
            "  " to runtime.absolutePath,
            executable.absolutePath to null,
            executable.absolutePath to "  ",
        )
        cases.forEach { (appPath, runtimeHome) ->
            assertFalse(AppPath.isTrusted(appPath, runtimeHome), "appPath=$appPath, runtimeHome=$runtimeHome")
        }
    }

    // テストは jpackage のランチャーから起動されていないので、開発版になる
    @Test
    fun testRunIsNotDistributed() {
        assertFalse(AppPath.isDistributed)
    }
}
