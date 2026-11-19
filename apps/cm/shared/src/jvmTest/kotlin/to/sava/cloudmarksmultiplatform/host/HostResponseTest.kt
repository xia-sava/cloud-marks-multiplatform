package to.sava.cloudmarksmultiplatform.host

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class HostResponseTest {
    // 成功の応答は、見本の成功の応答と JSON として同じ
    @Test
    fun okMatchesContract() {
        assertEquals(jsonOf(contractBytes("response-ok.json")), jsonOf(encodeHostResponse(HostResponse.Ok)))
    }

    // コマンドが無いときの応答は、見本の「コマンドが無い」応答と JSON として同じ
    @Test
    fun noCommandMatchesContract() {
        assertEquals(
            jsonOf(contractBytes("response-no-command.json")),
            jsonOf(encodeHostResponse(HostResponse.CommandTaken(null))),
        )
    }

    // 失敗の応答は、見本と同じ code と message を与えると、見本の失敗の応答と JSON として同じ
    @Test
    fun failedMatchesContract() {
        val contract = jsonOf(contractBytes("response-error.json"))
        val error = contract.jsonObject.getValue("error").jsonObject
        val code = HostErrorCode.entries.single { it.value == error.getValue("code").jsonPrimitive.content }
        val message = error.getValue("message").jsonPrimitive.content

        assertEquals(contract, jsonOf(encodeHostResponse(HostResponse.Failed(HostError(code, message)))))
    }

    // 応答の code は、取り決めの表の値
    @Test
    fun codesAreContractValues() {
        assertEquals(
            listOf(
                "notConfigured", "storageUnavailable", "updateRequired", "dataBroken",
                "busy", "unconfirmed", "invalidRequest", "launchFailed", "internal",
            ),
            HostErrorCode.entries.map { it.value },
        )
    }

    // 応答は、整形しない 1 行の JSON で、日本語をエスケープせず UTF-8 のまま書く
    @Test
    fun responseIsCompactUtf8() {
        val error = HostError(HostErrorCode.BUSY, "混み合っています")

        assertEquals(
            """{"ok":false,"error":{"code":"busy","message":"混み合っています"}}""",
            encodeHostResponse(HostResponse.Failed(error)).decodeToString(),
        )
    }
}
