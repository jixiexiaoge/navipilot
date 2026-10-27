package com.jixiexiaoge.drivingassist.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.jixiexiaoge.drivingassist.data.TelemetryAvailability
import com.jixiexiaoge.drivingassist.navigation.RouteTaskPhase
import com.jixiexiaoge.drivingassist.navigation.RouteTaskState
import com.jixiexiaoge.drivingassist.ui.theme.Accent
import com.jixiexiaoge.drivingassist.ui.theme.Warning
import com.jixiexiaoge.drivingassist.ui.utils.localized

@Composable
// 已从导航页移除（7.9.37）：路线任务数据仍由 RouteTaskTracker 提供，
// 参与导航对照卡的 ready 判定，但不再单独渲染这张卡片。组件保留供仪器测试与将来复用。
internal fun RouteTaskCard(task: RouteTaskState) {
    val attention = task.phase in setOf(RouteTaskPhase.OffRoute, RouteTaskPhase.VehicleAlert)
    val color = if (attention) Warning else Accent
    TelemetryCard(localized("路线任务", "Route task"), "route-task", color, Icons.Default.Place,
        when (task.phase) {
            RouteTaskPhase.Guiding -> TelemetryAvailability.Fresh
            RouteTaskPhase.Expired -> TelemetryAvailability.Expired
            else -> TelemetryAvailability.Missing
        }, sourceLabel = localized("高德证据", "AMap evidence")) {
        Info(when (task.phase) {
            RouteTaskPhase.Idle -> localized("当前未使用高德导航", "AMap navigation inactive")
            RouteTaskPhase.Waiting -> localized("等待有效路线引导", "Waiting for valid route guidance")
            RouteTaskPhase.OffRoute -> localized("车端报告偏航 · 已撤下旧任务", "Vehicle reports off-route · old task withdrawn")
            RouteTaskPhase.Recovering -> localized("等待新引导 · ${task.confirmationPackets}/2 条确认", "Waiting for new guidance · ${task.confirmationPackets}/2 packets")
            RouteTaskPhase.Expired -> localized("高德数据过期 · 任务已撤下", "AMap expired · task withdrawn")
            RouteTaskPhase.VehicleAlert -> localized("车端告警 · 任务已暂停", "Vehicle alert · task paused")
            RouteTaskPhase.Guiding -> when {
                task.newRouteObserved -> localized("新路线引导已更新", "Updated route guidance observed")
                task.recoveredWithoutRouteChange -> localized("引导已恢复 · 未确认路线重规划", "Guidance recovered · reroute not confirmed")
                else -> localized("当前路线引导有效", "Current route guidance valid")
            }
        }, emphasis = true)
        if (!task.ready) {
            if (task.phase in setOf(RouteTaskPhase.OffRoute, RouteTaskPhase.Recovering)) {
                Info(localized("错过出口可继续行驶，等待地图重规划；不追赶旧出口",
                    "Continue safely after a missed exit; let the map reroute, do not chase the old exit"))
            }
            return@TelemetryCard
        }
        task.destination?.let { Info(localized("目的地 · $it", "Destination · $it")) }
        DetailPanel(color, Modifier.testTag("route-task-current")) {
            Info(task.current?.label ?: localized("当前动作未提供", "Current maneuver unavailable"), emphasis = true)
            task.current?.let { action ->
                Info(listOfNotNull(action.roadName, distance(action.distanceM.toFloat()),
                    action.remainingSec?.let { localized("预计 $it 秒", "Estimated $it s") }).joinToString(" · "))
            }
        }
        task.next?.let { action ->
            DetailPanel(color, Modifier.testTag("route-task-next")) {
                Info(localized("随后 · ${action.label}", "Next · ${action.label}"), emphasis = true)
                Info(localized("随后航段 ${distance(action.distanceM.toFloat())}",
                    "Next segment ${distance(action.distanceM.toFloat())}"))
                action.roadName?.let { Info(it) }
            }
        }
        if (task.closelySpacedActions) {
            Info(localized("随后航段预计不超过30秒 · 提前留意连续动作，不据此跨车道",
                "Next segment estimated at 30s or less · watch consecutive maneuvers, not lane-change clearance"), emphasis = true)
        }
        task.exit?.let { Info(localized("出口 / 航段 · $it", "Exit / segment · $it")) }
        Info(localized("路线代次 ${task.routeGeneration} · 仅导航任务，非车端执行确认",
            "Route generation ${task.routeGeneration} · navigation task, not vehicle execution"))
    }
}
