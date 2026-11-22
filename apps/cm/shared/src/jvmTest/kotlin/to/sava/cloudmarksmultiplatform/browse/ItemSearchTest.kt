package to.sava.cloudmarksmultiplatform.browse

import to.sava.cloudmarksmultiplatform.operations.ItemRef
import to.sava.cloudmarksmultiplatform.storage.FolderEntry
import to.sava.cloudmarksmultiplatform.storage.Item
import to.sava.cloudmarksmultiplatform.storage.StorageProblem
import to.sava.cloudmarksmultiplatform.sync.FolderReadResult
import to.sava.cloudmarksmultiplatform.sync.SyncFailure
import to.sava.cloudmarksmultiplatform.sync.fileId
import to.sava.cloudmarksmultiplatform.sync.itemId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

private fun searchItem(n: Int, title: String, url: String): Item = Item(itemId(n), url, title)

private val ROOT_ITEM = searchItem(1, "Root Page", "https://example.com/root")
private val A1_FIRST = searchItem(2, "Kotlin 入門", "https://kotlinlang.org/docs")
private val A1_SECOND = searchItem(3, "Compose ガイド", "https://example.com/compose/guide")
private val A1A_ITEM = searchItem(4, "Deep Kotlin", "https://example.com/deep")
private val A2_ITEM = searchItem(5, "ＫＯＴＬＩＮ　全角", "https://example.com/other")

/** 項目を持つフォルダの、項目ファイルの ID。 */
private val ITEMS_FILE_IDS = mapOf(ROOT to fileId(10), A1 to fileId(11), A1A to fileId(12), A2 to fileId(13))

/**
 * 項目を読み込んだフォルダの項目。並びは、ツリーの行きがけ順（ルートフォルダ、A1、A1A、A2）と逆にする。
 * 項目を読んだ結果の並びがツリーの行きがけ順と違っても、結果はツリーの順になることを確かめるため。
 */
private val SEARCH_ITEMS = mapOf(
    A2 to listOf(A2_ITEM),
    A1A to listOf(A1A_ITEM),
    A1 to listOf(A1_FIRST, A1_SECOND),
    ROOT to listOf(ROOT_ITEM),
)

/** [treeEntries] のうち、ルートフォルダ・A1・A1A・A2 が項目を持つ行。 */
private fun searchEntries(): List<FolderEntry> =
    treeEntries().map { entry ->
        ITEMS_FILE_IDS[entry.id]?.let { entry.copy(items = it, count = SEARCH_ITEMS.getValue(entry.id).size) } ?: entry
    }

/** ルートフォルダ・A1・A1A・A2 の項目をすべて読んだ状態。 */
private fun browseWithSearchItems(): BrowseState = browseWithTree(loadedTree(searchEntries(), loadedItems = SEARCH_ITEMS))

class ItemSearchTest {
    // NFKC で全角と半角をそろえ、小文字にする。ひらがなとカタカナはそろえない
    @Test
    fun normalizeSearchTextUnifiesWidthAndCase() {
        val cases = mapOf(
            "ＡＢＣ　Ｄ１" to "abc d1",
            "Kotlin" to "kotlin",
            "ｶﾀｶﾅ" to "カタカナ",
            "ひらがな" to "ひらがな",
            "カタカナ" to "カタカナ",
        )
        cases.forEach { (text, expected) -> assertEquals(expected, normalizeSearchText(text), text) }
    }

    // 入力した語は、そろえてから空白（全角の空白も）で区切る。空の語は捨てる
    @Test
    fun searchTermsSplitsOnWhitespace() {
        val cases = mapOf(
            "foo bar" to listOf("foo", "bar"),
            "  Foo　BAR \t baz  " to listOf("foo", "bar", "baz"),
            "ＦＯＯ" to listOf("foo"),
            "" to emptyList(),
            "   　 " to emptyList(),
        )
        cases.forEach { (query, expected) -> assertEquals(expected, searchTerms(query), "「$query」") }
    }

    // 語が 1 つも無ければ（空文字・空白だけ）、結果は空
    @Test
    fun blankQueryHasNoResults() {
        val state = browseWithSearchItems()

        listOf("", " ", "　 \t").forEach { assertEquals(emptyList(), state.searchItems(it), "「$it」") }
    }

    // タイトルと URL のどちらにも合わせる。大文字と小文字、全角と半角は区別しない。ひらがなとカタカナは区別する
    @Test
    fun matchesTitleOrUrlIgnoringCaseAndWidth() {
        val cases: List<Pair<String, List<Item>>> = listOf(
            "root page" to listOf(ROOT_ITEM),
            "ROOT" to listOf(ROOT_ITEM),
            "kotlinlang.org" to listOf(A1_FIRST),
            "ＫＯＴＬＩＮ" to listOf(A1_FIRST, A1A_ITEM, A2_ITEM),
            "kotlin" to listOf(A1_FIRST, A1A_ITEM, A2_ITEM),
            "入門" to listOf(A1_FIRST),
            "ｇｕｉｄｅ" to listOf(A1_SECOND),
            "全角" to listOf(A2_ITEM),
            "存在しない語" to emptyList(),
        )
        val state = browseWithSearchItems()

        cases.forEach { (query, expected) ->
            assertEquals(expected, state.searchItems(query).map { it.item }, "「$query」")
        }
    }

