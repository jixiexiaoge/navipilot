package com.example.navipilot

/**
 * 车辆数据模型（原 XiaogeDataReceiver 中定义）
 * 通过 WebSocket 从 carrot server 获取数据后填充
 */

data class XiaogeVehicleData(
    val sequence: Long,
    val timestamp: Double,
    val ip: String?,
    val receiveTime: Long = 0L,
    val carState: CarStateData?,
    val modelV2: ModelV2Data?,
    val systemState: SystemStateData?,
    val overtakeStatus: OvertakeStatusData? = null,
    val tbtDist: Int = 0
)

data class OvertakeStatusData(
    val statusText: String,
    val canOvertake: Boolean,
    val cooldownRemaining: Long?,
    val lastDirection: String?,
    val blockingReason: String? = null,
    val currentLane: Int = 0,
    val totalLanes: Int = 0,
    val laneReminder: String? = null
)

data class CarStateData(
    val vEgo: Float,
    val steeringAngleDeg: Float,
    val leftLatDist: Float,
    val leftBlindspot: Boolean,
    val rightBlindspot: Boolean
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
    val active: Boolean
)
