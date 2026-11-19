package to.sava.cloudmarksmultiplatform

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.assertNotNull

/** テストの作業ディレクトリから親へ辿って、見本のディレクトリ `contracts/<name>`（`storage`・`native-messaging`）を見つける。 */
internal fun findContracts(name: String): File {
    val found = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { it.resolve("contracts/$name") }
        .firstOrNull { it.isDirectory }
    return assertNotNull(found, "contracts/$name が作業ディレクトリの祖先に見つからない")
}

/** `contracts/native-messaging/identities.json` の、配布版（[distributed] が真）か開発版の組の、欄 [field] の値。 */
internal fun nativeMessagingIdentity(distributed: Boolean, field: String): String =
    Json.parseToJsonElement(findContracts("native-messaging").resolve("identities.json").readText())
        .jsonObject.getValue(if (distributed) "distributed" else "dev")
        .jsonObject.getValue(field)
        .jsonPrimitive.content
