package com.jixiexiaoge.drivingassist.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.jixiexiaoge.drivingassist.CarrotManFields
import com.jixiexiaoge.drivingassist.data.TelemetryAvailability
import com.jixiexiaoge.drivingassist.navigation.AmapDisplayAction
import com.jixiexiaoge.drivingassist.navigation.AmapDisplayState
import com.jixiexiaoge.drivingassist.navigation.AmapNavigationDisplay
import com.jixiexiaoge.drivingassist.navigation.AmapRoadNoticeKind
import com.jixiexiaoge.drivingassist.navigation.AmapTrafficSegments
import com.jixiexiaoge.drivingassist.navigation.NaviSourceTracker
import com.jixiexiaoge.drivingassist.navigation.buildAmapNavigationDisplay
import com.jixiexiaoge.drivingassist.navigation.amapHeadingLabel
import com.jixiexiaoge.drivingassist.navigation.AmapSectionPhase
import com.jixiexiaoge.drivingassist.ui.theme.Accent
import com.jixiexiaoge.drivingassist.ui.theme.PrimaryLight
import com.jixiexiaoge.drivingassist.ui.theme.Surface900
import com.jixiexiaoge.drivingassist.ui.theme.TextSecondary
import com.jixiexiaoge.drivingassist.ui.theme.TextTertiary
import com.jixiexiaoge.drivingassist.ui.utils.localized
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

@Composable
internal fun rememberAmapNavigationDisplay(
    fields: CarrotManFields,
    sources: NaviSourceTracker,
    activeMode: String,
    onSample: (AmapNavigationDisplay) -> Unit = {},
    enabled: Boolean = true,
): AmapNavigationDisplay {
    val currentFields by rememberUpdatedState(fields)
    val currentMode by rememberUpdatedState(activeMode)
    val currentOnSample by rememberUpdatedState(onSample)
    val owner = LocalLifecycleOwner.current
    var display by remember(sources, owner) {
        mutableStateOf(buildAmapNavigationDisplay(fields, sources.snapshot(), activeMode, System.currentTimeMillis()))
    }
    LaunchedEffect(sources, owner, enabled) {
        if (!enabled) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val now = System.currentTimeMillis()
                display = buildAmapNavigationDisplay(
                    currentFields, sources.sampleReadOnly(now, currentMode == "AMAP"), currentMode, now,
                )
                currentOnSample(display)
                delay(500)
            }
        }
    }
    return display
}

@Composable
internal fun AmapNavigationOverview(
    fields: CarrotManFields,
    sources: NaviSourceTracker,
    activeMode: String,
    modeContent: @Composable (AmapNavigationDisplay) -> Unit = {},
    onSample: (AmapNavigationDisplay) -> Unit = {},
    routeTasksReady: Boolean = true,
    displayOverride: AmapNavigationDisplay? = null,
    showDetails: Boolean = true,
    header: @Composable (AmapNavigationDisplay) -> Unit,
) {
    val sampledDisplay = rememberAmapNavigationDisplay(
        fields, sources, activeMode, onSample, enabled = displayOverride == null,
    )
    val display = displayOverride ?: sampledDisplay
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        header(display)
        modeContent(display)
        if (showDetails) {
            AmapCruiseRoadCard(display)
            AmapNavigationCard(display, routeTasksReady)
        }
    }
}

@Composable
internal fun AmapCruiseRoadCard(data: AmapNavigationDisplay) {
    val road = data.cruiseRoad ?: return
    TelemetryCard(localized("高德巡航道路", "AMap cruise road"), "amap-cruise-road", PrimaryLight, Icons.Default.Place,
        TelemetryAvailability.Fresh, sourceLabel = localized("高德巡航广播", "AMap cruise broadcast")) {
        Info(localized("当前道路 · ${road.roadName ?: "—"}", "Current road · ${road.roadName ?: "—"}"), emphasis = true)
        MetricGrid(listOf(
            Metric("amap-cruise-speed", localized("高德巡航速度", "AMap cruise speed"),
                road.speedKph?.let { "${"%.1f".format(it)} km/h" } ?: "—"),
            Metric("amap-cruise-heading", localized("高德巡航航向", "AMap cruise bearing"),
                amapHeadingLabel(road.headingDeg) ?: "—"),
            Metric("amap-cruise-camera-type", localized("测速类型原始码", "Camera raw type"),
                road.cameraType?.toString() ?: "—"),
            Metric("amap-cruise-camera-distance", localized("测速距离（数值）", "Camera distance (numeric)"),
                road.cameraDistanceM?.let { "$it m" } ?: "—"),
            Metric("amap-cruise-camera-limit", localized("测速点限速", "Camera limit"),
                road.cameraLimitKph?.let { "$it km/h" } ?: "—"),
        ), PrimaryLight)
        Info(localized("原始巡航提示 · 非导航任务、车辆姿态或控制依据", "Raw cruise advisory · not a route task, vehicle posture or control input"))
    }
}

