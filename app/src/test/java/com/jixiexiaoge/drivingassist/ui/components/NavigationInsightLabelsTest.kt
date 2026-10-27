package com.jixiexiaoge.drivingassist.ui.components

import com.google.common.truth.Truth.assertThat
import com.jixiexiaoge.drivingassist.data.CompactTelemetry
import com.jixiexiaoge.drivingassist.data.ReceivedLead
import com.jixiexiaoge.drivingassist.data.toDisplaySnapshot
import com.jixiexiaoge.drivingassist.data.ReceivedNavigationLanes
import com.jixiexiaoge.drivingassist.data.NavigationReception
import com.jixiexiaoge.drivingassist.CarrotManFields
import com.jixiexiaoge.drivingassist.LaneInfo
import com.jixiexiaoge.drivingassist.navigation.AmapDisplayState
import com.jixiexiaoge.drivingassist.navigation.NaviSourceTracker
import com.jixiexiaoge.drivingassist.navigation.buildAmapNavigationDisplay
import org.junit.Test

class NavigationInsightLabelsTest {
    @Test
    fun `cruise lamp directions never use navigation direction codes`() {
        assertThat(cruiseLampDirectionLabel(1)).isEqualTo("左转")
        assertThat(cruiseLampDirectionLabel(2)).isEqualTo("直行")
        assertThat(cruiseLampDirectionLabel(3)).isEqualTo("右转")
        assertThat(cruiseLampDirectionLabel(4)).isEqualTo("方向未确认")
        assertThat(cruiseLampDirectionLabel(0)).isEqualTo("方向未确认")
    }

    @Test
    fun `lane group comparison needs fresh ordered selected navigation on both sides`() {
        // Given
        val fields = CarrotManFields(isNavigating = true, nLaneCount = 2,
            laneInfoList = listOf(LaneInfo("0", false, driveWayNumber = 0), LaneInfo("0", true, driveWayNumber = 1)))
        val source = NaviSourceTracker().apply { update("lane_current", nowMs = 1000) }
        val map = buildAmapNavigationDisplay(fields, source.snapshot(), "AMAP", 1100)
        val data = CompactTelemetry().toDisplaySnapshot().copy(navigation = NavigationReception.Guiding,
            navigationAvailability = com.jixiexiaoge.drivingassist.data.TelemetryAvailability.Fresh,
            navigationLanes = ReceivedNavigationLanes(2, listOf(1)))
        // When / Then
        assertThat(compareRouteLaneGroups(map, data)).isEqualTo(LaneGroupComparison.Same)
        assertThat(compareRouteLaneGroups(map, data.copy(navigationLanes = ReceivedNavigationLanes(2, listOf(0)))))
            .isEqualTo(LaneGroupComparison.Different)
        assertThat(compareRouteLaneGroups(map.copy(state = AmapDisplayState.Expired), data))
            .isEqualTo(LaneGroupComparison.Unavailable)
        assertThat(compareRouteLaneGroups(map, data.copy(navigation = NavigationReception.OffRoute)))
            .isEqualTo(LaneGroupComparison.Unavailable)
        assertThat(compareRouteLaneGroups(map,
            data.copy(navigationAvailability = com.jixiexiaoge.drivingassist.data.TelemetryAvailability.Expired)))
            .isEqualTo(LaneGroupComparison.Unavailable)
        assertThat(compareRouteLaneGroups(map, data.copy(navigationLanes = ReceivedNavigationLanes(2, null))))
            .isEqualTo(LaneGroupComparison.Unavailable)
        assertThat(compareRouteLaneGroups(map.copy(junction = map.junction!!.copy(leftToRightKnown = false)), data))
            .isEqualTo(LaneGroupComparison.Unavailable)
    }
    @Test
    fun `front absence invalidity and unknown are distinguishable without claiming empty roads`() {
        // Given
        val unknown = CompactTelemetry().toDisplaySnapshot()
        // When / Then
        assertThat(frontTargetLabel(unknown)).contains("未知")
        assertThat(frontTargetLabel(unknown.copy(leadPresent = false))).contains("非道路无车")
        assertThat(frontTargetLabel(unknown.copy(leadPresent = true))).contains("不完整")
        assertThat(frontTargetLabel(unknown.copy(lead = ReceivedLead(10f, 0f)))).contains("有效前车")
        assertThat(blindspotLabel(false)).contains("非安全确认")
        assertThat(blindspotLabel(true)).contains("触发")
        assertThat(blindspotLabel(null)).contains("未知")
    }

    @Test
    fun `all verified planner codes are labeled and unknown never defaults to cruise or green`() {
        // Given / When / Then
        assertThat(behaviorStageLabel(0)).contains("跟车")
        assertThat(behaviorStageLabel(5)).contains("已停车")
        assertThat(behaviorStageLabel(99)).contains("无有效数据")
        assertThat(planningTrafficLabel(0)).contains("不是无灯")
        assertThat(planningTrafficLabel(2)).contains("green")
        assertThat(planningTrafficLabel(null)).contains("无有效数据")
        assertThat(vehicleStyleLabel(1)).isEqualTo("Eco")
        assertThat(vehicleStyleLabel(4)).isEqualTo("High")
        assertThat(vehicleStyleLabel(0)).contains("无有效数据")
    }
}
