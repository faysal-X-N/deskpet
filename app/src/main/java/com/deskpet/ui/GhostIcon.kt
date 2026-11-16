package com.deskpet.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 幽灵模式专属图标（自绘，不用 material-icons-extended）：
 * 圆顶 + 波浪下摆的经典幽灵剪影，两只眼睛用偶奇填充挖空。
 * 填充色是占位黑——Icon 组件默认用 LocalContentColor tint 整个矢量，
 * 最终颜色跟其它图标在坞里的着色行为完全一致。
 *
 * 为什么不引 material-icons-extended：全量图标库 ~10MB（R8 可裁剪但
 * 仍拖慢构建），只为两个图标不值——而且市面上竞品全用"眼睛"图标表达
 * 穿透，幽灵剪影与本功能命名一致、辨识度更高。
 */
val GhostIcon: ImageVector = ImageVector.Builder(
    name = "Ghost",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply {
    path(
        fill = SolidColor(Color.Black),
        pathFillType = PathFillType.EvenOdd
    ) {
        // 身体：圆顶从 (12,2.5) 起弧，左右下探到 10.2，底部三段波浪
        moveTo(12f, 2.5f)
        curveTo(7.9f, 2.5f, 4.5f, 5.9f, 4.5f, 10.2f)
        lineTo(4.5f, 19.6f)
        lineTo(7.2f, 17.3f)
        lineTo(9.5f, 19.6f)
        lineTo(12f, 17.3f)
        lineTo(14.5f, 19.6f)
        lineTo(16.8f, 17.3f)
        lineTo(19.5f, 19.6f)
        lineTo(19.5f, 10.2f)
        curveTo(19.5f, 5.9f, 16.1f, 2.5f, 12f, 2.5f)
        close()
        // 左眼（EvenOdd 挖空）
        moveTo(9.3f, 9.2f)
        curveTo(10.1f, 9.2f, 10.7f, 9.9f, 10.7f, 10.7f)
        curveTo(10.7f, 11.5f, 10.1f, 12.2f, 9.3f, 12.2f)
        curveTo(8.5f, 12.2f, 7.9f, 11.5f, 7.9f, 10.7f)
        curveTo(7.9f, 9.9f, 8.5f, 9.2f, 9.3f, 9.2f)
        close()
        // 右眼
        moveTo(14.7f, 9.2f)
        curveTo(15.5f, 9.2f, 16.1f, 9.9f, 16.1f, 10.7f)
        curveTo(16.1f, 11.5f, 15.5f, 12.2f, 14.7f, 12.2f)
        curveTo(13.9f, 12.2f, 13.3f, 11.5f, 13.3f, 10.7f)
        curveTo(13.3f, 9.9f, 13.9f, 9.2f, 14.7f, 9.2f)
        close()
    }
}.build()
