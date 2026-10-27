package com.jixiexiaoge.drivingassist.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jixiexiaoge.drivingassist.CarrotManFields
import com.jixiexiaoge.drivingassist.LaneInfo
import com.jixiexiaoge.drivingassist.data.CompactTelemetry
import com.jixiexiaoge.drivingassist.data.LaneGeometry
import com.jixiexiaoge.drivingassist.data.LaneGeometryStatus
import com.jixiexiaoge.drivingassist.data.toDisplaySnapshot
import com.jixiexiaoge.drivingassist.navigation.AmapGuideExtras
import com.jixiexiaoge.drivingassist.navigation.AmapDisplayAction
import com.jixiexiaoge.drivingassist.navigation.AmapDisplayState
import com.jixiexiaoge.drivingassist.navigation.DrivingSceneEvidence
import com.jixiexiaoge.drivingassist.navigation.DrivingMode
import com.jixiexiaoge.drivingassist.navigation.NaviSourceTracker
import com.jixiexiaoge.drivingassist.navigation.buildAmapNavigationDisplay
import com.jixiexiaoge.drivingassist.ui.theme.NavipilotTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DrivingModeCardsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun automaticPendingWithholdsCandidatesAndGeometryExpiryRemovesIndex() {
        val fields = CarrotManFields(isNavigating = true, noaRouteGeneration = 1, nLaneCount = 2,
            laneInfoList = listOf(LaneInfo("0", true, driveWayNumber = 0), LaneInfo("0", true, driveWayNumber = 1)))
        val sources = NaviSourceTracker().apply {
            update("lane_current", nowMs = 1001)
            updateGuideExtras(AmapGuideExtras(1000, 1, "Road", "Destination", 10000, 900,
                3, null, null, null, null, null, reportedRoadType = 0))
        }
        val map = buildAmapNavigationDisplay(fields, sources.snapshot(), "AMAP", 1200)
        var geometry by mutableStateOf(LaneGeometry(LaneGeometryStatus.Consistent,
            3.5f, 3.5f, 7f, 1, 2, 2))
        compose.setContent {
            NavipilotTheme {
                Column(Modifier.requiredWidth(360.dp).verticalScroll(rememberScrollState())) {
                    DrivingModeCard(DrivingMode.HIGHWAY_EAP, map, CompactTelemetry().toDisplaySnapshot(),
                        onSelect = {}, automatic = true, sceneReady = false)
                    DrivingModeVehicleCard(null, CompactTelemetry().toDisplaySnapshot(), geometry)
                }
            }
        }
        compose.onNodeWithTag("mode-automatic").assertIsSelected()
        compose.onNodeWithText("第 2 条").assertDoesNotExist()
        compose.onNodeWithText("约第 2 带").performScrollTo().assertExists()
        compose.runOnIdle { geometry = LaneGeometry(LaneGeometryStatus.Expired) }
        compose.onNodeWithText("约第 2 带").assertDoesNotExist()
        compose.onNodeWithText("模型数据已过期").performScrollTo().assertExists()
    }

    @Test
    fun allModesStayVisibleAndSwitchAtNarrowWidthAndLargeFont() {
        var selected by mutableStateOf(DrivingMode.HIGHWAY_EAP)
        val map = buildAmapNavigationDisplay(CarrotManFields(), NaviSourceTracker().snapshot(), "AMAP", 1200)
        val vehicle = CompactTelemetry().toDisplaySnapshot()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                NavipilotTheme {
                    Column(Modifier.requiredWidth(320.dp).verticalScroll(rememberScrollState())) {
                        DrivingModeCard(selected, map, vehicle, onSelect = { selected = it })
                        DrivingModeVehicleCard(selected, vehicle)
                    }
                }
            }
        }
        DrivingMode.entries.forEach { mode ->
            compose.onNodeWithTag("mode-${mode.storageKey}").performScrollTo().performClick().assertIsSelected()
        }
        compose.onNodeWithTag("mode-authority").performScrollTo().assertExists()
        compose.onNodeWithText("只读提示 · 不自动控制").assertExists()
        compose.onNodeWithText("等待高德导航 · 暂停候选").performScrollTo().assertExists()
    }

    @Test
    fun candidateClearsWhenRoadDataExpiresAndModeSelectionRemains() {
        val fields = CarrotManFields(isNavigating = true, noaRouteGeneration = 1, nLaneCount = 2,
            laneInfoList = listOf(LaneInfo("0", true, driveWayNumber = 0), LaneInfo("0", true, driveWayNumber = 1)))
        val sources = NaviSourceTracker().apply {
            update("lane_current", nowMs = 1001)
            updateGuideExtras(AmapGuideExtras(1000, 1, "Road", "Destination", 10000, 900,
                3, null, null, null, null, null, reportedRoadType = 0))
        }
        var display by mutableStateOf(buildAmapNavigationDisplay(fields, sources.snapshot(), "AMAP", 1200))
        compose.setContent {
            NavipilotTheme {
                Column(Modifier.requiredWidth(360.dp).verticalScroll(rememberScrollState())) {
                    DrivingModeCard(DrivingMode.HIGHWAY_EAP, display, CompactTelemetry().toDisplaySnapshot(), onSelect = {})
                }

            }
        }
        compose.onNodeWithText("第 2 条").performScrollTo().assertExists()
        compose.runOnIdle { display = buildAmapNavigationDisplay(fields, sources.snapshot(), "AMAP", 11002) }
        compose.onNodeWithText("第 2 条").assertDoesNotExist()
        compose.onNodeWithTag("mode-highway_eap").performScrollTo().assertIsSelected()
    }

    @Test
    fun routeTaskAndOverrideWithdrawWhenMapExpires() {
        val fields = CarrotManFields(isNavigating = true, noaRouteGeneration = 1, nLaneCount = 2,
            laneInfoList = listOf(LaneInfo("0", true, driveWayNumber = 0), LaneInfo("0", false, driveWayNumber = 1)))
        val sources = NaviSourceTracker().apply {
            update("lane_current", nowMs = 1001)
            updateGuideExtras(AmapGuideExtras(1000, 1, "Road", "Destination", 10000, 900,
                3, null, null, null, null, null, reportedRoadType = 0))
        }
        var map by mutableStateOf(buildAmapNavigationDisplay(fields, sources.snapshot(), "AMAP", 1200)
            .copy(current = AmapDisplayAction("右转", "出口路", 500),
                next = AmapDisplayAction("左转", "随后道路", 120), exit = "A出口"))
        compose.setContent {
            NavipilotTheme {
                Column(Modifier.requiredWidth(320.dp).verticalScroll(rememberScrollState())) {
                    DrivingModeCard(DrivingMode.HIGHWAY_EAP, map,
                        CompactTelemetry().toDisplaySnapshot(), onSelect = {})
                }
            }
        }
        compose.onNodeWithText("导航推荐优先 · 本次不采用默认车道偏好").performScrollTo().assertExists()
        compose.onNodeWithText("随后 左转 · 随后航段 120 m").performScrollTo().assertExists()
        compose.runOnIdle { map = map.copy(state = AmapDisplayState.Expired) }
        compose.onNodeWithTag("mode-route-task").assertDoesNotExist()
        compose.onNodeWithText("第 1 条").assertDoesNotExist()
    }

    @Test
    fun automaticPendingShowsBroadcastEvidenceWithoutClaimingASelectedMode() {
        val map = buildAmapNavigationDisplay(CarrotManFields(), NaviSourceTracker().snapshot(), "AMAP", 1200)
        compose.setContent {
            NavipilotTheme {
                Column(Modifier.requiredWidth(320.dp).verticalScroll(rememberScrollState())) {
                    DrivingModeCard(DrivingMode.HIGHWAY_EAP, map, CompactTelemetry().toDisplaySnapshot(),
                        onSelect = {}, automatic = true, sceneReady = false,
                        sceneEvidence = DrivingSceneEvidence(DrivingMode.ELEVATED_NOA, 2, 1000))
                }
            }
        }
        compose.onNodeWithText("高架 NOA · 2/3 条 · 1.0/2 秒").performScrollTo().assertExists()
        compose.onNodeWithTag("mode-automatic").performScrollTo().assertIsSelected()
        compose.onNodeWithText("第 2 条").assertDoesNotExist()
    }
}
