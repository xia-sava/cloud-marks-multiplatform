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

    // フォルダの一覧の応答は、見本のフォルダの一覧の応答と JSON として同じ。ルートフォルダの親は null で出す
    @Test
    fun foldersMatchesContract() {
        val folders = listOf(
            HostFolder("0b8f6a52-1c3d-4e5f-8a9b-0c1d2e3f4a5b", null, ""),
            HostFolder("7d4c1e2a-3b5f-4a6d-9e8c-1f2a3b4c5d6e", "0b8f6a52-1c3d-4e5f-8a9b-0c1d2e3f4a5b", "資料"),
            HostFolder("2e9a7b31-4c6d-4f8e-a0b1-c2d3e4f5a6b7", "7d4c1e2a-3b5f-4a6d-9e8c-1f2a3b4c5d6e", "あとで読む"),
            HostFolder("5f1b3c4d-6e7a-4b8c-9d0e-a1b2c3d4e5f6", "0b8f6a52-1c3d-4e5f-8a9b-0c1d2e3f4a5b", "仕事"),
        )

        assertEquals(jsonOf(contractBytes("response-folders.json")), jsonOf(encodeHostResponse(HostResponse.Folders(folders))))
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

    // 追加先のフォルダが無いときの失敗の応答は、見本の応答と JSON として同じ（code の綴りと文言も見本で確かめる）
    @Test
    fun folderNotFoundMatchesContract() {
        assertEquals(
            jsonOf(contractBytes("response-folder-not-found.json")),
            jsonOf(encodeHostResponse(HostResponse.Failed(FOLDER_NOT_FOUND_ERROR))),
        )
    }

    // 応答の code は、取り決めの表の値
    @Test
    fun codesAreContractValues() {
        assertEquals(
            listOf(
                "notConfigured", "storageUnavailable", "updateRequired", "dataBroken", "folderNotFound",
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
