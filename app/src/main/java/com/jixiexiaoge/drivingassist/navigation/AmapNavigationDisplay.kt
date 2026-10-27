package com.jixiexiaoge.drivingassist.navigation

import com.jixiexiaoge.drivingassist.AmapIconSource
import com.jixiexiaoge.drivingassist.AmapManeuver
import com.jixiexiaoge.drivingassist.CarrotManFields
import com.jixiexiaoge.drivingassist.data.ReceivedNavigationLanes
import com.jixiexiaoge.drivingassist.data.ReceivedSection
import com.jixiexiaoge.drivingassist.data.ReceivedSpeedEvent

internal enum class AmapDisplayState { Inactive, Idle, Waiting, Fresh, Expired }
internal data class AmapDisplayAction(
    val label: String, val roadName: String?, val distanceM: Int, val remainingSec: Int? = null,
    val maneuverIcon: Int? = null,
)
internal data class AmapServiceStop(val name: String, val distanceM: Int)
internal data class AmapDisplaySignal(val state: Int, val direction: Int, val remainingSec: Int?)

internal fun AmapNavigationSignalSource.toDisplaySignal(nowMs: Long): AmapDisplaySignal? {
    val age = nowMs - receivedAtMs
    val knownPhase = status in 1..2 && countdownSec in 1..MAX_SIGNAL_SECONDS
    val fresh = age in 0 until minOf(NaviV2Constants.STALE_TIMEOUT_MS, countdownSec * 1000L)
    if (!knownPhase || !fresh) return null
    return AmapDisplaySignal(
        status, direction.takeIf { it in 1..MAX_SIGNAL_DIRECTION } ?: 0, countdownSec - (age / 1000).toInt(),
    )
}

internal data class AmapNavigationDisplay(
    val state: AmapDisplayState,
    val ageMs: Long?,
    val destination: String?,
    val roadName: String?,
    val remainingM: Int?,
    val remainingSec: Int?,
    val progress: Float?,
    val current: AmapDisplayAction?,
    val next: AmapDisplayAction?,
    val lanes: ReceivedNavigationLanes?,
    val exit: String?,
    val service: AmapServiceStop?,
    val events: List<ReceivedSpeedEvent>,
    val section: ReceivedSection?,
    val assistantAction: String?,
    val trafficSignal: AmapDisplaySignal?,
    val roadScope: String?,
    val junction: AmapJunctionLanes?,
    val roadNotices: List<AmapRoadNotice>,
    val trafficSegments: AmapTrafficSegments?,
    val speedEventsFresh: Boolean = false,
    val sectionFresh: Boolean = false,
    val roadLimitKph: Int? = null,
    val upcomingLimit: ReceivedSpeedEvent? = null,
    val totalDistanceM: Int? = null,
    val traveledDistanceM: Int? = null,
    val arrivalAtMs: Long? = null,
    val totalTimeSec: Int? = null,
    val reportedRoadType: Int? = null,
    val guideDetails: AmapGuideExtras? = null,
    val routeGeneration: Long = 0,
    val cruiseLamps: List<AmapCruiseLamp> = emptyList(),
    val mapSpeedKph: Double? = null,
    val mapHeadingDeg: Double? = null,
    val cruiseRoad: AmapCruiseRoad? = null,
    val sectionDetails: AmapSectionDisplay? = null,
    val displayJunction: AmapJunctionLanes? = null,
    val displayJunctionRetained: Boolean = false,
)

