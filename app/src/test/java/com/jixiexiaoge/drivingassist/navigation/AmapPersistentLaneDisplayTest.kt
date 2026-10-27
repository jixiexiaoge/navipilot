package com.jixiexiaoge.drivingassist.navigation

import com.google.common.truth.Truth.assertThat
import com.jixiexiaoge.drivingassist.CarrotManFields
import com.jixiexiaoge.drivingassist.LaneInfo
import org.junit.Test

class AmapPersistentLaneDisplayTest {
    private val lanes = listOf(LaneInfo("0", true, driveWayNumber = 0), LaneInfo("999", false, driveWayNumber = 1))
    private val fields = CarrotManFields(isNavigating = true, noaRouteGeneration = 1,
        nLaneCount = 2, laneInfoList = lanes)

    private fun guide(sources: NaviSourceTracker, at: Long, generation: Long = 1) {
        sources.updateGuideExtras(AmapGuideExtras(at, generation, "Road", "Destination",
            10000, 600, 3, null, null, null, null, null, reportedRoadType = 0))
        sources.update("guidance_current", nowMs = at)
    }

    private fun sources() = NaviSourceTracker().apply {
        guide(this, 1000)
        update("lane_current", nowMs = 1001)
        updateLaneDisplay(lanes, 1, 1001)
    }

    private fun continuing() = sources().apply {
        guide(this, 9000)
        guide(this, 14000)
    }

    @Test
    fun `ongoing actual guides retain graphics without refreshing lane advice or receipt`() {
        // Given
        val sources = continuing()
        // When
        val display = buildAmapNavigationDisplay(fields, sources.sampleReadOnly(14000, true), "AMAP", 14000)
        sources.sampleReadOnly(14500, true)
        // Then
        assertThat(display.state).isEqualTo(AmapDisplayState.Fresh)
        assertThat(display.displayJunctionRetained).isTrue()
        assertThat(display.displayJunction!!.lanes.map { it.rawIcon }).containsExactly("0", "999").inOrder()
        assertThat(display.displayJunction!!.lanes.first().recommended).isTrue()
        assertThat(display.displayJunction!!.lanes.last().direction).isNull()
        assertThat(display.displayJunction!!.actionDistanceM).isNull()
        assertThat(display.junction).isNull()
        assertThat(display.lanes).isNull()
        assertThat(buildDrivingModeAdvice(DrivingMode.HIGHWAY_EAP, display).candidateLane).isNull()
        assertThat(sources.snapshot().updatedAt["lane_current"]).isEqualTo(1001)
        assertThat(sources.snapshot().laneDisplay!!.receivedAtMs).isEqualTo(1001)
    }

    @Test
    fun `TTL boundary keeps live advice and only beyond boundary changes display label`() {
        // Given
        val sources = sources().apply { guide(this, 9000) }
        // When / Then
        val live = buildAmapNavigationDisplay(fields, sources.snapshot(), "AMAP", 11001)
        assertThat(live.junction).isNotNull()
        assertThat(live.displayJunction).isEqualTo(live.junction)
        assertThat(live.displayJunctionRetained).isFalse()
        val retained = buildAmapNavigationDisplay(fields, sources.snapshot(), "AMAP", 11002)
        assertThat(retained.junction).isNull()
        assertThat(retained.displayJunctionRetained).isTrue()
    }

    @Test
    fun `disabled empty invalid and legacy preference restored fields cannot display retained graphics`() {
        // Given / When / Then
        listOf(fields.copy(laneInfoFromCache = true), fields.copy(nLaneCount = 0, laneInfoList = emptyList()),
            fields.copy(nLaneCount = 3)).forEach {
            assertThat(buildAmapNavigationDisplay(it, continuing().snapshot(), "AMAP", 14000).displayJunction).isNull()
        }
        val sources = continuing()
        sources.invalidate("lane_current")
        guide(sources, 15000)
        assertThat(sources.snapshot().laneDisplay).isNull()
        assertThat(buildAmapNavigationDisplay(fields, sources.snapshot(), "AMAP", 15000).displayJunction).isNull()
    }

