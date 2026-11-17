package to.sava.cloudmarksmultiplatform.host

import to.sava.cloudmarksmultiplatform.browser.BrowsersWithExtensionReadResult
import to.sava.cloudmarksmultiplatform.browser.appendBrowserWithExtension
import to.sava.cloudmarksmultiplatform.browser.normalizedExecutable
import to.sava.cloudmarksmultiplatform.browser.readBrowsersWithExtension
import to.sava.cloudmarksmultiplatform.files.describeIoFailure
import java.io.IOException
import java.nio.file.Path

/** ホストを起動したプロセスの祖先をたどるとき、自分自身の実行ファイルのほかに飛ばす、ブラウザではない仲介のプロセスの実行ファイルの名前（小文字）。 */
private const val COMMAND_INTERPRETER_NAME = "cmd.exe"

/** 祖先をたどる段数の上限。 */
private const val MAX_ANCESTORS = 32

/**
 * ホストを起動したプロセスの祖先の実行ファイルのパスを、親から順に返す。実行ファイルが分からない祖先は null。
 * ホストを起動したブラウザを見つけるために使う。
 */
internal typealias AncestorExecutables = () -> Sequence<String?>

/** 実際のプロセスの祖先をたどる [AncestorExecutables]。 */
internal fun processAncestorExecutables(): Sequence<String?> =
    generateSequence(ProcessHandle.current().parent().orElse(null)) { it.parent().orElse(null) }
        .take(MAX_ANCESTORS)
        .map { it.info().command().orElse(null) }

/** このプロセス自身の実行ファイルのパス。分からなければ null。 */
internal fun processOwnExecutable(): String? =
    ProcessHandle.current().info().command().orElse(null)

/**
 * [ancestors]（親から順）のうち、仲介のプロセスでない最初のものの実行ファイルのパス。
 * 仲介のプロセスは、実行ファイルの名前が [ownExecutable]（このプロセス自身。ランチャーが自分を子として起動し直すと、
 * 同じ名前の祖先が現れる）と同じもの、または [COMMAND_INTERPRETER_NAME]。名前は大文字小文字によらず比べる。
 * そこに着く前に実行ファイルの分からない祖先に当たったとき、仲介でない祖先が無いときは null。
 */
internal fun findBrowserExecutable(ancestors: Sequence<String?>, ownExecutable: String?): String? {
    val relayNames = setOfNotNull(COMMAND_INTERPRETER_NAME, ownExecutable?.let(::executableNameOf))
    return ancestors.takeWhile { it != null }.filterNotNull().firstOrNull { executableNameOf(it) !in relayNames }
}

/** [path] の最後の区切りより後ろ（小文字）。 */
private fun executableNameOf(path: String): String =
    path.substringAfterLast('\\').substringAfterLast('/').lowercase()

/**
 * 拡張が入っているブラウザの記録 [file]（`{"browsers":[…]}`）に、[executable] を書き足す（[appendBrowserWithExtension]。
 * 実行ファイルのパスは [normalizedExecutable] の形にそろえる）。同じものが既にあれば書かない。[file] が無いか壊れていれば、
 * 空の記録として書く。ディレクトリが無ければ作る。読めなかったとき、書けなかったときは、[log] に書いて何も変えない。
 */
internal fun recordBrowserWithExtension(file: Path, executable: String, log: (String) -> Unit) {
    val known = when (val read = readBrowsersWithExtension(file)) {
        is BrowsersWithExtensionReadResult.Read -> read.browsers
        BrowsersWithExtensionReadResult.Broken -> {
            log(HOST_LOG_PREFIX + "ブラウザの記録が壊れていたので、書き直す")
            emptyList()
        }
        is BrowsersWithExtensionReadResult.Failed -> {
            log(HOST_LOG_PREFIX + "ブラウザの記録を読めなかった: ${describeIoFailure(read.cause)}")
            return
        }
    }
    try {
        appendBrowserWithExtension(file, known, executable)
    } catch (e: IOException) {
        log(HOST_LOG_PREFIX + "ブラウザの記録を書けなかった: ${describeIoFailure(e)}")
    }
}
