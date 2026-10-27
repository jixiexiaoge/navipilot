package com.jixiexiaoge.drivingassist

import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jixiexiaoge.drivingassist.navigation.NaviSourceTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AmapLaneBroadcastTest {
    @Test
    fun explicitDisabledPayloadWithdrawsEvenWhenLaneArrayStillExists() {
        val fields = mutableStateOf(CarrotManFields())
        val sources = NaviSourceTracker()
        val handlers = AmapBroadcastHandlers(fields, naviSources = sources)
        handlers.handleDriveWayInfo(lanes("""{"drive_way_enabled":true,"drive_way_size":1,
            "drive_way_info":[{"drive_way_lane_Back_icon":"0","trafficLaneAdvised":true,"drive_way_number":0}]}"""))
        assertEquals(1, fields.value.nLaneCount)
        handlers.handleDriveWayInfo(lanes("""{"drive_way_enabled":false,"drive_way_size":1,
            "drive_way_info":[{"drive_way_lane_Back_icon":"0","trafficLaneAdvised":true,"drive_way_number":0}]}"""))
        assertTrue(fields.value.laneInfoList.isEmpty())
        assertNull(sources.snapshot().updatedAt["lane_current"])
    }

    @Test
    fun numericStringRecommendationIsAcceptedButMissingDirectionAndNumberAreNotInvented() {
        val fields = mutableStateOf(CarrotManFields())
        val handlers = AmapBroadcastHandlers(fields)
        handlers.handleDriveWayInfo(lanes("""{"drive_way_enabled":1,"drive_way_size":2,
            "drive_way_info":[{"trafficLaneAdvised":"1"},{"trafficLaneAdvised":"0"}]}"""))
        assertEquals("", fields.value.laneInfoList.first().id)
        assertEquals(-1, fields.value.laneInfoList.first().driveWayNumber)
        assertTrue(fields.value.laneInfoList.first().isRecommended)
        assertFalse(fields.value.laneInfoList.last().isRecommended)
    }

    @Test
    fun malformedTrafficPayloadClearsPreviouslyAcceptedSegments() {
        val fields = mutableStateOf(CarrotManFields())
        val sources = NaviSourceTracker()
        val handlers = AmapBroadcastHandlers(fields, naviSources = sources)
        handlers.handleUnknownInfo13011(Intent().putExtra("EXTRA_TMC_SEGMENT",
            """{"total_distance":1000,"tmc_info":[{"tmc_status":1,"tmc_segment_distance":1000}]}"""))
        assertEquals(1000, sources.snapshot().trafficSegments!!.totalM)
        handlers.handleUnknownInfo13011(Intent().putExtra("EXTRA_TMC_SEGMENT", """{"total_distance":0}"""))
        assertNull(sources.snapshot().trafficSegments)
    }

    private fun lanes(raw: String) = Intent().putExtra("EXTRA_DRIVE_WAY", raw)
}
