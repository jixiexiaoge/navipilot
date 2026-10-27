package com.jixiexiaoge.drivingassist.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jixiexiaoge.drivingassist.AmapIconSource
import com.jixiexiaoge.drivingassist.AmapManeuver

internal enum class ManeuverGraphic { Straight, Left, Right, KeepLeft, KeepRight, SharpLeft, SharpRight, ULeft, URight, Roundabout, Landmark, Unknown }

/** Navigation ICONs, never the independent lane Back_icon encoding. */
internal fun maneuverGraphic(icon: Int?): ManeuverGraphic {
    if (icon == null || AmapManeuver.info(icon, AmapIconSource.AUTO_BROADCAST) == null) return ManeuverGraphic.Unknown
    return when (icon) {
        0, 1, 9, 20 -> ManeuverGraphic.Straight
        2 -> ManeuverGraphic.Left
        3 -> ManeuverGraphic.Right
        4, 65 -> ManeuverGraphic.KeepLeft
        5, 66, 101 -> ManeuverGraphic.KeepRight
        6 -> ManeuverGraphic.SharpLeft
        7 -> ManeuverGraphic.SharpRight
        8 -> ManeuverGraphic.ULeft
        19 -> ManeuverGraphic.URight
        11, 12, 17, 18, in 21..28 -> ManeuverGraphic.Roundabout
        else -> ManeuverGraphic.Landmark
    }
}

/** 左/右转的画法：折角 L 型（导航卡，与高德一致）/ 直行箭头旋转 90°（红绿灯胶囊，小尺寸更清楚）。 */
internal enum class TurnArrowStyle { Bent, Rotated }

/**
 * @param strokeScale 笔画粗细倍数（默认 1 = 导航区原样）。红绿灯胶囊尺寸很小（23~24dp），
 *   按原比例画出的线只有 1.7dp，缩小看会显虚，所以胶囊传 1.3 加粗一点。
 * @param turnStyle 左/右转的几何风格，见 [TurnArrowStyle]。
 */
@Composable
internal fun AmapManeuverIcon(
    icon: Int?,
    color: Color,
    modifier: Modifier = Modifier.size(48.dp),
    strokeScale: Float = 1f,
    turnStyle: TurnArrowStyle = TurnArrowStyle.Bent,
) {
    val shape = maneuverGraphic(icon)
    val description = icon?.let { AmapManeuver.info(it, AmapIconSource.AUTO_BROADCAST)?.labelZh }
        ?: "导航动作未知"
    // 尺寸交给调用方（默认 48dp）：红绿灯胶囊要 23dp 这类小尺寸，
    // 写死在 Canvas 里会被外层固定约束压回去，语义容易看错。
    Canvas(modifier.semantics { contentDescription = description }) {
        val neutral = Color(0xFF94A3B8)
        val stroke = size.minDimension * .075f * strokeScale
        fun point(x: Float, y: Float) = Offset(size.width * x, size.height * y)
        fun line(a: Offset, b: Offset, tint: Color = color) = drawLine(tint, a, b, stroke, StrokeCap.Round)
        fun arrow(tip: Offset, previous: Offset) {
            line(previous, tip)
            val dx = tip.x - previous.x
            val dy = tip.y - previous.y
            val length = kotlin.math.sqrt(dx * dx + dy * dy)
            val ux = dx / length
            val uy = dy / length
            val wing = size.minDimension * .16f
            line(tip, Offset(tip.x - ux * wing - uy * wing, tip.y - uy * wing + ux * wing))
            line(tip, Offset(tip.x - ux * wing + uy * wing, tip.y - uy * wing - ux * wing))
        }
        when (shape) {
            ManeuverGraphic.Unknown -> {
                drawCircle(neutral, size.minDimension * .3f, point(.5f, .5f), style = Stroke(stroke))
                line(point(.5f, .32f), point(.5f, .53f), neutral)
                drawCircle(neutral, stroke * .55f, point(.5f, .66f))
            }
            ManeuverGraphic.Landmark -> {
                line(point(.35f, .85f), point(.35f, .18f))
                val flag = Path().apply {
                    moveTo(size.width * .35f, size.height * .18f)
                    lineTo(size.width * .8f, size.height * .3f)
                    lineTo(size.width * .35f, size.height * .45f)
                    close()
                }
                drawPath(flag, color, style = Stroke(stroke))
            }
            ManeuverGraphic.Roundabout -> {
                drawCircle(color, size.minDimension * .23f, point(.5f, .45f), style = Stroke(stroke))
                line(point(.5f, .9f), point(.5f, .7f))
                // A ring marks the verified category; exit geometry/ordinal is not provided here.
            }
            ManeuverGraphic.ULeft, ManeuverGraphic.URight -> {
                val right = shape == ManeuverGraphic.URight
                val x = if (right) .3f else .7f
                val end = if (right) .7f else .3f
                val path = Path().apply {
                    moveTo(size.width * x, size.height * .88f)
                    lineTo(size.width * x, size.height * .4f)
                    cubicTo(size.width * x, 0f, size.width * end, 0f, size.width * end, size.height * .4f)
                }
                drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round))
                arrow(point(end, .65f), point(end, .4f))
            }
            else -> {
                val rotated = turnStyle == TurnArrowStyle.Rotated &&
                    (shape == ManeuverGraphic.Left || shape == ManeuverGraphic.Right)
                if (rotated) {
                    // 左/右转 = **直行箭头整体旋转 90°**：杆长、箭头长度、两翼尺寸与直行完全一致，
                    // 只把朝向从「向上」换成「向左 / 向右」，不做折角。
                    // 原来的 L 型在胶囊 23dp 这种小尺寸下会糊成一团，看起来既不像左转也不像右转。
                    val left = shape == ManeuverGraphic.Left
                    line(point(if (left) .9f else .1f, .5f), point(.5f, .5f))
                    arrow(point(if (left) .12f else .88f, .5f), point(.5f, .5f))
                } else {
                    val bend = point(.5f, .5f)
                    line(point(.5f, .9f), bend)
                    val tip = when (shape) {
                        ManeuverGraphic.Left -> point(.12f, .5f)
                        ManeuverGraphic.Right -> point(.88f, .5f)
                        ManeuverGraphic.KeepLeft -> point(.2f, .18f)
                        ManeuverGraphic.KeepRight -> point(.8f, .18f)
                        ManeuverGraphic.SharpLeft -> point(.15f, .75f)
                        ManeuverGraphic.SharpRight -> point(.85f, .75f)
                        else -> point(.5f, .12f)
                    }
                    arrow(tip, bend)
                }
            }
        }
    }
}
