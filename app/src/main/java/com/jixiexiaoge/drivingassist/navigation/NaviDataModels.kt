package com.jixiexiaoge.drivingassist.navigation

import org.json.JSONArray
import org.json.JSONObject

// ═══════════════════════════════════════════════════════════════
// NaviDataModels.kt — Carrot Navi v2 协议数据模型
// 对应 carrot_navi.py 中的协议常量和数据结构
// ═══════════════════════════════════════════════════════════════

// ─── 协议常量 ───

object NaviV2Constants {
    const val PROTOCOL_VERSION = 2
    const val CATALOG_REVISION = 1
    const val DEFAULT_PORT = 7714
    const val DISCOVERY_PORT = 7705
    const val CONTROL_HEARTBEAT_S = 15L
    const val STREAM_HEARTBEAT_S = 10L
    const val CONTROL_RECONNECT_DELAY_MS = 2000L
    const val MAX_RECONNECT_DELAY_MS = 30000L
    const val MAX_MESSAGE_BYTES = 8 * 1024 * 1024
    const val STALE_TIMEOUT_MS = 10000L
    const val SEND_INTERVAL_MS = 200L
    const val CHECK_INTERVAL_MS = 1000L
    const val CONTROL_FAIL_RESET_THRESHOLD = 3

    /** 所有 JSON 流名称（按协议顺序） */
    val JSON_NAMES = listOf(
        "vehicle", "guidance_current", "guidance_next", "lane_current",
        "lane_ahead", "speed", "traffic_signal", "crossroad", "route",
        "navigation_status", "app_status", "camera_state", "composition_state"
    )

    /** 所有 IMAGE 流名称（跳过不实现，但 manifest 需要） */
    val IMAGE_NAMES = listOf(
        "tbt_current_compact", "tbt_current_full", "tbt_next",
        "traffic_signal", "lane_top", "lane_bottom",
        "safety_primary", "safety_secondary", "safety_section",
        "crossroad_minimized", "crossroad_expanded",
        "center_tbt_icon", "center_tbt_text", "center_tbt_fee"
    )

    /** 所有 RENDER 流名称 */
    val RENDER_NAMES = listOf("map_main")

    /** 完整 CATALOG（28 项） */
    val CATALOG: List<Pair<String, String>> by lazy {
        val list = mutableListOf<Pair<String, String>>()
        JSON_NAMES.forEach { list.add("json" to it) }
        IMAGE_NAMES.forEach { list.add("image" to it) }
        RENDER_NAMES.forEach { list.add("render" to it) }
        list
    }

    /** 实际会启用的 JSON 流（不包括 image/render） */
    val ENABLED_JSON_STREAMS = JSON_NAMES
}

// ─── 会话状态 ───

/** WebSocket v2 连接状态 */
enum class NaviV2ConnectionState {
    DISCONNECTED,
    DISCOVERING,
    CONTROL_CONNECTING,
    NEGOTIATING,
    CONNECTED,
    RECONNECTING,
    FAILED
}

/** 会话信息 */
data class NaviV2Session(
    val sessionId: String = "",
    val revision: Int = 0,
    val streams: Map<String, StreamConfig> = emptyMap()  // key = "kind:name"
)

/** 单个流的配置 */
data class StreamConfig(
    val kind: String,
    val name: String,
    val schemaVersion: Int = 1,
    val streamHandle: Int = 0,
    val enabled: Boolean = true,
    val params: Map<String, Any> = emptyMap()
)

// ─── 消息信封 ───

/** v2 JSON 消息信封 */
data class JsonEnvelope(
    val type: String = "item_update",
    val protocolVersion: Int = NaviV2Constants.PROTOCOL_VERSION,
    val sessionId: String,
    val kind: String = "json",
    val name: String,
    val manifestRevision: Int,
    val streamHandle: Int,
    val schemaVersion: Int = 1,
    val sequence: Long,
    val sourceTimestampMs: Long = System.currentTimeMillis(),
    val sentAtMs: Long = System.currentTimeMillis(),
    val present: Boolean,
    val value: Any? = null,
    val reason: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        put("protocol_version", protocolVersion)
        put("session_id", sessionId)
        put("kind", kind)
        put("name", name)
        put("manifest_revision", manifestRevision)
        put("stream_handle", streamHandle)
        put("schema_version", schemaVersion)
        put("sequence", sequence)
        put("source_timestamp_ms", sourceTimestampMs)
        put("sent_at_ms", sentAtMs)
        put("present", present)
        // 始终写入 value 字段（服务端要求 value key 必须存在）
        if (present && value != null) {
            put("value", value)
        } else {
            put("value", JSONObject.NULL)
        }
        // absent 时必须提供非空 reason
        if (!present) {
            put("reason", (reason ?: "source_absent").take(64))
        }
    }
}

