package to.sava.cloudmarksmultiplatform

import java.io.File
import kotlin.test.assertNotNull

/** テストの作業ディレクトリから親へ辿って、見本のディレクトリ `contracts/<name>`（`storage`・`native-messaging`）を見つける。 */
internal fun findContracts(name: String): File {
    val found = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { it.resolve("contracts/$name") }
        .firstOrNull { it.isDirectory }
    return assertNotNull(found, "contracts/$name が作業ディレクトリの祖先に見つからない")
}
