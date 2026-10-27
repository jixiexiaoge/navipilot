package com.jixiexiaoge.drivingassist.navigation

private const val AMAP_EXPRESSWAY_TYPE = 6
private const val LAST_FIRST_GROUP_REGULAR_TYPE = 5
private const val FIRST_SECOND_GROUP_REGULAR_TYPE = 7
private const val LAST_SECOND_GROUP_REGULAR_TYPE = 9
private const val CARROT_REGULAR_CATEGORY = 6
internal const val MAX_SIGNAL_DIRECTION = 5
internal const val MAX_SIGNAL_SECONDS = 999

data class AmapRouteSource(
    val remainingDistanceM: Int?,
    val remainingTimeSec: Int?,
    val totalDistanceM: Int?,
    val routeGeneration: Long,
    val receivedAtMs: Long,
)

data class AmapSdiSource(
    val type: Int,
    val distanceM: Int,
    val speedLimitKph: Int?,
    val routeGeneration: Long,
    val receivedAtMs: Long,
    val blockType: Int? = null,
) {
    fun toJson() = org.json.JSONObject().apply {
        put("type", type)
        put("distance_m", distanceM)
        speedLimitKph?.let { put("speed_limit_kph", it) }
        blockType?.let {
            put("block_type", it)
            speedLimitKph?.let { limit -> put("block_speed_kph", limit) }
            put("block_distance_m", distanceM)
        }
    }
}

data class AmapSectionSource(
    val speedLimitKph: Int,
    val remainingM: Double,
    val averageKph: Double?,
    val progress: Double?,
    val routeGeneration: Long,
    val receivedAtMs: Long,
)

data class AmapRoadCategorySource(
    val roadType: Int,
    val routeGeneration: Long,
    val receivedAtMs: Long,
) {
    fun carrotCategory(): Int? = when (roadType) {
        0, AMAP_EXPRESSWAY_TYPE -> 1
        in 1..LAST_FIRST_GROUP_REGULAR_TYPE,
        in FIRST_SECOND_GROUP_REGULAR_TYPE..LAST_SECOND_GROUP_REGULAR_TYPE -> CARROT_REGULAR_CATEGORY
        else -> null
    }
}

data class AmapNavigationSignalSource(
    val status: Int,
    val direction: Int,
    val countdownSec: Int,
    val routeGeneration: Long,
    val receivedAtMs: Long,
) {
    fun toSignal(nowMs: Long): TrafficSignalData? {
        val ageMs = nowMs - receivedAtMs
        // Other direction codes currently have only log labels, not a verified wire contract.
        val verifiedPhase = status == 1 || (status == 2 && direction == 1)
        val timed = countdownSec in 1..MAX_SIGNAL_SECONDS && ageMs in 0 until countdownSec * 1000L
        if (!verifiedPhase || direction !in 0..MAX_SIGNAL_DIRECTION || !timed) return null
        val remaining = countdownSec - (ageMs / 1000).toInt()
        return TrafficSignalData(
            visible = true,
            redValid = status == 1, redOn = status == 1, redRemainSec = remaining,
            leftValid = status == 2 && direction == 1,
            leftOn = status == 2 && direction == 1, leftRemainSec = remaining,
        )
    }
}
