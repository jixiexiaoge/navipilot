package com.jixiexiaoge.drivingassist.data

import com.jixiexiaoge.drivingassist.vision.LaneResult
import com.jixiexiaoge.drivingassist.vision.LaneObservationSession

private const val MAX_PAIR_GAP_MS = 750L
private const val MIN_PAIRS = 3
private const val MIN_SPAN_MS = 600L
private const val FRAME_MASK = 0xFFFF_FFFFL
private const val MAX_FORWARD_FRAME_DELTA = 0x7FFF_FFFFL

internal enum class LaneFusionState {
    Missing, RoadTypeBlocked, VisionOnly, GeometryOnly, Unsynchronized,
    Maneuvering, Conflict, Limited, Stabilizing, Estimated,
}

/** An agreement between correlated visual models, not verified map-lane identity or control authority. */
internal data class LaneFusionEstimate(
    val state: LaneFusionState = LaneFusionState.Missing,
    val count: Int? = null,
    val current: Int? = null,
    val geometryIndex: Int? = null,
    val geometryCount: Int? = null,
    val sourceWallMs: Long = 0,
    val sourceMonoMs: Long = 0,
    val expiresMonoMs: Long = 0,
    val host: String = "",
    val route: Long = -1,
    val session: Long = 0,
    val model: String = "",
    val edge: LaneEdgeEstimate? = null,
)

/** Display-only width bands, never a single-source wire current_lane. */
internal data class LaneEdgeEstimate(
    val geometry: LaneGeometry,
    val leftIndex: Int?,
    val rightIndex: Int?,
    val arLeft: Int?,
    val arRight: Int?,
) {
    val arConflict: Boolean
        get() = (leftIndex != null && arLeft != null && leftIndex != arLeft) ||
            (rightIndex != null && arRight != null && rightIndex != arRight)
}

private class EdgeBandConfirmation {
    private var lastReceipt: Long? = null
    private var lastFrame: Long? = null
    private var firstReceipt = 0L
    private var candidate: Int? = null
    private var confirmations = 0
    private var confirmed: Int? = null

    fun reset() {
        lastReceipt = null
        lastFrame = null
        candidate = null
        confirmations = 0
        confirmed = null
    }

    @Suppress("ReturnCount")
    fun update(bands: Int?, sample: CompactServiceSample, frame: Long): Int? {
        if (bands == null) {
            reset()
            return null
        }
        val receipt = sample.receivedAtMs
        val oldReceipt = lastReceipt
        val oldFrame = lastFrame
        if (oldReceipt != null && oldFrame != null) {
            if (receipt == oldReceipt && frame == oldFrame && candidate == bands) return confirmed
            val delta = (frame - oldFrame) and FRAME_MASK
            if (receipt <= oldReceipt || delta !in 1..MAX_FORWARD_FRAME_DELTA) {
                reset()
                return null
            }
        }
        if (candidate != bands || oldReceipt == null || receipt - oldReceipt > MAX_PAIR_GAP_MS) {
            confirmations = 0
            firstReceipt = receipt
            confirmed = null
        }
        candidate = bands
        lastReceipt = receipt
        lastFrame = frame
        confirmations = (confirmations + 1).coerceAtMost(MIN_PAIRS)
        if (confirmations >= MIN_PAIRS && receipt - firstReceipt >= MIN_SPAN_MS) confirmed = bands + 1
        return confirmed
    }
}

internal class LaneFusionTracker {
    private data class Identity(val host: String, val route: Long, val session: Long, val model: String)
    private data class Evidence(val vision: Long, val geometry: Long, val frame: Long, val visionFrame: Long)

    private var identity: Identity? = null
    private var previous: Evidence? = null
    private var candidate: Pair<Int, Int>? = null
    private var firstReceipt = 0L
    private var confirmations = 0
    private var lastNow: Long? = null
    private var lastWall: Long? = null
    private var latest = LaneFusionEstimate()
    private val leftEdge = EdgeBandConfirmation()
    private val rightEdge = EdgeBandConfirmation()
    private var edge: LaneEdgeEstimate? = null

    private fun resetEdges() {
        leftEdge.reset()
        rightEdge.reset()
        edge = null
    }

