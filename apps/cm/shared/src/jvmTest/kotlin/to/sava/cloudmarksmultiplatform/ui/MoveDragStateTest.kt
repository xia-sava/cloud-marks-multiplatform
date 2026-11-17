package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.IntSize
import to.sava.cloudmarksmultiplatform.browse.DropRow
import to.sava.cloudmarksmultiplatform.browse.MoveDestination
import to.sava.cloudmarksmultiplatform.browse.MoveDrop
import to.sava.cloudmarksmultiplatform.browse.MoveSubject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** ドラッグを始める閾値として使う、画素の数。 */
private const val THRESHOLD_PX = 8f

/** ツリーの 1 行の高さとして使う、画素の数。 */
private const val ROW_HEIGHT = 40

/** ツリーの枠の幅として使う、画素の数。 */
private const val TREE_WIDTH = 100

/** 見えている行の 1 つ。キーはフォルダの ID。 */
private class Row(override val key: String, override val offset: Int, override val index: Int) : LazyListItemInfo {
    override val size: Int = ROW_HEIGHT
}

/**
 * ツリーの枠の、見えている行。[rows] のキーを上から [ROW_HEIGHT] ずつずらして並べ、先頭の行の位置を [firstOffset] にする
 * （上へスクロールして半ばまで隠れていれば負）。枠の大きさは、幅 [TREE_WIDTH]、高さ [height]。
 *
 * @param viewportStartOffset 枠の先頭の余白の幅を負にしたもの
 */
private class TreeLayout(
    rows: List<String>,
    firstOffset: Int = 0,
    height: Int = ROW_HEIGHT * rows.size,
    override val viewportStartOffset: Int = 0,
) : LazyListLayoutInfo {
    override val visibleItemsInfo: List<LazyListItemInfo> =
        rows.mapIndexed { index, key -> Row(key, firstOffset + ROW_HEIGHT * index, index) }
    override val viewportEndOffset: Int = height
    override val totalItemsCount: Int = rows.size
    override val viewportSize: IntSize = IntSize(TREE_WIDTH, height)
}

/** ドラッグしているポインターとして使う、ポインターの ID。 */
private val POINTER = PointerId(0)

/** 自動でスクロールさせる帯の幅として使う、画素の数。 */
private const val EDGE_PX = 48f

/** 自動でスクロールさせる向きを確かめる枠の大きさ。 */
private val VIEWPORT = IntSize(100, 200)

/** フォルダ [folderId] の中へ移す落とす先。 */
private fun intoDrop(folderId: String): MoveDrop = MoveDrop(MoveDestination.Into(folderId), DropRow.TreeFolder(folderId), line = null)

/**
 * 窓の座標をそのまま枠の中の座標とし、[layout] の行の下へ落とすと、その行のフォルダの中へ移す落とす先。[layout] を替えるとスクロールになる。
 */
private class FakeDropTargets(layout: TreeLayout) : MoveDropTargets {
    var layout: TreeLayout by mutableStateOf(layout)

    override fun dropAt(pointer: Offset, subject: MoveSubject): MoveDrop? =
        layout.rowAt(pointer)?.let { intoDrop(it.key as String) }
}

class MoveDragStateTest {
    private val folder = MoveSubject.Folder("moving")

    /** 上下に並んだ 2 つのツリーの行を落とす先にした状態。上が "upper"、下が "lower"。 */
    private fun stateWithRows(targets: MoveDropTargets = FakeDropTargets(TreeLayout(listOf("upper", "lower")))): MoveDragState =
        MoveDragState().apply { addDropTargets(targets) }

    // マウスの主ボタンで押し、操作を受け付けていて、閾値を越えて動かしたときだけ始める。閾値ちょうどまでは始めない
    @Test
    fun dragStartsOnlyBeyondThresholdWithMousePrimaryButton() {
        val beyond = Offset(THRESHOLD_PX + 1f, 0f)
        val cases = listOf(
            "閾値を越えた" to Triple(PointerType.Mouse, beyond, true),
            "斜めに閾値を越えた" to Triple(PointerType.Mouse, Offset(6f, 6f), true),
            "閾値ちょうど" to Triple(PointerType.Mouse, Offset(THRESHOLD_PX, 0f), false),
            "わずかに動いた" to Triple(PointerType.Mouse, Offset(2f, 1f), false),
            "タッチ" to Triple(PointerType.Touch, beyond, false),
            "ペン" to Triple(PointerType.Stylus, beyond, false),
        )
        cases.forEach { (name, case) ->
            val (type, movement, expected) = case
            assertEquals(expected, startsMoveDrag(type, primaryPressed = true, canWrite = true, movement, THRESHOLD_PX), name)
        }
    }

