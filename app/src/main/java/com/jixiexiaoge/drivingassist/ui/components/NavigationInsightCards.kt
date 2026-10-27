package com.jixiexiaoge.drivingassist.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.jixiexiaoge.drivingassist.data.CarrotDisplaySnapshot
import com.jixiexiaoge.drivingassist.data.NavigationReception
import com.jixiexiaoge.drivingassist.data.ReceivedNavigationLanes
import com.jixiexiaoge.drivingassist.navigation.AmapDisplayState
import com.jixiexiaoge.drivingassist.navigation.AmapNavigationDisplay
import com.jixiexiaoge.drivingassist.navigation.NaviDeliverySnapshot
import com.jixiexiaoge.drivingassist.ui.theme.Accent
import com.jixiexiaoge.drivingassist.ui.theme.PrimaryLight
import com.jixiexiaoge.drivingassist.ui.utils.localized

internal fun behaviorStageLabel(code: Int?): String = when (code) {
    0 -> localized("跟车", "Lead following")
    1 -> localized("巡航", "Cruise")
    2 -> localized("端到端巡航", "End-to-end cruise")
    3 -> localized("端到端停车阶段", "End-to-end stopping")
    4 -> localized("端到端准备阶段", "End-to-end preparing")
    5 -> localized("端到端已停车", "End-to-end stopped")
    else -> localized("当前无有效数据", "No valid current data")
}

internal fun planningTrafficLabel(code: Int?): String = when (code) {
    0 -> localized("未采用红/绿状态（不是无灯）", "No red/green state used (not no signal)")
    1 -> localized("规划状态 red", "Planner state red")
    2 -> localized("规划状态 green", "Planner state green")
    else -> localized("当前无有效数据", "No valid current data")
}

internal fun vehicleStyleLabel(code: Int?): String = when (code) {
    1 -> "Eco"
    2 -> "Safe"
    3 -> "Normal"
    4 -> "High"
    else -> localized("当前无有效数据", "No valid current data")
}

internal fun laneGroupLabel(lanes: ReceivedNavigationLanes?): String =
    lanes?.recommendedIndices?.takeIf { it.isNotEmpty() }?.joinToString(" / ") { (it + 1).toString() }
        ?: localized("当前无推荐数据", "No current recommendations")

internal enum class LaneGroupComparison { Unavailable, Same, Different }

internal fun compareRouteLaneGroups(map: AmapNavigationDisplay, data: CarrotDisplaySnapshot): LaneGroupComparison {
    if (map.state != AmapDisplayState.Fresh || map.junction?.leftToRightKnown != true ||
        map.lanes?.recommendedIndices == null || data.navigationLanes?.recommendedIndices == null ||
        data.navigationAvailability != com.jixiexiaoge.drivingassist.data.TelemetryAvailability.Fresh ||
        data.navigation != NavigationReception.Guiding) return LaneGroupComparison.Unavailable
    return if (map.lanes.count == data.navigationLanes.count &&
        map.lanes.recommendedIndices == data.navigationLanes.recommendedIndices) LaneGroupComparison.Same
    else LaneGroupComparison.Different
}