// ─── 数据值结构（对应 carrot_navi_cereal.py 中 build_carrot_navi_payload 的输入）───

/** Vehicle 数据 */
data class VehicleData(
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val headingDeg: Double? = null,
    val speedKph: Double? = null,
    val roadName: String = "",
    val virtualGps: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("lat", lat)
        put("lon", lon)
        headingDeg?.let { put("heading_deg", it) }
        speedKph?.let { put("speed_kph", it) }
        put("road_name", roadName)
        put("virtual_gps", virtualGps)
    }
}

/** 导航引导数据 */
data class GuidanceData(
    val distanceM: Int = 0,
    val turnType: Int = -1,
    val mainText: String = "",
    val nearDirection: String = "",
    val farDirection: String = "",
    val roadName: String = "",
    val timeSec: Int? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("distance_m", distanceM)
        put("turn_type", turnType)
        put("main_text", mainText)
        put("near_direction", nearDirection)
        put("far_direction", farDirection)
        put("road_name", roadName)
        timeSec?.takeIf { it in 0..604800 }?.let { put("time_sec", it) }
    }
}

/** Speed 数据 */
data class SpeedData(
    val currentKph: Double? = null,
    val roadLimitKph: Int? = null,
    val sectionActive: Boolean = false,
    val sectionSpeedLimitKph: Int = 0,
    val sectionAverageKph: Double? = null,
    val sectionRemainingDistanceM: Double? = null,
    val sectionRemainingTimeSec: Int? = null,
    val sectionProgress: Double? = null,
    val sectionSuspended: Boolean? = null,
    val sectionOffRoute: Boolean? = null,
    val secondarySdi: JSONObject? = null,
    val primarySdi: JSONObject? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        currentKph?.let { put("current_kph", it) }
        roadLimitKph?.takeIf { it in 10..200 && it % 10 == 0 }?.let { put("road_limit_kph", it) }
        primarySdi?.let { put("sdi", it) }
        secondarySdi?.let { put("sdi_secondary", it) }
        if (sectionActive) put("section", JSONObject().apply {
            put("active", sectionActive)
            put("speed_limit_kph", sectionSpeedLimitKph)
            sectionAverageKph?.let { put("average_kph", it) }
            sectionRemainingDistanceM?.let { put("remaining_distance_m", it) }
            sectionRemainingTimeSec?.let { put("remaining_time_sec", it) }
            sectionProgress?.let { put("progress", it) }
            sectionSuspended?.let { put("suspended", it) }
            sectionOffRoute?.let { put("off_route", it) }
        })
    }
}

/** TrafficSignal 数据 */
data class TrafficSignalData(
    val visible: Boolean = false,
    val distanceM: Int? = null,
    val redValid: Boolean = false,
    val redOn: Boolean = false,
    val redRemainSec: Int = 0,
    val greenValid: Boolean = false,
    val greenOn: Boolean = false,
    val greenRemainSec: Int = 0,
    val leftValid: Boolean = false,
    val leftOn: Boolean = false,
    val leftRemainSec: Int = 0,
    val rightValid: Boolean = false,
    val rightOn: Boolean = false,
    val rightRemainSec: Int = 0,
    val uturnValid: Boolean = false,
    val uturnOn: Boolean = false,
    val uturnRemainSec: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("visible", visible)
        distanceM?.let { put("distance_m", it) }
        // 值域保持 "navigation"：接收端可能按该字符串分支，改值风险高于收益。
        // 相位真正的来源在发送侧诊断（NaviStreamManager.debugSnapshot 的 signalMerge）里体现。
        put("source", "navigation")
        val lights = JSONObject().apply {
            putLight("red", redValid, redOn, redRemainSec)
            putLight("green", greenValid, greenOn, greenRemainSec)
            putLight("left", leftValid, leftOn, leftRemainSec)
            putLight("right", rightValid, rightOn, rightRemainSec)
            putLight("uturn", uturnValid, uturnOn, uturnRemainSec)
        }
        put("lights", lights)
        val countdown = sequenceOf("red", "left", "green", "right", "uturn")
            .mapNotNull { lights.optJSONObject(it) }
            .firstOrNull { it.optBoolean("on") && it.has("remain_sec") }
            ?.optInt("remain_sec")
            ?.takeIf { it > 0 }
        countdown?.let {
            put("ui_counter", JSONObject().apply {
                put("remain_sec", it)
            })
        }

    }

    /**
     * 只在「有真实倒计时」或「该相位明确点亮」时写灯对象。
     *
     * 车端 7711 只给灯色、不给倒计时，如果沿用旧的 `valid && remain>=1` 条件，
     * 车端红灯会被整条丢掉。灭灯（on=false）且无倒计时仍然整键省略，
     * 保持「无有效相位 = lights 整对象省略」这一原有保证。
     */
    private fun JSONObject.putLight(name: String, valid: Boolean, on: Boolean, remaining: Int) {
        if (!valid) return
        if (remaining !in 1..MAX_PHASE_SECONDS && !on) return
        put(name, JSONObject().apply {
            put("valid", true)
            put("on", on)
            if (remaining in 1..MAX_PHASE_SECONDS) put("remain_sec", remaining)
        })
    }

    private companion object {
        const val MAX_PHASE_SECONDS = 999
    }
}

