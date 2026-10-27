package com.jixiexiaoge.drivingassist.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.jixiexiaoge.drivingassist.data.CarrotDisplaySnapshot
import com.jixiexiaoge.drivingassist.data.NavigationReception
import com.jixiexiaoge.drivingassist.data.TelemetryAvailability
import com.jixiexiaoge.drivingassist.data.LaneGeometry
import com.jixiexiaoge.drivingassist.data.LaneGeometryStatus
import com.jixiexiaoge.drivingassist.navigation.AmapDisplayState
import com.jixiexiaoge.drivingassist.navigation.AmapNavigationDisplay
import com.jixiexiaoge.drivingassist.navigation.DrivingLaneAdvice
import com.jixiexiaoge.drivingassist.navigation.DrivingAdviceHold
import com.jixiexiaoge.drivingassist.navigation.DrivingLanePriority
import com.jixiexiaoge.drivingassist.navigation.DrivingSceneEvidence
import com.jixiexiaoge.drivingassist.navigation.RouteTaskState
import com.jixiexiaoge.drivingassist.navigation.DrivingMode
import com.jixiexiaoge.drivingassist.navigation.DrivingRoadMatch
import com.jixiexiaoge.drivingassist.navigation.buildDrivingModeAdvice
import com.jixiexiaoge.drivingassist.ui.theme.Accent
import com.jixiexiaoge.drivingassist.ui.theme.PrimaryLight
import com.jixiexiaoge.drivingassist.ui.theme.AccentPurple
import com.jixiexiaoge.drivingassist.ui.theme.AccentOrange
import com.jixiexiaoge.drivingassist.ui.utils.localized

internal fun drivingModeLabel(mode: DrivingMode): String = when (mode) {
    DrivingMode.HIGHWAY_EAP -> localized("高速 EAP", "Highway EAP")
    DrivingMode.ELEVATED_NOA -> localized("高架 NOA", "Elevated NOA")
    DrivingMode.REGULAR_NOO -> localized("常规 NOO", "Regular NOO")
}

