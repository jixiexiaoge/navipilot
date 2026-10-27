package com.jixiexiaoge.drivingassist.ui.components

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 速度圆环按钮（默认 36dp，与地图侧栏 FAB 协调；首页面板可传更小 diameter）
 */
@Composable
internal fun SpeedRingButton(
    value: Int,
    color: Color,
    onClick: () -> Unit,
    diameter: Dp = 36.dp,
    valueTextSize: TextUnit = 11.sp
) {
    val animatedValue by animateIntAsState(
        targetValue = value,
        animationSpec = tween(durationMillis = 300, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "speedRing"
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(diameter)
            .clickable(onClick = onClick)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val scale = (diameter.value / 36f).coerceAtLeast(0.75f)
            val edgePad = 3.dp.toPx() * scale
            val strokeW = 3.dp.toPx() * scale
            val radius = size.minDimension / 2f - edgePad
            drawCircle(
                color = Color(0xFF1E293B).copy(alpha = 0.65f),
                radius = radius + edgePad,
                center = center
            )
            drawCircle(
                color = color,
                radius = radius,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW)
            )
        }
        Text(
            text = animatedValue.toString(),
            fontSize = valueTextSize,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}
