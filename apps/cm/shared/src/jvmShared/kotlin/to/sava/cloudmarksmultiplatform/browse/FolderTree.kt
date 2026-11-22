package to.sava.cloudmarksmultiplatform.browse

import to.sava.cloudmarksmultiplatform.operations.FolderNode
import to.sava.cloudmarksmultiplatform.operations.TreeState

/**
 * ツリーに描く 1 行。開いているノードの子だけを、ルートフォルダから深さ優先の行きがけ順に並べたものの 1 つ。
 *
 * @property name フォルダの名前。ルートフォルダは空
 * @property depth ルートフォルダを 0 とする深さ
 * @property hasChildren 子のフォルダがあるか。無ければ開閉のボタンを付けない
 * @property expanded 開いていて、子の行が続くか
 */
data class FolderTreeRow(
    val folderId: String,
    val name: String,
    val depth: Int,
    val hasChildren: Boolean,
    val expanded: Boolean,
) {
    /** ルートフォルダの行か。 */
    val isRoot: Boolean get() = depth == 0
}

/**
 * [tree] を、[expanded] のノードだけを開いた行の並びにする。並びはルートフォルダから深さ優先の行きがけ順で、兄弟の並びはマニフェストの行の順。
 *
 * ルートフォルダは必ず先頭に出る。閉じたノードの子孫は、開いていても出さない。[expanded] の中の、ツリーに無いフォルダは無視する。
 */
fun flattenFolderTree(tree: TreeState, expanded: Set<String>): List<FolderTreeRow> =
    buildList {
        fun visit(folderId: String, depth: Int) {
            val node = tree.folders.getValue(folderId)
            val hasChildren = node.children.isNotEmpty()
            val open = hasChildren && folderId in expanded
            add(FolderTreeRow(node.id, node.name, depth, hasChildren, open))
            if (open) node.children.forEach { visit(it, depth + 1) }
        }
        visit(tree.rootId, depth = 0)
    }

/** ツリーの全フォルダ。ルートフォルダから深さ優先の行きがけ順で、兄弟の並びはマニフェストの行の順。 */
fun TreeState.foldersInPreorder(): List<FolderNode> =
    buildList {
        fun visit(folderId: String) {
            val node = folders[folderId] ?: return
            add(node)
            node.children.forEach(::visit)
        }
        visit(rootId)
    }

/** ツリーで開いているノード [this] の、[folderId] を開く、または閉じたもの。 */
fun Set<String>.withToggled(folderId: String): Set<String> = if (folderId in this) this - folderId else this + folderId

/** [folderId] の祖先のフォルダの ID。親からルートフォルダへ向かう順。ルートフォルダと、ツリーに無いフォルダには空。 */
fun TreeState.ancestorsOf(folderId: String): List<String> =
    generateSequence(folders[folderId]?.parent) { folders[it]?.parent }.toList()

/**
 * フォルダ [folderId] と、その子孫のフォルダの項目の数の合計。各フォルダの数は [countOf] で決め、既定ではマニフェストの `count` を足す
 * （項目ファイルは読まない）。ツリーに無いフォルダは 0。
 */
fun TreeState.itemCountWithDescendants(folderId: String, countOf: (String) -> Int = { folders[it]?.count ?: 0 }): Int =
    folders[folderId]?.let { node -> countOf(node.id) + node.children.sumOf { itemCountWithDescendants(it, countOf) } } ?: 0
