package com.jixiexiaoge.drivingassist.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction

/** Display-only CVS1/CVB1 schema from Carrot realtime/compact_state.py, not cereal layout.
 * There is no active/engageable, steering fault, driver override, model meta or orientation
 * in this transport. An enabled display bit is NOT permission to automate.
 */
object CompactStateDecoder {
    const val MAX_MESSAGE_BYTES = 1024 * 1024

    private data class Schema(val name: String, val fields: List<Pair<String, String>>, val required: Int)
    private fun fields(spec: String) = spec.trim().split(Regex("\\s+")).map {
        val parts = it.split(':', limit = 2)
        parts[0] to parts[1]
    }
    private val structs = mapOf(
        "xyz" to fields("x:ucm y:mm z:mm"),
        "velocity" to fields("x:cm"),
        "modelLead" to fields("prob:f x:ucm y:first v:first"),
        "tpms" to fields("fl:f fr:f rl:f rr:f"),
        "radarLead" to fields("dRel:f yRel:f vRel:f aRel:f vLead:f aLead:f dPath:f vLat:f vLeadK:f aLeadK:f fcw:b status:b aLeadTau:f modelProb:f radar:b radarTrackId:i jLead:f score:f"),
        "actuators" to fields("steeringAngleDeg:f accel:f curvature:f"),
        "vehicle" to fields("present:b latitude:d longitude:d headingDeg:f speedKph:f roadName:t"),
        "guidance" to fields("present:b distanceM:i timeSec:i turnType:i roadName:t mainText:t pointValid:b latitude:d longitude:d"),
        "lane" to fields("present:b count:h distanceM:i visible:b available:hs"),
        "speed" to fields("""
            roadLimitValid:b roadLimitKph:h sdiPresent:b sdiType:i sdiDistanceM:i sdiSpeedLimitKph:h
            sdiSectionType:i sdiBlockType:i sdiBlockSpeedKph:h sdiBlockDistanceM:i
            secondarySdiPresent:b secondarySdiType:i secondarySdiDistanceM:i secondarySdiSpeedLimitKph:h
            secondarySdiSectionType:i secondarySdiBlockType:i secondarySdiBlockSpeedKph:h secondarySdiBlockDistanceM:i
            sectionPresent:b sectionActive:b sectionSpeedLimitKph:h sectionAverageKph:f
            sectionOverallAverageKph:f sectionRemainingDistanceM:f sectionRemainingTimeSec:i
            sectionProgress:f sectionSuspended:b sectionOffRoute:b
        """),
        "signal" to fields("""
            visible:b distanceM:i redValid:b redOn:b redRemainSec:h leftValid:b leftOn:b leftRemainSec:h
            greenValid:b greenOn:b greenRemainSec:h rightValid:b rightOn:b rightRemainSec:h
            uturnValid:b uturnOn:b uturnRemainSec:h uiCounterValid:b uiCounterRemainSec:h
        """),
        "crossroad" to fields("visible:b distanceM:i imageCode:i"),
        "route" to fields("present:b remainingDistanceM:i remainingTimeSec:i movedDistanceM:i totalDistanceM:i polyline:coords"),
        "status" to fields("guidanceActive:b offRoute:b routePresent:b"),
    )
    private fun schema(name: String, spec: String, required: Int? = null): Schema {
        val parsed = fields(spec)
        return Schema(name, parsed, required ?: parsed.size)
    }
    private val schemas = mapOf(
        1 to schema("carState", """
            vEgo:f aEgo:f vEgoCluster:f vCruiseCluster:f steeringAngleDeg:f brakeHoldActive:b
            softHoldActive:h carrotCruise:h gearStep:h useLaneLineSpeed:f brakeLights:b
            leftBlindspot:b rightBlindspot:b leftLaneLine:h rightLaneLine:h
            gearShifter:e_unknown,park,drive,neutral,reverse,sport,low,brake,eco,manumatic
            leftBlinker:b rightBlinker:b fuelGauge:f ureaGauge:f tpms:s_tpms evModeValid:b evModeActive:b
        """, 21),
        2 to schema("controlsState", "enabled:b vCruiseCluster:f activeLaneLine:b curvature:f desiredCurvature:f actualLateralAccel:f desiredLateralAccel:f lateralOutput:f"),
        3 to schema("deviceState", "memoryUsagePercent:a freeSpacePercent:f cpuTempC:fs deviceType:e_unknown,neo,chffrAndroid,chffrIos,tici,pc,tizi,mici started:b", 4),
        5 to schema("carrotMan", """
            activeCarrot:i nRoadLimitSpeed:i xSpdType:i xSpdLimit:i xSpdDist:i xSpdCountDown:i
            xTurnInfo:i xDistToTurn:i xTurnCountDown:i atcType:t szPosRoadName:t szTBTMainText:t
            desiredSpeed:i xPosLat:f xPosLon:f xPosAngle:f xPosSpeed:f trafficState:i nGoPosDist:i
            nGoPosTime:i szSdiDescr:t naviPaths:t desiredSource:t vehicleNaviActive:b vehicleNaviSpeed:i
            vehicleNaviSectionActive:b vehicleNaviAvailable:b
        """),
        6 to schema("selfdriveState", """
            enabled:b personality:e_aggressive,standard,relaxed,moreRelaxed
            alertStatus:e_normal,userPrompt,critical alertSize:e_none,small,mid,full
            alertType:t alertText1:t alertText2:t
        """),
        7 to schema("gpsLocationExternal", "latitude:d longitude:d speed:f bearingDeg:f bearingAccuracyDeg:f speedAccuracy:f hasFix:b altitude:d horizontalAccuracy:f verticalAccuracy:f unixTimestampMillis:d", 7),
        8 to schema("longitudinalPlan", "accels:first speeds:first jerks:first tFollow:f desiredDistance:f myDrivingMode:i xState:i trafficState:i longitudinalPlanSource:e_cruise,lead0,lead1,lead2,e2e cruiseTarget:f", 9),
        9 to schema("modelV2", "frameId:u frameIdExtra:u position:s_xyz velocity:s_velocity laneLines:l_xyz laneLineProbs:fs roadEdges:l_xyz roadEdgeStds:fs leadsV3:l_modelLead laneLineStds:fs frameAge:i frameDropPerc:f modelExecutionTime:f", 9),
        12 to schema("lateralPlan", "useLaneLines:b latDebugText:t position:s_xyz distances:fs laneChangeState:e_off,preLaneChange,laneChangeStarting,laneChangeFinishing laneChangeDirection:e_none,left,right"),
        13 to schema("radarState", "leadOne:s_radarLead leadTwo:s_radarLead leadRight:s_radarLead leadLeft:s_radarLead leadsLeft:l_radarLead leadsCenter:l_radarLead leadsRight:l_radarLead leadsLeft2:l_radarLead leadsRight2:l_radarLead leadsCutIn:l_radarLead", 7),
        14 to schema("carControl", "latActive:b longActive:b actuators:s_actuators"),
        21 to schema("carrotNavi", "schemaVersion:H generation:q sessionId:t publishMonoTimeNanos:q connected:b vehicle:s_vehicle guidanceCurrent:s_guidance guidanceNext:s_guidance laneCurrent:s_lane laneAhead:l_lane speed:s_speed trafficSignal:s_signal crossroad:s_crossroad route:s_route navigationStatus:s_status"),
    )
    val supportedServices: List<String> = schemas.values.map { it.name }

