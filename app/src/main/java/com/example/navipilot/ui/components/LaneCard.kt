package com.example.navipilot.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
 *
 * @param turnType  前方转弯类型（nTBTTurnType）：12=左转,13=右转,51=直行,14=掉头 等
 */
@Composable
fun LaneCard(
    laneConfig: List<LaneInfo>,
    currentLane: Int,          // 1-based, 0=未知
    confidence: Float,         // 0.0~1.0
    turnDist: Int,
    turnText: String,
    totalLanesFromModel: Int,  // openpilot 模型估算的总车道数（兜底）
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    laneChangeReminder: LaneChangeReminder? = null,
    turnType: Int = -1,        // nTBTTurnType（用于转弯类型感知变道提醒）
) {
    val hasLaneData = laneConfig.size >= 2
    val totalLanes = if (hasLaneData) laneConfig.size else totalLanesFromModel.coerceAtLeast(0)

    // ── 变道必要性判断（转弯类型感知） ───────────────────────
    // needLaneChange：当前车道是否需要变道（基于 SDK 推荐或转弯类型推断）
    val needLaneChange = remember(laneConfig, currentLane, turnType) {
        if (currentLane <= 0 || laneConfig.size < 2) return@remember false

        val sdkRec = laneConfig.filter { it.isRecommended }
        if (sdkRec.isNotEmpty()) {
            // 有 SDK 推荐：检查当前是否在推荐车道内
            !sdkRec.any { laneConfig.indexOf(it) + 1 == currentLane }
        } else {
            // 无 SDK 推荐：按转弯类型推断（仅在有明确转弯且距离较近时）
            false  // 由 LaneChangeReminder 在 LaunchedEffect 中处理，UI 层不单独判断
        }
    }

    // SDK 推荐存在时，计算变道方向（用于 UI 指示器）
    val sdkBasedDirection: String? = remember(laneConfig, currentLane, needLaneChange) {
        if (!needLaneChange) return@remember null
        val recIndices = laneConfig.mapIndexedNotNull { i, l -> if (l.isRecommended) i + 1 else null }
        if (recIndices.isEmpty()) return@remember null
        val minRec = recIndices.min()
        if (currentLane < minRec) "RIGHT" else "LEFT"
    }

    // ── 变道提醒触发（含 TTS + 音效）────────────────────────
    LaunchedEffect(currentLane, turnType, turnDist, laneConfig) {
        if (hasLaneData) {
            laneChangeReminder?.checkAndRemind(laneConfig, currentLane, turnType, turnDist)
        }
    }

    // ── 运行时从提醒器获取实际变道方向（含推断方向）──────────
    // 同时考虑 SDK 推荐和转弯推断两个来源
    val effectiveDirection: String? = remember(laneConfig, currentLane, turnType, needLaneChange) {
        if (sdkBasedDirection != null) return@remember sdkBasedDirection
        // 没有 SDK 推荐，但有明确转弯类型 → 从提醒器分析结果推断显示方向
        if (currentLane <= 0 || laneConfig.size < 2) return@remember null
        when (turnType) {
            in LaneChangeReminder.TURN_LEFT_TYPES, in LaneChangeReminder.TURN_UTURN_TYPES -> {
                val target = 1..(if (laneConfig.size <= 3) 1 else 2)
                if (currentLane !in target) "LEFT" else null
            }
            in LaneChangeReminder.TURN_RIGHT_TYPES -> {
                val rightmost = laneConfig.indexOfLast { !LaneChangeReminder.isNonMotorizedLane(it) } + 1
                val minRight = if (laneConfig.size <= 3) rightmost else maxOf(rightmost - 1, (laneConfig.size / 2) + 1)
                if (currentLane < minRight) "RIGHT" else null
            }
            else -> null
        }
    }

    val actualNeedChange = needLaneChange || (effectiveDirection != null && currentLane > 0 && hasLaneData)

    // 需要变道时边框脉冲动画
    val pulseTransition = rememberInfiniteTransition(label = "laneChangePulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "borderAlpha"
    )
    val borderAlpha = if (actualNeedChange) pulseAlpha else 0f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(CardBg)
            .then(
                if (actualNeedChange) Modifier.border(
                    1.5.dp, UrgentColor.copy(alpha = borderAlpha), RoundedCornerShape(8.dp)
                ) else Modifier
            )
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
                needLaneChange = actualNeedChange,
                laneChangeDirection = if (actualNeedChange) effectiveDirection else null,
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
    needLaneChange: Boolean = false,
    laneChangeDirection: String? = null,
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
                    val ctx = LocalContext.current
                    val laneResId = remember(lane.id) {
                        listOf(
                            "landfront_recommend_${lane.id}",
                            "landfront_${lane.id}",
                            "landback_${lane.id}"
                        ).firstNotNullOfOrNull { name ->
                            val id = ctx.resources.getIdentifier(name, "drawable", ctx.packageName)
                            if (id != 0) id else null
                        } ?: 0
                    }
                    if (laneResId != 0) {
                        Image(
                            painter = painterResource(id = laneResId),
                            contentDescription = null,
                            modifier = Modifier.size(26.dp),
                            colorFilter = ColorFilter.tint(
                                when {
                                    isCurrent -> CurrentLaneColor
                                    isRec -> RecommendedBorder
                                    else -> Color.White.copy(alpha = 0.85f)
                                }
                            ),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
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
        }

        // — 第 2 行：变道方向指示器 ────────────
        if (needLaneChange && laneChangeDirection != null) {
            val isLeft = laneChangeDirection == "LEFT"
            val dirText = if (isLeft)
                localized("请向左变道", "Change left")
            else
                localized("请向右变道", "Change right")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(UrgentColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (isLeft) Icons.AutoMirrored.Filled.ArrowBack
                                  else Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = UrgentColor,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = dirText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = UrgentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // — 第 3 行：引导文字 ─────────────────
        GuidanceRow(
            currentLane = currentLane,
            lanes = lanes,
            turnDist = turnDist,
            turnText = turnText,
            confidence = confidence,
            needLaneChange = needLaneChange,
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
                fontSize = 14.sp,
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
                fontSize = 14.sp,
                color = GuidanceColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                text = localized("车道数据获取中", "Lane data..."),
                fontSize = 14.sp,
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
    needLaneChange: Boolean = false,
) {
    val recommendedLanes = lanes.filter { it.isRecommended }
    val hasRecLanes = recommendedLanes.isNotEmpty()
    val inRecommendedLane = hasRecLanes && recommendedLanes.any {
        it.driveWayNumber == currentLane || (lanes.indexOf(it) + 1 == currentLane)
    }

    // 当需要变道时用醒目背景
    val rowBg = if (needLaneChange) UrgentColor.copy(alpha = 0.08f) else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 1.dp)
            .background(rowBg, RoundedCornerShape(3.dp)),
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
                "▶ ${localized("向右变道", "Move right")}"
            else -> "◀ ${localized("向左变道", "Move left")}"
        }
        // 附加非机动车道提示（最右侧为非机动车道时提示驾驶员注意）
        val nonMotorNote = if (lanes.isNotEmpty() && LaneChangeReminder.isNonMotorizedLane(lanes.last()))
            " ⚡" else ""   // ⚡ 小图标提示最右道为非机动

        if (guidanceText.isNotEmpty()) {
            Text(
                text = guidanceText + nonMotorNote,
                fontSize = 14.sp,
                color = if (inRecommendedLane) CurrentLaneColor
                       else if (needLaneChange) UrgentColor
                       else GuidanceColor,
                fontWeight = if (needLaneChange) FontWeight.Bold
                       else if (!inRecommendedLane && hasRecLanes) FontWeight.Medium
                       else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }

        // 置信度指示点（需要变道时显示为红色脉冲点）
        Spacer(Modifier.width(4.dp))
        val confidenceColor = when {
            needLaneChange -> UrgentColor
            confidence >= 0.8f -> ConfidenceGreen
            confidence >= 0.5f -> ConfidenceYellow
            else -> ConfidenceRed
        }
        Box(
            modifier = Modifier
                .size(if (needLaneChange) 6.dp else 5.dp)
                .clip(CircleShape)
                .background(confidenceColor)
        )
    }
}

