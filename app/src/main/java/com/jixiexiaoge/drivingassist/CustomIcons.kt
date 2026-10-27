package com.jixiexiaoge.drivingassist

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 自定义 Material 图标（替代 material-icons-extended 中少数用到的图标）
 */
object CustomIcons {
    val Dashboard: ImageVector by lazy {
        ImageVector.Builder(
            name = "Dashboard", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(3f, 3f)
                lineTo(11f, 3f)
                lineTo(11f, 13f)
                lineTo(3f, 13f)
                close()
                moveTo(13f, 3f)
                lineTo(21f, 3f)
                lineTo(21f, 9f)
                lineTo(13f, 9f)
                close()
                moveTo(3f, 15f)
                lineTo(11f, 15f)
                lineTo(11f, 21f)
                lineTo(3f, 21f)
                close()
                moveTo(13f, 11f)
                lineTo(21f, 11f)
                lineTo(21f, 21f)
                lineTo(13f, 21f)
                close()
            }
        }.build()
    }
    
    /** 导航箭头 */
    val Navigation: ImageVector by lazy {
        ImageVector.Builder(
            name = "Navigation", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.EvenOdd
            ) {
                moveTo(12f, 2f)
                lineTo(4f, 22f)
                lineTo(12f, 18f)
                lineTo(20f, 22f)
                close()
            }
        }.build()
    }

    /** 速度 */
    val Speed: ImageVector by lazy {
        ImageVector.Builder(
            name = "Speed", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero
            ) {
                moveTo(20.38f, 8.57f)
                curveTo(19.89f, 8.05f, 19.34f, 7.58f, 18.73f, 7.17f)
                lineTo(15.33f, 11.34f)
                curveTo(14.9f, 11.12f, 14.42f, 11f, 13.91f, 11f)
                curveTo(12.35f, 11f, 11.09f, 12.26f, 11.09f, 13.82f)
                reflectiveCurveTo(12.35f, 16.64f, 13.91f, 16.64f)
                reflectiveCurveTo(16.73f, 15.38f, 16.73f, 13.82f)
                curveTo(16.73f, 13.31f, 16.61f, 12.83f, 16.39f, 12.4f)
                lineTo(20.38f, 8.57f)
                close()
                moveTo(12f, 4f)
                curveTo(7.58f, 4f, 4f, 7.58f, 4f, 12f)
                curveTo(4f, 16.42f, 7.58f, 20f, 12f, 20f)
                curveTo(16.42f, 20f, 20f, 16.42f, 20f, 12f)
                curveTo(20f, 10.83f, 19.74f, 9.72f, 19.26f, 8.72f)
                lineTo(20.67f, 7.31f)
                curveTo(21.52f, 8.73f, 22f, 10.37f, 22f, 12f)
                curveTo(22f, 17.52f, 17.52f, 22f, 12f, 22f)
                curveTo(6.48f, 22f, 2f, 17.52f, 2f, 12f)
                curveTo(2f, 6.48f, 6.48f, 2f, 12f, 2f)
                curveTo(13.63f, 2f, 15.27f, 2.48f, 16.69f, 3.33f)
                lineTo(15.28f, 4.74f)
                curveTo(14.28f, 4.26f, 13.17f, 4f, 12f, 4f)
                close()
            }
        }.build()
    }

    /** Bug 报告 */
    val BugReport: ImageVector by lazy {
        ImageVector.Builder(
            name = "BugReport", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero
            ) {
                moveTo(20f, 8f)
                horizontalLineTo(17.19f)
                curveTo(16.74f, 7.22f, 16.12f, 6.55f, 15.37f, 6.04f)
                lineTo(17f, 4.41f)
                lineTo(15.59f, 3f)
                lineTo(13.42f, 5.17f)
                curveTo(12.96f, 5.06f, 12.49f, 5f, 12f, 5f)
                curveTo(11.51f, 5f, 11.04f, 5.06f, 10.59f, 5.17f)
                lineTo(8.41f, 3f)
                lineTo(7f, 4.41f)
                lineTo(8.62f, 6.04f)
                curveTo(7.88f, 6.55f, 7.26f, 7.22f, 6.81f, 8f)
                horizontalLineTo(4f)
                verticalLineTo(10f)
                horizontalLineTo(6.09f)
                curveTo(6.04f, 10.33f, 6f, 10.66f, 6f, 11f)
                verticalLineTo(12f)
                horizontalLineTo(4f)
                verticalLineTo(14f)
                horizontalLineTo(6f)
                verticalLineTo(15f)
                curveTo(6f, 15.34f, 6.04f, 15.67f, 6.09f, 16f)
                horizontalLineTo(4f)
                verticalLineTo(18f)
                horizontalLineTo(6.81f)
                curveTo(7.43f, 19.27f, 8.56f, 20.25f, 9.92f, 20.66f)
                curveTo(10.59f, 20.88f, 11.28f, 21f, 12f, 21f)
                curveTo(12.72f, 21f, 13.41f, 20.88f, 14.08f, 20.66f)
                curveTo(15.44f, 20.25f, 16.57f, 19.27f, 17.19f, 18f)
                horizontalLineTo(20f)
                verticalLineTo(16f)
                horizontalLineTo(17.91f)
                curveTo(17.96f, 15.67f, 18f, 15.34f, 18f, 15f)
                verticalLineTo(14f)
                horizontalLineTo(20f)
                verticalLineTo(12f)
                horizontalLineTo(18f)
                verticalLineTo(11f)
                curveTo(18f, 10.66f, 17.96f, 10.33f, 17.91f, 10f)
                horizontalLineTo(20f)
                verticalLineTo(8f)
                close()
                moveTo(14f, 16f)
                horizontalLineTo(10f)
                verticalLineTo(14f)
                horizontalLineTo(14f)
                verticalLineTo(16f)
                close()
                moveTo(14f, 12f)
                horizontalLineTo(10f)
                verticalLineTo(10f)
                horizontalLineTo(14f)
                verticalLineTo(12f)
                close()
            }
        }.build()
    }

    /** 工作/公文包 */
    val Work: ImageVector by lazy {
        ImageVector.Builder(
            name = "Work", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero
            ) {
                moveTo(20f, 6f)
                horizontalLineTo(16f)
                verticalLineTo(4f)
                curveTo(16f, 2.89f, 15.11f, 2f, 14f, 2f)
                horizontalLineTo(10f)
                curveTo(8.89f, 2f, 8f, 2.89f, 8f, 4f)
                verticalLineTo(6f)
                horizontalLineTo(4f)
                curveTo(2.89f, 6f, 2f, 6.89f, 2f, 8f)
                verticalLineTo(19f)
                curveTo(2f, 20.11f, 2.89f, 21f, 4f, 21f)
                horizontalLineTo(20f)
                curveTo(21.11f, 21f, 22f, 20.11f, 22f, 19f)
                verticalLineTo(8f)
                curveTo(22f, 6.89f, 21.11f, 6f, 20f, 6f)
                close()
                moveTo(10f, 4f)
                horizontalLineTo(14f)
                verticalLineTo(6f)
                horizontalLineTo(10f)
                verticalLineTo(4f)
                close()
                moveTo(20f, 19f)
                horizontalLineTo(4f)
                verticalLineTo(8f)
                horizontalLineTo(20f)
                verticalLineTo(19f)
                close()
                moveTo(12f, 14f)
                curveTo(12.55f, 14f, 13f, 13.55f, 13f, 13f)
                reflectiveCurveTo(12.55f, 12f, 12f, 12f)
                reflectiveCurveTo(11f, 12.45f, 11f, 13f)
                reflectiveCurveTo(11.45f, 14f, 12f, 14f)
                close()
            }
        }.build()
    }

    /** 返回箭头 */
    val ArrowBack: ImageVector by lazy {
        ImageVector.Builder(
            name = "ArrowBack", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero
            ) {
                moveTo(20f, 11f)
                horizontalLineTo(7.83f)
                lineTo(13.42f, 5.41f)
                lineTo(12f, 4f)
                lineTo(4f, 12f)
                lineTo(12f, 20f)
                lineTo(13.41f, 18.59f)
                lineTo(7.83f, 13f)
                horizontalLineTo(20f)
                verticalLineTo(11f)
                close()
            }
        }.build()
    }

    /** 前进箭头 */
    val ArrowForward: ImageVector by lazy {
        ImageVector.Builder(
            name = "ArrowForward", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero
            ) {
                moveTo(12f, 4f)
                lineTo(10.59f, 5.41f)
                lineTo(16.17f, 11f)
                horizontalLineTo(4f)
                verticalLineTo(13f)
                horizontalLineTo(16.17f)
                lineTo(10.59f, 18.59f)
                lineTo(12f, 20f)
                lineTo(20f, 12f)
                close()
            }
        }.build()
    }
}
