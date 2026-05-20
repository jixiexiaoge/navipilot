package com.example.navipilot

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.navipilot.XiaogeVehicleData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 条件实验模式管理器 (Conditional Experimental Mode Manager)
 * 根据配置的条件自动切换实验模式和Chill模式
 * 
 * 数据来源说明：
 * - 弯道/前车/低速条件：来自小鸽(XiaogeVehicleData)的ModelV2和CarState数据
 * - 导航条件：使用7705回传的tbtDist（转弯距离）替代原nTBTDist
 * - 测速点条件：使用7705回传的sdiDist（测速点距离）
 * - 驾驶状态条件：使用7705回传的xState（纵向控制状态）
 * 
 * xState 状态码说明：
 *   0 = lead    (跟车模式)
 *   1 = cruise  (巡航模式)
 *   2 = e2eCruise (端到端巡航)
 *   3 = e2eStop   (端到端停车中)
 *   4 = e2ePrepare (端到端准备起步)
 *   5 = e2eStopped (端到端已停车)
 * 
 * 弯道判断说明：
 *   曲率(maxOrientationRate)单位为 rad/s，表示车辆行驶方向的变化率
 *   - 0.00 ~ 0.02: 直道或极缓弯，无需干预
 *   - 0.02 ~ 0.05: 缓弯，高速时可能需要注意（半径约200~500m）
 *   - 0.05 ~ 0.10: 中等弯道（半径约100~200m），建议切换实验模式
 *   - 0.10 ~ 0.20: 急弯（半径约50~100m），强烈建议切换
 *   - > 0.20: 极急弯/掉头（半径<50m）
 *   默认阈值0.05对应约200m半径弯道，适合大多数场景
 */
