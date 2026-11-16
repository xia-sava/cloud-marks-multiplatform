package to.sava.cloudmarksmultiplatform.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StorageKeysTest {
    // プレフィックスの前後の `/` を除き、途中の `/` は残す
    @Test
    fun normalizesPrefix() {
        val expectations = mapOf(
            "" to "",
            "/" to "",
            "///" to "",
            "marks" to "marks",
            "/marks/" to "marks",
            "a/b" to "a/b",
            "//a//b//" to "a//b",
        )

        expectations.forEach { (input, expected) -> assertEquals(expected, normalizePrefix(input), input) }
    }

    // マニフェストのキーは、プレフィックスが空なら manifest.jsonl.gz から始まり、あれば `/` でつなぐ
    @Test
    fun buildsManifestKey() {
        assertEquals("manifest.jsonl.gz", manifestKey(""))
        assertEquals("manifest.jsonl.gz", manifestKey("/"))
        assertEquals("marks/manifest.jsonl.gz", manifestKey("/marks/"))
    }

    // 項目ファイルのキーは folders/<フォルダの ID>/<ファイルの ID>.jsonl.gz
    @Test
    fun buildsItemsFileKey() {
        assertEquals("folders/$FOLDER_A/$FILE_ID.jsonl.gz", itemsFileKey("", FOLDER_A, FILE_ID))
        assertEquals("a/b/folders/$FOLDER_A/$FILE_ID.jsonl.gz", itemsFileKey("a/b/", FOLDER_A, FILE_ID))
    }

    // 新しい ID は小文字・ハイフン区切りの UUID（バージョン 4）の形で、毎回違う
    @Test
    fun generatesLowercaseVersion4Uuid() {
        val ids = List(20) { newId() }

        assertTrue(ids.all { isUuidShape(it) && it[14] == '4' }, ids.toString())
        assertEquals(ids.size, ids.toSet().size)
    }
}