    // 主ボタンを押していないか（右ボタンだけなど）、操作を受け付けていなければ、どれだけ動かしても始めない
    @Test
    fun dragDoesNotStartWithoutPrimaryButtonOrWhileNotWritable() {
        val far = Offset(100f, 100f)

        assertFalse(startsMoveDrag(PointerType.Mouse, primaryPressed = false, canWrite = true, far, THRESHOLD_PX))
        assertFalse(startsMoveDrag(PointerType.Mouse, primaryPressed = true, canWrite = false, far, THRESHOLD_PX))
    }

    // 枠の中の位置から、その下にある見えている行と、行の上端からの位置を求める。枠の外は、その位置に行が並んでいても、どの行にもならない
    @Test
    fun rowAtFindsVisibleRowUnderPosition() {
        // 先頭の行が半ば隠れるまでスクロールした枠。枠の高さは 2 行ぶんで、3 行目は枠の下にはみ出している
        val layout = TreeLayout(listOf("a", "b", "c"), firstOffset = -ROW_HEIGHT / 2, height = ROW_HEIGHT * 2)
        val height = ROW_HEIGHT.toFloat()
        val cases = mapOf(
            Offset(10f, 0f) to RowHit("a", 20f, height),
            Offset(10f, 19f) to RowHit("a", 39f, height),
            Offset(10f, 20f) to RowHit("b", 0f, height),
            Offset(10f, 59f) to RowHit("b", 39f, height),
            Offset(10f, 60f) to RowHit("c", 0f, height),
            Offset(10f, 79f) to RowHit("c", 19f, height),
            // 枠の下・上・右・左の外
            Offset(10f, 80f) to null,
            Offset(10f, -1f) to null,
            Offset(TREE_WIDTH.toFloat(), 10f) to null,
            Offset(-1f, 10f) to null,
        )

        assertEquals(cases, cases.keys.associateWith { layout.rowAt(it) })
    }

    // 行の位置は枠の先頭の余白の後ろから数える。余白の上はどの行にもならず、行は余白の分だけ下にある
    @Test
    fun rowAtAccountsForLeadingPadding() {
        val padding = 10
        val layout = TreeLayout(listOf("a", "b"), height = ROW_HEIGHT * 2 + padding, viewportStartOffset = -padding)

        assertNull(layout.rowAt(Offset(10f, 5f)))
        assertEquals(RowHit("a", 5f, ROW_HEIGHT.toFloat()), layout.rowAt(Offset(10f, 15f)))
        assertEquals(RowHit("b", 5f, ROW_HEIGHT.toFloat()), layout.rowAt(Offset(10f, ROW_HEIGHT + 15f)))
    }

    // 枠の上端・下端から帯の幅の中にポインターがあれば、その向きへ自動でスクロールする。帯の境目ちょうどは、上は帯の外、下は帯の中。
    // 帯の外と、枠の外（その位置が帯の延長にあっても）は、どちらへもスクロールしない
    @Test
    fun autoScrollDirectionFollowsEdgeBands() {
        val cases = mapOf(
            Offset(10f, 0f) to AutoScrollDirection.UP,
            Offset(10f, 47.9f) to AutoScrollDirection.UP,
            Offset(10f, 48f) to null,
            Offset(10f, 151.9f) to null,
            Offset(10f, 152f) to AutoScrollDirection.DOWN,
            Offset(10f, 199.9f) to AutoScrollDirection.DOWN,
            // 枠の下・上・右・左の外
            Offset(10f, 200f) to null,
            Offset(10f, -1f) to null,
            Offset(100f, 190f) to null,
            Offset(-1f, 10f) to null,
        )

        assertEquals(cases, cases.keys.associateWith { autoScrollDirectionOf(it, VIEWPORT, EDGE_PX) })
    }

