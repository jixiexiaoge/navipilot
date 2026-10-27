package com.jixiexiaoge.drivingassist.data

internal enum class TelemetryAvailability { Missing, Expired, Fresh }
internal enum class NavigationReception { Unknown, Unsupported, Disconnected, Idle, Guiding, OffRoute }

internal data class ReceivedJourney(val distanceM: Int?, val timeSec: Int?, val progress: Float? = null)
internal data class ReceivedGuidance(val text: String?, val roadName: String?, val distanceM: Int?)
internal data class ReceivedSpeedEvent(val type: Int, val limitKph: Int?, val distanceM: Int, val description: String?)
internal data class ReceivedSection(
    val limitKph: Int?,
    val averageKph: Float?,
    val remainingM: Float?,
    val progress: Float?,
)
internal data class ReceivedAlert(val status: String?, val first: String?, val second: String?)
internal data class ReceivedLead(val distanceM: Float, val relativeKph: Float)
internal data class ReceivedFollowing(val timeSec: Float?, val distanceM: Float?, val source: String?)
internal data class ReceivedLaneChange(val state: String?, val direction: String?)
internal data class ReceivedNavigationLanes(val count: Int, val recommendedIndices: List<Int>?)
internal data class ReceivedVehicleMotion(
    val gear: String?, val accelerationMps2: Float?, val brakeLights: Boolean?, val brakeHold: Boolean?,
)
internal data class ReceivedPlanning(val speedKph: Float?, val accelerationMps2: Float?)
internal data class ReceivedActuation(val steeringAngleDeg: Float?, val accelerationMps2: Float?)
internal data class ReceivedLamp(val direction: String, val on: Boolean, val remainingSec: Int?)
internal data class ReceivedNavigationSignal(val distanceM: Int?, val lamps: List<ReceivedLamp>)
internal data class ReceivedBehavior(val stage: Int?, val traffic: Int?, val style: Int?, val cruiseTargetKph: Float?, val jerk: Float?)
internal data class ReceivedLateral(val curvature: Float?, val desiredCurvature: Float?, val acceleration: Float?, val desiredAcceleration: Float?, val output: Float?)
internal data class ReceivedModelQuality(val lineProbabilities: List<Float?>, val edgeStds: List<Float?>,
    val age: Int?, val dropPercent: Float?, val executionMs: Float? = null)
internal data class ReceivedVehicleNavigation(
    val available: Boolean?, val active: Boolean?, val speedKph: Int?, val sectionActive: Boolean?,
)

/** Read-only projection of fresh compact data. Never a navigation or automation input. */
internal data class CarrotDisplaySnapshot(
    val resultAvailability: TelemetryAvailability,
    val resultAgeMs: Long?,
    val navigationAvailability: TelemetryAvailability,
    val navigation: NavigationReception,
    val desiredSpeedKph: Int?,
    val desiredSource: String?,
    val adoptedLimitKph: Int?,
    val events: List<ReceivedSpeedEvent>,
    val section: ReceivedSection?,
    val journey: ReceivedJourney?,
    val currentGuidance: ReceivedGuidance?,
    val nextGuidance: ReceivedGuidance?,
    val latActive: Boolean?,
    val longActive: Boolean?,
    val alertAvailability: TelemetryAvailability,
    val alert: ReceivedAlert?,
    val radarAvailability: TelemetryAvailability,
    val lead: ReceivedLead?,
    val followingAvailability: TelemetryAvailability,
    val following: ReceivedFollowing?,
    val laneAvailability: TelemetryAvailability,
    val laneChange: ReceivedLaneChange?,
    val navigationAgeMs: Long?,
    val navigationLanes: ReceivedNavigationLanes?,
    val leftLead: ReceivedLead? = null,
    val rightLead: ReceivedLead? = null,
    val vehicleAvailability: TelemetryAvailability = TelemetryAvailability.Missing,
    val vehicleMotion: ReceivedVehicleMotion? = null,
    val planningAvailability: TelemetryAvailability = TelemetryAvailability.Missing,
    val planning: ReceivedPlanning? = null,
    val actuationAvailability: TelemetryAvailability = TelemetryAvailability.Missing,
    val actuation: ReceivedActuation? = null,
    val laneLinesUsed: Boolean? = null,
    val leftBlindspot: Boolean? = null,
    val rightBlindspot: Boolean? = null,
    val leadPresent: Boolean? = null,
    val secondLead: ReceivedLead? = null,
    val cutInCount: Int? = null,
    val behavior: ReceivedBehavior? = null,
    val lateral: ReceivedLateral? = null,
    val quality: ReceivedModelQuality? = null,
    val navigationSignal: ReceivedNavigationSignal? = null,
    val radarCandidateCounts: List<Int?> = List(3) { null },
    val modelAvailability: TelemetryAvailability = TelemetryAvailability.Missing,
    val vehicleNavigation: ReceivedVehicleNavigation? = null,
    val lateralAvailability: TelemetryAvailability = TelemetryAvailability.Missing,
)

