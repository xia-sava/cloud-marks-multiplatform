package to.sava.cloudmarksmultiplatform.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class UpdateInfoTest {
    private fun decode(json: String, platform: UpdatePlatform = UpdatePlatform.DESKTOP): PlatformRelease? =
        decodePlatformRelease(json.encodeToByteArray(), platform)

    /** デスクトップの項目を [release] にした更新情報。 */
    private fun desktopOnly(release: String): String = """{"desktop":$release}"""

    // それぞれのプラットフォームは、自分のキーの項目を読む
    @Test
    fun readsOwnPlatformRelease() {
        assertEquals(PlatformRelease(RELEASED_VERSION_CODE, RELEASED_VERSION_NAME, DESKTOP_SHA256), decode(UPDATE_INFO, UpdatePlatform.DESKTOP))
        assertEquals(PlatformRelease(RELEASED_VERSION_CODE, RELEASED_VERSION_NAME, ANDROID_SHA256), decode(UPDATE_INFO, UpdatePlatform.ANDROID))
    }

    // 自分のキーが無いか、値が null なら、項目は無い
    @Test
    fun missingOrNullReleaseIsNull() {
        val androidOnly = """{"android":{"versionCode":5,"versionName":"0.1.0","sha256":"$ANDROID_SHA256"}}"""

        assertNull(decode(androidOnly))
        assertNull(decode("""{"desktop":null,"android":null}"""))
        assertNull(decode("{}"))
    }

    // 知らない欄は、最上位（知らないプラットフォームを含む）でも項目の中（配布物の URL を含む）でも無視する
    @Test
    fun ignoresUnknownFields() {
        val release = decode(
            """{"schemaVersion":2,"ios":{"versionCode":1},""" +
                """"desktop":{"versionCode":7,"versionName":"0.2.0","sha256":"$DESKTOP_SHA256",""" +
                """"url":"https://example.invalid/cloudmarks-setup.exe","notes":{"ja":"…"}}}""",
        )

        assertEquals(PlatformRelease(7, "0.2.0", DESKTOP_SHA256), release)
    }

    // ほかのプラットフォームの項目の形が合わなくても（壊れていても）、自分の項目は読む
    @Test
    fun readsOwnReleaseWhenOtherPlatformIsMalformed() {
        listOf(
            """{"versionCode":"20","versionName":"2.0.0","sha256":"$ANDROID_SHA256"}""",
            """{"versionCode":20}""",
            """"not an object"""",
            "[]",
        ).forEach { android ->
            val release = decode("""{"android":$android,"desktop":{"versionCode":20,"versionName":"2.0.0","sha256":"$DESKTOP_SHA256"}}""")

            assertEquals(PlatformRelease(20, "2.0.0", DESKTOP_SHA256), release, "android=$android")
        }
    }

    // versionCode は、JSON の整数の字句で Int に収まるものだけを読む。文字列にした数・小数・指数・大きすぎる数・null は読まない
    @Test
    fun rejectsVersionCodeThatIsNotInteger() {
        listOf("\"20\"", "20.0", "20.5", "2e1", "2147483648", "null", "true").forEach { versionCode ->
            assertFailsWith<IllegalArgumentException>("versionCode=$versionCode") {
                decode(desktopOnly("""{"versionCode":$versionCode,"versionName":"2.0.0","sha256":"$DESKTOP_SHA256"}"""))
            }
        }
    }

    // sha256 は、小文字の 16 進で 64 文字のものだけを読む
    @Test
    fun rejectsSha256ThatIsNotLowercaseHex() {
        listOf(
            DESKTOP_SHA256.uppercase(),
            "d".repeat(63),
            "d".repeat(65),
            "g".repeat(64),
            "",
        ).forEach { sha256 ->
            assertFailsWith<IllegalArgumentException>("sha256=$sha256") {
                decode(desktopOnly("""{"versionCode":20,"versionName":"2.0.0","sha256":"$sha256"}"""))
            }
        }
    }

    // sha256 が無い項目は読まない（確かめずに進む道を作らない）
    @Test
    fun rejectsReleaseWithoutSha256() {
        assertFailsWith<IllegalArgumentException> {
            decode(desktopOnly("""{"versionCode":20,"versionName":"2.0.0"}"""))
        }
    }

    // versionName は、空白だけでない文字列だけを読む
    @Test
    fun rejectsVersionNameThatIsNotText() {
        listOf("\"\"", "\" \"", "200", "null").forEach { versionName ->
            assertFailsWith<IllegalArgumentException>("versionName=$versionName") {
                decode(desktopOnly("""{"versionCode":20,"versionName":$versionName,"sha256":"$DESKTOP_SHA256"}"""))
            }
        }
    }

    // 自分の項目が JSON のオブジェクトでなければ読まない
    @Test
    fun rejectsOwnReleaseThatIsNotObject() {
        listOf("\"desktop\"", "20", "[]").forEach { release ->
            assertFailsWith<IllegalArgumentException>("release=$release") { decode(desktopOnly(release)) }
        }
    }

    // 全体が JSON として読めないもの、オブジェクトでないものは読まない
    @Test
    fun rejectsTextThatIsNotJsonObject() {
        listOf("not json {", "", "[]", "\"desktop\"").forEach { text ->
            assertFailsWith<IllegalArgumentException>("text=$text") { decode(text) }
        }
    }
}
