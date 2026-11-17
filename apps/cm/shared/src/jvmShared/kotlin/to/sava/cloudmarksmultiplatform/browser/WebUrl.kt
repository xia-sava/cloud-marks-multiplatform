package to.sava.cloudmarksmultiplatform.browser

/** 既定のブラウザで開く URL の種類。ほかの種類は、OS がブラウザでないアプリで開くことがあるため開かない。 */
private val WEB_SCHEMES = setOf("http", "https")

/** URL の種類（scheme）の形。英字で始まり、英数字と `+`・`-`・`.` が続き、`:` で終わる。 */
internal val URL_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*:")

/** 既定のブラウザで開かなかった理由のうち、URL の種類が既定のブラウザで開く種類でないこと（[isWebUrl]）。 */
internal const val NOT_WEB_URL_REASON = "URL の種類が http・https でない"

/** [url] の先頭の URL の種類が、既定のブラウザで開く種類（http か https）か。大文字と小文字は区別しない。 */
internal fun isWebUrl(url: String): Boolean =
    URL_SCHEME.find(url)?.value?.dropLast(1)?.lowercase() in WEB_SCHEMES

/**
 * 既定のブラウザで開けなかった理由 [reason] を [log] に書き、詳細の無い [BrowserOpenResult.LaunchFailed] を返す。
 * [reason] には URL を入れない。
 */
internal fun defaultBrowserFailed(log: (String) -> Unit, reason: String?): BrowserOpenResult {
    log("既定のブラウザで開けなかった: $reason")
    return BrowserOpenResult.LaunchFailed(detail = null)
}
