package to.sava.cloudmarksmultiplatform.host

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private const val OK = "ok"
private const val ERROR = "error"
private const val CODE = "code"
private const val MESSAGE = "message"

/** 失敗の種類。`docs/native-messaging.md` の `error.code`。[value] は応答に書く値。 */
internal enum class HostErrorCode(val value: String) {
    NOT_CONFIGURED("notConfigured"),
    STORAGE_UNAVAILABLE("storageUnavailable"),
    UPDATE_REQUIRED("updateRequired"),
    DATA_BROKEN("dataBroken"),
    BUSY("busy"),
    UNCONFIRMED("unconfirmed"),
    INVALID_REQUEST("invalidRequest"),
    INTERNAL("internal"),
}

/** 失敗の応答の `error`。[message] は、拡張が画面にそのまま出す文。 */
internal data class HostError(val code: HostErrorCode, val message: String)

/** ホストが返す応答。 */
internal sealed interface HostResponse {
    /** 追加を S3 に書けた。 */
    data object Ok : HostResponse

    /** 失敗した。 */
    data class Failed(val error: HostError) : HostResponse
}

/** [response] を、応答のメッセージの本体（UTF-8 の JSON）にする。 */
internal fun encodeHostResponse(response: HostResponse): ByteArray =
    when (response) {
        HostResponse.Ok -> buildJsonObject { put(OK, true) }
        is HostResponse.Failed -> buildJsonObject {
            put(OK, false)
            putJsonObject(ERROR) {
                put(CODE, response.error.code.value)
                put(MESSAGE, response.error.message)
            }
        }
    }.toString().encodeToByteArray()