private fun drivingModeColor(mode: DrivingMode) = when (mode) {
    DrivingMode.HIGHWAY_EAP -> PrimaryLight
    DrivingMode.ELEVATED_NOA -> AccentPurple
    DrivingMode.REGULAR_NOO -> AccentOrange
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
// 已从导航页移除（7.9.37）：模式改为实时页驾驶卡右上角的 ModePill 角标（7.9.39 起不再有指南页）。
// 本卡片（含模式切换按钮）保留供仪器测试与将来复用，生产链路不再调用。
internal fun DrivingModeCard(
    mode: DrivingMode,
    map: AmapNavigationDisplay,
    vehicle: CarrotDisplaySnapshot,
    onSelect: (DrivingMode) -> Unit,
    automatic: Boolean = false,
    sceneReady: Boolean = true,
    onAutomatic: () -> Unit = {},
    sceneEvidence: DrivingSceneEvidence? = null,
    routeTask: RouteTaskState? = null,
) {
    val offRoute = vehicle.navigation == NavigationReception.OffRoute
    val takeover = vehicle.alert?.status in setOf("critical", "userPrompt")
    val advice = buildDrivingModeAdvice(mode, map, offRoute, takeover, sceneReady, routeTask?.ready ?: true)
    val accent = if (sceneReady) drivingModeColor(mode) else Accent
    val availability = when (map.state) {
        AmapDisplayState.Fresh -> TelemetryAvailability.Fresh
        AmapDisplayState.Expired -> TelemetryAvailability.Expired
        else -> TelemetryAvailability.Missing
    }
    TelemetryCard(localized("驾驶模式 · 路线提示", "Driving mode · route advisory"),
        "driving-mode", accent, Icons.Default.Place, availability,
        sourceLabel = localized("高德", "AMap")) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(selected = automatic, onClick = onAutomatic,
                label = { Text(localized("自动识别", "Automatic")) },
                modifier = Modifier.testTag("mode-automatic"))
            DrivingMode.entries.forEach { option ->
                FilterChip(
                    selected = sceneReady && mode == option,
                    onClick = { onSelect(option) },
                    label = { Text(drivingModeLabel(option)) },
                    modifier = Modifier.testTag("mode-${option.storageKey}"),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = drivingModeColor(option).copy(alpha = 0.18f),
                        selectedLabelColor = drivingModeColor(option),
                    ),
                )
            }
        }
        Info(if (automatic) {
            if (sceneReady) localized("自动 · ${drivingModeLabel(mode)}", "Automatic · ${drivingModeLabel(mode)}")
            else localized("自动 · 等待稳定广播", "Automatic · awaiting stable broadcasts")
        } else localized("手动场景 · 请停车后选择", "Manual scene · select while parked"))
        if (automatic && !sceneReady && sceneEvidence != null) {
            Info(localized(
                "${drivingModeLabel(sceneEvidence.candidate)} · ${sceneEvidence.samples}/3 条 · ${decimal(sceneEvidence.spanMs / 1000f)}/2 秒",
                "${drivingModeLabel(sceneEvidence.candidate)} · ${sceneEvidence.samples}/3 packets · ${decimal(sceneEvidence.spanMs / 1000f)}/2 seconds",
            ))
        }
        DetailPanel(accent, Modifier.testTag("mode-road-match")) {
            Info(if (!sceneReady) localized("场景待识别 · 暂停候选", "Scene pending · candidate withheld")
            else when (advice.roadMatch) {
                DrivingRoadMatch.Waiting -> localized("等待高德导航 · 暂停候选", "Waiting for AMap · candidate withheld")
                DrivingRoadMatch.Unknown -> localized("道路类别未知", "Road category unknown")
                DrivingRoadMatch.Mismatch -> localized("模式与道路不匹配 · 暂停候选", "Road mismatch · candidate withheld")
                DrivingRoadMatch.ExpresswayUnconfirmed -> localized("快速路 · 高架/路肩待确认", "Expressway · elevation/shoulder unconfirmed")
                DrivingRoadMatch.Matching -> localized("道路类别匹配", "Road category matches")
            }, emphasis = true)
            if (sceneReady) Info(when (mode) {
                DrivingMode.HIGHWAY_EAP, DrivingMode.ELEVATED_NOA -> localized("偏好左起第二条 · 导航推荐优先",
                    "Prefer second from left · route lanes first")
                DrivingMode.REGULAR_NOO -> localized("偏好合法右侧车道 · 转向推荐优先",
                    "Prefer legal right lanes · turn guidance first")
            })
        }
        DetailPanel(accent, Modifier.testTag("mode-lane-advice")) {
            MetricGrid(listOf(
                Metric("mode-route-lanes", localized("地图推荐（左起）", "Route lanes (from left)"),
                    advice.routeLanes.takeIf { it.isNotEmpty() }?.joinToString(" / ") ?: "—"),
                Metric("mode-candidate", localized("组内偏好 · 非变道目标", "Preference · not change target"),
                    advice.candidateLane?.let { localized("第 $it 条", "Lane $it") } ?: "—"),
            ), accent)
            Info(when (advice.hold) {
                DrivingAdviceHold.ScenePending -> localized("道路场景尚未稳定；暂停候选", "Road scene not stable; candidate withheld")
                DrivingAdviceHold.VehicleAlert -> localized("车端告警 · 暂停候选", "Vehicle alert · candidate withheld")
                DrivingAdviceHold.OffRoute -> localized("车端偏航 · 等待重规划，不追赶旧路线", "Off-route · awaiting reroute, do not recover the old route")
                DrivingAdviceHold.MapUnavailable -> localized("高德未导航或数据失效", "AMap not guiding or data unavailable")
                DrivingAdviceHold.RoadUnknown -> localized("道路类别未知 · 暂停候选", "Road category unknown · candidate withheld")
                DrivingAdviceHold.RoadMismatch -> localized("场景与道路不匹配 · 暂停候选", "Scene mismatch · candidate withheld")
                DrivingAdviceHold.RouteRecovering -> localized("路线任务待更新 · 不采用旧候选", "Route task pending · old candidates withheld")
                null -> when (advice.laneAdvice) {
                    DrivingLaneAdvice.OrderUnknown -> localized("左右顺序未确认", "Lane order unconfirmed")
                    DrivingLaneAdvice.DirectionUnknown -> localized("车道方向未知 · 暂停候选", "Unknown direction · candidate withheld")
                    DrivingLaneAdvice.NoRecommendation -> localized("未提供推荐车道", "No route lanes supplied")
                    DrivingLaneAdvice.RoutePreferred -> if (advice.priority == DrivingLanePriority.RouteConstraint) {
                        localized("导航推荐优先 · 本次不采用默认车道偏好", "Route lanes override the default preference")
                    } else localized("推荐组内排序 · 出口/转向优先", "Rank within route group · exit/turn first")
                    DrivingLaneAdvice.Unavailable -> localized("当前无有效车道数据", "No current lane data")
                }
            }, emphasis = advice.hold != null || advice.priority == DrivingLanePriority.RouteConstraint)
            Info(localized("本车位置/通行合法性未知 · 不据此变道", "Ego lane/legality unknown · no maneuver authorization"))
        }
        if (routeTask == null && map.state == AmapDisplayState.Fresh && !offRoute && !takeover) {
            DetailPanel(accent, Modifier.testTag("mode-route-task")) {
                Info(localized("路线任务 · 非车辆动作", "Route task · not a vehicle action"), emphasis = true)
                val current = map.current
                Info(current?.let {
                    localized("当前 ${it.label} · ${distance(it.distanceM.toFloat())}",
                        "Current ${it.label} · ${distance(it.distanceM.toFloat())}")
                } ?: localized("当前动作 · 未提供", "Current maneuver · unavailable"))
                map.next?.let {
                    Info(localized("随后 ${it.label} · 随后航段 ${distance(it.distanceM.toFloat())}",
                        "Next ${it.label} · next segment ${distance(it.distanceM.toFloat())}"))
                }
                map.exit?.let { Info(localized("出口 / 航段 · $it", "Exit / segment · $it")) }
                map.assistantAction?.let { Info(it) }
                if (mode == DrivingMode.REGULAR_NOO && sceneReady) {
                    Info(localized("路口按导航选道 · 灯色不代表通行许可", "Use route lanes at junctions · signal color is not right of way"))
                }
            }
        }
    }
}

