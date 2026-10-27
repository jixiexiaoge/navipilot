package com.jixiexiaoge.drivingassist.data

internal data class OvertakeSideDisplay(
    val vehicleAvailability: TelemetryAvailability,
    val targetAvailability: TelemetryAvailability,
    val modelAvailability: TelemetryAvailability,
    val blindspot: Boolean?,
    val laneCode: Int?,
    val boundaryProbability: Float?,
    val outerProbability: Float?,
    val targetPresent: Boolean?,
    val targetDistanceM: Float?,
    val targetRelativeKph: Float?,
)

internal data class OvertakeDisplaySnapshot(
    val left: OvertakeSideDisplay,
    val right: OvertakeSideDisplay,
)

/** Observations only. Radar selections do not prove occupancy of an adjacent travel lane. */
internal fun CompactTelemetry?.toOvertakeDisplaySnapshot(): OvertakeDisplaySnapshot {
    fun availability(name: String) = when {
        this?.services?.containsKey(name) != true -> TelemetryAvailability.Missing
        fresh(name) == null -> TelemetryAvailability.Expired
        else -> TelemetryAvailability.Fresh
    }
    val car = this?.fresh("carState")?.frame?.values
    val model = this?.fresh("modelV2")?.frame?.values
    val radar = this?.fresh("radarState")?.frame?.values
    val probabilities = (model?.get("laneLineProbs") as? List<*>)?.takeIf { it.size == 4 }
    fun probability(index: Int) = (probabilities?.get(index) as? Number)?.toFloat()
        ?.takeIf { it.isFinite() && it in 0f..1f }
    fun side(left: Boolean): OvertakeSideDisplay {
        val prefix = if (left) "left" else "right"
        val target = radar?.objectValue(if (left) "leadLeft" else "leadRight")
        val present = target?.get("status") as? Boolean
        val distance = target?.floatValue("dRel")?.takeIf { it.isFinite() && it > 0f && present == true }
        val relative = target?.floatValue("vRel")?.takeIf { it.isFinite() && present == true }
            ?.times(3.6f)?.takeIf(Float::isFinite)
        val code = (car?.get("${prefix}LaneLine") as? Number)?.toDouble()?.takeIf {
            it.isFinite() && it % 1.0 == 0.0 && it in -32768.0..32767.0
        }?.toInt()
        return OvertakeSideDisplay(
            availability("carState"), availability("radarState"), availability("modelV2"),
            car?.get("${prefix}Blindspot") as? Boolean, code,
            probability(if (left) 1 else 2), probability(if (left) 0 else 3),
            present, distance, relative,
        )
    }
    return OvertakeDisplaySnapshot(side(true), side(false))
}
