package com.jixiexiaoge.drivingassist.navigation

import com.jixiexiaoge.drivingassist.vision.LaneInference
import com.jixiexiaoge.drivingassist.data.CompactTelemetry
import com.jixiexiaoge.drivingassist.data.LaneFusionEstimate
import com.jixiexiaoge.drivingassist.data.LaneFusionState
import com.jixiexiaoge.drivingassist.data.LaneFusionTracker

private const val EXPRESSWAY_ROAD_TYPE = 6

internal fun isLaneGeometryRoadEligible(state: AmapDisplayState, roadType: Int?): Boolean =
    state == AmapDisplayState.Fresh && (roadType == 0 || roadType == EXPRESSWAY_ROAD_TYPE)

/**
 * 本机 AR 与 7000 几何连续一致时对外暴露的估计，不是地图车道定位。
 *
 * 只有两个量，且都是**估计值**：
 * - [count]：视觉候选与几何宽度带连续一致的数量估计
 * - [currentLane]：1-based 左起编号估计，不是地图车道身份或通行权限
 *
 * 抽成接口的原因：`LaneInference.live` 是单例 StateFlow，单测没法直接喂值，
 * 而 7714 的 `lane_current` 又要严格验证「模型过期必须让位给地图源」。
 */
interface LaneCountSource {
    data class Sample(
        val count: Int,
        val currentLane: Int,
        val atMs: Long,
        val model: String = "",
        val targetHost: String = "",
        val routeGeneration: Long = -1,
        val sessionId: Long = 0,
    )

    /** 没有新鲜样本时返回 null（未进实时页 / 模型未加载 / 已停更） */
    fun latest(): Sample?
}

/** Single fusion tracker shared by the existing foreground sampler and 7714 publisher. */
object ModelLaneCountSource : LaneCountSource {
    const val MAX_AGE_MS = LaneFusionTracker.MAX_AGE_MS
    private const val NANOS_PER_MILLISECOND = 1_000_000L
    private val tracker = LaneFusionTracker()
    private var telemetry: CompactTelemetry? = null
    private var target: String? = null
    private var route: Long = -1
    private var roadEligible = false
    private var notBeforeMonoMs = Long.MIN_VALUE

    @Synchronized
    internal fun observe(
        telemetry: CompactTelemetry?, host: String?, route: Long,
        eligibleRoad: Boolean? = null,
        nowMonoMs: Long = System.nanoTime() / NANOS_PER_MILLISECOND,
    ) {
        val nextEligible = eligibleRoad ?: roadEligible
        if (nextEligible != roadEligible) {
            roadEligible = nextEligible
            tracker.reset()
        }
        if (target != host || this.route != route) {
            notBeforeMonoMs = nowMonoMs
            tracker.reset()
        }
        if (telemetry == null) tracker.reset()
        this.telemetry = telemetry?.takeIf {
            listOf("modelV2", "lateralPlan").all { name ->
                (it.services[name]?.receivedAtMs ?: Long.MIN_VALUE) > notBeforeMonoMs
            }
        }
        target = host
        this.route = route
    }

    @Synchronized
    internal fun estimate(
        vision: com.jixiexiaoge.drivingassist.vision.LaneResult? = LaneInference.live.value,
        nowMonoMs: Long = System.nanoTime() / NANOS_PER_MILLISECOND,
        nowWallMs: Long = System.currentTimeMillis(),
        videoSession: com.jixiexiaoge.drivingassist.vision.LaneObservationSession? = LaneInference.activeSession,
    ): LaneFusionEstimate {
        if (!roadEligible) {
            tracker.reset()
            return LaneFusionEstimate(LaneFusionState.RoadTypeBlocked)
        }
        return tracker.update(vision, telemetry, target, route, nowMonoMs, nowWallMs, videoSession)
    }

    override fun latest(): LaneCountSource.Sample? {
        val result = estimate().takeIf { it.state == LaneFusionState.Estimated } ?: return null
        return LaneCountSource.Sample(
            count = requireNotNull(result.count),
            currentLane = requireNotNull(result.current),
            atMs = result.sourceWallMs,
            model = result.model,
            targetHost = result.host,
            routeGeneration = result.route,
            sessionId = result.session,
        )
    }
}
