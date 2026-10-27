package com.jixiexiaoge.drivingassist.navigation

import com.jixiexiaoge.drivingassist.LaneInfo

data class AmapLaneDisplaySnapshot(
    val lanes: List<LaneInfo>,
    val routeGeneration: Long,
    val receivedAtMs: Long,
)

data class AmapGuideExtras(
    val receivedAtMs: Long,
    val routeGeneration: Long,
    val roadName: String?,
    val destinationName: String?,
    val remainingDistanceM: Int?,
    val remainingTimeSec: Int?,
    val currentIcon: Int?,
    val nextIcon: Int?,
    val exitName: String?,
    val exitDirection: String?,
    val serviceName: String?,
    val serviceDistanceM: Int?,
    val currentSegmentSec: Int? = null,
    val nextSegmentSec: Int? = null,
    val totalTimeSec: Int? = null,
    val reportedRoadType: Int? = null,
    val totalTrafficLights: Int? = null,
    val remainingTrafficLights: Int? = null,
    val rawEta: String? = null,
    val serviceDistanceText: String? = null,
    val serviceType: Int? = null,
    val nextServiceName: String? = null,
    val nextServiceDistanceText: String? = null,
    val nextServiceType: Int? = null,
    val nextServiceDistanceM: Int? = null,
    val currentSegmentIndex: Int? = null,
)

enum class AmapRoadNoticeKind { Traffic, Situation }
data class AmapRoadSpeedLimit(
    val limitKph: Int,
    val routeGeneration: Long,
    val receivedAtMs: Long,
    val policyLimitKph: Int? = null,
    val policyReceivedAtMs: Long? = null,
)

data class AmapRoadNotice(
    val kind: AmapRoadNoticeKind,
    val text: String,
    val distanceM: Int?,
    val routeGeneration: Long,
    val receivedAtMs: Long,
)

data class NaviPositionSample(
    val latitude: Double,
    val longitude: Double,
    val speedKph: Double?,
    val headingDeg: Double?,
    val receivedAtMs: Long,
    val roadName: String = "",
) {
    fun isFresh(nowMs: Long, timeoutMs: Long): Boolean =
        nowMs - receivedAtMs in 0..timeoutMs && validCoordinates(latitude, longitude)

    companion object {
        fun validCoordinates(latitude: Double, longitude: Double): Boolean =
            latitude.isFinite() && longitude.isFinite() &&
                latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
                (latitude != 0.0 || longitude != 0.0)
    }
}

/** Source clocks live outside CarrotManFields so unrelated copy/telemetry updates cannot refresh them. */
class NaviSourceTracker {
    private companion object {
        const val MIN_POLICY_LIMIT_KPH = 10
        const val MAX_POLICY_LIMIT_KPH = 200
        const val POLICY_LIMIT_STEP_KPH = 10
        const val MAX_RAW_LIMIT_KPH = 249
    }
    private val updatedAt = mutableMapOf<String, Long>()
    private var mapPosition: NaviPositionSample? = null
    private var phonePosition: NaviPositionSample? = null
    private var totalDistanceM: Int? = null
    private var sectionRemainingM: Double? = null
    private var sectionAverageKph: Double? = null
    private var sectionProgress: Double? = null
    private var guideExtras: AmapGuideExtras? = null
    private val roadNotices = mutableMapOf<AmapRoadNoticeKind, AmapRoadNotice>()
    private var trafficSegments: AmapTrafficSegments? = null
    private var roadSpeedLimit: AmapRoadSpeedLimit? = null
    private var cruiseLights: AmapCruiseSnapshot? = null
    private var cruiseRoad: AmapCruiseRoad? = null
    private var sectionDisplay: AmapSectionDisplay? = null
    private var lastReadOnlySampleMs: Long? = null
    private var laneDisplay: AmapLaneDisplaySnapshot? = null
    private var lastLaneDisplayReceiptMs: Long? = null
    private var roadCategory: AmapRoadCategorySource? = null
    private var navigationSignal: AmapNavigationSignalSource? = null
    private var displayNavigationSignal: AmapNavigationSignalSource? = null
    private var laneCurrent: AmapLaneDisplaySnapshot? = null
    private val controlSourceReceipts = mutableMapOf<String, Long>()
    private var lastPublicationMs: Long? = null
    private var lastAmapSelected: Boolean? = null
    private var route: AmapRouteSource? = null
    private var sdi: AmapSdiSource? = null
    private var secondarySdi: AmapSdiSource? = null
    private var section: AmapSectionSource? = null

