@file:Suppress("FunctionNaming", "MagicNumber", "TooManyFunctions")

package com.jixiexiaoge.drivingassist.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jixiexiaoge.drivingassist.data.DashboardPanel
import com.jixiexiaoge.drivingassist.data.DashboardSnapshot
import com.jixiexiaoge.drivingassist.data.TelemetryAvailability
import com.jixiexiaoge.drivingassist.ui.theme.TextSecondary
import com.jixiexiaoge.drivingassist.ui.utils.localized

@Composable
internal fun DashboardOverview(
    snapshot: DashboardSnapshot,
    pip: (@Composable () -> Unit)? = null,
    drivingMode: com.jixiexiaoge.drivingassist.navigation.DrivingMode? = null,
) {
    Column(Modifier.fillMaxWidth().testTag("dashboard-overview"), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        DriveViewCard(snapshot, pip = pip, drivingMode = drivingMode)
        DashboardDetailBlocks(snapshot)
    }
}

/**
 * 驾驶视图卡片之后的明细块（**告警横幅 + 规划与控制**）。
 *
 * 实时页把卡片固定在页面顶部（画中画里的 SurfaceView 不能跟着滚动），
 * 只让这部分滚动，所以单独抽出来复用；[DashboardOverview] 仍然按原顺序渲染，
 * 供纯看板场景与既有测试使用。
 */
@Composable
internal fun DashboardDetailBlocks(snapshot: DashboardSnapshot) {
    DashboardAlertBanner(snapshot)
    DashboardPanelGroup(snapshot, "规划与控制", listOf("plan", "control", "lane", "alert"))
}

@Composable
internal fun DashboardPanelGroup(snapshot: DashboardSnapshot, title: String, ids: List<String>) {
    Column(
        Modifier.fillMaxWidth().testTag("dashboard-group-${ids.first()}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Surface(color = Color(0xFF121E2C), shape = RoundedCornerShape(10.dp)) {
            Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ids.mapNotNull { id -> snapshot.panels.firstOrNull { it.id == id } }
                    .forEachIndexed { index, panel ->
                        if (index > 0) HorizontalDivider(color = Color(0xFF213544))
                        DashboardDetail(panel)
                    }
            }
        }
    }
}

/**
 * 车端告警横幅：**只有真的存在告警时才渲染**。
 *
 * 7.9.41 把原来那块「运营摘要」整块删掉，只留告警：
 * - 「横向激活 / 纵向激活 / 变道阶段」三格 → 方向盘与定速的变色 + 规划与控制面板已有
 * - 「当前引导（动作 + 距离）」 → 与驾驶卡上的驾驶提示、导航页完全重复
 * - 「实际纵向加速度 / 制动保持」→ 纵向规划面板与卡上的制动灯已有
 *
 * 没有告警时这里什么都不画，也不再占一行「未收到 / 已过期」的占位文字。
 */
@Composable
private fun DashboardAlertBanner(snapshot: DashboardSnapshot) {
    val alert = snapshot.panels.firstOrNull { it.id == "alert" } ?: return
    if (alert.status != "fresh" || alert.value("alert-title") == "车端未提供告警") return
    val color = if (alert.tone == "critical") Color(0xFFFF6D7D) else Color(0xFFFFBB70)
    Column(
        Modifier.fillMaxWidth().background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("dashboard-alert-banner"),
    ) {
        Text(alert.value("alert-title"), color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        if (alert.value("alert-detail") != "未知") {
            Text(alert.value("alert-detail"), color = Color.White, fontSize = 11.sp)
        }
    }
}

private fun DashboardPanel?.value(id: String): String =
    if (this?.status == "fresh") metrics.firstOrNull { it.id == id }?.value ?: "未知" else "未知"

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun DashboardDetail(panel: DashboardPanel) {
    val accent = when (panel.tone) {
        "critical" -> Color(0xFFFF6D7D)
        "userPrompt", "orange" -> Color(0xFFFFBB70)
        "cyan" -> Color(0xFF5DE4D4)
        else -> Color(0xFF8EB5FF)
    }
    val availability = when (panel.status) {
        "fresh" -> TelemetryAvailability.Fresh
        "expired" -> TelemetryAvailability.Expired
        else -> TelemetryAvailability.Missing
    }
    Column(Modifier.fillMaxWidth().testTag("dashboard-panel-${panel.id}"),
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(panel.title, Modifier.align(Alignment.CenterVertically),
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(availabilityLabel(availability), Modifier.align(Alignment.CenterVertically),
                color = if (panel.status == "fresh") accent else TextSecondary, fontSize = 9.sp)
        }
        MetricGrid(panel.metrics.map {
            Metric("dashboard-${it.id}", it.label, it.value + if (it.unit.isEmpty()) "" else " ${it.unit}")
        }, accent, fillLastRow = true)
    }
}
