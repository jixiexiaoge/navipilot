package com.jixiexiaoge.drivingassist.navigation

data class AmapCruiseRoad(
    val receivedAtMs: Long,
    val roadName: String?,
    val speedKph: Double?,
    val headingDeg: Double?,
    val cameraType: Int?,
    val cameraDistanceM: Double?,
    val cameraLimitKph: Int?,
) {
    fun isFresh(nowMs: Long): Boolean = nowMs - receivedAtMs in 0..10_000L
}

enum class AmapSectionPhase { Approaching, Inside, Ended }

data class AmapSectionDisplay(
    val receivedAtMs: Long,
    val routeGeneration: Long,
    val phase: AmapSectionPhase?,
    val limitKph: Int?,
    val averageKph: Double?,
    val remainingM: Double?,
    val startDistanceText: String?,
    val endDistanceText: String?,
    val progress: Double? = null,
) {
    fun isFresh(nowMs: Long, generation: Long): Boolean =
        routeGeneration == generation && nowMs - receivedAtMs in 0..10_000L
}

internal fun amapSectionPhase(keyType: Int, cameraType: Int?, hasNumericDistances: Boolean): AmapSectionPhase? =
    when {
        keyType == 10001 && cameraType == 8 -> AmapSectionPhase.Approaching
        keyType == 10001 && cameraType == 9 -> AmapSectionPhase.Ended
        keyType == 12110 && hasNumericDistances -> AmapSectionPhase.Inside
        else -> null
    }

internal fun amapHeadingLabel(degrees: Double?): String? =
    degrees?.takeIf { it.isFinite() && it in 0.0..<360.0 }?.let {
        val compass = listOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
        "${compass[((it + 22.5) / 45).toInt() % 8]} · ${"%.1f".format(java.util.Locale.ROOT, it)}°"
    }