    /** A whole batch is validated before any frame can affect state. */
    fun decode(bytes: ByteArray): List<CompactFrame> {
        checkWire(bytes.size in 4..MAX_MESSAGE_BYTES, "invalid message length ${bytes.size}")
        val reader = Reader(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))
        return when (reader.magic()) {
            "CVS1" -> listOf(frame(reader))
            "CVB1" -> {
                val count = reader.u16()
                checkWire(count <= reader.remaining / 12, "invalid batch count $count")
                val frames = List(count) {
                    val length = reader.u32()
                    checkWire(length in 8..reader.remaining.toLong(), "invalid embedded frame length $length")
                    val inner = reader.slice(length.toInt())
                    checkWire(inner.magic() == "CVS1", "batch item is not CVS1")
                    frame(inner)
                }
                checkWire(reader.remaining == 0, "trailing batch bytes")
                frames
            }
            else -> throw CompactDecodeException("unsupported compact magic")
        }
    }

    private fun frame(reader: Reader): CompactFrame {
        val id = reader.u8()
        checkWire(reader.u8() == 0, "unsupported frame flags")
        val sequence = reader.u16()
        val schema = schemas[id] ?: throw CompactDecodeException("unsupported service id $id")
        val values = linkedMapOf<String, Any?>()
        schema.fields.forEachIndexed { index, (name, type) ->
            // Only documented appended top-level fields may be absent in older recordings.
            if (reader.remaining == 0 && index >= schema.required) return@forEachIndexed
            values[name] = reader.value(type)
        }
        return CompactFrame(schema.name, sequence, values, reader.remaining)
    }

    private class Reader(private val buffer: ByteBuffer) {
        val remaining get() = buffer.remaining()
        private fun need(size: Int) = checkWire(size >= 0 && remaining >= size, "truncated compact field: need $size, have $remaining")
        fun u8(): Int { need(1); return buffer.get().toInt() and 255 }
        fun u16(): Int { need(2); return buffer.short.toInt() and 65535 }
        fun u32(): Long { need(4); return buffer.int.toLong() and 0xffffffffL }
        fun magic(): String { need(4); return String(ByteArray(4).also { buffer.get(it) }, Charsets.US_ASCII) }
        fun slice(length: Int): Reader {
            need(length)
            val slice = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
            slice.limit(length)
            buffer.position(buffer.position() + length)
            return Reader(slice)
        }
        private fun float(): Float { need(4); return buffer.float.also { checkWire(it.isFinite(), "nonfinite float") } }
        private fun double(): Double { need(8); return buffer.double.also { checkWire(it.isFinite(), "nonfinite double") } }
        private fun short(): Int { need(2); return buffer.short.toInt() }
        private fun struct(name: String): Map<String, Any?> = structs.getValue(name).associate { (key, type) -> key to value(type) }
        private fun list(width: Int, maxCount: Int = 65535, read: () -> Any): List<Any> {
            val count = u16()
            checkWire(count <= maxCount && count <= remaining / width, "invalid list length $count")
            return List(count) { read() }
        }
        fun value(type: String): Any = when {
            type == "b" -> u8().also { checkWire(it <= 1, "invalid boolean") } == 1
            type == "a" -> { need(1); buffer.get().toInt() }
            type == "h" -> short()
            type == "H" -> u16()
            type == "i" -> { need(4); buffer.int }
            type == "u" -> u32()
            type == "q" -> { need(8); buffer.long.toULong() }
            type == "f" -> float()
            type == "d" -> double()
            type == "t" -> {
                val length = u16()
                need(length)
                val text = buffer.slice()
                text.limit(length)
                val decoded = try {
                    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(text).toString()
                } catch (error: java.nio.charset.CharacterCodingException) {
                    throw CompactDecodeException("invalid UTF-8", error)
                }
                buffer.position(buffer.position() + length)
                decoded
            }
            type == "fs" -> list(4) { float() }
            type == "first" -> list(4, 1) { float() }
            type == "hs" -> list(2) { short() }
            type == "ucm" -> list(2) { u16() / 100f }
            type == "cm" -> list(2) { short() / 100f }
            type == "mm" -> list(2) { short() / 1000f }
            type == "coords" -> {
                val count = u8()
                checkWire(count <= 64, "route exceeds polyline limit")
                if (count == 0) emptyList() else {
                    need(16 + (count - 1) * 8)
                    val latitude = double()
                    val longitude = double()
                    List(count) { index ->
                        val lat = if (index == 0) latitude else latitude + float()
                        val lon = if (index == 0) longitude else longitude + float()
                        checkWire(lat in -90.0..90.0 && lon in -180.0..180.0, "invalid route coordinate")
                        mapOf("latitude" to lat, "longitude" to lon)
                    }
                }
            }
            type.startsWith("e_") -> {
                val names = type.removePrefix("e_").split(',')
                names.getOrNull(u8()) ?: throw CompactDecodeException("unknown enum value")
            }
            type.startsWith("s_") -> struct(type.removePrefix("s_"))
            type.startsWith("l_") -> {
                val count = u8()
                // Every struct has at least one byte. Bounds are checked again for each field.
                checkWire(count <= remaining, "invalid struct list length")
                List(count) { struct(type.removePrefix("l_")) }
            }
            else -> error("unknown schema type $type")
        }
    }
    private fun checkWire(condition: Boolean, message: String) {
        if (!condition) throw CompactDecodeException(message)
    }
}

class CompactDecodeException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

data class CompactFrame(
    val service: String,
    val sequence: Int,
    val values: Map<String, Any?>,
    val unknownTrailingBytes: Int = 0,
)