    // ドラッグを始めると、掴んだポインターを持ち、ポインターの下の落とす先を求める。動かすと追う。落とす先の無いところでは null
    @Test
    fun dropFollowsPointer() {
        val state = stateWithRows()

        state.start(folder, POINTER, Offset(10f, 10f))
        assertEquals(folder, state.subject)
        assertEquals(POINTER, state.pointerId)
        assertEquals(Offset(10f, 10f), state.pointer)
        assertEquals(intoDrop("upper"), state.drop)

        state.moveTo(Offset(10f, 50f))
        assertEquals(intoDrop("lower"), state.drop)

        state.moveTo(Offset(200f, 50f))
        assertNull(state.drop)
    }

    // ポインターを動かさずにツリーがスクロールしても、そのときの行から、ポインターの下の落とす先を求め直す。落とすときも同じ
    @Test
    fun dropFollowsScrollWithoutPointerMove() {
        val targets = FakeDropTargets(TreeLayout(listOf("upper", "lower")))
        val state = stateWithRows(targets)
        state.start(folder, POINTER, Offset(10f, 50f))
        assertEquals(intoDrop("lower"), state.drop)

        targets.layout = TreeLayout(listOf("lower", "below"))

        assertEquals(intoDrop("below"), state.drop)
        assertEquals(folder to MoveDestination.Into("below"), state.finish())
    }

    // 落とせるところで終えると、移しているものと落とす先を返し、ドラッグしていない状態に戻る（掴んだポインターも持たない）
    @Test
    fun finishOverDropReturnsDestination() {
        val state = stateWithRows()
        state.start(folder, POINTER, Offset(10f, 10f))
        state.moveTo(Offset(10f, 50f))

        assertEquals(folder to MoveDestination.Into("lower"), state.finish())
        assertNull(state.subject)
        assertNull(state.pointerId)
        assertNull(state.pointer)
        assertNull(state.drop)
    }

    // 落とせるところでないところで終えるか、取りやめると、何も返さず、ドラッグしていない状態に戻る
    @Test
    fun finishOutsideOrCancelDropsNothing() {
        val outside = stateWithRows()
        outside.start(folder, POINTER, Offset(200f, 10f))
        assertNull(outside.finish())
        assertNull(outside.subject)
        assertNull(outside.pointerId)

        val cancelled = stateWithRows()
        cancelled.start(folder, POINTER, Offset(10f, 10f))
        cancelled.cancel()
        assertNull(cancelled.subject)
        assertNull(cancelled.pointerId)
        assertNull(cancelled.drop)
        assertNull(cancelled.finish())
    }

    // 落とす先の枠が複数あれば、ポインターの下で落とす先を返した枠のものを使う（ツリーとリストのように、重ならない 2 つの枠）
    @Test
    fun dropComesFromTargetsUnderPointer() {
        val tree = FakeDropTargets(TreeLayout(listOf("upper", "lower")))
        val list = MoveDropTargets { pointer, _ ->
            val inList = pointer.x >= TREE_WIDTH
            if (inList) MoveDrop(MoveDestination.At("open", beforeId = null), DropRow.ListItem("item"), line = null) else null
        }
        val state = stateWithRows(tree).apply { addDropTargets(list) }

        state.start(folder, POINTER, Offset(10f, 10f))
        assertEquals(intoDrop("upper"), state.drop)

        state.moveTo(Offset(TREE_WIDTH + 10f, 10f))
        assertEquals(MoveDestination.At("open", beforeId = null), state.drop?.destination)
    }

    // 落とす先の枠を外すと、その枠ではどの落とす先にもならない。別の枠を外しても、いまの枠は残る
    @Test
    fun removedDropTargetsHitNothing() {
        val targets = FakeDropTargets(TreeLayout(listOf("upper", "lower")))
        val state = stateWithRows(targets)
        state.start(folder, POINTER, Offset(10f, 10f))

        state.removeDropTargets(FakeDropTargets(TreeLayout(emptyList())))
        assertEquals(intoDrop("upper"), state.drop)

        state.removeDropTargets(targets)
        assertNull(state.drop)
        assertNull(state.finish())
    }

    // ドラッグしていないあいだは、ポインターが動いても、ポインターの位置も落とす先も持たない
    @Test
    fun moveWithoutDragKeepsNoDrop() {
        val state = stateWithRows()

        state.moveTo(Offset(10f, 10f))

        assertNull(state.pointer)
        assertNull(state.drop)
        assertNull(state.finish())
        assertNull(state.subject)
    }
}
