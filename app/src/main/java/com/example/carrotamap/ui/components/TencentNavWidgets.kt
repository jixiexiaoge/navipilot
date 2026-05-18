package com.example.carrotamap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================
// 腾讯导航页面子组件（从 TencentNavPage.kt 拆分）
// ============================================================

/**
 * Comma3/OpenPilot 连接状态指示器
 * 显示：连接状态 + 自动驾驶状态 + 车速 + 数据发送统计
 */
@Composable
internal fun TencentComma3StatusBadge(
    ip: String = "",
    isOnroad: Boolean = false,
    active: Boolean = false,
    vEgoKph: Int = 0,
    carrot2: String = "",
    totalSent: Int = 0,
    lastSendAgo: Long = 0,  // 距上次发送的毫秒数
    modifier: Modifier = Modifier
) {
    val isConnected = ip.isNotEmpty()
    if (!isConnected) return

    val statusColor = when {
        active -> Color(0xFF10B981)       // 自动驾驶中 — 绿色
        isOnroad -> Color(0xFF3B82F6)     // 在路上但未激活 — 蓝色
        else -> Color(0xFF94A3B8)          // 已连接但未上路 — 灰色
    }
    val statusText = when {
        active -> "AP"
        isOnroad -> "ON"
        else -> "C3"
    }
    // 发送状态：绿色=正常(<1s), 黄色=延迟(1-3s), 红色=超时(>3s)
    val sendHealthColor = when {
        lastSendAgo <= 0 -> Color(0xFF64748B)
        lastSendAgo < 1000 -> Color(0xFF10B981)
        lastSendAgo < 3000 -> Color(0xFFFBBF24)
        else -> Color(0xFFEF4444)
    }

    Surface(
        modifier = modifier,
        color = Color(0xFF0F172A).copy(alpha = 0.85f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 6.dp, vertical = 3.dp)
                .widthIn(min = 60.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 状态圆点
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .background(statusColor, CircleShape)
            )
            Text(
                text = statusText,
                color = statusColor,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold
            )
            if (active && vEgoKph > 0) {
                Text(
                    text = "${vEgoKph}",
                    color = Color.White,
                    fontSize = 8.sp
                )
            }
            // 发送统计
            if (totalSent > 0) {
                Text(
                    text = "↑${if (totalSent > 999) "${totalSent / 1000}k" else "$totalSent"}",
                    color = sendHealthColor,
                    fontSize = 7.sp
                )
            }
        }
    }
}

/**
 * 转弯调试信息卡片
 * 显示：SDK原始枚举值 → 映射后的nTBTTurnType → 对应的转弯类型名称
 */
@Composable
internal fun TencentTurnDebugBadge(
    sdkIntersectionType: Int = -1,
    nTBTTurnType: Int = -1,
    nTBTDist: Int = 0,
    szTBTMainText: String = "",
    modifier: Modifier = Modifier
) {
    // 无数据时不显示
    if (sdkIntersectionType < 0 && nTBTTurnType < 0) return

    // nTBTTurnType → 转弯类型中文名
    val turnName = when (nTBTTurnType) {
        51 -> "直行"
        12 -> "左转"
        13 -> "右转"
        102 -> "左前方"
        101 -> "右前方"
        17 -> "左后方"
        19 -> "右后方"
        14 -> "掉头"
        131 -> "进入环岛"
        132 -> "驶出环岛"
        153 -> "岔路/收费站"
        55 -> "服务区"
        201 -> "到达目的地"
        -1 -> "未知"
        else -> "?($nTBTTurnType)"
    }

    // SDK原始值 → SDK枚举含义
    val sdkName = when (sdkIntersectionType) {
        0 -> "无动作"
        1 -> "直行"
        2 -> "左转"
        3 -> "右转"
        4 -> "左前方"
        5 -> "右前方"
        6 -> "左后方"
        7 -> "右后方"
        8 -> "走中间"
        9 -> "靠左"
        10 -> "靠右"
        11 -> "进环岛"
        12 -> "靠左主路直行"
        13 -> "服务区"
        14 -> "收费站"
        15 -> "到达"
        18 -> "靠左行驶"
        22 -> "靠右行驶"
        61 -> "到达目的地"
        64 -> "进入隧道"
        66 -> "过收费站"
        81 -> "沿主路直行"
        87 -> "右转掉头"
        1001 -> "匝道直行"
        -1 -> "-"
        else -> "?($sdkIntersectionType)"
    }

    Surface(
        modifier = modifier,
        color = Color(0xFF0F172A).copy(alpha = 0.85f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
        ) {
            // 标题
            Text(
                text = "🔧 Turn",
                color = Color(0xFF64748B),
                fontSize = 7.sp
            )
            // SDK原始值
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "SDK", color = Color(0xFF94A3B8), fontSize = 7.sp)
                Text(
                    text = "$sdkIntersectionType",
                    color = Color(0xFFFBBF24),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(text = sdkName, color = Color(0xFF94A3B8), fontSize = 7.sp)
            }
            // 映射后的值
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "MAP", color = Color(0xFF94A3B8), fontSize = 7.sp)
                Text(
                    text = "$nTBTTurnType",
                    color = Color(0xFF10B981),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(text = turnName, color = Color(0xFF10B981), fontSize = 7.sp)
            }
            // 距离 + 文本
            if (nTBTDist > 0 || szTBTMainText.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (nTBTDist > 0) {
                        val distText = if (nTBTDist >= 1000) "${"%.1f".format(nTBTDist / 1000.0)}km" else "${nTBTDist}m"
                        Text(text = distText, color = Color.White, fontSize = 7.sp)
                    }
                    if (szTBTMainText.isNotEmpty()) {
                        Text(
                            text = szTBTMainText.take(8),
                            color = Color(0xFF94A3B8),
                            fontSize = 7.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
