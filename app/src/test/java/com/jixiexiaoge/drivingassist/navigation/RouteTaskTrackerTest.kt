package com.jixiexiaoge.drivingassist.navigation

import com.google.common.truth.Truth.assertThat
import com.jixiexiaoge.drivingassist.CarrotManFields
import com.jixiexiaoge.drivingassist.data.NavigationReception
import org.junit.Test

class RouteTaskTrackerTest {
    private fun map(receipt: Long = 1000, generation: Long = 1): AmapNavigationDisplay {
        val fields = CarrotManFields(isNavigating = true, noaRouteGeneration = generation)
        val sources = NaviSourceTracker().apply {
            updateGuideExtras(AmapGuideExtras(receipt, generation, "道路", "目的地", 10000, 900,
                3, 2, "出口A", null, null, null))
        }
        return buildAmapNavigationDisplay(fields, sources.snapshot(), "AMAP", receipt).copy(
            current = AmapDisplayAction("右转", "出口路", 500, 60),
            next = AmapDisplayAction("左转", "随后道路", 100, 20),
        )
    }

    @Test
    fun `replay missed exit withdraws tasks and needs fresh vehicle guidance and distinct map packets`() {
        // Given
        val tracker = RouteTaskTracker()
        val first = map()
        assertThat(tracker.update(first, NavigationReception.Guiding, false, 1000).ready).isTrue()
        // When / Then
        val off = tracker.update(first, NavigationReception.OffRoute, false, 1200)
        assertThat(off.phase).isEqualTo(RouteTaskPhase.OffRoute)
        assertThat(off.current).isNull()
        assertThat(off.destination).isNull()
        assertThat(tracker.update(first, NavigationReception.Guiding, false, 1250).ready).isFalse()
        assertThat(tracker.update(map(1300), NavigationReception.Unknown, false, 1300).ready).isFalse()
        val pending = tracker.update(map(1400), NavigationReception.Guiding, false, 1400)
        assertThat(pending.phase).isEqualTo(RouteTaskPhase.Recovering)
        repeat(10) {
            assertThat(tracker.update(map(1400), NavigationReception.Guiding, false, 1500).confirmationPackets)
                .isEqualTo(1)
        }
        val recovered = tracker.update(map(1600), NavigationReception.Guiding, false, 1600)
        assertThat(recovered.ready).isTrue()
        assertThat(recovered.recoveredWithoutRouteChange).isTrue()
        assertThat(recovered.newRouteObserved).isFalse()
    }

    @Test
    fun `replay route change withholds old route then confirms two new guides`() {
        // Given
        val tracker = RouteTaskTracker()
        tracker.update(map(), NavigationReception.Unknown, false, 1000)
        // When / Then
        assertThat(tracker.update(map(2000, 2), NavigationReception.Unknown, false, 2000).ready).isFalse()
        assertThat(tracker.state.current).isNull()
        val updated = tracker.update(map(3000, 2), NavigationReception.Unknown, false, 3000)
        assertThat(updated.ready).isTrue()
        assertThat(updated.newRouteObserved).isTrue()
        assertThat(updated.routeGeneration).isEqualTo(2)
        assertThat(updated.validUntilMs).isEqualTo(13000)
        assertThat(updated.closelySpacedActions).isTrue()
    }

    @Test
    fun `replay expiry unrelated refresh clock rollback and stopped navigation never retain tasks`() {
        // Given
        val tracker = RouteTaskTracker()
        val display = map()
        tracker.update(display, NavigationReception.Guiding, false, 1000)
        // When / Then
        assertThat(tracker.update(display, NavigationReception.Guiding, false, 11001).ready).isFalse()
        assertThat(tracker.state.current).isNull()
        assertThat(tracker.update(display.copy(state = AmapDisplayState.Expired),
            NavigationReception.Guiding, false, 12000).phase).isEqualTo(RouteTaskPhase.Expired)
        assertThat(tracker.update(display, NavigationReception.Guiding, false, 999).ready).isFalse()
        assertThat(tracker.update(display.copy(state = AmapDisplayState.Idle),
            NavigationReception.OffRoute, false, 1500).phase).isEqualTo(RouteTaskPhase.Idle)
        assertThat(tracker.state.routeGeneration).isNull()
    }

    @Test
    fun `alert and malformed route evidence cannot resume using the previous guide`() {
        // Given
        val tracker = RouteTaskTracker()
        tracker.update(map(), NavigationReception.Guiding, false, 1000)
        // When / Then
        assertThat(tracker.update(map(), NavigationReception.Guiding, true, 1200).phase)
            .isEqualTo(RouteTaskPhase.VehicleAlert)
        assertThat(tracker.update(map(), NavigationReception.Guiding, false, 1300).ready).isFalse()
        assertThat(tracker.update(map(1400).copy(routeGeneration = 2),
            NavigationReception.Guiding, false, 1400).ready).isFalse()
        assertThat(tracker.state.next).isNull()
        assertThat(tracker.update(map(1500).copy(guideDetails = null),
            NavigationReception.Guiding, false, 1500).ready).isFalse()
    }

    @Test
    fun `short following segment requires actual timing not guessed distance or label`() {
        // Given
        val tracker = RouteTaskTracker()
        // When / Then
        assertThat(tracker.update(map().copy(next = map().next?.copy(remainingSec = null)),
            NavigationReception.Unknown, false, 1000).closelySpacedActions).isFalse()
        assertThat(tracker.update(map(2000).copy(next = map().next?.copy(remainingSec = 31)),
            NavigationReception.Unknown, false, 2000).closelySpacedActions).isFalse()
    }

