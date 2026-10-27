package com.jixiexiaoge.drivingassist.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VehicleObservationTest {
    @Test
    fun `observation retains units signs and explicit false states from each service`() {
        val telemetry = telemetry(
            now = 1000,
            "carState" to mapOf(
                "vEgo" to 10f,
                "aEgo" to -1.25f,
                "softHoldActive" to false,
                "brakeHoldActive" to true,
            ),
            "longitudinalPlan" to mapOf("speeds" to listOf(5f), "accels" to listOf(-0.5f)),
            "radarState" to mapOf("leadOne" to mapOf("status" to true, "dRel" to 21.5f, "vRel" to -2f)),
            "carControl" to mapOf("longActive" to false),
        )

        val result = telemetry.toVehicleObservation()

        assertThat(result.vehicleSpeedKph).isWithin(0.001f).of(36f)
        assertThat(result.vehicleAccelerationMps2).isEqualTo(-1.25f)
        assertThat(result.softHoldActive).isFalse()
        assertThat(result.brakeHoldActive).isTrue()
        assertThat(result.plannedSpeedKph).isWithin(0.001f).of(18f)
        assertThat(result.plannedAccelerationMps2).isEqualTo(-0.5f)
        assertThat(result.leadOneDistanceM).isEqualTo(21.5f)
        assertThat(result.leadOneRelativeSpeedKph).isWithin(0.001f).of(-7.2f)
        assertThat(result.longitudinalActive).isFalse()
    }

    @Test
    fun `expired and missing services withdraw only their own observations`() {
        val telemetry = telemetry(
            now = 1000,
            "carState" to mapOf("vEgo" to 12f, "aEgo" to 0f, "softHoldActive" to true),
            "longitudinalPlan" to mapOf("speeds" to listOf(4f), "accels" to listOf(0f)),
            "radarState" to mapOf("leadOne" to mapOf("status" to false, "dRel" to 20f, "vRel" to 0f)),
        ).copy(
            services = mapOf(
                "carState" to sample("carState", mapOf("vEgo" to 12f, "aEgo" to 0f, "softHoldActive" to true), 100, 600),
                "longitudinalPlan" to sample("longitudinalPlan", mapOf("speeds" to listOf(4f), "accels" to listOf(0f)), 900, 1500),
                "radarState" to sample("radarState", mapOf("leadOne" to mapOf("status" to false, "dRel" to 20f, "vRel" to 0f)), 900, 999),
            ),
        )

        val result = telemetry.toVehicleObservation()

        assertThat(result.vehicleAvailability).isEqualTo(TelemetryAvailability.Expired)
        assertThat(result.vehicleSpeedKph).isNull()
        assertThat(result.softHoldActive).isNull()
        assertThat(result.planningAvailability).isEqualTo(TelemetryAvailability.Fresh)
        assertThat(result.plannedSpeedKph).isWithin(0.001f).of(14.4f)
        assertThat(result.radarAvailability).isEqualTo(TelemetryAvailability.Expired)
        assertThat(result.leadOneSelected).isNull()
        assertThat(result.leadOneDistanceM).isNull()
        assertThat(result.leadOneRelativeSpeedKph).isNull()
        assertThat(result.controlAvailability).isEqualTo(TelemetryAvailability.Missing)
        assertThat(result.longitudinalActive).isNull()
    }

    @Test
    fun `soft hold compact integer representation preserves tri state`() {
        val telemetry = telemetry(1000, "carState" to mapOf("softHoldActive" to 1))

        assertThat(telemetry.toVehicleObservation().softHoldActive).isTrue()
        assertThat(telemetry.toVehicleObservation().brakeHoldActive).isNull()
    }

    private fun telemetry(now: Long, vararg frames: Pair<String, Map<String, Any?>>): CompactTelemetry =
        CompactTelemetry(
            frames.associate { (service, values) -> service to sample(service, values, now, now + 1000) },
            observedAtMs = now,
        )

    private fun sample(service: String, values: Map<String, Any?>, received: Long, expires: Long) =
        CompactServiceSample(CompactFrame(service, 0, values), received, expires)
}