internal fun buildAmapNavigationDisplay(
    fields: CarrotManFields,
    source: NaviSourceTracker.Snapshot,
    activeMode: String,
    nowMs: Long,
): AmapNavigationDisplay {
    val selected = activeMode == "AMAP"
    val navigating = selected && fields.isNavigating
    fun fresh(name: String): Boolean {
        if (!navigating || !source.isFresh(name, nowMs, NaviV2Constants.STALE_TIMEOUT_MS)) return false
        val boundary = source.updatedAt["ui_route_changed"] ?: return true
        val receivedAt = source.updatedAt[name] ?: return false
        // Millisecond-equal lanes cannot prove they belong to the new route.
        return if (name == "lane_current") receivedAt > boundary else receivedAt >= boundary
    }
    val extras = source.guideExtras?.takeIf {
        navigating && it.routeGeneration == fields.noaRouteGeneration &&
            nowMs - it.receivedAtMs in 0..NaviV2Constants.STALE_TIMEOUT_MS
    }
    val notices = source.roadNotices.filter {
        navigating && it.routeGeneration == fields.noaRouteGeneration &&
            nowMs - it.receivedAtMs in 0..NaviV2Constants.STALE_TIMEOUT_MS
    }
    val traffic = source.trafficSegments?.takeIf {
        navigating && it.routeGeneration == fields.noaRouteGeneration &&
            nowMs - it.receivedAtMs in 0..NaviV2Constants.STALE_TIMEOUT_MS
    }
    val roadLimit = source.roadSpeedLimit?.takeIf {
        navigating && it.routeGeneration == fields.noaRouteGeneration &&
            nowMs - it.receivedAtMs in 0..NaviV2Constants.STALE_TIMEOUT_MS &&
            it.receivedAtMs >= (source.updatedAt["ui_route_changed"] ?: Long.MIN_VALUE)
    }
    val displayClocks = listOf("guidance_current", "guidance_next", "route", "lane_current",
        "road_category", "sdi", "sdi_secondary", "section")
    val sectionDetails = source.sectionDisplay?.takeIf {
        navigating && it.isFresh(nowMs, fields.noaRouteGeneration)
    }
    val state = when {
        !selected -> AmapDisplayState.Inactive
        !fields.isNavigating -> AmapDisplayState.Idle
        extras != null || roadLimit != null || sectionDetails != null || displayClocks.any(::fresh) || notices.isNotEmpty() || traffic != null -> AmapDisplayState.Fresh
        source.guideExtras != null || displayClocks.any { source.updatedAt[it] != null } ||
            source.roadNotices.isNotEmpty() || source.trafficSegments != null ||
            source.roadSpeedLimit != null -> AmapDisplayState.Expired
        else -> AmapDisplayState.Waiting
    }
    fun action(next: Boolean): AmapDisplayAction? {
        val clock = if (next) "guidance_next" else "guidance_current"
        if (!fresh(clock)) return null
        val distance = if (next) fields.nTBTDistNext else fields.nTBTDist
        val type = if (next) fields.nTBTTurnTypeNext else fields.nTBTTurnType
        val fromGuide = extras != null && (source.updatedAt[clock] ?: 0) <= extras.receivedAtMs
        val icon = if (next) extras?.nextIcon else extras?.currentIcon
        if (fromGuide && icon == null) return null
        val mapped = icon?.takeIf { fromGuide }?.let { AmapManeuver.info(it, AmapIconSource.AUTO_BROADCAST) }
        val text = if (next) fields.szTBTMainTextNext.takeIf { fromGuide } else fields.szTBTMainText
        val hasSourceDescription = mapped != null || (!fromGuide && !text.isNullOrBlank())
        if (distance < 0 || (type < 0 && !hasSourceDescription)) return null
        val label = when {
            mapped != null -> mapped.labelZh
            fromGuide && icon != null -> "未识别的高德动作（ICON $icon）"
            !text.isNullOrBlank() -> text
            else -> "未识别的导航动作"
        }
        val road = if (fromGuide) (if (next) fields.szFarDirName else fields.szNearDirName).takeIf { it.isNotBlank() } else null
        val seconds = if (fromGuide) {
            (if (next) extras?.nextSegmentSec else extras?.currentSegmentSec)?.takeIf { it >= 0 }
        } else null
        return AmapDisplayAction(label, road, distance, seconds, icon?.takeIf { fromGuide && mapped != null })
    }
    val remaining = extras?.remainingDistanceM
    val total = source.totalDistanceM?.takeIf { remaining != null && it >= remaining && it > 0 }
    val progress = remaining?.let { distance -> total?.let { 1f - distance.toFloat() / it } }
    val laneInfo = fields.laneInfoList.takeIf {
        fresh("lane_current") && !fields.laneInfoFromCache && it.size in 1..16 &&
            fields.nLaneCount == it.size
    }
    val ordered = laneInfo?.let { info -> info.map { it.driveWayNumber }.sorted() == info.indices.toList() } == true
    val displayLanes = if (ordered) laneInfo?.sortedBy { it.driveWayNumber } else laneInfo
    val lanes = displayLanes?.let { ReceivedNavigationLanes(it.size, it.mapIndexedNotNull { index, lane -> index.takeIf { lane.isRecommended } }) }
    val junction = displayLanes?.let { info ->
        AmapJunctionLanes(info.mapIndexed { index, lane ->
            AmapJunctionLane(index + 1, amapLaneDirection(lane.id), lane.isRecommended, rawIcon = lane.id)
        }, ordered, action(false)?.distanceM)
    }
    // Sparse 13012 graphics outlive advice only while actual guide receipts prove this route is ongoing.
    val retainedLanes = source.laneDisplay?.takeIf {
        extras != null && fresh("guidance_current") && !fields.laneInfoFromCache &&
            it.routeGeneration == fields.noaRouteGeneration &&
            nowMs >= it.receivedAtMs &&
            it.receivedAtMs > (source.updatedAt["ui_route_changed"] ?: Long.MIN_VALUE) &&
            source.updatedAt["lane_current"] == it.receivedAtMs &&
            fields.nLaneCount == it.lanes.size && fields.laneInfoList == it.lanes
    }
    val retainedJunction = retainedLanes?.let {
        val orderedCache = it.lanes.map { lane -> lane.driveWayNumber }.sorted() == it.lanes.indices.toList()
        val info = if (orderedCache) it.lanes.sortedBy { lane -> lane.driveWayNumber } else it.lanes
        AmapJunctionLanes(info.mapIndexed { index, lane ->
            AmapJunctionLane(index + 1, amapLaneDirection(lane.id), lane.isRecommended, rawIcon = lane.id)
        }, orderedCache, null)
    }
    val events = buildList {
        if (fresh("sdi") && fields.nSdiType >= 0 && fields.nSdiDist > 0) {
            add(ReceivedSpeedEvent(fields.nSdiType, fields.nSdiSpeedLimit.takeIf { it in 1..249 }, fields.nSdiDist, null))
        }
        if (fresh("sdi_secondary") && fields.nSdiPlusType >= 0 && fields.nSdiPlusDist > 0) {
            add(ReceivedSpeedEvent(fields.nSdiPlusType, fields.nSdiPlusSpeedLimit.takeIf { it in 1..249 }, fields.nSdiPlusDist, null))
        }
    }
    val section = if (fresh("section") && fields.nSdiBlockType == 2) {
        ReceivedSection(
            fields.nSdiBlockSpeed.takeIf { it in 1..249 },
            source.sectionAverageKph?.takeIf { it.isFinite() && it in 0.0..300.0 }?.toFloat(),
            source.sectionRemainingM?.takeIf { it.isFinite() && it >= 0 }?.toFloat(),
            source.sectionProgress?.takeIf { it.isFinite() && it in 0.0..1.0 }?.toFloat(),
        )
    } else null
    val upcomingLimit = events.filter {
        it.limitKph != null && it.type in setOf(0, 1, 2, 7, 8, 75, 76)
    }.minByOrNull { it.distanceM }
    val receivedAt = listOfNotNull(extras?.receivedAtMs, roadLimit?.receivedAtMs,
        source.updatedAt["guidance_current"]?.takeIf { fresh("guidance_current") }).maxOrNull()
    val signal = source.displayNavigationSignal?.takeIf {
        selected && it.routeGeneration == fields.noaRouteGeneration &&
            it.receivedAtMs >= (source.updatedAt["ui_route_changed"] ?: Long.MIN_VALUE)
    }?.toDisplaySignal(nowMs)
    val roadScope = when (extras?.reportedRoadType) {
        0 -> "高速公路"
        6 -> "快速路（高架属性未确认）"
        1 -> "国道"
        2 -> "省道"
        3 -> "县道"
        4 -> "乡公路"
        5 -> "县乡村内部道路"
        7 -> "主要道路"
        8 -> "次要道路"
        9 -> "普通道路"
        10 -> "非导航道路"
        else -> null
    }
    return AmapNavigationDisplay(
        state, receivedAt?.let { (nowMs - it).coerceAtLeast(0) },
        extras?.destinationName, extras?.roadName, remaining, extras?.remainingTimeSec, progress,
        action(false), action(true), lanes,
        extras?.let { listOfNotNull(it.exitName, it.exitDirection).joinToString(" · ").takeIf(String::isNotBlank) },
        extras?.let { guide -> guide.serviceName?.let { name -> guide.serviceDistanceM?.let { AmapServiceStop(name, it) } } },
        events, section,
        fields.segAssistantAction.takeIf { extras != null && it > 0 }?.let {
            AmapManeuver.segmentAssistantActionInfo(it)?.labelZh ?: "未识别的航段提示 #$it"
        },
        signal, roadScope, junction, notices, traffic,
        fresh("sdi") || fresh("sdi_secondary"), fresh("section"),
        roadLimit?.limitKph, upcomingLimit,
        total, remaining?.let { distance -> total?.let { it - distance } },
        extras?.let { guide ->
            guide.remainingTimeSec?.takeIf { it > 0 }?.let { guide.receivedAtMs + it.toLong() * 1000 }
        },
        extras?.let { guide ->
            guide.totalTimeSec?.takeIf { it > 0 && (guide.remainingTimeSec == null || it >= guide.remainingTimeSec) }
        },
        extras?.reportedRoadType,
        extras,
        fields.noaRouteGeneration,
        source.cruiseLights?.takeIf {
            selected && state != AmapDisplayState.Fresh && it.isFresh(nowMs)
        }?.lamps.orEmpty(),
        source.mapPosition?.takeIf { it.isFresh(nowMs, NaviV2Constants.STALE_TIMEOUT_MS) }?.speedKph?.takeIf {
            selected && it.isFinite() && it in 0.0..300.0
        },
        source.mapPosition?.takeIf { it.isFresh(nowMs, NaviV2Constants.STALE_TIMEOUT_MS) }?.headingDeg?.takeIf {
            selected && it.isFinite() && it in 0.0..<360.0
        },
        source.cruiseRoad?.takeIf { selected && !fields.isNavigating && it.isFresh(nowMs) },
        sectionDetails,
        junction ?: retainedJunction,
        junction == null && retainedJunction != null,
    )
}
