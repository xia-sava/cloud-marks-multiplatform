package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import to.sava.cloudmarksmultiplatform.settings.DisplayScale

/** 画面全体の表示の大きさ。[ScaledDensity] が入れる。ポップアップとダイアログの中身を、同じ大きさで描くのに使う（[ScaledPopupContent]）。 */
private val LocalDisplayScale = staticCompositionLocalOf { DisplayScale.DEFAULT }

/**
 * [base] の 1 dp を、[factor] 倍の画素で描く密度。sp と dp の換算は [base] に任せ、文字は換算した dp に同じ倍率を掛けた画素で描く。
 * Android の非線形の文字の拡大（大きな文字ほど拡大の度合いを抑える換算）も、[base] のものを保つ。
 */
private data class DisplayScaledDensity(val base: Density, val factor: Float) : Density {
    override val density: Float get() = base.density * factor

    override val fontScale: Float get() = base.fontScale

    override fun Dp.toSp(): TextUnit = with(base) { this@toSp.toSp() }

    override fun TextUnit.toDp(): Dp = with(base) { this@toDp.toDp() }
}

/**
 * [density] を、表示の大きさ [scale] で拡大縮小した密度。[density] が拡大縮小したものなら、拡大縮小する前の密度から作り直す
 * （倍率を重ねて掛けない）。100% なら、拡大縮小する前の密度をそのまま返す。
 */
internal fun scaledDensityOf(density: Density, scale: DisplayScale): Density {
    val base = (density as? DisplayScaledDensity)?.base ?: density
    return if (scale == DisplayScale.DEFAULT) base else DisplayScaledDensity(base, scale.factor)
}

/** [content] を、いまの密度を表示の大きさ [scale] で拡大縮小した密度（[scaledDensityOf]）で描く。 */
@Composable
internal fun ScaledDensity(scale: DisplayScale, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val scaled = remember(density, scale) { scaledDensityOf(density, scale) }
    CompositionLocalProvider(LocalDensity provides scaled, LocalDisplayScale provides scale, content = content)
}

/**
 * ポップアップ（ダイアログ・メニュー・ツールチップ）の中身 [content] を、画面全体と同じ表示の大きさで描く。
 *
 * Android のポップアップとダイアログは、中身の密度を端末の密度に入れ直すので、表示の大きさで拡大縮小し直す。
 * このとき、ポップアップを作る部品の外枠（メニューの上下の余白、ダイアログの余白と幅の範囲など）は、端末の密度のまま描く。
 * 画面全体の密度を受け継ぐプラットフォーム（デスクトップ）では、拡大縮小する前の密度から作り直すので、倍率を重ねて掛けない。
 */
@Composable
internal fun ScaledPopupContent(content: @Composable () -> Unit) {
    ScaledDensity(LocalDisplayScale.current, content)
}

/** 中身（題・本文・ボタン）を、画面全体と同じ表示の大きさで描く（[ScaledPopupContent]）[AlertDialog]。 */
@Composable
internal fun ScaledAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { ScaledPopupContent(title) },
        text = { ScaledPopupContent(text) },
        confirmButton = { ScaledPopupContent(confirmButton) },
        dismissButton = { ScaledPopupContent(dismissButton) },
    )
}
