package com.example.navipilot.scoring

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*

class DrivingDataCollector(private val context: Context) {

    companion object {
        private const val TAG = "DrivingDataCollector"
        private const val PREFS_NAME = "driving_sessions"
        private const val KEY_SESSIONS = "sessions"
        private const val MAX_SESSIONS = 50
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val scoreEngine = DrivingScoreEngine()

    private var currentSession: DrivingSession? = null
    @Volatile private var collecting = false
    private var saveJob: Job? = null
    private val saveScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 实时统计
    private var totalDistance = 0f
    private var nooDistance = 0f
    private var speedSum = 0f
    private var speedCount = 0
    private var maxSpeed = 0f
    private var harshAccelCount = 0
    private var harshBrakeCount = 0
    private var sharpTurnCount = 0
    private var interventionCount = 0
    private var smoothBrakeBeforeCurve = 0
    private var smoothDecelBeforeLimit = 0
    private var cruiseSeconds = 0
    private var nooSeconds = 0
    private var totalSeconds = 0

    private var lastNooActive = false
    private var lastSpeed = 0f
    private var lastRoadLimitSpeed = 0
    private var interventionDetails = mutableListOf<InterventionDetail>()

    // 预判检测状态
    private var approachingCurve = false
    private var approachingLimit = false
    private var wasDecelerating = false

    fun startCollecting() {
        if (collecting) return
        collecting = true
        Log.i(TAG, "开始驾驶数据采集")

        // 重置
        totalDistance = 0f; nooDistance = 0f; speedSum = 0f; speedCount = 0; maxSpeed = 0f
        harshAccelCount = 0; harshBrakeCount = 0; sharpTurnCount = 0; interventionCount = 0
        smoothBrakeBeforeCurve = 0; smoothDecelBeforeLimit = 0
        cruiseSeconds = 0; nooSeconds = 0; totalSeconds = 0
        lastNooActive = false; lastSpeed = 0f; lastRoadLimitSpeed = 0
        interventionDetails.clear()
        approachingCurve = false; approachingLimit = false; wasDecelerating = false

        currentSession = DrivingSession(startTime = System.currentTimeMillis())

        saveJob = saveScope.launch {
            while (isActive && collecting) {
                delay(30000)
                saveCurrentSessionTemp()
            }
        }
    }

    suspend fun stopCollecting() {
        if (!collecting) return
        collecting = false
        saveJob?.cancel()
        Log.i(TAG, "停止驾驶数据采集")

        val session = currentSession ?: return
        val avgSpeed = if (speedCount > 0) speedSum / speedCount else 0f

        val sm = scoreEngine.calculateSmoothnessScore(harshAccelCount, harshBrakeCount, sharpTurnCount, totalDistance)
        val pr = scoreEngine.calculatePredictionScore(avgSpeed, maxSpeed, smoothBrakeBeforeCurve, smoothDecelBeforeLimit, totalDistance)
        val iv = scoreEngine.calculateInterventionScore(interventionCount, totalDistance, interventionDetails.toList())
        val ec = scoreEngine.calculateEcoScore(avgSpeed, if (totalSeconds > 0) cruiseSeconds.toFloat() / totalSeconds else 0f)
        val st = scoreEngine.calculateStabilityScore(nooDistance, totalDistance, nooSeconds, totalSeconds)
        val total = scoreEngine.calculateTotalScore(sm, pr, iv, ec, st)
        val style = scoreEngine.determineDrivingStyle(avgSpeed, harshAccelCount, harshBrakeCount, totalDistance)

        val finalSession = session.copy(
            endTime = System.currentTimeMillis(),
            totalDistance = totalDistance,
            nooDistance = nooDistance,
            avgSpeed = avgSpeed,
            maxSpeed = maxSpeed,
            smoothnessScore = sm,
            predictionScore = pr,
            interventionScore = iv,
            ecoScore = ec,
            stabilityScore = st,
            totalScore = total,
            harshAccelCount = harshAccelCount,
            harshBrakeCount = harshBrakeCount,
            sharpTurnCount = sharpTurnCount,
            interventionCount = interventionCount,
            interventionDetails = interventionDetails.toList(),
            cruiseTimeSeconds = cruiseSeconds,
            nooTimeSeconds = nooSeconds,
            totalTimeSeconds = totalSeconds,
            cruiseRatio = if (totalSeconds > 0) cruiseSeconds.toFloat() / totalSeconds else 0f,
            nooRatio = if (totalSeconds > 0) nooSeconds.toFloat() / totalSeconds else 0f,
            smoothBrakeBeforeCurveCount = smoothBrakeBeforeCurve,
            smoothDecelBeforeLimitCount = smoothDecelBeforeLimit,
            drivingStyle = style
        )

        saveSession(finalSession)
        currentSession = null
        Log.i(TAG, "会话已保存: 总分=$total 里程=%.1fkm 接管=$interventionCount".format(totalDistance))
    }

    /**
     * 更新实时数据（每秒调用一次）
     */
    fun updateData(
        speed: Float,
        acceleration: Float,
        steeringAngle: Float,
        isHardBraking: Boolean,
        isHardAcceleration: Boolean,
        isSharpTurn: Boolean,
        nooActive: Boolean = false,
        distanceDelta: Float = 0f,
        cruiseActive: Boolean = false,
        roadLimitSpeed: Int = 0,
        tbtDist: Int = 0,
        roadType: Int = 8,
        roadName: String = "",
        leadDistance: Float = 0f
    ) {
        if (!collecting) return

        // 速度统计
        if (speed > 0) {
            speedSum += speed; speedCount++
            if (speed > maxSpeed) maxSpeed = speed
        }

        // 里程统计
        totalDistance += distanceDelta
        if (nooActive) nooDistance += distanceDelta

        // 时间统计（每次调用约1秒）
        totalSeconds++
        if (cruiseActive) cruiseSeconds++
        if (nooActive) nooSeconds++

        // 异常事件
        if (isHardAcceleration) harshAccelCount++
        if (isHardBraking) harshBrakeCount++
        if (isSharpTurn) sharpTurnCount++

        // 接管检测（NOO从激活变为未激活，且需驾驶员干预：加速度<-1.5m/s²）
        // 避免将ACC系统自然减速/Nav取消NOO误判为接管
        if (lastNooActive && !nooActive && speed > 5f && acceleration < -1.5f) {
            interventionCount++
            val reason = guessInterventionReason(speed, leadDistance, tbtDist, roadType)
            interventionDetails.add(InterventionDetail(
                timestamp = System.currentTimeMillis(),
                speed = speed,
                leadDistance = leadDistance,
                roadType = roadType,
                reason = reason,
                isActive = acceleration > -1f,
                roadName = roadName
            ))
            Log.d(TAG, "接管 #$interventionCount: reason=$reason speed=$speed")
        }

        // 预判检测：接近弯道时平滑减速
        if (tbtDist in 1..300 && !approachingCurve) {
            approachingCurve = true
            wasDecelerating = false
        }
        if (approachingCurve && acceleration < -0.5f && acceleration > -3f) {
            wasDecelerating = true
        }
        if (approachingCurve && tbtDist < 50) {
            if (wasDecelerating) smoothBrakeBeforeCurve++
            approachingCurve = false
        }

        // 预判检测：接近限速区时平滑减速
        if (roadLimitSpeed > 0 && speed > roadLimitSpeed + 5 && !approachingLimit) {
            approachingLimit = true
        }
        if (approachingLimit && speed <= roadLimitSpeed + 2) {
            if (acceleration > -3f) smoothDecelBeforeLimit++
            approachingLimit = false
        }

        lastNooActive = nooActive
        lastSpeed = speed
        lastRoadLimitSpeed = roadLimitSpeed
    }

    private fun guessInterventionReason(
        speed: Float, leadDist: Float, tbtDist: Int, roadType: Int
    ): String {
        return when {
            tbtDist in 1..200 -> "curve"
            leadDist in 0.1f..15f -> "traffic"
            speed < 20f -> "traffic"
            roadType == 0 && speed > 100f -> "speed_limit"
            else -> "unknown"
        }
    }

    private fun saveCurrentSessionTemp() {
        val s = currentSession ?: return
        try {
            prefs.edit().putString("current_session_temp", gson.toJson(s)).apply()
        } catch (e: Exception) {
            Log.e(TAG, "临时保存失败: ${e.message}")
        }
    }

    private fun saveSession(session: DrivingSession) {
        val list = getSessions().toMutableList()
        list.add(0, session)
        if (list.size > MAX_SESSIONS) list.subList(MAX_SESSIONS, list.size).clear()
        prefs.edit().putString(KEY_SESSIONS, gson.toJson(list)).apply()
        prefs.edit().remove("current_session_temp").apply()
    }

    fun getSessions(): List<DrivingSession> {
        val json = prefs.getString(KEY_SESSIONS, null) ?: return emptyList()
        val type = object : TypeToken<List<DrivingSession>>() {}.type
        return try {
            gson.fromJson<List<DrivingSession>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "解析失败: ${e.message}")
            emptyList()
        }
    }

