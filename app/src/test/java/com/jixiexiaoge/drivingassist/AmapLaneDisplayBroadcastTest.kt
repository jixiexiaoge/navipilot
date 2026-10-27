package com.jixiexiaoge.drivingassist

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.google.common.truth.Truth.assertThat
import com.jixiexiaoge.drivingassist.navigation.NaviSourceTracker
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
import org.junit.Test

class AmapLaneDisplayBroadcastTest {
    private val fields = mutableStateOf(CarrotManFields(isNavigating = true, noaRouteGeneration = 1))
    private val sources = NaviSourceTracker()
    private var time = 1000L
    private var selected = true
    private val handlers = AmapBroadcastHandlers(fields, naviSources = sources,
        isAmapSelected = { selected }, nowMs = { time })
    private val enabled = """{"drive_way_enabled":true,"drive_way_size":1,
        "drive_way_info":[{"drive_way_lane_Back_icon":"0","trafficLaneAdvised":true,"drive_way_number":0}]}"""

    @Before
    fun mockLogging() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun restoreLogging() = unmockkStatic(Log::class)

    @Test
    fun `explicit enabled lanes capture original receipt generation and raw direction`() {
        // Given / When
        handlers.handleDriveWayInfo(payload(enabled))
        // Then
        val snapshot = sources.snapshot().laneDisplay!!
        assertThat(snapshot.receivedAtMs).isEqualTo(time)
        assertThat(snapshot.routeGeneration).isEqualTo(1)
        assertThat(snapshot.lanes).isEqualTo(fields.value.laneInfoList)
        assertThat(snapshot.lanes.single().isRecommended).isTrue()
        assertThat(sources.snapshot().updatedAt["lane_current"]).isEqualTo(time)
    }

    @Test
    fun `disable empty unknown validity count mismatch and malformed lane packets clear retained snapshot`() {
        // Given / When / Then
        listOf(enabled.replace(":true,", ":false,"),
            enabled.replace(":true,", """: "unknown","""),
            enabled.replace("\"drive_way_size\":1", "\"drive_way_size\":2"),
            """{"drive_way_enabled":true,"drive_way_size":0,"drive_way_info":[]}""",
            "{broken", "").forEach { invalid ->
            time++
            handlers.handleDriveWayInfo(payload(enabled))
            assertThat(sources.snapshot().laneDisplay).isNotNull()
            time++
            handlers.handleDriveWayInfo(payload(invalid))
            assertThat(sources.snapshot().laneDisplay).isNull()
            assertThat(sources.snapshot().updatedAt["lane_current"]).isNull()
            assertThat(fields.value.laneInfoList).isEmpty()
        }
    }

    @Test
    fun `missing explicit enable never permits persistent display even when legacy lanes parse`() {
        // Given
        handlers.handleDriveWayInfo(payload(enabled))
        time++
        // When
        handlers.handleDriveWayInfo(payload(enabled.replace("\"drive_way_enabled\":true,", "")))
        // Then
        assertThat(fields.value.nLaneCount).isEqualTo(1)
        assertThat(sources.snapshot().laneDisplay).isNull()
        assertThat(sources.snapshot().updatedAt["lane_current"]).isEqualTo(time)
    }

    @Test
    fun `inactive source stopped navigation and equal route boundary cannot accept display cache`() {
        // Given / When / Then
        selected = false
        handlers.handleDriveWayInfo(payload(enabled))
        assertThat(sources.snapshot().laneDisplay).isNull()
        assertThat(sources.snapshot().updatedAt["lane_current"]).isNull()
        selected = true
        fields.value = fields.value.copy(isNavigating = false)
        handlers.handleDriveWayInfo(payload(enabled))
        assertThat(sources.snapshot().laneDisplay).isNull()
        fields.value = fields.value.copy(isNavigating = true, noaRouteGeneration = 2)
        sources.resetGuideDisplay(time)
        handlers.handleDriveWayInfo(payload(enabled))
        assertThat(sources.snapshot().laneDisplay).isNull()
        time++
        handlers.handleDriveWayInfo(payload(enabled))
        assertThat(sources.snapshot().laneDisplay!!.routeGeneration).isEqualTo(2)
    }

    @Suppress("DEPRECATION")
    private fun payload(raw: String): Intent {
        val bundle = mockk<Bundle>()
        every { bundle.get(any()) } answers { if (firstArg<String>() == "EXTRA_DRIVE_WAY") raw else null }
        every { bundle.keySet() } returns setOf("EXTRA_DRIVE_WAY")
        return mockk<Intent>(relaxed = true).also {
            every { it.extras } returns bundle
            every { it.hasExtra(any()) } returns false
        }
    }
}
