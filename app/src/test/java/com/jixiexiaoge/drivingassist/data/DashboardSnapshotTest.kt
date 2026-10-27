package com.jixiexiaoge.drivingassist.data

import com.google.common.truth.Truth.assertThat
import com.jixiexiaoge.drivingassist.CarrotManFields
import com.jixiexiaoge.drivingassist.navigation.AmapDisplayState
import com.jixiexiaoge.drivingassist.navigation.AmapDisplayAction
import com.jixiexiaoge.drivingassist.navigation.NaviSourceTracker
import com.jixiexiaoge.drivingassist.navigation.buildAmapNavigationDisplay
import org.junit.After
import org.junit.Test

class DashboardSnapshotTest {
    @After
    fun clear() = DashboardStore.clear()

    @Test
    fun `independent service deadlines do not renew when snapshot is requested`() {
        val telemetry = CompactTelemetry(mapOf(
            "carState" to sample("carState", 100, 600, mapOf("vEgo" to 10f, "aEgo" to 0f)),
            "carControl" to sample("carControl", 400, 900, mapOf("longActive" to false)),
        ), 400)
        val first = buildDashboardSnapshot(telemetry, 500)
        assertThat(first.panel("vehicle").remainingMs).isEqualTo(100)
        assertThat(first.value("speed")).isEqualTo("36.0")
        assertThat(first.value("accel")).isEqualTo("0.0")
        val later = buildDashboardSnapshot(telemetry, 600)
        assertThat(later.panel("vehicle").status).isEqualTo("expired")
        assertThat(later.value("speed")).isEqualTo("未知")
        assertThat(later.panel("control").remainingMs).isEqualTo(300)
        assertThat(later.value("long-active")).isEqualTo("否")
        assertThat(later.value("lat-active")).isEqualTo("未知")
    }

    @Test
    fun `radar retains unknown selection semantics without any signal fallback`() {
        val telemetry = CompactTelemetry(mapOf("radarState" to sample("radarState", 100, 850, mapOf(
            "leadOne" to mapOf("status" to false, "dRel" to 10f, "vRel" to -1f),
            "leadLeft" to mapOf("status" to true, "dRel" to 20f, "yRel" to 3f, "vRel" to -2f),
        ))), 200)
        val snapshot = buildDashboardSnapshot(telemetry, 500)
        assertThat(snapshot.value("lead-status")).isEqualTo("未选择（非无车确认）")
        assertThat(snapshot.value("lead-distance")).isEqualTo("未知")
        assertThat(snapshot.value("left-relative")).isEqualTo("-7.2")
        assertThat(snapshot.value("right-status")).isEqualTo("未知")
    }

    @Test
    fun `7000 dashboard never presents removed CAN signal or meta edge fields as equivalent data`() {
        val telemetry = CompactTelemetry(mapOf(
            "carState" to sample("carState", 100, 600,
                mapOf("vEgo" to 10f, "aEgo" to -0.5f, "trafficLightColor" to 2, "stopLineDist" to 30f)),
            "modelV2" to sample("modelV2", 100, 850,
                mapOf("meta" to mapOf("distanceToRoadEdgeLeft" to 3.5f))),
            "carrotNavi" to sample("carrotNavi", 100, 1600, mapOf(
                "schemaVersion" to 1, "connected" to true,
                "navigationStatus" to mapOf("guidanceActive" to true, "offRoute" to false),
                "trafficSignal" to mapOf("visible" to true, "greenValid" to true, "greenOn" to true),
            )),
        ), 200)
        val snapshot = buildDashboardSnapshot(telemetry, 200)
        assertThat(snapshot.panels.map { it.id }).doesNotContain("tesla")
        assertThat(snapshot.panels.all { it.source.startsWith("7000/") }).isTrue()
        assertThat(snapshot.value("accel")).isEqualTo("-0.5")
        assertThat(snapshot.value("lamp-green")).contains("亮")
        assertThat(snapshot.toString()).doesNotContain("停止线")
        assertThat(snapshot.toString()).doesNotContain("distanceToRoadEdgeLeft")
    }

