package com.jixiexiaoge.drivingassist.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jixiexiaoge.drivingassist.data.CarrotDisplaySnapshot
import com.jixiexiaoge.drivingassist.data.NavigationReception
import com.jixiexiaoge.drivingassist.data.ReceivedAlert
import com.jixiexiaoge.drivingassist.data.ReceivedGuidance
import com.jixiexiaoge.drivingassist.data.ReceivedNavigationLanes
import com.jixiexiaoge.drivingassist.data.TelemetryAvailability
import com.jixiexiaoge.drivingassist.ui.theme.Accent
import com.jixiexiaoge.drivingassist.ui.theme.PrimaryLight
import com.jixiexiaoge.drivingassist.ui.theme.Surface800
import com.jixiexiaoge.drivingassist.ui.theme.Surface900
import com.jixiexiaoge.drivingassist.ui.theme.TextSecondary
import com.jixiexiaoge.drivingassist.ui.theme.TextTertiary
import com.jixiexiaoge.drivingassist.ui.utils.localized
import kotlin.math.ceil

private val CarrotOrange = Color(0xFFED9121)
private val LocalGroupedTelemetry = staticCompositionLocalOf { false }

@Composable
internal fun VehicleDetailsGroup(data: CarrotDisplaySnapshot) {
    Card(Modifier.fillMaxWidth().testTag("vehicle-details-group")
        .border(1.dp, Accent.copy(alpha = 0.16f), RoundedCornerShape(18.dp)),
        colors = CardDefaults.cardColors(containerColor = Surface800),
        shape = RoundedCornerShape(18.dp)) {
        CompositionLocalProvider(LocalGroupedTelemetry provides true) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                CarrotBehaviorCard(data)
                HorizontalDivider(color = Accent.copy(alpha = 0.16f))
                CarrotVehiclePlanCard(data)
                HorizontalDivider(color = Accent.copy(alpha = 0.16f))
                PerceptionQualityCard(data)
            }
        }
    }
}

internal fun blindspotLabel(value: Boolean?): String = when (value) {
    true -> localized("检测触发", "Detection triggered")
    false -> localized("未触发（非安全确认）", "Not triggered (not clearance)")
    null -> localized("未知", "Unknown")
}

internal fun frontTargetLabel(data: CarrotDisplaySnapshot): String = when {
    data.lead != null -> localized("有效前车目标", "Valid selected lead")
    data.leadPresent == false -> localized("未报告前车（非道路无车）", "No lead reported (not an empty road)")
    data.leadPresent == true -> localized("目标不完整 / 无效", "Incomplete / invalid target")
    else -> localized("未知", "Unknown")
}

