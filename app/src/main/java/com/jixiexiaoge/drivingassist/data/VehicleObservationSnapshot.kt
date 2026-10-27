package com.jixiexiaoge.drivingassist.data

private const val KPH_PER_MPS = 3.6f

/** Read-only 7000 observations; every service retains its own receipt deadline. */
internal data class VehicleObservationSnapshot(
    val vehicleAvailability: TelemetryAvailability,
    val vehicleReceptionAgeMs: Long?,
    val vehicleSpeedKph: Float?,
    val vehicleAccelerationMps2: Float?,
    val softHoldActive: Boolean?,
    val brakeHoldActive: Boolean?,
    val planningAvailability: TelemetryAvailability,
    val planningReceptionAgeMs: Long?,
    val plannedSpeedKph: Float?,
    val plannedAccelerationMps2: Float?,
    val radarAvailability: TelemetryAvailability,
    val radarReceptionAgeMs: Long?,
    val leadOneSelected: Boolean?,
    val leadOneDistanceM: Float?,
    val leadOneRelativeSpeedKph: Float?,
    val controlAvailability: TelemetryAvailability,
    val controlReceptionAgeMs: Long?,
    val longitudinalActive: Boolean?,
)

internal fun CompactTelemetry?.toVehicleObservation(): VehicleObservationSnapshot {
    fun availability(service: String): TelemetryAvailability = when {
        this?.services?.containsKey(service) != true -> TelemetryAvailability.Missing
        fresh(service) == null -> TelemetryAvailability.Expired
        else -> TelemetryAvailability.Fresh
    }
    fun values(service: String) = this?.fresh(service)?.frame?.values
    fun receptionAge(service: String) = this?.services?.get(service)?.let {
        (observedAtMs - it.receivedAtMs).coerceAtLeast(0)
    }
    fun numberToKph(value: Float?) = value?.takeIf { it.isFinite() }
        ?.times(KPH_PER_MPS)?.takeIf { it.isFinite() }

    val car = values("carState")
    val plan = values("longitudinalPlan")
    val radar = values("radarState")
    val lead = radar?.objectValue("leadOne")
    val selected = lead?.get("status") as? Boolean
    val leadDistance = if (selected == true) lead?.finiteValue("dRel") else null
    val leadRelativeSpeed = if (selected == true) numberToKph(lead?.finiteValue("vRel")) else null

    return VehicleObservationSnapshot(
        vehicleAvailability = availability("carState"),
        vehicleReceptionAgeMs = receptionAge("carState"),
        vehicleSpeedKph = numberToKph(car?.finiteValue("vEgo")),
        vehicleAccelerationMps2 = car?.finiteValue("aEgo"),
        softHoldActive = car?.get("softHoldActive").observationBoolean(),
        brakeHoldActive = car?.get("brakeHoldActive") as? Boolean,
        planningAvailability = availability("longitudinalPlan"),
        planningReceptionAgeMs = receptionAge("longitudinalPlan"),
        plannedSpeedKph = numberToKph(plan?.firstFiniteValue("speeds")),
        plannedAccelerationMps2 = plan?.firstFiniteValue("accels"),
        radarAvailability = availability("radarState"),
        radarReceptionAgeMs = receptionAge("radarState"),
        leadOneSelected = selected,
        leadOneDistanceM = leadDistance,
        leadOneRelativeSpeedKph = leadRelativeSpeed,
        controlAvailability = availability("carControl"),
        controlReceptionAgeMs = receptionAge("carControl"),
        longitudinalActive = values("carControl")?.get("longActive") as? Boolean,
    )
}

private fun Map<String, Any?>.finiteValue(key: String): Float? =
    (this[key] as? Number)?.toFloat()?.takeIf { it.isFinite() }

private fun Map<String, Any?>.firstFiniteValue(key: String): Float? =
    ((this[key] as? List<*>)?.firstOrNull() as? Number)?.toFloat()?.takeIf { it.isFinite() }

private fun Any?.observationBoolean(): Boolean? = when (this) {
    is Boolean -> this
    is Number -> when (toDouble()) {
        0.0 -> false
        1.0 -> true
        else -> null
    }
    else -> null
}
