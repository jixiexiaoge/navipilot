package com.jixiexiaoge.drivingassist.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OvertakeDisplaySnapshotTest {
    @Test
    fun `automation observations never turn fresh or active telemetry into authority`() {
        val frames = listOf("carState", "controlsState", "carControl", "modelV2", "lateralPlan", "selfdriveState")
            .map { CompactFrame(it, 1, mapOf("active" to true, "enabled" to true, "frameId" to 1L)) }
        val data = CompactTelemetryStore().accept(frames, 1000)
        assertThat(resolveAutomationObservation(false, false, data)).isEqualTo(AutomationObservation.Disconnected)
        assertThat(resolveAutomationObservation(true, false, null)).isEqualTo(AutomationObservation.Waiting)
        assertThat(resolveAutomationObservation(true, false, data.copy(
            services = data.services - "carControl"))).isEqualTo(AutomationObservation.Waiting)
        assertThat(resolveAutomationObservation(true, false, data)).isEqualTo(AutomationObservation.MissingAuthority)
        assertThat(resolveAutomationObservation(true, true, data)).isEqualTo(AutomationObservation.Expired)
        val firstExpiry = data.services.values.minOf { it.expiresAtMs }
        assertThat(resolveAutomationObservation(true, false, data.copy(observedAtMs = firstExpiry)))
            .isEqualTo(AutomationObservation.Expired)
        assertThat(resolveAutomationObservation(true, false, data.copy(observedAtMs = 999)))
            .isEqualTo(AutomationObservation.Expired)
        // Later unrelated traffic cannot renew an expired required observation.
        assertThat(resolveAutomationObservation(true, false, data.copy(observedAtMs = firstExpiry,
            services = data.services + ("radarState" to data.services.getValue("carState").copy(
                receivedAtMs = firstExpiry, expiresAtMs = firstExpiry + 1000)))))
            .isEqualTo(AutomationObservation.Expired)
    }

    @Test
    fun `side selections markings and boundary indices remain independent`() {
        // Given
        val store = CompactTelemetryStore()
        val data = store.accept(listOf(
            CompactFrame("carState", 1, mapOf("leftLaneLine" to 21, "rightLaneLine" to 10,
                "leftBlindspot" to true, "rightBlindspot" to false)),
            CompactFrame("modelV2", 1, mapOf("frameId" to 1L,
                "laneLineProbs" to listOf(0.1f, 0.8f, 0.9f, 0.2f))),
            CompactFrame("radarState", 1, mapOf(
                "leadLeft" to mapOf("status" to true, "dRel" to 40f, "vRel" to -2f),
                "leadRight" to mapOf("status" to false, "dRel" to 90f, "vRel" to 5f))),
        ), 1000)
        // When
        val display = data.toOvertakeDisplaySnapshot()
        // Then
        assertThat(display.left.laneCode).isEqualTo(21)
        assertThat(display.right.laneCode).isEqualTo(10)
        assertThat(display.left.boundaryProbability).isEqualTo(0.8f)
        assertThat(display.right.boundaryProbability).isEqualTo(0.9f)
        assertThat(display.left.outerProbability).isEqualTo(0.1f)
        assertThat(display.right.outerProbability).isEqualTo(0.2f)
        assertThat(display.left.targetRelativeKph).isWithin(0.001f).of(-7.2f)
        assertThat(display.right.targetPresent).isFalse()
        assertThat(display.right.targetDistanceM).isNull()
        assertThat(display.right.blindspot).isFalse()
        val carExpired = data.copy(observedAtMs = 1500).toOvertakeDisplaySnapshot()
        assertThat(carExpired.left.laneCode).isNull()
        assertThat(carExpired.right.blindspot).isNull()
        assertThat(carExpired.left.targetDistanceM).isEqualTo(40f)
        val expired = data.copy(observedAtMs = 1750).toOvertakeDisplaySnapshot()
        assertThat(expired.left.targetPresent).isNull()
        assertThat(expired.left.boundaryProbability).isNull()
    }

    @Test
    fun `missing invalid and partially reported target do not become clearance`() {
        // Given / When
        val data = CompactTelemetryStore().accept(listOf(
            CompactFrame("carState", 1, mapOf("leftLaneLine" to 0.5, "leftBlindspot" to "false")),
            CompactFrame("modelV2", 1, mapOf("frameId" to 1L,
                "laneLineProbs" to listOf(Float.NaN, -1f, 2f, Float.POSITIVE_INFINITY))),
            CompactFrame("radarState", 1, mapOf("leadLeft" to mapOf(
                "status" to true, "dRel" to -1f, "vRel" to Float.MAX_VALUE))),
        ), 1000).toOvertakeDisplaySnapshot()
        // Then
        assertThat(data.left.laneCode).isNull()
        assertThat(data.left.blindspot).isNull()
        assertThat(data.left.boundaryProbability).isNull()
        assertThat(data.left.targetPresent).isTrue()
        assertThat(data.left.targetDistanceM).isNull()
        assertThat(data.left.targetRelativeKph).isNull()
        assertThat(data.right.targetPresent).isNull()
        assertThat((null as CompactTelemetry?).toOvertakeDisplaySnapshot().left.vehicleAvailability)
            .isEqualTo(TelemetryAvailability.Missing)
    }
}
