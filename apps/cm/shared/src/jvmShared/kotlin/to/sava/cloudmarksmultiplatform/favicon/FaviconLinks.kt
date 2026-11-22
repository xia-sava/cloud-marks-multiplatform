package to.sava.cloudmarksmultiplatform.favicon

import java.net.URI
import java.net.URISyntaxException

/** HTML のコメントの始まり。 */
private const val COMMENT_OPENING = "<!--"

/** HTML のコメントの終わり。 */
private const val COMMENT_CLOSING = "-->"

/** `<link>` の開始タグの始まりの形。 */
private val LINK_TAG_START = Regex("""<link(?=[\s/>])""", RegexOption.IGNORE_CASE).toPattern()

/**
 * `<link>` の開始タグの形。引用符で囲んだ属性の値の中の `>` では終わらない。
 * 量指定子はすべて所有の形にし、合わない入力でも後戻りしない。
 */
private val LINK_TAG = Regex("""<link(?=[\s/>])(?:[^>"']++|"[^"]*+"|'[^']*+')*+>""", RegexOption.IGNORE_CASE).toPattern()

/** 1 つの `<link>` の開始タグとして見る長さの上限（文字数）。8 KiB。これより長いタグは、閉じていないものとして読まない。 */
private const val MAX_LINK_TAG_LENGTH = 8 * 1024

/** `<link` の長さ。開始タグから属性を読むときに飛ばす。 */
private const val LINK_TAG_OPENING_LENGTH = "<link".length

/**
 * 開始タグの中の属性の形。値は、二重引用符で囲むもの・一重引用符で囲むもの・引用符なしのどれか。値の無い属性もある。
 * 量指定子はすべて所有の形にし、後戻りしない。
 */
private val ATTRIBUTE = Regex("""([^\s"'<>/=]++)(?:\s*+=\s*+(?:"([^"]*+)"|'([^']*+)'|([^\s"'<>`=]++)))?+""")

/** `rel` の値をトークンに分ける区切り。HTML の空白。 */
private val REL_SEPARATOR = Regex("\\s+")

/** favicon を指す `rel` のトークン。 */
private const val ICON_REL = "icon"

/** 文字参照の形。数値の参照と、URL に現れうる名前の参照だけを読む。 */
private val CHARACTER_REFERENCE = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|amp|lt|gt|quot|apos);")

/** 名前の文字参照と、それが表す文字。 */
private val NAMED_REFERENCES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'")

/** 16 進の数値の文字参照の頭の長さ（`#x`）。 */
private const val HEX_REFERENCE_PREFIX_LENGTH = 2

/** 10 進の数値の文字参照の頭の長さ（`#`）。 */
private const val DECIMAL_REFERENCE_PREFIX_LENGTH = 1

private const val HEX_RADIX = 16

/** favicon を取りに行く URL の種類。 */
private val HTTP_SCHEMES = setOf("http", "https")

/**
 * HTML の文書 [html] にある、favicon を指す `<link>` の `href` を、文書の URL [documentUrl] を基準に解決した URL。文書の順に並べ、
 * 同じ URL は最初の 1 つだけにする。
 *
 * favicon を指す `<link>` は、`rel` の値を空白で区切ったトークンに `icon` があるもの（`icon`・`shortcut icon` など。
 * 大文字と小文字は区別しない）。`apple-touch-icon` のようにトークンが `icon` でないものは含めない。
 * `href` の文字参照（`&amp;` など）は戻してから解決する。解決した URL が `http`・`https` でないもの（`data:` など）と、
 * ホストの無いもの、解決できないものは含めない。HTML のコメントの中は見ない。[MAX_LINK_TAG_LENGTH] より長いタグは読まない。
 *
 * @param documentUrl 文書を取った URL。転送されたなら、転送後の最終の URL
 */
internal fun iconLinksOf(html: String, documentUrl: String): List<String> {
    val base = baseUriOf(documentUrl) ?: return emptyList()
    return linkTagsOf(withoutComments(html))
        .map(::attributesOf)
        .filter { attributes -> attributes["rel"]?.let(::hasIconToken) == true }
        .mapNotNull { attributes -> attributes["href"]?.let(::decodeCharacterReferences)?.trim()?.takeIf { it.isNotEmpty() } }
        .mapNotNull { href -> resolveHttpUrl(base, href) }
        .distinct()
        .toList()
}

