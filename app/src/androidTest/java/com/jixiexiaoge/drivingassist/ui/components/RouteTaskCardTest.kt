package com.jixiexiaoge.drivingassist.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jixiexiaoge.drivingassist.data.CompactTelemetry
import com.jixiexiaoge.drivingassist.data.toDisplaySnapshot
import com.jixiexiaoge.drivingassist.navigation.AmapDisplayAction
import com.jixiexiaoge.drivingassist.navigation.RouteTaskPhase
import com.jixiexiaoge.drivingassist.navigation.RouteTaskState
import com.jixiexiaoge.drivingassist.ui.theme.NavipilotTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RouteTaskCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun offRouteWithdrawsTaskAndRecoveryDoesNotClaimReplanning() {
        var task by mutableStateOf(RouteTaskState(RouteTaskPhase.Guiding, 2,
            current = AmapDisplayAction("右转", "出口路", 500),
            next = AmapDisplayAction("左转", "随后道路", 100, 20)))
        compose.setContent {
            NavipilotTheme {
                Column(Modifier.requiredWidth(320.dp).verticalScroll(rememberScrollState())) {
                    RouteTaskCard(task)
                    val map = com.jixiexiaoge.drivingassist.navigation.buildAmapNavigationDisplay(
                        com.jixiexiaoge.drivingassist.CarrotManFields(isNavigating = true),
                        com.jixiexiaoge.drivingassist.navigation.NaviSourceTracker().apply {
                            update("guidance_current", nowMs = 1000)
                        }.snapshot(), "AMAP", 1100)
                    AmapNavigationCard(map, routeTasksReady = task.ready)
                }
            }
        }
        compose.onNodeWithTag("route-task-next").performScrollTo().assertExists()
        compose.onNodeWithText("随后航段 100 m").assertExists()
        compose.runOnIdle { task = RouteTaskState(RouteTaskPhase.OffRoute, 2) }
        compose.onNodeWithTag("route-task-current").assertDoesNotExist()
        compose.onNodeWithTag("route-task-next").assertDoesNotExist()
        compose.onNodeWithTag("amap-current-action").assertDoesNotExist()
        compose.onNodeWithTag("amap-next-action").assertDoesNotExist()
        compose.onNodeWithText("车端报告偏航 · 已撤下旧任务").performScrollTo().assertExists()
        compose.runOnIdle {
            task = RouteTaskState(RouteTaskPhase.Guiding, 2,
                recoveredWithoutRouteChange = true)
        }
        compose.onNodeWithText("引导已恢复 · 未确认路线重规划").performScrollTo().assertExists()
        compose.onNodeWithText("新路线引导已更新").assertDoesNotExist()
    }

    @Test
    fun missingVehicleStateDoesNotClaimCompletedLaneChangeOrAcknowledgement() {
        compose.setContent {
            NavipilotTheme {
                Column(Modifier.requiredWidth(320.dp).verticalScroll(rememberScrollState())) {
                    val map = com.jixiexiaoge.drivingassist.navigation.buildAmapNavigationDisplay(
                        com.jixiexiaoge.drivingassist.CarrotManFields(),
                        com.jixiexiaoge.drivingassist.navigation.NaviSourceTracker().snapshot(), "AMAP", 1000)
                    NavigationComparisonCard(map, CompactTelemetry().toDisplaySnapshot(), routeReady = false)
                }
            }
        }
        compose.onNodeWithText("7714通道未就绪").performScrollTo().assertExists()
        compose.onNodeWithText("车端变道阶段 · 未知 / 数据失效").performScrollTo().assertExists()
        compose.onNodeWithText("采用/拒绝原因 · 当前协议未提供关联确认").performScrollTo().assertExists()
    }
}
