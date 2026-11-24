package to.sava.cloudmarksmultiplatform.host

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import to.sava.cloudmarksmultiplatform.browser.OpenTabsCommand
import to.sava.cloudmarksmultiplatform.browser.toJson

private const val OK = "ok"
private const val ERROR = "error"
private const val CODE = "code"
private const val MESSAGE = "message"
private const val COMMAND = "command"
private const val FOLDERS = "folders"
private const val ID = "id"
private const val PARENT = "parent"
private const val NAME = "name"

/** ホストの応答の長さ（バイト数）の上限。Chrome がホストから受け取るメッセージの上限（1 MiB）と同じ。 */
internal const val MAX_RESPONSE_BYTES = MEBIBYTE

/** 失敗の種類。`docs/native-messaging.md` の `error.code`。[value] は応答に書く値。 */
internal enum class HostErrorCode(val value: String) {
    NOT_CONFIGURED("notConfigured"),
    STORAGE_UNAVAILABLE("storageUnavailable"),
    UPDATE_REQUIRED("updateRequired"),
    DATA_BROKEN("dataBroken"),
    FOLDER_NOT_FOUND("folderNotFound"),
    BUSY("busy"),
    UNCONFIRMED("unconfirmed"),
    INVALID_REQUEST("invalidRequest"),
    LAUNCH_FAILED("launchFailed"),
    INTERNAL("internal"),
}

/** 失敗の応答の `error`。[message] は、拡張が画面にそのまま出す文。 */
internal data class HostError(val code: HostErrorCode, val message: String)

/** ホストが返す応答。 */
internal sealed interface HostResponse {
    /** 追加を S3 に書けた。拡張が入っていることの知らせを受け付けた。デスクトップのアプリを起動した。 */
    data object Ok : HostResponse

    /** `takeCommand` を受け付けた。[command] が `null` なら、渡せるコマンドが無い。 */
    data class CommandTaken(val command: OpenTabsCommand?) : HostResponse

    /** `listFolders` を受け付けた。[folders] は、マニフェストのフォルダの行と同じ順。 */
    data class Folders(val folders: List<HostFolder>) : HostResponse

    /** 失敗した。 */
    data class Failed(val error: HostError) : HostResponse
}

/** フォルダの一覧の 1 件。[parent] は、ルートフォルダだけが null。[name] は、ルートフォルダだけが空。 */
internal data class HostFolder(val id: String, val parent: String?, val name: String)

/** [response] を、応答のメッセージの本体（UTF-8 の JSON）にする。 */
internal fun encodeHostResponse(response: HostResponse): ByteArray =
    when (response) {
        HostResponse.Ok -> buildJsonObject { put(OK, true) }
        is HostResponse.CommandTaken -> buildJsonObject {
            put(OK, true)
            put(COMMAND, response.command?.toJson() ?: JsonNull)
        }
        is HostResponse.Folders -> buildJsonObject {
            put(OK, true)
            putJsonArray(FOLDERS) {
                response.folders.forEach { folder ->
                    addJsonObject {
                        put(ID, folder.id)
                        put(PARENT, folder.parent)
                        put(NAME, folder.name)
                    }
                }
            }
        }
        is HostResponse.Failed -> buildJsonObject {
            put(OK, false)
            putJsonObject(ERROR) {
                put(CODE, response.error.code.value)
                put(MESSAGE, response.error.message)
            }
        }
    }.toString().encodeToByteArray()
