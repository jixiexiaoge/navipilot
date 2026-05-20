package com.example.navipilot.scoring

import com.example.navipilot.ui.utils.localized
import kotlin.math.abs

/**
 * 驾驶评分引擎
 * 五维评分 + 驾驶风格标签 + 改进建议 + 成就系统
 */
class DrivingScoreEngine {

    // ==================== 总分计算 ====================

    fun calculateTotalScore(s: Int, p: Int, i: Int, e: Int, st: Int): Int {
        val w = s * 0.30 + p * 0.25 + i * 0.20 + e * 0.15 + st * 0.10
        return w.toInt().coerceIn(0, 100)
    }

    // ==================== 五维评分 ====================

    /**
     * 1️⃣ 平稳指数 — 急加速/急刹车/急转弯越少越高
     * 阈值：急加速 > 2.5m/s², 急刹车 > 3.5m/s²
     */
    fun calculateSmoothnessScore(
        harshAccel: Int, harshBrake: Int, sharpTurn: Int, distKm: Float
    ): Int {
        if (distKm < 0.1f) return 100
        val eventsPerKm = (harshAccel + harshBrake + sharpTurn) / distKm
        // 0次/km=100, 每0.3次/km扣10分
        return (100 - eventsPerKm * 33).toInt().coerceIn(0, 100)
    }

    /**
     * 2️⃣ 预判指数 — 提前减速进弯/限速区的能力
     * 综合：速度波动 + 预判减速次数
     */
    fun calculatePredictionScore(
        avgSpeed: Float, maxSpeed: Float,
        smoothBrakeBeforeCurve: Int, smoothDecelBeforeLimit: Int,
        distKm: Float
    ): Int {
        if (maxSpeed < 1f || distKm < 0.1f) return 100
        // 速度波动分（占60%）
        val variation = (maxSpeed - avgSpeed) / maxSpeed
        val variationScore = (100 - variation * 80).toInt().coerceIn(0, 100)
        // 预判行为分（占40%）：每公里预判次数越多越好
        val predictionEvents = (smoothBrakeBeforeCurve + smoothDecelBeforeLimit) / distKm
        val predictionBonus = (predictionEvents * 20).toInt().coerceIn(0, 40)
        return (variationScore * 0.6 + (60 + predictionBonus) * 0.4).toInt().coerceIn(0, 100)
    }

    // 向后兼容
    fun calculatePredictionScore(avgSpeed: Float, maxSpeed: Float): Int {
        return calculatePredictionScore(avgSpeed, maxSpeed, 0, 0, 1f)
    }

    /**
     * 3️⃣ 接管依赖指数 — 每100km接管次数，区分主动/被动
     */
    fun calculateInterventionScore(
        interventionCount: Int, distKm: Float,
        details: List<InterventionDetail> = emptyList()
    ): Int {
        if (distKm < 0.1f) return 100
        val per100km = interventionCount / (distKm / 100f)
        // 基础分：0次=100, 每次扣8分
        var score = (100 - per100km * 8).toInt()
        // 被动接管额外扣分（风险行为触发的）
        val passiveCount = details.count { !it.isActive }
        score -= passiveCount * 3
        return score.coerceIn(0, 100)
    }

    // 向后兼容
    fun calculateInterventionScore(interventionCount: Int, distKm: Float): Int {
        return calculateInterventionScore(interventionCount, distKm, emptyList())
    }

    /**
     * 4️⃣ 节能指数 — 巡航比例 + 速度经济性
     */
    fun calculateEcoScore(avgSpeed: Float, cruiseRatio: Float = 0f): Int {
        // 速度经济性（最佳60-90km/h）
        val optimalSpeed = 75f
        val deviation = abs(avgSpeed - optimalSpeed)
        val speedScore = (100 - deviation * 1.5).toInt().coerceIn(0, 100)
        // 巡航比例加分
        val cruiseBonus = (cruiseRatio * 30).toInt()
        return (speedScore * 0.7 + cruiseBonus).toInt().coerceIn(0, 100)
    }

