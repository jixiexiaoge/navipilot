@file:Suppress("MagicNumber", "FunctionNaming")

package com.jixiexiaoge.drivingassist.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface as CardSurface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jixiexiaoge.drivingassist.ui.theme.AccentCyan
import com.jixiexiaoge.drivingassist.ui.theme.AccentOrange
import com.jixiexiaoge.drivingassist.vision.LaneInference
import com.jixiexiaoge.drivingassist.vision.LaneResult
import com.jixiexiaoge.drivingassist.vision.LaneStatus
import com.jixiexiaoge.drivingassist.data.LaneFusionEstimate
import com.jixiexiaoge.drivingassist.data.LaneFusionState
import com.jixiexiaoge.drivingassist.data.LaneEdgeEstimate
import java.util.Locale

/**
 * 车道状态 chip。
 *
 * 与 AR 页面读同一份结果流（[LaneInference.live]），所以：
 * - **进入实时页、画面连上就开始出数**，无需任何开关
 * - 结果一直跟着画面走，直到离开实时页（`close()` 会把它置空，chip 自动消失）
 *
 * 显示位置：**叠在前视画面里，水平居中、靠下**（7.9.40 从页面标题栏移下来）。
 * 叠在视频上时传 [onVideo] 用半透明黑底保证可读，[compact] 用于画中画那点尺寸。
 *
 * 用 `derivedStateOf` 把 15 fps 的结果流压成"文字真的变了才重组"，
 * 视频叠层不会被推理频率拖着重组。
 */
@Composable
internal fun ArLaneChip(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onVideo: Boolean = false,
    fusion: LaneFusionEstimate = LaneFusionEstimate(),
) {
    val latest = LaneInference.live.collectAsState()
    val status = LaneInference.liveStatus.collectAsState()
    val chip by remember(latest, status, fusion) {
        derivedStateOf { chipState(latest.value, status.value, fusion) }
    }
    val state = chip ?: return
    LaneFusionChip(state.text, state.error, modifier, compact, onVideo, state.details)
}