    @Synchronized
    fun updateRoute(value: AmapRouteSource) {
        if (!acceptControlReceipt("route", value.routeGeneration, value.receivedAtMs)) return
        route = value
        update("route", nowMs = value.receivedAtMs)
    }

    @Synchronized
    fun updateSdi(value: AmapSdiSource?, routeGeneration: Long, receivedAtMs: Long, secondary: Boolean = false) {
        val name = if (secondary) "sdi_secondary" else "sdi"
        if (!acceptControlReceipt(name, routeGeneration, receivedAtMs)) return
        if (secondary) secondarySdi = value else sdi = value
        if (value == null) invalidate(name) else update(name, nowMs = receivedAtMs)
    }

    private fun acceptControlReceipt(name: String, routeGeneration: Long, receivedAtMs: Long): Boolean {
        val boundary = updatedAt["ui_route_changed"]
        val equalAllowed = name != "lane_current" && receivedAtMs == boundary
        val afterBoundary = boundary == null || receivedAtMs > boundary || equalAllowed
        val advancing = controlSourceReceipts[name]?.let { receivedAtMs > it } ?: true
        val sameRoute = guideExtras?.let { it.routeGeneration == routeGeneration } ?: true
        if (!afterBoundary || !advancing || !sameRoute) return false
        controlSourceReceipts[name] = receivedAtMs
        return true
    }

    @Synchronized
    fun updateRoadCategory(roadType: Int?, routeGeneration: Long, receivedAtMs: Long) {
        if (!acceptControlReceipt("road_category", routeGeneration, receivedAtMs)) return
        roadCategory = roadType?.takeIf { it in 0..10 }?.let {
            AmapRoadCategorySource(it, routeGeneration, receivedAtMs)
        }
        if (roadCategory != null) update("road_category", nowMs = receivedAtMs)
        else invalidate("road_category")
    }

    @Synchronized
    fun updateNavigationSignal(
        status: Int?, direction: Int?, countdownSec: Int?, routeGeneration: Long, receivedAtMs: Long,
    ) {
        if (!acceptControlReceipt("traffic_signal", routeGeneration, receivedAtMs)) return
        val received = if (status != null && direction != null && countdownSec != null) {
            AmapNavigationSignalSource(status, direction, countdownSec, routeGeneration, receivedAtMs)
        } else null
        displayNavigationSignal = received?.takeIf { it.toDisplaySignal(receivedAtMs) != null }
        navigationSignal = received?.takeIf { it.toSignal(receivedAtMs) != null }
    }

    /**
     * @return true = 这一帧被接受（receipt 前进、同路线、在 ui_route_changed 之后）；
     *         false = 被拒（此时**不应**刷新 `lane_current` 的源时钟，否则会出现
     *         「旧车道图配新时钟」，旧图被当成当前路口图继续上发）。
     */
    @Synchronized
    fun updateLaneCurrent(lanes: List<LaneInfo>?, routeGeneration: Long, receivedAtMs: Long): Boolean {
        if (!acceptControlReceipt("lane_current", routeGeneration, receivedAtMs)) return false
        laneCurrent = lanes?.takeIf { it.size in 1..16 }?.let {
            AmapLaneDisplaySnapshot(it.toList(), routeGeneration, receivedAtMs)
        }
        return true
    }

    @Synchronized
    fun publicationSnapshot(nowMs: Long, amapSelected: Boolean): Snapshot {
        if ((!amapSelected && lastAmapSelected != false) || lastPublicationMs?.let { nowMs < it } == true) {
            stopNavigation(nowMs)
            update("ui_route_changed", nowMs = nowMs)
        }
        lastAmapSelected = amapSelected
        lastPublicationMs = nowMs
        return snapshot()
    }

    @Synchronized
    fun updateLaneDisplay(lanes: List<LaneInfo>?, routeGeneration: Long, nowMs: Long) {
        val boundary = updatedAt["ui_route_changed"]
        laneDisplay = lanes?.takeIf {
            it.size in 1..16 && (boundary == null || nowMs > boundary) &&
                (lastLaneDisplayReceiptMs == null || nowMs > lastLaneDisplayReceiptMs!!)
        }?.let { AmapLaneDisplaySnapshot(it.toList(), routeGeneration, nowMs) }
        lastLaneDisplayReceiptMs = maxOf(lastLaneDisplayReceiptMs ?: Long.MIN_VALUE, nowMs)
    }