    /**
     * 5️⃣ NOO稳定度 — NOO使用时长占比 + 速度波动
     */
    fun calculateStabilityScore(
        nooDistance: Float, totalDistance: Float,
        nooTimeSeconds: Int = 0, totalTimeSeconds: Int = 0
    ): Int {
        if (totalDistance < 0.1f) return 0
        val distRatio = nooDistance / totalDistance
        val timeRatio = if (totalTimeSeconds > 0) nooTimeSeconds.toFloat() / totalTimeSeconds else distRatio
        // 综合NOO占比
        val combinedRatio = (distRatio * 0.5 + timeRatio * 0.5)
        return (combinedRatio * 100).toInt().coerceIn(0, 100)
    }

    // ==================== 驾驶风格标签 ====================

    fun determineDrivingStyle(
        avgSpeed: Float, harshAccel: Int, harshBrake: Int, distKm: Float
    ): String {
        if (distKm < 0.1f) return "normal"
        val eventsPerKm = (harshAccel + harshBrake) / distKm
        return when {
            eventsPerKm > 2f -> "aggressive"
            avgSpeed < 35f -> "city"
            avgSpeed > 90f -> "highway"
            eventsPerKm < 0.3f && avgSpeed in 50f..100f -> "smooth"
            else -> "normal"
        }
    }

    fun getStyleLabel(style: String): String = when (style) {
        "smooth" -> localized("🧘 平稳型", "🧘 Smooth")
        "aggressive" -> localized("🔥 激进型", "🔥 Aggressive")
        "city" -> localized("🏙️ 城市型", "🏙️ City")
        "highway" -> localized("🛣️ 高速巡航型", "🛣️ Highway")
        else -> localized("🚗 均衡型", "🚗 Balanced")
    }

    // ==================== 改进建议（正向引导） ====================

    fun getImprovementTips(session: DrivingSession): List<String> {
        val tips = mutableListOf<String>()
        if (session.smoothnessScore < 70) {
            tips.add("💡 " + localized("建议提前1.5秒松油门进入弯道，平稳性会明显提升", "Release throttle 1.5s earlier before curves for smoother driving"))
        }
        if (session.predictionScore < 70) {
            tips.add("💡 " + localized("关注前方路况变化，提前减速可以让预判指数更高", "Watch ahead and decelerate early to improve prediction score"))
        }
        if (session.interventionScore < 70) {
            val topReason = getTopInterventionReason(session.interventionDetails)
            if (topReason.isNotEmpty()) {
                tips.add("💡 " + localized("接管主要发生在${topReason}场景，熟悉后会越来越少", "Most takeovers happen in $topReason scenarios, will decrease with familiarity"))
            } else {
                tips.add("💡 " + localized("接管次数偏多，随着使用会逐渐减少", "Takeover count is high, will decrease with usage"))
            }
        }
        if (session.ecoScore < 70) {
            tips.add("💡 " + localized("保持60-90km/h匀速巡航，节能效果最佳", "Maintain 60-90km/h cruise speed for best efficiency"))
        }
        if (session.stabilityScore < 50) {
            tips.add("💡 " + localized("多使用NOO功能，系统会越来越稳定", "Use NOO more often, the system will become more stable"))
        }
        if (tips.isEmpty()) {
            tips.add("🎉 " + localized("驾驶表现很棒，继续保持！", "Great driving performance, keep it up!"))
        }
        return tips
    }

    private fun getTopInterventionReason(details: List<InterventionDetail>): String {
        if (details.isEmpty()) return ""
        val reasons = details.groupBy { it.reason }.mapValues { it.value.size }
        val top = reasons.maxByOrNull { it.value } ?: return ""
        return when (top.key) {
            "curve" -> localized("弯道", "curve")
            "traffic" -> localized("拥堵跟车", "traffic")
            "no_nav" -> localized("无导航", "no-nav")
            "lane_change" -> localized("变道", "lane change")
            "speed_limit" -> localized("限速区", "speed limit")
            else -> ""
        }
    }

    // ==================== 接管原因分析 ====================

