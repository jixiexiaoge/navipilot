package com.jixiexiaoge.drivingassist.navigation

import android.util.Log
import com.jixiexiaoge.drivingassist.CarrotManFields
import org.json.JSONObject

internal data class NaviDeliverySnapshot(
    val ready: Boolean = false,
    val enqueuedStreams: Set<String> = emptySet(),
    val withdrawnStreams: Set<String> = emptySet(),
)

/** Polled by the lifecycle on the main dispatcher; only acknowledged enqueues enter the dedup cache. */
class NaviStreamManager(
    private val client: NaviWebSocketV2Client,
    private val sources: NaviSourceTracker = NaviSourceTracker(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val laneCounts: LaneCountSource = ModelLaneCountSource,
    private val signalWorld: SignalWorldSource = StoreSignalWorldSource,
    private val suppressHighwayLeftGuidance: () -> Boolean = { true },
) {
    companion object {
        private const val TAG = "NaviStreamMgr"
        const val CHECK_INTERVAL_MS = 200L

        /**
         * 模型车道结果的有效窗口。模型按 15fps 出数，这里给 1.2 秒余量；
         * 离开实时页 / 画面断开后模型停更，超过这个窗口就不能再拿最后一帧当有效值。
         */
        private const val MODEL_LANE_TTL_MS = ModelLaneCountSource.MAX_AGE_MS

        /** CP `cluster_renderer` 最多画 8 条车道，超过就没意义 */
        private const val MAX_MODEL_LANE_COUNT = 8
    }

    /** 模型给的两个量：总车道数 + 1-based 当前车道号 */
    private data class ModelLane(val count: Int, val current: Int, val model: String)

    private data class SentState(val present: Boolean, val value: String?, val sentAtMs: Long,
        val sourceTimestampMs: Long, val reason: String? = null)
    private val sent = mutableMapOf<String, SentState>()
    private val enabled = mutableMapOf<String, Boolean>()
    private val sourceReceipts = mutableMapOf<String, Long>()
    private var running = false
    private var foreground = false
    private var invalidRoadLimit: Int? = null

    /** 最近一次参与 `traffic_signal` 合成的 7711 世界量，仅用于诊断面板 */
    private var lastSignalWorld: SignalWorld? = null

    /** 最近一次合成的相位来源：car / amap / car_out_of_range / none */
    private var lastSignalChosen: String = "none"

    @Synchronized
    fun start() {
        running = true
        sent.clear()
        sourceReceipts.clear()
    }

    @Synchronized
    fun stop() {
        // 断开前把仍然 present 的流显式撤销：否则 CP 侧会一直保留断线前的限速/引导/车道，
        // 直到它自己的 stale_timeout 兜底（或永远不兜底）。撤销不参与节流。
        if (running && client.isReady()) {
            val now = clock()
            sent.filterValues { it.present }.keys.forEach { name ->
                runCatching {
                    client.sendJsonData(name, false, null, reason = "stream_stopped", sourceTimestampMs = now)
                }
            }
        }
        running = false
        sent.clear()
        sourceReceipts.clear()
    }

    @Synchronized
    fun setStreamEnabled(name: String, enabled: Boolean) {
        this.enabled[name] = enabled
    }

    @Synchronized
    fun sendAppStatus(foreground: Boolean) {
        this.foreground = foreground
        if (running && client.isReady()) publish("app_status", AppStatusData(foreground).toJson())
    }

    @Synchronized
    fun update(fields: CarrotManFields, amapSelected: Boolean = true) {
        if (!running || !client.isReady()) return
        val now = clock()
        val source = sources.publicationSnapshot(now, amapSelected)
        fun fresh(sourceName: String, streamName: String = sourceName): Boolean =
            amapSelected && source.isFresh(sourceName, now, staleTimeout(streamName)) &&
                (source.updatedAt[sourceName] ?: Long.MIN_VALUE) >=
                (source.updatedAt["ui_route_changed"] ?: Long.MIN_VALUE)
        val position = source.position(now, staleTimeout("vehicle"), amapSelected)
        sourceReceipts.clear()
        sourceReceipts.putAll(source.updatedAt)
        position?.let { sourceReceipts["vehicle"] = it.receivedAtMs }
        val extras = source.guideExtras?.takeIf {
            it.routeGeneration == fields.noaRouteGeneration &&
                now - it.receivedAtMs in 0..staleTimeout("guidance_current")
        }
        fun segmentTime(stream: String, value: Int?): Int? =
            value?.takeIf { extras?.receivedAtMs == source.updatedAt[stream] }
        publish("vehicle", position?.let {
            VehicleData(it.latitude, it.longitude, it.headingDeg, it.speedKph,
                roadName = it.roadName).toJson()
        })
        val categorySource = source.roadCategory?.takeIf {
            amapSelected && fields.isNavigating && it.routeGeneration == fields.noaRouteGeneration &&
                fresh("road_category", "lane_current") &&
                now - it.receivedAtMs in 0..staleTimeout("lane_current")
        }
        val filterLeft = suppressHighwayLeftGuidance()
        val suppressCurrent = LeftGuidancePolicy.suppress(fields.nTBTTurnType, categorySource, filterLeft)
        val suppressNext = LeftGuidancePolicy.suppress(fields.nTBTTurnTypeNext, categorySource, filterLeft)
        val currentSourceValid = fields.isNavigating && fresh("guidance_current") &&
            fields.nTBTTurnType >= 0 && fields.nTBTDist >= 0
        val currentValid = currentSourceValid && !suppressCurrent
        val nextValid = !suppressNext && fields.isNavigating && fresh("guidance_next") &&
            fields.nTBTTurnTypeNext >= 0 && fields.nTBTDistNext >= 0
        fun bindCategoryReceipt(stream: String, turnCode: Int) {
            if (LeftGuidancePolicy.suppress(turnCode, null, filterLeft)) {
                categorySource?.let {
                    sourceReceipts[stream] = minOf(sourceReceipts[stream] ?: now, it.receivedAtMs)
                }
            }
        }
        bindCategoryReceipt("guidance_current", fields.nTBTTurnType)
        bindCategoryReceipt("guidance_next", fields.nTBTTurnTypeNext)
        publish("guidance_current", if (currentValid) GuidanceData(
            distanceM = fields.nTBTDist,
            turnType = fields.nTBTTurnType,
            mainText = fields.szTBTMainText,
            nearDirection = fields.szNearDirName,
            farDirection = fields.szFarDirName,
            roadName = fields.szNearDirName,
            timeSec = segmentTime("guidance_current", extras?.currentSegmentSec),
        ).toJson() else null, if (suppressCurrent) LeftGuidancePolicy.WITHDRAWAL_REASON else "source_absent_or_stale")
        publish("guidance_next", if (nextValid) GuidanceData(
            distanceM = fields.nTBTDistNext,
            turnType = fields.nTBTTurnTypeNext,
            mainText = fields.szTBTMainTextNext,
            roadName = fields.szFarDirName,
            timeSec = segmentTime("guidance_next", extras?.nextSegmentSec),
        ).toJson() else null, if (suppressNext) LeftGuidancePolicy.WITHDRAWAL_REASON else "source_absent_or_stale")

        val route = source.route?.takeIf {
            fields.isNavigating && it.routeGeneration == fields.noaRouteGeneration && fresh("route") &&
                it.remainingDistanceM != null && it.remainingTimeSec != null
        }
        val routeValid = route != null
        publish("route", route?.let {
            val remaining = requireNotNull(it.remainingDistanceM)
            val total = it.totalDistanceM?.takeIf { distance -> distance >= remaining }
            RouteData(
                remainDistanceM = remaining,
                remainTimeSec = requireNotNull(it.remainingTimeSec),
                totalDistanceM = total,
                movedDistanceM = total?.minus(remaining),
            ).toJson()
        })
        val guiding = fields.isNavigating && (currentSourceValid || routeValid) &&
            fresh("navigation_status")
        publish("navigation_status", NavigationStatusData(
            mode = if (guiding) "guiding" else "idle",
            guidanceActive = guiding,
            routePresent = routeValid,
        ).toJson())

        val rawLimit = source.roadSpeedLimit?.takeIf {
            amapSelected && fields.isNavigating && it.routeGeneration == fields.noaRouteGeneration &&
                now - it.receivedAtMs in 0..staleTimeout("speed")
        }
        // The raw snapshot remains a map observation; only an explicitly source-bound
        // navigation policy may replace the wire value. Local/UI fields are not authority.
        val policyLimit = rawLimit?.policyLimitKph?.takeIf {
            rawLimit.policyReceivedAtMs?.let { receipt ->
                now - receipt in 0..staleTimeout("speed")
            } == true
        }
        val roadLimit = policyLimit ?: rawLimit?.limitKph
        val compatibleLimit = roadLimit?.takeIf { it in 10..200 && it % 10 == 0 }
        if (roadLimit != null && compatibleLimit == null && invalidRoadLimit != roadLimit) {
            Log.w(TAG, "carrot v2 不支持道路限速 $roadLimit，撤销该字段而不是伪造限速")
            invalidRoadLimit = roadLimit
        } else if (compatibleLimit != null) invalidRoadLimit = null
        val sdi = source.sdi?.takeIf {
            fields.isNavigating && it.routeGeneration == fields.noaRouteGeneration && fresh("sdi", "speed")
        }
        val secondary = source.secondarySdi?.takeIf {
            fields.isNavigating && it.routeGeneration == fields.noaRouteGeneration && fresh("sdi_secondary", "speed")
        }
        val section = source.section?.takeIf {
            fields.isNavigating && it.routeGeneration == fields.noaRouteGeneration && fresh("section", "speed")
        }
        listOfNotNull(
            position?.receivedAtMs,
            rawLimit?.let {
                if (policyLimit != null) minOf(it.receivedAtMs, it.policyReceivedAtMs!!) else it.receivedAtMs
            }?.takeIf { compatibleLimit != null },
            sdi?.receivedAtMs,
            secondary?.receivedAtMs,
            section?.receivedAtMs,
        ).minOrNull()?.let { sourceReceipts["speed"] = it }
        val speed = SpeedData(
            currentKph = position?.speedKph,
            roadLimitKph = compatibleLimit,
            primarySdi = sdi?.toJson(),
            sectionActive = section != null,
            sectionSpeedLimitKph = section?.speedLimitKph ?: 0,
            sectionAverageKph = section?.averageKph,
            sectionRemainingDistanceM = section?.remainingM,
            sectionProgress = section?.progress,
            secondarySdi = secondary?.toJson(),
        )
        publish("speed", if (compatibleLimit != null || sdi != null || secondary != null || section != null) {
            speed.toJson()
        } else null)

        val signalSource = source.navigationSignal?.takeIf {
            amapSelected && fields.isNavigating && it.routeGeneration == fields.noaRouteGeneration &&
                now - it.receivedAtMs in 0..staleTimeout("traffic_signal")
        }
        signalSource?.let { sourceReceipts["traffic_signal"] = it.receivedAtMs }
        val amapSignal = signalSource?.toSignal(now)
        // 125m 只是源范围校验，不证明 CAN 与高德是同一灯组。
        val carSignal = signalWorld.latest()?.takeIf {
            it.receivedAtMs?.let { receipt -> now - receipt in 0..SignalWorld.MAX_AGE_MS } != false
        }
        lastSignalWorld = carSignal
        lastSignalChosen = TrafficSignalComposer.chosenSource(amapSignal, carSignal)
        val composedSignal = TrafficSignalComposer.compose(amapSignal, carSignal)
        if (lastSignalChosen == "car") {
            sourceReceipts["traffic_signal"] = requireNotNull(carSignal).receivedAtMs ?: (now - carSignal.ageMs)
        }
        publish("traffic_signal", composedSignal?.toJson(), when {
            composedSignal != null -> "source_absent_or_stale"
            lastSignalChosen == "car_yellow_unsupported" -> "car_yellow_not_representable"
            carSignal?.hasLamp == true && !carSignal.distanceValid -> "car_signal_distance_out_of_range"
            else -> "signal_source_missing_unverified_or_expired"
        })

        val laneSource = source.laneCurrent?.takeIf {
            fields.isNavigating && it.routeGeneration == fields.noaRouteGeneration &&
                fresh("lane_current") && now - it.receivedAtMs in 0..staleTimeout("lane_current")
        }
        val orderedLanes = laneSource?.lanes?.takeIf { lanes ->
            lanes.map { it.driveWayNumber }.sorted() == lanes.indices.toList()
        }?.sortedBy { it.driveWayNumber }.orEmpty()
        val lanes = orderedLanes.mapIndexed { index, lane ->
            LaneItem(index = index, direction = lane.id, isRecommended = lane.isRecommended,
                driveWayNumber = lane.driveWayNumber, driveWayLaneExtended = lane.driveWayLaneExtended,
                trafficLaneExtendedNew = lane.trafficLaneExtendedNew, trafficLaneType = lane.trafficLaneType)
        }
        val laneValid = fields.isNavigating && fresh("lane_current") &&
            !fields.laneInfoFromCache && lanes.isNotEmpty()
        // Production samples require AR/7000 geometric agreement; they never identify a map junction lane.
        val modelSample = laneCounts.latest()
        val modelLane = modelSample?.takeIf { sample ->
            sample.targetHost == client.getTargetHost() &&
                sample.routeGeneration == fields.noaRouteGeneration && sample.sessionId > 0
        }?.let { sample ->
            val count = sample.count
            // lanesLeft 的语义是「含当前车道的左侧车道数」，因此它本身就是 1-based 当前车道号
            val current = sample.currentLane
            if (now - sample.atMs !in 0..MODEL_LANE_TTL_MS ||
                count !in 1..MAX_MODEL_LANE_COUNT ||
                current !in 1..count) null else ModelLane(count, current, sample.model)
        }
        val roadCategory = categorySource?.carrotCategory()
        listOfNotNull(
            modelSample?.atMs?.takeIf { modelLane != null },
            laneSource?.receivedAtMs?.takeIf { laneValid },
            categorySource?.receivedAtMs?.takeIf { roadCategory != null },
            source.updatedAt["guidance_current"]?.takeIf { laneValid && currentValid },
        ).minOrNull()?.let { sourceReceipts["lane_current"] = it }
        val knownLanes = if (laneValid) lanes else emptyList()
        // 车道数：地图车道图优先；地图缺图时用模型估计（CP 的 lane strip 只读 count，
        // 没有 available 就是灰格子，所以不会把模型数伪装成「可通行」）
        val laneCount = if (laneValid) knownLanes.size else modelLane?.count ?: 0
        // Equal counts alone do not associate a current-road estimate with a future junction lane graph.
        val currentLane = modelLane?.current?.takeIf {
            it in 1..laneCount && !laneValid
        } ?: -1
        val turnInfo = if (laneValid) LaneTurnCode.all(knownLanes.map { it.direction }) else null
        // The current receiver control parser ignores roadCategoryValid and treats omission as 0.
        // Withhold the mixed lane item instead of manufacturing a motorway or a non-motorway.
        // 注意：只要类别有效就必须发（control 只读 road_category），车道图缺失时发 count=0、
        // visible=false 的「空车道图 + 类别」，模型有数时 count/current_lane 才被填上。
        publish("lane_current", if (roadCategory != null) LaneData(
            laneCount = laneCount, lanes = knownLanes,
            recommendedLane = knownLanes.indexOfFirst { it.isRecommended },
            distanceM = fields.nTBTDist.takeIf { laneValid && currentValid },
            visible = laneValid || modelLane != null,
            lanePlay = laneValid,
            currentLane = currentLane,
            turnCode = if (laneValid && currentValid) fields.nTBTTurnType else -1,
            roadCategory = roadCategory,
            turnInfo = turnInfo,
            laneSource = when {
                laneValid && modelLane != null -> "map+model"
                laneValid -> "map"
                modelLane != null -> "model"
                else -> "none"
            },
            modelCount = modelLane?.count,
            modelCurrentLane = modelLane?.current ?: -1,
        ).toJson() else null,
            if (laneValid && roadCategory == null) "unknown_road_category_receiver_compat"
            else "source_absent_or_stale")
        // 下一路口车道组：当前没有任何已接入的来源（高德 13012 只给当前路口图，
        // 模型只给 ego 车道数），所以仍然撤销。结构上每个元素与 lane_current 同构，
        // 将来接入下一路口图时直接 listOf(LaneData(...)) 即可，不需要改协议。
        publish("lane_ahead", null, "next_junction_lane_map_absent")
        publish("crossroad", null, "source_unavailable")
        publish("camera_state", null, "source_unavailable")
        publish("composition_state", null, "capture_unavailable")
        publish("app_status", AppStatusData(foreground).toJson())
    }

    private fun config(name: String): StreamConfig? = client.getSession()?.streams?.get("json:$name")

    private fun staleTimeout(name: String): Long =
        (config(name)?.params?.get("stale_timeout_ms") as? Number)?.toLong()?.coerceAtLeast(1)
            ?: NaviV2Constants.STALE_TIMEOUT_MS

    private fun publish(name: String, value: JSONObject?, reason: String = "source_absent_or_stale") {
        val config = config(name)?.takeIf { it.enabled && enabled[name] != false } ?: return
        val now = clock()
        val present = value != null
        val text = value?.toString()
        val sourceTimestampMs = if (present && name != "app_status") sourceReceipts[name] ?: now else now
        val previous = sent[name]
        // A shrinking composite must withdraw expired fields even inside the live-data throttle.
        val removesFields = previous?.value?.let {
            present && previous.present && fieldsWithdrawn(JSONObject(it), value)
        } == true
        if (removesFields) {
            if (!client.sendJsonData(name, false, null, reason = "source_fields_withdrawn",
                    sourceTimestampMs = now)) return
            sent[name] = SentState(false, null, now, now, "source_fields_withdrawn")
        }
        val effectivePrevious = sent[name]
        val changed = previous == null || previous.present != present || previous.value != text ||
            (present && name != "app_status" && previous.sourceTimestampMs != sourceTimestampMs) ||
            (!present && previous.reason != reason)
        val interval = (config.params["interval_ms"] as? Number)?.toLong()?.coerceAtLeast(0) ?: 1000L
        val mode = config.params["delivery_mode"] as? String ?: "on_change"
        val timed = name != "lane_current" && mode in setOf("periodic", "on_change_with_heartbeat") &&
            (previous == null || now - previous.sentAtMs >= interval)
        if (!changed && !timed) return
        // Withdrawals must not wait behind a live-data throttle.
        if (present && effectivePrevious?.present == true && now - effectivePrevious.sentAtMs < interval) return
        if (client.sendJsonData(name, present, value, reason = if (present) null else reason,
                sourceTimestampMs = sourceTimestampMs)) {
            sent[name] = SentState(present, text, now, sourceTimestampMs, reason.takeIf { !present })
        }
    }

    @Synchronized
    internal fun deliverySnapshot(): NaviDeliverySnapshot {
        if (!running || !client.isReady()) return NaviDeliverySnapshot()
        val tracked = setOf("route", "guidance_current", "guidance_next", "lane_current")
        return NaviDeliverySnapshot(true,
            sent.filter { (name, state) -> name in tracked && state.present }.keys.toSet(),
            sent.filter { (name, state) -> name in tracked && !state.present }.keys.toSet())
    }

    @Synchronized
    fun debugSnapshot(): JSONObject = JSONObject().apply {
        val now = clock()
        put("streamCount", sent.size)
        put("presentCount", sent.values.count { it.present })
        // 7711 → 7714 的合成诊断：车端给到哪一步、最终采用哪一侧
        put("signalMerge", JSONObject().apply {
            val world = lastSignalWorld
            put("carLamp", world?.lightColor ?: 0)
            put("carDistance", world?.stopLineDistM?.toDouble() ?: JSONObject.NULL)
            put("carAgeMs", world?.ageMs ?: -1)
            put("carDistanceValid", world?.distanceValid ?: false)
            put("maxDistanceM", SignalWorld.MAX_VALID_DISTANCE_M.toDouble())
            put("chosen", lastSignalChosen)
        })
        NaviV2Constants.JSON_NAMES.forEach { name ->
            val state = sent[name]
            put(name, JSONObject().apply {
                put("present", state?.present ?: false)
                put("ageMs", state?.let { now - it.sentAtMs } ?: -1)
                put("value", state?.value ?: JSONObject.NULL)
                put("reason", state?.reason ?: JSONObject.NULL)
            })
        }
    }
}

private fun fieldsWithdrawn(previous: JSONObject, value: JSONObject?): Boolean {
    if (value == null) return true
    return previous.keys().asSequence().any { key ->
        val old = previous.opt(key)
        val current = value.opt(key)
        when {
            !value.has(key) -> true
            key in setOf("visible", "lane_play", "active", "guidance_active", "route_present") &&
                old == true && current == false -> true
            old is org.json.JSONArray && current is org.json.JSONArray ->
                old.length() > 0 && current.length() == 0
            old is JSONObject && current is JSONObject -> fieldsWithdrawn(old, current)
            else -> false
        }
    }
}
