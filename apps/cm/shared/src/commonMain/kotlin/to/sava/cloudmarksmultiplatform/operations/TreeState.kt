package to.sava.cloudmarksmultiplatform.operations

import to.sava.cloudmarksmultiplatform.storage.FolderEntry
import to.sava.cloudmarksmultiplatform.storage.Item
import to.sava.cloudmarksmultiplatform.storage.Manifest

/**
 * フォルダのツリーの 1 ノード。
 *
 * @property children 子のフォルダの ID。並びがマニフェストの行の順になる
 * @property itemsFileId マニフェストが指している、このフォルダの項目ファイルの ID。項目が無ければ null
 * @property count マニフェストが持っている、項目の数。[itemsFileId] が null のときだけ 0
 */
data class FolderNode(
    val id: String,
    val parent: String?,
    val name: String,
    val children: List<String>,
    val itemsFileId: String?,
    val count: Int,
)

/**
 * ツリーの状態。操作の適用の入力と出力になる不変の値。
 *
 * @property folders フォルダの ID から [FolderNode] への対応。ルートフォルダを含む
 * @property items 項目を読み込んだフォルダの、項目の並び。項目を持たないフォルダは、読まなくても分かるので最初から含む。
 * 読み込んでいないフォルダは含まない
 */
data class TreeState(
    val rootId: String,
    val folders: Map<String, FolderNode>,
    val items: Map<String, List<Item>>,
) {
    /** フォルダ [folderId] の項目を読み込んだ状態にする。 */
    fun withLoadedItems(folderId: String, loaded: List<Item>): TreeState {
        require(folderId in folders) { "フォルダ $folderId はツリーに無い" }
        return copy(items = items + (folderId to loaded))
    }
}

/** マニフェストからツリーの状態を作る。 */
fun Manifest.toTreeState(): TreeState {
    val childrenByParent = folders.groupBy({ it.parent }, { it.id })
    val nodes = folders.map { entry ->
        FolderNode(
            id = entry.id,
            parent = entry.parent,
            name = entry.name,
            children = childrenByParent[entry.id].orEmpty(),
            itemsFileId = entry.items,
            count = entry.count,
        )
    }
    return TreeState(
        rootId = folders.first().id,
        folders = nodes.associateBy { it.id },
        items = nodes.filter { it.itemsFileId == null }.associate { it.id to emptyList() },
    )
}

/**
 * 適用した結果から、マニフェストのフォルダの行を、ルートフォルダから深さ優先の行きがけ順で作る。
 *
 * [newFileIds] は、項目ファイルを置くフォルダ（[ApplyResult.itemsToWrite] のフォルダ）の ID から、
 * 置く項目ファイルの新しい ID への対応。過不足があってはならない。
 * その `items` はこの ID に、`count` は項目の数になる。
 * それ以外のフォルダは、項目を読み込んでいなければ、また項目の並びが変わっていなければ、元の `items` と `count` を引き継ぐ。
 * 項目が 0 件のフォルダは `items` を null、`count` を 0 にする。
 *
 * @throws IllegalArgumentException [newFileIds] のフォルダが [ApplyResult.itemsToWrite] のフォルダと違うとき
 */
fun ApplyResult.toFolderEntries(newFileIds: Map<String, String>): List<FolderEntry> {
    require(newFileIds.keys == itemsToWrite.keys) {
        "新しいファイルの ID を振るフォルダが、ファイルを置くフォルダと違う: " +
            "不足 ${itemsToWrite.keys - newFileIds.keys}、余分 ${newFileIds.keys - itemsToWrite.keys}"
    }
    return state.entriesFrom(state.rootId, newFileIds)
}

private fun TreeState.entriesFrom(folderId: String, newFileIds: Map<String, String>): List<FolderEntry> =
    folders.getValue(folderId).let { node ->
        listOf(entryOf(node, newFileIds)) + node.children.flatMap { entriesFrom(it, newFileIds) }
    }

private fun TreeState.entryOf(node: FolderNode, newFileIds: Map<String, String>): FolderEntry {
    val loaded = items[node.id]
    val (itemsFileId, count) = when {
        loaded == null -> node.itemsFileId to node.count
        loaded.isEmpty() -> null to 0
        node.id in newFileIds -> newFileIds.getValue(node.id) to loaded.size
        else -> {
            check(loaded.size == node.count) {
                "フォルダ ${node.id} の項目の数が ${node.count} から ${loaded.size} に変わっているが、新しいファイルの ID が無い"
            }
            node.itemsFileId to node.count
        }
    }
    return FolderEntry(node.id, node.parent, node.name, itemsFileId, count)
}
