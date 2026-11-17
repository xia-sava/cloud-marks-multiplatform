package to.sava.cloudmarksmultiplatform

import to.sava.cloudmarksmultiplatform.host.reserveStandardOutputForResponse
import to.sava.cloudmarksmultiplatform.host.runNativeMessagingHost
import to.sava.cloudmarksmultiplatform.settings.WindowsDpapiProtector
import kotlin.system.exitProcess

/**
 * Native Messaging のホスト（`docs/native-messaging.md`）。拡張がメッセージを送ると、ブラウザが起動する。
 * メッセージを 1 つ読み、追加なら S3 に書き、拡張が入っていることの知らせならブラウザの記録に書き、コマンドの受け取りなら
 * コマンドのファイルを読んで消し、応答を 1 つ返して終わる。画面は出さない。
 */
fun main() {
    try {
        val responseOutput = reserveStandardOutputForResponse()
        runNativeMessagingHost(System.`in`, responseOutput, desktopAppFiles(), WindowsDpapiProtector)
    } finally {
        // S3 のクライアントなどのスレッドが残っていても終える
        exitProcess(0)
    }
}