    fun analyzeInterventionReasons(details: List<InterventionDetail>): Map<String, Float> {
        if (details.isEmpty()) return emptyMap()
        val total = details.size.toFloat()
        return details.groupBy { it.reason }
            .mapValues { (it.value.size / total * 100) }
            .toList().sortedByDescending { it.second }.toMap()
    }

    fun getReasonLabel(reason: String): String = when (reason) {
        "curve" -> localized("弯道", "Curve")
        "traffic" -> localized("拥堵跟车", "Traffic")
        "no_nav" -> localized("无导航", "No Nav")
        "lane_change" -> localized("变道中", "Lane Change")
        "speed_limit" -> localized("限速区", "Speed Limit")
        "construction" -> localized("施工路段", "Construction")
        else -> localized("其他", "Other")
    }

    // ==================== 成就系统 ====================

    fun checkAchievements(allSessions: List<DrivingSession>): List<Achievement> {
        val totalDist = allSessions.sumOf { it.totalDistance.toDouble() }.toFloat()
        val totalNoo = allSessions.sumOf { it.nooDistance.toDouble() }.toFloat()
        val maxConsecutiveNoIntervention = getMaxConsecutiveNoIntervention(allSessions)
        val smoothSessions = allSessions.count { it.smoothnessScore >= 90 }

        return listOf(
            Achievement(
                "first_ride", localized("🚀 首次出发", "🚀 First Ride"), localized("完成第一次驾驶记录", "Complete your first driving session"),
                "🚀", allSessions.isNotEmpty(), if (allSessions.isNotEmpty()) 1f else 0f
            ),
            Achievement(
                "100km", localized("🛣️ 百公里达人", "🛣️ 100km Driver"), localized("累计行驶100公里", "Drive 100km total"),
                "🛣️", totalDist >= 100, totalDist / 100f
            ),
            Achievement(
                "1000km", localized("🏆 千里驾驶员", "🏆 1000km Pro"), localized("累计行驶1000公里", "Drive 1000km total"),
                "🏆", totalDist >= 1000, totalDist / 1000f
            ),
            Achievement(
                "no_intervention_50km", localized("🎯 稳如泰山", "🎯 Rock Steady"), localized("连续50km无接管", "50km without takeover"),
                "🎯", maxConsecutiveNoIntervention >= 50f,
                maxConsecutiveNoIntervention / 50f
            ),
            Achievement(
                "no_intervention_100km", localized("👑 NOO专家", "👑 NOO Expert"), localized("连续100km无接管", "100km without takeover"),
                "👑", maxConsecutiveNoIntervention >= 100f,
                maxConsecutiveNoIntervention / 100f
            ),
            Achievement(
                "smooth_10", localized("🧘 平稳大师", "🧘 Smooth Master"), localized("10次行程平稳评分≥90", "10 trips with smoothness ≥90"),
                "🧘", smoothSessions >= 10, smoothSessions / 10f
            ),
            Achievement(
                "noo_500km", localized("🤖 智驾先锋", "🤖 NOO Pioneer"), localized("NOO累计500公里", "500km NOO driving"),
                "🤖", totalNoo >= 500, totalNoo / 500f
            ),
            Achievement(
                "eco_master", localized("🌿 节能达人", "🌿 Eco Master"), localized("连续5次行程节能评分≥85", "5 consecutive trips with eco ≥85"),
                "🌿", getConsecutiveEcoCount(allSessions) >= 5,
                getConsecutiveEcoCount(allSessions) / 5f
            )
        )
    }

    private fun getMaxConsecutiveNoIntervention(sessions: List<DrivingSession>): Float {
        var maxDist = 0f; var currentDist = 0f
        for (s in sessions.sortedBy { it.startTime }) {
            if (s.interventionCount == 0) {
                currentDist += s.totalDistance
                if (currentDist > maxDist) maxDist = currentDist
            } else {
                currentDist = 0f
            }
        }
        return maxDist
    }

    private fun getConsecutiveEcoCount(sessions: List<DrivingSession>): Float {
        var max = 0; var current = 0
        for (s in sessions.sortedBy { it.startTime }) {
            if (s.ecoScore >= 85) { current++; if (current > max) max = current }
            else current = 0
        }
        return max.toFloat()
    }
}
