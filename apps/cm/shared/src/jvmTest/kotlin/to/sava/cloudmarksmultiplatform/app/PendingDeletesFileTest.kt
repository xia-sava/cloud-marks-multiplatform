package to.sava.cloudmarksmultiplatform.app

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.sava.cloudmarksmultiplatform.operations.ItemRef
import to.sava.cloudmarksmultiplatform.sync.connectionName
import to.sava.cloudmarksmultiplatform.sync.folderId
import to.sava.cloudmarksmultiplatform.sync.itemId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** 溜めた削除の対象。並びを確かめるため、ID の順と違う順に並べる。 */
private val TARGETS = linkedSetOf(
    ItemRef(folderId(2), itemId(5)),
    ItemRef(folderId(1), itemId(3)),
    ItemRef(folderId(2), itemId(4)),
)

class PendingDeletesFileTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** まだ無いディレクトリの下の、溜めた削除の記録のファイル。 */
    private val directory: Path by lazy { temporaryFolder.root.toPath().resolve("state") }
    private val path: Path by lazy { directory.resolve("pending-deletes.json") }
    private val logs = mutableListOf<String>()

    private fun file(): PendingDeletesFile = PendingDeletesFile(path) { logs += it }

    /** [text] を、記録のファイルとしてそのまま置く。 */
    private fun placeRaw(text: String) {
        Files.createDirectories(directory)
        Files.writeString(path, text)
    }

    // 書いた対象を読み直すと、同じ対象が同じ並びで返る。親のディレクトリが無ければ作り、一時ファイルを残さない
    @Test
    fun writtenTargetsReadBackInOrder() {
        file().write(TARGETS)

        assertEquals(TARGETS.toList(), file().read().toList())
        assertEquals(listOf("pending-deletes.json"), directory.listDirectoryEntries().map { it.name })
        assertEquals(emptyList(), logs)
    }

    // ファイルには、対象を targets の欄に、フォルダの ID と項目の ID の組で書く
    @Test
    fun targetsAreWrittenAsJson() {
        file().write(linkedSetOf(ItemRef(folderId(1), itemId(3))))

        assertEquals("""{"targets":[{"folderId":"${folderId(1)}","itemId":"${itemId(3)}"}]}""", Files.readString(path))
    }

    // 空を書くと、ファイルを消す。無いファイルを空で書いても失敗にしない
    @Test
    fun writingEmptyDeletesFile() {
        file().write(TARGETS)

        file().write(emptySet())
        assertFalse(path.exists())

        file().write(emptySet())
        assertFalse(path.exists())
        assertEquals(emptyList(), logs)
    }

    // ファイルが無ければ、溜めた削除は無いものとする。ログには書かない
    @Test
    fun missingFileIsEmpty() {
        assertEquals(emptySet(), file().read())
        assertEquals(emptyList(), logs)
    }

    // 同じ対象が 2 度書かれていれば、1 つにする。知らない欄は無視して読む
    @Test
    fun duplicatesAndUnknownKeysAreTolerated() {
        val target = """{"folderId":"${folderId(1)}","itemId":"${itemId(3)}","other":1}"""
        placeRaw("""{"targets":[$target,$target],"other":true}""")

        assertEquals(setOf(ItemRef(folderId(1), itemId(3))), file().read())
        assertEquals(emptyList(), logs)
    }

    // 記録の形でなければ（JSON でない・オブジェクトでない・targets が無いか配列でない・ID が無いか文字列でない・小文字の UUID の形でない）、
    // 溜めた削除は無いものとし、ログに書く。一部の対象だけが壊れていても、全体を無いものとする
    @Test
    fun brokenRecordIsEmptyAndLogged() {
        val good = """{"folderId":"${folderId(1)}","itemId":"${itemId(3)}"}"""
        val cases = listOf(
            "not json",
            "[]",
            "{}",
            """{"targets":1}""",
            """{"targets":[{"folderId":"${folderId(1)}"}]}""",
            """{"targets":[{"folderId":1,"itemId":"${itemId(3)}"}]}""",
            """{"targets":[$good,{"folderId":"${folderId(1)}","itemId":"not-a-uuid"}]}""",
            """{"targets":[{"folderId":"ABCDEF00-0000-4000-8000-000000000001","itemId":"${itemId(3)}"}]}""",
        )
        cases.forEach { text ->
            logs.clear()
            placeRaw(text)

            assertEquals(emptySet(), file().read(), text)
            assertEquals(1, logs.size, text)
        }
    }

    // 読めなければ（記録の場所がディレクトリ）、例外を投げずに無いものとし、ログに書く
    @Test
    fun readFailureIsEmptyAndLogged() {
        Files.createDirectories(path)

        assertEquals(emptySet(), file().read())
        assertEquals(1, logs.size)
        assertTrue(logs.single().startsWith("溜めた削除の記録を読めなかった"))
    }

    // 書けなければ（置き換え先が、中身のあるディレクトリ）、例外を投げずにログに書く。消せないときも同じ
    @Test
    fun writeAndDeleteFailuresAreLogged() {
        Files.createDirectories(path.resolve("child"))

        file().write(TARGETS)
        file().write(emptySet())

        assertEquals(2, logs.size)
        assertTrue(logs[0].startsWith("溜めた削除の記録を書けなかった"))
        assertTrue(logs[1].startsWith("溜めた削除の記録を消せなかった"))
    }

    // 記録のファイルは接続先ごとに分かれ、名前は接続先から決まる名前で始まる。前後の / だけが違うプレフィックスは同じ接続先
    @Test
    fun fileIsPerConnection() {
        val stateDirectory = temporaryFolder.root.toPath()
        val file = pendingDeletesFile(stateDirectory, "bucket", "prefix")

        assertEquals(stateDirectory, file.parent)
        assertTrue(file.name.startsWith(connectionName("bucket", "prefix")))
        assertEquals(file, pendingDeletesFile(stateDirectory, "bucket", "/prefix/"))
        assertNotEquals(file, pendingDeletesFile(stateDirectory, "other", "prefix"))
        assertNotEquals(file, pendingDeletesFile(stateDirectory, "bucket", "other"))
    }
}
