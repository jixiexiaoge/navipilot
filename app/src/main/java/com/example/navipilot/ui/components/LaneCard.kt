package com.example.navipilot.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.navipilot.LaneInfo
import com.example.navipilot.ui.utils.localized

// ── 配色 ──────────────────────────────────────────
private val CardBg = Color(0xCC0F172A)          // 半透深色背景（与面板一致）
private val CurrentLaneColor = Color(0xFF4CAF50) // 绿色
private val RecommendedBorder = Color(0xFF2196F3) // 蓝色
private val RecommendedFill = Color(0xFF2196F3).copy(alpha = 0.12f)
private val NumColor = Color(0xFFFFFFFF).copy(alpha = 0.5f)
private val GuidanceColor = Color(0xFFFFFFFF).copy(alpha = 0.7f)
private val UrgentColor = Color(0xFFEF4444)     // 红色
private val ConfidenceGreen = Color(0xFF4CAF50)
private val ConfidenceYellow = Color(0xFFEAB308)
private val ConfidenceRed = Color(0xFFEF4444)

/**
 * 车道引导条 —— Style A 顶部车道条
 *
 * 在 LED 预览槽位显示车道布局、当前位置和导航引导。
 * - 有车道数据：显示车道位置条 + 绿色框(当前车道) + 蓝色底(推荐车道) + 引导文字
 * - 有车道计数但无详细配置：显示简化编号
 * - 无车道数据：显示简化引导信息
 */
@Composable
fun LaneCard(
    laneConfig: List<LaneInfo>,
    currentLane: Int,       // 1-based, 0=未知
    confidence: Float,      // 0.0~1.0
    turnDist: Int,
    turnText: String,
    totalLanesFromModel: Int, // openpilot 模型估算的总车道数（兜底）
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasLaneData = laneConfig.size >= 2
    val totalLanes = if (hasLaneData) laneConfig.size else totalLanesFromModel.coerceAtLeast(0)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(CardBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp),
    ) {
        if (hasLaneData && totalLanes >= 2) {
            LaneStripContent(
                lanes = laneConfig,
                currentLane = currentLane,
                confidence = confidence,
                turnDist = turnDist,
                turnText = turnText,
            )
        } else if (totalLanes >= 2) {
            // 有车道计数但无详细配置 → 简化显示
            SimpleLaneContent(
                totalLanes = totalLanes,
                currentLane = currentLane,
                turnDist = turnDist,
                turnText = turnText,
            )
        } else {
            // 完全没有车道信息
            NoLaneDataHint(turnDist = turnDist, turnText = turnText)
        }
    }
}

// ═══════════════════════════════════════════════════════
// 完整车道条（有 laneConfig）
// ═══════════════════════════════════════════════════════

@Composable
private fun LaneStripContent(
    lanes: List<LaneInfo>,
    currentLane: Int,
    confidence: Float,
    turnDist: Int,
    turnText: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // — 第 1 行：车道位置条 ────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            lanes.forEachIndexed { idx, lane ->
                val laneIdx = idx + 1
                val isCurrent = laneIdx == currentLane
                val isRec = lane.isRecommended

                Box(
                    modifier = Modifier.weight(1f).padding(horizontal = 1.dp).then(
                        when {
                            isCurrent -> {
                                val borderColor = if (confidence >= 0.6f) CurrentLaneColor else ConfidenceYellow
                                Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .border(2.dp, borderColor, RoundedCornerShape(4.dp))
                                    .defaultMinSize(minHeight = 32.dp)
                            }
                            isRec -> Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(RecommendedFill)
                                .border(1.dp, RecommendedBorder, RoundedCornerShape(4.dp))
                                .defaultMinSize(minHeight = 32.dp)
                            else -> Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .defaultMinSize(minHeight = 32.dp)
                        }
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = laneIdx.toString(),
                        fontSize = 14.sp,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        color = if (isCurrent) CurrentLaneColor else NumColor,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // — 第 3 行：引导文字 ─────────────────
        GuidanceRow(
            currentLane = currentLane,
            lanes = lanes,
            turnDist = turnDist,
            turnText = turnText,
            confidence = confidence,
        )
    }
}

// ═══════════════════════════════════════════════════════
// 简化车道条（有 count 但无 laneConfig）
// ═══════════════════════════════════════════════════════

@Composable
private fun SimpleLaneContent(
    totalLanes: Int,
    currentLane: Int,
    turnDist: Int,
    turnText: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // 简化车道条：只显示编号和当前位置
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (i in 1..totalLanes) {
                val isCurrent = i == currentLane
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    if (isCurrent) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier.size(7.dp).clip(CircleShape).background(CurrentLaneColor)
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                text = i.toString(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = CurrentLaneColor,
                            )
                        }
                    } else {
                        Text(
                            text = i.toString(),
                            fontSize = 11.sp,
                            color = NumColor,
                        )
                    }
                }
            }
        }

        // 引导文字（精简）
        if (turnDist > 0 && turnText.isNotEmpty()) {
            Text(
                text = "${turnDist}m后${turnText}",
                fontSize = 10.sp,
                color = GuidanceColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ═══════════════════════════════════════════════════════
// 无车道数据提示
// ═══════════════════════════════════════════════════════

@Composable
private fun NoLaneDataHint(turnDist: Int, turnText: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (turnDist > 0 && turnText.isNotEmpty()) {
            Text(
                text = "${turnDist}m ${turnText}",
                fontSize = 12.sp,
                color = GuidanceColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                text = localized("车道数据获取中", "Lane data..."),
                fontSize = 12.sp,
                color = NumColor,
            )
        }
        Spacer(Modifier.width(6.dp))
        // 点击提示
        Text(
            text = "▸",
            fontSize = 11.sp,
            color = NumColor,
        )
    }
}

// ═══════════════════════════════════════════════════════
// 引导文字行（公共）
// ═══════════════════════════════════════════════════════

@Composable
private fun GuidanceRow(
    currentLane: Int,
    lanes: List<LaneInfo>,
    turnDist: Int,
    turnText: String,
    confidence: Float,
) {
    val recommendedLanes = lanes.filter { it.isRecommended }
    val hasRecLanes = recommendedLanes.isNotEmpty()
    val inRecommendedLane = hasRecLanes && recommendedLanes.any {
        it.driveWayNumber == currentLane || (lanes.indexOf(it) + 1 == currentLane)
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 引导建议
        val guidanceText = when {
            !hasRecLanes || currentLane <= 0 -> {
                if (turnDist > 0 && turnText.isNotEmpty())
                    "→ ${turnDist}m $turnText" else ""
            }
            inRecommendedLane -> "✓ ${localized("保持车道", "Keep lane")}"
            currentLane < lanes.indexOfFirst { it.isRecommended } + 1 ->
                "→ ${localized("向右变道", "Move right")}"
            else -> "← ${localized("向左变道", "Move left")}"
        }

        if (guidanceText.isNotEmpty()) {
            Text(
                text = guidanceText,
                fontSize = 10.sp,
                color = if (inRecommendedLane) CurrentLaneColor else GuidanceColor,
                fontWeight = if (!inRecommendedLane && hasRecLanes) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }

        // 置信度指示点
        Spacer(Modifier.width(4.dp))
        val confidenceColor = when {
            confidence >= 0.8f -> ConfidenceGreen
            confidence >= 0.5f -> ConfidenceYellow
            else -> ConfidenceRed
        }
        Box(
            modifier = Modifier.size(5.dp).clip(CircleShape).background(confidenceColor)
        )
    }
}

