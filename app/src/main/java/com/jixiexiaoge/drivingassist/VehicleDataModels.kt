package com.jixiexiaoge.drivingassist

/**
 * Compatibility display/decision models populated only by the 7000 compact adapter.
 * Legacy Xiaoge type names do not imply TCP input or complete control authority.
 */

data class XiaogeVehicleData(
    val sequence: Long,
    val timestamp: Double,
    val ip: String?,
    val receiveTime: Long = 0L,
    val carState: CarStateData?,
    val modelV2: ModelV2Data?,
    val systemState: SystemStateData?,
    val controlsState: XiaogeControlsStateData? = null,
    val noaLaneLocalization: NoaLaneLocalizationData? = null,
    val noaBehaviorPlan: NoaBehaviorPlanData? = null,
    val overtakeStatus: OvertakeStatusData? = null,
    val tbtDist: Int = 0
)

data class OvertakeStatusData(
    val statusText: String,
    val canOvertake: Boolean,
    val lastDirection: String? = null,
    val blockingReason: String? = null,
    val cooldownRemaining: Long? = null,
    val currentLane: Int = 0,
    val totalLanes: Int = 0,
    val laneReminder: String? = null
)

data class CarStateData(
    val vEgo: Float,
    val steeringAngleDeg: Float,
    val leftLatDist: Float,
    val leftBlindspot: Boolean,
    val rightBlindspot: Boolean,
    val leftBlinker: Boolean = false,
    val rightBlinker: Boolean = false,
    val steeringPressed: Boolean = false,
    val standstill: Boolean = false,
    val steerFaultTemporary: Boolean = false,
    val steerFaultPermanent: Boolean = false,
    val brakePressed: Boolean = false,
    val gasPressed: Boolean = false,
)

data class ModelV2Data(
    val lead0: LeadData?,
    val leadLeft: SideLeadDataExtended?,
    val leadRight: SideLeadDataExtended?,
    val laneLineProbs: List<Float>,
    val meta: MetaData?,
    val curvature: CurvatureData?
)

data class LeadData(
    val x: Float,
    val y: Float,
    val v: Float,
    val prob: Float
)

data class MetaData(
    val distanceToRoadEdgeLeft: Float = 0.0f,
    val distanceToRoadEdgeRight: Float = 0.0f
)

data class CurvatureData(
    val maxOrientationRate: Float
)

data class SideLeadDataExtended(
    val dRel: Float,
    val vRel: Float,
    val status: Boolean
)

data class SystemStateData(
    val enabled: Boolean,
    val active: Boolean,
    val engageable: Boolean = false,
)

data class XiaogeControlsStateData(
    val enabled: Boolean = false,
    val active: Boolean = false,
    val vCruise: Float = 0f,
    val curvature: Float = 0f,
    val state: String = "",
    val experimentalMode: Boolean = false,
)

/** CP shadow-only lane localization; never grants control authority. */
data class NoaLaneLocalizationData(
    val valid: Boolean = false,
    val estimatedLane: Int = -1,
    val laneProbabilities: List<Float> = emptyList(),
    val confidence: Float = 0f,
    val laneCount: Int = 0,
    val reason: String = "",
)

/** CP shadow-only NOA behavior decision; it never requests vehicle control. */
data class NoaBehaviorPlanData(
    val state: String = "OFF",
    val requestActive: Boolean = false,
    val direction: String = "none",
    val requiredDistanceM: Float = 0f,
    val remainingLaneChanges: Int = 0,
    val confidence: Float = 0f,
    val rejectReason: String = "",
)