    fun reset() {
        identity = null
        previous = null
        candidate = null
        confirmations = 0
        lastNow = null
        lastWall = null
        latest = LaneFusionEstimate()
        resetEdges()
    }

    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod")
    fun update(
        vision: LaneResult?,
        telemetry: CompactTelemetry?,
        host: String?,
        route: Long,
        nowMonoMs: Long,
        nowWallMs: Long,
        videoSession: LaneObservationSession? = vision?.let {
            LaneObservationSession(it.sessionId, it.targetHost, it.routeGeneration)
        },
    ): LaneFusionEstimate {
        val rollback = lastNow?.let { nowMonoMs < it } == true || lastWall?.let { nowWallMs < it } == true
        if (rollback) {
            reset()
            lastNow = nowMonoMs
            lastWall = nowWallMs
            return latest
        }
        lastNow = nowMonoMs
        lastWall = nowWallMs
        val context = videoSession?.takeIf { it.host == host && it.route == route && it.id > 0 }
        val key = context?.let { Identity(it.host, it.route, it.id, vision?.model.orEmpty()) }
        if (key != identity) {
            previous = null
            candidate = null
            confirmations = 0
            identity = key
            resetEdges()
        }
        val observed = telemetry?.copy(observedAtMs = nowMonoMs)
        val geometry = measureLaneGeometry(observed)
        val validVision = vision?.takeIf {
            !host.isNullOrBlank() && it.targetHost == host && it.routeGeneration == route && it.sessionId > 0 &&
                it.sessionId == context?.id &&
                nowMonoMs - it.receivedMonoMs in 0..MAX_AGE_MS && nowWallMs - it.atMs in 0..MAX_AGE_MS
        }
        if (context != null) updateEdges(geometry, observed, validVision, nowMonoMs, nowWallMs, context.startedMonoMs)
        else resetEdges()
        if (validVision == null) {
            return withdraw(if (geometry.widthM != null) LaneFusionState.GeometryOnly else LaneFusionState.Missing)
        }
        if (geometry.status == LaneGeometryStatus.Maneuvering) return withdraw(LaneFusionState.Maneuvering)
        val left = validVision.lanesLeft
        val right = validVision.lanesRight
        if (left !in 1..MAX_CLASS || right !in 1..MAX_CLASS ||
            validVision.lanesTotal != left + right - 1) return withdraw(LaneFusionState.Limited)
        val model = observed?.fresh("modelV2") ?: return withdraw(LaneFusionState.VisionOnly)
        val lateral = observed.fresh("lateralPlan") ?: return withdraw(LaneFusionState.VisionOnly)
        val frame = (model.frame.values["frameId"] as? Number)?.toLong()
            ?: return withdraw(LaneFusionState.VisionOnly)
        val clocks = listOf(model.receivedAtMs, lateral.receivedAtMs, validVision.receivedMonoMs)
        if (clocks.any { nowMonoMs < it } || clocks.max() - clocks.min() > MAX_RECEIPT_SKEW_MS) {
            return withdraw(LaneFusionState.Unsynchronized)
        }
        val index = geometry.provisionalIndex ?: return withdraw(LaneFusionState.VisionOnly)
        val total = geometry.leftWidthBands?.let { l -> geometry.rightWidthBands?.let { r -> l + r + 1 } }
            ?: return withdraw(LaneFusionState.VisionOnly)
        val observation = LaneFusionEstimate(
            LaneFusionState.Stabilizing, geometryIndex = index, geometryCount = total,
            sourceWallMs = minOf(validVision.atMs, model.receivedAtWallMs, lateral.receivedAtWallMs),
            sourceMonoMs = clocks.min(), host = validVision.targetHost, route = route,
            session = validVision.sessionId, model = validVision.model,
            expiresMonoMs = minOf(model.expiresAtMs, lateral.expiresAtMs,
                validVision.receivedMonoMs + MAX_AGE_MS),
            edge = edge,
        )
        // The last four-class category has no verified exact-count / saturation contract.
        if (left == MAX_CLASS || right == MAX_CLASS) return withdraw(LaneFusionState.Limited, observation)
        if (index != left || total != validVision.lanesTotal) return withdraw(LaneFusionState.Conflict, observation)
        if (observation.sourceWallMs <= 0 ||
            nowWallMs - observation.sourceWallMs !in 0..MAX_AGE_MS) {
            return withdraw(LaneFusionState.Unsynchronized)
        }
        return confirm(validVision, model, frame, observation, total to index, nowMonoMs)
    }

    private fun withdraw(
        state: LaneFusionState, observation: LaneFusionEstimate = LaneFusionEstimate(),
    ): LaneFusionEstimate {
        previous = null
        candidate = null
        confirmations = 0
        latest = observation.copy(state = state, edge = edge,
            host = identity?.host.orEmpty(), route = identity?.route ?: -1,
            session = identity?.session ?: 0, model = identity?.model.orEmpty())
        return latest
    }

