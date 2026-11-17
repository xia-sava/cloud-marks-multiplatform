package to.sava.cloudmarksmultiplatform.host

import kotlinx.coroutines.runBlocking
import to.sava.cloudmarksmultiplatform.AppFiles
import to.sava.cloudmarksmultiplatform.logging.FileLog
import to.sava.cloudmarksmultiplatform.settings.SecretProtector
import java.io.InputStream
import java.io.PrintStream

/**
 * 標準出力を応答のために取り分け、[System.out] を標準エラーへ向け直す。ホストを起動したら、ほかの何よりも先に呼ぶ。
 *
 * 標準出力はブラウザへの応答だけに使う。ライブラリ（AWS SDK、slf4j など）が [System.out] へ書いても、
 * 標準エラーへ出て、応答を壊さない。
 *
 * @return 応答を書く、元の標準出力
 */
fun reserveStandardOutputForResponse(): PrintStream =
    System.out.also { System.setOut(System.err) }

/**
 * Native Messaging のホストとして、[input] からメッセージを 1 つ読み、S3 へ書き、[output] へ応答を 1 つ書く。
 *
 * ログは [AppFiles.logDirectory] のファイル（アプリと同じもの）に書く。
 *
 * @param output 応答だけを書く。[reserveStandardOutputForResponse] が返したもの
 * @param appFiles アプリと同じファイルの場所
 * @param protector 設定の認証情報を復号する仕組み。アプリと同じもの
 */
fun runNativeMessagingHost(input: InputStream, output: PrintStream, appFiles: AppFiles, protector: SecretProtector) {
    val log = FileLog(appFiles.logDirectory)
    runNativeMessagingHost(input, output, NativeMessagingHost(appFiles, protector, log::write), log::write)
}

/**
 * [host] で [input] のメッセージを 1 つ処理し、[output] へ応答を 1 つ書く。
 *
 * [host] の処理から [Error] などの例外が抜けても、応答をまだ書いていなければ、そのほかの失敗の応答を書く。
 * そのときは例外のクラス名をログに書く。応答を書けなかったときも、ログに書いて終える。
 */
internal fun runNativeMessagingHost(input: InputStream, output: PrintStream, host: NativeMessagingHost, log: (String) -> Unit) {
    var responseStarted = false
    try {
        val response = runBlocking { host.respond(input) }
        responseStarted = true
        writeResponse(output, response, log)
    } catch (e: Throwable) {
        log(HOST_LOG_PREFIX + "予期しない失敗: ${e.javaClass.name}")
        if (!responseStarted) writeResponse(output, HostResponse.Failed(INTERNAL_ERROR), log)
    }
}

/** [output] へ [response] を書く。書けなければ、ログに書く。 */
private fun writeResponse(output: PrintStream, response: HostResponse, log: (String) -> Unit) {
    writeMessage(output, encodeHostResponse(response))
    if (output.checkError()) log(HOST_LOG_PREFIX + "応答を書けなかった")
}
