@file:Suppress("FunctionNaming", "MagicNumber")

package com.jixiexiaoge.drivingassist.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jixiexiaoge.drivingassist.data.LaneGeometry
import com.jixiexiaoge.drivingassist.ui.theme.Accent
import com.jixiexiaoge.drivingassist.ui.theme.TextSecondary
import java.util.Locale

/**
 * 车道宽度条：`左 | 本 | 右`，单位米，一位小数。
 *
 * 数据来自 [LaneGeometry]（7000 `modelV2` 的 4 条 laneLines 在前视 5 米处插值差分），
 * 不是高德广播。本车道用 [accent] 强调。
 *
 * **没有值就不画**（7.9.46）：以前某一侧外侧线不可信时会画成 `— | — | — m`，
 * 看上去像「宽度是 0」或「读数坏了」。现在只显示真实拿到了的那几段——
 * 只有本车道就只显示 `3.5 m`，左右齐全才出现竖线分隔；三格都没有时整条不渲染。
 *
 * 显示位置：**实时页驾驶卡的自车正下方**（原「左 / 右盲区提示」文字的位置），
 * 白底卡片上必须传 [accent] / [muted] / 字号，默认值是按深色页面调的。
 *
 * 7.9.48：这个文件原来叫 `JunctionLaneStrip.kt`，那次把二次开发的车道引导条删掉后
 * （车道方向改由 `AmapLaneCard` 显示高德原生素材），只剩车道宽度这一块。
 */
@Composable
internal fun LaneWidthStrip(
    geometry: LaneGeometry,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 10.sp,
    unitFontSize: TextUnit = 9.sp,
    accent: Color = Accent,
    muted: Color = TextSecondary,
) {
    fun format(width: Float?): String? =
        width?.let { String.format(Locale.ROOT, "%.1f", it) }

    val left = format(geometry.leftLaneWidthM)
    val ego = format(geometry.widthM)
    val right = format(geometry.rightLaneWidthM)
    if (left == null && ego == null && right == null) return

    Row(
        modifier.testTag("lane-width-strip"),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 相邻车道只在本车道有读数时才有意义（否则 `4.2 | 3.5` 这种缺项会读错位）
        if (left != null && ego != null) {
            Text(left, color = muted, fontSize = fontSize)
            Text("|", color = muted.copy(alpha = 0.5f), fontSize = fontSize)
        }
        ego?.let {
            Text(it, color = accent, fontSize = fontSize, fontWeight = FontWeight.SemiBold)
        }
        if (right != null && ego != null) {
            Text("|", color = muted.copy(alpha = 0.5f), fontSize = fontSize)
            Text(right, color = muted, fontSize = fontSize)
        }
        Text("m", color = muted.copy(alpha = 0.6f), fontSize = unitFontSize,
            modifier = Modifier.padding(start = 1.dp))
    }
}
