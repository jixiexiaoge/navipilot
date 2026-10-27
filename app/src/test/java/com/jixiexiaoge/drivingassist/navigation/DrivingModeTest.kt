package com.jixiexiaoge.drivingassist.navigation

import com.google.common.truth.Truth.assertThat
import com.jixiexiaoge.drivingassist.CarrotManFields
import com.jixiexiaoge.drivingassist.LaneInfo
import org.junit.Test

class DrivingModeTest {
    private fun fields(count: Int = 3, recommended: Set<Int> = (1..count).toSet()) =
        CarrotManFields(isNavigating = true, noaRouteGeneration = 1, nLaneCount = count,
            laneInfoList = (1..count).map {
                LaneInfo("0", it in recommended, driveWayNumber = it - 1)
            })

    private fun sources(road: Int? = 0) = NaviSourceTracker().apply {
        updateGuideExtras(AmapGuideExtras(1000, 1, "Road", "Destination",
            20000, 900, 3, null, null, null, null, null, reportedRoadType = road))
        update("lane_current", "road_category", nowMs = 1001)
    }

    private fun map(road: Int? = 0, state: CarrotManFields = fields()) =
        buildAmapNavigationDisplay(state, sources(road).snapshot(), "AMAP", 1200)

    @Test
    fun `stable storage keys roundtrip all three modes and unknown values are rejected`() {
        // Given / When / Then
        DrivingMode.entries.forEach { assertThat(DrivingMode.fromStorage(it.storageKey)).isEqualTo(it) }
        listOf(null, "", "NOA", "bogus").forEach { assertThat(DrivingMode.fromStorage(it)).isNull() }
        assertThat(DrivingMode.entries.map { it.storageKey }.toSet()).hasSize(3)
    }

    @Test
    fun `two three and four map lanes prefer left for highway and right for regular roads`() {
        // Given / When / Then
        (2..4).forEach { count ->
            val highway = buildDrivingModeAdvice(DrivingMode.HIGHWAY_EAP, map(0, fields(count)))
            val regular = buildDrivingModeAdvice(DrivingMode.REGULAR_NOO, map(9, fields(count)))
            assertThat(highway.candidateLane).isEqualTo(2)
            assertThat(regular.candidateLane).isEqualTo(count)
            assertThat(highway.roadMatch).isEqualTo(DrivingRoadMatch.Matching)
            assertThat(regular.roadMatch).isEqualTo(DrivingRoadMatch.Matching)
        }
    }

    @Test
    fun `expressway supports conditional left preference but never proves elevation or shoulder`() {
        // Given / When
        val elevated = buildDrivingModeAdvice(DrivingMode.ELEVATED_NOA, map(6))
        // Then
        assertThat(elevated.roadMatch).isEqualTo(DrivingRoadMatch.ExpresswayUnconfirmed)
        assertThat(elevated.candidateLane).isEqualTo(2)
        assertThat(buildDrivingModeAdvice(DrivingMode.HIGHWAY_EAP, map(6)).roadMatch)
            .isEqualTo(DrivingRoadMatch.Mismatch)
        assertThat(buildDrivingModeAdvice(DrivingMode.ELEVATED_NOA, map(0)).candidateLane).isNull()
    }

    @Test
    fun `exit and turn route recommendations override mode preference without assuming shoulder`() {
        // Given / When / Then
        assertThat(buildDrivingModeAdvice(DrivingMode.HIGHWAY_EAP,
            map(0, fields(4, setOf(4)))).candidateLane).isEqualTo(4)
        assertThat(buildDrivingModeAdvice(DrivingMode.REGULAR_NOO,
            map(9, fields(3, setOf(1)))).candidateLane).isEqualTo(1)
        val subset = buildDrivingModeAdvice(DrivingMode.REGULAR_NOO, map(9, fields(4, setOf(1, 2))))
        assertThat(subset.routeLanes).containsExactly(1, 2).inOrder()
        assertThat(subset.candidateLane).isEqualTo(2)
    }