    @Test
    fun `missing invalid sentinels and rollback never manufacture useful values`() {
        val telemetry = CompactTelemetry(mapOf("carState" to sample("carState", 100, 600, mapOf(
            "vEgo" to Float.NaN, "vCruiseCluster" to 250f, "fuelGauge" to -1f,
            "tpms" to mapOf("fl" to 0f, "fr" to 32f), "evModeValid" to false,
        ))), 200)
        val snapshot = buildDashboardSnapshot(telemetry, 200)
        listOf("speed", "cruise").forEach {
            assertThat(snapshot.value(it)).isEqualTo("未知")
        }
        assertThat(snapshot.panels.map { it.id }).doesNotContain("extras")
        assertThat(buildDashboardSnapshot(telemetry, 99).panel("vehicle").status).isEqualTo("missing")
    }

    @Test
    fun `store clears all observations and never exposes location or transport identity`() {
        DashboardStore.telemetry = CompactTelemetry(mapOf("carState" to sample("carState", 100, 600,
            mapOf("vEgo" to 0f, "latitude" to 31.2, "deviceId" to "private"))), 200)
        val snapshot = DashboardStore.snapshot(200)
        assertThat(snapshot.value("speed")).isEqualTo("0.0")
        assertThat(snapshot.toString()).doesNotContain("private")
        assertThat(snapshot.toString()).doesNotContain("31.2")
        DashboardStore.clear()
        assertThat(DashboardStore.snapshot(200).panels.all { it.status == "missing" }).isTrue()
    }

    @Test
    fun `alert text is preserved and expired control does not borrow another service`() {
        val text = "重要告警".repeat(200)
        val telemetry = CompactTelemetry(mapOf(
            "selfdriveState" to sample("selfdriveState", 100, 1600,
                mapOf("alertStatus" to "critical", "alertText1" to text)),
            "controlsState" to sample("controlsState", 100, 600, mapOf("activeLaneLine" to true)),
            "modelV2" to sample("modelV2", 500, 1250,
                mapOf("laneLineProbs" to listOf(0.1f, 0.8f, 0.9f, 0.2f))),
        ), 700)
        val snapshot = buildDashboardSnapshot(telemetry, 700)
        assertThat(snapshot.panel("alert").tone).isEqualTo("critical")
        assertThat(snapshot.value("alert-title")).isEqualTo(text)
        assertThat(snapshot.value("active-lines")).isEqualTo("未知")
        assertThat(snapshot.value("prob-1")).isEqualTo("80.0")
    }

    @Test
    fun `navigation uses original source deadlines and route task gates with sampler lease`() {
        val map = buildAmapNavigationDisplay(CarrotManFields(), NaviSourceTracker().snapshot(), "AMAP", 10_900)
            .copy(state = AmapDisplayState.Fresh, roadLimitKph = 80,
                current = AmapDisplayAction("左转", "当前道路", 200, 12),
                next = AmapDisplayAction("右转", "随后道路", 300, 20))
        val clocks = mapOf("dashboard_road_limit" to 1000L, "guidance_current" to 1000L, "guidance_next" to 10_000L)
        DashboardStore.publishNavigation(map, true, clocks, 10_900, 500)
        val snapshot = DashboardStore.snapshot(500)
        assertThat(snapshot.panel("map-current").remainingMs).isEqualTo(100)
        assertThat(snapshot.panel("map-next").remainingMs).isEqualTo(750)
        assertThat(snapshot.value("current-time")).isEqualTo("12")
        assertThat(snapshot.value("next-time")).isEqualTo("20")
        assertThat(DashboardStore.snapshot(600).value("current-distance")).isEqualTo("未知")
        assertThat(DashboardStore.snapshot(1250).value("next-distance")).isEqualTo("未知")
        DashboardStore.publishNavigation(map, false, clocks, 10_900, 500)
        assertThat(DashboardStore.snapshot(500).panels.map { it.id }).doesNotContain("map-current")
        assertThat(DashboardStore.snapshot(500).value("road-limit")).isEqualTo("80")
        DashboardStore.publishNavigation(map.copy(state = AmapDisplayState.Idle), true, clocks, 10_900, 500)
        assertThat(DashboardStore.snapshot(500).panels.map { it.id }).doesNotContain("map-road")
    }

    @Test
    fun `normalized navigation panels never borrow legacy carrotMan event freshness`() {
        val telemetry = CompactTelemetry(mapOf("carrotMan" to sample("carrotMan", 100, 1600,
            mapOf("xSpdType" to 1, "xSpdLimit" to 60, "xSpdDist" to 200))), 200)
        assertThat(telemetry.toDisplaySnapshot().events).hasSize(1)
        val snapshot = buildDashboardSnapshot(telemetry, 200)
        assertThat(snapshot.panel("navi-events").status).isEqualTo("missing")
        assertThat(snapshot.panel("navi-events").metrics.map { it.id }).doesNotContain("event-0-type")
        assertThat(snapshot.panel("result").source).isEqualTo("7000/carrotMan")
        assertThat(snapshot.value("legacy-event-0-type")).isEqualTo("1")
        assertThat(snapshot.value("legacy-event-0-distance")).isEqualTo("200")
    }