@Composable
internal fun NavigationComparisonCard(map: AmapNavigationDisplay, data: CarrotDisplaySnapshot,
    delivery: NaviDeliverySnapshot = NaviDeliverySnapshot(), routeReady: Boolean = true) {
    val currentMap = map.takeIf { routeReady && it.state == AmapDisplayState.Fresh }
    TelemetryCard(localized("导航对照 · 高德 / 车端", "Navigation · AMap / vehicle"),
        "navigation-comparison", Accent, Icons.Default.Info, data.navigationAvailability,
        sourceLabel = localized("7000返回", "7000 feedback")) {
        MetricGrid(listOf(
            Metric("compare-map-action", localized("高德当前动作", "AMap maneuver"), currentMap?.current?.label ?: "—"),
            Metric("compare-carrot-action", localized("车端当前动作", "Vehicle maneuver"), data.currentGuidance?.text ?: "—"),
            Metric("compare-map-distance", localized("高德动作距离", "AMap maneuver distance"),
                currentMap?.current?.distanceM?.let { "$it m" } ?: "—"),
            Metric("compare-carrot-distance", localized("车端动作距离", "Vehicle maneuver distance"),
                data.currentGuidance?.distanceM?.let { "$it m" } ?: "—"),
            Metric("compare-map-lanes", localized("高德推荐（左起）", "AMap lanes (from left)"),
                laneGroupLabel(currentMap?.lanes?.takeIf { currentMap.junction?.leftToRightKnown == true })),
            Metric("compare-carrot-lanes", localized("车端推荐（左起）", "Vehicle lanes (from left)"),
                laneGroupLabel(data.navigationLanes)),
            Metric("compare-map-next", localized("高德随后动作", "AMap following maneuver"), currentMap?.next?.label ?: "—"),
            Metric("compare-carrot-next", localized("车端随后动作", "Vehicle following maneuver"), data.nextGuidance?.text ?: "—"),
        ), Accent, maxColumns = 2)
        Info(when (if (currentMap != null) compareRouteLaneGroups(currentMap, data) else LaneGroupComparison.Unavailable) {
            LaneGroupComparison.Unavailable -> localized("推荐组对照 · 数据不足", "Lane-group comparison · insufficient data")
            LaneGroupComparison.Same -> localized("当前推荐组相同（不是执行确认）", "Current recommendation groups match (not execution confirmation)")
            LaneGroupComparison.Different -> localized("推荐组不同 · 请核查来源/时差", "Groups differ · check source/timing")
        })
        Info(navigationLabel(data))
        DetailPanel(PrimaryLight, Modifier.testTag("navigation-delivery")) {
            Info(localized("7714发送 / 7000返回 / 车辆状态分开核对",
                "Check 7714 publishing / 7000 feedback / vehicle state separately"), emphasis = true)
            Info(if (!delivery.ready) localized("7714通道未就绪", "7714 channel not ready")
                else localized("7714已入队 ${delivery.enqueuedStreams.size}/4 项 · 已撤销 ${delivery.withdrawnStreams.size} 项",
                    "7714 enqueued ${delivery.enqueuedStreams.size}/4 streams · withdrawn ${delivery.withdrawnStreams.size}"))
            if (delivery.ready) Info(localized("最近成功入队记录 · 不确认当前任务已被采用",
                "Last successful enqueue records · not adoption of the current task"))
            Info(localized("车端变道阶段 · ", "Vehicle lane-change phase · ") + laneChangePhaseLabel(data))
            Info(localized("采用/拒绝原因 · 当前协议未提供关联确认", "Adoption/rejection reason · protocol has no correlated confirmation"))
        }
        Info(localized("7000返回非7714 ACK，非执行确认", "7000 feedback is not a 7714 ACK or execution confirmation"))
    }
}

internal fun laneChangePhaseLabel(data: CarrotDisplaySnapshot): String {
    if (data.laneAvailability != com.jixiexiaoge.drivingassist.data.TelemetryAvailability.Fresh) {
        return localized("未知 / 数据失效", "Unknown / unavailable")
    }
    return when (data.laneChange?.state) {
        "off" -> localized("未变道（非已完成确认）", "Not changing (not completion confirmation)")
        "preLaneChange" -> localized("准备变道", "Preparing")
        "laneChangeStarting" -> localized("变道中", "Changing")
        "laneChangeFinishing" -> localized("变道收尾", "Finishing")
        else -> localized("未知", "Unknown")
    }
}

