package com.jixiexiaoge.drivingassist.ui.components

import com.google.common.truth.Truth.assertThat
import com.jixiexiaoge.drivingassist.data.CompactTelemetry
import com.jixiexiaoge.drivingassist.data.ReceivedLaneChange
import com.jixiexiaoge.drivingassist.data.TelemetryAvailability
import com.jixiexiaoge.drivingassist.data.toDisplaySnapshot
import org.junit.Test

class NavigationFeedbackTest {
    @Test
    fun `lane phase never interprets off missing or expired as command completion`() {
        // Given
        val missing = CompactTelemetry().toDisplaySnapshot()
        // When / Then
        assertThat(laneChangePhaseLabel(missing)).contains("未知")
        val active = missing.copy(laneAvailability = TelemetryAvailability.Fresh,
            laneChange = ReceivedLaneChange("off", "none"))
        assertThat(laneChangePhaseLabel(active)).contains("非已完成确认")
        listOf("preLaneChange" to "准备", "laneChangeStarting" to "变道中",
            "laneChangeFinishing" to "收尾").forEach { (code, label) ->
            assertThat(laneChangePhaseLabel(active.copy(laneChange = ReceivedLaneChange(code, "right")))).contains(label)
        }
        assertThat(laneChangePhaseLabel(active.copy(laneAvailability = TelemetryAvailability.Expired))).contains("失效")
        assertThat(laneChangePhaseLabel(active.copy(laneChange = ReceivedLaneChange("invalid", "left")))).isEqualTo("未知")
    }
}