internal fun CompactTelemetry?.toDisplaySnapshot(): CarrotDisplaySnapshot {
    fun availability(service: String): TelemetryAvailability = when {
        this?.services?.containsKey(service) != true -> TelemetryAvailability.Missing
        fresh(service) == null -> TelemetryAvailability.Expired
        else -> TelemetryAvailability.Fresh
    }
    fun values(service: String) = this?.fresh(service)?.frame?.values
    val result = this?.carrotMan
    val navi = this?.carrotNavi
    val status = navi?.navigationStatus
    val navigation = when {
        navi == null -> NavigationReception.Unknown
        navi.schemaVersion != 1 -> NavigationReception.Unsupported
        navi.connected == false -> NavigationReception.Disconnected
        navi.connected != true -> NavigationReception.Unknown
        status?.get("offRoute") == true -> NavigationReception.OffRoute
        status?.get("offRoute") != false -> NavigationReception.Unknown
        status?.get("guidanceActive") == true -> NavigationReception.Guiding
        status?.get("guidanceActive") == false -> NavigationReception.Idle
        else -> NavigationReception.Unknown
    }
    val guiding = navigation == NavigationReception.Guiding
    val linked = navi?.connected == true && navi.schemaVersion == 1
    val speed = navi?.speed?.takeIf { linked && navigation != NavigationReception.OffRoute }
    val events = buildList {
        fun append(present: String, type: String, limit: String, distance: String) {
            val value = speed ?: return
            if (value[present] != true) return
            val eventType = value.intValue(type)?.takeIf { it >= 0 } ?: return
            val meters = value.intValue(distance)?.takeIf { it > 0 } ?: return
            add(ReceivedSpeedEvent(eventType, value.validSpeed(limit), meters, null))
        }
        append("sdiPresent", "sdiType", "sdiSpeedLimitKph", "sdiDistanceM")
        append("secondarySdiPresent", "secondarySdiType", "secondarySdiSpeedLimitKph", "secondarySdiDistanceM")
        // A normalized, linked session is authoritative, including an absent event.
        if (!linked && result != null) {
            val type = result.speedType?.takeIf { it >= 0 }
            val distance = result.speedDistanceM?.takeIf { it > 0 }
            if (type != null && distance != null) {
                add(ReceivedSpeedEvent(type, result.fields.validSpeed("xSpdLimit"), distance,
                    result.fields.text("szSdiDescr")))
            }
        }
    }
    val section = speed?.takeIf {
        it["sectionPresent"] == true && it["sectionActive"] == true &&
            it["sectionSuspended"] == false && it["sectionOffRoute"] == false
    }?.let {
        ReceivedSection(
            it.validSpeed("sectionSpeedLimitKph"),
            it.positiveFloat("sectionAverageKph")?.takeIf { average -> average <= 300f },
            it.positiveFloat("sectionRemainingDistanceM"),
            // Compact drops optional-field validity; zero can be an upstream missing value.
            it.positiveFloat("sectionProgress")?.takeIf { progress -> progress <= 1f },
        )
    }
    val route = navi?.route?.takeIf { guiding && status?.get("routePresent") == true && it["present"] == true }
    val journey = route?.let {
        val remaining = it.intValue("remainingDistanceM")?.takeIf { distance -> distance > 0 }
        val total = it.intValue("totalDistanceM")?.takeIf { distance -> remaining != null && distance >= remaining }
        val progress = remaining?.let { distance -> total?.let { fullDistance -> 1f - distance.toFloat() / fullDistance } }
        ReceivedJourney(remaining,
            it.intValue("remainingTimeSec")?.takeIf { seconds -> seconds > 0 },
            progress,
        )
    }?.takeIf { it.distanceM != null || it.timeSec != null }
    fun guidance(value: Map<String, Any?>?) = value?.takeIf { guiding && it["present"] == true }?.let {
        ReceivedGuidance(it.text("mainText"), it.text("roadName"),
            it.intValue("distanceM")?.takeIf { distance -> distance >= 0 })
    }
    val control = values("carControl")
    val alert = values("selfdriveState")?.let {
        val first = it.text("alertText1")
        val second = it.text("alertText2")
        val severity = it.text("alertStatus")
        if (first == null && second == null && severity !in setOf("critical", "userPrompt")) null
        else ReceivedAlert(severity, first, second)
    }
    fun selectedLead(name: String) = this?.radarLead(name)?.takeIf {
        it.distanceM.isFinite() && it.distanceM > 0 && it.relativeSpeedMps.isFinite()
    }?.let {
        val relativeKph = (it.relativeSpeedMps * 3.6f).takeIf { value -> value.isFinite() } ?: return@let null
        ReceivedLead(it.distanceM, relativeKph)
    }
    val lead = selectedLead("leadOne")
    val motion = values("carState")?.let {
        ReceivedVehicleMotion(
            (it["gearShifter"] as? String)?.takeIf { gear -> gear in setOf(
                "park", "drive", "neutral", "reverse", "sport", "low", "brake", "eco", "manumatic",
            ) },
            it.finiteFloat("aEgo"), it["brakeLights"] as? Boolean, it["brakeHoldActive"] as? Boolean,
        )
    }
    val planning = values("longitudinalPlan")?.let {
        ReceivedPlanning(it.firstFiniteFloat("speeds")?.takeIf { speed -> speed >= 0 }?.times(3.6f)
            ?.takeIf { speed -> speed.isFinite() },
            it.firstFiniteFloat("accels"))
    }
    val actuation = control?.objectValue("actuators")?.let {
        ReceivedActuation(it.finiteFloat("steeringAngleDeg"), it.finiteFloat("accel"))
    }
    val following = values("longitudinalPlan")?.let {
        ReceivedFollowing(it.positiveFloat("tFollow"), it.positiveFloat("desiredDistance"),
            it.text("longitudinalPlanSource"))
    }
    val lane = values("lateralPlan")?.let {
        ReceivedLaneChange(it.text("laneChangeState"), it.text("laneChangeDirection"))
    }
    val navigationLanes = navi?.laneCurrent?.takeIf {
        guiding && it["present"] == true && it["visible"] == true
    }?.let {
        val count = it.intValue("count")?.takeIf { value -> value in 1..16 } ?: return@let null
        val rawFlags = (it["available"] as? List<*>)?.takeIf { flags -> flags.size == count }
        val flags = rawFlags?.mapNotNull { value -> value as? Int }
            ?.takeIf { flags -> flags.size == count && flags.all { flag -> flag in 0..1 } }
        ReceivedNavigationLanes(count, flags?.mapIndexedNotNull { index, flag -> index.takeIf { flag == 1 } })
    }
    val desired = result?.desiredSpeedKph?.takeIf { it in 1..249 }
    val behavior = values("longitudinalPlan")?.let {
        ReceivedBehavior(it.intValue("xState")?.takeIf { code -> code in 0..5 },
            it.intValue("trafficState")?.takeIf { code -> code in 0..2 },
            it.intValue("myDrivingMode")?.takeIf { code -> code in 1..4 },
            it.finiteFloat("cruiseTarget")?.takeIf { speed -> speed in 0f..249f }, it.firstFiniteFloat("jerks"))
    }
    val lateralDetails = values("controlsState")?.let {
        ReceivedLateral(it.finiteFloat("curvature"), it.finiteFloat("desiredCurvature"),
            it.finiteFloat("actualLateralAccel"), it.finiteFloat("desiredLateralAccel"), it.finiteFloat("lateralOutput"))
    }
    val quality = values("modelV2")?.let { model ->
        fun list(key: String, count: Int, range: ClosedFloatingPointRange<Float>): List<Float?> =
            (model[key] as? List<*>)?.takeIf { it.size == count }?.map {
                (it as? Number)?.toFloat()?.takeIf { value -> value.isFinite() && value in range }
            } ?: List(count) { null }
        ReceivedModelQuality(list("laneLineProbs", 4, 0f..1f), list("roadEdgeStds", 2, 0f..100f),
            model.intValue("frameAge")?.takeIf { it >= 0 }, model.finiteFloat("frameDropPerc")?.takeIf { it in 0f..100f },
            model.finiteFloat("modelExecutionTime")?.takeIf { it >= 0f }?.times(1000f)?.takeIf(Float::isFinite))
    }
    val signal = navi?.trafficSignal?.takeIf { linked && guiding && it["visible"] == true }?.let { value ->
        ReceivedNavigationSignal(value.intValue("distanceM")?.takeIf { it > 0 },
            listOf("red", "left", "green", "right", "uturn").mapNotNull { name ->
                val on = value["${name}On"] as? Boolean
                if (value["${name}Valid"] != true || on == null) null
                else ReceivedLamp(name, on, value.intValue("${name}RemainSec")?.takeIf { it >= 0 })
            })
    }
    fun candidateCount(name: String): Int? =
        (values("radarState")?.get(name) as? List<*>)?.count { raw ->
            val target = raw as? Map<*, *>
            val d = (target?.get("dRel") as? Number)?.toFloat()
            val v = (target?.get("vRel") as? Number)?.toFloat()
            target?.get("status") == true && d != null && d.isFinite() && d > 0 && v != null && v.isFinite()
        }
    val currentGuidance = if (linked) guidance(navi?.guidanceCurrent) else result?.takeIf {
        it.turnInfo?.let { type -> type > 0 } == true && it.turnDistanceM?.let { meters -> meters > 0 } == true
    }?.let { ReceivedGuidance(it.mainText?.takeIf(String::isNotBlank), null, it.turnDistanceM) }
    return CarrotDisplaySnapshot(
        availability("carrotMan"),
        this?.fresh("carrotMan")?.let { (observedAtMs - it.receivedAtMs).coerceAtLeast(0) },
        availability("carrotNavi"), navigation,
        desired, result?.desiredSource?.takeIf { desired != null },
        result?.fields?.validSpeed("nRoadLimitSpeed"), events, section, journey,
        currentGuidance, guidance(navi?.guidanceNext),
        control?.get("latActive") as? Boolean, control?.get("longActive") as? Boolean,
        availability("selfdriveState"), alert,
        availability("radarState"), lead,
        availability("longitudinalPlan"), following,
        availability("lateralPlan"), lane,
        navi?.sample?.let { (observedAtMs - it.receivedAtMs).coerceAtLeast(0) },
        navigationLanes,
        selectedLead("leadLeft"), selectedLead("leadRight"),
        availability("carState"), motion,
        availability("longitudinalPlan"), planning,
        availability("carControl"), actuation,
        values("lateralPlan")?.get("useLaneLines") as? Boolean,
        values("carState")?.get("leftBlindspot") as? Boolean,
        values("carState")?.get("rightBlindspot") as? Boolean,
        values("radarState")?.objectValue("leadOne")?.get("status") as? Boolean,
        selectedLead("leadTwo"),
        candidateCount("leadsCutIn"),
        behavior, lateralDetails, quality, signal,
        listOf("leadsLeft", "leadsCenter", "leadsRight").map(::candidateCount),
        availability("modelV2"),
        result?.fields?.let {
            val available = it["vehicleNaviAvailable"] as? Boolean
            val active = it["vehicleNaviActive"] as? Boolean
            ReceivedVehicleNavigation(available, active,
                it.validSpeed("vehicleNaviSpeed")?.takeIf { available == true && active == true },
                (it["vehicleNaviSectionActive"] as? Boolean)?.takeIf { available == true })
        },
        availability("controlsState"),
    )
}

private fun Map<String, Any?>.validSpeed(key: String) = intValue(key)?.takeIf { it in 1..249 }
private fun Map<String, Any?>.positiveFloat(key: String) =
    floatValue(key)?.takeIf { it.isFinite() && it > 0 }
private fun Map<String, Any?>.finiteFloat(key: String) = floatValue(key)?.takeIf { it.isFinite() }
private fun Map<String, Any?>.firstFiniteFloat(key: String) =
    ((get(key) as? List<*>)?.firstOrNull() as? Number)?.toFloat()?.takeIf { it.isFinite() }
private fun Map<String, Any?>.text(key: String) = (get(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }
