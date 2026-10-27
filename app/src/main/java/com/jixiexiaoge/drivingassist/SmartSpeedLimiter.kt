package com.jixiexiaoge.drivingassist

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlin.math.abs
import com.jixiexiaoge.drivingassist.data.CompactTelemetry
import com.jixiexiaoge.drivingassist.data.floatValue

data class SmartSpeedInput(
    val rawLimit: Int,
    val correctedLimit: Int,
    val roadType: Int?,
    val remainingDistanceMeters: Int,
    val tbtText: String,
    val currentOutputLimit: Int,
    val routeGeneration: Long = 0,
    val receivedAtMs: Long = System.currentTimeMillis(),
    val currentRoadMentionsRamp: Boolean = false,
)

data class SmartSpeedOutput(
    val outputLimit: Int,
    val targetLimit: Int?,
    val transitionType: SmartSpeedTransitionType,
    val remainingDistanceMeters: Int,
    val reason: String,
)

enum class SmartSpeedTransitionType {
    None,
    RampProtection,
    RampToMainline,
    MainlineToRamp,
    CurveHold,
    WaitingForMainlineLimit,
    WaitingForConfirmation,
    RecoveryTimedOut,
}

/**
 * 仅处理高速上下匝道的限速过渡。
 *
 * 出口按剩余距离降档；汇入使用独立、有时限的真实限速恢复状态。
 * 曲率仅作为升速的附加约束，不证明主路身份或安全速度。
 */
class SmartSpeedLimiter(
    context: Context,
    private val monotonicMs: () -> Long = { System.nanoTime() / NANOS_PER_MILLISECOND },
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("CarrotAmap", Context.MODE_PRIVATE)

    private var transition: RampTransition? = null
    private var recovery: MainlineRecovery? = null
    // Losing a candidate must not unlock the independent raw-limit SPEED path.
    private var recoveryInterrupted = false
    private var routeGeneration: Long? = null
    private var lastReceiptMs: Long? = null
    private var lastUpdateMs: Long? = null
    private var curveReceiptMs: Long? = null
    private var egoReceiptMs: Long? = null
    private var curveExpiresMs: Long = 0
    private var straightSinceMs: Long? = null
    private var straightSamples = 0
    private var straightConfirmed = false

    @Synchronized
    fun updateCurve(telemetry: CompactTelemetry?) {
        val now = monotonicMs()
        val observation = curveObservation(telemetry, now)
        if (observation == null) {
            clearStraightEvidence()
            return
        }
        if (curveReceiptMs?.let { observation.controlsReceiptMs < it } == true ||
            egoReceiptMs?.let { observation.egoReceiptMs < it } == true ||
            now >= curveExpiresMs) clearStraightEvidence()
        if (abs(observation.yawRate) >= CURVE_ORIENTATION_RATE_THRESHOLD) {
            straightSinceMs = null
            straightSamples = 0
            straightConfirmed = false
        }
        if (curveReceiptMs?.let { observation.controlsReceiptMs <= it } == true ||
            egoReceiptMs?.let { observation.egoReceiptMs <= it } == true) return
        curveReceiptMs = observation.controlsReceiptMs
        egoReceiptMs = observation.egoReceiptMs
        curveExpiresMs = observation.expiresAtMs
        if (abs(observation.yawRate) <= CURVE_ORIENTATION_RATE_THRESHOLD / 2) {
            val receipt = minOf(observation.controlsReceiptMs, observation.egoReceiptMs)
            if (straightSinceMs == null) {
                straightSinceMs = receipt
            }
            straightSamples = (straightSamples + 1).coerceAtMost(MIN_STRAIGHT_SAMPLES)
            straightConfirmed = straightSamples >= MIN_STRAIGHT_SAMPLES &&
                straightSinceMs?.let { receipt - it >= STRAIGHT_CONFIRM_MS } == true
        } else if (!straightConfirmed) {
            straightSinceMs = null
            straightSamples = 0
        }
    }

    private fun curveObservation(telemetry: CompactTelemetry?, now: Long): CurveObservation? {
        val controls = telemetry?.copy(observedAtMs = now)?.fresh("controlsState")
        val ego = telemetry?.copy(observedAtMs = now)?.fresh("carState")
        val curvature = controls?.frame?.values?.floatValue("curvature")?.takeIf { it.isFinite() }
        val speed = ego?.frame?.values?.floatValue("vEgo")?.takeIf { it.isFinite() }
        return when {
            controls == null || ego == null -> null
            curvature == null || speed == null -> null
            speed < MIN_CURVE_SPEED_MPS -> null
            abs(controls.receivedAtMs - ego.receivedAtMs) > CURVE_PAIR_SKEW_MS -> null
            now - controls.receivedAtMs !in 0..CURVE_SAMPLE_TTL_MS -> null
            now - ego.receivedAtMs !in 0..CURVE_SAMPLE_TTL_MS -> null
            // curvature (1/m) * speed (m/s) is a yaw-rate proxy, not a safe-speed model.
            else -> CurveObservation(controls.receivedAtMs, ego.receivedAtMs,
                minOf(controls.expiresAtMs, ego.expiresAtMs), curvature * speed)
        }
    }

    private data class CurveObservation(
        val controlsReceiptMs: Long,
        val egoReceiptMs: Long,
        val expiresAtMs: Long,
        val yawRate: Float,
    )

    private fun clearStraightEvidence() {
        curveReceiptMs = null
        egoReceiptMs = null
        curveExpiresMs = 0
        straightSinceMs = null
        straightSamples = 0
        straightConfirmed = false
    }

    private fun curveReady(now: Long): Boolean =
        !prefs.getBoolean(KEY_CURVE_HOLD_ENABLED, true) ||
            (now < curveExpiresMs && straightConfirmed)

    @Synchronized
    fun automaticCruiseCap(): Int? {
        val active = recovery ?: return if (transition != null || recoveryInterrupted) 0 else null
        val now = monotonicMs()
        val enabled = prefs.getBoolean(KEY_ENABLED, true) && prefs.getBoolean(KEY_ACCEL_TO_MAINLINE, true)
        val fresh = now - active.startedAtMs in 0..RECOVERY_TIMEOUT_MS &&
            lastUpdateMs?.let { now - it in 0..NAVIGATION_TTL_MS } == true
        val ready = active.ready && curveReady(now)
        return if (enabled && fresh && ready) active.output else 0
    }

    @Synchronized
    fun completeRecovery(route: Long) {
        val active = recovery
        val atTarget = active?.target?.let { active.output >= it } == true
        if (routeGeneration == route && active?.ready == true && atTarget) {
            recovery = null
            recoveryInterrupted = false
        }
    }

    @Synchronized
    fun update(input: SmartSpeedInput): SmartSpeedOutput {
        val now = monotonicMs()
        val advancing = prepareInput(input, now)
        if (!prefs.getBoolean(KEY_ENABLED, true)) {
            reset()
            return persist(input.correctedLimit, null, SmartSpeedTransitionType.None, input.remainingDistanceMeters, "智能调速关闭")
        }

        val highwayLike = isHighwayLike(input)
        val rampLimit = DEFAULT_RAMP_LIMIT
        val rampProtected =
            prefs.getBoolean(KEY_RAMP_PROTECTION, true) &&
            input.rawLimit == REPORTED_RAMP_LIMIT &&
            highwayLike
        val correctedLimit = if (rampProtected) {
            rampLimit
        } else {
            input.correctedLimit
        }

        if (correctedLimit <= 0) {
            clearTransition()
            invalidateRecoveryTarget()
            return persist(correctedLimit, null, SmartSpeedTransitionType.None, input.remainingDistanceMeters, "无有效限速")
        }

        updateRecoveryCandidate(input, highwayLike, rampProtected, correctedLimit, now)
        if (!highwayLike && recovery != null) {
            return persist(correctedLimit, null, SmartSpeedTransitionType.WaitingForConfirmation,
                input.remainingDistanceMeters, "道路类别不适用，暂停本轮汇入恢复")
        }
        recovery?.let { return recover(it, input, correctedLimit, now, advancing) }
        return updateExit(input, highwayLike, rampProtected, correctedLimit)
    }

    private fun updateExit(
        input: SmartSpeedInput, highwayLike: Boolean, rampProtected: Boolean, correctedLimit: Int,
    ): SmartSpeedOutput {
        val rampLimit = DEFAULT_RAMP_LIMIT
        val transitionType = detectTransition(input, highwayLike)
        if (transitionType == null) {
            clearTransition()
            if (recoveryInterrupted) {
                return persist(correctedLimit, null, SmartSpeedTransitionType.WaitingForConfirmation,
                    input.remainingDistanceMeters, "汇入证据中断，暂停自动巡航提速；等待新入口证据或手动设置")
            }
            val type = if (correctedLimit != input.rawLimit) {
                SmartSpeedTransitionType.RampProtection
            } else SmartSpeedTransitionType.None
            val reason = if (type == SmartSpeedTransitionType.RampProtection) "匝道限速 40→$rampLimit" else "跟随导航限速"
            return persist(correctedLimit, correctedLimit, type, input.remainingDistanceMeters, reason)
        }

        val activeTransition = transitionFor(transitionType, input, correctedLimit, rampLimit)
        if (rampProtected) {
            activeTransition.lastOutputLimit = rampLimit
            return persist(rampLimit, rampLimit, SmartSpeedTransitionType.RampProtection,
                input.remainingDistanceMeters, "匝道限速 40→$rampLimit")
        }

        val output = steppedLimit(activeTransition, input.remainingDistanceMeters)
        Log.i(
            TAG,
            "🚦 匝道调速: ${activeTransition.type} ${activeTransition.startLimit}->${activeTransition.targetLimit}, " +
                "dist=${input.remainingDistanceMeters}m, out=$output"
        )
        return persist(
            output,
            activeTransition.targetLimit,
            activeTransition.type,
            input.remainingDistanceMeters,
            if (activeTransition.type == SmartSpeedTransitionType.MainlineToRamp) "下匝道阶梯降速" else "上匝道阶梯升速"
        )
    }

    private fun prepareInput(input: SmartSpeedInput, now: Long): Boolean {
        if (routeGeneration != input.routeGeneration) reset()
        val interrupted = lastReceiptMs?.let { input.receivedAtMs < it } == true ||
            lastUpdateMs?.let { now - it !in 0..NAVIGATION_TTL_MS } == true
        if (interrupted) {
            val wasRecovering = recovery != null || recoveryInterrupted
            reset()
            recoveryInterrupted = wasRecovering
        }
        routeGeneration = input.routeGeneration
        val advancing = lastReceiptMs?.let { input.receivedAtMs > it } ?: true
        lastReceiptMs = input.receivedAtMs
        if (advancing) lastUpdateMs = now
        return advancing
    }

    private fun updateRecoveryCandidate(
        input: SmartSpeedInput, highwayLike: Boolean, rampProtected: Boolean, correctedLimit: Int, now: Long,
    ) {
        val exiting = containsAny(input.tbtText, RAMP_EXIT_KEYWORDS)
        val entering = containsAny(input.tbtText, RAMP_ENTRY_KEYWORDS)
        val rampNamed = input.currentRoadMentionsRamp && input.rawLimit in 1..DEFAULT_RAMP_LIMIT
        val evidence = entering || rampProtected || rampNamed
        val lowOutput = rampProtected || currentLimit(input) <= MAX_RECOVERY_START_KPH
        if (!highwayLike) {
            invalidateRecoveryTarget()
        } else if (exiting || !prefs.getBoolean(KEY_ACCEL_TO_MAINLINE, true)) {
            recoveryInterrupted = recoveryInterrupted || recovery != null
            recovery = null
        } else if (recovery == null && evidence && lowOutput) {
            val start = if (rampProtected) DEFAULT_RAMP_LIMIT else minOf(currentLimit(input), correctedLimit)
            recovery = MainlineRecovery(now, start)
            recoveryInterrupted = false
            transition = null
        }
    }

    private fun invalidateRecoveryTarget() {
        recovery?.let {
            it.ready = false
            it.target = null
        }
    }

    private fun recover(
        active: MainlineRecovery, input: SmartSpeedInput, correctedLimit: Int, now: Long, advancing: Boolean,
    ): SmartSpeedOutput {
        active.output = minOf(active.output, correctedLimit)
        if (now - active.startedAtMs > RECOVERY_TIMEOUT_MS) {
            return persist(active.output, null, SmartSpeedTransitionType.RecoveryTimedOut,
                input.remainingDistanceMeters, "汇入确认超时，等待驾驶员确认")
        }
        val target = input.rawLimit.takeIf { it in MIN_MAINLINE_LIMIT_KPH..MAX_MAINLINE_LIMIT_KPH }
        if (active.target != target) {
            active.target = target
            active.targetSinceMs = now
            active.targetReceiptMs = input.receivedAtMs
            active.lastStepMs = now
        }
        val stable = now - active.targetSinceMs >= RECOVERY_STEP_MS &&
            input.receivedAtMs - active.targetReceiptMs >= RECOVERY_STEP_MS
        val ready = target != null && stable && curveReady(now)
        val stepDue = !active.ready || now - active.lastStepMs >= RECOVERY_STEP_MS
        if (ready && advancing && stepDue) {
            active.output = minOf(target, active.output + STEP_KPH)
            active.lastStepMs = now
        }
        active.ready = ready
        return recoveryOutput(active, input, now)
    }

    private fun recoveryOutput(active: MainlineRecovery, input: SmartSpeedInput, now: Long): SmartSpeedOutput {
        val type = when {
            active.target == null -> SmartSpeedTransitionType.WaitingForMainlineLimit
            !curveReady(now) -> SmartSpeedTransitionType.CurveHold
            !active.ready -> SmartSpeedTransitionType.WaitingForConfirmation
            else -> SmartSpeedTransitionType.RampToMainline
        }
        val reason = when (type) {
            SmartSpeedTransitionType.WaitingForMainlineLimit -> "等待主路真实限速，不推测80/120"
            SmartSpeedTransitionType.CurveHold -> "曲率缺失、弯道或尚未稳定，暂停升速"
            SmartSpeedTransitionType.WaitingForConfirmation -> "等待主路限速稳定"
            else -> if (active.target?.let { active.output >= it } == true) {
                "主路限速策略恢复完成，非车辆执行确认"
            } else "按真实主路限速分级恢复"
        }
        return persist(active.output, active.target, type, input.remainingDistanceMeters, reason)
    }

    private fun detectTransition(input: SmartSpeedInput, highwayLike: Boolean): SmartSpeedTransitionType? {
        if (!highwayLike || !prefs.getBoolean(KEY_DECEL_TO_RAMP, true)) return null
        val activeTransition = transition
        val exiting = containsAny(input.tbtText, RAMP_EXIT_KEYWORDS)
        val entering = !exiting && containsAny(input.tbtText, RAMP_ENTRY_KEYWORDS)
        val changedDirection = when (activeTransition?.type) {
            SmartSpeedTransitionType.MainlineToRamp -> entering
            SmartSpeedTransitionType.RampToMainline -> exiting
            else -> false
        }
        val continuing = activeTransition != null &&
            !changedDirection &&
            input.remainingDistanceMeters <= activeTransition.lastDistanceMeters + TRANSITION_RESET_DISTANCE_METERS
        return when {
            continuing -> activeTransition?.type
            exiting &&
                currentLimit(input) > DEFAULT_RAMP_LIMIT -> SmartSpeedTransitionType.MainlineToRamp

            else -> null
        }
    }

    private fun transitionFor(
        type: SmartSpeedTransitionType,
        input: SmartSpeedInput,
        correctedLimit: Int,
        rampLimit: Int,
    ): RampTransition {
        val target = when (type) {
            SmartSpeedTransitionType.MainlineToRamp -> rampLimit
            SmartSpeedTransitionType.RampToMainline -> correctedLimit.coerceAtLeast(rampLimit)
            else -> correctedLimit
        }
        val current = currentLimit(input)
        val existing = transition
        if (
            existing == null ||
            existing.type != type ||
            existing.targetLimit != target ||
            input.remainingDistanceMeters > existing.lastDistanceMeters + TRANSITION_RESET_DISTANCE_METERS
        ) {
            return RampTransition(
                type = type,
                startLimit = current,
                targetLimit = target,
                initialDistanceMeters = input.remainingDistanceMeters,
            ).also { transition = it }
        }

        existing.lastDistanceMeters = input.remainingDistanceMeters
        return existing
    }

    private fun steppedLimit(transition: RampTransition, remainingDistanceMeters: Int): Int {
        val transitionDistance = minOf(
            TRANSITION_START_DISTANCE_METERS,
            transition.initialDistanceMeters.coerceAtLeast(0),
        )
        val requiredSteps = ((abs(transition.startLimit - transition.targetLimit) + STEP_KPH - 1) / STEP_KPH)
            .coerceAtLeast(1)
        if (remainingDistanceMeters > transitionDistance) return transition.lastOutputLimit
        val distanceCovered = (transitionDistance - remainingDistanceMeters)
            .coerceIn(0, transitionDistance)
        val scheduledSteps = if (transitionDistance == 0) requiredSteps else {
            (distanceCovered.toLong() * requiredSteps / transitionDistance).toInt()
        }
        // Exit deceleration starts at the boundary, reaching the target before the ramp.
        val steps = (scheduledSteps + if (transition.type == SmartSpeedTransitionType.MainlineToRamp) 1 else 0)
            .coerceAtMost(requiredSteps)
        val delta = steps * STEP_KPH
        return when (transition.type) {
            SmartSpeedTransitionType.MainlineToRamp ->
                (transition.startLimit - delta).coerceAtLeast(transition.targetLimit)
                    .coerceAtMost(transition.lastOutputLimit)
                    .coerceAtLeast(transition.lastOutputLimit - STEP_KPH)
            SmartSpeedTransitionType.RampToMainline ->
                (transition.startLimit + delta).coerceAtMost(transition.targetLimit)
                    .coerceAtMost(transition.lastOutputLimit + STEP_KPH)
                    .coerceAtLeast(transition.lastOutputLimit)
            else -> transition.startLimit
        }.also { transition.lastOutputLimit = it }
    }

    private fun isHighwayLike(input: SmartSpeedInput): Boolean =
        input.roadType in HIGHWAY_ROAD_TYPES

    private fun containsAny(text: String, keywords: List<String>): Boolean =
        keywords.any(text::contains)

    private fun currentLimit(input: SmartSpeedInput): Int =
        input.currentOutputLimit.takeIf { it > 0 }?.let {
            if (it == LEGACY_RAMP_LIMIT_KPH) DEFAULT_RAMP_LIMIT else it
        } ?: input.correctedLimit

    private fun clearTransition() {
        transition = null
    }

    @Synchronized
    fun reset() {
        clearTransition()
        recovery = null
        recoveryInterrupted = false
        routeGeneration = null
        lastReceiptMs = null
        lastUpdateMs = null
        clearStraightEvidence()
    }

    private data class MainlineRecovery(
        val startedAtMs: Long,
        var output: Int,
        var target: Int? = null,
        var targetSinceMs: Long = startedAtMs,
        var targetReceiptMs: Long = 0,
        var lastStepMs: Long = startedAtMs,
        var ready: Boolean = false,
    )

    private fun persist(
        outputLimit: Int,
        targetLimit: Int?,
        type: SmartSpeedTransitionType,
        remainingDistance: Int,
        reason: String,
    ): SmartSpeedOutput {
        prefs.edit()
            .putInt(KEY_LAST_OUTPUT, outputLimit)
            .putInt(KEY_LAST_TARGET, targetLimit ?: 0)
            .putString(KEY_LAST_TYPE, type.name)
            .putInt(KEY_LAST_DISTANCE, remainingDistance)
            .putString(KEY_LAST_REASON, reason)
            .apply()

        return SmartSpeedOutput(outputLimit, targetLimit, type, remainingDistance, reason)
    }

    private data class RampTransition(
        val type: SmartSpeedTransitionType,
        val startLimit: Int,
        val targetLimit: Int,
        val initialDistanceMeters: Int,
        var lastDistanceMeters: Int,
        var lastOutputLimit: Int = startLimit,
    ) {
        constructor(
            type: SmartSpeedTransitionType,
            startLimit: Int,
            targetLimit: Int,
            initialDistanceMeters: Int,
        ) : this(type, startLimit, targetLimit, initialDistanceMeters, initialDistanceMeters)
    }

    companion object {
        private const val TAG = "SmartSpeedLimiter"
        const val KEY_ENABLED = "smart_speed_enabled"
        const val KEY_RAMP_PROTECTION = "smart_speed_ramp_protection"
        const val KEY_ACCEL_TO_MAINLINE = "smart_speed_accel_mainline"
        const val KEY_DECEL_TO_RAMP = "smart_speed_decel_ramp"
        const val KEY_RAMP_LIMIT = "smart_speed_ramp_limit"
        const val KEY_CURVE_HOLD_ENABLED = "smart_speed_curve_hold_enabled"
        const val KEY_LAST_OUTPUT = "smart_speed_last_output"
        const val KEY_LAST_TARGET = "smart_speed_last_target"
        const val KEY_LAST_TYPE = "smart_speed_last_type"
        const val KEY_LAST_DISTANCE = "smart_speed_last_distance"
        const val KEY_LAST_REASON = "smart_speed_last_reason"

        const val DEFAULT_RAMP_LIMIT = 60

        const val TRANSITION_START_DISTANCE_METERS = 800
        const val STEP_KPH = 10
        private const val TRANSITION_RESET_DISTANCE_METERS = 100
        private const val CURVE_ORIENTATION_RATE_THRESHOLD = 0.02f
        private const val RECOVERY_TIMEOUT_MS = 90_000L
        private const val NAVIGATION_TTL_MS = 10_000L
        private const val RECOVERY_STEP_MS = 3_000L
        private const val STRAIGHT_CONFIRM_MS = 2_000L
        private const val MIN_STRAIGHT_SAMPLES = 3
        private const val CURVE_PAIR_SKEW_MS = 150L
        private const val CURVE_SAMPLE_TTL_MS = 500L
        private const val MIN_CURVE_SPEED_MPS = 5f
        private const val REPORTED_RAMP_LIMIT = 40
        private const val LEGACY_RAMP_LIMIT_KPH = 55
        private const val MAX_RECOVERY_START_KPH = 70
        private const val MIN_MAINLINE_LIMIT_KPH = 70
        private const val MAX_MAINLINE_LIMIT_KPH = 120
        private const val NANOS_PER_MILLISECOND = 1_000_000

        private val HIGHWAY_ROAD_TYPES = setOf(0, 6)
        private val RAMP_EXIT_KEYWORDS = listOf("下匝道", "进入匝道", "靠右下匝道", "驶出高速", "驶出快速路", "高速出口", "出口", "收费站")
        private val RAMP_ENTRY_KEYWORDS = listOf("进入高速", "驶入高速", "进入主路", "汇入主路", "进入快速路", "高速入口", "上高速")
    }
}
