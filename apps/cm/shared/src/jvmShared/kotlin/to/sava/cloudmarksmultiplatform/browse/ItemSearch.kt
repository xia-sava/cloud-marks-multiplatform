package to.sava.cloudmarksmultiplatform.browse

import to.sava.cloudmarksmultiplatform.operations.ItemRef
import to.sava.cloudmarksmultiplatform.storage.Item
import to.sava.cloudmarksmultiplatform.sync.FolderReadResult
import java.text.Normalizer

/**
 * 項目の検索の状態。検索を開いているあいだ持つ。
 *
 * @property query 入力した語。空白で区切って複数の語にできる
 */
data class ItemSearchState(
    val query: String = "",
)

/**
 * 検索の結果の 1 件。
 *
 * @property ref 項目と、その項目が入っているフォルダの組
 * @property item 項目
 * @property folderPath ルートフォルダの子から、項目が入っているフォルダまでの、フォルダの名前。ルートフォルダ直下の項目なら空
 */
data class ItemSearchResult(
    val ref: ItemRef,
    val item: Item,
    val folderPath: List<String>,
)

private val WHITESPACES = Regex("\\s+")

/**
 * 検索で照らし合わせるために [text] をそろえる。NFKC で正規化してから、ロケールに依らずに小文字にする。
 * 大文字と小文字、全角と半角は区別しなくなる。ひらがなとカタカナはそろえない。
 */
fun normalizeSearchText(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()

/** 入力した語 [query] を、[normalizeSearchText] でそろえてから空白で区切った語。空の語は含まない。 */
fun searchTerms(query: String): List<String> = normalizeSearchText(query).split(WHITESPACES).filter { it.isNotEmpty() }

/**
 * 入力した語 [query] に合う項目を、ツリーの順で返す。呼ぶたびに計算する。
 *
 * 照らし合わせるのは項目のタイトルと URL で、フォルダの名前は対象にしない。[query] の語ごとに、タイトルか URL のどちらかに
 * 含まれていなければならず、すべての語が含まれる項目を返す。語が 1 つも無ければ空のリスト。
 * 並びは、フォルダがルートフォルダから深さ優先の行きがけ順、フォルダの中は項目の並び。
 * 対象は、項目を読めたフォルダの項目で、溜めた削除の項目は含まない。
 */
fun BrowseState.searchItems(query: String): List<ItemSearchResult> {
    val terms = searchTerms(query).ifEmpty { return emptyList() }
    val treeState = tree?.state ?: return emptyList()
    return treeState.foldersInPreorder().flatMap { folder ->
        val loaded = folderReads[folder.id] as? FolderReadResult.Loaded ?: return@flatMap emptyList()
        val matched = shownItems(folder.id, loaded.items).filter { it.matchesAll(terms) }
        if (matched.isEmpty()) return@flatMap emptyList()
        val folderPath = (treeState.ancestorsOf(folder.id).asReversed() + folder.id).drop(1).mapNotNull { treeState.folders[it]?.name }
        matched.map { ItemSearchResult(ItemRef(folder.id, it.id), it, folderPath) }
    }
}

/** そろえた語 [terms] のすべてが、それぞれ項目のタイトルか URL のどちらかに含まれるか。 */
private fun Item.matchesAll(terms: List<String>): Boolean {
    val title = normalizeSearchText(title)
    val url = normalizeSearchText(url)
    return terms.all { it in title || it in url }
}