@Composable
internal fun CarrotJourneySummary(data: CarrotDisplaySnapshot) {
    TelemetryCard(localized("行程概览", "Journey"), "carrot-journey", Accent, Icons.Default.Place,
        data.navigationAvailability, sourceLabel = "7000/carrotNavi") {
        MetricGrid(listOf(
            Metric("journey-distance", localized("剩余距离", "Remaining distance"),
                data.journey?.distanceM?.let { distance(it.toFloat()) } ?: "—"),
            Metric("journey-time", localized("预计剩余", "Time remaining"),
                data.journey?.timeSec?.let { localized("${ceil(it / 60.0).toInt()} 分钟", "${ceil(it / 60.0).toInt()} min") } ?: "—"),
        ), Accent)
        data.journey?.progress?.let { Progress(it, localized("行程进度", "Journey progress"), Accent, "carrot-journey-progress") }
            ?: Info(localized("行程进度 · 当前无数据", "Journey progress · no current data"))
        Info(navigationLabel(data))
        data.navigationAgeMs?.let {
            Info(localized("车端导航 ${decimal(it / 1000f)}秒前收到", "Navigation received ${decimal(it / 1000f)}s ago"))
        } ?: Info(localized("车端导航接收时间 · 当前无数据", "Navigation receive age · no current data"))
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun CarrotReceivedStatusCard(data: CarrotDisplaySnapshot) {
    TelemetryCard(localized("carrot 处理结果", "Carrot results"), "carrot-results", CarrotOrange,
        Icons.Default.Info, data.resultAvailability, sourceLabel = "7000/carrotMan") {
        data.alert?.let { AlertBanner(it) }
        if (data.alertAvailability != TelemetryAvailability.Fresh) {
            Info(localized("告警数据：", "Alert data: ") + availabilityLabel(data.alertAvailability))
        } else if (data.alert == null) {
            Info(localized("当前车端未提供告警", "No alert supplied by the vehicle"))
        }
        MetricGrid(listOf(
            Metric("advisory-speed", localized("车端建议", "Carrot advisory"), data.desiredSpeedKph?.let { "$it km/h" } ?: "—"),
            Metric("adopted-limit", localized("车端采用限速", "Received limit"), data.adoptedLimitKph?.let { "$it km/h" } ?: "—"),
        ), CarrotOrange)
        Info(if (data.desiredSpeedKph != null) speedReason(data.desiredSource)
            else if (data.resultAvailability == TelemetryAvailability.Fresh) localized("未提供有效速度约束", "No valid speed constraint")
            else availabilityLabel(data.resultAvailability), emphasis = true)
        data.resultAgeMs?.let { Info(localized("${decimal(it / 1000f)}秒前 · 非巡航设定", "${decimal(it / 1000f)}s ago · not cruise setting")) }
            ?: Info(localized("建议值 · 非巡航设定", "Advisory · not cruise setting"))
        DetailPanel(CarrotOrange, Modifier.testTag("carrot-vehicle-navigation")) {
            val vehicle = data.vehicleNavigation
            Info(localized("车辆自带导航测速 · 非高德接收确认",
                "Vehicle-native navigation speed · not AMap receipt confirmation"), emphasis = true)
            MetricGrid(listOf(
                Metric("vehicle-navi-available", localized("可用 / 激活", "Available / active"),
                    "${switchState(vehicle?.available)} / ${switchState(vehicle?.active)}"),
                Metric("vehicle-navi-speed", localized("车辆导航限速", "Vehicle navigation limit"),
                    vehicle?.speedKph?.let { "$it km/h" } ?: "—"),
                Metric("vehicle-navi-section", localized("车辆区间测速", "Vehicle section control"),
                    switchState(vehicle?.sectionActive)),
            ), CarrotOrange)
        }

        DetailPanel(CarrotOrange, Modifier.testTag("carrot-speed-events")) {
            Info(localized("车端测速提醒", "Received speed events"), emphasis = true)
            if (data.events.isEmpty()) Info(localized("当前未提供有效测速提醒", "No valid speed event supplied"))
            data.events.forEach { event ->
                Info(speedEventLabel(event.type), emphasis = true)
                Info(localized(
                    "事件限速 ${event.limitKph?.let { "$it km/h" } ?: "—"} · 距离 ${distance(event.distanceM.toFloat())}",
                    "Event limit ${event.limitKph?.let { "$it km/h" } ?: "—"} · ${distance(event.distanceM.toFloat())}",
                ))
                event.description?.let { Info(it) }
            }
        }
        DetailPanel(Accent, Modifier.testTag("carrot-section")) {
            val section = data.section
            if (section == null) {
                Info(localized("车端区间测速", "Received section control"), emphasis = true)
                Info(localized("当前未提供有效区间测速 · ", "No valid section supplied · ") + availabilityLabel(data.navigationAvailability))
            } else {
                Info(localized("区间测速进行中", "Section control active"), emphasis = true)
                MetricGrid(listOf(
                    Metric("section-limit", localized("区间限速", "Section limit"), section.limitKph?.let { "$it km/h" } ?: "—"),
                    Metric("section-average", localized("区间均速", "Section average"), section.averageKph?.let { "${decimal(it)} km/h" } ?: "—"),
                ), Accent)
                Info(localized("区间剩余 ${section.remainingM?.let(::distance) ?: "—"}", "Section remaining ${section.remainingM?.let(::distance) ?: "—"}"))
                section.progress?.let { Progress(it, localized("区间进度", "Section progress"), Accent, "carrot-section-progress") }
                    ?: Info(localized("区间进度 · 当前无数据", "Section progress · no current data"))
            }
        }
        Info(navigationLabel(data))
        GuidancePanel(localized("车端当前动作", "Received maneuver"), data.currentGuidance)
        GuidancePanel(localized("车端下一动作", "Next received maneuver"), data.nextGuidance)
        NavigationLaneStrip(data.navigationLanes, localized("车端导航推荐", "Received lane guidance"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusPill(localized("横向 ${execution(data.latActive)}", "Lateral ${execution(data.latActive)}"), TextSecondary)
            StatusPill(localized("纵向 ${execution(data.longActive)}", "Longitudinal ${execution(data.longActive)}"), TextSecondary)
        }
        Info(localized("状态展示 · 非控制授权", "State display · not control authority"))
    }
}

@Composable
internal fun BlindspotMetrics(data: CarrotDisplaySnapshot) {
    MetricGrid(listOf(
        Metric("blindspot-left", localized("左盲区", "Left blindspot"), blindspotLabel(data.leftBlindspot)),
        Metric("blindspot-right", localized("右盲区", "Right blindspot"), blindspotLabel(data.rightBlindspot)),
    ), PrimaryLight)
}

@Composable
internal fun CarrotVehiclePlanCard(data: CarrotDisplaySnapshot) {
    TelemetryCard(localized("车辆与规划", "Vehicle & planning"), "carrot-vehicle-plan", PrimaryLight,
        Icons.Default.Info, data.vehicleAvailability, sourceLabel = "7000/carState") {
        BlindspotMetrics(data)
        DetailPanel(PrimaryLight) {
            Info(localized("车辆实测 · ", "Measured vehicle · ") + availabilityLabel(data.vehicleAvailability), emphasis = true)
            MetricGrid(listOf(
                Metric("carrot-gear", localized("挡位", "Gear"), gearLabel(data.vehicleMotion?.gear)),
                Metric("carrot-measured-acceleration", localized("实测纵向加速", "Measured acceleration"),
                    data.vehicleMotion?.accelerationMps2?.let { "${decimal(it)} m/s²" } ?: "—"),
                Metric("carrot-brake-lights", localized("制动灯 / 保持", "Brake lights / hold"),
                    "${switchState(data.vehicleMotion?.brakeLights)} / ${switchState(data.vehicleMotion?.brakeHold)}"),
            ), PrimaryLight)
        }
        DetailPanel(Accent) {
            Info(localized("纵向规划 · ", "Longitudinal plan · ") + availabilityLabel(data.planningAvailability), emphasis = true)
            MetricGrid(listOf(
                Metric("carrot-plan-speed", localized("规划首点速度", "Plan first-point speed"),
                    data.planning?.speedKph?.let { "${decimal(it)} km/h" } ?: "—"),
                Metric("carrot-plan-acceleration", localized("规划首点加速度", "Plan first-point acceleration"),
                    data.planning?.accelerationMps2?.let { "${decimal(it)} m/s²" } ?: "—"),
                Metric("carrot-plan-lanes", localized("车道线规划", "Lane-line planning"),
                    data.laneLinesUsed?.let { if (it) localized("采用", "Used") else localized("未采用", "Not used") } ?: "—"),
            ), Accent)
            Info(localized("车道线采用状态 · ", "Lane-line use state · ") + availabilityLabel(data.laneAvailability))
        }
        DetailPanel(PrimaryLight) {
            Info(localized("车端指令状态 · ", "Vehicle command state · ") + availabilityLabel(data.actuationAvailability), emphasis = true)
            MetricGrid(listOf(
                Metric("carrot-requested-acceleration", localized("请求纵向加速度", "Requested acceleration"),
                    data.actuation?.accelerationMps2?.let { "${decimal(it)} m/s²" } ?: "—"),
                Metric("carrot-requested-steering", localized("请求转向角", "Requested steering angle"),
                    data.actuation?.steeringAngleDeg?.let { "${decimal(it)}°" } ?: "—"),
            ), PrimaryLight)
        }
        Info(localized("规划/指令非执行确认 · 制动标记非踏板状态",
            "Plan/commands are not execution confirmation · brake flags are not pedals"))
    }
}

private fun switchState(value: Boolean?) = when (value) {
    true -> localized("开启", "On")
    false -> localized("关闭", "Off")
    null -> localized("当前无数据", "No current data")
}

private fun gearLabel(value: String?) = when (value) {
    "park" -> localized("驻车 P", "Park P")
    "drive" -> localized("前进 D", "Drive D")
    "neutral" -> localized("空挡 N", "Neutral N")
    "reverse" -> localized("倒车 R", "Reverse R")
    "sport" -> localized("运动 S", "Sport S")
    "low" -> localized("低速 L", "Low L")
    "brake" -> localized("制动挡 B", "Brake B")
    "eco" -> localized("节能", "Eco")
    "manumatic" -> localized("手动", "Manual")
    else -> localized("当前无数据", "No current data")
}

internal fun travelTime(seconds: Int): String = if (seconds < 60) {
    localized("$seconds 秒", "$seconds s")
} else {
    localized("${ceil(seconds / 60.0).toInt()} 分钟", "${ceil(seconds / 60.0).toInt()} min")
}

@Composable
internal fun CarrotLaneChangeStatus(data: CarrotDisplaySnapshot) {
    val lane = data.laneChange
    val state = when (lane?.state) {
        "off" -> localized("未变道", "Idle")
        "preLaneChange" -> localized("准备变道", "Preparing")
        "laneChangeStarting" -> localized("变道中", "Changing lane")
        "laneChangeFinishing" -> localized("变道收尾（非完成确认）", "Finishing (not completion confirmation)")
        else -> availabilityLabel(data.laneAvailability) + localized(" · 状态未知", " · unknown state")
    }
    val direction = when (lane?.direction) {
        "left" -> localized("向左", "Left")
        "right" -> localized("向右", "Right")
        else -> ""
    }.takeIf { lane?.state != "off" }.orEmpty()
    StatusPill(localized("车端变道：", "Vehicle lane change: ") +
        listOf(state, direction).filter { it.isNotBlank() }.joinToString(" · "),
        if (lane?.state in setOf("preLaneChange", "laneChangeStarting", "laneChangeFinishing")) PrimaryLight else TextTertiary)
}

internal data class Metric(val tag: String, val label: String, val value: String, val progress: Float? = null)

internal fun telemetryMetricColumns(widthDp: Float, fontScale: Float): Int = when {
    widthDp / fontScale.coerceAtLeast(1f) >= 420f -> 3
    widthDp / fontScale.coerceAtLeast(1f) >= 280f -> 2
    else -> 1
}

@Composable
internal fun MetricGrid(
    metrics: List<Metric>,
    accent: Color,
    maxColumns: Int = 3,
    fillLastRow: Boolean = false,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = minOf(telemetryMetricColumns(maxWidth.value, LocalDensity.current.fontScale),
            maxColumns.coerceIn(1, 3), metrics.size.coerceAtLeast(1))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            metrics.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { metric ->
                        Column(
                            Modifier.weight(1f).fillMaxHeight().heightIn(min = 44.dp)
                                .background(Surface900.copy(alpha = 0.6f), RoundedCornerShape(9.dp))
                                .border(1.dp, accent.copy(alpha = 0.10f), RoundedCornerShape(9.dp))
                                .padding(horizontal = 6.dp, vertical = 4.dp).testTag(metric.tag),
                            verticalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            Text(metric.label, color = TextSecondary, fontSize = 10.sp, maxLines = 1)
                            Text(metric.value, color = accent, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1)
                            metric.progress?.let {
                                LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth(), color = accent,
                                    trackColor = accent.copy(alpha = 0.12f))
                            }
                        }
                    }
                    if (!fillLastRow) repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun TelemetryCard(
    title: String,
    tag: String,
    accent: Color,
    icon: ImageVector,
    availability: TelemetryAvailability,
    sourceLabel: String = "7000",
    content: @Composable () -> Unit,
) {
    val grouped = LocalGroupedTelemetry.current
    val body: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().testTag(tag).padding(if (grouped) 0.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            TelemetryHeader(title, accent, icon, availability, sourceLabel)
            content()
        }
    }
    if (grouped) {
        body()
    } else Card(
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, accent.copy(alpha = 0.16f), RoundedCornerShape(18.dp)),
        colors = CardDefaults.cardColors(containerColor = Surface800),
        shape = RoundedCornerShape(18.dp),
    ) {
        body()
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TelemetryHeader(title: String, accent: Color, icon: ImageVector,
    availability: TelemetryAvailability, sourceLabel: String) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.weight(1f).heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = RoundedCornerShape(10.dp), color = accent.copy(alpha = 0.13f)) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.padding(5.dp).size(18.dp))
            }
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                modifier = Modifier.weight(1f))
        }
        StatusPill(
            when (availability) {
                TelemetryAvailability.Fresh -> localized("$sourceLabel · 已更新", "$sourceLabel · updated")
                TelemetryAvailability.Expired -> localized("$sourceLabel · 过期", "$sourceLabel · expired")
                TelemetryAvailability.Missing -> localized("$sourceLabel · 未收到", "$sourceLabel · unavailable")
            },
            if (availability == TelemetryAvailability.Fresh) accent else TextTertiary,
        )
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.1f), shape = RoundedCornerShape(10.dp)) {
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp))
    }
}

