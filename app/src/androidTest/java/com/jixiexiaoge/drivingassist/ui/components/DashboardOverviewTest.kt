package com.jixiexiaoge.drivingassist.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.Density
import com.jixiexiaoge.drivingassist.data.CompactFrame
import com.jixiexiaoge.drivingassist.data.CompactServiceSample
import com.jixiexiaoge.drivingassist.data.CompactTelemetry
import com.jixiexiaoge.drivingassist.data.buildDashboardSnapshot
import com.jixiexiaoge.drivingassist.data.toDisplaySnapshot
import com.jixiexiaoge.drivingassist.data.toOvertakeDisplaySnapshot
import com.jixiexiaoge.drivingassist.ui.theme.NavipilotTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue

class DashboardOverviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun boardIsFirstTabAndKeepsReadableUnknownStatesAtLargeFont() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                NavipilotTheme {
                    val snapshot = buildDashboardSnapshot(null, 1000)
                    HomeDashboardTabs { section ->
                        // 实时页内部自带滚动，这里用同样的滚动容器包一层
                    if (section == 0) Column(Modifier.verticalScroll(rememberScrollState())) {
                        DashboardOverview(snapshot)
                    }
                    }
                }
            }
        }
        compose.onNodeWithTag("dashboard-overview").assertExists()
        // 只有 实时 / 控制 两页（指南页与右侧侧栏已删除）。
        // 7.9.44 起导航页整块并入实时页。
        // 7.9.45 起驾驶卡左上角的分区按钮也删掉了：进控制页只点方向盘，
        // 卡上（以及本测试这种只渲染看板内容的页面）不再出现任何分区 pill。
        compose.onNodeWithTag("section-tabs-in-card").assertDoesNotExist()
        compose.onNodeWithTag("home-section-1").assertDoesNotExist()
        compose.onNodeWithTag("home-section-0").assertDoesNotExist()
        compose.onNodeWithTag("home-section-2").assertDoesNotExist()
        // 缺数据不再隐藏：数值位显示占位符，避免卡片随数据到达而抖动
        compose.onNode(hasTestTag("drive-view-speed") and hasText("-")).assertExists()
        compose.onNode(hasText("-") and hasAnyAncestor(hasTestTag("drive-view-gear"))).assertExists()
        compose.onNode(
            hasText("未知", substring = true) and hasAnyAncestor(hasTestTag("drive-view-card")),
        ).assertDoesNotExist()
        compose.onNodeWithTag("dashboard-instrument-tesla").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-panel-tesla").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-panel-plan").performScrollTo().assertIsDisplayed()
        // 方向盘是卡上唯一的「进控制页」入口
        compose.onNodeWithTag("drive-view-wheel").assertExists()
    }

    @Test
    fun heroWithdrawsExpiredVehicleButPreservesFreshCriticalAlert() {
        val telemetry = CompactTelemetry(mapOf(
            "carState" to CompactServiceSample(CompactFrame("carState", 1, mapOf("vEgo" to 10f)), 100, 600),
            "selfdriveState" to CompactServiceSample(CompactFrame("selfdriveState", 1, mapOf(
                "alertStatus" to "critical", "alertText1" to "请接管驾驶", "alertText2" to "车端原始告警详情",
            )), 100, 1600),
        ), 500)
        val snapshot = mutableStateOf(buildDashboardSnapshot(telemetry, 500))
        compose.setContent {
            NavipilotTheme {
                HomeDashboardTabs { section ->
                    if (section == 0) Column(Modifier.verticalScroll(rememberScrollState())) {
                        DashboardOverview(snapshot.value)
                    }
                }
            }
        }
        val heroSpeed = hasTestTag("drive-view-speed") and hasText("36")
        compose.onNode(heroSpeed).assertExists()
        compose.runOnIdle { snapshot.value = buildDashboardSnapshot(telemetry, 600) }
        compose.onNode(heroSpeed).assertDoesNotExist()
        compose.onNode(hasTestTag("drive-view-speed") and hasText("-")).assertExists()
        compose.onAllNodesWithText("请接管驾驶")[0].performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("车端原始告警详情")[0].performScrollTo().assertIsDisplayed()
    }

    @Test
    fun removedDetailSectionsStayHiddenWithoutChangingSnapshotData() {
        val curve = mapOf("x" to listOf(0f, 20f, 80f), "y" to listOf(0f, 1f, 2f))
        val telemetry = CompactTelemetry(mapOf(
            "modelV2" to CompactServiceSample(CompactFrame("modelV2", 1, mapOf("position" to curve)), 100, 850),
            "controlsState" to CompactServiceSample(CompactFrame("controlsState", 1, mapOf(
                "activeLaneLine" to true,
            )), 100, 850),
            "radarState" to CompactServiceSample(CompactFrame("radarState", 1, mapOf(
                "leadOne" to mapOf("status" to true, "dRel" to 25f, "yRel" to 0f, "vRel" to -1f),
            )), 100, 850),
        ), 500)
        val snapshot = buildDashboardSnapshot(telemetry, 500)
        assertTrue(snapshot.panels.any { it.id == "model" })
        assertTrue(snapshot.panels.any { it.id == "lateral" })
        assertTrue(snapshot.panels.any { it.id == "radar" })
        compose.setContent {
            NavipilotTheme {
                HomeDashboardTabs { section ->
                    // 实时页内部自带滚动，这里用同样的滚动容器包一层
                    if (section == 0) Column(Modifier.verticalScroll(rememberScrollState())) {
                        DashboardOverview(snapshot)
                    }
                }
            }
        }
        compose.onNodeWithTag("dashboard-road-plot").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-road-unavailable").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-panel-model").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-panel-lateral").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-panel-radar").assertDoesNotExist()
    }

    @Test
    fun instrumentsUseOneHorizontallyScrollableRowAndOnlyPlanningDetailsRemain() {
        val telemetry = CompactTelemetry(mapOf(
            "carState" to CompactServiceSample(CompactFrame("carState", 1, mapOf(
                "vEgo" to 20f,
                "vCruiseCluster" to 100f,
                "gearShifter" to "drive",
                "leftBlinker" to true,
                "rightBlinker" to true,
                "brakeLights" to true,
            )), 100, 1000),
            "carrotMan" to CompactServiceSample(CompactFrame("carrotMan", 1, mapOf(
                "desiredSpeed" to 88,
                "nRoadLimitSpeed" to 80,
            )), 100, 1600),
        ), 500)
        compose.setContent {
            NavipilotTheme {
                val snapshot = buildDashboardSnapshot(telemetry, 500)
                HomeDashboardTabs { section ->
                    // 实时页内部自带滚动，这里用同样的滚动容器包一层
                    if (section == 0) Column(Modifier.verticalScroll(rememberScrollState())) {
                        DashboardOverview(snapshot)
                    }
                }
            }
        }
        // 顶部标题栏不再挂仪表：全部读数收进驾驶视图卡片
        val card = compose.onNodeWithTag("drive-view-card").getUnclippedBoundsInRoot()
        listOf("drive-view-speed", "drive-view-gear", "drive-view-limit", "drive-view-acc")
            .forEach { tag ->
                val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
                assertTrue("$tag 必须落在驾驶视图卡片内", bounds.top >= card.top && bounds.bottom <= card.bottom)
            }
        compose.onNodeWithTag("drive-view-left-turn").assertIsDisplayed()
        compose.onNodeWithTag("drive-view-speed").assertIsDisplayed()
        // 「运营摘要」整块已删除（三格 / 当前引导 / 纵向加速度），只剩告警横幅 + 规划与控制
        compose.onNodeWithTag("dashboard-instrument-navigation").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-instrument-motion").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-group-plan").assertIsDisplayed()
        compose.onNodeWithTag("dashboard-road-unavailable").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-panel-plan").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("dashboard-panel-vehicle").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-panel-extras").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-panel-device").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-panel-position").assertDoesNotExist()
    }

    @Test
    fun topInstrumentsPrioritizeDrivingSignalsSpeedPolicyAndDeviceHealth() {
        val telemetry = CompactTelemetry(mapOf(
            "carState" to CompactServiceSample(CompactFrame("carState", 1, mapOf(
                "vEgo" to 20f,
                "vCruiseCluster" to 100f,
                "gearShifter" to "drive",
                "leftBlinker" to true,
                "rightBlinker" to true,
                "brakeLights" to true,
            )), 100, 1000),
            "carrotMan" to CompactServiceSample(CompactFrame("carrotMan", 1, mapOf(
                "desiredSpeed" to 88,
                "nRoadLimitSpeed" to 80,
            )), 100, 1600),
        ), 500)
        compose.setContent {
            NavipilotTheme {
                val snapshot = buildDashboardSnapshot(telemetry, 500)
                HomeDashboardTabs { section ->
                    // 实时页内部自带滚动，这里用同样的滚动容器包一层
                    if (section == 0) Column(Modifier.verticalScroll(rememberScrollState())) {
                        DashboardOverview(snapshot)
                    }
                }
            }
        }
        // 车速 → 左侧大号数字
        compose.onNode(hasTestTag("drive-view-speed") and hasText("72")).assertExists()
        // 定速 → 右上 ACC 位（原版 #accSetSpeed 的位置）
        compose.onNode(hasText("100") and hasAnyAncestor(hasTestTag("drive-view-acc"))).assertExists()
        // 挡位 → 灰底徽标
        compose.onNode(hasText("D") and hasAnyAncestor(hasTestTag("drive-view-gear"))).assertExists()
        // 建议速度 → 左下文本区（原版 #bottomLeft）
        compose.onNode(hasTestTag("drive-view-suggested") and hasText("88", substring = true)).assertExists()
        // 采用限速 → 右上红圈
        compose.onNode(hasText("80") and hasAnyAncestor(hasTestTag("drive-view-limit"))).assertExists()
        compose.onNodeWithTag("dashboard-instrument-cpu").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-instrument-memory").assertDoesNotExist()
        compose.onNodeWithTag("drive-view-left-turn").assertExists()
        compose.onNodeWithTag("drive-view-right-turn").assertExists()
        compose.onNodeWithTag("drive-view-brake").assertExists()
    }

    @Test
    fun navigationDetailsMoveToNavigationWhileBoardDropsRepeatedAndRemovedGroups() {
        val snapshot = buildDashboardSnapshot(null, 1000)
        compose.setContent {
            NavipilotTheme {
                HomeDashboardTabs { section ->
                    when (section) {
                        0 -> Column(Modifier.verticalScroll(rememberScrollState())) {
                            // 卡内分区按钮已删除（7.9.45），这里用页面级分区按钮切到控制页
                            OnPage()
                            DashboardOverview(snapshot)
                        }
                        1 -> androidx.compose.foundation.layout.Column {
                            androidx.compose.material3.Text("7000 回传核对", Modifier.testTag("navigation-amap-content"))
                            NavigationAndLimitsDetails(snapshot)
                        }
                    }
                }
            }
        }
        listOf("result", "navigation", "navi-events", "navi-signal", "vehicle", "sides",
            "model", "lateral", "radar", "extras", "device", "position").forEach {
            compose.onNodeWithTag("dashboard-panel-$it").assertDoesNotExist()
        }
        compose.onNodeWithTag("dashboard-panel-plan").performScrollTo().assertIsDisplayed()
        // 控制页只能从页面上的分区按钮（或驾驶卡方向盘）进入
        compose.onNodeWithTag("home-section-1").performClick()
        compose.onNodeWithTag("navigation-amap-content").assertExists()
        compose.onNodeWithTag("dashboard-panel-result").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("dashboard-panel-navigation").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("dashboard-panel-navi-events").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("dashboard-panel-navi-signal").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun otherPagesRetainGeometryAndRequestStatusWithoutRepeatedTelemetry() {
        compose.setContent {
            NavipilotTheme {
                val snapshot = buildDashboardSnapshot(null, 1000)
                HomeDashboardTabs { section ->
                    when (section) {
                        1 -> androidx.compose.foundation.layout.Column {
                            DrivingModeVehicleCard(null, null.toDisplaySnapshot(), geometryOnly = true)
                            OvertakeStatusCard(null.toOvertakeDisplaySnapshot(), showObservations = false)
                        }
                        0 -> androidx.compose.foundation.layout.Column {
                            OnPage()
                            DashboardOverview(snapshot)
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("home-section-1").performClick()
        compose.onNodeWithTag("lane-geometry").assertExists()
        compose.onNodeWithTag("mode-lateral").assertDoesNotExist()
        compose.onNodeWithTag("mode-longitudinal").assertDoesNotExist()
        compose.onNodeWithTag("home-section-1").performClick()
        compose.onNodeWithTag("overtake-status").assertExists()
        compose.onNodeWithTag("overtake-left").assertDoesNotExist()
        compose.onNodeWithTag("overtake-right").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-panel-sides").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-panel-vehicle").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-panel-radar").assertDoesNotExist()
    }
}