    @Test
    fun `new guide older than accepted receipt cannot replace task after polling or invalidation`() {
        // Given
        val tracker = RouteTaskTracker()
        tracker.update(map(2000), NavigationReception.Unknown, false, 2000)
        // When / Then
        assertThat(tracker.update(map(1500), NavigationReception.Unknown, false, 2500).ready).isFalse()
        assertThat(tracker.state.current).isNull()
    }

    @Test
    fun `route boundary withdraws old publisher clocks including speed and lane data`() {
        // Given
        val sources = NaviSourceTracker().apply {
            update("guidance_current", "guidance_next", "route", "lane_current", "road_category",
                "sdi", "sdi_secondary", "section", nowMs = 1000)
            updatePosition(NaviPositionSample(31.0, 120.0, null, null, 1000), true)
        }
        // When
        sources.resetGuideDisplay(2000)
        // Then
        val snapshot = sources.snapshot()
        listOf("guidance_current", "guidance_next", "route", "lane_current", "road_category",
            "sdi", "sdi_secondary", "section").forEach {
            assertThat(snapshot.isFresh(it, 2000, 10000)).isFalse()
        }
        assertThat(snapshot.mapPosition).isNotNull()
        assertThat(snapshot.updatedAt["ui_route_changed"]).isEqualTo(2000)
    }

    @Test
    fun `expiry and alert recovery need two newly received guides`() {
        // Given / When / Then
        listOf(false, true).forEach { alert ->
            val tracker = RouteTaskTracker()
            tracker.update(map(), NavigationReception.Unknown, false, 1000)
            tracker.update(map().copy(state = if (alert) AmapDisplayState.Fresh else AmapDisplayState.Expired),
                NavigationReception.Unknown, alert, 12000)
            val first = tracker.update(map(13000), NavigationReception.Unknown, false, 13000)
            assertThat(first.ready).isFalse()
            assertThat(first.confirmationPackets).isEqualTo(1)
            assertThat(tracker.update(map(14000), NavigationReception.Unknown, false, 14000).ready).isTrue()
        }
    }

    @Test
    fun `temporary missing guide cannot recycle previous accepted packet as recovery evidence`() {
        // Given
        val tracker = RouteTaskTracker()
        val previous = map(1000)
        tracker.update(previous, NavigationReception.Unknown, false, 1000)
        tracker.update(previous.copy(guideDetails = null), NavigationReception.Unknown, false, 1200)
        // When / Then
        assertThat(tracker.update(previous, NavigationReception.Unknown, false, 1300).confirmationPackets).isEqualTo(0)
        assertThat(tracker.update(map(1400), NavigationReception.Unknown, false, 1400).ready).isFalse()
        assertThat(tracker.update(map(1500), NavigationReception.Unknown, false, 1500).ready).isTrue()
    }

    @Test
    fun `reordered pending packet cannot replace distinct recovery confirmation`() {
        // Given
        val tracker = RouteTaskTracker()
        tracker.update(map(), NavigationReception.Unknown, false, 1000)
        tracker.update(map(2000, 2), NavigationReception.Unknown, false, 2000)
        // When / Then
        assertThat(tracker.update(map(1500, 2), NavigationReception.Unknown, false, 2200).ready).isFalse()
        assertThat(tracker.update(map(2000, 2), NavigationReception.Unknown, false, 2300).ready).isFalse()
        assertThat(tracker.update(map(2500, 2), NavigationReception.Unknown, false, 2500).ready).isFalse()
        assertThat(tracker.update(map(3000, 2), NavigationReception.Unknown, false, 3000).ready).isTrue()
    }

    @Test
    fun `clock rollback must not restore old accepted task without new packets`() {
        // Given
        val tracker = RouteTaskTracker()
        val previous = map(1000)
        tracker.update(previous, NavigationReception.Unknown, false, 1000)
        tracker.update(previous, NavigationReception.Unknown, false, 5000)
        // When / Then
        assertThat(tracker.update(previous, NavigationReception.Unknown, false, 4000).ready).isFalse()
        assertThat(tracker.update(map(4500), NavigationReception.Unknown, false, 4500).ready).isFalse()
        assertThat(tracker.update(map(4600), NavigationReception.Unknown, false, 4600).ready).isTrue()
    }

    @Test
    fun `clock rollback cannot clear vehicle off route latch`() {
        // Given
        val tracker = RouteTaskTracker()
        tracker.update(map(), NavigationReception.Guiding, false, 1000)
        tracker.update(map(), NavigationReception.OffRoute, false, 5000)
        // When / Then
        assertThat(tracker.update(map(4000), NavigationReception.Unknown, false, 4000).ready).isFalse()
        assertThat(tracker.update(map(4500), NavigationReception.Unknown, false, 4500).ready).isFalse()
        assertThat(tracker.update(map(4600), NavigationReception.Disconnected, false, 4600).ready).isFalse()
        assertThat(tracker.update(map(4700), NavigationReception.Guiding, false, 4700).ready).isFalse()
        assertThat(tracker.update(map(4800), NavigationReception.Guiding, false, 4800).ready).isTrue()
    }
}
