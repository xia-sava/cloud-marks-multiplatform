package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import to.sava.cloudmarksmultiplatform.browse.A
import to.sava.cloudmarksmultiplatform.browse.A1
import to.sava.cloudmarksmultiplatform.browse.MoveSubject
import to.sava.cloudmarksmultiplatform.browse.browseWithTree
import to.sava.cloudmarksmultiplatform.browse.loadedTree
import to.sava.cloudmarksmultiplatform.sync.item
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 札をカーソルからずらす量として使う、画素の数。 */
private val LABEL_GAP = IntOffset(16, 16)

class MoveDragLabelTest {
    /** A1 を開き、その項目として item(1)〜item(3) を読んだ状態。 */
    private val browse = browseWithTree(loadedTree(loadedItems = mapOf(A1 to (1..3).map { item(it) }))).openFolder(A1)

    // 項目が 2 つ以上なら「N 件の項目」、1 つならそのタイトル、フォルダならその名前
    @Test
    fun labelDependsOnWhatIsDragged() {
        val cases = listOf(
            MoveSubject.Items(A1, (1..3).map { item(it).id }) to "3 件の項目",
            MoveSubject.Items(A1, listOf(item(1).id, item(3).id)) to "2 件の項目",
            MoveSubject.Items(A1, listOf(item(2).id)) to item(2).title,
            MoveSubject.Folder(A) to "A",
        )
        cases.forEach { (subject, expected) ->
            assertEquals(expected, moveDragLabelOf(subject, browse), "$subject")
        }
    }

    // 項目のタイトルとフォルダの名前は、空でもそのまま使う（S3 の値をそのまま）
    @Test
    fun emptyTitleIsUsedAsIs() {
        val untitled = item(1).copy(title = "")
        val withUntitled = browseWithTree(loadedTree(loadedItems = mapOf(A1 to listOf(untitled)))).openFolder(A1)

        assertEquals("", moveDragLabelOf(MoveSubject.Items(A1, listOf(untitled.id)), withUntitled))
    }

    // 読んである項目に無い 1 つの項目、ツリーに無いフォルダ、項目が無いときは、札を出さない
    @Test
    fun noLabelForUnknownSubject() {
        assertNull(moveDragLabelOf(MoveSubject.Items(A1, listOf(item(9).id)), browse))
        assertNull(moveDragLabelOf(MoveSubject.Folder("unknown"), browse))
        assertNull(moveDragLabelOf(MoveSubject.Items(A1, emptyList()), browse))
    }

    // 札はカーソルから右下へずらして置く。枠の右端・下端からはみ出すなら、はみ出さないところまで戻す。枠より大きい札は左端・上端に揃える
    @Test
    fun labelStaysInsideFrame() {
        val frame = IntSize(400, 300)
        val label = IntSize(100, 20)
        val cases = listOf(
            Triple("枠の中", Offset(50f, 50f) to label, IntOffset(66, 66)),
            Triple("右端の近く", Offset(350f, 50f) to label, IntOffset(300, 66)),
            Triple("下端の近く", Offset(50f, 290f) to label, IntOffset(66, 280)),
            Triple("右下の角の近く", Offset(399f, 299f) to label, IntOffset(300, 280)),
            Triple("枠より幅の広い札", Offset(50f, 50f) to IntSize(500, 20), IntOffset(0, 66)),
        )
        cases.forEach { (name, case, expected) ->
            val (pointer, size) = case
            assertEquals(expected, dragLabelPosition(pointer, LABEL_GAP, size, frame), name)
        }
    }
}