@Composable
internal fun AmapNavigationCard(data: AmapNavigationDisplay, routeTasksReady: Boolean = true) {
    if (data.state != AmapDisplayState.Fresh) return
    val empty = localized("当前无数据", "No current data")
    TelemetryCard(localized("高德导航态势", "AMap navigation"), "amap-overview", Accent, Icons.Default.Place,
        TelemetryAvailability.Fresh, sourceLabel = localized("高德", "AMap")) {
        DetailPanel(PrimaryLight, Modifier.testTag("amap-road")) {
            Text(data.roadName ?: localized("当前道路 · $empty", "Current road · $empty"),
                color = TextSecondary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Info(localized("道路类型 · ${data.roadScope ?: empty}", "Road category · ${data.roadScope ?: empty}"))
        }
        DetailPanel(Accent, Modifier.testTag("amap-limits")) {
            MetricGrid(listOf(
                Metric("amap-road-limit", localized("当前道路限速", "Current road limit"),
                    data.roadLimitKph?.let { "$it km/h" } ?: "—"),
                Metric("amap-map-speed", localized("高德定位速度", "AMap position speed"),
                    data.mapSpeedKph?.let { "${"%.1f".format(it)} km/h" } ?: "—"),
                Metric("amap-map-heading", localized("高德定位航向", "AMap position bearing"),
                    amapHeadingLabel(data.mapHeadingDeg) ?: "—"),
                Metric("amap-upcoming-limit", localized("前方限速提醒", "Upcoming speed event"),
                    data.upcomingLimit?.limitKph?.let { "$it km/h" } ?: "—"),
            ), Accent)
            Info(localized("距提醒点 · ${data.upcomingLimit?.distanceM?.let { distance(it.toFloat()) } ?: empty}",
                "Distance to event · ${data.upcomingLimit?.distanceM?.let { distance(it.toFloat()) } ?: empty}"),
                emphasis = data.upcomingLimit != null)
            Info(localized("提醒类型 · ${data.upcomingLimit?.let { speedEventLabel(it.type) } ?: empty}",
                "Event type · ${data.upcomingLimit?.let { speedEventLabel(it.type) } ?: empty}"))
            Info(localized("测速提醒 · 非下一路段限速", "Speed event · not next road limit"))
            val roadWarning = exceedsDisplayedLimit(data.mapSpeedKph, data.roadLimitKph)
            Text(when (roadWarning) {
                true -> localized("高德速度高于当前道路限速 · 仅提醒", "AMap speed exceeds the current road limit · advisory")
                false -> localized("高德速度未高于该限速 · 非安全判断", "AMap speed is not above this limit · not a safety assessment")
                null -> localized("超速对照 · 速度或道路限速未知", "Speed comparison · speed or road limit unknown")
            }, color = if (roadWarning == true) Color(0xFFFB7185) else TextSecondary,
                fontSize = 12.sp, modifier = Modifier.testTag("amap-road-speed-warning"))
        }
        if (routeTasksReady) {
            AmapSegmentPair(data.current, data.next)
            // 路口车道引导已移到驾驶卡顶部中间（AmapLaneCard），此处不再渲染卡片
            DetailPanel(Accent, Modifier.testTag("amap-exit")) {
                Info(localized("出口引导 · ${data.exit ?: empty}", "Exit guidance · ${data.exit ?: empty}"), emphasis = true)
                Info(localized("航段提示 · ${data.assistantAction ?: empty}", "Segment guidance · ${data.assistantAction ?: empty}"))
            }
        } else {
            Info(localized("路线任务暂缓 · 旧动作与推荐已撤下", "Route task on hold · old maneuvers and lanes withdrawn"), emphasis = true)
        }
        DetailPanel(Accent, Modifier.testTag("amap-journey")) {
            Info(localized("高德ETA · ${data.guideDetails?.rawEta ?: empty}",
                "AMap raw ETA · ${data.guideDetails?.rawEta ?: empty}"))
            MetricGrid(listOf(
                Metric("amap-total-lights", localized("路线交通灯总数", "Route traffic lights"),
                    data.guideDetails?.totalTrafficLights?.toString() ?: "—"),
                Metric("amap-remaining-lights", localized("剩余交通灯数", "Remaining traffic lights"),
                    data.guideDetails?.remainingTrafficLights?.toString() ?: "—"),
            ), Accent)
            Info(localized("目的地 · ${data.destination ?: empty}", "Destination · ${data.destination ?: empty}"), emphasis = true)
            MetricGrid(listOf(
                Metric("amap-remaining-distance", localized("剩余距离", "Remaining distance"),
                    data.remainingM?.let { distance(it.toFloat()) } ?: "—"),
                Metric("amap-remaining-time", localized("预计剩余", "Time remaining"),
                    data.remainingSec?.let { localized("${ceil(it / 60.0).toInt()} 分钟", "${ceil(it / 60.0).toInt()} min") } ?: "—"),
                Metric("amap-arrival", localized("预计到达（本机估算）", "Arrival (local estimate)"),
                    data.arrivalAtMs?.let { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it)) } ?: "—"),
                Metric("amap-total-distance", localized("全程距离", "Route distance"),
                    data.totalDistanceM?.let { distance(it.toFloat()) } ?: "—"),
                Metric("amap-traveled-distance", localized("已行驶（估算）", "Traveled (estimated)"),
                    data.traveledDistanceM?.let { distance(it.toFloat()) } ?: "—"),
                Metric("amap-total-time", localized("全程预估时间", "Total estimated time"),
                    data.totalTimeSec?.let(::travelTime) ?: "—"),
            ), Accent)
            data.progress?.let { Progress(it, localized("高德行程进度", "AMap route progress"), Accent, "amap-route-progress") }
                ?: Info(localized("高德行程进度 · $empty", "AMap route progress · $empty"))
        }
        DetailPanel(PrimaryLight, Modifier.testTag("amap-speed-events")) {
            Info(localized("高德测速提醒", "AMap speed events"), emphasis = true)
            if (data.events.isEmpty()) {
                Info(if (data.speedEventsFresh) localized("当前广播未提供有效测速提醒", "Current broadcast has no valid speed event") else empty)
            }
            data.events.forEach {
                Info(localized("高德提醒 · ${speedEventLabel(it.type)}", "AMap event · ${speedEventLabel(it.type)}"), emphasis = true)
                Info(localized("前方 ${distance(it.distanceM.toFloat())} · 事件限速 ${it.limitKph?.let { speed -> "$speed km/h" } ?: "—"}",
                    "${distance(it.distanceM.toFloat())} ahead · event limit ${it.limitKph?.let { speed -> "$speed km/h" } ?: "—"}"))
            }
        }
        DetailPanel(Accent, Modifier.testTag("amap-section")) {
            Info(localized("高德区间测速", "AMap section control"), emphasis = true)
            val detail = data.sectionDetails
            val phase = when (detail?.phase) {
                AmapSectionPhase.Approaching -> localized("接近起点", "Approaching")
                AmapSectionPhase.Inside -> localized("区间内", "Inside")
                AmapSectionPhase.Ended -> localized("已结束", "Ended")
                null -> localized("未知", "Unknown")
            }
            Info(localized("阶段 · $phase", "Phase · $phase"))
            if (detail == null) {
                Info(if (data.sectionFresh) localized("当前广播未提供进行中的区间测速", "Current broadcast has no active section control") else empty)
            } else {
                MetricGrid(listOf(
                    Metric("amap-section-limit", localized("区间限速", "Section limit"), detail.limitKph?.let { speed -> "$speed km/h" } ?: "—"),
                    Metric("amap-section-average", localized("区间均速", "Section average"), detail.averageKph?.let { average -> "${"%.1f".format(average)} km/h" } ?: "—"),
                    Metric("amap-section-remaining", localized("剩余（数值）", "Remaining (numeric)"),
                        detail.remainingM?.toFloat()?.let(::distance) ?: "—"),
                ), Accent)
                val averageWarning = exceedsDisplayedLimit(
                    detail.averageKph, detail.limitKph)
                Text(when (averageWarning) {
                    true -> localized("区间均速高于区间限速 · 仅提醒", "Section average exceeds its limit · advisory")
                    false -> localized("区间均速未高于该限速 · 非安全判断", "Section average is not above this limit · not a safety assessment")
                    null -> localized("区间超速对照 · 数据未知", "Section speed comparison · unknown data")
                }, color = if (averageWarning == true) Color(0xFFFB7185) else TextSecondary,
                    fontSize = 12.sp, modifier = Modifier.testTag("amap-section-speed-warning"))
                Info(localized("起点距离（原文） · ${detail?.startDistanceText ?: "—"}", "Start distance (raw) · ${detail?.startDistanceText ?: "—"}"))
                Info(localized("终点距离（原文） · ${detail?.endDistanceText ?: "—"}", "End distance (raw) · ${detail?.endDistanceText ?: "—"}"))
                detail.progress?.takeIf { detail.phase == AmapSectionPhase.Inside }?.let { progress -> Progress(progress.toFloat(), localized("区间进度", "Section progress"), Accent, "amap-section-progress") }
                    ?: Info(localized("区间进度 · 当前无数据", "Section progress · no current data"))
            }
        }
        TrafficSegmentPanel(data.trafficSegments, empty)
        AmapRoadNoticeKind.entries.forEach { kind ->
            val notice = data.roadNotices.firstOrNull { it.kind == kind }
            DetailPanel(PrimaryLight, Modifier.testTag("amap-notice-${kind.name}")) {
                Info(if (kind == AmapRoadNoticeKind.Traffic) localized("高德路况提示", "AMap traffic notice")
                    else localized("高德道路态势", "AMap road situation"), emphasis = true)
                Info(notice?.text ?: empty)
                notice?.distanceM?.let { Info(localized("前方 ${distance(it.toFloat())}", "${distance(it.toFloat())} ahead")) }
            }
        }
        DetailPanel(PrimaryLight, Modifier.testTag("amap-service")) {
            Info(localized("当前设施 · ${data.guideDetails?.serviceName ?: empty}",
                "Current facility · ${data.guideDetails?.serviceName ?: empty}"))
            Info(localized("距离（原文） · ${data.guideDetails?.serviceDistanceText ?: empty}",
                "Distance (raw) · ${data.guideDetails?.serviceDistanceText ?: empty}"))
            Info(localized("距离（数值） · ${data.guideDetails?.serviceDistanceM?.let { "$it m" } ?: "—"}",
                "Distance (numeric) · ${data.guideDetails?.serviceDistanceM?.let { "$it m" } ?: "—"}"))
            Info(localized("随后设施 · ${data.guideDetails?.nextServiceName ?: empty}",
                "Next facility · ${data.guideDetails?.nextServiceName ?: empty}"))
            Info(localized("随后距离（原文） · ${data.guideDetails?.nextServiceDistanceText ?: empty}",
                "Next distance (raw) · ${data.guideDetails?.nextServiceDistanceText ?: empty}"))
            Info(localized("随后距离（数值） · ${data.guideDetails?.nextServiceDistanceM?.let { "$it m" } ?: "—"}",
                "Next distance (numeric) · ${data.guideDetails?.nextServiceDistanceM?.let { "$it m" } ?: "—"}"))
            Info(localized("设施类型原始码 · ${data.guideDetails?.serviceType ?: "—"} / ${data.guideDetails?.nextServiceType ?: "—"}",
                "Facility raw type codes · ${data.guideDetails?.serviceType ?: "—"} / ${data.guideDetails?.nextServiceType ?: "—"}"))
        }
        data.ageMs?.let { Info(localized("高德引导 ${"%.1f".format(it / 1000f)}秒前更新", "AMap guidance updated ${"%.1f".format(it / 1000f)}s ago")) }
            ?: Info(localized("高德引导更新时间 · $empty", "AMap guidance update age · $empty"))
        Info(localized("地图提示 · 非执行确认", "Map advisory · not execution confirmation"))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AmapCruiseLamps(data: AmapNavigationDisplay) {
    if (data.cruiseLamps.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.testTag("amap-cruise-lamps")) {
        Text(localized("高德巡航灯组 · 非路线控制灯", "AMap cruise lamps · not route control signals"),
            color = TextSecondary, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            data.cruiseLamps.forEachIndexed { index, lamp ->
                val color = when (lamp.status) {
                    0 -> Color(0xFFFB7185)
                    1 -> Color(0xFF4ADE80)
                    else -> TextTertiary
                }
                val direction = cruiseLampDirectionLabel(lamp.direction)
                val status = when (lamp.status) {
                    0 -> localized("红灯", "Red")
                    1 -> localized("绿灯", "Green")
                    else -> localized("灯色未知(${lamp.status})", "Unknown (${lamp.status})")
                }
                Surface(color = Surface900, shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("amap-cruise-lamp-$index").border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(10.dp))) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("$direction · $status", color = color, fontSize = 13.sp)
                        Text(localized("广播倒计时 ${lamp.remainingSec} 秒", "Reported countdown ${lamp.remainingSec} s"),
                            color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun trafficColor(status: Int): Color = when (status) {
    1 -> Color(0xFF4ADE80)
    2 -> Color(0xFFFBBF24)
    3 -> Color(0xFFFB7185)
    4 -> Color(0xFFBE123C)
    10 -> TextTertiary
    else -> PrimaryLight
}

private fun trafficLabel(status: Int): String = when (status) {
    1 -> localized("畅通", "Clear")
    2 -> localized("缓行", "Slow")
    3 -> localized("拥堵", "Congested")
    4 -> localized("严重拥堵", "Heavy congestion")
    10 -> localized("已驶过", "Passed")
    else -> localized("未知路况($status)", "Unknown traffic ($status)")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrafficSegmentPanel(data: AmapTrafficSegments?, empty: String) {
    DetailPanel(PrimaryLight, Modifier.testTag("amap-traffic-segments")) {
        Info(localized("沿途分段路况", "Route traffic segments"), emphasis = true)
        if (data == null) {
            Info(empty)
            return@DetailPanel
        }
        Canvas(Modifier.fillMaxWidth().height(18.dp).testTag("amap-traffic-bar")) {
            var left = 0f
            data.segments.forEachIndexed { index, segment ->
                val right = if (index == data.segments.lastIndex) size.width
                    else left + size.width * (segment.distanceM.toFloat() / data.totalM)
                drawRect(trafficColor(segment.status), Offset(left, 5.dp.toPx()), Size(right - left, 8.dp.toPx()))
                left = right
            }
            data.finishedM?.let {
                val x = (size.width * (it.toFloat() / data.totalM)).coerceIn(1.dp.toPx(), size.width - 1.dp.toPx())
                drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            data.segments.groupBy { it.status }.forEach { (status, segments) ->
                Text("${trafficLabel(status)} · ${distance(segments.sumOf { it.distanceM }.toFloat())}",
                    color = trafficColor(status), fontSize = 12.sp)
            }
        }
        Info(localized("左至右为路线顺序；白线仅表示高德已行驶进度", "Route order from left to right; white marker is AMap reported progress"))
        if (data.finishedM == null) Info(localized("已行驶进度未知", "Traveled progress unknown"))
    }
}

internal fun segmentPairColumns(widthDp: Float, fontScale: Float): Int =
    minOf(2, telemetryMetricColumns(widthDp, fontScale))

@Composable
internal fun AmapSegmentPair(current: AmapDisplayAction?, next: AmapDisplayAction?) {
    val empty = localized("当前无数据", "No current data")
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("amap-segment-pair")) {
        val columns = segmentPairColumns(maxWidth.value, LocalDensity.current.fontScale)
        val currentPanel: @Composable (Modifier) -> Unit = { modifier ->
            ActionPanel(localized("当前航段 · 即将到达", "Current segment · upcoming maneuver"),
                current, empty, "amap-current-action",
                localized("当前航段预计剩余", "Current segment remaining time"), modifier)
        }
        val nextPanel: @Composable (Modifier) -> Unit = { modifier ->
            ActionPanel(localized("随后航段 · 随后动作", "Next segment · following maneuver"),
                next, empty, "amap-next-action",
                localized("随后航段预计用时", "Next segment estimated duration"), modifier)
        }
        if (columns == 2) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                currentPanel(Modifier.weight(1f))
                nextPanel(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                currentPanel(Modifier.fillMaxWidth())
                nextPanel(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ActionPanel(label: String, action: AmapDisplayAction?, empty: String, tag: String, timeLabel: String,
    modifier: Modifier = Modifier) {
    DetailPanel(PrimaryLight, modifier.testTag(tag)) {
        AmapManeuverIcon(action?.maneuverIcon, PrimaryLight, Modifier.size(48.dp).testTag("$tag-graphic"))
        if (action == null) {
            Info(label, emphasis = true)
            Info(empty)
        }
        if (action != null) {
            Info("$label · ${distance(action.distanceM.toFloat())}", emphasis = true)
            Text(action.label, color = PrimaryLight, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            action.roadName?.let { Info(it) }
        }
        Column(Modifier.testTag("$tag-time")) {
            Info("$timeLabel · ${action?.remainingSec?.let(::travelTime) ?: empty}")
        }
    }
}