@Composable
internal fun DrivingModeVehicleCard(mode: DrivingMode?, data: CarrotDisplaySnapshot,
    geometry: LaneGeometry = LaneGeometry(LaneGeometryStatus.Missing),
    geometryOnly: Boolean = false) {
    val label = mode?.let(::drivingModeLabel) ?: localized("场景待识别", "Scene pending")
    val accent = mode?.let(::drivingModeColor) ?: Accent
    TelemetryCard(if (geometryOnly) localized("模型几何 · 实验", "Model geometry · experimental")
        else localized("$label · 状态与几何", "$label · state & geometry"),
        "driving-mode-vehicle", accent, Icons.Default.Info,
        if (geometryOnly) data.modelAvailability else data.vehicleAvailability,
        sourceLabel = if (geometryOnly) "7000/modelV2 + lateralPlan" else "7000/carState") {
        if (!geometryOnly) {
            data.alert?.let { AlertBanner(it) }
            Info(localized("控制状态 · ", "Control state · ") + availabilityLabel(data.actuationAvailability))
            MetricGrid(listOf(
                Metric("mode-lateral", localized("横向状态 · 非授权", "Lateral state · not authorization"),
                    controlStateLabel(data.latActive)),
                Metric("mode-longitudinal", localized("纵向状态 · 非授权", "Longitudinal state · not authorization"),
                    controlStateLabel(data.longActive)),
                Metric("mode-ego-lane", localized("本车所在通行车道", "Ego travel lane"), localized("当前无数据", "No current data")),
                Metric("mode-authority", localized("App 模式执行", "App mode execution"),
                    localized("只读提示 · 不自动控制", "Advisory only · no automatic control")),
            ), accent)
        }
        DetailPanel(Accent, Modifier.testTag("lane-geometry")) {
            Info(localized("7000几何 · 实验估计", "7000 geometry · experimental"),
                emphasis = true)
            MetricGrid(listOf(
                Metric("lane-width", localized("本车道宽度 · 前方5m", "Ego lane width · 5m ahead"),
                    geometry.widthM?.let { "${decimal(it)} m" } ?: "—"),
                Metric("lane-left-edge", localized("左边界至左路缘", "Left boundary to left edge"),
                    geometry.leftSpaceM?.let { "${decimal(it)} m" } ?: "—"),
                Metric("lane-right-edge", localized("右边界至右路缘", "Right boundary to right edge"),
                    geometry.rightSpaceM?.let { "${decimal(it)} m" } ?: "—"),
                Metric("lane-geometric-index", localized("左起宽度带 · 非车道号", "Width band · not lane index"),
                    geometry.provisionalIndex?.let { localized("约第 $it 带", "Approximately band $it") } ?: "—"),
            ), Accent)
            Info(when (geometry.status) {
                LaneGeometryStatus.Missing -> localized("当前无模型数据", "No current model data")
                LaneGeometryStatus.Expired -> localized("模型数据已过期", "Model data expired")
                LaneGeometryStatus.Invalid -> localized("几何/置信度/稳定车道状态不足，无法估计", "Geometry, confidence or steady-lane state insufficient")
                LaneGeometryStatus.Maneuvering -> localized("车端正在准备/执行变道，撤下估计", "Vehicle preparing/changing lanes; estimate withdrawn")
                LaneGeometryStatus.WidthOnly -> localized(
                    "仅本车道宽度可用；路缘几何/稳定车道状态不足，不给宽度带",
                    "Ego lane width only; edge geometry or steady-lane state insufficient for width bands",
                )
                LaneGeometryStatus.Stabilizing -> localized("近远几何或连续帧尚未确认，仅展示有效测量", "Near/far geometry or consecutive frames not confirmed; measurements only")
                LaneGeometryStatus.Consistent -> localized("5m/15m及连续帧几何一致；不是安全概率或定位确认", "5m/15m and consecutive frames agree; not safety probability or confirmed localization")
            })
            Info(localized("宽度带非通行车道定位", "Width bands are not travel-lane localization"))
        }
        if (!geometryOnly) {
            Info(navigationLabel(data))
            Info(localized("仅展示 · 控制授权与变道间隙未确认", "Display only · authority and clearance unconfirmed"))
        }
    }
}

private fun controlStateLabel(value: Boolean?): String = when (value) {
    true -> localized("车端报告激活", "Vehicle reports active")
    false -> localized("车端报告未激活", "Vehicle reports inactive")
    null -> localized("当前无数据", "No current data")
}