    /**
     * 获取所有会话（包含正在采集中的当前会话快照）
     */
    fun getAllSessionsIncludingCurrent(): List<DrivingSession> {
        val saved = getSessions()
        val live = getCurrentSessionSnapshot() ?: return saved
        return listOf(live) + saved
    }

    /**
     * 获取当前正在采集的会话快照（实时计算评分）
     */
    fun getCurrentSessionSnapshot(): DrivingSession? {
        if (!collecting) return null
        val session = currentSession ?: return null
        val avgSpeed = if (speedCount > 0) speedSum / speedCount else 0f
        val sm = scoreEngine.calculateSmoothnessScore(harshAccelCount, harshBrakeCount, sharpTurnCount, totalDistance)
        val pr = scoreEngine.calculatePredictionScore(avgSpeed, maxSpeed, smoothBrakeBeforeCurve, smoothDecelBeforeLimit, totalDistance)
        val iv = scoreEngine.calculateInterventionScore(interventionCount, totalDistance, interventionDetails.toList())
        val ec = scoreEngine.calculateEcoScore(avgSpeed, if (totalSeconds > 0) cruiseSeconds.toFloat() / totalSeconds else 0f)
        val st = scoreEngine.calculateStabilityScore(nooDistance, totalDistance, nooSeconds, totalSeconds)
        val total = scoreEngine.calculateTotalScore(sm, pr, iv, ec, st)
        val style = scoreEngine.determineDrivingStyle(avgSpeed, harshAccelCount, harshBrakeCount, totalDistance)
        return session.copy(
            endTime = System.currentTimeMillis(),
            totalDistance = totalDistance,
            nooDistance = nooDistance,
            avgSpeed = avgSpeed,
            maxSpeed = maxSpeed,
            smoothnessScore = sm,
            predictionScore = pr,
            interventionScore = iv,
            ecoScore = ec,
            stabilityScore = st,
            totalScore = total,
            harshAccelCount = harshAccelCount,
            harshBrakeCount = harshBrakeCount,
            sharpTurnCount = sharpTurnCount,
            interventionCount = interventionCount,
            interventionDetails = interventionDetails.toList(),
            cruiseTimeSeconds = cruiseSeconds,
            nooTimeSeconds = nooSeconds,
            totalTimeSeconds = totalSeconds,
            cruiseRatio = if (totalSeconds > 0) cruiseSeconds.toFloat() / totalSeconds else 0f,
            nooRatio = if (totalSeconds > 0) nooSeconds.toFloat() / totalSeconds else 0f,
            smoothBrakeBeforeCurveCount = smoothBrakeBeforeCurve,
            smoothDecelBeforeLimitCount = smoothDecelBeforeLimit,
            drivingStyle = style
        )
    }

    fun getTodaySessions(): List<DrivingSession> {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        return getAllSessionsIncludingCurrent().filter { it.startTime >= start }
    }

    fun getWeekSessions(): List<DrivingSession> {
        val weekAgo = System.currentTimeMillis() - 7 * 24 * 3600 * 1000L
        return getAllSessionsIncludingCurrent().filter { it.startTime >= weekAgo }
    }

    fun isCollecting(): Boolean = collecting

    fun getCurrentSession(): DrivingSession? = currentSession

    /**
     * 检查是否有驾驶数据
     * 用于判断是否需要显示驾驶报告页面
     */
    fun hasData(): Boolean = totalDistance > 0.1f || totalSeconds > 10
}
