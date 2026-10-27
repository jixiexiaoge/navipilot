package com.jixiexiaoge.drivingassist.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jixiexiaoge.drivingassist.CarrotManFields
import com.jixiexiaoge.drivingassist.navigation.NaviSourceTracker
import com.jixiexiaoge.drivingassist.navigation.buildAmapNavigationDisplay
import com.jixiexiaoge.drivingassist.data.CompactFrame
import com.jixiexiaoge.drivingassist.data.CompactTelemetry
import com.jixiexiaoge.drivingassist.data.CompactTelemetryStore
import com.jixiexiaoge.drivingassist.data.toDisplaySnapshot
import com.jixiexiaoge.drivingassist.data.LaneGeometry
import com.jixiexiaoge.drivingassist.data.LaneGeometryStatus
import com.jixiexiaoge.drivingassist.ui.theme.Accent
import com.jixiexiaoge.drivingassist.ui.theme.NavipilotTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarrotTelemetryCardsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun groupedSectionsIdentifyIndependentServicesAndDoNotBorrowModelFreshness() {
        val store = CompactTelemetryStore()
        var telemetry by mutableStateOf(store.accept(listOf(
            CompactFrame("controlsState", 1, mapOf("curvature" to 0.01f)),
            CompactFrame("modelV2", 1, mapOf("frameId" to 1L)),
            CompactFrame("lateralPlan", 1, mapOf("useLaneLines" to true)),
            CompactFrame("longitudinalPlan", 1, mapOf("speeds" to listOf(0f))),
        ), 1000))
        compose.setContent {
            NavipilotTheme {
                Column(Modifier.requiredWidth(300.dp).verticalScroll(rememberScrollState())) {
                    VehicleDetailsGroup(telemetry.toDisplaySnapshot())
                }
            }
        }
        compose.onNodeWithText("灯组接收 · 未收到／连接不可用").performScrollTo().assertExists()
        compose.onNodeWithText("横向规划状态 · 数据有效").performScrollTo().assertExists()
        compose.runOnIdle { telemetry = store.snapshot(1500) }
        compose.onNodeWithText("7000/modelV2 · 已更新").performScrollTo().assertExists()
        compose.onNodeWithText("横向规划状态 · 数据已过期").performScrollTo().assertExists()
        compose.onNodeWithTag("actual-curvature").performScrollTo().assertExists()
        compose.onNodeWithText("0.01 1/m").assertDoesNotExist()
        compose.onNodeWithText("车道线采用状态 · 数据有效").performScrollTo().assertExists()
        compose.runOnIdle { telemetry = store.snapshot(1750) }
        compose.onNodeWithText("车道线采用状态 · 数据已过期").performScrollTo().assertExists()
    }

    @Test
    fun groupedVehicleDetailsKeepEverySectionAndReduceMeasuredHeight() {
        var grouped by mutableStateOf(false)
        var measuredHeight = 0
        val data = (null as CompactTelemetry?).toDisplaySnapshot()
        compose.setContent {
            NavipilotTheme {
                Column(Modifier.requiredWidth(440.dp).verticalScroll(rememberScrollState())) {
                    Column(Modifier.testTag("details-measure").onGloballyPositioned {
                        measuredHeight = it.size.height
                    }) {
                        if (grouped) VehicleDetailsGroup(data) else {
                            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                                CarrotBehaviorCard(data)
                                CarrotVehiclePlanCard(data)
                                PerceptionQualityCard(data)
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val before = measuredHeight
        compose.runOnIdle { grouped = true }
        compose.waitForIdle()
        val after = measuredHeight
        assertTrue("Grouped sections must reduce actual height: $before -> $after", before - after >= 30)
        listOf("carrot-behavior", "carrot-vehicle-plan", "perception-quality",
            "behavior-stage", "behavior-style", "behavior-traffic", "behavior-cruise-target",
            "behavior-jerk", "blindspot-left", "blindspot-right", "carrot-gear",
            "carrot-measured-acceleration", "carrot-brake-lights", "carrot-plan-speed",
            "carrot-plan-acceleration", "carrot-plan-lanes", "carrot-requested-acceleration",
            "carrot-requested-steering", "actual-curvature", "desired-curvature",
            "actual-lat-accel", "desired-lat-accel", "lateral-output").forEach {
            compose.onNodeWithTag(it).performScrollTo().assertExists()
        }
    }

    @Test
    fun vehicleNativeNavigationAndModelExecutionRemainSeparateAndExpire() {
        val store = CompactTelemetryStore()
        var telemetry by mutableStateOf(store.accept(listOf(
            CompactFrame("carrotMan", 1, mapOf("vehicleNaviAvailable" to true,
                "vehicleNaviActive" to true, "vehicleNaviSpeed" to 80, "vehicleNaviSectionActive" to false)),
            CompactFrame("modelV2", 1, mapOf("frameId" to 100L, "modelExecutionTime" to 0.025f)),
        ), 1000))
        compose.setContent {
            NavipilotTheme {
                Column(Modifier.requiredWidth(320.dp).verticalScroll(rememberScrollState())) {
                    CarrotReceivedStatusCard(telemetry.toDisplaySnapshot())
                    PerceptionQualityCard(telemetry.toDisplaySnapshot())
                }
            }
        }
        compose.onNodeWithTag("vehicle-navi-speed").performScrollTo().assertExists()
        compose.onNodeWithText("80 km/h").assertExists()
        compose.onNodeWithText("车辆自带导航测速 · 非高德接收确认").performScrollTo().assertExists()
        compose.onNodeWithText("模型推理耗时 · 25.0 ms · 非端到端延迟").performScrollTo().assertExists()
        compose.runOnIdle { telemetry = store.snapshot(2500) }
        compose.onNodeWithText("80 km/h").assertDoesNotExist()
        compose.onNodeWithText("模型推理耗时 · 25.0 ms · 非端到端延迟").assertDoesNotExist()
        compose.onNodeWithText("模型推理耗时 · — · 非端到端延迟").performScrollTo().assertExists()
    }

    @Test
    fun threeColumnsReflowAndKeepEqualCellWidthsWithIncompleteLastRow() {
        var scale by mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                NavipilotTheme {
                    Column(Modifier.requiredWidth(460.dp).verticalScroll(rememberScrollState())) {
                        MetricGrid((0..4).map { Metric("compact-$it", "数据 $it", "$it km/h") }, Accent)
                    }
                }
            }
        }
        fun bounds(index: Int) = compose.onNodeWithTag("compact-$index").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds(0).top == bounds(2).top)
        assertTrue(bounds(2).left > bounds(1).right)
        assertTrue(bounds(3).top > bounds(0).bottom)
        assertTrue(kotlin.math.abs(bounds(0).width - bounds(3).width) < 1f)
        compose.runOnIdle { scale = 1.5f }
        assertTrue(bounds(0).top == bounds(1).top)
        assertTrue(bounds(2).top > bounds(0).bottom)
        compose.runOnIdle { scale = 2f }
        assertTrue(bounds(1).top > bounds(0).bottom)
        (0..4).forEach { compose.onNodeWithTag("compact-$it").performScrollTo().assertExists() }
    }

    @Test
    fun compactBlindspotsClearAndGeometryHasNoLegacyRoadEdges() {
        val store = CompactTelemetryStore()
        var telemetry by mutableStateOf(store.accept(listOf(
            CompactFrame("carState", 1, mapOf("leftBlindspot" to true, "rightBlindspot" to false), 0),
            CompactFrame("radarState", 1, mapOf("leadTwo" to mapOf("status" to true,
                "dRel" to 40f, "yRel" to 0f, "vRel" to -2f)), 0),
        ), 1000))
        compose.setContent {
            NavipilotTheme {
                Column(Modifier.requiredWidth(320.dp).verticalScroll(rememberScrollState())) {
                    val data = telemetry.toDisplaySnapshot()
                    DrivingModeVehicleCard(null, data, LaneGeometry(LaneGeometryStatus.Missing))
                    CarrotVehiclePlanCard(data)
                    CarrotBehaviorCard(data)
                }
            }
        }
        compose.onNodeWithTag("road-edge-left-7711").assertDoesNotExist()
        compose.onNodeWithText("40 m").assertDoesNotExist()
        compose.onNodeWithTag("carrot-following").assertDoesNotExist()
        compose.onNodeWithTag("carrot-side-targets").assertDoesNotExist()
        compose.onNodeWithTag("behavior-cutin").assertDoesNotExist()
        compose.onNodeWithTag("second-lead-distance").assertDoesNotExist()
        compose.onNodeWithText("检测触发").performScrollTo().assertExists()
        compose.onNodeWithText("未触发（非安全确认）").performScrollTo().assertExists()
        compose.onNodeWithTag("surroundings").assertDoesNotExist()
        compose.runOnIdle {
            telemetry = store.snapshot(2000)
        }
        listOf("3.5 m", "40 m", "检测触发", "未触发（非安全确认）").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.onNodeWithTag("blindspot-left").performScrollTo().assertExists()
    }

    @Test
    fun receivedAdvisoryAndBothAlertLinesDisappearWhenExpired() {
        val store = CompactTelemetryStore()
        var telemetry by mutableStateOf(store.accept(listOf(
            CompactFrame("carrotMan", 1, mapOf("desiredSpeed" to 60, "desiredSource" to "cam"), 0),
            CompactFrame("selfdriveState", 1, mapOf(
                "alertStatus" to "critical", "alertText1" to "Take over", "alertText2" to "Immediately",
            ), 0),
        ), 1000))
        compose.setContent {
            NavipilotTheme {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    CarrotReceivedStatusCard(telemetry.toDisplaySnapshot())
                }
            }
        }
        compose.onNodeWithText("60 km/h").assertExists()
        compose.onNodeWithText("电子眼约束").assertExists()
        val alertTop = compose.onNodeWithTag("carrot-alert").fetchSemanticsNode().boundsInRoot.top
        val speedTop = compose.onNodeWithTag("advisory-speed").fetchSemanticsNode().boundsInRoot.top
        assertTrue(alertTop < speedTop)
        compose.onNodeWithText("Take over").performScrollTo().assertExists()
        compose.onNodeWithText("Immediately").assertExists()
        compose.runOnIdle { telemetry = store.snapshot(2500) }
        compose.onNodeWithText("Take over").assertDoesNotExist()
        compose.onNodeWithText("Immediately").assertDoesNotExist()
        compose.onNodeWithText("60 km/h").assertDoesNotExist()
        compose.onNodeWithText("电子眼约束").assertDoesNotExist()
        compose.onNodeWithText("告警数据：数据已过期").assertExists()
    }

    @Test
    fun missingDataIsExplicitInBothNarrowAndExpandedLayouts() {
        var width by mutableStateOf(360.dp)
        compose.setContent {
            val data = CompactTelemetry().toDisplaySnapshot()
            NavipilotTheme {
                Column(Modifier.requiredWidth(width).verticalScroll(rememberScrollState())) {
                    AdaptiveHomeOverview(
                        map = { Box(Modifier.fillMaxWidth().height(40.dp).testTag("map")) },
                        navigation = {
                            Column {
                                Box(Modifier.fillMaxWidth().height(40.dp).testTag("amap-header"))
                                AmapNavigationCard(buildAmapNavigationDisplay(
                                    CarrotManFields(), NaviSourceTracker().snapshot(), "AMAP", 1000))
                            }
                        },
                        results = {
                            Column {
                                CarrotReceivedStatusCard(data)
                                CarrotVehiclePlanCard(data)
                                CarrotJourneySummary(data)
                            }
                        },
                        controls = {
                            Column {
                                CarrotLaneChangeStatus(data)
                            }
                        },
                    )
                }
            }
        }
        compose.onNodeWithTag("carrot-following").assertDoesNotExist()
        compose.onNodeWithText("横向 未知").assertExists()
        compose.onNodeWithText("纵向 未知").assertExists()
        compose.onNodeWithText("车端当前动作").assertExists()
        compose.onNodeWithText("车端下一动作").assertExists()
        compose.onNodeWithText("车端导航推荐").assertExists()
        compose.onNodeWithTag("carrot-speed-events").assertExists()
        compose.onNodeWithTag("carrot-section").assertExists()
        compose.onNodeWithText("当前车速").assertDoesNotExist()
        compose.onNodeWithText("道路限速").assertDoesNotExist()
        compose.onNodeWithTag("carrot-device").assertDoesNotExist()
        compose.runOnIdle { width = 720.dp }
        compose.onNodeWithTag("carrot-journey").assertExists()
        compose.onNodeWithTag("carrot-results").assertExists()
        compose.onNodeWithTag("carrot-device").assertDoesNotExist()
        compose.onNodeWithTag("amap-overview").assertDoesNotExist()
        val amapX = compose.onNodeWithTag("amap-header").fetchSemanticsNode().positionInRoot.x
        listOf("carrot-results", "carrot-vehicle-plan", "carrot-journey").forEach {
            assertTrue(compose.onNodeWithTag(it).fetchSemanticsNode().positionInRoot.x > amapX)
        }
    }

    @Test
    fun largerFontsReflowMetricsWithoutDroppingAnyValues() {
        var scale by mutableStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                NavipilotTheme {
                    Column(Modifier.requiredWidth(360.dp).verticalScroll(rememberScrollState())) {
                        MetricGrid(listOf(
                            Metric("layout-first", "数据一", "—"),
                            Metric("layout-second", "数据二", "—"),
                            Metric("layout-third", "数据三", "—"),
                            Metric("layout-fourth", "数据四", "—"),
                        ), Accent)
                    }
                }
            }
        }
        val first = compose.onNodeWithTag("layout-first").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("layout-second").fetchSemanticsNode().boundsInRoot
        assertTrue(second.left > first.right)
        compose.runOnIdle { scale = 1.6f }
        val largerFirst = compose.onNodeWithTag("layout-first").fetchSemanticsNode().boundsInRoot
        val largerSecond = compose.onNodeWithTag("layout-second").fetchSemanticsNode().boundsInRoot
        assertTrue(largerSecond.top > largerFirst.bottom)
        compose.onNodeWithTag("layout-third").assertExists()
        compose.onNodeWithTag("layout-fourth").assertExists()
    }

    @Test
    fun receivedLaneRecommendationsAndJourneyProgressClearOnExpiry() {
        val store = CompactTelemetryStore()
        var telemetry by mutableStateOf(store.accept(listOf(
            CompactFrame("carrotNavi", 1, mapOf(
                "schemaVersion" to 1, "connected" to true,
                "navigationStatus" to mapOf("guidanceActive" to true, "routePresent" to true, "offRoute" to false),
                "route" to mapOf("present" to true, "remainingDistanceM" to 12000, "remainingTimeSec" to 900, "totalDistanceM" to 20000),
                "laneCurrent" to mapOf("present" to true, "visible" to true, "count" to 3, "available" to listOf(0, 1, 1)),
            ), 0),
        ), 1000))
        compose.setContent {
            NavipilotTheme {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    val data = telemetry.toDisplaySnapshot()
                    CarrotJourneySummary(data)
                    CarrotReceivedStatusCard(data)
                }
            }
        }
        compose.onNodeWithTag("carrot-journey-progress").assertExists()
        compose.onNodeWithText("行程进度 40%").assertExists()
        compose.onNodeWithText("推荐第 2/3 车道").performScrollTo().assertExists()
        compose.onNodeWithText("编号从左到右；导航推荐不代表可安全变道").assertExists()
        compose.runOnIdle { telemetry = store.snapshot(4000) }
        compose.onNodeWithTag("carrot-journey-progress").assertDoesNotExist()
        compose.onNodeWithTag("received-lane-0").assertDoesNotExist()
        compose.onNodeWithText("推荐第 2/3 车道").assertDoesNotExist()
    }

    @Test
    fun supplementaryPlanningExpiresWithoutShowingRadarTargets() {
        val store = CompactTelemetryStore()
        var telemetry by mutableStateOf(store.accept(listOf(
            CompactFrame("carState", 1, mapOf("gearShifter" to "drive", "aEgo" to -0.3f,
                "brakeLights" to true, "brakeHoldActive" to false), 0),
            CompactFrame("longitudinalPlan", 1, mapOf("speeds" to listOf(20f), "accels" to listOf(-0.5f)), 0),
            CompactFrame("carControl", 1, mapOf("actuators" to mapOf("accel" to -1f, "steeringAngleDeg" to 2f)), 0),
            CompactFrame("radarState", 1, mapOf("leadLeft" to mapOf("status" to true, "dRel" to 12f, "yRel" to 3f, "vRel" to 1f)), 0),
        ), 1000))
        compose.setContent {
            NavipilotTheme {
                Column(Modifier.requiredWidth(360.dp).verticalScroll(rememberScrollState())) {
                    val data = telemetry.toDisplaySnapshot()
                    CarrotVehiclePlanCard(data)
                }
            }
        }
        compose.onNodeWithText("距离 12 m · 相对速度 3.6 km/h").assertDoesNotExist()
        compose.onNodeWithText("驻车 P").assertDoesNotExist()
        compose.onNodeWithText("前进 D").assertExists()
        compose.onNodeWithText("72.0 km/h").assertExists()
        compose.onNodeWithText("-0.3 m/s²").assertExists()
        compose.onNodeWithText("-0.5 m/s²").assertExists()
        compose.onNodeWithText("-1.0 m/s²").assertExists()
        compose.runOnIdle { telemetry = store.snapshot(1750) }
        compose.onNodeWithText("距离 12 m · 相对速度 3.6 km/h").assertDoesNotExist()
        compose.onNodeWithText("前进 D").assertDoesNotExist()
        compose.onNodeWithText("72.0 km/h").assertDoesNotExist()
        compose.onNodeWithText("-1.0 m/s²").assertDoesNotExist()
        compose.onNodeWithTag("carrot-side-targets").assertDoesNotExist()
        compose.onNodeWithTag("carrot-requested-acceleration").assertExists()
    }
}