    // ひらがなとカタカナはそろえない
    @Test
    fun hiraganaAndKatakanaAreDistinct() {
        val state = browseWithTree(
            loadedTree(
                searchEntries(),
                loadedItems = SEARCH_ITEMS + (A1 to listOf(searchItem(6, "ひらがな", "https://example.com/h"), searchItem(7, "カタカナ", "https://example.com/k"))),
            ),
        )

        assertEquals(listOf(itemId(6)), state.searchItems("ひらがな").map { it.item.id })
        assertEquals(listOf(itemId(7)), state.searchItems("カタカナ").map { it.item.id })
    }

    // 既定のロケールがトルコ語でも、大文字の I は小文字の i に合う（小文字にするのはロケールに依らない）
    @Test
    fun matchingDoesNotDependOnDefaultLocale() {
        val state = browseWithSearchItems()
        val original = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr"))
        try {
            assertEquals("kotlin", normalizeSearchText("KOTLIN"))
            assertEquals(listOf(A1_FIRST, A1A_ITEM, A2_ITEM), state.searchItems("KOTLIN").map { it.item })
        } finally {
            Locale.setDefault(original)
        }
    }

    // 1 つの語は、タイトルと URL のどちらか一方に含まれなければならない。タイトルの終わりと URL の初めにまたがっては合わない
    @Test
    fun termDoesNotSpanTitleAndUrl() {
        val boundary = searchItem(8, "abc", "https://def.example")
        val state = browseWithTree(loadedTree(searchEntries(), loadedItems = SEARCH_ITEMS + (A1 to listOf(boundary))))

        assertEquals(listOf(boundary), state.searchItems("abc").map { it.item })
        assertEquals(listOf(boundary), state.searchItems("abc def").map { it.item })
        assertEquals(emptyList(), state.searchItems("abchttps"))
        assertEquals(emptyList(), state.searchItems("exampleabc"))
    }

    // 語ごとに AND。1 つの語はタイトルと URL のどちらに含まれていてもよい。語の順や位置は問わない
    @Test
    fun allTermsMustMatchTitleOrUrl() {
        val state = browseWithSearchItems()
        val cases: List<Pair<String, List<Item>>> = listOf(
            "kotlin docs" to listOf(A1_FIRST),
            "docs kotlin" to listOf(A1_FIRST),
            "kotlin 入門 kotlinlang" to listOf(A1_FIRST),
            "compose guide" to listOf(A1_SECOND),
            "kotlin guide" to emptyList(),
            "kotlin　deep" to listOf(A1A_ITEM),
            "kotlin 存在しない語" to emptyList(),
        )

        cases.forEach { (query, expected) ->
            assertEquals(expected, state.searchItems(query).map { it.item }, "「$query」")
        }
    }

    // フォルダの名前は照らし合わせない
    @Test
    fun folderNamesAreNotSearched() {
        val state = browseWithSearchItems()

        assertEquals(emptyList(), state.searchItems("A1a"))
        assertEquals(emptyList(), state.searchItems("A2"))
    }

    // 結果の並びは、フォルダがルートフォルダから深さ優先の行きがけ順で、フォルダの中は項目の並び。フォルダの名前は、
    // ルートフォルダの子から、項目が入っているフォルダまで。ルートフォルダ直下の項目は空
    @Test
    fun resultsFollowTreeOrderWithFolderPaths() {
        val results = browseWithSearchItems().searchItems("https")

        assertEquals(
            listOf(
                ItemSearchResult(ItemRef(ROOT, ROOT_ITEM.id), ROOT_ITEM, emptyList()),
                ItemSearchResult(ItemRef(A1, A1_FIRST.id), A1_FIRST, listOf("A", "A1")),
                ItemSearchResult(ItemRef(A1, A1_SECOND.id), A1_SECOND, listOf("A", "A1")),
                ItemSearchResult(ItemRef(A1A, A1A_ITEM.id), A1A_ITEM, listOf("A", "A1", "A1a")),
                ItemSearchResult(ItemRef(A2, A2_ITEM.id), A2_ITEM, listOf("A", "A2")),
            ),
            results,
        )
    }

    // 溜めた削除の項目は、結果に含めない
    @Test
    fun pendingDeletesAreExcluded() {
        val state = browseWithSearchItems().withPendingDeletes(setOf(ItemRef(A1, A1_FIRST.id), ItemRef(A2, A2_ITEM.id)))

        assertEquals(listOf(A1A_ITEM), state.searchItems("kotlin").map { it.item })
    }

    // 項目を読めたフォルダの項目だけを対象にする。読んでいないフォルダ、読めなかったフォルダ、壊れたフォルダの項目は含めない
    @Test
    fun onlyLoadedFoldersAreSearched() {
        val base = browseWithSearchItems()
        val state = base.copy(
            folderReads = base.folderReads - A1A +
                (A1 to FolderReadResult.Failed(SyncFailure.Forbidden)) +
                (A2 to FolderReadResult.Broken(StorageProblem(line = null, message = "壊れている"))),
        )

        assertEquals(listOf(ROOT_ITEM), state.searchItems("https").map { it.item })
    }

    // ツリーを読んでいなければ、結果は空
    @Test
    fun noTreeHasNoResults() {
        assertEquals(emptyList(), BrowseState().searchItems("kotlin"))
    }
}