    @Test
    fun `road absence never reuses default highway legacy roadcate or telemetry freshness`() {
        // Given
        val tracker = sources(null).apply { update("vehicle", "road_category", nowMs = 1200) }
        val display = buildAmapNavigationDisplay(fields().copy(roadType = 0, roadcate = 10),
            tracker.snapshot(), "AMAP", 1200)
        // When / Then
        assertThat(display.reportedRoadType).isNull()
        assertThat(display.roadScope).isNull()
        assertThat(buildDrivingModeAdvice(DrivingMode.HIGHWAY_EAP, display).roadMatch)
            .isEqualTo(DrivingRoadMatch.Unknown)
        assertThat(buildDrivingModeAdvice(DrivingMode.HIGHWAY_EAP, display).candidateLane).isNull()
    }

    @Test
    fun `mismatched unsupported road classes block lane candidates in every mode`() {
        // Given / When / Then
        DrivingMode.entries.forEach { mode ->
            listOf(null, -1, 10, 99).forEach { road ->
                assertThat(buildDrivingModeAdvice(mode, map(road)).candidateLane).isNull()
            }
        }
        assertThat(buildDrivingModeAdvice(DrivingMode.HIGHWAY_EAP, map(9)).roadMatch)
            .isEqualTo(DrivingRoadMatch.Mismatch)
        assertThat(buildDrivingModeAdvice(DrivingMode.REGULAR_NOO, map(0)).candidateLane).isNull()
        assertThat(buildDrivingModeAdvice(DrivingMode.REGULAR_NOO, map(6)).candidateLane).isNull()
    }

    @Test
    fun `missing recommendation unknown direction order or cached lanes cannot generate a candidate`() {
        // Given
        val states = listOf(
            fields(recommended = emptySet()),
            fields().copy(laneInfoFromCache = true),
            fields().copy(laneInfoList = listOf(LaneInfo("unknown", true, driveWayNumber = 0),
                LaneInfo("0", true, driveWayNumber = 1), LaneInfo("0", true, driveWayNumber = 2))),
            fields().copy(laneInfoList = List(3) { LaneInfo("0", true, driveWayNumber = -1) }),
            fields().copy(nLaneCount = 4),
        )
        // When / Then
        states.forEach { assertThat(buildDrivingModeAdvice(DrivingMode.HIGHWAY_EAP, map(0, it)).candidateLane).isNull() }
        assertThat(buildDrivingModeAdvice(DrivingMode.HIGHWAY_EAP, map(0, states.first())).laneAdvice)
            .isEqualTo(DrivingLaneAdvice.NoRecommendation)
    }

    @Test
    fun `route change stop source deselection expiration and future timestamps withdraw advice`() {
        // Given
        val tracker = sources()
        val state = fields()
        // When / Then
        listOf(
            buildAmapNavigationDisplay(state, tracker.snapshot(), "OTHER", 1200),
            buildAmapNavigationDisplay(state.copy(isNavigating = false), tracker.snapshot(), "AMAP", 1200),
            buildAmapNavigationDisplay(state.copy(noaRouteGeneration = 2), tracker.snapshot(), "AMAP", 1200),
            buildAmapNavigationDisplay(state, tracker.snapshot(), "AMAP", 11001),
            buildAmapNavigationDisplay(state, tracker.snapshot(), "AMAP", 999),
        ).forEach {
            assertThat(it.reportedRoadType).isNull()
            assertThat(buildDrivingModeAdvice(DrivingMode.HIGHWAY_EAP, it).candidateLane).isNull()
        }
        val boundary = buildAmapNavigationDisplay(state, tracker.snapshot(), "AMAP", 11000)
        assertThat(buildDrivingModeAdvice(DrivingMode.HIGHWAY_EAP, boundary).candidateLane).isEqualTo(2)
        tracker.resetGuideDisplay(1300)
        assertThat(buildDrivingModeAdvice(DrivingMode.HIGHWAY_EAP,
            buildAmapNavigationDisplay(state, tracker.snapshot(), "AMAP", 1400)).candidateLane).isNull()
        tracker.stopNavigation()
        assertThat(buildAmapNavigationDisplay(state, tracker.snapshot(), "AMAP", 1500).reportedRoadType).isNull()
    }