/** Route 数据 */
data class RouteData(
    val remainDistanceM: Int = 0,
    val remainTimeSec: Int = 0,
    val movedDistanceM: Int? = null,
    val movedTimeSec: Int? = null,
    val totalDistanceM: Int? = null,
    val polyline: List<Pair<Double, Double>> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("remain_distance_m", remainDistanceM)
        put("remain_time_sec", remainTimeSec)
        movedDistanceM?.let { put("moved_distance_m", it) }
        movedTimeSec?.let { put("moved_time_sec", it) }
        totalDistanceM?.let { put("total_distance_m", it) }
        put("polyline", JSONArray().apply {
            polyline.forEach { (lat, lon) ->
                put(JSONObject().apply {
                    put("lat", lat)
                    put("lon", lon)
                })
            }
        })
    }
}

/** NavigationStatus 数据 */
data class NavigationStatusData(
    val mode: String = "idle",
    val guidanceActive: Boolean = false,
    val offRoute: Boolean? = null,
    val routePresent: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("mode", mode)
        put("guidance_active", guidanceActive)
        offRoute?.let { put("off_route", it) }
        put("route_present", routePresent)
    }
}

/** Lane 数据 */
data class LaneData(
    val laneCount: Int = 0,
    val lanes: List<LaneItem> = emptyList(),
    val recommendedLane: Int = -1,
    val distanceM: Int? = null,
    val visible: Boolean = true,
    val lanePlay: Boolean = false,
    val currentLane: Int = -1,
    val turnCode: Int = -1,
    val roadCategory: Int? = null,
    val voiceCode: Int = 0,
    /** 逐车道转向码（CP 转向码域，见 [LaneTurnCode]）；null = 不可完整翻译，整键省略 */
    val turnInfo: List<Int>? = null,
    /** 车道数来源：`map`（高德 13012 车道图）/ `model`（本机 AR 车道模型）/ `map+model` */
    val laneSource: String = "map",
    /** 模型估计总车道数；仅当地图车道图缺失时才作为 `count`，这里留原值便于对照 */
    val modelCount: Int? = null,
    /** 模型估计的当前车道（1-based，与 `current_lane` 同约定） */
    val modelCurrentLane: Int = -1,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("count", laneCount)
        distanceM?.let { put("distance_m", it) }
        put("visible", visible)
        put("lane_play", lanePlay)
        put("current_lane", currentLane)
        put("turn_code", turnCode)
        // turn_info 必须与 count 等长；长度不符时整键省略，避免 CP 逐车道错位
        turnInfo?.takeIf { it.size == laneCount }?.let {
            put("turn_info", JSONArray().apply { it.forEach { code -> put(code) } })
        }
        if (lanes.isNotEmpty() && lanes.all { it.isAvailable != null }) {
            put("available", JSONArray().apply {
                lanes.forEach { put(if (it.isAvailable == true) 1 else 0) }
            })
        }
        roadCategory?.let { put("road_category", it) }
        put("voice_code", voiceCode)
        // 诊断键：cereal 不读，用于现场判断这条车道数到底是地图来的还是模型来的
        put("lane_source", laneSource)
        modelCount?.let { put("model_count", it) }
        if (modelCurrentLane >= 0) put("model_current_lane", modelCurrentLane)

        // Compatibility with early Navipilot Android builds and CP fallback parser.
        put("lane_count", laneCount)
        put("recommended_lane", recommendedLane)
        put("lanes", JSONArray().apply {
            lanes.forEach { lane ->
                put(JSONObject().apply {
                    put("index", lane.index)
                    put("direction", lane.direction)
                    put("is_recommended", lane.isRecommended)
                    lane.isAvailable?.let { put("is_available", it) }
                    put("drive_way_number", lane.driveWayNumber)
                    put("drive_way_lane_extended", lane.driveWayLaneExtended)
                    put("traffic_lane_extended_new", lane.trafficLaneExtendedNew)
                    put("traffic_lane_type", lane.trafficLaneType)
                })
            }
        })
    }
}