    @Synchronized
    fun updateCruiseRoad(value: AmapCruiseRoad?) {
        cruiseRoad = value
    }

    @Synchronized
    fun updateSectionDisplay(value: AmapSectionDisplay?) {
        sectionDisplay = value
    }

    @Synchronized
    fun clearCruise() {
        cruiseRoad = null
        cruiseLights = null
    }

    /** Reuses the foreground sampler; rollback/source changes permanently withdraw display-only caches. */
    @Synchronized
    fun sampleReadOnly(nowMs: Long, amapSelected: Boolean): Snapshot {
        if (!amapSelected || lastReadOnlySampleMs?.let { nowMs < it } == true) {
            clearCruise()
            sectionDisplay = null
            laneDisplay = null
            displayNavigationSignal = null
        }
        if (guideExtras?.let { nowMs - it.receivedAtMs in 0..NaviV2Constants.STALE_TIMEOUT_MS } != true) {
            laneDisplay = null
        }
        lastReadOnlySampleMs = nowMs
        return snapshot()
    }

    @Synchronized
    fun updateCruiseLights(lamps: List<AmapCruiseLamp>?, nowMs: Long = System.currentTimeMillis()) {
        cruiseLights = lamps?.takeIf { it.isNotEmpty() }?.let { AmapCruiseSnapshot(it.toList(), nowMs) }
    }

    @Synchronized
    fun updateRoadSpeedLimit(
        limitKph: Int,
        routeGeneration: Long,
        nowMs: Long = System.currentTimeMillis(),
        policyLimitKph: Int? = null,
        policyReceivedAtMs: Long = nowMs,
    ) {
        roadSpeedLimit = limitKph.takeIf { it in 1..249 }?.let {
            AmapRoadSpeedLimit(it, routeGeneration, nowMs,
                policyLimitKph?.takeIf { policy ->
                    policy in MIN_POLICY_LIMIT_KPH..MAX_POLICY_LIMIT_KPH && policy % POLICY_LIMIT_STEP_KPH == 0
                },
                policyReceivedAtMs.takeIf { policyLimitKph != null })
        }
    }

    @Synchronized
    fun updateTrafficSegments(value: AmapTrafficSegments?) {
        trafficSegments = value
    }

    @Synchronized
    fun updateRoadNotice(
        kind: AmapRoadNoticeKind,
        text: String,
        distanceM: Int?,
        routeGeneration: Long,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (text.isBlank()) roadNotices.remove(kind)
        else roadNotices[kind] = AmapRoadNotice(kind, text.trim(), distanceM?.takeIf { it > 0 }, routeGeneration, nowMs)
    }

    @Synchronized
    fun updateGuideExtras(extras: AmapGuideExtras) {
        if (guideExtras?.let {
                extras.routeGeneration != it.routeGeneration ||
                    extras.receivedAtMs - it.receivedAtMs !in 0..NaviV2Constants.STALE_TIMEOUT_MS
            } == true) {
            laneDisplay = null
        }
        guideExtras = extras
    }

    @Synchronized
    fun resetGuideDisplay(nowMs: Long = System.currentTimeMillis()) {
        invalidate("guidance_current", "guidance_next", "route", "lane_current", "road_category", "traffic_signal",
            "road_limit", "sdi", "sdi_secondary", "section")
        sectionRemainingM = null
        sectionAverageKph = null
        sectionProgress = null
        sectionDisplay = null
        guideExtras = null
        roadNotices.clear()
        trafficSegments = null
        roadSpeedLimit = null
        lastLaneDisplayReceiptMs = null
        controlSourceReceipts.clear()
        update("ui_route_changed", nowMs = nowMs)
    }

    @Synchronized
    fun update(vararg names: String, nowMs: Long = System.currentTimeMillis()) {
        names.forEach { updatedAt[it] = nowMs }
    }

    @Synchronized
    fun invalidate(vararg names: String) {
        names.forEach { updatedAt.remove(it) }
        if ("lane_current" in names) laneDisplay = null
        if ("lane_current" in names) laneCurrent = null
        if ("road_category" in names) roadCategory = null
        if ("route" in names) route = null
        if ("sdi" in names) sdi = null
        if ("sdi_secondary" in names) secondarySdi = null
        if ("section" in names) section = null
        if ("traffic_signal" in names) {
            navigationSignal = null
            displayNavigationSignal = null
        }
    }

