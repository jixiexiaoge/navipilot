package com.jixiexiaoge.drivingassist.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CarrotDisplaySnapshotTest {
    @Test
    fun `lateral state expires independently from model and lane planning`() {
        val store = CompactTelemetryStore()
        store.accept(listOf(
            CompactFrame("controlsState", 1, mapOf("curvature" to 0.01f)),
            CompactFrame("modelV2", 1, mapOf("frameId" to 1L)),
            CompactFrame("lateralPlan", 1, mapOf("useLaneLines" to true)),
        ), 1000)
        val fresh = store.snapshot(1200).toDisplaySnapshot()
        assertThat(fresh.lateralAvailability).isEqualTo(TelemetryAvailability.Fresh)
        assertThat(fresh.lateral?.curvature).isEqualTo(0.01f)
        val mixed = store.snapshot(1500).toDisplaySnapshot()
        assertThat(mixed.lateralAvailability).isEqualTo(TelemetryAvailability.Expired)
        assertThat(mixed.lateral).isNull()
        assertThat(mixed.modelAvailability).isEqualTo(TelemetryAvailability.Fresh)
        assertThat(mixed.laneAvailability).isEqualTo(TelemetryAvailability.Fresh)
        assertThat(mixed.laneLinesUsed).isTrue()
        assertThat(store.snapshot(1750).toDisplaySnapshot().laneLinesUsed).isNull()
    }

    @Test
    fun `vehicle navigation is independent from AMap reception and expires with carrotMan`() {
        // Given
        val fields = mapOf("vehicleNaviAvailable" to true, "vehicleNaviActive" to true,
            "vehicleNaviSpeed" to 80, "vehicleNaviSectionActive" to false)
        val store = CompactTelemetryStore()
        // When / Then
        val fresh = store.accept(listOf(CompactFrame("carrotMan", 1, fields)), 1000).toDisplaySnapshot()
        assertThat(fresh.vehicleNavigation).isEqualTo(ReceivedVehicleNavigation(true, true, 80, false))
        assertThat(fresh.navigation).isEqualTo(NavigationReception.Unknown)
        assertThat(store.snapshot(2500).toDisplaySnapshot().vehicleNavigation).isNull()
        assertThat(snapshot("carrotMan" to (fields + ("vehicleNaviAvailable" to false)))
            .toDisplaySnapshot().vehicleNavigation!!.speedKph).isNull()
        assertThat(snapshot("carrotMan" to emptyMap()).toDisplaySnapshot().vehicleNavigation)
            .isEqualTo(ReceivedVehicleNavigation(null, null, null, null))
    }

    @Test
    fun `model execution seconds become milliseconds without accepting invalid values`() {
        // Given / When / Then
        for (value in listOf(0f, 0.025f)) {
            val telemetry = CompactTelemetryStore().accept(listOf(CompactFrame("modelV2", 1,
                mapOf("frameId" to 1L, "modelExecutionTime" to value))), 1000)
            assertThat(telemetry.toDisplaySnapshot().quality!!.executionMs).isEqualTo(value * 1000f)
            assertThat(telemetry.copy(observedAtMs = 1750).toDisplaySnapshot().quality).isNull()
        }
        for (value in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY, Float.MAX_VALUE)) {
            val telemetry = CompactTelemetryStore().accept(listOf(CompactFrame("modelV2", 1,
                mapOf("frameId" to 1L, "modelExecutionTime" to value))), 1000)
            assertThat(telemetry.toDisplaySnapshot().quality!!.executionMs).isNull()
        }
    }
    @Test
    fun `vehicle planning and command values preserve units signed acceleration and reported zeros`() {
        // Given / When
        val display = snapshot(
            "carState" to mapOf("gearShifter" to "drive", "aEgo" to -0.3f,
                "brakeLights" to false, "brakeHoldActive" to true),
            "longitudinalPlan" to mapOf("speeds" to listOf(20f), "accels" to listOf(-0.5f)),
            "carControl" to mapOf("actuators" to mapOf("steeringAngleDeg" to 0f, "accel" to -1f)),
            "lateralPlan" to mapOf("useLaneLines" to false),
        ).toDisplaySnapshot()
        // Then
        assertThat(display.vehicleMotion).isEqualTo(ReceivedVehicleMotion("drive", -0.3f, false, true))
        assertThat(display.planning).isEqualTo(ReceivedPlanning(72f, -0.5f))
        assertThat(display.actuation).isEqualTo(ReceivedActuation(0f, -1f))
        assertThat(display.laneLinesUsed).isFalse()
        assertThat(display.latActive).isNull()
        assertThat(display.longActive).isNull()
        val zero = snapshot("carState" to mapOf("aEgo" to 0f),
            "longitudinalPlan" to mapOf("speeds" to listOf(0f), "accels" to listOf(0f))).toDisplaySnapshot()
        assertThat(zero.vehicleMotion!!.gear).isNull()
        assertThat(zero.vehicleMotion!!.brakeLights).isNull()
        assertThat(zero.planning).isEqualTo(ReceivedPlanning(0f, 0f))
    }

    @Test
    fun `missing malformed and nonfinite supplementary values remain unknown`() {
        // Given / When
        val display = snapshot(
            "carState" to mapOf("gearShifter" to "unknown", "aEgo" to Float.NaN),
            "longitudinalPlan" to mapOf("speeds" to listOf(-1f), "accels" to emptyList<Float>()),
            "carControl" to mapOf("actuators" to mapOf("steeringAngleDeg" to Float.POSITIVE_INFINITY)),
        ).toDisplaySnapshot()
        // Then
        assertThat(display.vehicleMotion).isEqualTo(ReceivedVehicleMotion(null, null, null, null))
        assertThat(display.planning).isEqualTo(ReceivedPlanning(null, null))
        assertThat(display.actuation).isEqualTo(ReceivedActuation(null, null))
        assertThat(display.laneLinesUsed).isNull()
        assertThat(snapshot("longitudinalPlan" to mapOf("speeds" to listOf(Float.MAX_VALUE)))
            .toDisplaySnapshot().planning!!.speedKph).isNull()
    }

    @Test
    fun `side radar selections need real targets and expire independently of fresh device packets`() {
        // Given
        val radar = mapOf(
            "leadLeft" to mapOf("status" to true, "dRel" to 12f, "yRel" to 3f, "vRel" to 1f),
            "leadRight" to mapOf("status" to false, "dRel" to 8f, "vRel" to -2f),
        )
        val store = CompactTelemetryStore()
        store.accept(listOf(frame("radarState", radar), frame("carState", mapOf("gearShifter" to "drive")),
            frame("longitudinalPlan", mapOf("speeds" to listOf(20f))),
            frame("carControl", mapOf("actuators" to mapOf("accel" to 0f)))), 1000)
        // When / Then
        val fresh = store.snapshot(1200).toDisplaySnapshot()
        assertThat(fresh.leftLead).isEqualTo(ReceivedLead(12f, 3.6f))
        assertThat(fresh.rightLead).isNull()
        val expired = store.accept(listOf(frame("deviceState", emptyMap())), 1750).toDisplaySnapshot()
        assertThat(expired.leftLead).isNull()
        assertThat(expired.vehicleMotion).isNull()
        assertThat(expired.planning).isNull()
        assertThat(expired.actuation).isNull()
        assertThat(expired.vehicleAvailability).isEqualTo(TelemetryAvailability.Expired)
        assertThat(expired.planningAvailability).isEqualTo(TelemetryAvailability.Expired)
        assertThat(expired.actuationAvailability).isEqualTo(TelemetryAvailability.Expired)
    }

    @Test
    fun `missing telemetry is unknown rather than inactive or healthy`() {
        // Given / When
        val display = (null as CompactTelemetry?).toDisplaySnapshot()
        // Then
        assertThat(display.resultAvailability).isEqualTo(TelemetryAvailability.Missing)
        assertThat(display.navigation).isEqualTo(NavigationReception.Unknown)
        assertThat(display.latActive).isNull()
        assertThat(display.longActive).isNull()
        assertThat(display.lead).isNull()
    }

    @Test
    fun `sentinel speed and invalid adopted limits are never advisory speeds`() {
        // Given
        for (speed in listOf(-1, 0, 250, 251)) {
            // When
            val display = snapshot("carrotMan" to mapOf(
                "desiredSpeed" to speed, "desiredSource" to "atc", "nRoadLimitSpeed" to 0,
            )).toDisplaySnapshot()
            // Then
            assertThat(display.desiredSpeedKph).isNull()
            assertThat(display.desiredSource).isNull()
            assertThat(display.adoptedLimitKph).isNull()
        }
        val valid = snapshot("carrotMan" to mapOf("desiredSpeed" to 60, "desiredSource" to "cam")).toDisplaySnapshot()
        assertThat(valid.desiredSpeedKph).isEqualTo(60)
        assertThat(valid.desiredSource).isEqualTo("cam")
    }

    @Test
    fun `fresh received route and both maneuvers retain units and presence`() {
        // Given / When
        val display = snapshot("carrotNavi" to navigation()).toDisplaySnapshot()
        // Then
        assertThat(display.navigation).isEqualTo(NavigationReception.Guiding)
        assertThat(display.journey).isEqualTo(ReceivedJourney(12000, 900))
        assertThat(display.currentGuidance).isEqualTo(ReceivedGuidance("Exit", "Road A", 500))
        assertThat(display.nextGuidance).isEqualTo(ReceivedGuidance("Right", "Road B", 200))
    }

    @Test
    fun `disconnected unsupported idle absent and off route sessions do not expose stale journey`() {
        // Given
        val variants = listOf(
            navigation() + ("connected" to false),
            navigation() + ("schemaVersion" to 2),
            navigation() + ("navigationStatus" to mapOf("guidanceActive" to false, "offRoute" to false, "routePresent" to true)),
            navigation() + ("navigationStatus" to mapOf("guidanceActive" to true, "offRoute" to true, "routePresent" to true)),
            navigation() + ("route" to mapOf("present" to false, "remainingDistanceM" to 12000)),
            navigation() + ("navigationStatus" to mapOf("guidanceActive" to true, "routePresent" to true)),
        )
        // When / Then
        variants.forEach { assertThat(snapshot("carrotNavi" to it).toDisplaySnapshot().journey).isNull() }
        val offRoute = snapshot("carrotNavi" to variants[3]).toDisplaySnapshot()
        assertThat(offRoute.navigation).isEqualTo(NavigationReception.OffRoute)
        assertThat(offRoute.currentGuidance).isNull()
        assertThat(offRoute.events).isEmpty()
        assertThat(offRoute.section).isNull()
    }

    @Test
    fun `normalized absent speed events do not fall back to contradictory legacy events`() {
        // Given
        val legacy = mapOf("xSpdType" to 22, "xSpdDist" to 120, "xSpdLimit" to 30)
        // When
        val display = snapshot("carrotMan" to legacy,
            "carrotNavi" to (navigation() + ("speed" to mapOf("sdiPresent" to false)))).toDisplaySnapshot()
        // Then
        assertThat(display.events).isEmpty()
        assertThat(snapshot("carrotMan" to legacy).toDisplaySnapshot().events)
            .containsExactly(ReceivedSpeedEvent(22, 30, 120, null))
    }

    @Test
    fun `primary and secondary events require presence type and positive distance`() {
        // Given
        val speed = mapOf(
            "sdiPresent" to true, "sdiType" to 2, "sdiSpeedLimitKph" to 60, "sdiDistanceM" to 400,
            "secondarySdiPresent" to true, "secondarySdiType" to 22, "secondarySdiSpeedLimitKph" to 0, "secondarySdiDistanceM" to 100,
        )
        // When / Then
        val display = snapshot("carrotNavi" to (navigation() + ("speed" to speed))).toDisplaySnapshot()
        assertThat(display.events).containsExactly(
            ReceivedSpeedEvent(2, 60, 400, null), ReceivedSpeedEvent(22, null, 100, null),
        ).inOrder()
        val absent = speed + mapOf("sdiPresent" to false, "secondarySdiDistanceM" to 0)
        assertThat(snapshot("carrotNavi" to (navigation() + ("speed" to absent))).toDisplaySnapshot().events).isEmpty()
    }

    @Test
    fun `section respects suspension off route and compact missing optional values`() {
        // Given
        val speed = mapOf(
            "sectionPresent" to true, "sectionActive" to true, "sectionSuspended" to false, "sectionOffRoute" to false,
            "sectionSpeedLimitKph" to 80, "sectionAverageKph" to 72.5f,
            "sectionRemainingDistanceM" to 2400f, "sectionProgress" to 0.4f,
        )
        // When / Then
        val display = snapshot("carrotNavi" to (navigation() + ("speed" to speed))).toDisplaySnapshot()
        assertThat(display.section).isEqualTo(ReceivedSection(80, 72.5f, 2400f, 0.4f))
        for (flag in listOf("sectionSuspended", "sectionOffRoute")) {
            assertThat(snapshot("carrotNavi" to (navigation() + ("speed" to (speed + (flag to true)))))
                .toDisplaySnapshot().section).isNull()
        }
        val missingValues = speed + mapOf("sectionAverageKph" to 0f, "sectionRemainingDistanceM" to 0f, "sectionProgress" to 0f)
        val unknown = snapshot("carrotNavi" to (navigation() + ("speed" to missingValues))).toDisplaySnapshot().section!!
        assertThat(unknown.averageKph).isNull()
        assertThat(unknown.remainingM).isNull()
        assertThat(unknown.progress).isNull()
    }

    @Test
    fun `radar relative speed is converted to kph without requiring an ego speed`() {
        // Given
        val radar = mapOf("leadOne" to mapOf("status" to true, "dRel" to 32f, "yRel" to 0f, "vRel" to -2f))
        // When
        val display = snapshot("radarState" to radar).toDisplaySnapshot()
        // Then
        assertThat(display.lead!!.distanceM).isEqualTo(32f)
        assertThat(display.lead!!.relativeKph).isWithin(0.0001f).of(-7.2f)
        assertThat(snapshot("radarState" to mapOf("leadOne" to mapOf("status" to false))).toDisplaySnapshot().lead).isNull()
    }

    @Test
    fun `following lane direction and control state retain truthful meanings`() {
        // Given / When
        val display = snapshot(
            "longitudinalPlan" to mapOf("tFollow" to 1.8f, "desiredDistance" to 38f, "longitudinalPlanSource" to "lead0"),
            "lateralPlan" to mapOf("laneChangeState" to "preLaneChange", "laneChangeDirection" to "right"),
            "carControl" to mapOf("latActive" to false, "longActive" to true),
        ).toDisplaySnapshot()
        // Then
        assertThat(display.following).isEqualTo(ReceivedFollowing(1.8f, 38f, "lead0"))
        assertThat(display.laneChange).isEqualTo(ReceivedLaneChange("preLaneChange", "right"))
        assertThat(display.latActive).isFalse()
        assertThat(display.longActive).isTrue()
    }

    @Test
    fun `both alert lines and critical severity are retained even with no message text`() {
        // Given / When
        val alert = snapshot("selfdriveState" to mapOf(
            "alertStatus" to "critical", "alertText1" to "Take over", "alertText2" to "Immediately",
        )).toDisplaySnapshot().alert
        // Then
        assertThat(alert).isEqualTo(ReceivedAlert("critical", "Take over", "Immediately"))
        assertThat(snapshot("selfdriveState" to mapOf("alertStatus" to "critical"))
            .toDisplaySnapshot().alert?.status).isEqualTo("critical")
    }

    @Test
    fun `unrelated service updates cannot revive expired results alerts radar plans or control`() {
        // Given
        val store = CompactTelemetryStore()
        store.accept(listOf(
            frame("carrotMan", mapOf("desiredSpeed" to 60, "desiredSource" to "cam")),
            frame("carrotNavi", navigation()),
            frame("selfdriveState", mapOf("alertStatus" to "critical", "alertText1" to "Old alert")),
            frame("carControl", mapOf("latActive" to true)),
            frame("radarState", mapOf("leadOne" to mapOf("status" to true, "dRel" to 20f, "yRel" to 0f, "vRel" to -1f))),
            frame("longitudinalPlan", mapOf("tFollow" to 1.8f)),
        ), 1000)
        // When
        val partial = store.snapshot(1750).toDisplaySnapshot()
        val expired = store.snapshot(4000).toDisplaySnapshot()
        // Then
        assertThat(partial.latActive).isNull()
        assertThat(partial.lead).isNull()
        assertThat(partial.following).isNull()
        assertThat(partial.desiredSpeedKph).isEqualTo(60)
        assertThat(expired.resultAvailability).isEqualTo(TelemetryAvailability.Expired)
        assertThat(expired.navigationAvailability).isEqualTo(TelemetryAvailability.Expired)
        assertThat(expired.desiredSpeedKph).isNull()
        assertThat(expired.desiredSource).isNull()
        assertThat(expired.alert).isNull()
        assertThat(expired.journey).isNull()
    }

    private fun snapshot(vararg services: Pair<String, Map<String, Any?>>) =
        CompactTelemetryStore().accept(services.map { (service, values) -> frame(service, values) }, 1000)

    private fun frame(service: String, values: Map<String, Any?>) = CompactFrame(service, 1, values, 0)

    @Test
    fun `journey progress requires a known total covering the remaining distance`() {
        // Given
        val route = mapOf("present" to true, "remainingDistanceM" to 12000, "remainingTimeSec" to 900)
        // When / Then
        fun progress(total: Int?) = snapshot("carrotNavi" to (navigation() +
            ("route" to (route + ("totalDistanceM" to total))))).toDisplaySnapshot().journey?.progress
        assertThat(progress(20000)).isWithin(0.0001f).of(0.4f)
        assertThat(progress(12000)).isEqualTo(0f)
        assertThat(progress(10000)).isNull()
        assertThat(progress(0)).isNull()
        assertThat(progress(null)).isNull()
    }

    @Test
    fun `received lanes use left to right recommendation flags not lane change clearance`() {
        // Given / When
        val lanes = mapOf("present" to true, "visible" to true, "count" to 3, "available" to listOf(0, 1, 1))
        val display = snapshot("carrotNavi" to (navigation() + ("laneCurrent" to lanes))).toDisplaySnapshot()
        // Then
        assertThat(display.navigationLanes).isEqualTo(ReceivedNavigationLanes(3, listOf(1, 2)))
        assertThat(display.latActive).isNull()
        assertThat(display.longActive).isNull()
        assertThat(snapshot("carrotNavi" to (navigation() + ("laneCurrent" to (lanes + ("visible" to false)))))
            .toDisplaySnapshot().navigationLanes).isNull()
        assertThat(snapshot("carrotNavi" to (navigation() + ("laneCurrent" to (lanes + ("count" to 17)))))
            .toDisplaySnapshot().navigationLanes).isNull()
    }

    @Test
    fun `incomplete recommendation masks remain unknown not all unselected`() {
        // Given
        val lanes = mapOf("present" to true, "visible" to true, "count" to 3)
        // When / Then
        for (mask in listOf(emptyList(), listOf(1, 0), listOf(0, 2, 1), listOf(0, 1.5f, 1), listOf(0, "bad", 1, 1))) {
            assertThat(snapshot("carrotNavi" to (navigation() + ("laneCurrent" to (lanes + ("available" to mask)))))
                .toDisplaySnapshot().navigationLanes).isEqualTo(ReceivedNavigationLanes(3, null))
        }
        assertThat(snapshot("carrotNavi" to (navigation() + ("laneCurrent" to (lanes + ("available" to listOf(0, 0, 0))))))
            .toDisplaySnapshot().navigationLanes).isEqualTo(ReceivedNavigationLanes(3, emptyList()))
    }

    @Test
    fun `navigation receipt age and lane guidance expire without unrelated revival`() {
        // Given
        val store = CompactTelemetryStore()
        val lanes = mapOf("present" to true, "visible" to true, "count" to 2, "available" to listOf(0, 1))
        store.accept(listOf(frame("carrotNavi", navigation() + ("laneCurrent" to lanes))), 1000)
        // When / Then
        assertThat(store.snapshot(1250).toDisplaySnapshot().navigationAgeMs).isEqualTo(250)
        val expired = store.accept(listOf(frame("deviceState", mapOf("memoryUsagePercent" to 30))), 4000)
            .toDisplaySnapshot()
        assertThat(expired.navigationAgeMs).isNull()
        assertThat(expired.navigationLanes).isNull()
        assertThat(expired.journey).isNull()
        assertThat(expired.navigationAvailability).isEqualTo(TelemetryAvailability.Expired)
    }

    private fun navigation(): Map<String, Any?> = mapOf(
        "schemaVersion" to 1, "connected" to true,
        "navigationStatus" to mapOf("guidanceActive" to true, "routePresent" to true, "offRoute" to false),
        "route" to mapOf("present" to true, "remainingDistanceM" to 12000, "remainingTimeSec" to 900),
        "guidanceCurrent" to mapOf("present" to true, "mainText" to "Exit", "roadName" to "Road A", "distanceM" to 500),
        "guidanceNext" to mapOf("present" to true, "mainText" to "Right", "roadName" to "Road B", "distanceM" to 200),
    )
}