class ConditionalExperimentManager(
    private val context: Context,
    private val getParamClient: () -> CarrotParamClient?
) {
    companion object {
        private const val TAG = "CEM"
        private const val COOLDOWN_MS = 5000L  // 5秒冷却时间
        private const val CHECK_INTERVAL_MS = 500L  // 500ms检查一次
    }

    private val prefs: SharedPreferences = context.getSharedPreferences("CarrotAmap", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    
    private var isExperimentalModeActive = false
    private var lastSwitchTime = 0L
    private var isMonitoring = false
    private var monitoringJob: Job? = null
    
    // 上次触发的条件名称（用于UI显示和日志）
    var lastTriggerReason: String = ""
        private set
    
    // 限速变化追踪（用于条件7）
    private var lastKnownSpeedLimit: Int = 0
    private var speedLimitChangedTime: Long = 0L

    /**
     * 启动监控
     */
    fun startMonitoring() {
        if (isMonitoring) return
        if (!prefs.getBoolean("cem_enabled", false)) {
            Log.d(TAG, "CEM主开关未启用")
            return
        }
        
        isMonitoring = true
        Log.i(TAG, "🚀 启动CEM监控")
        
        monitoringJob = scope.launch {
            while (isMonitoring) {
                try {
                    if (!prefs.getBoolean("cem_enabled", false)) {
                        if (isExperimentalModeActive) {
                            switchToChillMode("主开关已关闭")
                        }
                    }
                    delay(CHECK_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "监控异常: ${e.message}", e)
                    delay(CHECK_INTERVAL_MS)
                }
            }
        }
    }
    
    fun stopMonitoring() {
        isMonitoring = false
        monitoringJob?.cancel()
        Log.i(TAG, "⏹️ 停止CEM监控")
    }
    
    /**
     * 检查是否应该启用实验模式
     * 任一条件满足即返回true
     */
    fun checkConditions(
        xiaogeData: XiaogeVehicleData?,
        carrotManFields: CarrotManFields?
    ): Boolean {
        if (!prefs.getBoolean("cem_enabled", false)) return false
        
        // 前提：7705 active 必须为 true
        if (carrotManFields == null || !carrotManFields.active) return false
        
        val carState = xiaogeData?.carState
        val modelV2 = xiaogeData?.modelV2
        
        if (carState == null || modelV2 == null) return false
        
        // 条件1: 弯道检测
        if (checkCurveCondition(carState, modelV2)) {
            lastTriggerReason = "弯道检测"
            Log.d(TAG, "✅ 条件1: 弯道检测触发")
            return true
        }
        
        // 条件2: 前车检测
        if (checkLeadCondition(carState, modelV2)) {
            lastTriggerReason = "前车检测"
            Log.d(TAG, "✅ 条件2: 前车检测触发")
            return true
        }
        
        // 条件3: 低速条件
        if (checkSpeedCondition(carState, modelV2)) {
            lastTriggerReason = "低速条件"
            Log.d(TAG, "✅ 条件3: 低速条件触发")
            return true
        }
        
        // 条件4: 导航转弯条件（使用7705 tbtDist）
        if (checkNavigationCondition(carrotManFields, modelV2)) {
            lastTriggerReason = "导航转弯"
            Log.d(TAG, "✅ 条件4: 导航转弯触发")
            return true
        }
        
        // 条件5: 测速点条件（使用7705 sdiDist）
        if (checkSdiCondition(carrotManFields)) {
            lastTriggerReason = "测速点"
            Log.d(TAG, "✅ 条件5: 测速点触发")
            return true
        }
        
        // 条件6: 驾驶状态条件（使用7705 xState）
        if (checkXStateCondition(carrotManFields)) {
            lastTriggerReason = "驾驶状态"
            Log.d(TAG, "✅ 条件6: 驾驶状态触发")
            return true
        }
        
        // 条件7: 巡航速度调整（限速变化时临时切换）
        if (checkCruiseSpeedMismatch(carrotManFields)) {
            lastTriggerReason = "巡航调速"
            Log.d(TAG, "✅ 条件7: 巡航速度不匹配触发")
            return true
        }
        
        lastTriggerReason = ""
        return false
    }
    
    /**
     * 条件1: 弯道检测
     * 
     * 判断依据：ModelV2的curvature.maxOrientationRate（曲率变化率）
     * 曲率阈值说明：
     *   - 0.02 (激进): 缓弯即触发，适合谨慎驾驶者
     *   - 0.05 (默认): 中等弯道触发，约200m半径，平衡安全与舒适
     *   - 0.08 (保守): 只在较急弯道触发
     *   - 0.10 (极保守): 只在急弯触发，约100m半径
     * 
     * 正值=左转弯，负值=右转弯，取绝对值判断
     * 
     * 附加条件：
     *   - 车速必须低于设定值（高速弯道更需要实验模式的精确控制）
     *   - 可配置有前车时是否也触发
     */
    private fun checkCurveCondition(
        carState: CarStateData,
        modelV2: ModelV2Data
    ): Boolean {
        if (!prefs.getBoolean("ce_curves", false)) return false
        
        val curvature = modelV2.curvature ?: return false
        val maxOrientationRate = abs(curvature.maxOrientationRate)
        
        // 曲率阈值（用户可配置，默认0.05）
        val curvatureThreshold = prefs.getInt("ce_curves_curvature", 5) / 100f
        if (maxOrientationRate < curvatureThreshold) return false
        
        // 速度检查：低于设定速度时才触发（高速弯道更危险）
        val speedKmh = carState.vEgo * 3.6f
        val triggerSpeed = prefs.getInt("ce_curves_speed", 40)
        if (speedKmh > triggerSpeed) return false
        
        // 有前车时也触发？
        if (!prefs.getBoolean("ce_curves_lead", false)) {
            val hasLead = (modelV2.lead0?.prob ?: 0f) > 0.5f
            if (hasLead) return false
        }
        
        return true
    }
    
    /**
     * 条件2: 前车检测
     * 
     * 判断依据：ModelV2的lead0数据
     * - prob > 0.5 表示有前车（置信度阈值）
     * - v < 1.0 m/s 表示前车停止
     * - v 相对较慢表示前车减速
     * 
     * 前车距离阈值：可配置，默认80m内的前车才触发
     */
    private fun checkLeadCondition(
        carState: CarStateData,
        modelV2: ModelV2Data
    ): Boolean {
        if (!prefs.getBoolean("ce_lead", false)) return false
        
        val lead0 = modelV2.lead0 ?: return false
        if (lead0.prob < 0.5f) return false
        
        // 前车距离检查
        val maxLeadDist = prefs.getInt("ce_lead_dist", 80)
        if (lead0.x > maxLeadDist) return false
        
        val checkSlowerLead = prefs.getBoolean("ce_slower_lead", true)
        val checkStoppedLead = prefs.getBoolean("ce_stopped_lead", true)
        
        // 较慢前车：前车速度比自车慢 > 阈值
        if (checkSlowerLead) {
            val egoSpeed = carState.vEgo
            val leadSpeed = lead0.v
            val speedDiffThreshold = prefs.getInt("ce_lead_speed_diff", 7) / 3.6f  // km/h -> m/s
            if (egoSpeed - leadSpeed > speedDiffThreshold) return true
        }
        
        // 停止前车
        if (checkStoppedLead) {
            if (lead0.v < 1.0f) return true
        }
        
        return false
    }

    /**
     * 条件3: 低速条件
     * 车速低于阈值时切换到实验模式（低速场景实验模式更安全）
     * 有前车和无前车使用不同阈值
     */
    private fun checkSpeedCondition(
        carState: CarStateData,
        modelV2: ModelV2Data
    ): Boolean {
        val speedKmh = carState.vEgo * 3.6f
        val hasLead = (modelV2.lead0?.prob ?: 0f) > 0.5f
        
        return if (hasLead) {
            val threshold = prefs.getInt("ce_speed_lead", 20)
            speedKmh < threshold
        } else {
            val threshold = prefs.getInt("ce_speed", 30)
            speedKmh < threshold
        }
    }
    
    /**
     * 条件4: 导航转弯条件（改用7705回传数据）
     * 
     * 数据来源变更：
     *   旧方案：nTBTDist / nTBTTurnType（来自高德地图广播 → CarrotManFields）
     *   新方案：tbtDist（来自7705 JSON广播 → CarrotManFields.tbtDist）
     * 
     * 优势：
     *   - 不依赖高德导航运行，只要Python端有路线数据即可
     *   - 形成闭环：手机发导航数据 → Python处理 → 回传tbt_dist → 手机判断
     *   - tbtDist > 0 即表示有有效转弯指令，替代 nTBTTurnType != -1
     */
    private fun checkNavigationCondition(
        carrotManFields: CarrotManFields,
        modelV2: ModelV2Data
    ): Boolean {
        if (!prefs.getBoolean("ce_navigation", false)) return false
        
        // 使用7705回传的tbtDist
        val turnDistance = carrotManFields.nTBTDist
        
        // tbtDist > 0 表示有有效转弯指令
        if (turnDistance <= 0) return false
        
        // 距离阈值检查
        val distanceThreshold = prefs.getInt("ce_nav_turn_distance", 200)
        if (turnDistance > distanceThreshold) return false
        
        // 有前车时也触发？
        if (!prefs.getBoolean("ce_nav_lead", true)) {
            val hasLead = (modelV2.lead0?.prob ?: 0f) > 0.5f
            if (hasLead) return false
        }
        
        return true
    }
    
    /**
     * 条件5: 测速点条件（新增，使用7705 sdiDist）
     * 
     * 接近测速点时切换到实验模式，实验模式对速度控制更精确
     * sdiDist: 到测速点的距离（米），来自7705 JSON广播
     * sdiDist > 0 表示前方有测速点
     */
    private fun checkSdiCondition(
        carrotManFields: CarrotManFields
    ): Boolean {
        if (!prefs.getBoolean("ce_sdi", false)) return false
        
        val sdiDistance = carrotManFields.nSdiDist
        if (sdiDistance <= 0) return false
        
        val distanceThreshold = prefs.getInt("ce_sdi_distance", 300)
        return sdiDistance <= distanceThreshold
    }
    
    /**
     * 条件6: 驾驶状态条件（新增，使用7705 xState）
     * 
     * xState 状态码：
     *   0 = lead       跟车模式 — 有前车，系统跟随前车
     *   1 = cruise     巡航模式 — 无前车，定速巡航
     *   2 = e2eCruise  端到端巡航 — 模型控制巡航（已是实验模式特征）
     *   3 = e2eStop    端到端停车中 — 模型检测到需要停车
     *   4 = e2ePrepare 端到端准备起步 — 停车后准备出发
     *   5 = e2eStopped 端到端已停车 — 完全停止状态
     * 
     * 典型用法：
     *   - 当xState=3(停车中)或5(已停车)时切换实验模式，让模型更好地处理起步
     *   - 当xState=0(跟车)时切换实验模式，让模型更好地处理跟车距离
     */
    private fun checkXStateCondition(
        carrotManFields: CarrotManFields
    ): Boolean {
        if (!prefs.getBoolean("ce_xstate", false)) return false
        
        val xState = carrotManFields.xState
        
        // 用户可选择哪些xState触发
        val triggerOnLead = prefs.getBoolean("ce_xstate_lead", false)       // 0: 跟车
        val triggerOnStop = prefs.getBoolean("ce_xstate_stop", true)        // 3: 停车中
        val triggerOnStopped = prefs.getBoolean("ce_xstate_stopped", true)  // 5: 已停车
        val triggerOnPrepare = prefs.getBoolean("ce_xstate_prepare", false) // 4: 准备起步
        
        return when (xState) {
            0 -> triggerOnLead
            3 -> triggerOnStop
            4 -> triggerOnPrepare
            5 -> triggerOnStopped
            else -> false
        }
    }
    
    /**
     * 条件7: 巡航速度调整条件（高速限速变化时临时切换）
     * 
     * 场景：高速公路限速在80/100/110/120之间变化
     * 
     * 数据来源：
     *   - nRoadLimitSpeed: 道路限速（来自高德/腾讯/OSM导航，发送给7705）
     *   - vCruiseKph: 巡航设定速度（7705回传，openpilot的目标速度）
     *   - carcruiseSpeed: 车辆实际巡航速度（7705回传，车辆当前执行的速度）
     * 
     * 工作原理：
     *   1. 检测到道路限速在高速范围(80~120)内发生变化
     *   2. 切换到实验模式，让openpilot根据新限速自动调整vCruiseKph
     *   3. 当carcruiseSpeed与vCruiseKph的差值在容差范围内（默认±5km/h），
     *      说明车辆已完成速度调整，自动切回Chill模式
     *   4. 超时保护：如果超过设定时间仍未对齐，也切回Chill（防止卡在实验模式）
     * 
     * 判断逻辑：
     *   触发条件: nRoadLimitSpeed在[80,120]范围 且 发生变化
     *             且 abs(carcruiseSpeed - vCruiseKph) > tolerance
     *   解除条件: abs(carcruiseSpeed - vCruiseKph) <= tolerance
     *             或 超时（默认30秒）
     */
    private fun checkCruiseSpeedMismatch(
        carrotManFields: CarrotManFields
    ): Boolean {
        if (!prefs.getBoolean("ce_cruise_adj", false)) return false
        
        val roadLimit = carrotManFields.nRoadLimitSpeed
        val vCruise = carrotManFields.vCruiseKph
        val carCruise = carrotManFields.carcruiseSpeed
        val currentTime = System.currentTimeMillis()
        
        // 限速范围检查：只在高速限速范围内工作
        val minLimit = prefs.getInt("ce_cruise_min_limit", 80)
        val maxLimit = prefs.getInt("ce_cruise_max_limit", 120)
        
        if (roadLimit < minLimit || roadLimit > maxLimit) {
            lastKnownSpeedLimit = roadLimit
            return false
        }
        
        // 检测限速变化
        if (roadLimit != lastKnownSpeedLimit && lastKnownSpeedLimit > 0) {
            // 限速发生了变化
            if (lastKnownSpeedLimit in minLimit..maxLimit) {
                // 从一个高速限速变到另一个高速限速
                speedLimitChangedTime = currentTime
                Log.i(TAG, "🚦 限速变化: $lastKnownSpeedLimit → $roadLimit km/h")
            }
        }
        lastKnownSpeedLimit = roadLimit
        
        // 如果没有记录到限速变化，不触发
        if (speedLimitChangedTime == 0L) return false
        
        // 超时保护
        val timeout = prefs.getInt("ce_cruise_timeout", 30) * 1000L
        if (currentTime - speedLimitChangedTime > timeout) {
            speedLimitChangedTime = 0L
            Log.d(TAG, "⏰ 巡航调速超时，重置")
            return false
        }
        
        // 核心判断：carcruiseSpeed 和 vCruiseKph 是否对齐
        val tolerance = prefs.getInt("ce_cruise_tolerance", 5).toFloat()
        val mismatch = abs(carCruise - vCruise)
        
        if (mismatch <= tolerance) {
            // 已对齐，清除状态
            if (speedLimitChangedTime > 0) {
                val elapsed = (currentTime - speedLimitChangedTime) / 1000f
                Log.i(TAG, "✅ 巡航速度已对齐: carCruise=${carCruise.toInt()}, vCruise=${vCruise.toInt()}, 耗时${elapsed}s")
                speedLimitChangedTime = 0L
            }
            return false
        }
        
        // 不匹配，需要切换实验模式让系统调整
        Log.d(TAG, "🔄 巡航速度不匹配: carCruise=${carCruise.toInt()}, vCruise=${vCruise.toInt()}, diff=${mismatch.toInt()}, limit=$roadLimit")
        return true
    }
    
    /**
     * 执行模式切换（带冷却机制）
     */
    fun performModeSwitch(
        xiaogeData: XiaogeVehicleData?,
        carrotManFields: CarrotManFields?
    ) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSwitchTime < COOLDOWN_MS) return
        
        val shouldEnableExperimental = checkConditions(xiaogeData, carrotManFields)
        if (shouldEnableExperimental == isExperimentalModeActive) return
        
        scope.launch {
            try {
                if (shouldEnableExperimental) {
                    switchToExperimentalMode(lastTriggerReason)
                } else {
                    switchToChillMode("条件解除")
                }
                lastSwitchTime = currentTime
            } catch (e: Exception) {
                Log.e(TAG, "模式切换失败: ${e.message}", e)
            }
        }
    }
    
    /**
     * 切换到实验模式
     * 非马自达: ExperimentalMode=1, 马自达: SpeedFromPCM=0
     */
    private suspend fun switchToExperimentalMode(reason: String) {
        val isMazda = prefs.getBoolean("cem_mazda", false)
        val paramName = if (isMazda) "SpeedFromPCM" else "ExperimentalMode"
        val paramValue = if (isMazda) 0 else 1
        Log.i(TAG, "🧪 → 实验模式: $reason ($paramName=$paramValue)")
        
        val paramClient = getParamClient()
        if (paramClient == null) {
            Log.e(TAG, "❌ 设备未连接")
            showToast("❌ 切换失败: 设备未连接")
            return
        }
        
        try {
            val result = paramClient.setParam(paramName, paramValue)
            if (result.isSuccess) {
                isExperimentalModeActive = true
                showToast("🧪 实验模式 ($reason)")
            } else {
                Log.e(TAG, "❌ 切换失败: ${result.exceptionOrNull()?.message}")
                showToast("❌ 切换失败: ${result.exceptionOrNull()?.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 切换异常: ${e.message}", e)
            showToast("❌ 切换失败: ${e.message}")
        }
    }
    
    /**
     * 切换到Chill模式
     */
    private suspend fun switchToChillMode(reason: String) {
        val isMazda = prefs.getBoolean("cem_mazda", false)
        val paramName = if (isMazda) "SpeedFromPCM" else "ExperimentalMode"
        val paramValue = if (isMazda) 1 else 0
        Log.i(TAG, "😌 → Chill模式: $reason ($paramName=$paramValue)")
        
        val paramClient = getParamClient()
        if (paramClient == null) {
            Log.e(TAG, "❌ 设备未连接")
            showToast("❌ 切换失败: 设备未连接")
            return
        }
        
        try {
            val result = paramClient.setParam(paramName, paramValue)
            if (result.isSuccess) {
                isExperimentalModeActive = false
                showToast("😌 Chill模式 ($reason)")
            } else {
                Log.e(TAG, "❌ 切换失败: ${result.exceptionOrNull()?.message}")
                showToast("❌ 切换失败: ${result.exceptionOrNull()?.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 切换异常: ${e.message}", e)
            showToast("❌ 切换失败: ${e.message}")
        }
    }
    
    private suspend fun showToast(msg: String) {
        kotlinx.coroutines.withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 手动切换模式（用于测试）
     */
    suspend fun manualSwitchMode(enableExperimental: Boolean): Result<String> {
        return try {
            val paramClient = getParamClient()
                ?: return Result.failure(Exception("设备未连接"))
            
            val isMazda = prefs.getBoolean("cem_mazda", false)
            val result = if (isMazda) {
                paramClient.setParam("SpeedFromPCM", if (enableExperimental) 0 else 1)
            } else {
                paramClient.setExperimentalMode(enableExperimental)
            }
            
            if (result.isSuccess) {
                isExperimentalModeActive = enableExperimental
                lastSwitchTime = System.currentTimeMillis()
                Result.success("切换成功")
            } else {
                Result.failure(result.exceptionOrNull() ?: Exception("未知错误"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    fun getCurrentMode(): String = if (isExperimentalModeActive) "实验模式" else "Chill模式"
    fun isMonitoringActive(): Boolean = isMonitoring
}