    @Synchronized
    fun updatePosition(sample: NaviPositionSample, mapMatched: Boolean) {
        if (mapMatched) mapPosition = sample else phonePosition = sample
    }

    @Synchronized
    fun updateRouteTotal(distanceM: Int) {
        totalDistanceM = distanceM.takeIf { it > 0 }
    }

    @Synchronized
    fun updateSection(remainingM: Double?, averageKph: Double?, progress: Double?,
        nowMs: Long = System.currentTimeMillis(), speedLimitKph: Int? = null, routeGeneration: Long = 0) {
        if (!acceptControlReceipt("section", routeGeneration, nowMs)) return
        sectionRemainingM = remainingM
        sectionAverageKph = averageKph
        sectionProgress = progress
        val validRemaining = remainingM?.takeIf { it.isFinite() && it >= 1 }
        val validLimit = speedLimitKph?.takeIf { it in 1..MAX_RAW_LIMIT_KPH }
        section = validRemaining?.let { remaining ->
            validLimit?.let { limit ->
                AmapSectionSource(limit, remaining, averageKph, progress, routeGeneration, nowMs)
            }
        }
        update("section", nowMs = nowMs)
    }

    @Synchronized
    fun stopNavigation(nowMs: Long = System.currentTimeMillis()) {
        invalidate("guidance_current", "guidance_next", "route", "road_limit", "sdi", "sdi_secondary",
            "section", "traffic_signal", "lane_current", "road_category")
        totalDistanceM = null
        sectionRemainingM = null
        sectionAverageKph = null
        sectionProgress = null
        guideExtras = null
        roadNotices.clear()
        trafficSegments = null
        roadSpeedLimit = null
        sectionDisplay = null
        clearCruise()
        lastLaneDisplayReceiptMs = null
        controlSourceReceipts.clear()
        invalidate("ui_route_changed")
        update("navigation_status", nowMs = nowMs)
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        updatedAt.toMap(), mapPosition, phonePosition, totalDistanceM,
        sectionRemainingM, sectionAverageKph, sectionProgress,
        guideExtras,
        roadNotices.values.toList(),
        trafficSegments,
        roadSpeedLimit,
        cruiseLights,
        cruiseRoad,
        sectionDisplay,
        laneDisplay,
        roadCategory,
        navigationSignal,
        laneCurrent,
        displayNavigationSignal,
        route, sdi, secondarySdi, section,
    )

    data class Snapshot(
        val updatedAt: Map<String, Long>,
        val mapPosition: NaviPositionSample?,
        val phonePosition: NaviPositionSample?,
        val totalDistanceM: Int?,
        val sectionRemainingM: Double?,
        val sectionAverageKph: Double?,
        val sectionProgress: Double?,
        val guideExtras: AmapGuideExtras? = null,
        val roadNotices: List<AmapRoadNotice> = emptyList(),
        val trafficSegments: AmapTrafficSegments? = null,
        val roadSpeedLimit: AmapRoadSpeedLimit? = null,
        val cruiseLights: AmapCruiseSnapshot? = null,
        val cruiseRoad: AmapCruiseRoad? = null,
        val sectionDisplay: AmapSectionDisplay? = null,
        val laneDisplay: AmapLaneDisplaySnapshot? = null,
        val roadCategory: AmapRoadCategorySource? = null,
        val navigationSignal: AmapNavigationSignalSource? = null,
        val laneCurrent: AmapLaneDisplaySnapshot? = null,
        val displayNavigationSignal: AmapNavigationSignalSource? = null,
        val route: AmapRouteSource? = null,
        val sdi: AmapSdiSource? = null,
        val secondarySdi: AmapSdiSource? = null,
        val section: AmapSectionSource? = null,
    ) {
        fun isFresh(name: String, nowMs: Long, timeoutMs: Long): Boolean =
            updatedAt[name]?.let { nowMs - it in 0..timeoutMs } == true

        fun position(nowMs: Long, timeoutMs: Long, amapSelected: Boolean = true): NaviPositionSample? =
            mapPosition?.takeIf { amapSelected && it.isFresh(nowMs, timeoutMs) }
                ?: phonePosition?.takeIf { it.isFresh(nowMs, timeoutMs) }
    }
}
