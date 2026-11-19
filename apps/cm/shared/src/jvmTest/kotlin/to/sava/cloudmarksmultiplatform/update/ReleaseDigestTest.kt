package to.sava.cloudmarksmultiplatform.update

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `abc` の SHA-256。 */
private const val ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

class ReleaseDigestTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun fileOf(content: String): Path = temporaryFolder.newFile().toPath().also { it.writeText(content) }

    // 知られた入力の SHA-256 を、小文字の 16 進で返す
    @Test
    fun computesKnownDigest() {
        assertEquals(ABC_SHA256, sha256HexOf(fileOf("abc")))
    }

    // 一度に読む大きさより大きい入力でも、少しずつ読んで同じ値になる
    @Test
    fun computesDigestAcrossBufferBoundary() {
        val content = "a".repeat(200_000)

        assertEquals(sha256Of(content.encodeToByteArray()), sha256HexOf(fileOf(content)))
    }

    // 期待した値と同じなら真
    @Test
    fun matchesExpectedDigest() {
        assertTrue(matchesSha256(fileOf("abc"), ABC_SHA256))
    }

    // 期待した値と違えば偽（書き換えられたか壊れたダウンロードを通さない）。更新情報の表記は小文字なので、大文字の値とも合わない
    @Test
    fun rejectsDifferentDigest() {
        assertFalse(matchesSha256(fileOf("abc"), "0".repeat(64)))
        assertFalse(matchesSha256(fileOf("abc"), ABC_SHA256.uppercase()))
    }

    // 期待した値が空白だけなら偽（確かめずに進む道を作らない）
    @Test
    fun rejectsBlankExpectedDigest() {
        assertFalse(matchesSha256(fileOf("abc"), ""))
        assertFalse(matchesSha256(fileOf("abc"), "   "))
    }

    // 読めないファイルは投げる
    @Test
    fun throwsWhenFileIsMissing() {
        assertFailsWith<IOException> { sha256HexOf(temporaryFolder.root.toPath().resolve("missing")) }
    }
}
