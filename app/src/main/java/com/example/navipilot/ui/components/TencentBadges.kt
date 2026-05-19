package com.example.navipilot.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.navipilot.ui.utils.localized
import kotlin.math.roundToInt

@Composable
fun TencentComma3StatusBadge(
    ip: String,
    isOnroad: Boolean,
    active: Boolean,
    vEgoKph: Int,
    carrot2: String,
    totalSent: Int,
    lastSendAgo: Long,
    modifier: Modifier = Modifier,
) {
    val container = Color(0xFF0F172A).copy(alpha = 0.80f)
    val label = Color(0xFF94A3B8)
    val value = Color(0xFFE2E8F0)
    val ok = Color(0xFF34D399)
    val warn = Color(0xFFFBBF24)

    val statusColor = when {
        active -> ok
        isOnroad -> warn
        else -> label
    }

    val lastSendSec = if (lastSendAgo > 0) (lastSendAgo / 1000.0).roundToInt() else -1

    Surface(
        modifier = modifier,
        color = container,
        tonalElevation = 2.dp,
        shadowElevation = 3.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = localized("comma3 状态", "comma3 status"),
                color = value,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )

            Row(modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    text = localized("IP", "IP") + ":",
                    color = label,
                    fontSize = 10.sp,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (ip.isBlank()) localized("未知", "Unknown") else ip,
                    color = value,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(modifier = Modifier.padding(top = 2.dp)) {
                Text(
                    text = localized("模式", "Mode") + ":",
                    color = label,
                    fontSize = 10.sp,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = when {
                        active -> localized("接管", "Active")
                        isOnroad -> localized("上路", "Onroad")
                        else -> localized("离线", "Offline")
                    },
                    color = statusColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = localized("车速", "Speed") + ":",
                    color = label,
                    fontSize = 10.sp,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${vEgoKph}km/h",
                    color = value,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            }

            Row(modifier = Modifier.padding(top = 2.dp)) {
                Text(
                    text = localized("发送", "Sent") + ":",
                    color = label,
                    fontSize = 10.sp,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = totalSent.toString(),
                    color = value,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = localized("最近", "Last") + ":",
                    color = label,
                    fontSize = 10.sp,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (lastSendSec >= 0) "${lastSendSec}s" else "-",
                    color = value,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            }

            if (carrot2.isNotBlank()) {
                Text(
                    text = carrot2,
                    color = label,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
fun TencentTurnDebugBadge(
    sdkIntersectionType: Int,
    nTBTTurnType: Int,
    nTBTDist: Int,
    szTBTMainText: String,
    modifier: Modifier = Modifier,
) {
    val container = Color(0xFF0F172A).copy(alpha = 0.80f)
    val label = Color(0xFF94A3B8)
    val value = Color(0xFFE2E8F0)

    Surface(
        modifier = modifier,
        color = container,
        tonalElevation = 2.dp,
        shadowElevation = 3.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = localized("转向调试", "Turn debug"),
                color = value,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )

            Row(modifier = Modifier.padding(top = 4.dp)) {
                Text(text = "sdk:", color = label, fontSize = 10.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = sdkIntersectionType.toString(), color = value, fontSize = 10.sp, maxLines = 1)
                Spacer(modifier = Modifier.width(10.dp))
                Text(text = "tbt:", color = label, fontSize = 10.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = nTBTTurnType.toString(), color = value, fontSize = 10.sp, maxLines = 1)
            }

            Row(modifier = Modifier.padding(top = 2.dp)) {
                Text(
                    text = localized("距离", "Dist") + ":",
                    color = label,
                    fontSize = 10.sp,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${nTBTDist}m",
                    color = value,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            }

            if (szTBTMainText.isNotBlank()) {
                Text(
                    text = szTBTMainText,
                    color = value,
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

