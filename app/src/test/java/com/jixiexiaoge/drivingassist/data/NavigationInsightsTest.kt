package com.jixiexiaoge.drivingassist.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NavigationInsightsTest {
    private fun frame(name: String, values: Map<String, Any?>) = CompactFrame(name, 1,
        if (name == "modelV2") mapOf("frameId" to 1L) + values else values, 0)
    private fun receive(vararg values: Pair<String, Map<String, Any?>>) =
        CompactTelemetryStore().accept(values.map { frame(it.first, it.second) }, 1000)
    private fun navi(signal: Map<String, Any?>, connected: Boolean = true, offRoute: Boolean = false) =
        mapOf("schemaVersion" to 1, "connected" to connected,
            "navigationStatus" to mapOf("guidanceActive" to true, "offRoute" to offRoute),
            "trafficSignal" to signal)

    @Test
    fun `blindspot false is distinct from missing and radar absence is distinct from stale`() {
        // Given / When
        val telemetry = receive(
            "carState" to mapOf("leftBlindspot" to true, "rightBlindspot" to false),
            "radarState" to mapOf("leadOne" to mapOf("status" to false)),
        )
        val data = telemetry.toDisplaySnapshot()
        // Then
        assertThat(data.leftBlindspot).isTrue()
        assertThat(data.rightBlindspot).isFalse()
        assertThat(data.leadPresent).isFalse()
        assertThat(data.lead).isNull()
        val expiredCar = telemetry.copy(observedAtMs = 1500).toDisplaySnapshot()
        assertThat(expiredCar.leftBlindspot).isNull()
        assertThat(expiredCar.rightBlindspot).isNull()
        assertThat(expiredCar.leadPresent).isFalse()
        assertThat(telemetry.copy(observedAtMs = 1750).toDisplaySnapshot().leadPresent).isNull()
        assertThat(receive("carState" to mapOf("leftBlindspot" to "false")).toDisplaySnapshot().leftBlindspot).isNull()
    }

    @Test
    fun `valid second lead converts units invalid active lead remains incomplete`() {
        // Given / When
        val data = receive("radarState" to mapOf(
            "leadOne" to mapOf("status" to true, "dRel" to Float.NaN),
            "leadTwo" to mapOf("status" to true, "dRel" to 40f, "yRel" to 0f, "vRel" to -2f),
            "leadsLeft" to emptyList<Map<String, Any?>>(),
            "leadsCenter" to listOf(mapOf("status" to true, "dRel" to 10f, "vRel" to 0f)),
            "leadsCutIn" to listOf(mapOf("status" to true, "dRel" to 10f, "vRel" to -1f),
                mapOf("status" to false, "dRel" to 12f, "vRel" to 1f),
                mapOf("status" to true, "dRel" to Float.NaN, "vRel" to 0f)),
        )).toDisplaySnapshot()
        // Then
        assertThat(data.leadPresent).isTrue()
        assertThat(data.lead).isNull()
        assertThat(data.secondLead).isEqualTo(ReceivedLead(40f, -7.2f))
        assertThat(data.cutInCount).isEqualTo(1)
        assertThat(data.radarCandidateCounts).containsExactly(0, 1, null).inOrder()
        assertThat((null as CompactTelemetry?).toDisplaySnapshot().cutInCount).isNull()
    }

    @Test
    fun `planner state style cruise target and jerk preserve explicit zeros and units`() {
        // Given / When
        val data = receive("longitudinalPlan" to mapOf("xState" to 0, "trafficState" to 0,
            "myDrivingMode" to 2, "cruiseTarget" to 80f, "jerks" to listOf(-0.4f)),
            "controlsState" to mapOf("curvature" to 0f, "desiredCurvature" to 0.01f,
                "actualLateralAccel" to -0.2f, "desiredLateralAccel" to 0f, "lateralOutput" to 0.3f))
            .toDisplaySnapshot()
        // Then
        assertThat(data.behavior).isEqualTo(ReceivedBehavior(0, 0, 2, 80f, -0.4f))
        assertThat(data.lateral).isEqualTo(ReceivedLateral(0f, 0.01f, -0.2f, 0f, 0.3f))
        val invalid = receive("longitudinalPlan" to mapOf("xState" to 8, "trafficState" to -1,
            "myDrivingMode" to 0, "cruiseTarget" to Float.POSITIVE_INFINITY, "jerks" to listOf(Float.NaN)))
            .toDisplaySnapshot()
        assertThat(invalid.behavior).isEqualTo(ReceivedBehavior(null, null, null, null, null))
    }

    @Test
    fun `navigation lamps require explicit validity visible supported linked guiding session`() {
        // Given
        val signal = mapOf("visible" to true, "distanceM" to 90, "redValid" to true, "redOn" to false,
            "redRemainSec" to 0, "greenValid" to true, "greenOn" to true, "greenRemainSec" to 8,
            "leftValid" to false, "leftOn" to true, "leftRemainSec" to 12)
        // When / Then
        val data = receive("carrotNavi" to navi(signal)).toDisplaySnapshot()
        assertThat(data.navigationSignal).isEqualTo(ReceivedNavigationSignal(90,
            listOf(ReceivedLamp("red", false, 0), ReceivedLamp("green", true, 8))))
        listOf(navi(signal, connected = false), navi(signal, offRoute = true),
            navi(signal) + ("schemaVersion" to 2),
            navi(signal) + ("navigationStatus" to mapOf("guidanceActive" to false, "offRoute" to false)),
            navi(signal + ("visible" to false))).forEach {
            assertThat(receive("carrotNavi" to it).toDisplaySnapshot().navigationSignal).isNull()
        }
        assertThat(receive("carrotNavi" to navi(mapOf("visible" to true, "greenOn" to true)))
            .toDisplaySnapshot().navigationSignal!!.lamps).isEmpty()
    }

    @Test
    fun `service expiry removes new fields without unrelated device data renewing them`() {
        // Given
        val store = CompactTelemetryStore()
        store.accept(listOf(
            frame("carState", mapOf("leftBlindspot" to true)),
            frame("longitudinalPlan", mapOf("xState" to 5)),
            frame("controlsState", mapOf("curvature" to 0f)),
            frame("modelV2", mapOf("laneLineProbs" to listOf(0.1f, 0.8f, 0.9f, 0.2f),
                "roadEdgeStds" to listOf(0.1f, 0.2f), "frameAge" to 1, "frameDropPerc" to 0f)),
            frame("carrotNavi", navi(mapOf("visible" to true, "redValid" to true, "redOn" to true))),
        ), 1000)
        // When / Then
        val fresh = store.snapshot(1100).toDisplaySnapshot()
        assertThat(fresh.quality!!.lineProbabilities).containsExactly(0.1f, 0.8f, 0.9f, 0.2f).inOrder()
        assertThat(fresh.modelAvailability).isEqualTo(TelemetryAvailability.Fresh)
        val expired = store.accept(listOf(frame("deviceState", emptyMap())), 4000).toDisplaySnapshot()
        assertThat(expired.leftBlindspot).isNull()
        assertThat(expired.behavior).isNull()
        assertThat(expired.lateral).isNull()
        assertThat(expired.quality).isNull()
        assertThat(expired.modelAvailability).isEqualTo(TelemetryAvailability.Expired)
        assertThat(expired.navigationSignal).isNull()
    }
}
