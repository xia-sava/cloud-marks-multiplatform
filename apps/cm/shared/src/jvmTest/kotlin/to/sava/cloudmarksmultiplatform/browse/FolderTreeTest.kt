package to.sava.cloudmarksmultiplatform.browse

import to.sava.cloudmarksmultiplatform.sync.fileId
import kotlin.test.Test
import kotlin.test.assertEquals

class FolderTreeTest {
    private val tree = loadedTree().state

    /** 行を、(ID, 深さ, 子があるか, 開いているか) の組にする。 */
    private fun List<FolderTreeRow>.summary(): List<String> =
        map { "${it.folderId}:${it.depth}:${if (it.hasChildren) "+" else "-"}:${if (it.expanded) "open" else "closed"}" }

    private fun row(folderId: String, depth: Int, hasChildren: Boolean, expanded: Boolean): String =
        "$folderId:$depth:${if (hasChildren) "+" else "-"}:${if (expanded) "open" else "closed"}"

    // 開いているノードの子だけを、ルートフォルダから深さ優先の行きがけ順に、深さを付けて並べる。ルートフォルダは必ず先頭に出る。
    // 閉じたノードの子孫は、開いていても出さない
    @Test
    fun flattensOnlyExpandedNodesInPreorder() {
        val cases: List<Pair<Set<String>, List<String>>> = listOf(
            // 何も開いていない：ルートフォルダだけ
            emptySet<String>() to listOf(row(ROOT, 0, hasChildren = true, expanded = false)),
            // ルートフォルダだけを開いている：ルートフォルダと、その子
            setOf(ROOT) to listOf(
                row(ROOT, 0, hasChildren = true, expanded = true),
                row(A, 1, hasChildren = true, expanded = false),
                row(B, 1, hasChildren = false, expanded = false),
            ),
            // すべて開いている：行きがけ順で、兄弟はマニフェストの行の順
            setOf(ROOT, A, A1) to listOf(
                row(ROOT, 0, hasChildren = true, expanded = true),
                row(A, 1, hasChildren = true, expanded = true),
                row(A1, 2, hasChildren = true, expanded = true),
                row(A1A, 3, hasChildren = false, expanded = false),
                row(A2, 2, hasChildren = false, expanded = false),
                row(B, 1, hasChildren = false, expanded = false),
            ),
            // A を閉じている：A1 が開いていても、A の子孫は出さない
            setOf(ROOT, A1) to listOf(
                row(ROOT, 0, hasChildren = true, expanded = true),
                row(A, 1, hasChildren = true, expanded = false),
                row(B, 1, hasChildren = false, expanded = false),
            ),
        )
        cases.forEach { (expanded, expected) ->
            assertEquals(expected, flattenFolderTree(tree, expanded).summary(), "開いているノード: $expanded")
        }
    }

    // 子の無いノードと、ツリーに無いフォルダは、開いているノードに含まれていても開いた行にならない
    @Test
    fun leavesAndUnknownFoldersAreNotExpanded() {
        val rows = flattenFolderTree(tree, setOf(ROOT, B, "unknown"))

        assertEquals(
            listOf(
                row(ROOT, 0, hasChildren = true, expanded = true),
                row(A, 1, hasChildren = true, expanded = false),
                row(B, 1, hasChildren = false, expanded = false),
            ),
            rows.summary(),
        )
    }

    // ルートフォルダの行は、深さ 0 で名前が空。ルートフォルダの行だけがルートフォルダの行として扱われる
    @Test
    fun rootRowIsFirstWithEmptyName() {
        val rows = flattenFolderTree(tree, setOf(ROOT))

        assertEquals("", rows.first().name)
        assertEquals(listOf(true, false, false), rows.map { it.isRoot })
    }

    // 開いているノードに含まれていれば閉じ（除き）、含まれていなければ開く（足す）。ほかのノードは変えない
    @Test
    fun toggledFlipsOnlyGivenFolder() {
        assertEquals(setOf(ROOT), setOf(ROOT, A).withToggled(A))
        assertEquals(setOf(ROOT, A, B), setOf(ROOT, A).withToggled(B))
    }

    // 祖先は、親からルートフォルダへ向かう順。ルートフォルダと、ツリーに無いフォルダには祖先が無い
    @Test
    fun ancestorsFromParentToRoot() {
        assertEquals(listOf(A1, A, ROOT), tree.ancestorsOf(A1A))
        assertEquals(emptyList(), tree.ancestorsOf(ROOT))
        assertEquals(emptyList(), tree.ancestorsOf("unknown"))
    }

    // 項目の数は、そのフォルダと子孫のフォルダの、マニフェストの count の合計。項目ファイルを読んでいなくても数える。
    // 子孫の無いフォルダはそのフォルダの count だけ、ツリーに無いフォルダは 0
    @Test
    fun itemCountAddsDescendantCounts() {
        // ルートフォルダ 1、A 2、A1 4、A1a 8、A2 16、B 32 件
        val counts = mapOf(ROOT to 1, A to 2, A1 to 4, A1A to 8, A2 to 16, B to 32)
        val entries = treeEntries().mapIndexed { index, entry ->
            entry.copy(items = fileId(index + 10), count = counts.getValue(entry.id))
        }
        val counted = loadedTree(entries).state
        val expected = mapOf(
            ROOT to 63,
            A to 2 + 4 + 8 + 16,
            A1 to 4 + 8,
            A1A to 8,
            A2 to 16,
            B to 32,
            "unknown" to 0,
        )

        assertEquals(expected, expected.keys.associateWith { counted.itemCountWithDescendants(it) })
        assertEquals(emptyMap(), counted.items.filterKeys { it in counts })
    }
}