    @Test
    fun `expired lane sample cannot be renewed by fresh road data`() {
        // Given
        val tracker = sources()
        tracker.updateGuideExtras(tracker.snapshot().guideExtras!!.copy(receivedAtMs = 11002))
        // When
        val display = buildAmapNavigationDisplay(fields(), tracker.snapshot(), "AMAP", 11002)
        // Then
        assertThat(display.reportedRoadType).isEqualTo(0)
        assertThat(buildDrivingModeAdvice(DrivingMode.HIGHWAY_EAP, display).candidateLane).isNull()
    }

    @Test
    fun `off route or takeover alert withdraws map candidates in every mode`() {
        // Given / When / Then
        DrivingMode.entries.forEach { mode ->
            val display = map(when (mode) {
                DrivingMode.HIGHWAY_EAP -> 0
                DrivingMode.ELEVATED_NOA -> 6
                DrivingMode.REGULAR_NOO -> 9
            })
            assertThat(buildDrivingModeAdvice(mode, display, offRoute = true).candidateLane).isNull()
            assertThat(buildDrivingModeAdvice(mode, display, takeoverAlert = true).routeLanes).isEmpty()
            assertThat(buildDrivingModeAdvice(mode, display).candidateLane).isEqualTo(if (mode.prefersLeft) 2 else 3)
        }
    }

    @Test
    fun `route constraints explain when route lanes replace the default preference`() {
        // Given / When / Then
        assertThat(buildDrivingModeAdvice(DrivingMode.HIGHWAY_EAP, map(0, fields(4))).priority)
            .isEqualTo(DrivingLanePriority.ModePreference)
        assertThat(buildDrivingModeAdvice(DrivingMode.HIGHWAY_EAP, map(0, fields(4, setOf(4)))).priority)
            .isEqualTo(DrivingLanePriority.RouteConstraint)
        assertThat(buildDrivingModeAdvice(DrivingMode.ELEVATED_NOA, map(6, fields(1))).candidateLane).isEqualTo(1)
        assertThat(buildDrivingModeAdvice(DrivingMode.ELEVATED_NOA, map(6, fields(1))).priority)
            .isEqualTo(DrivingLanePriority.ModePreference)
        assertThat(buildDrivingModeAdvice(DrivingMode.REGULAR_NOO, map(9, fields(4, setOf(1, 2)))).priority)
            .isEqualTo(DrivingLanePriority.RouteConstraint)
    }

    @Test
    fun `withheld advice has specific causes and never retains candidate or priority`() {
        // Given
        val display = map()
        // When / Then
        listOf(
            buildDrivingModeAdvice(DrivingMode.HIGHWAY_EAP, display, sceneReady = false) to DrivingAdviceHold.ScenePending,
            buildDrivingModeAdvice(DrivingMode.HIGHWAY_EAP, display, offRoute = true) to DrivingAdviceHold.OffRoute,
            buildDrivingModeAdvice(DrivingMode.HIGHWAY_EAP, display, takeoverAlert = true) to DrivingAdviceHold.VehicleAlert,
            buildDrivingModeAdvice(DrivingMode.REGULAR_NOO, display) to DrivingAdviceHold.RoadMismatch,
            buildDrivingModeAdvice(DrivingMode.HIGHWAY_EAP, map(null)) to DrivingAdviceHold.RoadUnknown,
            buildDrivingModeAdvice(DrivingMode.HIGHWAY_EAP, display.copy(state = AmapDisplayState.Expired)) to DrivingAdviceHold.MapUnavailable,
        ).forEach { (advice, reason) ->
            assertThat(advice.hold).isEqualTo(reason)
            assertThat(advice.candidateLane).isNull()
            assertThat(advice.routeLanes).isEmpty()
            assertThat(advice.priority).isNull()
        }
        assertThat(buildDrivingModeAdvice(DrivingMode.HIGHWAY_EAP, display,
            takeoverAlert = true, offRoute = true, sceneReady = false).hold).isEqualTo(DrivingAdviceHold.VehicleAlert)
    }
}
