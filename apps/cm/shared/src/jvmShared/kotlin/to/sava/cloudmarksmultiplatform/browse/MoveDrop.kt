package to.sava.cloudmarksmultiplatform.browse

/** ツリーの行と、リストの子のフォルダの行の、上下の端の帯の、行の高さに対する割合。端の帯に落とすと、前か後ろへ並べ替える。 */
private const val EDGE_BAND_FRACTION = 0.25f

/** リストの項目の行を、上半分と下半分に分ける位置の、行の高さに対する割合。 */
private const val HALF_BAND_FRACTION = 0.5f

/** ツリーで、ルートフォルダの子の行の深さ。 */
private const val ROOT_CHILD_DEPTH = 1

/** 行の中で、ポインターのある帯。 */
enum class RowBand {
    /** 上の端。行の前へ並べ替える。 */
    TOP,

    /** 真ん中。行のフォルダの中へ移す。 */
    MIDDLE,

    /** 下の端。行の後ろへ並べ替える。 */
    BOTTOM,
}

/** 行の端。落とす位置の線を引くところ。 */
enum class RowEdge {
    TOP,
    BOTTOM,
}

/**
 * 高さ [height] の行の、上端から [y] の位置の帯。上の端と下の端は、それぞれ行の高さの [EDGE_BAND_FRACTION]。
 * ツリーの行と、リストの子のフォルダの行に使う。
 */
fun edgeBandOf(y: Float, height: Float): RowBand =
    when {
        y < height * EDGE_BAND_FRACTION -> RowBand.TOP
        y >= height * (1 - EDGE_BAND_FRACTION) -> RowBand.BOTTOM
        else -> RowBand.MIDDLE
    }

/** 高さ [height] の行の、上端から [y] の位置が、上半分なら [RowBand.TOP]、下半分なら [RowBand.BOTTOM]。リストの項目の行に使う。 */
fun halfBandOf(y: Float, height: Float): RowBand = if (y < height * HALF_BAND_FRACTION) RowBand.TOP else RowBand.BOTTOM

/** ドラッグして移すものを落とす先として、強調するか、端に線を引く行。 */
sealed interface DropRow {
    /** ツリーの、フォルダ [folderId] の行。 */
    data class TreeFolder(val folderId: String) : DropRow

    /** リストの、子のフォルダ [folderId] の行。 */
    data class ListFolder(val folderId: String) : DropRow

    /** リストの、項目 [itemId] の行。 */
    data class ListItem(val itemId: String) : DropRow
}

/**
 * 並べ替える位置に引く線。
 *
 * @property edge 線を引く、行の端
 * @property depth 線の字下げの深さ。ツリーの行では、挿入先の親の子の行の深さで、その行の字下げの位置から線を引く。リストの行では 0
 */
data class DropLine(val edge: RowEdge, val depth: Int = 0)

/**
 * ドラッグして移すものを落とす先と、その見せ方。
 *
 * @property row 強調するか、端に線を引く行
 * @property line [row] に引く、並べ替える位置の線。null なら線を引かず、[row] を強調する（[MoveDestination.Into] のとき）
 */
data class MoveDrop(val destination: MoveDestination, val row: DropRow, val line: DropLine?)

/**
 * ツリーの行の並び [rows]（[flattenFolderTree] の並び）のうち、フォルダ [folderId] の行の、上端から [y] の位置（行の高さ [height]）へ
 * [subject] を落とすときの、落とす先。移せないか、並びが変わらなければ（[canMove]）null。[folderId] の行が [rows] に無いか、
 * そのフォルダが親の子のフォルダの並びに無くても null。
 *
 * フォルダを落とすときは、上の端（[edgeBandOf]）ならその行のフォルダの前へ置く。下の端なら、その行が開いていて子があれば、
 * その行のフォルダの先頭の子の前へ置く。閉じているか子が無ければ、その行のフォルダの次の兄弟の前へ（次の兄弟が無ければ末尾へ）置く。
 * 真ん中ならその行のフォルダの中の末尾へ移す。
 * ルートフォルダの行と、項目を落とすときは、行のどこでも中へ移す。
 *
 * 位置を指定するときの線は、ポインターのある行の上の端か下の端に引き、挿入先の親の子の行の深さで字下げする。開いていて子のある行の
 * 下の端なら子の深さ、そのほかはその行の深さ。そのため、開いているフォルダの最後の子の下の端（その子の深さ）と、そのフォルダの
 * 次の兄弟の上の端（そのフォルダの深さ）は、同じ行の境目でも字下げで見分けられる。
 */
fun BrowseState.treeDrop(
    rows: List<FolderTreeRow>,
    folderId: String,
    y: Float,
    height: Float,
    subject: MoveSubject,
): MoveDrop? {
    val folders = tree?.state?.folders ?: return null
    val row = rows.firstOrNull { it.folderId == folderId } ?: return null
    val parentId = folders[folderId]?.parent
    val band = edgeBandOf(y, height)
    if (parentId == null || subject !is MoveSubject.Folder || band == RowBand.MIDDLE) {
        return checkedDrop(subject, MoveDestination.Into(folderId), DropRow.TreeFolder(folderId), line = null)
    }
    val beforeFirstChild = band == RowBand.BOTTOM && row.expanded
    val destination = if (beforeFirstChild) {
        positionedDestination(folderId, folders.getValue(folderId).children, boundary = 0, subject)
    } else {
        val siblings = folders.getValue(parentId).children
        positionedDestination(parentId, siblings, boundaryOf(siblings, folderId, band) ?: return null, subject)
    }
    val depth = if (beforeFirstChild) row.depth + 1 else row.depth
    return checkedDrop(subject, destination, DropRow.TreeFolder(folderId), DropLine(edgeOf(band), depth))
}