    private fun updateEdges(
        geometry: LaneGeometry, observed: CompactTelemetry?, vision: LaneResult?,
        nowMonoMs: Long, nowWallMs: Long, notBeforeMonoMs: Long,
    ) {
        val model = observed?.fresh("modelV2")
        val lateral = observed?.fresh("lateralPlan")
        val frame = (model?.frame?.values?.get("frameId") as? Number)?.toLong()
        if (model == null || lateral == null || frame == null) {
            resetEdges()
            return
        }
        val steady = lateral.frame.values["laneChangeState"] == "off"
        val synchronized = kotlin.math.abs(model.receivedAtMs - lateral.receivedAtMs) <= MAX_RECEIPT_SKEW_MS
        val fresh = listOf(model, lateral).all {
            edgeSampleFresh(it, nowMonoMs, nowWallMs, notBeforeMonoMs)
        }
        val frameUsable = frame in 0..FRAME_MASK && geometry.widthM != null
        if (!listOf(steady, synchronized, fresh, frameUsable).all { it }) {
            resetEdges()
            return
        }
        val arPaired = vision != null &&
            kotlin.math.abs(vision.receivedMonoMs - model.receivedAtMs) <= MAX_RECEIPT_SKEW_MS &&
            kotlin.math.abs(vision.receivedMonoMs - lateral.receivedAtMs) <= MAX_RECEIPT_SKEW_MS &&
            vision.lanesTotal == vision.lanesLeft + vision.lanesRight - 1
        edge = LaneEdgeEstimate(
            geometry,
            leftEdge.update(geometry.leftWidthBands, model, frame),
            rightEdge.update(geometry.rightWidthBands, model, frame),
            vision?.lanesLeft?.takeIf { arPaired && it in 1 until MAX_CLASS },
            vision?.lanesRight?.takeIf { arPaired && it in 1 until MAX_CLASS },
        )
    }

    private fun edgeSampleFresh(
        sample: CompactServiceSample, nowMonoMs: Long, nowWallMs: Long, notBeforeMonoMs: Long,
    ): Boolean {
        val monotonic = sample.receivedAtMs <= nowMonoMs && sample.receivedAtMs > notBeforeMonoMs
        val wall = sample.receivedAtWallMs > 0 && nowWallMs - sample.receivedAtWallMs in 0..MAX_AGE_MS
        return monotonic && wall
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun confirm(
        vision: LaneResult,
        model: CompactServiceSample,
        frame: Long,
        observation: LaneFusionEstimate,
        nextCandidate: Pair<Int, Int>,
        nowMonoMs: Long,
    ): LaneFusionEstimate {
        val evidence = Evidence(vision.receivedMonoMs, model.receivedAtMs, frame, vision.frames)
        val old = previous
        if (old != null) {
            val unexpired = latest.state != LaneFusionState.Estimated || nowMonoMs < latest.expiresMonoMs
            if (evidence == old && candidate == nextCandidate) {
                return if (unexpired) latest.copy(edge = edge).also { latest = it }
                else withdraw(LaneFusionState.Stabilizing, observation)
            }
            val backwards = evidence.vision < old.vision || evidence.geometry < old.geometry
            if (backwards) return withdraw(LaneFusionState.Unsynchronized)
            if (evidence.vision > old.vision && evidence.visionFrame <= old.visionFrame) {
                return withdraw(LaneFusionState.Unsynchronized)
            }
            // Neither UI polling nor one fast stream can turn an unchanged sibling into new evidence.
            if (evidence.vision == old.vision || evidence.geometry == old.geometry) {
                if (candidate != nextCandidate) return withdraw(LaneFusionState.Conflict, observation)
                return if (unexpired) latest.copy(edge = edge).also { latest = it }
                else withdraw(LaneFusionState.Stabilizing, observation)
            }
        }
        val frameDelta = old?.let { (frame - it.frame) and FRAME_MASK }
        val consecutive = old != null && candidate == nextCandidate &&
            evidence.vision - old.vision <= MAX_PAIR_GAP_MS &&
            evidence.geometry - old.geometry <= MAX_PAIR_GAP_MS &&
            frameDelta != null && frameDelta in 1..MAX_FORWARD_FRAME_DELTA
        if (!consecutive) {
            confirmations = 0
            firstReceipt = observation.sourceMonoMs
        }
        confirmations = (confirmations + 1).coerceAtMost(MIN_PAIRS)
        previous = evidence
        candidate = nextCandidate
        latest = if (confirmations >= MIN_PAIRS && observation.sourceMonoMs - firstReceipt >= MIN_SPAN_MS) {
            observation.copy(state = LaneFusionState.Estimated,
                count = nextCandidate.first, current = nextCandidate.second)
        } else observation
        return latest
    }

    companion object {
        const val MAX_AGE_MS = 1200L
        private const val MAX_CLASS = 3
        private const val MAX_RECEIPT_SKEW_MS = 300L
    }
}
