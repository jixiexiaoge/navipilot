package com.jixiexiaoge.drivingassist.ui.components

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.jixiexiaoge.drivingassist.data.OvertakeDisplaySnapshot
import com.jixiexiaoge.drivingassist.data.OvertakeSideDisplay
import com.jixiexiaoge.drivingassist.data.TelemetryAvailability
import com.jixiexiaoge.drivingassist.data.AutomationObservation
import com.jixiexiaoge.drivingassist.ui.theme.Accent
import com.jixiexiaoge.drivingassist.ui.utils.localized

internal fun laneMarkingLabel(code: Int?): String {
    if (code == null) return localized("未知", "Unknown")
    if (code == -1) return localized("未报告车道线（码 -1）", "No line reported (code -1)")
    val color = when (code / 10) {
        1 -> localized("白色", "White")
        2 -> localized("黄色", "Yellow")
        3 -> localized("蓝色", "Blue")
        else -> ""
    }
    val type = if (code in 0..31 && code % 10 in 0..1) {
        if (code % 10 == 0) localized("虚线", "Dashed") else localized("实线", "Solid")
    } else localized("类型未确认", "Unconfirmed type")
    return "$color$type · ${localized("原始码", "raw code")} $code"
}

internal fun overtakeSidesUseTwoColumns(widthDp: Float, fontScale: Float): Boolean =
    telemetryMetricColumns((widthDp - 8f) / 2f - 16f, fontScale) >= 2

@Composable
internal fun automationCapabilityNotice(observation: AutomationObservation, experimental: Boolean = false) {
    Info(if (experimental) localized("条件实验：仅配置 · 自动切换未启用",
        "CEM: configuration only · automatic switching unavailable")
    else localized("App自动请求已暂停：compact缺少完整控制授权",
        "App automatic requests suspended: compact lacks complete control authority"), emphasis = true)
    Info(when (observation) {
        AutomationObservation.Disconnected -> localized("7000未连接", "7000 disconnected")
        AutomationObservation.Waiting -> localized("等待7000必要服务数据", "Waiting for required 7000 services")
        AutomationObservation.Expired -> localized("7000必要服务数据过期或时钟异常",
            "Required 7000 services expired or clock invalid")
        AutomationObservation.MissingAuthority -> localized("7000观测新鲜，但缺少完整控制授权契约",
            "7000 observations fresh, but no complete control-authority contract")
    })
}

@Composable
internal fun overtakeConfigurationStatus(mode: Int, observation: AutomationObservation) {
    val availability = when (observation) {
        AutomationObservation.MissingAuthority -> TelemetryAvailability.Fresh
        AutomationObservation.Expired -> TelemetryAvailability.Expired
        else -> TelemetryAvailability.Missing
    }
    TelemetryCard(localized("自动超车状态 · 只读", "Overtake status · read-only"),
        "overtake-status", Accent, Icons.Default.Info, availability,
        sourceLabel = localized("7000必要服务 · 非授权", "Required 7000 services · not authority")) {
        overtakeConfiguration(mode)
        automationCapabilityNotice(observation)
        Info(localized("仅保存本机配置，不运行自动决策；手动请求入队不代表车端执行",
            "Local configuration only, no automatic decisions; manual enqueue is not vehicle execution"))
    }
}

@Composable
private fun overtakeConfiguration(mode: Int) {
    Info(when (mode) {
        0 -> localized("配置：禁止超车", "Configured: disabled")
        1 -> localized("配置：拨杆超车", "Configured: signal mode")
        2 -> localized("配置：自动超车", "Configured: automatic")
        else -> localized("配置：未知", "Configured: unknown")
    }, emphasis = true)
}

@Composable
internal fun OvertakeStatusCard(data: OvertakeDisplaySnapshot, showObservations: Boolean = true) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("CarrotAmap", Context.MODE_PRIVATE) }
    var mode by remember(prefs) { mutableIntStateOf(prefs.getInt("overtake_mode", 1)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "overtake_mode") mode = prefs.getInt(key, 1)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    OvertakeStatusCard(data, mode, showObservations)
}

@Composable
internal fun OvertakeStatusCard(data: OvertakeDisplaySnapshot, mode: Int, showObservations: Boolean = true) {
    TelemetryCard(localized("自动超车状态 · 只读", "Overtake status · read-only"),
        "overtake-status", Accent, Icons.Default.Info, data.left.vehicleAvailability,
        sourceLabel = "7000/carState") {
        overtakeConfiguration(mode)
        Info(localized("App自动请求已暂停：compact缺少完整控制授权",
            "App automatic requests suspended: compact lacks complete control authority"), emphasis = true)
        if (showObservations) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (overtakeSidesUseTwoColumns(maxWidth.value, LocalDensity.current.fontScale)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) { sidePanel(data.left, true) }
                        Column(Modifier.weight(1f)) { sidePanel(data.right, false) }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        sidePanel(data.left, true)
                        sidePanel(data.right, false)
                    }
                }
            }
            Info(localized("侧前方目标不保证位于相邻车道；未检出、虚线和高概率均不代表可变道",
                "Side-forward selections may not be adjacent; no detection, dashed lines and high scores are not clearance"))
            Info(localized("本车绝对车道与变道完成未确认；本卡不发送命令",
                "Absolute ego lane and lane-change completion unconfirmed; this card sends no commands"))
        }
    }
}

@Composable
private fun sidePanel(side: OvertakeSideDisplay, left: Boolean) {
    val key = if (left) "left" else "right"
    fun age(value: TelemetryAvailability) = when (value) {
        TelemetryAvailability.Fresh -> localized("本机接收新鲜", "Locally fresh")
        TelemetryAvailability.Expired -> localized("已过期", "Expired")
        TelemetryAvailability.Missing -> localized("未收到", "Not received")
    }
    DetailPanel(Accent, Modifier.testTag("overtake-$key")) {
        Info(if (left) localized("左侧观察", "Left observations") else localized("右侧观察", "Right observations"),
            emphasis = true)
        MetricGrid(listOf(
            Metric("overtake-$key-blindspot", localized("盲区触发", "Blindspot"), blindspotLabel(side.blindspot)),
            Metric("overtake-$key-marking", localized("车辆车道线", "Vehicle marking"), laneMarkingLabel(side.laneCode)),
            Metric("overtake-$key-target", localized("侧前方选中目标", "Side-forward selection"), when (side.targetPresent) {
                true -> localized("报告目标", "Target reported")
                false -> localized("未检出（非通行确认）", "Not detected (not clearance)")
                null -> localized("未知", "Unknown")
            }),
            Metric("overtake-$key-distance", localized("目标距离", "Target distance"), side.targetDistanceM?.let { "${decimal(it)} m" } ?: "—"),
            Metric("overtake-$key-relative", localized("相对速度（负值接近）", "Relative speed (negative closing)"),
                side.targetRelativeKph?.let { "${decimal(it)} km/h" } ?: "—"),
            Metric("overtake-$key-probability", localized("边界 / 外侧线概率", "Boundary / outer line probability"),
                listOf(side.boundaryProbability, side.outerProbability).joinToString(" / ") {
                    it?.let { value -> "${(value * 100).toInt()}%" } ?: "—"
                }),
        ), Accent, maxColumns = 2)
        Info(localized("车辆 ${age(side.vehicleAvailability)} · 目标 ${age(side.targetAvailability)} · 模型 ${age(side.modelAvailability)}",
            "Vehicle ${age(side.vehicleAvailability)} · target ${age(side.targetAvailability)} · model ${age(side.modelAvailability)}"))
    }
}
