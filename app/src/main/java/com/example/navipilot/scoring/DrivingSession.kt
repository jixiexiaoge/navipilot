package com.example.navipilot.scoring

/**
 * 驾驶会话数据类
 * 记录每次行程的完整数据，用于评分和分析
 */
data class DrivingSession(
    val id: Long = System.currentTimeMillis(),
    val startTime: Long,
    val endTime: Long = 0,
    val totalDistance: Float = 0f,
    val nooDistance: Float = 0f,
    val avgSpeed: Float = 0f,
    val maxSpeed: Float = 0f,

    // 五维评分
    val smoothnessScore: Int = 0,
    val predictionScore: Int = 0,
    val interventionScore: Int = 0,
    val ecoScore: Int = 0,
    val stabilityScore: Int = 0,
    val totalScore: Int = 0,

    // 异常事件统计
    val harshAccelCount: Int = 0,
    val harshBrakeCount: Int = 0,
    val sharpTurnCount: Int = 0,
    val interventionCount: Int = 0,

    // 接管分析
    val interventionDetails: List<InterventionDetail> = emptyList(),

    // 巡航与NOO统计
    val cruiseTimeSeconds: Int = 0,
    val nooTimeSeconds: Int = 0,
    val totalTimeSeconds: Int = 0,
    val cruiseRatio: Float = 0f,
    val nooRatio: Float = 0f,

    // 预判统计
    val smoothBrakeBeforeCurveCount: Int = 0,
    val smoothDecelBeforeLimitCount: Int = 0,

    // 驾驶风格
    val drivingStyle: String = "normal"
)

/**
 * 接管详情记录
 */
data class InterventionDetail(
    val timestamp: Long = 0,
    val speed: Float = 0f,
    val leadDistance: Float = 0f,
    val roadType: Int = 8,
    val reason: String = "unknown",
    val isActive: Boolean = true,
    val roadName: String = ""
)

/**
 * 成就定义
 */
data class Achievement(
    val id: String,
    val name: String,
    val description: String,
    val icon: String,
    val isUnlocked: Boolean = false,
    val progress: Float = 0f,
    val target: Float = 1f
)