data class LaneItem(
    val index: Int = 0,
    val direction: String = "",
    val directions: List<String> = listOf("unknown"),
    val isRecommended: Boolean = false,
    val isAvailable: Boolean? = null,
    val driveWayNumber: Int = 0,
    val driveWayLaneExtended: String = "0",
    val trafficLaneExtendedNew: Int = 0,
    val trafficLaneType: Int = 0
)

/** Highway NOA/EAP intent; advisory only, CP owns validation and control decisions. */
data class NoaIntentData(
    val routeGeneration: Long = 0,
    val routeChangeReason: String = "",
    val routeId: String = "",
    val source: String = "amap_auto",
    val roadName: String = "",
    val roadType: Int = 8,
    val roadCategory: Int = 0,
    val controlledAccess: Boolean = false,
    val maneuverType: String = "none",
    val distanceM: Int = 0,
    val exitSide: String = "unknown",
    val mainText: String = "",
    val nearRoad: String = "",
    val exitDirectionInfo: String = "",
    val exitNameInfo: String = "",
    val rawAmapIcon: Int = -1,
    val rawAmapIconSource: String = "",
    val rawSegAssistantAction: Int = -1,
    val laneCount: Int = 0,
    val preferredLaneMask: Int = 0,
    val lanes: List<LaneItem> = emptyList(),
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val headingDeg: Double = 0.0,
    val speedKph: Double = 0.0,
    val mapMatched: Boolean = false,
    val sourceSequence: Long = 0,
    val sourceTimestampMs: Long = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schema_version", 1)
        put("source", source)
        put("route_id", routeId)
        put("route_generation", routeGeneration)
        put("route", JSONObject().apply {
            put("generation_reason", routeChangeReason)
        })
        put("road", JSONObject().apply {
            put("name", roadName)
            put("road_type", roadType)
            put("road_category", roadCategory)
            put("controlled_access", controlledAccess)
            put("is_highway", controlledAccess)
            put("is_ramp", maneuverType.contains("ramp"))
            put("confidence", if (controlledAccess) 0.8 else 0.3)
        })
        put("maneuver", JSONObject().apply {
            put("type", maneuverType)
            put("distance_m", distanceM)
            put("exit_side", exitSide)
            put("main_text", mainText)
            put("near_road", nearRoad)
            put("exit_direction_info", exitDirectionInfo)
            put("exit_name_info", exitNameInfo)
            put("raw_amap_icon", rawAmapIcon)
            put("raw_amap_icon_source", rawAmapIconSource)
            put("raw_seg_assistant_action", rawSegAssistantAction)
        })
        put("lanes", JSONObject().apply {
            put("lane_count", laneCount)
            put("index_order", "left_to_right")
            put("current_lane_hint", -1)
            put("preferred_lane_mask", preferredLaneMask)
            put("preferred_lanes", JSONArray().apply {
                lanes.forEachIndexed { index, lane ->
                    if (lane.isRecommended) put(index)
                }
            })
            put("lanes", JSONArray().apply {
                lanes.forEach { lane ->
                    put(JSONObject().apply {
                        put("index", lane.index)
                        put("directions", JSONArray().apply { lane.directions.forEach(::put) })
                        put("recommended", lane.isRecommended)
                        put("available", lane.isAvailable)
                        put("raw_icon", lane.direction)
                        put("raw_drive_way_number", lane.driveWayNumber)
                        put("raw_drive_way_lane_extended", lane.driveWayLaneExtended)
                        put("raw_traffic_lane_extended_new", lane.trafficLaneExtendedNew)
                        put("raw_traffic_lane_type", lane.trafficLaneType)
                    })
                }
            })
        })
        put("position", JSONObject().apply {
            put("lat", lat)
            put("lon", lon)
            put("heading_deg", headingDeg)
            put("speed_kph", speedKph)
            put("map_matched", mapMatched)
        })
        put("freshness", JSONObject().apply {
            put("sequence", sourceSequence)
            put("source_timestamp_ms", sourceTimestampMs)
        })
    }
}

/** AppStatus 数据 */
data class AppStatusData(
    val foreground: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("foreground", foreground)
    }
}