    @Test
    fun `route generation and strict equal receipt route boundary reject old graphics`() {
        // Given
        val sources = continuing()
        // When / Then
        assertThat(buildAmapNavigationDisplay(fields.copy(noaRouteGeneration = 2),
            sources.snapshot(), "AMAP", 14000).displayJunction).isNull()
        sources.resetGuideDisplay(14000)
        guide(sources, 14000, 2)
        sources.update("lane_current", nowMs = 14000)
        sources.updateLaneDisplay(lanes, 2, 14000)
        assertThat(sources.snapshot().laneDisplay).isNull()
        val changed = fields.copy(noaRouteGeneration = 2)
        assertThat(buildAmapNavigationDisplay(changed, sources.snapshot(), "AMAP", 14000).displayJunction).isNull()
        sources.update("lane_current", nowMs = 14001)
        sources.updateLaneDisplay(lanes, 2, 14001)
        assertThat(sources.snapshot().laneDisplay!!.routeGeneration).isEqualTo(2)
        guide(sources, 23000, 2)
        assertThat(buildAmapNavigationDisplay(changed, sources.snapshot(), "AMAP", 24002).displayJunctionRetained).isTrue()
    }

    @Test
    fun `stop and source switch permanently clear retained graphics`() {
        // Given / When / Then
        val stopped = continuing()
        assertThat(buildAmapNavigationDisplay(fields.copy(isNavigating = false),
            stopped.snapshot(), "AMAP", 14000).displayJunction).isNull()
        stopped.stopNavigation()
        guide(stopped, 15000)
        assertThat(stopped.snapshot().laneDisplay).isNull()
        val switched = continuing()
        assertThat(buildAmapNavigationDisplay(fields, switched.snapshot(), "OTHER", 14000).displayJunction).isNull()
        switched.sampleReadOnly(14000, false)
        guide(switched, 15000)
        assertThat(buildAmapNavigationDisplay(fields, switched.snapshot(), "AMAP", 15000).displayJunction).isNull()
    }

    @Test
    fun `unrelated broadcasts cannot keep dead actual navigation display alive`() {
        // Given
        val sources = continuing()
        sources.update("route", "vehicle", "road_category", nowMs = 24001)
        // When / Then
        assertThat(buildAmapNavigationDisplay(fields, sources.snapshot(), "AMAP", 24001).displayJunction).isNull()
        assertThat(sources.sampleReadOnly(24001, true).laneDisplay).isNull()
        guide(sources, 25000)
        assertThat(buildAmapNavigationDisplay(fields, sources.snapshot(), "AMAP", 25000).displayJunction).isNull()
    }

    @Test
    fun `guide gap generation change and receipt rollback clear cache without needing UI polling`() {
        // Given / When / Then
        listOf(24001L to 1L, 15000L to 2L, 13999L to 1L).forEach { (at, generation) ->
            val sources = continuing()
            guide(sources, at, generation)
            assertThat(sources.snapshot().laneDisplay).isNull()
        }
    }

    @Test
    fun `local sample clock rollback withdraws retained display even with still fresh guide`() {
        // Given
        val sources = continuing()
        sources.sampleReadOnly(15000, true)
        // When / Then
        assertThat(sources.sampleReadOnly(14500, true).laneDisplay).isNull()
        guide(sources, 16000)
        assertThat(buildAmapNavigationDisplay(fields, sources.snapshot(), "AMAP", 16000).displayJunction).isNull()
    }

    @Test
    fun `missing explicit acceptance future receipts and duplicate or unordered receipts cannot revive cache`() {
        // Given / When / Then
        val sources = continuing()
        sources.updateLaneDisplay(null, 1, 14000)
        assertThat(sources.snapshot().laneDisplay).isNull()
        sources.update("lane_current", nowMs = 15000)
        sources.updateLaneDisplay(lanes, 1, 15000)
        assertThat(buildAmapNavigationDisplay(fields, sources.snapshot(), "AMAP", 14000).displayJunction).isNull()
        sources.updateLaneDisplay(lanes, 1, 15000)
        assertThat(sources.snapshot().laneDisplay).isNull()
        sources.updateLaneDisplay(lanes, 1, 15000)
        assertThat(sources.snapshot().laneDisplay).isNull()
        sources.updateLaneDisplay(lanes, 1, 16000)
        sources.updateLaneDisplay(lanes, 1, 15999)
        assertThat(sources.snapshot().laneDisplay).isNull()
    }

    @Test
    fun `retained unknown order stays unknown and is never silently sorted`() {
        // Given
        val unknown = lanes.map { it.copy(driveWayNumber = -1) }
        val state = fields.copy(laneInfoList = unknown)
        val sources = continuing().apply {
            update("lane_current", nowMs = 1002)
            updateLaneDisplay(unknown, 1, 1002)
        }
        // When
        val display = buildAmapNavigationDisplay(state, sources.snapshot(), "AMAP", 14000)
        // Then
        assertThat(display.displayJunction!!.leftToRightKnown).isFalse()
        assertThat(display.displayJunction!!.lanes.map { it.rawIcon }).containsExactly("0", "999").inOrder()
        assertThat(display.junction).isNull()
    }
}