@Composable
internal fun DetailPanel(color: Color, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier = modifier.fillMaxWidth(), color = color.copy(alpha = 0.07f), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { content() }
    }
}

@Composable
internal fun AlertBanner(alert: ReceivedAlert) {
    val color = when (alert.status) {
        "critical" -> Color(0xFFFB7185)
        "userPrompt" -> Color(0xFFFBBF24)
        else -> Color(0xFF93C5FD)
    }
    DetailPanel(color, Modifier.testTag("carrot-alert").border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(12.dp))) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Text(
                when (alert.status) {
                    "critical" -> localized("车端严重告警", "Critical vehicle alert")
                    "userPrompt" -> localized("车端提示", "Vehicle prompt")
                    else -> localized("车端告警信息", "Vehicle alert")
                }, color = color, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            )
        }
        alert.first?.let { Text(it, color = color, fontSize = 14.sp) }
        alert.second?.let { Text(it, color = color, fontSize = 13.sp) }
    }
}

@Composable
private fun GuidancePanel(label: String, guidance: ReceivedGuidance?) {
    DetailPanel(PrimaryLight) {
        Text(label, color = TextTertiary, fontSize = 12.sp)
        if (guidance == null) {
            Info(localized("当前无数据", "No current data"))
            return@DetailPanel
        }
        Text(listOfNotNull(guidance.text, guidance.roadName,
            guidance.distanceM?.let { distance(it.toFloat()) }).joinToString(" · ").ifBlank { "—" },
            color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NavigationLaneStrip(lanes: ReceivedNavigationLanes?, title: String) {
    DetailPanel(Accent) {
        if (lanes == null) {
            Info(title, emphasis = true)
            Info(localized("当前无数据", "No current data"))
            return@DetailPanel
        }
        Info(localized("$title · ${lanes.count}车道", "$title · ${lanes.count} lanes"), emphasis = true)
        if (lanes.recommendedIndices != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(lanes.count) { index ->
                    val recommended = index in lanes.recommendedIndices
                    Box(Modifier.widthIn(min = 34.dp).heightIn(min = 34.dp).background(if (recommended) Accent.copy(alpha = 0.22f) else Surface900,
                        RoundedCornerShape(8.dp)).border(1.dp, if (recommended) Accent else TextTertiary.copy(alpha = 0.2f),
                        RoundedCornerShape(8.dp)).padding(8.dp).testTag("received-lane-$index"),
                        contentAlignment = Alignment.Center) {
                        Text("${index + 1}", color = if (recommended) Accent else TextTertiary, fontSize = 13.sp,
                            fontWeight = if (recommended) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            Info(if (lanes.recommendedIndices.isEmpty()) localized("未给出推荐车道", "No recommended lane")
                else localized("推荐第 ${lanes.recommendedIndices.joinToString("/") { "${it + 1}" }} 车道",
                    "Recommended lanes ${lanes.recommendedIndices.joinToString("/") { "${it + 1}" }}"))
        } else Info(localized("推荐车道数据不完整", "Recommended lane data incomplete"))
        Info(localized("编号从左到右；导航推荐不代表可安全变道", "Left-to-right numbering; recommendation is not lane-change clearance"))
    }
}

@Composable
internal fun Progress(value: Float, label: String, color: Color, tag: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        LinearProgressIndicator(progress = { value }, modifier = Modifier.fillMaxWidth().testTag(tag), color = color,
            trackColor = color.copy(alpha = 0.12f))
        Info("$label ${"%.0f".format(value * 100)}%")
    }
}

@Composable
internal fun Info(text: String, emphasis: Boolean = false) {
    Text(text, color = if (emphasis) Color(0xFF93C5FD) else TextSecondary,
        fontSize = if (emphasis) 14.sp else 12.sp,
        fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal)
}

internal fun navigationLabel(data: CarrotDisplaySnapshot): String {
    if (data.navigationAvailability != TelemetryAvailability.Fresh) {
        return localized("车端导航：", "Received navigation: ") + availabilityLabel(data.navigationAvailability)
    }
    return when (data.navigation) {
        NavigationReception.Guiding -> localized("车端导航已接入 · 正在引导", "Navigation linked · guiding")
        NavigationReception.Idle -> localized("车端导航已连接 · 未引导", "Navigation linked · idle")
        NavigationReception.Disconnected -> localized("车端导航未接入", "Navigation not linked")
        NavigationReception.Unsupported -> localized("车端导航版本不兼容", "Unsupported navigation version")
        NavigationReception.OffRoute -> localized("车端报告偏离路线", "Vehicle reports off-route")
        NavigationReception.Unknown -> localized("车端导航状态未知", "Received navigation state unknown")
    }
}

internal fun availabilityLabel(availability: TelemetryAvailability) = when (availability) {
    TelemetryAvailability.Fresh -> localized("数据有效", "Fresh data")
    TelemetryAvailability.Expired -> localized("数据已过期", "Data expired")
    TelemetryAvailability.Missing -> localized("未收到／连接不可用", "Not received / unavailable")
}

private fun execution(active: Boolean?) = when (active) {
    true -> localized("激活", "Active")
    false -> localized("未激活", "Inactive")
    null -> localized("未知", "Unknown")
}

internal fun speedReason(source: String?): String {
    val code = source?.trim()?.lowercase().orEmpty()
    val base = code.substringBefore(':')
    val reason = when (base) {
        "cam", "hda" -> localized("电子眼约束", "Camera constraint")
        "section", "hda_section" -> localized("区间测速约束", "Section constraint")
        "bump", "hda_bump" -> localized("减速带约束", "Speed bump constraint")
        "atc", "turn" -> localized("当前转弯约束", "Current turn constraint")
        "atc2" -> localized("下一转弯约束", "Next turn constraint")
        "vturn" -> localized("弯道约束", "Curve constraint")
        "route" -> localized("路线曲率约束", "Route curvature constraint")
        "road" -> localized("道路限速约束", "Road limit constraint")
        "school" -> localized("学校区域约束", "School zone constraint")
        "gas" -> localized("驾驶员加速覆盖", "Driver accelerator override")
        "police" -> localized("警示事件约束", "Police alert constraint")
        "waze" -> localized("Waze测速约束", "Waze camera constraint")
        "model" -> localized("模型速度约束", "Model speed constraint")
        else -> return if (code.isBlank()) localized("来源未知", "Unknown source")
            else localized("未知来源 $code", "Unknown source $code")
    }
    val vehicle = base in setOf("hda", "hda_section", "hda_bump", "school") || code.endsWith(":v")
    return if (vehicle) localized("车辆导航 · $reason", "Vehicle navigation · $reason") else reason
}

internal fun speedEventLabel(type: Int): String = when (type) {
    0 -> localized("红灯／超速监测", "Signal / speed camera")
    1 -> localized("固定测速", "Fixed speed camera")
    2 -> localized("区间测速起点", "Section start")
    3 -> localized("区间测速终点", "Section end")
    4 -> localized("区间测速", "Section control")
    6 -> localized("红灯监测", "Red light camera")
    7 -> localized("移动测速", "Mobile speed camera")
    8 -> localized("测速风险路段", "Speed camera zone")
    20 -> localized("学校区域起点", "School zone start")
    21 -> localized("学校区域终点", "School zone end")
    22 -> localized("减速带", "Speed bump")
    75 -> localized("后向测速", "Rear speed camera")
    76 -> localized("后向红灯／测速", "Rear signal / speed camera")
    100 -> localized("警示事件", "Police alert")
    101 -> localized("测速提醒", "Camera alert")
    else -> localized("未知限速事件 #$type", "Unknown speed event #$type")
}

internal fun decimal(value: Float) = "%.1f".format(value)
internal fun distance(meters: Float) =
    if (meters >= 1000f) "${decimal(meters / 1000f)} km" else "${"%.0f".format(meters)} m"
