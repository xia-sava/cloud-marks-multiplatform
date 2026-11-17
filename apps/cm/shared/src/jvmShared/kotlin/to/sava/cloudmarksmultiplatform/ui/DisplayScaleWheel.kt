package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import to.sava.cloudmarksmultiplatform.app.CloudMarksController
import to.sava.cloudmarksmultiplatform.settings.DisplayScale

/**
 * Ctrl を押しながらホイールを縦に回したときに、表示の大きさ [scale] から替える先の段。[scrollY] はホイールを縦に回した量で、
 * 上へ回すと負、下へ回すと正。上へ回せば 1 段大きく、下へ回せば 1 段小さくする。回した量の大きさによらず 1 段。
 * いちばん大きい段・小さい段からは、その段のまま。
 * Ctrl を押していないか、縦に回していなければ null（表示の大きさの操作ではない）。
 */
internal fun displayScaleByWheel(scale: DisplayScale, ctrlPressed: Boolean, scrollY: Float): DisplayScale? =
    when {
        !ctrlPressed -> null
        scrollY < 0f -> scale.larger()
        scrollY > 0f -> scale.smaller()
        else -> null
    }

/**
 * この要素の中で Ctrl を押しながらホイールを縦に回したら、[controller] の表示の大きさを 1 段替える（[displayScaleByWheel]）。
 * この修飾は、画面の入口（窓の中身の一番外側）に付ける。効くのはメインの画面と設定の画面の上で、ダイアログとメニューの上では効かない。
 *
 * 表示の大きさの操作は Ctrl+縦のホイールだけで、横のホイールには触らない。
 * ホイールのイベントは子より先に受け（[PointerEventPass.Initial]）、表示の大きさの操作なら消費して、リストやツリーのスクロールに渡さない。
 * タッチパッドのように細かい量のイベントが続けて来ても、イベントごとに 1 段。Ctrl を押していないホイールにも触らない。
 */
internal fun Modifier.displayScaleWheel(controller: CloudMarksController): Modifier =
    pointerInput(controller) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Scroll) continue
                val scrollY = event.changes.fold(0f) { total, change -> total + change.scrollDelta.y }
                val ctrlPressed = event.keyboardModifiers.isCtrlPressed
                val scaled = displayScaleByWheel(controller.state.value.displayScale, ctrlPressed, scrollY) ?: continue
                event.changes.forEach { it.consume() }
                controller.setDisplayScale(scaled)
            }
        }
    }
