package com.example.navipilot

import com.example.navipilot.scoring.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 驾驶评分引擎单元测试
 */
class DrivingScoreEngineTest {

    private val engine = DrivingScoreEngine()

    // ==================== 总分计算 ====================

    @Test
    fun totalScore_allPerfect_returns100() {
        val score = engine.calculateTotalScore(100, 100, 100, 100, 100)
        assertEquals(100, score)
    }

    @Test
    fun totalScore_allZero_returns0() {
        val score = engine.calculateTotalScore(0, 0, 0, 0, 0)
        assertEquals(0, score)
    }

    @Test
    fun totalScore_weightedCorrectly() {
        // s=100*0.3=30, p=80*0.25=20, i=60*0.2=12, e=40*0.15=6, st=20*0.1=2 = 70
        val score = engine.calculateTotalScore(100, 80, 60, 40, 20)
        assertEquals(70, score)
    }

    // ==================== 平稳指数 ====================

    @Test
    fun smoothness_noEvents_returns100() {
        assertEquals(100, engine.calculateSmoothnessScore(0, 0, 0, 10f))
    }

    @Test
    fun smoothness_manyEvents_returnsLow() {
        val score = engine.calculateSmoothnessScore(10, 10, 5, 5f)
        assertTrue("大量事件应得低分，实际=$score", score < 50)
    }

    @Test
    fun smoothness_shortDistance_returns100() {
        assertEquals(100, engine.calculateSmoothnessScore(5, 5, 5, 0.05f))
    }

    // ==================== 预判指数 ====================

    @Test
    fun prediction_stableSpeed_returnsHigh() {
        val score = engine.calculatePredictionScore(80f, 85f, 3, 2, 10f)
        assertTrue("稳定速度应得高分，实际=$score", score > 70)
    }

    @Test
    fun prediction_wildSpeedVariation_returnsLow() {
        val score = engine.calculatePredictionScore(40f, 120f, 0, 0, 10f)
        assertTrue("速度波动大应得低分，实际=$score", score < 60)
    }

    // ==================== 接管依赖指数 ====================

    @Test
    fun intervention_noIntervention_returns100() {
        assertEquals(100, engine.calculateInterventionScore(0, 50f))
    }

    @Test
    fun intervention_manyInterventions_returnsLow() {
        val score = engine.calculateInterventionScore(20, 10f)
        assertTrue("频繁接管应得低分，实际=$score", score < 50)
    }

    // ==================== 驾驶风格 ====================

    @Test
    fun drivingStyle_aggressive_detected() {
        val style = engine.determineDrivingStyle(80f, 15, 15, 5f)
        assertEquals("aggressive", style)
    }

    @Test
    fun drivingStyle_city_detected() {
        val style = engine.determineDrivingStyle(25f, 1, 1, 5f)
        assertEquals("city", style)
    }

    @Test
    fun drivingStyle_highway_detected() {
        val style = engine.determineDrivingStyle(100f, 0, 0, 50f)
        assertEquals("highway", style)
    }

    @Test
    fun drivingStyle_smooth_detected() {
        val style = engine.determineDrivingStyle(70f, 0, 0, 20f)
        assertEquals("smooth", style)
    }

    // ==================== 成就系统 ====================

    @Test
    fun achievements_noSessions_firstRideNotUnlocked() {
        val achievements = engine.checkAchievements(emptyList())
        val firstRide = achievements.find { it.id == "first_ride" }
        assertNotNull(firstRide)
        assertFalse(firstRide!!.isUnlocked)
    }

    @Test
    fun achievements_withSessions_firstRideUnlocked() {
        val sessions = listOf(
            DrivingSession(startTime = System.currentTimeMillis(), totalDistance = 10f)
        )
        val achievements = engine.checkAchievements(sessions)
        val firstRide = achievements.find { it.id == "first_ride" }
        assertTrue(firstRide!!.isUnlocked)
    }

    // ==================== 改进建议 ====================

    @Test
    fun improvementTips_perfectSession_returnsEncouragement() {
        val session = DrivingSession(
            startTime = System.currentTimeMillis(),
            smoothnessScore = 95,
            predictionScore = 92,
            interventionScore = 90,
            ecoScore = 88,
            stabilityScore = 85
        )
        val tips = engine.getImprovementTips(session)
        assertTrue("完美行程应返回鼓励", tips.any { it.contains("🎉") })
    }

    @Test
    fun improvementTips_lowSmoothness_returnsTip() {
        val session = DrivingSession(
            startTime = System.currentTimeMillis(),
            smoothnessScore = 50,
            predictionScore = 80,
            interventionScore = 80,
            ecoScore = 80,
            stabilityScore = 80
        )
        val tips = engine.getImprovementTips(session)
        assertTrue("低平稳分应有建议", tips.any { it.contains("平稳") || it.contains("弯道") })
    }
}
