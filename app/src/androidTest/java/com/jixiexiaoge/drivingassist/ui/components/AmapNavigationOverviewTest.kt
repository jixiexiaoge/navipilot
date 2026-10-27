package com.jixiexiaoge.drivingassist.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jixiexiaoge.drivingassist.CarrotManFields
import com.jixiexiaoge.drivingassist.LaneInfo
import com.jixiexiaoge.drivingassist.navigation.AmapGuideExtras
import com.jixiexiaoge.drivingassist.navigation.AmapCruiseLamp
import com.jixiexiaoge.drivingassist.navigation.AmapCruiseRoad
import com.jixiexiaoge.drivingassist.navigation.AmapDisplayAction
import com.jixiexiaoge.drivingassist.navigation.AmapSectionDisplay
import com.jixiexiaoge.drivingassist.navigation.AmapSectionPhase
import com.jixiexiaoge.drivingassist.navigation.AmapRoadNoticeKind
import com.jixiexiaoge.drivingassist.navigation.AmapTrafficSegment
import com.jixiexiaoge.drivingassist.navigation.AmapTrafficSegments
import com.jixiexiaoge.drivingassist.navigation.NaviSourceTracker
import com.jixiexiaoge.drivingassist.navigation.buildAmapNavigationDisplay
import com.jixiexiaoge.drivingassist.ui.theme.NavipilotTheme
import org.junit.Rule
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AmapNavigationOverviewTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun pairedSegmentsKeepEqualColumnsDistinctUnitsUnknownsAndFontFallback() {
        var width by mutableStateOf(420.dp)
        var fontScale by mutableStateOf(1f)
        var next by mutableStateOf<AmapDisplayAction?>(
            AmapDisplayAction("右转", "Next road", 300, remainingSec = 20, maneuverIcon = 3))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                NavipilotTheme {
                    Column(Modifier.requiredWidth(width).verticalScroll(rememberScrollState())) {
                        AmapSegmentPair(
                            AmapDisplayAction("左转", "Current road", 200, remainingSec = 80, maneuverIcon = 2),
                            next,
                        )
                    }
                }
            }
        }
        fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val current = bounds("amap-current-action")
        val following = bounds("amap-next-action")
        assertEquals(current.width, following.width, 1f)
        assertEquals(current.top, following.top, 1f)
        assertTrue(following.left >= current.right)
        compose.onNodeWithText("当前航段 · 即将到达 · 200 m").assertExists()
        compose.onNodeWithText("随后航段 · 随后动作 · 300 m").assertExists()
        compose.onNodeWithText("当前航段预计剩余 · 2 分钟").assertExists()
        compose.onNodeWithText("随后航段预计用时 · 20 秒").assertExists()
        compose.runOnIdle { next = null; fontScale = 2f }
        assertTrue(bounds("amap-next-action").top >= bounds("amap-current-action").bottom)
        compose.onNodeWithText("随后航段预计用时 · 当前无数据").assertExists()
        compose.onNodeWithContentDescription("导航动作未知").assertExists()
        compose.runOnIdle { fontScale = 1f; width = 260.dp }
        assertTrue(bounds("amap-next-action").top >= bounds("amap-current-action").bottom)
    }

    @Test
    fun combinedSegmentsWithdrawTogetherOnRouteHoldAndExpiry() {
        val fields = CarrotManFields(isNavigating = true, noaRouteGeneration = 1)
        val sources = NaviSourceTracker().apply { updateRoadSpeedLimit(80, 1, 1000) }
        var now by mutableStateOf(1200L)
        var ready by mutableStateOf(true)
        compose.setContent {
            NavipilotTheme {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    AmapNavigationCard(buildAmapNavigationDisplay(fields, sources.snapshot(), "AMAP", now)
                        .copy(current = AmapDisplayAction("左转", null, 200),
                            next = AmapDisplayAction("右转", null, 300)), ready)
                }
            }
        }
        compose.onNodeWithTag("amap-segment-pair").assertExists()
        compose.runOnIdle { ready = false }
        compose.onNodeWithTag("amap-segment-pair").assertDoesNotExist()
        compose.onNodeWithTag("amap-current-action").assertDoesNotExist()
        compose.onNodeWithTag("amap-next-action").assertDoesNotExist()
        compose.runOnIdle { ready = true; now = 11001L }
        compose.onNodeWithTag("amap-segment-pair").assertDoesNotExist()
        compose.onNodeWithTag("amap-overview").assertDoesNotExist()
    }

    @Test
    fun cruiseRoadCardIsIdleOnlyAndDisappearsOnExpirySourceChangeAndClear() {
        val sources = NaviSourceTracker().apply {
            updateCruiseRoad(AmapCruiseRoad(1000, "Cruise road", null, null, 999, null, null))
        }
        var fields by mutableStateOf(CarrotManFields())
        var now by mutableStateOf(1200L)
        var mode by mutableStateOf("AMAP")
        var snapshot by mutableStateOf(sources.snapshot())
        compose.setContent {
            NavipilotTheme { AmapCruiseRoadCard(buildAmapNavigationDisplay(fields, snapshot, mode, now)) }
        }
        compose.onNodeWithTag("amap-cruise-road").assertExists()
        compose.onNodeWithText("当前道路 · Cruise road").assertExists()
        compose.onNodeWithText("测速类型原始码").assertExists()
        compose.onNodeWithTag("amap-cruise-speed").assertExists()
        compose.runOnIdle { fields = fields.copy(isNavigating = true) }
        compose.onNodeWithTag("amap-cruise-road").assertDoesNotExist()
        compose.runOnIdle { fields = fields.copy(isNavigating = false); now = 11001 }
        compose.onNodeWithTag("amap-cruise-road").assertDoesNotExist()
        compose.runOnIdle { now = 1200; mode = "OTHER" }
        compose.onNodeWithTag("amap-cruise-road").assertDoesNotExist()
        compose.runOnIdle { mode = "AMAP"; sources.clearCruise(); snapshot = sources.snapshot() }
        compose.onNodeWithTag("amap-cruise-road").assertDoesNotExist()
    }

    @Test
    fun currentAndNextManeuverGraphicsKeepUnknownNeutralAndRespectRouteHold() {
        val sources = NaviSourceTracker().apply { updateRoadSpeedLimit(80, 1, 1000) }
        val base = buildAmapNavigationDisplay(CarrotManFields(isNavigating = true, noaRouteGeneration = 1),
            sources.snapshot(), "AMAP", 1200).copy(
            current = AmapDisplayAction("左转", null, 200, maneuverIcon = 2),
            next = AmapDisplayAction("未识别的高德动作（ICON 999）", null, 300),
        )
        var ready by mutableStateOf(true)
        compose.setContent {
            NavipilotTheme {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    AmapNavigationCard(base, ready)
                }
            }
        }
        compose.onNodeWithTag("amap-current-action-graphic").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("左转").assertExists()
        val pixels = compose.onNodeWithTag("amap-current-action-graphic").captureToImage().toPixelMap()
        assertTrue((0 until pixels.width).any { x -> (0 until pixels.height).any { y ->
            val pixel = pixels[x, y]
            pixel.blue > .8f && pixel.green > .4f
        } })
        compose.onNodeWithTag("amap-next-action-graphic").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("导航动作未知").assertExists()
        compose.runOnIdle { ready = false }
        compose.onNodeWithTag("amap-current-action-graphic").assertDoesNotExist()
        compose.onNodeWithTag("amap-next-action-graphic").assertDoesNotExist()
    }

    @Test
    fun sectionRawTextsStaySeparateFromNumericValuesAndWithdrawOnExpiry() {
        val fields = CarrotManFields(isNavigating = true, noaRouteGeneration = 1)
        val sources = NaviSourceTracker().apply {
            updateSectionDisplay(AmapSectionDisplay(1000, 1, AmapSectionPhase.Inside, 80, 65.0, 1500.0,
                "前方2公里", "约1.5公里"))
        }
        var now by mutableStateOf(1200L)
        compose.setContent {
            NavipilotTheme {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    AmapNavigationCard(buildAmapNavigationDisplay(fields, sources.snapshot(), "AMAP", now))
                }
            }
        }
        compose.onNodeWithTag("amap-section").performScrollTo()
        compose.onNodeWithText("阶段 · 区间内").assertExists()
        compose.onNodeWithText("起点距离（原文） · 前方2公里").assertExists()
        compose.onNodeWithText("终点距离（原文） · 约1.5公里").assertExists()
        compose.onNodeWithTag("amap-section-remaining").assertExists()
        compose.runOnIdle { now = 11001 }
        compose.onNodeWithText("阶段 · 区间内").assertDoesNotExist()
        compose.onNodeWithText("终点距离（原文） · 约1.5公里").assertDoesNotExist()
    }

    @Test
    fun facilitiesDoNotDuplicateNamesAndRetainRawNumericAndUnknownTypeCodes() {
        val fields = CarrotManFields(isNavigating = true, noaRouteGeneration = 1)
        val sources = NaviSourceTracker().apply {
            updateGuideExtras(AmapGuideExtras(1000, 1, null, null, null, null, null, null,
                null, null, "Current facility", 1000, serviceDistanceText = "约1公里", serviceType = 998,
                nextServiceName = "Next facility", nextServiceDistanceText = "前方2公里",
                nextServiceType = 999, nextServiceDistanceM = 2000))
        }
        var now by mutableStateOf(1200L)
        compose.setContent {
            NavipilotTheme {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    AmapNavigationCard(buildAmapNavigationDisplay(fields, sources.snapshot(), "AMAP", now))
                }
            }
        }
        compose.onNodeWithTag("amap-service").performScrollTo()
        compose.onAllNodesWithText("Current facility", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("Next facility", substring = true).assertCountEquals(1)
        compose.onNodeWithText("距离（原文） · 约1公里").assertExists()
        compose.onNodeWithText("距离（数值） · 1000 m").assertExists()
        compose.onNodeWithText("随后距离（原文） · 前方2公里").assertExists()
        compose.onNodeWithText("随后距离（数值） · 2000 m").assertExists()
        compose.onNodeWithText("设施类型原始码 · 998 / 999").assertExists()
        compose.runOnIdle { now = 11001 }
        compose.onAllNodesWithText("Current facility", substring = true).assertCountEquals(0)
    }

    @Test
    fun cruiseLampCodesRemainSeparateAndWithdrawnLampsDisappear() {
        val base = buildAmapNavigationDisplay(CarrotManFields(), NaviSourceTracker().snapshot(), "AMAP", 1200)
        var lamps by mutableStateOf(listOf(AmapCruiseLamp(0, 1, 10), AmapCruiseLamp(1, 2, 15),
            AmapCruiseLamp(99, 99, 5)))
        compose.setContent {
            NavipilotTheme { AmapCruiseLamps(base.copy(cruiseLamps = lamps)) }
        }
        compose.onNodeWithText("左转 · 红灯").assertExists()
        compose.onNodeWithText("直行 · 绿灯").assertExists()
        compose.onNodeWithText("方向未知 · 灯色未知(99)").assertExists()
        compose.runOnIdle { lamps = emptyList() }
        compose.onNodeWithTag("amap-cruise-lamps").assertDoesNotExist()
    }

    @Test
    fun readOnlyRoadSpeedWarningBecomesUnknownWhenSpeedIsWithdrawn() {
        val fields = CarrotManFields(isNavigating = true, noaRouteGeneration = 1)
        val sources = NaviSourceTracker().apply { updateRoadSpeedLimit(80, 1, 1000) }
        var speed by mutableStateOf<Double?>(85.0)
        compose.setContent {
            NavipilotTheme {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    AmapNavigationCard(buildAmapNavigationDisplay(fields, sources.snapshot(), "AMAP", 1200)
                        .copy(mapSpeedKph = speed))
                }
            }
        }
        compose.onNodeWithTag("amap-road-speed-warning").performScrollTo()
        compose.onNodeWithText("高德速度高于当前道路限速 · 仅提醒").assertExists()
        compose.runOnIdle { speed = null }
        compose.onNodeWithText("高德速度高于当前道路限速 · 仅提醒").assertDoesNotExist()
        compose.onNodeWithText("超速对照 · 速度或道路限速未知").assertExists()
    }

    @Test
    fun originalRoadAndUpcomingEventLimitsAppearBelowRoadAndClearWithoutTelemetryFallback() {
        val fields = CarrotManFields(isNavigating = true, noaRouteGeneration = 1, nRoadLimitSpeed = 120,
            nSdiType = 1, nSdiSpeedLimit = 60, nSdiDist = 300)
        val sources = NaviSourceTracker().apply {
            updateRoadSpeedLimit(80, 1, 1000)
            update("sdi", nowMs = 1000)
        }
        var now by mutableStateOf(1200L)
        compose.setContent {
            NavipilotTheme {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    AmapNavigationCard(buildAmapNavigationDisplay(fields, sources.snapshot(), "AMAP", now))
                }
            }
        }
        compose.onNodeWithTag("amap-limits").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("80 km/h") and hasAnyAncestor(hasTestTag("amap-road-limit"))).assertExists()
        compose.onNode(hasText("60 km/h") and hasAnyAncestor(hasTestTag("amap-upcoming-limit"))).assertExists()
        compose.onNodeWithText("距提醒点 · 300 m").assertExists()
        compose.onNodeWithText("120 km/h").assertDoesNotExist()
        compose.runOnIdle {
            sources.update("route", nowMs = 12000)
            now = 12000
        }
        compose.onNodeWithTag("amap-limits").assertExists()
        compose.onNodeWithText("80 km/h").assertDoesNotExist()
        compose.onNodeWithText("60 km/h").assertDoesNotExist()
        compose.onNodeWithText("距提醒点 · 当前无数据").assertExists()
    }

    @Test
    fun routeAndServiceFacilityDisappearWhenBroadcastsExpire() {
        val fields = CarrotManFields(isNavigating = true, noaRouteGeneration = 1, nTBTDist = 500, nTBTTurnType = 13)
        val sources = NaviSourceTracker().apply {
            update("guidance_current", "route", nowMs = 1000)
            updateRouteTotal(20000)
            updateGuideExtras(AmapGuideExtras(1000, 1, "Test road", "Test destination", 12000, 900,
                3, null, null, null, "Test service area", 3000, currentSegmentSec = 45, totalTimeSec = 1800))
        }
        var now by mutableStateOf(1200L)
        compose.setContent {
            NavipilotTheme {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    AmapNavigationCard(buildAmapNavigationDisplay(fields, sources.snapshot(), "AMAP", now))
                }
            }
        }
        compose.onNodeWithText("目的地 · Test destination").assertExists()
        compose.onNodeWithText("右转").assertExists()
        compose.onNodeWithTag("amap-route-progress").assertExists()
        compose.onNodeWithTag("amap-arrival").assertExists()
        compose.onNodeWithTag("amap-total-distance").assertExists()
        compose.onNodeWithText("当前航段预计剩余 · 45 秒").assertExists()
        compose.onNodeWithText("已行驶（估算）").assertExists()
        compose.onNodeWithText("当前设施 · Test service area").performScrollTo().assertExists()
        compose.runOnIdle { now = 12000L }
        compose.onNodeWithText("目的地 · Test destination").assertDoesNotExist()
        compose.onNodeWithText("右转").assertDoesNotExist()
        compose.onNodeWithTag("amap-route-progress").assertDoesNotExist()
        compose.onNodeWithTag("amap-arrival").assertDoesNotExist()
        compose.onNodeWithTag("amap-overview").assertDoesNotExist()
    }

    @Test
    fun overviewAppearsOnlyAfterValidMapDataAndKeepsItsHeaderOnSourceChanges() {
        val sources = NaviSourceTracker()
        var fields by mutableStateOf(CarrotManFields())
        var mode by mutableStateOf("AMAP")
        compose.setContent {
            NavipilotTheme {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Text("Map header", modifier = Modifier.testTag("amap-header"))
                    AmapNavigationCard(buildAmapNavigationDisplay(fields, sources.snapshot(), mode, 1000))
                }
            }
        }
        fun assertSections() {
            listOf("amap-remaining-distance", "amap-remaining-time", "amap-limits",
                "amap-current-action", "amap-next-action", "amap-exit", "amap-traffic-segments",
                "amap-notice-Traffic", "amap-notice-Situation", "amap-service", "amap-speed-events", "amap-section")
                .forEach { compose.onNodeWithTag(it).performScrollTo().assertIsDisplayed() }
            compose.onNodeWithText("目的地 · 当前无数据").assertExists()
            compose.onNodeWithText("Test road").assertExists()
            compose.onNodeWithTag("amap-route-progress").assertDoesNotExist()
            compose.onNodeWithTag("amap-traffic-bar").assertDoesNotExist()
        }
        compose.onNodeWithTag("amap-overview").assertDoesNotExist()
        compose.onNodeWithTag("amap-header").assertExists()
        compose.runOnIdle { fields = fields.copy(isNavigating = true, lastUpdateTime = 1000) }
        compose.onNodeWithTag("amap-overview").assertDoesNotExist()
        compose.runOnIdle {
            sources.updateGuideExtras(AmapGuideExtras(1000, fields.noaRouteGeneration, "Test road", null, null, null,
                null, null, null, null, null, null))
            fields = fields.copy(lastUpdateTime = 1001)
        }
        assertSections()
        val order = listOf("amap-road", "amap-limits", "amap-segment-pair", "amap-exit", "amap-journey",
            "amap-speed-events", "amap-section", "amap-traffic-segments", "amap-notice-Traffic", "amap-notice-Situation", "amap-service")
        val tops = order.map { compose.onNodeWithTag(it).fetchSemanticsNode().positionInRoot.y }
        assertTrue(tops.zipWithNext().all { (first, second) -> second > first })
        compose.runOnIdle { mode = "OTHER" }
        compose.onNodeWithTag("amap-overview").assertDoesNotExist()
        compose.onNodeWithTag("amap-header").assertExists()
    }
}
