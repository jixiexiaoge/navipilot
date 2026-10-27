package com.jixiexiaoge.drivingassist.navigation

import com.jixiexiaoge.drivingassist.data.NavigationReception

internal enum class RouteTaskPhase { Idle, Waiting, Guiding, OffRoute, Recovering, Expired, VehicleAlert }

internal data class RouteTaskState(
    val phase: RouteTaskPhase = RouteTaskPhase.Waiting,
    val routeGeneration: Long? = null,
    val current: AmapDisplayAction? = null,
    val next: AmapDisplayAction? = null,
    val exit: String? = null,
    val destination: String? = null,
    val receivedAtMs: Long? = null,
    val validUntilMs: Long? = null,
    val confirmationPackets: Int = 0,
    val newRouteObserved: Boolean = false,
    val recoveredWithoutRouteChange: Boolean = false,
) {
    val ready: Boolean get() = phase == RouteTaskPhase.Guiding
    val closelySpacedActions: Boolean
        get() = ready && current != null && next?.remainingSec in 0..30
}

/** Read-only route evidence, never permission to maneuver or feedback to the publisher. */
internal class RouteTaskTracker {
    private var generation: Long? = null
    private var acceptedReceipt: Long? = null
    private var confirmationReceipt: Long? = null
    private var packets = 0
    private var offRouteLatched = false
    private var recoveryAfter = Long.MIN_VALUE
    private var updated = false
    private var recovered = false
    private var confirmationRequired = false
    private var lastNow: Long? = null
    var state = RouteTaskState()
        private set

    fun update(map: AmapNavigationDisplay, vehicle: NavigationReception, alert: Boolean, nowMs: Long): RouteTaskState {
        if (lastNow?.let { nowMs < it } == true) {
            val previouslyOffRoute = offRouteLatched
            clear()
            offRouteLatched = previouslyOffRoute
            recoveryAfter = nowMs
            confirmationRequired = true
        }
        lastNow = nowMs
        if (map.state in setOf(AmapDisplayState.Inactive, AmapDisplayState.Idle)) {
            clear()
            return publish(RouteTaskPhase.Idle)
        }
        if (generation != map.routeGeneration) {
            updated = generation != null
            confirmationRequired = confirmationRequired || updated || offRouteLatched
            generation = map.routeGeneration
            acceptedReceipt = null
            confirmationReceipt = null
            packets = 0
            recovered = false
        }
        if (vehicle == NavigationReception.OffRoute) {
            offRouteLatched = true
            confirmationRequired = true
            recoveryAfter = nowMs
            confirmationReceipt = null
            packets = 0
            return publish(RouteTaskPhase.OffRoute)
        }
        if (alert) {
            confirmationRequired = true
            confirmationReceipt = null
            packets = 0
            recoveryAfter = maxOf(recoveryAfter, nowMs)
            return publish(RouteTaskPhase.VehicleAlert)
        }
        val guide = map.guideDetails?.takeIf {
            map.state == AmapDisplayState.Fresh && it.routeGeneration == generation &&
                nowMs - it.receivedAtMs in 0..NaviV2Constants.STALE_TIMEOUT_MS &&
                it.receivedAtMs > recoveryAfter
        }
        if (guide == null) {
            if (acceptedReceipt != null) {
                confirmationRequired = true
                recoveryAfter = maxOf(recoveryAfter, nowMs)
            }
            confirmationReceipt = null
            packets = 0
            return publish(if (offRouteLatched) RouteTaskPhase.Recovering
                else if (map.state == AmapDisplayState.Expired) RouteTaskPhase.Expired else RouteTaskPhase.Waiting)
        }
        if (offRouteLatched && vehicle != NavigationReception.Guiding) {
            confirmationReceipt = null
            packets = 0
            return publish(RouteTaskPhase.Recovering)
        }
        if (acceptedReceipt?.let { guide.receivedAtMs < it } == true) {
            confirmationRequired = true
            confirmationReceipt = null
            packets = 0
            return publish(RouteTaskPhase.Waiting)
        }
        if (confirmationReceipt?.let { guide.receivedAtMs < it } == true) {
            packets = 0
            return publish(RouteTaskPhase.Recovering)
        }
        if (confirmationReceipt != guide.receivedAtMs) {
            packets = (packets + 1).coerceAtMost(2)
            confirmationReceipt = guide.receivedAtMs
        }
        // First route needs one guide; route changes and recovery need two distinct new guides.
        if (confirmationRequired && packets < 2) return publish(RouteTaskPhase.Recovering)
        if (offRouteLatched) {
            recovered = !updated
            offRouteLatched = false
        }
        acceptedReceipt = guide.receivedAtMs
        confirmationRequired = false
        state = RouteTaskState(RouteTaskPhase.Guiding, generation, map.current, map.next, map.exit,
            map.destination, guide.receivedAtMs, guide.receivedAtMs + NaviV2Constants.STALE_TIMEOUT_MS,
            packets, updated, recovered)
        return state
    }

    private fun publish(phase: RouteTaskPhase): RouteTaskState {
        state = RouteTaskState(phase, generation, confirmationPackets = packets,
            newRouteObserved = updated, recoveredWithoutRouteChange = recovered)
        return state
    }

    private fun clear() {
        generation = null
        acceptedReceipt = null
        confirmationReceipt = null
        packets = 0
        offRouteLatched = false
        recoveryAfter = Long.MIN_VALUE
        updated = false
        recovered = false
        confirmationRequired = false
    }
}
