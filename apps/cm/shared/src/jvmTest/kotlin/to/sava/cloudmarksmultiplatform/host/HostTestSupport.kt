package to.sava.cloudmarksmultiplatform.host

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import to.sava.cloudmarksmultiplatform.findContracts
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertIs

/** `contracts/native-messaging` の見本 [name] のバイト列。 */
internal fun contractBytes(name: String): ByteArray = findContracts("native-messaging").resolve(name).readBytes()

/** [bytes] を JSON として読む。整形の有無によらず、同じ JSON なら等しくなる。 */
internal fun jsonOf(bytes: ByteArray): JsonElement = Json.parseToJsonElement(bytes.decodeToString())

/** [body] を、4 バイトの長さ（リトルエンディアン）を前に付けたメッセージにする。 */
internal fun framed(body: ByteArray): ByteArray =
    ByteBuffer.allocate(Int.SIZE_BYTES + body.size).order(ByteOrder.LITTLE_ENDIAN).putInt(body.size).put(body).array()

/** [body] のメッセージを 1 つ持つ入力。 */
internal fun inputOf(body: ByteArray): ByteArrayInputStream = ByteArrayInputStream(framed(body))

/** [output] に書かれたメッセージがちょうど 1 つであることを確かめ、その本体を返す。 */
internal fun singleMessageIn(output: ByteArrayOutputStream): ByteArray {
    val input = ByteArrayInputStream(output.toByteArray())
    val message = assertIs<FrameReadResult.Message>(readMessage(input))
    assertIs<FrameReadResult.Truncated>(readMessage(input), "メッセージの後ろに余計なバイトがある")
    return message.body
}

/** テスト用の、UUID の形の ID。[n] ごとに違う。 */
internal fun hostId(n: Int): String = "50000000-0000-4000-8000-" + n.toString().padStart(12, '0')

/** [hostId] を 1 から順に振る。 */
internal class SequentialIds {
    private var next = 1

    fun next(): String = hostId(next++)
}

/** 見本 `take-command.json` のトークン。 */
internal const val TOKEN = "0123456789abcdef0123456789abcdef"

/** 場合の名前と、取り決めに合わない要求の本体。検証の規則（`docs/native-messaging.md`）ごとに 1 つ以上。 */
internal val INVALID_REQUESTS: List<Pair<String, String>> = listOf(
    "protocol が 2" to """{"protocol":2,"type":"addBookmarks","tabs":[{"url":"https://example.com/","title":"t"}]}""",
    "protocol が文字列" to """{"protocol":"1","type":"addBookmarks","tabs":[{"url":"https://example.com/","title":"t"}]}""",
    "protocol が小数の字句" to """{"protocol":1.0,"type":"addBookmarks","tabs":[{"url":"https://example.com/","title":"t"}]}""",
    "protocol が無い" to """{"type":"addBookmarks","tabs":[{"url":"https://example.com/","title":"t"}]}""",
    "type が知らない値" to """{"protocol":1,"type":"addTabs","tabs":[{"url":"https://example.com/","title":"t"}]}""",
    "type が無い" to """{"protocol":1,"tabs":[{"url":"https://example.com/","title":"t"}]}""",
    "type が文字列でない" to """{"protocol":1,"type":1,"tabs":[{"url":"https://example.com/","title":"t"}]}""",
    "addFolder の name が無い" to """{"protocol":1,"type":"addFolder","tabs":[{"url":"https://example.com/","title":"t"}]}""",
    "addFolder の name が文字列でない" to
        """{"protocol":1,"type":"addFolder","name":null,"tabs":[{"url":"https://example.com/","title":"t"}]}""",
    "addFolder の name が空" to """{"protocol":1,"type":"addFolder","name":"","tabs":[{"url":"https://example.com/","title":"t"}]}""",
    "tabs が無い" to """{"protocol":1,"type":"addBookmarks"}""",
    "tabs が配列でない" to """{"protocol":1,"type":"addBookmarks","tabs":{"url":"https://example.com/","title":"t"}}""",
    "tabs が空" to """{"protocol":1,"type":"addFolder","name":"n","tabs":[]}""",
    "タブがオブジェクトでない" to """{"protocol":1,"type":"addBookmarks","tabs":["https://example.com/"]}""",
    "url が無い" to """{"protocol":1,"type":"addBookmarks","tabs":[{"title":"t"}]}""",
    "url が文字列でない" to """{"protocol":1,"type":"addBookmarks","tabs":[{"url":1,"title":"t"}]}""",
    "url が空" to """{"protocol":1,"type":"addBookmarks","tabs":[{"url":"https://example.com/","title":"t"},{"url":"","title":"t"}]}""",
    "title が無い" to """{"protocol":1,"type":"addBookmarks","tabs":[{"url":"https://example.com/"}]}""",
    "title が文字列でない" to """{"protocol":1,"type":"addBookmarks","tabs":[{"url":"https://example.com/","title":null}]}""",
    "extensionPresent の protocol が 2" to """{"protocol":2,"type":"extensionPresent"}""",
    "takeCommand の protocol が無い" to """{"type":"takeCommand","token":"$TOKEN"}""",
    "takeCommand の token が無い" to """{"protocol":1,"type":"takeCommand"}""",
    "takeCommand の token が文字列でない" to """{"protocol":1,"type":"takeCommand","token":1}""",
    "takeCommand の token が空" to """{"protocol":1,"type":"takeCommand","token":""}""",
    "takeCommand の token が大文字" to """{"protocol":1,"type":"takeCommand","token":"${TOKEN.uppercase()}"}""",
    "takeCommand の token が 31 文字" to """{"protocol":1,"type":"takeCommand","token":"${TOKEN.dropLast(1)}"}""",
    "takeCommand の token が 33 文字" to """{"protocol":1,"type":"takeCommand","token":"${TOKEN}0"}""",
    "takeCommand の token が 16 進でない文字を含む" to """{"protocol":1,"type":"takeCommand","token":"${TOKEN.dropLast(1)}g"}""",
    "takeCommand の token が .. を含む" to """{"protocol":1,"type":"takeCommand","token":"..${TOKEN.drop(2)}"}""",
    "takeCommand の token が / を含む" to """{"protocol":1,"type":"takeCommand","token":"a/${TOKEN.drop(2)}"}""",
    "takeCommand の token がバックスラッシュを含む" to """{"protocol":1,"type":"takeCommand","token":"a\\${TOKEN.drop(2)}"}""",
    "takeCommand の token の後ろに改行" to """{"protocol":1,"type":"takeCommand","token":"${TOKEN.dropLast(1)}\n"}""",
    "JSON として読めない" to """{"protocol":1,"type":"addBookmarks",""",
    "JSON のオブジェクトでない" to """[{"protocol":1}]""",
)
