package to.sava.cloudmarksmultiplatform.browse

import to.sava.cloudmarksmultiplatform.operations.TreeState
import to.sava.cloudmarksmultiplatform.operations.insertedBefore
import to.sava.cloudmarksmultiplatform.sync.FolderReadResult

/** 移動先のフォルダへ移すもの。 */
sealed interface MoveSubject {
    /**
     * フォルダ [folderId] の項目。
     *
     * @property folderId 項目のあるフォルダ
     * @property itemIds 移す項目の ID。この順に移す
     */
    data class Items(val folderId: String, val itemIds: List<String>) : MoveSubject

    /** フォルダ [folderId]。子孫ごと移す。 */
    data class Folder(val folderId: String) : MoveSubject
}

/** 移すものを置く先。 */
sealed interface MoveDestination {
    /** 移動先のフォルダ。 */
    val folderId: String

    /**
     * フォルダ [folderId] の中の末尾。項目はその項目の末尾へ、フォルダはその子のフォルダの末尾へ移す。
     * 位置を指定しない移動なので、項目のあるフォルダと、フォルダのいまの親へは移さない（[canMoveTo]）。
     */
    data class Into(override val folderId: String) : MoveDestination

    /**
     * フォルダ [folderId] の中の、[beforeId] の前（`docs/operations.md` の位置）。項目を移すならその項目のうちの、
     * フォルダを移すならその子のフォルダのうちの、どれの前か。null なら末尾。同じフォルダ（同じ親）の中なら並べ替えになる。
     */
    data class At(override val folderId: String, val beforeId: String?) : MoveDestination
}

/** [this] が移すものの ID。項目ならそれらの ID を移す順に、フォルダならそのフォルダの ID。 */
val MoveSubject.movedIds: List<String>
    get() = when (this) {
        is MoveSubject.Items -> itemIds
        is MoveSubject.Folder -> listOf(folderId)
    }

/** 開いているフォルダの、選んでいる項目。表示の並び順。開いているフォルダが無ければ null。 */
val BrowseState.selectedItemsSubject: MoveSubject.Items?
    get() = currentFolderId?.let { folderId -> MoveSubject.Items(folderId, selectedItems.map { it.id }) }

/**
 * リストの項目 [grabbedId] をドラッグし始めたときに移す項目。[grabbedId] を選んでいれば選んでいる項目すべて（表示の並び順）、
 * 選んでいなければ [grabbedId] だけ。開いているフォルダが無ければ null。
 */
fun BrowseState.draggedItems(grabbedId: String): MoveSubject.Items? {
    val selected = selectedItemsSubject ?: return null
    return if (grabbedId in selected.itemIds) selected else selected.copy(itemIds = listOf(grabbedId))
}

/**
 * [tree] で、フォルダ [destinationId] を、位置を指定しない移動（[MoveDestination.Into]）の移動先にできるか。
 * [destinationId] がツリーに無ければできない。
 * 項目を移すときは、項目のあるフォルダを移動先にできない。フォルダを移すときは、そのフォルダ自身と子孫と、いまの親を移動先にできない。
 */
fun MoveSubject.canMoveTo(tree: TreeState, destinationId: String): Boolean {
    if (destinationId !in tree.folders) return false
    return when (this) {
        is MoveSubject.Items -> destinationId != folderId
        is MoveSubject.Folder ->
            destinationId != folderId &&
                destinationId != tree.folders[folderId]?.parent &&
                folderId !in tree.ancestorsOf(destinationId)
    }
}

/**
 * 並び [order] から [moved] を外し、[beforeId] の前へ [moved] の順に入れた並び。[beforeId] が null か、外したあとの並びに無ければ
 * （[moved] に含まれていれば）末尾へ入れる。操作を適用するときと同じ決まり（[insertedBefore]）で決める。
 */
fun reordered(order: List<String>, moved: List<String>, beforeId: String?): List<String> {
    val movedIds = moved.toSet()
    return order.filterNot { it in movedIds }.insertedBefore(moved, beforeId) { it }
}

/**
 * 読んであるツリーで、[subject] を [destination] へ移せるか。ツリーを読めていなければ移せない。
 *
 * [MoveDestination.Into] は [canMoveTo] で決める。
 * [MoveDestination.At] は、移動先のフォルダがツリーにあれば、同じフォルダ（同じ親）の中の並べ替えも受け付ける。ただし、並びが変わらない
 * もの（[reordered] が元の並びと同じ。自分のすぐ前やすぐ後ろへ置くなど）は受け付けない。並びは、項目なら移動先のフォルダの読めた項目、
 * フォルダなら移動先の子のフォルダで確かめる。移動先のフォルダの項目を読めていなければ、並びが変わるものとする。
 * フォルダは、ルートフォルダを動かせず、自身と子孫へは移せない。移す項目が無ければ移せない。
 */
fun BrowseState.canMove(subject: MoveSubject, destination: MoveDestination): Boolean {
    val treeState = tree?.state ?: return false
    return when (destination) {
        is MoveDestination.Into -> subject.canMoveTo(treeState, destination.folderId)
        is MoveDestination.At -> destination.folderId in treeState.folders && when (subject) {
            is MoveSubject.Items -> subject.itemIds.isNotEmpty() && changesItemOrder(subject, destination)
            is MoveSubject.Folder -> treeState.canPlaceFolder(subject.folderId, destination)
        }
    }
}

/** 項目 [subject] を [destination] へ置くと、並びが変わるか。別のフォルダへ移すなら変わる。 */
private fun BrowseState.changesItemOrder(subject: MoveSubject.Items, destination: MoveDestination.At): Boolean {
    if (subject.folderId != destination.folderId) return true
    val order = (folderReads[destination.folderId] as? FolderReadResult.Loaded)?.items?.map { it.id } ?: return true
    return reordered(order, subject.itemIds, destination.beforeId) != order
}

/** フォルダ [folderId] を [destination] へ置けるか。ルートフォルダでなく、移動先が自身か子孫でなく、並びが変わるとき。 */
private fun TreeState.canPlaceFolder(folderId: String, destination: MoveDestination.At): Boolean {
    val parentId = folders[folderId]?.parent ?: return false
    if (destination.folderId == folderId || folderId in ancestorsOf(destination.folderId)) return false
    if (parentId != destination.folderId) return true
    val siblings = folders.getValue(parentId).children
    return reordered(siblings, listOf(folderId), destination.beforeId) != siblings
}