/**
 * ツリーの行の並び [rows]（[flattenFolderTree] の並び）の最後の行より下の、行の無いところへ [subject] を落とすときの、落とす先。
 * 移せないか、並びが変わらなければ（[canMove]）null。
 *
 * フォルダを落とすときは、ルートフォルダの子のフォルダの末尾へ並べ替える。線は最後の行の下端に、ルートフォルダの子の深さで引く。
 * 項目を落とすときは、ルートフォルダの中の末尾へ移す。ルートフォルダの行を強調する。
 */
fun BrowseState.treeEndDrop(rows: List<FolderTreeRow>, subject: MoveSubject): MoveDrop? {
    val rootId = tree?.state?.rootId ?: return null
    val lastRow = rows.lastOrNull() ?: return null
    return if (subject is MoveSubject.Folder) {
        checkedDrop(subject, MoveDestination.At(rootId, null), DropRow.TreeFolder(lastRow.folderId), DropLine(RowEdge.BOTTOM, ROOT_CHILD_DEPTH))
    } else {
        checkedDrop(subject, MoveDestination.Into(rootId), DropRow.TreeFolder(rootId), line = null)
    }
}

/**
 * 開いているフォルダのリストの行 [row] の、上端から [y] の位置（行の高さ [height]）へ [subject] を落とすときの、落とす先。
 * 移せないか、並びが変わらなければ（[canMove]）null。並べ替えるときに、[row] が開いているフォルダの子のフォルダか読めた項目の並びに
 * 無くても null。
 *
 * 子のフォルダの行は、フォルダを落とすときは、上の端（[edgeBandOf]）ならその子のフォルダの前へ、下の端なら次の子のフォルダの前へ
 * （無ければ末尾へ）並べ替え、真ん中ならその子のフォルダの中の末尾へ移す。項目を落とすときは、行のどこでもその中の末尾へ移す。
 * 項目の行は、項目を落とすときは、上半分（[halfBandOf]）ならその項目の前へ、下半分なら次の項目の前へ（無ければ末尾へ）並べ替える。
 * フォルダは項目の行に落とせない。
 *
 * 位置を指定するときの線は、ポインターのある行の、上の端か下の端に引く。字下げはしない。
 */
fun BrowseState.listDrop(row: DropRow, y: Float, height: Float, subject: MoveSubject): MoveDrop? {
    val folderId = currentFolderId ?: return null
    return when (row) {
        is DropRow.ListFolder -> {
            val band = edgeBandOf(y, height)
            if (subject !is MoveSubject.Folder || band == RowBand.MIDDLE) {
                checkedDrop(subject, MoveDestination.Into(row.folderId), row, line = null)
            } else {
                val children = currentFolder?.children.orEmpty()
                val boundary = boundaryOf(children, row.folderId, band) ?: return null
                checkedDrop(subject, positionedDestination(folderId, children, boundary, subject), row, DropLine(edgeOf(band)))
            }
        }
        is DropRow.ListItem -> {
            if (subject !is MoveSubject.Items) return null
            val band = halfBandOf(y, height)
            val itemIds = currentLoadedItems.map { it.id }
            val boundary = boundaryOf(itemIds, row.itemId, band) ?: return null
            checkedDrop(subject, positionedDestination(folderId, itemIds, boundary, subject), row, DropLine(edgeOf(band)))
        }
        is DropRow.TreeFolder -> null
    }
}

/** [destination] へ移せれば（[canMove]）、[row] に線 [line] を引く（null なら [row] を強調する）落とす先。移せなければ null。 */
private fun BrowseState.checkedDrop(subject: MoveSubject, destination: MoveDestination, row: DropRow, line: DropLine?): MoveDrop? =
    MoveDrop(destination, row, line).takeIf { canMove(subject, destination) }

/** 並び [order] の、[id] の行の帯 [band] に落とすときの、境目の位置。上の端ならその前、下の端ならその後ろ。[id] が並びに無ければ null。 */
private fun boundaryOf(order: List<String>, id: String, band: RowBand): Int? =
    order.indexOf(id).takeIf { it >= 0 }?.let { index -> if (band == RowBand.BOTTOM) index + 1 else index }

/**
 * フォルダ [folderId] の中の並び [order] の、[boundary] 番目の前の境目へ [subject] を置く移動先。前に置くものは、境目より後ろの、移すもので
 * ないうちの最初のもの（無ければ末尾）。移すものの行の端に落としても、移すものの前を指さない。
 */
private fun positionedDestination(folderId: String, order: List<String>, boundary: Int, subject: MoveSubject): MoveDestination.At {
    val moved = subject.movedIds.toSet()
    return MoveDestination.At(folderId, order.drop(boundary).firstOrNull { it !in moved })
}

private fun edgeOf(band: RowBand): RowEdge = if (band == RowBand.TOP) RowEdge.TOP else RowEdge.BOTTOM
