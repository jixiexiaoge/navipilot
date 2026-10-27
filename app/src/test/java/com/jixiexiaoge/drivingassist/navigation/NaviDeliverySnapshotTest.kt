package com.jixiexiaoge.drivingassist.navigation

import com.google.common.truth.Truth.assertThat
import com.jixiexiaoge.drivingassist.CarrotManFields
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class NaviDeliverySnapshotTest {
    @Test
    fun `delivery reflects successful enqueue only and hides stale cache on disconnect`() {
        // Given
        var now = 1000L
        val client = mockk<NaviWebSocketV2Client>()
        every { client.isReady() } returns true
        every { client.getSession() } returns NaviV2Session("test", 1,
            NaviV2Constants.JSON_NAMES.associate { name ->
                "json:$name" to StreamConfig("json", name, params = mapOf(
                    "delivery_mode" to "on_change", "interval_ms" to 1000, "stale_timeout_ms" to 2000))
            })
        every { client.sendJsonData(any(), any(), any(), any(), any()) } returns true
        every { client.sendJsonData("guidance_current", true, any(), any(), any()) } returnsMany listOf(false, true)
        val sources = NaviSourceTracker().apply { update("guidance_current", nowMs = now) }
        val manager = NaviStreamManager(client, sources, { now })
        val fields = CarrotManFields(isNavigating = true, nTBTDist = 500, nTBTTurnType = 13)
        // When / Then
        assertThat(manager.deliverySnapshot().ready).isFalse()
        manager.start()
        manager.update(fields)
        assertThat(manager.deliverySnapshot().enqueuedStreams).doesNotContain("guidance_current")
        manager.update(fields)
        assertThat(manager.deliverySnapshot().enqueuedStreams).contains("guidance_current")
        sources.resetGuideDisplay(++now)
        manager.update(fields)
        assertThat(manager.deliverySnapshot().enqueuedStreams).doesNotContain("guidance_current")
        assertThat(manager.deliverySnapshot().withdrawnStreams).contains("guidance_current")
        every { client.isReady() } returns false
        assertThat(manager.deliverySnapshot()).isEqualTo(NaviDeliverySnapshot())
        manager.stop()
        assertThat(manager.deliverySnapshot()).isEqualTo(NaviDeliverySnapshot())
    }

    @Test
    fun `unconfirmed route prevents mode candidates without rewriting the map or vehicle state`() {
        // Given
        val sources = NaviSourceTracker().apply {
            updateGuideExtras(AmapGuideExtras(1000, 1, "Road", "Destination", 10000, 900,
                3, null, null, null, null, null, reportedRoadType = 0))
        }
        val map = buildAmapNavigationDisplay(CarrotManFields(isNavigating = true, noaRouteGeneration = 1),
            sources.snapshot(), "AMAP", 1100)
        // When
        val advice = buildDrivingModeAdvice(DrivingMode.HIGHWAY_EAP, map, routeReady = false)
        // Then
        assertThat(advice.hold).isEqualTo(DrivingAdviceHold.RouteRecovering)
        assertThat(advice.candidateLane).isNull()
        assertThat(map.guideDetails).isNotNull()
    }
}
