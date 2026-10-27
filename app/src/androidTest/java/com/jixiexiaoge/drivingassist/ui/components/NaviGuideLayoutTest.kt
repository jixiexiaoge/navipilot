package com.jixiexiaoge.drivingassist.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.jixiexiaoge.drivingassist.CarrotManFields
import com.jixiexiaoge.drivingassist.data.CompactFrame
import com.jixiexiaoge.drivingassist.data.CompactServiceSample
import com.jixiexiaoge.drivingassist.data.CompactTelemetry
import com.jixiexiaoge.drivingassist.data.DashboardSnapshot
import com.jixiexiaoge.drivingassist.data.buildDashboardSnapshot
import com.jixiexiaoge.drivingassist.navigation.AmapDisplayAction
import com.jixiexiaoge.drivingassist.navigation.AmapJunctionLane
import com.jixiexiaoge.drivingassist.navigation.AmapJunctionLanes
import com.jixiexiaoge.drivingassist.navigation.NaviSourceTracker
import com.jixiexiaoge.drivingassist.navigation.amapLaneDirection
import com.jixiexiaoge.drivingassist.navigation.buildAmapNavigationDisplay
import com.jixiexiaoge.drivingassist.ui.theme.NavipilotTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/**
 * 版式回归：驾驶卡上沿的车道卡位置，以及导航页右侧「道路引导卡」的四段结构。
 *
 * 这两处都只能靠几何位置判断对错（贴不贴顶、压不压转向灯、四行顺序），
 * 纯 JVM 单测拿不到布局，所以在真机/模拟器上量 bounds。
 *
 * 尺寸取真机实测值：驾驶卡 660dp 宽（平板 691dp 屏减去左右 16dp 内边距），
 * 引导卡 323×200dp（半屏宽，高度被左格路口图反推后封顶 200dp）。
 */
class NaviGuideLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private fun snapshot(): DashboardSnapshot {
        val left = mapOf("x" to listOf(0f, 10f, 25f, 45f), "y" to listOf(-1.8f, -1.8f, -1.8f, -1.8f))
        val right = mapOf("x" to listOf(0f, 10f, 25f, 45f), "y" to listOf(1.8f, 1.8f, 1.8f, 1.8f))
        val telemetry = CompactTelemetry(
            mapOf(
                "carState" to CompactServiceSample(CompactFrame("carState", 1, mapOf(
                    "vEgo" to 8f, "gearShifter" to "drive", "steeringAngleDeg" to 12f,
                    "vCruiseCluster" to 60f, "brakeLights" to false,
                    "leftBlinker" to true, "rightBlinker" to false,
                )), 100, 900),
                "carControl" to CompactServiceSample(CompactFrame("carControl", 1, mapOf(
                    "latActive" to true, "longActive" to true,
                )), 100, 900),
                "modelV2" to CompactServiceSample(CompactFrame("modelV2", 1, mapOf(
                    "position" to left,
                    "laneLines" to listOf(left, left, right, right),
                    "laneLineProbs" to listOf(0.9f, 0.9f, 0.9f, 0.9f),
                    "roadEdges" to listOf(left, right),
                )), 100, 900),
            ),
            200,
        )
        return buildDashboardSnapshot(telemetry, 200)
    }

    private fun lanes(): AmapJunctionLanes = AmapJunctionLanes(
        listOf(
            AmapJunctionLane(1, amapLaneDirection("0"), false, "0"),
            AmapJunctionLane(2, amapLaneDirection("2"), false, "2"),
            AmapJunctionLane(3, amapLaneDirection("2"), true, "2"),
            AmapJunctionLane(4, amapLaneDirection("4"), false, "4"),
            AmapJunctionLane(5, amapLaneDirection("3"), false, "3"),
        ),
        true,
        81,
    )

    /** 车道卡与右上角模式角标同在 18u，贴住卡片上沿且不压转向灯 */
    @Test
    fun laneCardSitsOnTopEdge() {
        compose.setContent {
            NavipilotTheme {
                Box(Modifier.width(660.dp).background(Color(0xFF101828))) {
                    DriveViewCard(snapshot = snapshot(), laneGuide = lanes())
                }
            }
        }
        compose.onNodeWithTag("amap-lane-card").assertIsDisplayed()
        val card = compose.onNodeWithTag("drive-view-card").fetchSemanticsNode().boundsInRoot
        val lane = compose.onNodeWithTag("amap-lane-card").fetchSemanticsNode().boundsInRoot
        val pill = compose.onNodeWithTag("drive-view-mode").fetchSemanticsNode().boundsInRoot
        val blinker = compose.onNodeWithTag("drive-view-left-turn").fetchSemanticsNode().boundsInRoot

        // 与模式角标落在同一条顶部带里（两者都按 18u 内缩；角标的语义框因内边距
        // 比实际填充低约 3dp，所以这里比「带」而不是比像素，容差 1.5% 卡高）
        val band = card.height * 0.015f
        assertTrue(
            "车道卡 top=${lane.top} 与角标 top=${pill.top} 不在同一顶部带",
            abs(lane.top - pill.top) <= band,
        )
        // 贴顶：离卡片上沿约 3.8% 卡高（=18u），不能浮在中间
        assertTrue("车道卡离上沿 ${lane.top - card.top}，没有贴近顶部", lane.top - card.top < card.height * 0.06f)
        // 转向灯让到车道卡下方，两者不叠
        assertTrue("转向灯 top=${blinker.top} 与车道卡 bottom=${lane.bottom} 重叠", blinker.top >= lane.bottom)
    }

    /** 有数据时四段齐全，且从上到下依次为本段 / 随后 / 到达时刻 */
    @Test
    fun guidePanelShowsManeuverRoadAndTrip() {
        val sources = NaviSourceTracker().apply { update("guidance_current", "route", nowMs = 1000) }
        val base = buildAmapNavigationDisplay(
            CarrotManFields(isNavigating = true, noaRouteGeneration = 1),
            sources.snapshot(), "AMAP", 1200,
        ).copy(
            current = AmapDisplayAction("右转", "阜安西路", 81, maneuverIcon = 3),
            next = AmapDisplayAction("掉头", "南三环西路", 106, maneuverIcon = 8),
            remainingM = 33000,
            remainingSec = 4020,
            arrivalAtMs = System.currentTimeMillis() + 4_020_000,
        )
        compose.setContent {
            NavipilotTheme {
                Box(Modifier.width(323.dp).height(200.dp).background(Color(0xFF101828))) {
                    NaviGuidePanel(display = base, modifier = Modifier.fillMaxSize())
                }
            }
        }
        compose.onNodeWithTag("navi-guide-panel").assertIsDisplayed()
        val distance = compose.onNodeWithTag("navi-guide-distance").fetchSemanticsNode().boundsInRoot
        val road = compose.onNodeWithTag("navi-guide-road").fetchSemanticsNode().boundsInRoot
        val next = compose.onNodeWithTag("navi-guide-next").fetchSemanticsNode().boundsInRoot
        val trip = compose.onNodeWithTag("navi-guide-trip").fetchSemanticsNode().boundsInRoot
        val eta = compose.onNodeWithTag("navi-guide-eta").fetchSemanticsNode().boundsInRoot

        assertTrue("距离应在道路名上方", distance.bottom <= road.top + 1f)
        assertTrue("道路名应在随后一档上方", road.bottom <= next.top)
        assertTrue("随后一档应在底行上方", next.bottom <= trip.top)
        // 到达时刻与总里程同一行
        assertTrue("到达时刻应与底行同行", abs(eta.top - trip.top) <= eta.height)
    }

    /** 没接导航时给一句明确提示，不留空白假占位 */
    @Test
    fun guidePanelExplainsEmptyState() {
        compose.setContent {
            NavipilotTheme {
                Box(Modifier.width(323.dp).height(200.dp).background(Color(0xFF101828))) {
                    NaviGuidePanel(
                        display = buildAmapNavigationDisplay(
                            CarrotManFields(), NaviSourceTracker().snapshot(), "AMAP", 1000,
                        ),
                        modifier = Modifier.fillMaxSize().testTag("tmp"),
                    )
                }
            }
        }
        compose.onNodeWithTag("navi-guide-hint").assertIsDisplayed()
    }
}