/**
 * [html] の `<link>` の開始タグ。文書の順に並べ、重ならないように拾う。
 * タグは、始まりから [MAX_LINK_TAG_LENGTH] 文字のうちで閉じていなければ読まず、次の始まりを探す。
 * 閉じなかった始まりより後ろのタグは、その次の `>` より後ろでしか閉じないので、その `>` から [MAX_LINK_TAG_LENGTH] 文字より前の始まりは
 * 試さずに飛ばし、後ろに `>` が無ければやめる。`>` の無い `<link` が多く並んでも、時間がかかりすぎない。
 */
private fun linkTagsOf(html: String): Sequence<String> =
    sequence {
        val start = LINK_TAG_START.matcher(html)
        val tag = LINK_TAG.matcher(html)
        var from = 0
        while (from < html.length && start.find(from)) {
            val opening = start.start()
            tag.region(opening, minOf(html.length, opening + MAX_LINK_TAG_LENGTH))
            if (tag.lookingAt()) {
                yield(tag.group())
                from = tag.end()
                continue
            }
            val nextClosing = html.indexOf('>', opening)
            if (nextClosing < 0) break
            from = maxOf(opening + 1, nextClosing - MAX_LINK_TAG_LENGTH + 1)
        }
    }

/**
 * [html] から HTML のコメント（`<!--` から `-->` まで）を除いたもの。前から 1 回なめて除く。閉じていないコメントは、文書の終わりまでとする。
 */
private fun withoutComments(html: String): String =
    buildString(html.length) {
        var position = 0
        while (position < html.length) {
            val opening = html.indexOf(COMMENT_OPENING, position)
            if (opening < 0) {
                append(html, position, html.length)
                break
            }
            append(html, position, opening)
            val closing = html.indexOf(COMMENT_CLOSING, opening + COMMENT_OPENING.length)
            if (closing < 0) break
            position = closing + COMMENT_CLOSING.length
        }
    }

/** 文書の URL [documentUrl] を、相対の URL を解決する基準にしたもの。パスが空なら `/` にする。解釈できなければ null。 */
private fun baseUriOf(documentUrl: String): URI? =
    try {
        URI(documentUrl).let { if (it.rawPath.isNullOrEmpty()) it.resolve("/") else it }
    } catch (e: URISyntaxException) {
        null
    }

/** `<link>` の開始タグ [tag] の属性。名前は小文字にする。同じ名前が重なれば、最初のものを使う。値の無い属性の値は空。 */
private fun attributesOf(tag: String): Map<String, String> =
    buildMap {
        ATTRIBUTE.findAll(tag.substring(LINK_TAG_OPENING_LENGTH).removeSuffix(">")).forEach { match ->
            val name = match.groupValues[1].lowercase()
            if (name !in this) put(name, match.groupValues.drop(2).firstOrNull { it.isNotEmpty() }.orEmpty())
        }
    }

/** `rel` の値 [rel] のトークンに `icon` があるか。 */
private fun hasIconToken(rel: String): Boolean = rel.split(REL_SEPARATOR).any { it.equals(ICON_REL, ignoreCase = true) }

/** [text] の文字参照を、表す文字に戻す。読めない参照はそのまま残す。 */
private fun decodeCharacterReferences(text: String): String =
    CHARACTER_REFERENCE.replace(text) { match ->
        val reference = match.groupValues[1]
        when {
            reference.startsWith("#x", ignoreCase = true) ->
                reference.drop(HEX_REFERENCE_PREFIX_LENGTH).toIntOrNull(HEX_RADIX)?.let(::codePointText)
            reference.startsWith("#") -> reference.drop(DECIMAL_REFERENCE_PREFIX_LENGTH).toIntOrNull()?.let(::codePointText)
            else -> NAMED_REFERENCES[reference]
        } ?: match.value
    }

/** 符号位置 [codePoint] の文字。符号位置として正しくなければ null。 */
private fun codePointText(codePoint: Int): String? =
    codePoint.takeIf(Character::isValidCodePoint)?.let { StringBuilder().appendCodePoint(it).toString() }

/** [href] を [base] を基準に解決した URL。`http`・`https` でないか、ホストが無いか、解決できなければ null。 */
private fun resolveHttpUrl(base: URI, href: String): String? {
    val resolved = try {
        base.resolve(href)
    } catch (e: IllegalArgumentException) {
        // URL として正しくない文字を含む href は、URI.create が IllegalArgumentException で断る
        return null
    }
    if (resolved.scheme?.lowercase() !in HTTP_SCHEMES || resolved.host.isNullOrEmpty()) return null
    return resolved.toASCIIString()
}
