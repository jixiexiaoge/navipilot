package com.jixiexiaoge.drivingassist.navigation

import kotlin.math.abs

enum class DrivingMode(val storageKey: String) {
    HIGHWAY_EAP("highway_eap"),
    ELEVATED_NOA("elevated_noa"),
    REGULAR_NOO("regular_noo");

    val prefersLeft: Boolean get() = this != REGULAR_NOO

    companion object {
        fun fromStorage(value: String?): DrivingMode? = entries.firstOrNull { it.storageKey == value }
    }
}

internal enum class DrivingRoadMatch { Waiting, Unknown, Mismatch, ExpresswayUnconfirmed, Matching }
internal enum class DrivingLaneAdvice { Unavailable, OrderUnknown, DirectionUnknown, NoRecommendation, RoutePreferred }
internal enum class DrivingAdviceHold { ScenePending, MapUnavailable, RoadUnknown, RoadMismatch, OffRoute, VehicleAlert, RouteRecovering }
internal enum class DrivingLanePriority { ModePreference, RouteConstraint }
internal data class DrivingSceneEvidence(val candidate: DrivingMode, val samples: Int, val spanMs: Long)

/** Map-only advisory. A numbered candidate is not ego localization or a maneuver request. */
internal data class DrivingModeAdvice(
    val roadMatch: DrivingRoadMatch,
    val laneAdvice: DrivingLaneAdvice,
    val candidateLane: Int?,
    val routeLanes: List<Int>,
    val hold: DrivingAdviceHold? = null,
    val priority: DrivingLanePriority? = null,
)

internal fun buildDrivingModeAdvice(
    mode: DrivingMode,
    map: AmapNavigationDisplay,
    offRoute: Boolean = false,
    takeoverAlert: Boolean = false,
    sceneReady: Boolean = true,
    routeReady: Boolean = true,
): DrivingModeAdvice {
    val roadMatch = when {
        map.state != AmapDisplayState.Fresh -> DrivingRoadMatch.Waiting
        map.reportedRoadType == null || map.reportedRoadType == 10 -> DrivingRoadMatch.Unknown
        mode == DrivingMode.HIGHWAY_EAP && map.reportedRoadType == 0 -> DrivingRoadMatch.Matching
        mode == DrivingMode.ELEVATED_NOA && map.reportedRoadType == 6 -> DrivingRoadMatch.ExpresswayUnconfirmed
        mode == DrivingMode.REGULAR_NOO && map.reportedRoadType in setOf(1, 2, 3, 4, 5, 7, 8, 9) ->
            DrivingRoadMatch.Matching
        else -> DrivingRoadMatch.Mismatch
    }
    val hold = when {
        takeoverAlert -> DrivingAdviceHold.VehicleAlert
        offRoute -> DrivingAdviceHold.OffRoute
        !sceneReady -> DrivingAdviceHold.ScenePending
        roadMatch == DrivingRoadMatch.Waiting -> DrivingAdviceHold.MapUnavailable
        roadMatch == DrivingRoadMatch.Unknown -> DrivingAdviceHold.RoadUnknown
        roadMatch == DrivingRoadMatch.Mismatch -> DrivingAdviceHold.RoadMismatch
        !routeReady -> DrivingAdviceHold.RouteRecovering
        else -> null
    }
    fun empty(reason: DrivingLaneAdvice) = DrivingModeAdvice(roadMatch, reason, null, emptyList(), hold)
    if (hold != null) return empty(DrivingLaneAdvice.Unavailable)
    val junction = map.junction ?: return empty(DrivingLaneAdvice.Unavailable)
    if (!junction.leftToRightKnown || junction.lanes.size !in 1..16 ||
        junction.lanes.map { it.number } != (1..junction.lanes.size).toList()) {
        return empty(DrivingLaneAdvice.OrderUnknown)
    }
    val recommended = junction.lanes.filter { it.recommended }
    if (recommended.isEmpty()) return empty(DrivingLaneAdvice.NoRecommendation)
    if (recommended.any { it.direction == null }) return empty(DrivingLaneAdvice.DirectionUnknown)
    val numbers = recommended.map { it.number }
    val preferred = if (mode.prefersLeft) minOf(2, junction.lanes.size) else junction.lanes.size
    return DrivingModeAdvice(
        roadMatch, DrivingLaneAdvice.RoutePreferred,
        if (mode.prefersLeft) numbers.minWithOrNull(compareBy<Int> { abs(it - 2) }.thenBy { it })
        else numbers.maxOrNull(), numbers,
        priority = if (preferred in numbers) DrivingLanePriority.ModePreference else DrivingLanePriority.RouteConstraint,
    )
}

/** Counts distinct guide broadcasts, never repeated UI polling of the same packet. */
internal class AutomaticDrivingMode {
    var mode: DrivingMode? = null
        private set
    private var pending: DrivingMode? = null
    private var generation: Long? = null
    private var lastReceipt = Long.MIN_VALUE
    private var firstReceipt = 0L
    private var count = 0
    private var lastNow: Long? = null
    val evidence: DrivingSceneEvidence?
        get() = pending?.let { DrivingSceneEvidence(it, count, (lastReceipt - firstReceipt).coerceAtLeast(0)) }

    fun reset() {
        mode = null
        pending = null
        generation = null
        lastReceipt = Long.MIN_VALUE
        count = 0
    }

    fun update(roadType: Int?, receiptMs: Long?, routeGeneration: Long?, nowMs: Long): DrivingMode? {
        if (lastNow?.let { nowMs < it } == true) reset()
        lastNow = nowMs
        val candidate = when (roadType) {
            0 -> DrivingMode.HIGHWAY_EAP
            6 -> DrivingMode.ELEVATED_NOA
            1, 2, 3, 4, 5, 7, 8, 9 -> DrivingMode.REGULAR_NOO
            else -> null
        }
        if (candidate == null || receiptMs == null || routeGeneration == null ||
            nowMs - receiptMs !in 0..NaviV2Constants.STALE_TIMEOUT_MS) {
            reset()
            return null
        }
        if (generation != routeGeneration || receiptMs < lastReceipt ||
            (lastReceipt != Long.MIN_VALUE && receiptMs - lastReceipt > NaviV2Constants.STALE_TIMEOUT_MS)) {
            reset()
            generation = routeGeneration
        }
        if (receiptMs == lastReceipt) {
            // The same packet cannot establish two different road scenes.
            if (pending != candidate) reset()
            return mode?.takeIf { it == candidate }
        }
        lastReceipt = receiptMs
        if (pending != candidate) {
            mode = null
            pending = candidate
            firstReceipt = receiptMs
            count = 1
        } else {
            count = (count + 1).coerceAtMost(3)
        }
        if (count >= 3 && receiptMs - firstReceipt >= 2000) mode = candidate
        // Withhold the old scene while another scene is stabilizing.
        return mode?.takeIf { it == candidate }
    }
}
