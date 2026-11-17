package to.sava.cloudmarksmultiplatform.ui

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * フォルダへ移すことを示すアイコン。Material Icons の `Filled.DriveFileMove` と同じ形（24 × 24 の座標の経路。
 * フォルダの形から、右向きの矢印を抜く）。
 *
 * `material-icons-core` に無いため、ここで描く。
 */
internal val MoveToFolderIcon: ImageVector by lazy {
    materialIcon(name = "Filled.DriveFileMove") {
        materialPath {
            moveTo(20.0f, 6.0f)
            horizontalLineToRelative(-8.0f)
            lineToRelative(-2.0f, -2.0f)
            horizontalLineTo(4.0f)
            curveTo(2.9f, 4.0f, 2.01f, 4.9f, 2.01f, 6.0f)
            lineTo(2.0f, 18.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            horizontalLineToRelative(16.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            verticalLineTo(8.0f)
            curveTo(22.0f, 6.9f, 21.1f, 6.0f, 20.0f, 6.0f)
            close()
            moveTo(12.0f, 18.0f)
            verticalLineToRelative(-3.0f)
            horizontalLineTo(8.0f)
            verticalLineToRelative(-4.0f)
            horizontalLineToRelative(4.0f)
            verticalLineTo(8.0f)
            lineToRelative(5.0f, 5.0f)
            lineTo(12.0f, 18.0f)
            close()
        }
    }
}