    @Test
    fun `unsupported occupant and departure fields are not advertised as compact capabilities`() {
        val withFields = CompactTelemetry(mapOf("carState" to sample("carState", 100, 600, mapOf(
            "vEgo" to 10f, "buckleStatus" to 0, "anyDoorOpen" to 2, "laneDepartureWarning" to 3,
        ))), 200)
        val snapshot = buildDashboardSnapshot(withFields, 200)
        assertThat(snapshot.panels.flatMap { it.metrics }.map { it.id })
            .containsNoneOf("seatbelt-unfastened", "door-open", "ldw")
    }

    @Test
    fun `driving inputs preserve zero and independent axis TTL without parsing panel strings`() {
        val telemetry = CompactTelemetry(mapOf(
            "carState" to sample("carState", 100, 600, mapOf(
                "vEgo" to 0f, "vCruiseCluster" to 80f, "gearShifter" to "park",
                "steeringAngleDeg" to 0f, "leftBlinker" to true, "brakeLights" to false,
            )),
            "carControl" to sample("carControl", 100, 400, mapOf("latActive" to true, "longActive" to false)),
            "carrotMan" to sample("carrotMan", 100, 1600, mapOf("desiredSpeed" to 100)),
        ), 200)
        val fresh = buildDashboardSnapshot(telemetry, 200).driving
        assertThat(fresh.speedKph).isEqualTo(0f)
        assertThat(fresh.steeringDeg).isEqualTo(0f)
        assertThat(fresh.parked).isTrue()
        assertThat(fresh.lateralActive).isTrue()
        assertThat(fresh.longitudinalActive).isFalse()
        assertThat(fresh.brakeLights).isFalse()
        assertThat(fresh.leftBlinker).isTrue()
        assertThat(fresh.suggestedKph).isEqualTo(100)
        assertThat(fresh.adoptedLimitKph).isNull()
        val partial = buildDashboardSnapshot(telemetry, 400).driving
        assertThat(partial.lateralActive).isNull()
        assertThat(partial.cruiseKph).isEqualTo(80f)
        val expired = buildDashboardSnapshot(telemetry, 600).driving
        assertThat(expired.gear).isNull()
        assertThat(expired.cruiseKph).isNull()
        assertThat(expired.leftBlinker).isNull()
        assertThat(expired.suggestedKph).isEqualTo(100)
        assertThat(buildDashboardSnapshot(telemetry, 99).driving.speedKph).isNull()
    }

    @Test
    fun `invalid driving values and speed sentinel are never displayed as measurements`() {
        val telemetry = CompactTelemetry(mapOf(
            "carState" to sample("carState", 100, 600, mapOf(
                "vEgo" to Float.NaN, "vCruiseCluster" to 250f, "steeringAngleDeg" to Float.POSITIVE_INFINITY,
            )),
            "carrotMan" to sample("carrotMan", 100, 1600, mapOf("desiredSpeed" to 250)),
        ), 200)
        val driving = buildDashboardSnapshot(telemetry, 200).driving
        assertThat(driving.speedKph).isNull()
        assertThat(driving.cruiseKph).isNull()
        assertThat(driving.steeringDeg).isNull()
        assertThat(driving.suggestedKph).isNull()
    }

    @Test
    fun `driving notice selects nearest known upcoming event rather than section end`() {
        val display = null.toDisplaySnapshot().copy(events = listOf(
            ReceivedSpeedEvent(9, 60, 10, null),
            ReceivedSpeedEvent(999, 60, 20, null),
            ReceivedSpeedEvent(1, 60, 100, null),
            ReceivedSpeedEvent(8, 80, 50, null),
        ))
        assertThat(null.drivingDisplay(display).speedEvent?.distanceM).isEqualTo(50)
    }

    private fun sample(service: String, received: Long, expires: Long, values: Map<String, Any?>) =
        CompactServiceSample(CompactFrame(service, 1, values), received, expires)
    private fun DashboardSnapshot.panel(id: String) = panels.first { it.id == id }
    private fun DashboardSnapshot.value(id: String) = panels.flatMap { it.metrics }.first { it.id == id }.value
}