@Composable
internal fun LaneFusionChip(
    text: String,
    error: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onVideo: Boolean = false,
    details: String? = null,
) {
    var showDetails by remember(details != null) { mutableStateOf(false) }
    if (showDetails && details != null) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text("车道几何与AR参考") },
            text = {
                Text(details + "\n\n左起宽度带 ≈ 左内线至左路缘余宽 ÷ 本道宽 + 1；右侧同理。" +
                    "\n同一5m/15m截面须一致，至少3个递增模型帧跨600ms确认。" +
                    "\n7000也是视觉模型，并非实测定位；路肩、应急道、导流区和分合流可能造成误差。" +
                    "\n仅高德确认的高速/快速路启用，匝道不凭常见车道数猜测。" +
                    "\n单侧编号只作显示参考，不发送为7714当前车道，不提供变道授权。",
                    Modifier.testTag("lane-fusion-details").verticalScroll(rememberScrollState()))
            },
            confirmButton = { TextButton(onClick = { showDetails = false }) { Text("关闭") } },
        )
    }
    CardSurface(
        modifier = modifier.testTag("ar-lane-chip")
            .then(if (details != null) Modifier.clickable { showDetails = true } else Modifier),
        shape = RoundedCornerShape(12.dp),
        color = when {
            // 叠在视频上：只有半透明黑底才能压住任意画面
            onVideo -> Color.Black.copy(alpha = 0.55f)
            error -> AccentOrange.copy(alpha = 0.16f)
            else -> AccentCyan.copy(alpha = 0.14f)
        },
    ) {
        Text(
            text,
            Modifier.padding(
                horizontal = if (compact) 6.dp else 10.dp,
                vertical = if (compact) 2.dp else 5.dp,
            ),
            color = if (error) AccentOrange else AccentCyan,
            fontSize = if (compact) 9.sp else 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private data class ChipState(val text: String, val error: Boolean = false, val details: String? = null)

private fun fusionChipState(fusion: LaneFusionEstimate) = ChipState(
    if (fusion.edge != null) laneFusionCompactText(fusion) else laneFusionText(fusion),
    error = fusion.edge?.arConflict == true || fusion.state in setOf(
        LaneFusionState.Conflict, LaneFusionState.Limited, LaneFusionState.Unsynchronized),
    details = fusion.edge?.let { laneFusionText(fusion) },
)

/**
 * Geometry bands are display-only; wire lane indices still require corroborated fusion.
 */
private fun chipState(result: LaneResult?, status: LaneStatus, fusion: LaneFusionEstimate): ChipState? {
    val session = LaneInference.activeSession
    val activeGeometry = fusion.edge != null && session?.let {
        it.id == fusion.session && it.host == fusion.host && it.route == fusion.route
    } == true
    return when {
        fusion.state == LaneFusionState.RoadTypeBlocked -> ChipState(laneFusionMessage(fusion.state))
        activeGeometry -> fusionChipState(fusion)
        !status.running && status.message.isNotEmpty() -> ChipState(status.message, error = true)
        !status.running && result == null -> null
        result == null -> ChipState("车道识别中…")
        else -> visionChipState(result, fusion)
    }
}

private fun visionChipState(result: LaneResult, fusion: LaneFusionEstimate): ChipState {
    val sameSession = result.sessionId == fusion.session && result.targetHost == fusion.host &&
        result.routeGeneration == fusion.route && result.model == fusion.model
    return if (sameSession) fusionChipState(fusion) else ChipState(laneFusionMessage(fusion.state),
        error = fusion.state in setOf(
            LaneFusionState.Conflict, LaneFusionState.Limited, LaneFusionState.Unsynchronized))
}

internal fun laneFusionCompactText(fusion: LaneFusionEstimate): String {
    val edge = fusion.edge ?: return laneFusionText(fusion)
    val position = when {
        edge.leftIndex != null -> "左起第 ${edge.leftIndex} 带"
        edge.rightIndex != null -> "右起第 ${edge.rightIndex} 带"
        else -> "宽度带确认中"
    }
    val ar = when {
        edge.arConflict -> "AR冲突"
        edge.leftIndex != null && edge.leftIndex == edge.arLeft -> "AR左一致"
        edge.rightIndex != null && edge.rightIndex == edge.arRight -> "AR右一致"
        else -> "AR未核准"
    }
    return "几何估计：$position · $ar\n非定位确认 · 点击详情"
}

internal fun laneFusionText(fusion: LaneFusionEstimate): String {
    val edge = fusion.edge
    val message = if (fusion.state == LaneFusionState.Estimated && fusion.current != null && fusion.count != null) {
        "融合估计：左起第 ${fusion.current} / ${fusion.count} 道 · 非定位确认"
    } else if (edge?.arConflict == true) {
        "路缘与AR冲突 · 仅保留几何参考，车道号未知"
    } else if ((edge?.leftIndex != null || edge?.rightIndex != null) &&
        fusion.state !in setOf(LaneFusionState.Conflict, LaneFusionState.Unsynchronized)) {
        "路缘几何估计 · AR辅助核对 · 非定位确认"
    } else laneFusionMessage(fusion.state)
    return if (edge == null) message else "$message\n${laneEdgeText(edge)}"
}

private fun laneEdgeText(edge: LaneEdgeEstimate): String {
    fun meters(value: Float?): String = value?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "未知"
    fun ar(index: Int?, candidate: Int?): String = when {
        index == null -> "待几何确认"
        candidate == null -> "未核准/不同步"
        index == candidate -> "一致"
        else -> "冲突"
    }
    val position = when {
        edge.leftIndex != null -> "左缘优先：左起第 ${edge.leftIndex} 带" +
            (edge.rightIndex?.let { " · 右起第 $it 带" } ?: " · 总数未知")
        edge.rightIndex != null -> "右缘参考：右起第 ${edge.rightIndex} 带 · 左起编号/总数未知"
        else -> "路缘宽度带确认中 · 编号未知"
    }
    return "$position\n" +
        "7000 本道宽 ${meters(edge.geometry.widthM)}m · 左/右余宽 " +
        "${meters(edge.geometry.leftSpaceM)}/${meters(edge.geometry.rightSpaceM)}m\n" +
        "AR 左${ar(edge.leftIndex, edge.arLeft)} / 右${ar(edge.rightIndex, edge.arRight)}" +
        " · 路肩/导流区未识别，非变道依据"
}

internal fun laneFusionMessage(state: LaneFusionState): String = when (state) {
    LaneFusionState.Estimated, LaneFusionState.Stabilizing -> "车道融合确认中 · 非定位确认"
    LaneFusionState.Missing -> "车道融合暂无新鲜数据"
    LaneFusionState.RoadTypeBlocked -> "当前道路未启用车道融合（仅高速/高架）"
    LaneFusionState.GeometryOnly -> "仅有几何宽度 · 车道号未知"
    LaneFusionState.VisionOnly -> "仅有视觉候选 · 等待可靠几何"
    LaneFusionState.Unsynchronized -> "观测不同步 · 车道号未知"
    LaneFusionState.Maneuvering -> "变道中 · 暂不判定车道号"
    LaneFusionState.Conflict -> "视觉与几何冲突 · 车道号未知"
    LaneFusionState.Limited -> "模型计数范围未核准 · 车道号未知"
}
