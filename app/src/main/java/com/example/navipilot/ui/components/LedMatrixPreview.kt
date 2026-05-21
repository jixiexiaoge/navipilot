package com.example.navipilot.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.navipilot.ui.utils.localized

/**
 * LED 点阵实时预览组件
 *
 * 固定 16x64 点阵显示，4:1 比例，居中布局
 */
@Composable
fun LedMatrixPreview(
    text: String,
    color: Color,
    animCode: Int,
    bitmapData: List<ByteArray> = emptyList(),
    modifier: Modifier = Modifier,
    /** 非 null 时点阵预览区域可点击（例如打开 7706 JSON 调试） */
    onClick: (() -> Unit)? = null,
) {
    // 动画状态
    val infiniteTransition = rememberInfiniteTransition(label = "led_preview")

    // 滚动偏移
    val scrollOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (animCode == 1) 16f else if (animCode == 2) -16f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scroll"
    )

    // 呼吸 alpha
    val breatheAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe"
    )

    // 闪烁 alpha
    val flickerAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "flicker"
    )

    val renderedBitmaps = remember(text, bitmapData) {
        if (bitmapData.isNotEmpty()) bitmapData else LedMatrixBitmapRenderer.renderTextToColumnMajor(text)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        val panelWidth = minOf(maxWidth - 16.dp, 280.dp)
        val panelHeight = panelWidth / 4f

        Box(
            modifier = Modifier
                .size(panelWidth, panelHeight)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black)
                .border(2.dp, Color(0xFF111111), RoundedCornerShape(6.dp))
                .then(
                    if (onClick != null) {
                        Modifier
                            .semantics {
                                role = Role.Button
                                contentDescription = localized("打开7706 JSON调试", "Open 7706 JSON debug")
                            }
                            .clickable(onClick = onClick)
                    } else Modifier
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            LedDotMatrixCanvas(
                bitmapData = renderedBitmaps,
                color = color,
                animCode = animCode,
                scrollOffset = scrollOffset,
                breatheAlpha = breatheAlpha,
                flickerAlpha = flickerAlpha,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f)
            )
        }
    }
}

/**
 * 点阵画布组件 - 16行 x 64列
 */
@Composable
private fun LedDotMatrixCanvas(
    bitmapData: List<ByteArray>,
    color: Color,
    animCode: Int,
    scrollOffset: Float,
    breatheAlpha: Float,
    flickerAlpha: Float,
    modifier: Modifier = Modifier
) {
    val alpha = when (animCode) {
        6 -> breatheAlpha
        8 -> flickerAlpha
        else -> 1f
    }

    val displayColor = color.copy(alpha = alpha)
    val inactiveDotColor = Color(0xFF1F2937)

    Canvas(modifier = modifier) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        val cols = 64
        val rows = 16

        // 计算每个点的大小，保持 16:64 = 1:4 的比例
        val dotWidth = canvasWidth / cols
        val dotHeight = canvasHeight / rows
        // 取较小值，并留出点与点之间的间隙
        val dotSize = minOf(dotWidth, dotHeight) * 0.75f

        // 点与点之间的间距，严格按 16x64 网格排布
        val spacingX = (canvasWidth - cols * dotSize) / (cols + 1)
        val spacingY = (canvasHeight - rows * dotSize) / (rows + 1)
        val dotRadius = dotSize / 2f

        // 先绘制所有背景点（圆形）
        for (col in 0 until cols) {
            val cx = col * (dotSize + spacingX) + spacingX + dotRadius
            for (row in 0 until rows) {
                drawCircle(
                    color = inactiveDotColor,
                    radius = dotRadius,
                    center = Offset(cx, row * (dotSize + spacingY) + spacingY + dotRadius)
                )
            }
        }

        // 计算字符宽度
        val charPixelWidth = 16 * (dotSize + spacingX)

        // 初始偏移（用于滚动）
        val baseX = when (animCode) {
            1 -> -scrollOffset * (dotSize + spacingX)
            2 -> scrollOffset * (dotSize + spacingX)
            else -> 0f
        }

        var xOffset = baseX

        for (bitmap in bitmapData) {
            for (sourceCol in 0 until 16) {
                if (sourceCol >= bitmap.size / 2) continue

                val upper = bitmap[sourceCol * 2].toInt() and 0xFF
                val lower = bitmap[sourceCol * 2 + 1].toInt() and 0xFF

                for (sourceRow in 0 until rows) {
                    val bit = if (sourceRow < 8) {
                        (upper shr sourceRow) and 1
                    } else {
                        (lower shr (sourceRow - 8)) and 1
                    }

                    if (bit == 1) {
                        // 设备位图为列优先方向，预览里按转置后的坐标绘制，
                        // 等价于先旋转90度再左右调换，使文字按正常阅读方向显示。
                        val displayCol = sourceRow
                        val displayRow = sourceCol
                        val absX = xOffset + displayCol * (dotSize + spacingX)

                        if (absX < -dotSize || absX > canvasWidth) continue

                        drawCircle(
                            color = displayColor,
                            radius = dotRadius,
                            center = Offset(
                                absX + spacingX + dotRadius,
                                displayRow * (dotSize + spacingY) + spacingY + dotRadius
                            )
                        )
                    }
                }
            }
            xOffset += charPixelWidth
        }
    }
}
