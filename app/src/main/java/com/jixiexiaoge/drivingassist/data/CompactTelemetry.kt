package com.jixiexiaoge.drivingassist.data

import com.jixiexiaoge.drivingassist.CarStateData
import com.jixiexiaoge.drivingassist.LeadData
import com.jixiexiaoge.drivingassist.ModelV2Data
import com.jixiexiaoge.drivingassist.SystemStateData
import com.jixiexiaoge.drivingassist.XiaogeControlsStateData
import com.jixiexiaoge.drivingassist.XiaogeVehicleData
import kotlin.math.abs

/** Receipt time is local monotonic time: CVS1 sequence is not a cereal source timestamp. */
data class CompactServiceSample(
    val frame: CompactFrame,
    val receivedAtMs: Long,
    val expiresAtMs: Long,
    val receivedAtWallMs: Long = 0,
)

data class CompactTelemetry(
    val services: Map<String, CompactServiceSample> = emptyMap(),
    val observedAtMs: Long = 0,
) {
    fun fresh(service: String): CompactServiceSample? =
        services[service]?.takeIf { observedAtMs < it.expiresAtMs }

    val carrotMan: CarrotManReceipt?
        get() = fresh("carrotMan")?.let { CarrotManReceipt(it) }
    val carrotNavi: CarrotNaviReceipt?
        get() = fresh("carrotNavi")?.let { CarrotNaviReceipt(it) }

    /**
     * Radar vRel is m/s relative to ego. Only correlate locally co-received, fresh
     * ego samples; transport has no source timestamp to prove tighter synchronization.
     * leadLeft/right are radar track selections, NOT model leads or guaranteed adjacent cars.
     */
    fun radarLead(name: String): CompactRadarLead? {
        if (name !in setOf("leadOne", "leadTwo", "leadLeft", "leadRight")) return null
        val radar = fresh("radarState") ?: return null
        val values = radar.frame.values.objectValue(name) ?: return null
        if (values["status"] != true) return null
        val ego = fresh("carState")
        val speed = ego?.frame?.values?.floatValue("vEgo")
            ?.takeIf { abs(ego.receivedAtMs - radar.receivedAtMs) <= 150 }
        val relative = values.floatValue("vRel") ?: return null
        return CompactRadarLead(
            name, values.floatValue("dRel") ?: return null,
            values.floatValue("yRel") ?: return null, relative,
            speed?.let { it + relative }, radar.receivedAtMs,
        )
    }
}

data class CompactRadarLead(
    val selection: String,
    val distanceM: Float,
    val lateralM: Float,
    val relativeSpeedMps: Float,
    val estimatedAbsoluteSpeedMps: Float?,
    val receivedAtMs: Long,
)

data class CarrotManReceipt(val sample: CompactServiceSample) {
    val fields get() = sample.frame.values
    val desiredSpeedKph get() = fields.intValue("desiredSpeed")
    val desiredSource get() = fields["desiredSource"] as? String
    val roadLimitKph get() = fields.intValue("nRoadLimitSpeed")
    val speedType get() = fields.intValue("xSpdType")
    val speedLimitKph get() = fields.intValue("xSpdLimit")
    val speedDistanceM get() = fields.intValue("xSpdDist")
    val speedCountdownSec get() = fields.intValue("xSpdCountDown")
    val turnInfo get() = fields.intValue("xTurnInfo")
    val turnDistanceM get() = fields.intValue("xDistToTurn")
    val turnCountdownSec get() = fields.intValue("xTurnCountDown")
    val roadName get() = fields["szPosRoadName"] as? String
    val mainText get() = fields["szTBTMainText"] as? String
}

/** Normalized compact receipt, NOT a command and never written back as Android navigation input. */
data class CarrotNaviReceipt(val sample: CompactServiceSample) {
    val fields get() = sample.frame.values
    val connected get() = fields["connected"] as? Boolean
    val schemaVersion get() = fields.intValue("schemaVersion")
    val sessionId get() = fields["sessionId"] as? String
    val generation get() = fields["generation"] as? ULong
    val publishMonoTimeNanos get() = fields["publishMonoTimeNanos"] as? ULong
    val vehicle get() = fields.objectValue("vehicle")
    val guidanceCurrent get() = fields.objectValue("guidanceCurrent")
    val guidanceNext get() = fields.objectValue("guidanceNext")
    val laneCurrent get() = fields.objectValue("laneCurrent")
    val speed get() = fields.objectValue("speed")
    val trafficSignal get() = fields.objectValue("trafficSignal")
    val route get() = fields.objectValue("route")
    val navigationStatus get() = fields.objectValue("navigationStatus")
}

