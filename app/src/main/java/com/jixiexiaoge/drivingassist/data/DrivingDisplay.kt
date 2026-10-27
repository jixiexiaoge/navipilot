@file:Suppress("MagicNumber")

package com.jixiexiaoge.drivingassist.data

internal data class DrivingDisplay(
    val speedKph: Float? = null,
    val gear: String? = null,
    val steeringDeg: Float? = null,
    val cruiseKph: Float? = null,
    val adoptedLimitKph: Int? = null,
    val suggestedKph: Int? = null,
    val brakeLights: Boolean? = null,
    val leftBlinker: Boolean? = null,
    val rightBlinker: Boolean? = null,
    val leftBlindspot: Boolean? = null,
    val rightBlindspot: Boolean? = null,
    val lateralActive: Boolean? = null,
    val longitudinalActive: Boolean? = null,
    val laneChange: ReceivedLaneChange? = null,
    val alert: ReceivedAlert? = null,
    val speedEvent: ReceivedSpeedEvent? = null,
) {
    val parked: Boolean get() = gear == "park"
}

internal fun CompactTelemetry?.drivingDisplay(display: CarrotDisplaySnapshot): DrivingDisplay {
    val car = this?.fresh("carState")?.frame?.values.orEmpty()
    fun number(key: String, range: ClosedFloatingPointRange<Float>): Float? =
        (car[key] as? Number)?.toFloat()?.takeIf { it.isFinite() && it in range }
    return DrivingDisplay(
        speedKph = number("vEgo", 0f..100f)?.times(3.6f),
        gear = car["gearShifter"] as? String,
        steeringDeg = number("steeringAngleDeg", -1080f..1080f),
        cruiseKph = number("vCruiseCluster", 0.1f..249f),
        adoptedLimitKph = display.adoptedLimitKph,
        suggestedKph = display.desiredSpeedKph,
        brakeLights = car["brakeLights"] as? Boolean,
        leftBlinker = car["leftBlinker"] as? Boolean,
        rightBlinker = car["rightBlinker"] as? Boolean,
        leftBlindspot = display.leftBlindspot,
        rightBlindspot = display.rightBlindspot,
        lateralActive = display.latActive,
        longitudinalActive = display.longActive,
        laneChange = display.laneChange,
        alert = display.alert,
        speedEvent = display.events.filter { it.type in setOf(0, 1, 2, 7, 8, 75, 76) && it.distanceM > 0 }
            .minByOrNull { it.distanceM },
    )
}
