package com.jixiexiaoge.drivingassist

internal data class AutomaticCruiseSpeedInput(
    val cruiseEngaged: Boolean,
    val cruiseSetKph: Int?,
    val roadLimitKph: Int?,
    val routeGeneration: Long?,
    val recoveryCapKph: Int? = null,
)

internal class AutomaticCruiseSpeedController(
    private val stableDurationMs: Long = 3_000,
    private val retryDurationMs: Long = 15_000,
    private val failedAttemptDelayMs: Long = 1_000,
) {
    private var candidateLimitKph: Int? = null
    private var candidateRouteGeneration: Long? = null
    private var candidateSinceMs: Long? = null
    private var pendingLimitKph: Int? = null
    private var pendingSentAtMs: Long? = null
    private var nextAttemptAtMs = Long.MIN_VALUE
    private var lastNowMs: Long? = null
    private var lastCruiseKph: Int? = null
    private var lastRouteGeneration: Long? = null
    private var recoveryCancelled = false
    private var completedRecoveryRoute: Long? = null

    @Synchronized
    fun update(input: AutomaticCruiseSpeedInput, nowMs: Long): Int? {
        if (lastNowMs?.let { nowMs < it } == true) reset()
        lastNowMs = nowMs

        if (!input.cruiseEngaged) {
            reset()
            lastNowMs = nowMs
            return null
        }

        val cruise = input.cruiseSetKph?.takeIf { it in 1..249 }
        val route = input.routeGeneration
        if (route != null && route != lastRouteGeneration) {
            clearPending()
            recoveryCancelled = false
            lastCruiseKph = null
        }
        if (input.recoveryCapKph != null && cruise != null &&
            lastCruiseKph?.let { it != cruise } == true &&
            cruise != pendingLimitKph) recoveryCancelled = true
        if (cruise != null) lastCruiseKph = cruise
        if (route != null) lastRouteGeneration = route
        val rawLimit = input.roadLimitKph?.takeIf { it in MIN_LIMIT_KPH..MAX_LIMIT_KPH }
            ?.takeUnless { recoveryCancelled }
        val atRoadLimit = rawLimit != null && cruise != null && cruise >= rawLimit
        val policyAtTarget = input.recoveryCapKph?.let { rawLimit != null && it >= rawLimit } == true
        if (atRoadLimit && policyAtTarget) completedRecoveryRoute = route
        val limit = if (input.recoveryCapKph != null) {
            rawLimit?.let {
                minOf(it, input.recoveryCapKph, (cruise ?: 0) + 10)
            }?.takeIf { it in MIN_LIMIT_KPH..MAX_LIMIT_KPH }
        } else rawLimit
        val feedbackMissing = cruise == null || route == null
        if (feedbackMissing || limit == null || cruise >= limit) {
            clearCandidate()
            if (pendingLimitKph?.let { cruise != null && cruise >= it } == true) clearPending()
            return null
        }

        if (pendingLimitKph?.let { cruise >= it } == true) clearPending()
        if (candidateLimitKph != limit || candidateRouteGeneration != route) {
            candidateLimitKph = limit
            candidateRouteGeneration = route
            candidateSinceMs = nowMs
            return null
        }

        val stableSince = candidateSinceMs ?: return null
        if (nowMs - stableSince < stableDurationMs || nowMs < nextAttemptAtMs) return null

        val pending = pendingLimitKph
        val sentAt = pendingSentAtMs
        if (pending != null && limit <= pending && sentAt != null && nowMs - sentAt < retryDurationMs) {
            return null
        }

        nextAttemptAtMs = nowMs + failedAttemptDelayMs
        return limit
    }

    @Synchronized
    fun recordSent(limitKph: Int, nowMs: Long) {
        pendingLimitKph = limitKph
        pendingSentAtMs = nowMs
    }

    @Synchronized
    fun takeRecoveryCompletion(): Long? =
        completedRecoveryRoute.also { completedRecoveryRoute = null }

    @Synchronized
    fun reset() {
        clearCandidate()
        clearPending()
        nextAttemptAtMs = Long.MIN_VALUE
        lastNowMs = null
        lastCruiseKph = null
        lastRouteGeneration = null
        recoveryCancelled = false
        completedRecoveryRoute = null
    }

    private fun clearCandidate() {
        candidateLimitKph = null
        candidateRouteGeneration = null
        candidateSinceMs = null
    }

    private fun clearPending() {
        pendingLimitKph = null
        pendingSentAtMs = null
    }

    private companion object {
        const val MIN_LIMIT_KPH = 60
        const val MAX_LIMIT_KPH = 120
    }
}