/** Single-owner reducer; client serializes socket callbacks and expiry ticks. */
class CompactTelemetryStore {
    private var samples: Map<String, CompactServiceSample> = emptyMap()
    private val sequences = mutableMapOf<String, Int>()
    private var modelFrame: Long? = null
    var acceptedFramesLastBatch: Int = 0
        private set
    var rejectedFramesLastBatch: Int = 0
        private set
    var modelResetLastBatch: Boolean = false
        private set
    fun clear() {
        samples = emptyMap()
        sequences.clear()
        modelFrame = null
        acceptedFramesLastBatch = 0
        rejectedFramesLastBatch = 0
        modelResetLastBatch = false
    }
    fun accept(frames: List<CompactFrame>, nowMs: Long, wallTimeMs: Long = System.currentTimeMillis()): CompactTelemetry {
        acceptedFramesLastBatch = 0
        rejectedFramesLastBatch = 0
        modelResetLastBatch = false
        frames.forEach { frame ->
            val previous = sequences[frame.service]
            val advance = previous?.let { (frame.sequence - it) and 0xffff }
            if (advance != null && advance !in 1..32767) {
                rejectedFramesLastBatch++
                return@forEach
            }
            sequences[frame.service] = frame.sequence
            if (frame.service == "modelV2") {
                val id = (frame.values["frameId"] as? Number)?.toLong()
                // Transport sequence progress does not prove inference-frame progress.
                if (id == null || modelFrame?.let { ((id - it) and 0xffffffffL) !in 1L..0x7fffffffL } == true) {
                    if (id != null && id != modelFrame) {
                        samples = samples - "modelV2"
                        modelResetLastBatch = true
                    }
                    rejectedFramesLastBatch++
                    return@forEach
                }
                modelFrame = id
            }
            samples = samples + (frame.service to CompactServiceSample(
                frame, nowMs, nowMs + expiryMs(frame.service), wallTimeMs))
            acceptedFramesLastBatch++
        }
        return snapshot(nowMs)
    }
    fun snapshot(nowMs: Long) = CompactTelemetry(samples, nowMs)

    private fun expiryMs(service: String): Long = when (service) {
        "carState", "carControl", "controlsState" -> 500
        "modelV2", "radarState", "lateralPlan", "longitudinalPlan" -> 750
        "selfdriveState", "carrotMan" -> 1500
        else -> 3000
    }
}

internal fun Map<String, Any?>.floatValue(key: String) = (get(key) as? Number)?.toFloat()
internal fun Map<String, Any?>.intValue(key: String) = (get(key) as? Number)?.toInt()
@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.objectValue(key: String) = get(key) as? Map<String, Any?>

/** Legacy HUD view. Unsupported numeric values are NaN, not zero; never use for automation. */
internal fun CompactTelemetry.toVehicleData(wallTimeMs: Long): VehicleData? {
    val car = fresh("carState")?.frame?.values
    val model = fresh("modelV2")?.frame?.values
    val controls = fresh("controlsState")?.frame?.values
    val selfdrive = fresh("selfdriveState")?.frame?.values
    val carrot = fresh("carrotMan")?.frame?.values
    if (listOf(car, model, controls, selfdrive, carrot).all { it == null }) return null
    return VehicleData(
        carState = car?.let {
            CarState(
                vEgo = it.floatValue("vEgo") ?: Float.NaN,
                steeringAngleDeg = it.floatValue("steeringAngleDeg") ?: Float.NaN,
                leftBlindspot = it["leftBlindspot"] == true,
                rightBlindspot = it["rightBlindspot"] == true,
                leftBlinker = it["leftBlinker"] == true,
                rightBlinker = it["rightBlinker"] == true,
            )
        },
        modelV2 = model?.let {
            val leads = it["leadsV3"] as? List<*>
            val lead = leads?.firstOrNull() as? Map<*, *>
            fun first(key: String) = ((lead?.get(key) as? List<*>)?.firstOrNull() as? Number)?.toFloat() ?: Float.NaN
            ModelV2(
                leadX = first("x"),
                leadV = first("v"), // model leadsV3.v is absolute m/s, unlike radar vRel.
                leadProb = (lead?.get("prob") as? Number)?.toFloat() ?: Float.NaN,
                laneLineProbs = (it["laneLineProbs"] as? List<*>)?.mapNotNull { value ->
                    (value as? Number)?.toFloat()
                } ?: emptyList(),
            )
        },
        controlsState = controls?.let {
            ControlsState(
                enabled = it["enabled"] == true,
                vCruise = (it.floatValue("vCruiseCluster") ?: Float.NaN) / 3.6f,
                curvature = it.floatValue("curvature") ?: Float.NaN,
            )
        },
        selfdriveState = selfdrive?.let { SelfdriveState(enabled = it["enabled"] == true) },
        carrotMan = carrot,
        // Expiry ticks must not make old vehicle samples appear newly received.
        timestamp = listOf("carState", "modelV2", "controlsState", "selfdriveState", "carrotMan")
            .mapNotNull { fresh(it)?.receivedAtWallMs?.takeIf { receipt -> receipt > 0 } }
            .maxOrNull() ?: wallTimeMs,
    )
}

/** Display adapter only: missing compact authorization/meta remains unavailable. */
internal fun VehicleData.toDisplayVehicleData(tbtDist: Int): XiaogeVehicleData = XiaogeVehicleData(
    sequence = 0,
    timestamp = timestamp.toDouble() / 1000.0,
    ip = null,
    receiveTime = timestamp,
    carState = carState?.let {
        CarStateData(
            vEgo = it.vEgo,
            steeringAngleDeg = it.steeringAngleDeg,
            leftLatDist = Float.NaN,
            leftBlindspot = it.leftBlindspot,
            rightBlindspot = it.rightBlindspot,
            leftBlinker = it.leftBlinker,
            rightBlinker = it.rightBlinker,
        )
    },
    modelV2 = modelV2?.let {
        ModelV2Data(
            lead0 = it.leadX.takeIf(Float::isFinite)?.let { distance ->
                LeadData(distance, Float.NaN, it.leadV, it.leadProb)
            },
            leadLeft = null,
            leadRight = null,
            laneLineProbs = it.laneLineProbs,
            meta = null,
            curvature = null,
        )
    },
    systemState = selfdriveState?.let { SystemStateData(enabled = it.enabled, active = false) },
    controlsState = controlsState?.let {
        XiaogeControlsStateData(enabled = it.enabled, active = false,
            vCruise = it.vCruise * 3.6f, curvature = it.curvature)
    },
    tbtDist = tbtDist,
)