@Composable
internal fun CarrotBehaviorCard(data: CarrotDisplaySnapshot) {
    TelemetryCard(localized("Carrot行为与交通信息", "Carrot behavior & traffic"),
        "carrot-behavior", PrimaryLight, Icons.Default.Info, data.planningAvailability,
        sourceLabel = "7000/longitudinalPlan") {
        MetricGrid(listOf(
            Metric("behavior-stage", localized("纵向行为阶段", "Longitudinal behavior"), behaviorStageLabel(data.behavior?.stage)),
            Metric("behavior-style", localized("舒适风格", "Comfort style"), vehicleStyleLabel(data.behavior?.style)),
            Metric("behavior-traffic", localized("规划交通状态", "Planner traffic state"), planningTrafficLabel(data.behavior?.traffic)),
            Metric("behavior-cruise-target", localized("规划巡航目标", "Planner cruise target"),
                data.behavior?.cruiseTargetKph?.let { "${decimal(it)} km/h" } ?: "—"),
            Metric("behavior-jerk", localized("规划jerk", "Plan jerk"),
                data.behavior?.jerk?.let { "${decimal(it)} m/s³" } ?: "—"),
        ), PrimaryLight)
        DetailPanel(Accent, Modifier.testTag("carrot-returned-signals")) {
            Info(localized("7000导航灯组 · 非Tesla/感知灯色",
                "7000 navigation lamps · not Tesla/camera"), emphasis = true)
            Info(localized("灯组接收 · ", "Lamp reception · ") + availabilityLabel(data.navigationAvailability))
            Info(localized("导航信号距离 · ${data.navigationSignal?.distanceM?.let { "$it m" } ?: "—"}",
                "Navigation signal distance · ${data.navigationSignal?.distanceM?.let { "$it m" } ?: "—"}"))
            if (data.navigationSignal?.lamps.isNullOrEmpty()) Info(localized("当前无有效灯组", "No valid lamp group"))
            data.navigationSignal?.lamps?.forEach { lamp ->
                val name = when (lamp.direction) {
                    "red" -> localized("红", "Red")
                    "green" -> localized("绿", "Green")
                    "left" -> localized("左转", "Left")
                    "right" -> localized("右转", "Right")
                    else -> localized("掉头", "U-turn")
                }
                Info("$name · ${if (lamp.on) localized("报告亮", "Reported on") else localized("报告灭", "Reported off")} · " +
                    (lamp.remainingSec?.let { "$it s" } ?: "—"))
            }
        }
        Info(localized("灯秒数为接收值，非通行许可或控制授权",
            "Lamp seconds are received values, not right of way or control authority"))
    }
}

@Composable
internal fun PerceptionQualityCard(data: CarrotDisplaySnapshot) {
    TelemetryCard(localized("车道感知与横向规划", "Lane perception & lateral plan"),
        "perception-quality", Accent, Icons.Default.Info,
        data.modelAvailability, sourceLabel = "7000/modelV2") {
        val quality = data.quality
        val probabilities = quality?.lineProbabilities?.joinToString(" / ") { it?.let { p -> "${(p * 100).toInt()}%" } ?: "—" } ?: "—"
        Info(localized("车道线概率 左外/左/右/右外 · $probabilities", "Line probability outer-L/L/R/outer-R · $probabilities"))
        Info(localized("左右路缘标准差 · ${quality?.edgeStds?.joinToString(" / ") { it?.let { d -> "${decimal(d)} m" } ?: "—" } ?: "—"}",
            "Left/right edge std · ${quality?.edgeStds?.joinToString(" / ") { it?.let { d -> "${decimal(d)} m" } ?: "—" } ?: "—"}"))
        Info(localized("模型帧年龄 · ${quality?.age ?: "—"}；丢帧字段 · ${quality?.dropPercent?.let(::decimal) ?: "—"}",
            "Model frame age · ${quality?.age ?: "—"}; frame-drop field · ${quality?.dropPercent?.let(::decimal) ?: "—"}"))
        Info(localized("模型推理耗时 · ${quality?.executionMs?.let { "${decimal(it)} ms" } ?: "—"} · 非端到端延迟",
            "Model execution · ${quality?.executionMs?.let { "${decimal(it)} ms" } ?: "—"} · not end-to-end latency"))
        Info(localized("重复/旧模型帧不续期；帧超时撤下，非源模型新鲜保证",
            "Repeated/old model frames do not renew freshness; expiry withdraws data, not a source-freshness guarantee"))
        Info(localized("横向规划状态 · ", "Lateral planning state · ") + availabilityLabel(data.lateralAvailability),
            emphasis = true)
        MetricGrid(listOf(
            Metric("actual-curvature", localized("实测曲率", "Measured curvature"), data.lateral?.curvature?.let { "$it 1/m" } ?: "—"),
            Metric("desired-curvature", localized("期望曲率", "Desired curvature"), data.lateral?.desiredCurvature?.let { "$it 1/m" } ?: "—"),
            Metric("actual-lat-accel", localized("实测横向加速", "Measured lateral accel"), data.lateral?.acceleration?.let { "${decimal(it)} m/s²" } ?: "—"),
            Metric("desired-lat-accel", localized("期望横向加速", "Desired lateral accel"), data.lateral?.desiredAcceleration?.let { "${decimal(it)} m/s²" } ?: "—"),
            Metric("lateral-output", localized("横向输出", "Lateral output"), data.lateral?.output?.let(::decimal) ?: "—"),
        ), Accent)
        Info(localized("模型指标非安全概率 · 不授予控制权限", "Model metrics are not safety probabilities or control authority"))
    }
}
