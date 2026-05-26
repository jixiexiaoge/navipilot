package com.example.navipilot

import android.content.Context
import android.util.Log
import kotlin.math.abs

/**
 * 自动超车管理器
 * 分析车辆数据，判断超车条件，发送变道命令
 * 根据超车模式状态决定是否执行自动超车：
 * - 0: 禁止超车 - 不执行任何超车操作
 * - 1: 拨杆超车 - 需要用户手动拨杆触发（暂不实现）
 * - 2: 自动超车 - 系统自动检测并执行超车
 */
class AutoOvertakeManager(
    private val context: Context,
    private val networkManager: NetworkManager
) {
    companion object {
        private const val TAG = "AutoOvertakeManager"
        
        // 速度阈值
        private const val MIN_OVERTAKE_SPEED_MS = 19.44f  // 70 km/h = 19.44 m/s
        private const val SPEED_DIFF_THRESHOLD = 2.78f    // 速度差阈值 (10 km/h = 2.78 m/s)
        
        // 远距离超车参数（方案3）
        private const val EARLY_OVERTAKE_SPEED_RATIO = 0.8f   // 前车速度 ≤ 80% 本车速度
        private const val EARLY_OVERTAKE_MIN_LEAD_SPEED_KPH = 50.0f  // 前车速度 ≥ 50 km/h
        private const val EARLY_OVERTAKE_MIN_SPEED_DIFF_KPH = 20.0f  // 速度差 ≥ 20 km/h
        private const val EARLY_OVERTAKE_MIN_DISTANCE = 30.0f  // 最小距离 30m
        private const val EARLY_OVERTAKE_MAX_DISTANCE = 100.0f // 最大距离 100m
        
        // 距离阈值
        private const val MAX_LEAD_DISTANCE = 80.0f       // 最大前车距离 (m)
        private const val MIN_TURN_DIST = 2000            // 最小转弯距离 (m)
        
        // 车道线阈值
        private const val MIN_LANE_PROB = 0.6f            // 最小车道线置信度 (60%)
        // 注意：车道线类型检查已移除，允许实线变道（由openpilot系统自行判断）
        
        // 曲率阈值
        private const val MAX_CURVATURE = 0.02f            // 最大曲率 (rad/s) - 更严格的直道判断
        
        // 方向盘角度阈值
        private const val MAX_STEERING_ANGLE = 15.0f       // 最大方向盘角度 (度)
        
        // 时间参数
        private const val DEBOUNCE_FRAMES = 3             // 防抖帧数（需要连续3帧满足条件才确认超车，防止误判）
        private const val CONFIRM_SOUND_COOLDOWN_MS = 2500L  // 🆕 确认音冷却时间（2.5秒）
        private const val LANE_CHANGE_DELAY_MS = 2500L    // 🆕 变道延迟时间（2.5秒）
        private const val OVERTAKE_ACTION_COOLDOWN_MS = 20000L  // 🆕 超车操作冷却时间（20秒）
        
        // 🆕 车道提醒参数
        private const val LANE_REMINDER_COOLDOWN_MS = 15000L  // 15秒提醒一次
        private const val EXIT_TBT_DIST_THRESHOLD = 1500      // 1.5公里内开始提醒
        
        // 返回原车道参数（方案5）
        private const val MAX_LANE_MEMORY_TIME_MS = 30000L  // 30秒超时
        private const val RETURN_MIN_SPEED_ADVANTAGE_KPH = 8.0f  // 返回需要至少8 km/h速度优势
        // 超越完成后等待2秒再返回
        private const val OVERTAKE_COMPLETE_DURATION_MS = 2000L  
        
        // 🆕 驾驶风格常量（性能：E）
        private const val DRIVING_STYLE_CONSERVATIVE = 0 // 保守
        private const val DRIVING_STYLE_STANDARD = 1     // 标准
        private const val DRIVING_STYLE_AGGRESSIVE = 2   // 激进
        
        // 🆕 TBT 偏好参数（方案：D）
        private const val TBT_BIAS_DISTANCE_THRESHOLD = 3000   // 3公里内开始考虑转向偏好
        private const val TBT_STOP_OVERTAKE_THRESHOLD = 1000   // 1公里内禁止反向超车
        
        // 魔法数字优化
        private const val LANE_CENTER_OFFSET = 1.5f         // 车道中心偏移 (m)
        private const val NEAR_LEAD_DISTANCE = 20f          // 近距离前车判断阈值 (m)
        private const val LANE_MATCH_TOLERANCE = 1.0f       // 车道匹配容差 (m)
        private const val TARGET_SPEED_BOOST_KPH = 10f      // 目标车道假设提速 (km/h)
        private const val SAFE_PASS_REL_SPEED_KPH = 5f      // 安全超越相对速度 (km/h)
        private const val SAFE_DISTANCE_MIN = 30f           // 最小安全距离 (m)
        private const val SAFE_DISTANCE_FACTOR = 0.4f       // 安全距离系数 (相对于速度)
        
        // 单位转换（km/h -> m/s）
        private const val MS_PER_KMH = 1.0f / 3.6f
        
        // 声音播放（SoundPool）
        private var soundPool: android.media.SoundPool? = null
        private var soundIdLeft: Int? = null
        private var soundIdRight: Int? = null
    private var soundIdLeftConfirm: Int? = null
    private var soundIdRightConfirm: Int? = null
    private var soundIdGoto: Int? = null
    private val soundLoadedMap = mutableMapOf<Int, Boolean>()
    
    // 🆕 车道提醒状态
    private var lastLaneReminderTime = 0L
    }

    /**
     * 检查结果密封类
     * 替代 Pair<Boolean, String?>，提供更清晰的语义
     */
    private sealed class CheckResult {
        object Pass : CheckResult()
        data class Fail(val reason: String) : CheckResult()
    }

    /**
     * 超车决策数据类
     */
    private data class OvertakeDecision(
        val direction: String,  // "LEFT" 或 "RIGHT"
        val reason: String      // 决策原因
    )
    
    // ===============================
    // 状态变量
    // ===============================
    
    // 配置管理器
    private val config = OvertakeConfig(context)
    
    // 防抖状态
    private var debounceCounter = 0
    private var lastOvertakeDirection: String? = null
    
    // 确认音冷却机制（用于拨杆模式）
    private var lastConfirmSoundTime = 0L
    
    // 🆕 超车操作冷却机制（20秒，用于所有超车相关操作）
    private var lastOvertakeActionTime = 0L  // 最后一次超车操作时间（播放提示音、确认音或发送命令）
    
    // 超车结果跟踪
    private enum class OvertakeResult { NONE, PENDING, SUCCESS, FAILED, CONDITION_NOT_MET }
    private var lastOvertakeResult = OvertakeResult.NONE
    private var pendingOvertakeStartTime = 0L  // 待确认超车开始时间
    private var originalLaneIndex = 0          // 🆕 变道前的原始车道索引
    
    // 返回原车道策略（方案5）
    private var originalLanePosition = 0f  // 原始车道位置（使用横向距离）
    private var netLaneChanges = 0  // 净变道数：>0表示在左侧，<0表示在右侧
    private var laneMemoryStartTime = 0L
    private var overtakeCompleteTimer = 0L
    
    // 待执行变道状态（延迟执行机制）
    private data class PendingLaneChange(
        val direction: String,      // 变道方向 "LEFT" 或 "RIGHT"
        val startTime: Long         // 开始时间（毫秒）
    )
    private var pendingLaneChange: PendingLaneChange? = null  // 待执行的变道
    
    // 日志频率控制
    private val logThrottleMap = mutableMapOf<String, Long>()
    private val DEFAULT_LOG_THROTTLE_MS = 3000L

    // 🆕 导航车道数缓存（由外部从 CarrotManFields.nLaneCount 更新）
    @Volatile var navLaneCountCache: Int = 0
    
    private fun logThrottled(key: String, message: String, level: Int = Log.INFO) {
        val now = System.currentTimeMillis()
        val lastLog = logThrottleMap[key] ?: 0L
        if (now - lastLog > DEFAULT_LOG_THROTTLE_MS) {
            when (level) {
                Log.DEBUG -> Log.d(TAG, message)
                Log.INFO -> Log.i(TAG, message)
                Log.WARN -> Log.w(TAG, message)
                Log.ERROR -> Log.e(TAG, message)
            }
            logThrottleMap[key] = now
        }
    }

    /**
     * 配置管理内部类
     * 负责 SharedPreferences 读取和缓存
     */
    private inner class OvertakeConfig(private val context: Context) {
        private var cachedOvertakeMode: Int? = null
        private var cachedOvertakeModeTime = 0L
        private val OVERTAKE_MODE_CACHE_DURATION_MS = 1000L
        
        private var cachedMinOvertakeSpeedKph: Float? = null
        private var cachedSpeedDiffThresholdKph: Float? = null
        private var cachedDrivingStyle: Int? = null
        
        fun getOvertakeMode(): Int {
            val now = System.currentTimeMillis()
            if (cachedOvertakeMode != null && 
                (now - cachedOvertakeModeTime) < OVERTAKE_MODE_CACHE_DURATION_MS) {
                return cachedOvertakeMode ?: 0
            }
            
            val mode = try {
                context.getSharedPreferences("CarrotAmap", Context.MODE_PRIVATE)
                    .getInt("overtake_mode", 1)  // 默认值改为 1（拨杆超车）
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ 获取超车模式失败，使用默认值1（拨杆超车）: ${e.message}")
                1  // 默认值改为 1（拨杆超车）
            }
            
            cachedOvertakeMode = mode
            cachedOvertakeModeTime = now
            return mode
        }

        /**
         * 🆕 获取驾驶风格 (E)
         */
        fun getDrivingStyle(): Int {
            cachedDrivingStyle?.let { return it }
            val style = try {
                context.getSharedPreferences("CarrotAmap", Context.MODE_PRIVATE)
                    .getInt("overtake_driving_style", DRIVING_STYLE_STANDARD)
            } catch (e: Exception) {
                DRIVING_STYLE_STANDARD
            }
            cachedDrivingStyle = style
            return style
        }
        
        /** 获取 Float 类型自适应参数（类型安全，无泛型强转） */
        fun getAdaptiveParameter(key: String, defaultValue: Float): Float {
            val style = getDrivingStyle()
            return when (key) {
                "SPEED_DIFF_THRESHOLD" -> when (style) {
                    DRIVING_STYLE_CONSERVATIVE -> defaultValue * 1.5f
                    DRIVING_STYLE_AGGRESSIVE   -> defaultValue * 0.7f
                    else -> defaultValue
                }
                "EARLY_OVERTAKE_SPEED_RATIO" -> when (style) {
                    DRIVING_STYLE_CONSERVATIVE -> (defaultValue * 0.8f).coerceIn(0.5f, 0.95f)
                    DRIVING_STYLE_AGGRESSIVE   -> (defaultValue * 1.1f).coerceIn(0.5f, 0.95f)
                    else -> defaultValue
                }
                "RETURN_MIN_SPEED_ADVANTAGE" -> when (style) {
                    DRIVING_STYLE_CONSERVATIVE -> defaultValue * 1.5f
                    DRIVING_STYLE_AGGRESSIVE   -> defaultValue * 0.5f
                    else -> defaultValue
                }
                "MAX_LEAD_DISTANCE" -> when (style) {
                    DRIVING_STYLE_CONSERVATIVE -> defaultValue * 0.8f
                    DRIVING_STYLE_AGGRESSIVE   -> defaultValue * 1.2f
                    else -> defaultValue
                }
                "MIN_TURN_DIST" -> when (style) {
                    DRIVING_STYLE_CONSERVATIVE -> defaultValue * 1.5f
                    DRIVING_STYLE_AGGRESSIVE   -> defaultValue * 0.7f
                    else -> defaultValue
                }
                else -> defaultValue
            }
        }

        /** 获取 Long 类型自适应参数（类型安全，无泛型强转） */
        fun getAdaptiveParameter(key: String, defaultValue: Long): Long {
            val style = getDrivingStyle()
            return when (key) {
                "ACTION_COOLDOWN" -> when (style) {
                    DRIVING_STYLE_CONSERVATIVE -> (defaultValue * 1.5).toLong()
                    DRIVING_STYLE_AGGRESSIVE   -> (defaultValue * 0.5).toLong()
                    else -> defaultValue
                }
                else -> defaultValue
            }
        }
        
        fun getMinOvertakeSpeedKph(): Float {
            cachedMinOvertakeSpeedKph?.let { return it }
            
            val value = try {
                val prefs = context.getSharedPreferences("CarrotAmap", Context.MODE_PRIVATE)
                val defaultValue = MIN_OVERTAKE_SPEED_MS * 3.6f  // 70 km/h
                val v = prefs.getFloat("overtake_param_min_speed_kph", defaultValue)
                v.coerceIn(50f, 120f)  // 允许范围 50-120 km/h
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ 获取最小超车速度失败，使用默认值70: ${e.message}")
                MIN_OVERTAKE_SPEED_MS * 3.6f  // 70 km/h
            }
            
            cachedMinOvertakeSpeedKph = value
            return value
        }
        
        fun getSpeedDiffThresholdKph(): Float {
            cachedSpeedDiffThresholdKph?.let { return it }
            
            val rawValue = try {
                val prefs = context.getSharedPreferences("CarrotAmap", Context.MODE_PRIVATE)
                val defaultValue = SPEED_DIFF_THRESHOLD * 3.6f
                val v = prefs.getFloat("overtake_param_speed_diff_kph", defaultValue)
                v.coerceIn(5f, 30f)
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ 获取速度差阈值失败，使用默认值10: ${e.message}")
                SPEED_DIFF_THRESHOLD * 3.6f
            }
            
            // 🆕 应用自适应参数 (E)
            val value = getAdaptiveParameter("SPEED_DIFF_THRESHOLD", rawValue)
            
            cachedSpeedDiffThresholdKph = value
            return value
        }
    }

    /**
     * 更新数据并判断是否需要超车
     * ✅ 优化：拆分逻辑，提高可读性和可维护性
     * @param data 车辆数据
     * @param roadType 道路类型（高德地图 ROAD_TYPE：0=高速公路，6=快速路，8=未知等）。如果为null，则不检查道路类型（向后兼容）
     * @param segAssistantAction 导航辅助动作（1表示驶出）
     * @param tbtMainText TBT主文本
     * @return 更新后的超车状态数据，用于更新 XiaogeVehicleData
     */
    fun update(
        data: XiaogeVehicleData?, 
        roadType: Int? = null,
        segAssistantAction: Int? = null,
        tbtMainText: String? = null
    ): OvertakeStatusData? {
        // 快速失败：空数据检查
        if (data == null) return null
        
        // 🆕 1. 推断当前车道信息
        val (currentLane, totalLanes) = inferLanePosition(data)
        
        // 🆕 2. 检查驶出提醒
        val laneReminder = checkExitLaneReminder(
            data, roadType, segAssistantAction, tbtMainText, currentLane, totalLanes
        )
        
        // 获取超车模式
        val overtakeMode = config.getOvertakeMode()
        
        // 3. 处理禁止超车模式
        if (overtakeMode == 0) {
            return handleOvertakeModeDisabled(data, currentLane, totalLanes, laneReminder)
        }
        
        // 4. 处理待执行的变道（延迟执行机制）
        val pendingCheck = checkPendingLaneChange(data, overtakeMode, roadType, currentLane, totalLanes, laneReminder)
        if (pendingCheck != null) return pendingCheck
        
        // 5. 处理变道中状态
        val laneChangeCheck = checkLaneChangeProgress(data, currentLane, totalLanes, laneReminder)
        if (laneChangeCheck != null) return laneChangeCheck
        
        // 6. 处理变道完成状态
        handleLaneChangeCompleted()
        
        // 7. 检查返回原车道条件
        val returnCheck = checkReturnToOriginalLane(data, overtakeMode, currentLane, totalLanes, laneReminder)
        if (returnCheck != null) return returnCheck
        
        // 8. 评估超车条件并执行决策
        return evaluateOvertakeConditions(data, overtakeMode, roadType, currentLane, totalLanes, laneReminder, tbtMainText)
    }
    
    /**
     * ✅ 优化：处理禁止超车模式
     */
    private fun handleOvertakeModeDisabled(
        data: XiaogeVehicleData,
        currentLane: Int,
        totalLanes: Int,
        laneReminder: String?
    ): OvertakeStatusData {
        debounceCounter = 0
        resetLaneMemory()
        cancelPendingLaneChange()
        return createOvertakeStatus(data, "禁止超车", false, null, 
            currentLane = currentLane, totalLanes = totalLanes, laneReminder = laneReminder)
    }
    
    /**
     * 🆕 检查变道进度
     * 由于 Python 端不再发送 laneChangeState，我们通过车道索引的变化来推断变道是否完成
     */
    private fun checkLaneChangeProgress(
        data: XiaogeVehicleData,
        currentLane: Int,
        totalLanes: Int,
        laneReminder: String?
    ): OvertakeStatusData? {
        if (lastOvertakeResult != OvertakeResult.PENDING) return null

        val now = System.currentTimeMillis()
        val elapsed = now - pendingOvertakeStartTime

        // 1. 检查变道是否完成（当前车道索引已改变）
        if (currentLane != originalLaneIndex && originalLaneIndex != 0 && currentLane != 0) {
            Log.i(TAG, "✅ 检测到车道变更: $originalLaneIndex -> $currentLane, 变道完成")
            handleLaneChangeCompleted()
            return null // 让 update 流程继续，进入下一阶段
        }

        // 2. 检查超时（12秒未完成变道则认为失败/取消）
        if (elapsed > 12000L) {
            Log.w(TAG, "⏱️ 变道超时 (12s)，标记为失败")
            lastOvertakeResult = OvertakeResult.FAILED
            pendingOvertakeStartTime = 0L
            originalLaneIndex = 0
            return null
        }

        // 3. 仍在变道中
        val direction = lastOvertakeDirection
        return createOvertakeStatus(data, "变道中", false, direction, 
            currentLane = currentLane, totalLanes = totalLanes, laneReminder = laneReminder)
    }
    
    /**
     * ✅ 优化：处理变道完成状态
     */
    private fun handleLaneChangeCompleted() {
        if (lastOvertakeResult == OvertakeResult.PENDING) {
            lastOvertakeResult = OvertakeResult.SUCCESS
            pendingOvertakeStartTime = 0L
            originalLaneIndex = 0
            Log.i(TAG, "✅ 变道成功完成")
        }
    }

    /**
     * 🆕 根据路缘推断当前车道位置
     * 与 VehicleLaneVisualization 中的逻辑保持一致
     */
    private fun inferLanePosition(data: XiaogeVehicleData): Pair<Int, Int> {
        val meta = data.modelV2?.meta ?: return Pair(0, 0)
        
        val roadEdgeLeft = meta.distanceToRoadEdgeLeft
        val roadEdgeRight = meta.distanceToRoadEdgeRight
        
        val referenceLaneWidth = 2.5f // 2.5m 作为基准车道宽
        
        // 1. 推断左侧还有几条车道
        val leftLanes = if (roadEdgeLeft > 0.5f) {
            (roadEdgeLeft / referenceLaneWidth).toInt()
        } else 0
        
        // 2. 推断右侧还有几条车道
        val rightLanes = if (roadEdgeRight > 0.5f) {
            (roadEdgeRight / referenceLaneWidth).toInt()
        } else 0
        
        val totalLanes = leftLanes + 1 + rightLanes
        val currentLane = leftLanes + 1
        
        return Pair(currentLane, totalLanes)
    }

    /**
     * 🆕 从 ModelV2 数据推断车道位置（用于 feasibility check，不依赖 XiaogeVehicleData）
     */
    private fun inferLanePositionFromModel(modelV2: ModelV2Data): Pair<Int, Int> {
        val meta = modelV2.meta ?: return Pair(0, 0)
        val roadEdgeLeft = meta.distanceToRoadEdgeLeft
        val roadEdgeRight = meta.distanceToRoadEdgeRight
        val referenceLaneWidth = 2.5f
        val leftLanes = if (roadEdgeLeft > 0.5f) (roadEdgeLeft / referenceLaneWidth).toInt() else 0
        val rightLanes = if (roadEdgeRight > 0.5f) (roadEdgeRight / referenceLaneWidth).toInt() else 0
        val totalLanes = leftLanes + 1 + rightLanes
        val currentLane = leftLanes + 1
        return Pair(currentLane, totalLanes)
    }

    /**
     * 🆕 检查是否需要驶出高速/高架的车道提醒
     */
    private fun checkExitLaneReminder(
        data: XiaogeVehicleData,
        roadType: Int?,
        segAssistantAction: Int?,
        tbtMainText: String?,
        currentLane: Int,
        totalLanes: Int
    ): String? {
        // 1. 检查道路类型：只在高速公路(0)或快速路(6)时才提醒
        // 注意：道路类型限制仅用于驶出提醒，不影响超车功能
        if (roadType != 0 && roadType != 6) return null
        
        // 2. 检查是否接近出口
        // 方案A: 检查 segAssistantAction (1表示驶出)
        // 方案B: 检查 TBT 文本和距离
        val isExiting = segAssistantAction == 1 || 
                      (data.tbtDist > 0 && data.tbtDist < EXIT_TBT_DIST_THRESHOLD && 
                       (tbtMainText?.contains("出口") == true || tbtMainText?.contains("驶出") == true))
        
        if (!isExiting) return null
        
        // 3. 检查是否在最右侧车道
        // 高速公路(roadType=0)时，如果 totalLanes > 1，最右侧通常是第 totalLanes 车道
        // 用户提到：高速时则忽略应急车道。通常模型识别出的 totalLanes 已经是不含应急车道的行驶车道。
        if (totalLanes > 1 && currentLane < totalLanes) {
            val now = System.currentTimeMillis()
            if (now - lastLaneReminderTime > LANE_REMINDER_COOLDOWN_MS) {
                lastLaneReminderTime = now
                playGotoSound()
                return "请靠右行驶以准备驶出"
            }
        }
        
        return null
    }

    private fun playGotoSound() {
        try {
            ensureSoundPool()
            val id = soundIdGoto ?: return
            if (soundLoadedMap[id] == true) {
                soundPool?.play(id, 1f, 1f, 1, 0, 1f)
                Log.i(TAG, "🎵 播放驶出提醒音效 (go_to.mp3)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 播放驶出提醒音效失败: ${e.message}")
        }
    }

    // 注意：checkLaneChangeProgress 的 2 参数版本已移除（与 4 参数版本逻辑重复）
    // 统一使用 checkLaneChangeProgress(data, currentLane, totalLanes, laneReminder) 版本

    /**
     * ✅ 优化：检查返回原车道条件
     */
    private fun checkReturnToOriginalLane(
        data: XiaogeVehicleData,
        overtakeMode: Int,
        currentLane: Int,
        totalLanes: Int,
        laneReminder: String?
    ): OvertakeStatusData? {
        if (!checkReturnConditions(data)) return null
        
        val returnDirection = if (netLaneChanges > 0) "RIGHT" else "LEFT"
        if (overtakeMode == 2) {
            sendLaneChangeCommand(returnDirection)
            Log.i(TAG, "🔄 返回原车道: $returnDirection")
            resetLaneMemory()
        }
        return createOvertakeStatus(data, "返回原车道", false, returnDirection, 
            currentLane = currentLane, totalLanes = totalLanes, laneReminder = laneReminder)
    }
        
    /**
     * ✅ 优化：评估超车条件并执行决策
     */
    private fun evaluateOvertakeConditions(
        data: XiaogeVehicleData,
        overtakeMode: Int,
        roadType: Int?,
        currentLane: Int,
        totalLanes: Int,
        laneReminder: String?,
        tbtMainText: String?
    ): OvertakeStatusData {
        // 🆕 检查超车操作冷却时间 (🆕 自适应冷却: E)
        val now = System.currentTimeMillis()
        val timeSinceLastAction = now - lastOvertakeActionTime
        val adaptiveCooldown = config.getAdaptiveParameter("ACTION_COOLDOWN", OVERTAKE_ACTION_COOLDOWN_MS)
        
        if (lastOvertakeActionTime > 0 && timeSinceLastAction < adaptiveCooldown) {
            val remainingCooldown = adaptiveCooldown - timeSinceLastAction
            val remainingSec = String.format("%.1f", remainingCooldown / 1000.0)
            logThrottled("cooldown", "⏱️ 超车冷却中，剩余 $remainingSec 秒", Log.DEBUG)
            return createOvertakeStatus(
                data,
                "冷却中",
                false,
                null,
                blockingReason = "超车操作冷却中，剩余 $remainingSec 秒",
                cooldownRemaining = remainingCooldown,
                currentLane = currentLane,
                totalLanes = totalLanes,
                laneReminder = laneReminder
            )
        }
        
        // 🆕 检查 TBT 方向偏好 (D)
        val tbtBiasDirection = checkTbtDirectionBias(data, tbtMainText)
        if (tbtBiasDirection != null && data.tbtDist > 0 && data.tbtDist < TBT_STOP_OVERTAKE_THRESHOLD) {
            // 如果距离转向点已经非常近（1公里内），且该偏好方向与当前可能的超车方向冲突，则禁止超车
            logThrottled("tbt_stop", "🛑 接近转向点 (${data.tbtDist}m)，禁止反向变道以保证安全驶出", Log.WARN)
            return createOvertakeStatus(data, "监控中", false, null,
                blockingReason = "接近转向点，禁止超车",
                currentLane = currentLane,
                totalLanes = totalLanes,
                laneReminder = laneReminder)
        }
        
        // 如果有待执行的变道，检查条件是否仍满足
        if (pendingLaneChange != null) {
            cancelPendingLaneChangeIfConditionsChanged(data, roadType)
        }
        
        // 检查前置条件
        val prerequisites = checkPrerequisites(data, roadType)
        if (prerequisites is CheckResult.Fail) {
            lastOvertakeResult = OvertakeResult.CONDITION_NOT_MET
            return createOvertakeStatus(data, "监控中", false, null, 
                blockingReason = prerequisites.reason,
                currentLane = currentLane,
                totalLanes = totalLanes,
                laneReminder = laneReminder)
        }
        
        // 检查是否需要超车
        val overtakeCheck = shouldOvertake(data)
        if (overtakeCheck is CheckResult.Fail) {
            debounceCounter = 0
            lastOvertakeResult = OvertakeResult.CONDITION_NOT_MET
            return createOvertakeStatus(data, "监控中", false, null, 
                blockingReason = overtakeCheck.reason,
                currentLane = currentLane,
                totalLanes = totalLanes,
                laneReminder = laneReminder)
        }
        
        // 防抖机制：需要连续3帧满足条件才确认超车，防止误判
        debounceCounter++
        if (debounceCounter < DEBOUNCE_FRAMES) {
            return createOvertakeStatus(data, "监控中", true, null,
                currentLane = currentLane,
                totalLanes = totalLanes,
                laneReminder = laneReminder)
        }
        
        // 评估超车方向（已通过3帧验证）
        var decision = checkOvertakeConditions(data)
        
        // 🆕 应用 TBT 偏好权重 (D)
        if (decision != null && tbtBiasDirection != null && decision.direction != tbtBiasDirection) {
            // 如果当前决策方向与 TBT 偏好方向相反，则根据距离动态调整抑制力度
            val vEgo = data.carState?.vEgo ?: 0f
            val vLead = data.modelV2?.lead0?.v ?: 0f
            val speedDiff = (vEgo - vLead) * 3.6f
            
            // 距离越近，要求的速度差越高（3km 时要求 15km/h，1km 时要求 40km/h）
            val distRatio = (3000f - data.tbtDist.coerceIn(1000, 3000).toFloat()) / 2000f // 0.0 (3km) to 1.0 (1km)
            val requiredSpeedDiff = 15f + distRatio * 25f 
            
            if (speedDiff < requiredSpeedDiff) {
                Log.i(TAG, "⚖️ TBT 偏好抑制: 目标 $tbtBiasDirection, 距离 ${data.tbtDist}m, 要求速度差 ${requiredSpeedDiff.toInt()}km/h (当前 ${speedDiff.toInt()}km/h), 抑制反向变道")
                decision = null
            }
        }

        if (decision != null) {
            return handleOvertakeDecision(data, decision, overtakeMode, currentLane, totalLanes, laneReminder)
        } else {
            // 超车方向不可行，重置防抖计数
            debounceCounter = 0
            lastOvertakeResult = OvertakeResult.CONDITION_NOT_MET
            val blockingReason = generateBlockingReason(data)
            return createOvertakeStatus(data, "监控中", false, null, 
                blockingReason = blockingReason,
                currentLane = currentLane,
                totalLanes = totalLanes,
                laneReminder = laneReminder)
        }
    }
    
    /**
     * ✅ 优化：处理超车决策
     */
    private fun handleOvertakeDecision(
        data: XiaogeVehicleData,
        decision: OvertakeDecision,
        overtakeMode: Int,
        currentLane: Int,
        totalLanes: Int,
        laneReminder: String?
    ): OvertakeStatusData {
        val carState = data.carState
        val lead0 = data.modelV2?.lead0
    
        if (overtakeMode == 2) {
            // 自动超车模式：先播放提示音，记录待执行状态，2.5秒后再执行
            handleAutoOvertakeMode(decision)
        } else {
            // 拨杆模式：检查冷却时间，只播放一次确认音
            handleManualOvertakeMode(decision)
        }
        
        lastOvertakeDirection = decision.direction
        debounceCounter = 0
        
        // 记录日志
        logOvertakeDecision(decision, carState, lead0, overtakeMode)
        
        return createOvertakeStatus(
            data,
            if (overtakeMode == 2) "准备变道" else "可超车",
            true,
            decision.direction,
            currentLane = currentLane,
            totalLanes = totalLanes,
            laneReminder = laneReminder
        )
    }
    
    /**
     * ✅ 优化：处理自动超车模式
     */
    private fun handleAutoOvertakeMode(decision: OvertakeDecision) {
        val pending = pendingLaneChange
        if (pending == null) {
            // 第一次检测到可超车，播放提示音并记录待执行状态
            playLaneChangeSound(decision.direction)
            // 🆕 记录超车操作时间（播放提示音）
            lastOvertakeActionTime = System.currentTimeMillis()
            pendingLaneChange = PendingLaneChange(
                direction = decision.direction,
                startTime = System.currentTimeMillis()
            )
            Log.i(TAG, "🔔 检测到可超车，播放提示音: ${decision.direction}, 2.5秒后执行")
        } else if (pending.direction != decision.direction) {
            // 如果方向改变，取消旧的待执行变道，开始新的
            Log.i(TAG, "🔄 变道方向改变: ${pending.direction} -> ${decision.direction}")
            cancelPendingLaneChange()
            playLaneChangeSound(decision.direction)
            // 🆕 记录超车操作时间（重新播放提示音）
            lastOvertakeActionTime = System.currentTimeMillis()
            pendingLaneChange = PendingLaneChange(
                direction = decision.direction,
                startTime = System.currentTimeMillis()
            )
            Log.i(TAG, "🔔 重新播放提示音: ${decision.direction}, 2.5秒后执行")
        }
    }
    
    /**
     * ✅ 优化：处理拨杆超车模式
     */
    private fun handleManualOvertakeMode(decision: OvertakeDecision) {
        val now = System.currentTimeMillis()
        if (now - lastConfirmSoundTime >= CONFIRM_SOUND_COOLDOWN_MS) {
            playConfirmSound(decision.direction)
            lastConfirmSoundTime = now
            // 🆕 记录超车操作时间（播放确认音）
            lastOvertakeActionTime = now
            Log.i(TAG, "🔔 拨杆模式播放确认音: ${decision.direction}, 原因: ${decision.reason}")
        } else {
            val remainingCooldown = (CONFIRM_SOUND_COOLDOWN_MS - (now - lastConfirmSoundTime)) / 1000
            Log.d(TAG, "⏱️ 拨杆模式冷却中，剩余${remainingCooldown}秒")
        }
    }
    
    /**
     * ✅ 优化：记录超车决策日志
     */
    private fun logOvertakeDecision(
        decision: OvertakeDecision,
        carState: CarStateData?,
        lead0: LeadData?,
        overtakeMode: Int
    ) {
        val logContext = if (carState != null && lead0 != null) {
            ", 本车${(carState.vEgo * 3.6f).toInt()}km/h, 前车${(lead0.v * 3.6f).toInt()}km/h, 距离${lead0.x.toInt()}m"
        } else {
            ""
        }
    
        if (overtakeMode == 2) {
            val remainingTime = pendingLaneChange?.let { pending ->
                val elapsed = System.currentTimeMillis() - pending.startTime
                val remaining = (LANE_CHANGE_DELAY_MS - elapsed) / 1000
                if (remaining > 0) " (${remaining}秒后执行)" else " (即将执行)"
            } ?: ""
            logThrottled("pending_overtake", "⏳ 待执行超车: ${decision.direction}, 原因: ${decision.reason}$logContext$remainingTime")
        }
    }
    
    /**
     * ✅ 优化：如果条件改变，取消待执行变道
     */
    private fun cancelPendingLaneChangeIfConditionsChanged(data: XiaogeVehicleData, roadType: Int?) {
        val prerequisites = checkPrerequisites(data, roadType)
        val overtakeCheck = shouldOvertake(data)
        val decision = checkOvertakeConditions(data)
        
        if (prerequisites is CheckResult.Fail || 
            overtakeCheck is CheckResult.Fail || 
            decision == null || 
            decision.direction != pendingLaneChange?.direction) {
            cancelPendingLaneChange()
        }
    }
    
    /**
     * ✅ 优化：检查前置条件（必须全部满足）
     * 简化版：只保留6项必要检查
     * 优化：使用快速失败原则，先检查最可能失败的条件
     * @param data 车辆数据
     * @param roadType 道路类型（高德地图 ROAD_TYPE）。已废弃，不再限制道路类型
     * @return CheckResult 检查结果
     */
    private fun checkPrerequisites(data: XiaogeVehicleData, roadType: Int?): CheckResult {
        val carState = data.carState ?: return CheckResult.Fail("车辆状态缺失")
        val modelV2 = data.modelV2 ?: return CheckResult.Fail("模型数据缺失")
        
        // ✅ 优化：快速失败 - 先检查最可能失败的条件
        
        // 0. 道路类型检查已取消 - 所有道路类型均可超车
        
        // 1. 🆕 检查转弯距离：如果距离转弯点小于2000米，禁止超车 (🆕 自适应距离: E)
        val adaptiveMinTurnDist = config.getAdaptiveParameter("MIN_TURN_DIST", MIN_TURN_DIST.toFloat()).toInt()
        if (data.tbtDist > 0 && data.tbtDist < adaptiveMinTurnDist) {
            return CheckResult.Fail("接近转弯点 (距离${data.tbtDist}m < 阈值${adaptiveMinTurnDist}m)")
        }
        
        // 2. 若系统正在变道，禁止新的超车（快速失败）
        // 🆕 适配：由于 Python 端不再发送 laneChangeState，我们使用本地状态判断
        if (lastOvertakeResult == OvertakeResult.PENDING) {
            return CheckResult.Fail("变道中")
        }
        
        // 3. 前车存在且距离较近（快速失败） (🆕 自适应距离: E)
        val lead0 = modelV2.lead0
        val adaptiveMaxLeadDist = config.getAdaptiveParameter("MAX_LEAD_DISTANCE", MAX_LEAD_DISTANCE)
        if (lead0 == null || lead0.x >= adaptiveMaxLeadDist || lead0.prob < 0.5f) {
            return CheckResult.Fail("前车距离过远或置信度不足")
        }
        
        // 4. 速度满足要求（使用可配置参数，最低 70 km/h）
        val minOvertakeSpeedKph = config.getMinOvertakeSpeedKph()
        val minOvertakeSpeedMs = minOvertakeSpeedKph * MS_PER_KMH
        if (carState.vEgo < minOvertakeSpeedMs) {
            return CheckResult.Fail("速度过低 (< ${minOvertakeSpeedKph.toInt()} km/h)")
        }
        
        // 5. 前车最低速度限制（避免堵车误判）
        val leadSpeedKmh = lead0.v * 3.6f
        val minLeadSpeed = 50.0f  // 统一使用50 km/h作为最低速度阈值
        if (leadSpeedKmh < minLeadSpeed) {
            return CheckResult.Fail("前车速度过低 (< ${minLeadSpeed.toInt()} km/h)")
        }
        
        // 6. 不在弯道 (使用更严格的阈值)
        val curvature = modelV2.curvature
        if (curvature != null && abs(curvature.maxOrientationRate) >= MAX_CURVATURE) {
            return CheckResult.Fail("弯道中 (曲率过大)")
        }
        
        // 7. 方向盘角度检查
        if (abs(carState.steeringAngleDeg) > MAX_STEERING_ANGLE) {
            return CheckResult.Fail("方向盘角度过大")
        }
        
        return CheckResult.Pass
    }
    
    /**
     * 判断是否需要超车
     * @return CheckResult 检查结果
     */
    private fun shouldOvertake(data: XiaogeVehicleData): CheckResult {
        val carState = data.carState ?: return CheckResult.Fail("车辆状态缺失")
        val lead0 = data.modelV2?.lead0 ?: return CheckResult.Fail("前车数据缺失")
        
        // 方案3：远距离超车支持（优先检查）
        if (checkEarlyOvertakeConditions(data)) {
            return CheckResult.Pass
        }
        
        val vEgo = carState.vEgo
        val vLead = lead0.v
        
        // 前车速度明显低于本车（只检查速度差，移除速度比例检查）
        val speedDiff = vEgo - vLead

        // 使用可配置参数（只检查速度差）
        val speedDiffThreshold = config.getSpeedDiffThresholdKph() * MS_PER_KMH  // 转换为 m/s
        val needsOvertake = speedDiff >= speedDiffThreshold
        return if (needsOvertake) {
            CheckResult.Pass
        } else {
            CheckResult.Fail("速度差不足 (< ${config.getSpeedDiffThresholdKph().toInt()} km/h)")
        }
    }
    
    /**
     * 检查超车条件并返回决策
     */
    private fun checkOvertakeConditions(data: XiaogeVehicleData): OvertakeDecision? {
        val carState = data.carState ?: return null
        val modelV2 = data.modelV2 ?: return null
        
        // 检查左超车可行性（使用 modelV2 数据，纯视觉方案）
        val leftResult = checkLeftOvertakeFeasibility(carState, modelV2)
        if (leftResult is CheckResult.Pass) {
            return OvertakeDecision("LEFT", "左超车条件满足")
        }
        
        // 检查右超车可行性（使用 modelV2 数据，纯视觉方案）
        val rightResult = checkRightOvertakeFeasibility(carState, modelV2)
        if (rightResult is CheckResult.Pass) {
            return OvertakeDecision("RIGHT", "右超车条件满足")
        }
        
        return null
    }
    
    /**
     * ✅ 检查左超车可行性（纯视觉方案 + 导航车道线推断）
     * 简化版：只保留车道线置信度、车道宽度、盲区检查、左侧车辆检查
     * 🆕 增加：导航数据推断实线/虚线
     */
    private fun checkLeftOvertakeFeasibility(
        carState: CarStateData,
        modelV2: ModelV2Data
    ): CheckResult {
        // 🆕 导航数据推断车道线类型：最左车道左侧为实线，禁止左变道
        val (currentLane, totalLanes) = inferLanePositionFromModel(modelV2)
        val navLaneCount = navLaneCountCache
        if (navLaneCount > 0 && currentLane == 1 && totalLanes > 1) {
            return CheckResult.Fail("最左车道，左侧实线")
        }
        // 如果导航车道数不可用，用模型推断：左侧路缘距离 < 一个车道宽 → 最左车道
        if (navLaneCount <= 0 && totalLanes > 1 && currentLane == 1) {
            return CheckResult.Fail("最左车道，左侧实线(推断)")
        }

        // 检查左侧是否有车辆（openpilot 模型）
        if (modelV2.leadLeft?.status == true) {
            return CheckResult.Fail("左侧车道有车")
        }

        // 🆕 适配：使用路缘距离判断车道可行性
        val roadEdgeLeft = modelV2.meta?.distanceToRoadEdgeLeft ?: 0f
        val isLaneFeasible = roadEdgeLeft > 2.5f // 如果左侧路缘距离 > 2.5m，认为有足够空间变道

        return checkOvertakeFeasibility(
            direction = "LEFT",
            laneProb = modelV2.laneLineProbs.getOrNull(0),
            isLaneFeasible = isLaneFeasible,
            hasBlindspot = carState.leftBlindspot
        )
    }
    
    /**
     * ✅ 检查右超车可行性（纯视觉方案 + 导航车道线推断）
     * 简化版：只保留车道线置信度、路缘检查、盲区检查、右侧车辆检查
     * 🆕 增加：导航数据推断实线/虚线
     */
    private fun checkRightOvertakeFeasibility(
        carState: CarStateData,
        modelV2: ModelV2Data
    ): CheckResult {
        // 🆕 导航数据推断车道线类型：最右车道右侧为实线，禁止右变道
        val (currentLane, totalLanes) = inferLanePositionFromModel(modelV2)
        val navLaneCount = navLaneCountCache
        if (navLaneCount > 0 && currentLane == totalLanes && totalLanes > 1) {
            return CheckResult.Fail("最右车道，右侧实线")
        }
        // 如果导航车道数不可用，用模型推断
        if (navLaneCount <= 0 && totalLanes > 1 && currentLane == totalLanes) {
            return CheckResult.Fail("最右车道，右侧实线(推断)")
        }

        // 检查右侧是否有车辆（openpilot 模型）
        if (modelV2.leadRight?.status == true) {
            return CheckResult.Fail("右侧车道有车")
        }

        // 🆕 适配：使用路缘距离判断车道可行性
        val roadEdgeRight = modelV2.meta?.distanceToRoadEdgeRight ?: 0f
        val isLaneFeasible = roadEdgeRight > 2.5f // 如果右侧路缘距离 > 2.5m，认为有足够空间变道

        return checkOvertakeFeasibility(
            direction = "RIGHT",
            laneProb = modelV2.laneLineProbs.getOrNull(1),
            isLaneFeasible = isLaneFeasible,
            hasBlindspot = carState.rightBlindspot
        )
    }
    
    /**
     * ✅ 优化：提取左右超车检查的公共逻辑，减少代码重复
     */
    private fun checkOvertakeFeasibility(
        direction: String,
        laneProb: Float?,
        isLaneFeasible: Boolean,
        hasBlindspot: Boolean
    ): CheckResult {
        val dirText = if (direction == "LEFT") "左侧" else "右侧"

        // 1. 车道线置信度检查
        if (laneProb == null || laneProb < MIN_LANE_PROB) {
            return CheckResult.Fail("${dirText}车道线置信度不足")
        }
        
        // 2. 车道可行性检查 (基于路缘距离)
        if (!isLaneFeasible) {
            return CheckResult.Fail("${dirText}空间不足(靠边)")
        }
        
        // 3. 盲区检查
        if (hasBlindspot) {
            return CheckResult.Fail("${dirText}盲区有车")
        }
        
        return CheckResult.Pass
    }
    
    /**
     * 发送变道命令
     * 发送命令给comma3（不播放提示音，因为已在2.5秒前播放）
     */
    private fun sendLaneChangeCommand(direction: String, playSound: Boolean = false) {
        try {
            // 发送变道命令给comma3
            networkManager.sendControlCommand("LANECHANGE", direction)
            // 🆕 记录超车操作时间（发送变道命令）
            lastOvertakeActionTime = System.currentTimeMillis()
            Log.i(TAG, "📤 已发送变道命令: $direction")
            
            // 🆕 可选：播放变道提示音（默认不播放，因为已在2.5秒前播放过）
            if (playSound) {
                playLaneChangeSound(direction)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 发送变道命令失败: ${e.message}", e)
        }
    }
    
    /**
     * 播放变道提示音
     * 左变道播放left音效，右变道播放right音效
     */
    private fun playLaneChangeSound(direction: String) {
        try {
            ensureSoundPool()
            val idOpt = when (direction.uppercase()) {
                "LEFT" -> soundIdLeft
                "RIGHT" -> soundIdRight
                else -> {
                    Log.w(TAG, "⚠️ 未知的变道方向: $direction，不播放音效")
                    return
                }
            }
            val id = idOpt ?: return
            if (soundLoadedMap[id] == true) {
                soundPool?.play(id, 1f, 1f, 1, 0, 1f)
            } else {
                Log.d(TAG, "⏱️ 音效尚未加载完成: $direction")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 播放${direction}变道提示音失败: ${e.message}", e)
        }
    }

    private fun playConfirmSound(direction: String) {
        try {
            ensureSoundPool()
            val idOpt = when (direction.uppercase()) {
                "LEFT" -> soundIdLeftConfirm
                "RIGHT" -> soundIdRightConfirm
                else -> null
            }
            val id = idOpt ?: return
            if (soundLoadedMap[id] == true) {
                soundPool?.play(id, 1f, 1f, 1, 0, 1f)
            } else {
                Log.d(TAG, "⏱️ 确认音尚未加载完成: $direction")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 播放确认音失败(${direction}): ${e.message}", e)
        }
    }

    private fun ensureSoundPool() {
        if (soundPool != null) return
        
        soundPool = android.media.SoundPool.Builder().setMaxStreams(4).build().apply {
            setOnLoadCompleteListener { _, sampleId, status ->
                if (status == 0) {
                    soundLoadedMap[sampleId] = true
                    Log.d(TAG, "🎵 音效加载成功: ID=$sampleId")
                } else {
                    Log.e(TAG, "❌ 音效加载失败: ID=$sampleId, Status=$status")
                }
            }
        }
        
        soundIdLeft = soundPool?.load(context, R.raw.left, 1)
        soundIdRight = soundPool?.load(context, R.raw.right, 1)
        soundIdLeftConfirm = soundPool?.load(context, R.raw.left_confirm, 1)
        soundIdRightConfirm = soundPool?.load(context, R.raw.right_confirm, 1)
        soundIdGoto = soundPool?.load(context, R.raw.go_to, 1)
    }
    
    /**
     * 方案3：远距离超车支持（提前超车，提高通行效率）
     */
    private fun checkEarlyOvertakeConditions(data: XiaogeVehicleData): Boolean {
        // 道路类型检查已移除（carrotMan.roadcate 不再可用）
        // 远距离超车功能在所有道路类型上启用
        
        val carState = data.carState ?: return false
        val lead0 = data.modelV2?.lead0 ?: return false
        
        val vEgoKph = carState.vEgo * 3.6f
        val leadSpeedKph = lead0.v * 3.6f
        val leadDistance = lead0.x
        
        // 条件1：前车最低速度检查（避免堵车）
        if (leadSpeedKph < EARLY_OVERTAKE_MIN_LEAD_SPEED_KPH) return false
        
        // 条件2：前车速度 ≤ 80% 本车速度（🆕 使用自适应比例: E）
        val speedRatio = if (vEgoKph > 0.1f) leadSpeedKph / vEgoKph else 1.0f
        val adaptiveRatio = config.getAdaptiveParameter("EARLY_OVERTAKE_SPEED_RATIO", EARLY_OVERTAKE_SPEED_RATIO)
        if (speedRatio > adaptiveRatio) return false
        
        // 条件3：速度差 ≥ 20 km/h
        val speedDiff = vEgoKph - leadSpeedKph
        if (speedDiff < EARLY_OVERTAKE_MIN_SPEED_DIFF_KPH) return false
        
        // 条件4：距离在 30-100 米范围内
        if (leadDistance < EARLY_OVERTAKE_MIN_DISTANCE || leadDistance > EARLY_OVERTAKE_MAX_DISTANCE) return false
        
        Log.i(TAG, "🚀 远距离超车触发: 前车${leadSpeedKph.toInt()}km/h vs 本车${vEgoKph.toInt()}km/h (慢${speedDiff.toInt()}km/h, 距离${leadDistance.toInt()}m)")
        return true
    }
    
    /**
     * 方案5：记录超车开始（用于返回原车道策略）
     * 优化：使用横向距离而非绝对车道号
     */
    private fun recordOvertakeStart(direction: String, data: XiaogeVehicleData, currentLane: Int) {
        // 记录原车道索引 (🆕 适配：用于本地判断变道进度)
        originalLaneIndex = currentLane
        
        // 记录原车道位置（使用横向距离，更准确）
        if (originalLanePosition == 0f) {
            val carState = data.carState
            // 使用左侧距离作为参考位置（正值表示在车道中心左侧）
            val leftLatDist = carState?.leftLatDist ?: 0f
            originalLanePosition = leftLatDist
            laneMemoryStartTime = System.currentTimeMillis()
            Log.d(TAG, "🎯 开始原车道记忆: 位置${originalLanePosition.toInt()}cm, 车道索引: $originalLaneIndex, 方向: $direction")
        }
        
        // 更新净变道数
        when (direction.uppercase()) {
            "LEFT" -> netLaneChanges++
            "RIGHT" -> netLaneChanges--
        }
        
        // 重置超越完成计时器
        overtakeCompleteTimer = 0L
    }
    
    /**
     * 方案5：检查返回原车道条件
     */
    private fun checkReturnConditions(data: XiaogeVehicleData): Boolean {
        // 如果没有记录原车道位置，不需要返回
        if (originalLanePosition == 0f || netLaneChanges == 0) {
            return false
        }
        
        // 检查超时
        if (laneMemoryStartTime > 0 && 
            System.currentTimeMillis() - laneMemoryStartTime > MAX_LANE_MEMORY_TIME_MS) {
            Log.d(TAG, "⏰ 返回超时(30秒)，重置状态")
            resetLaneMemory()
            return false
        }
        
        // 检查是否完全超越
        if (!hasCompletelyOvertaken(data)) {
            return false
        }
        
        // 检查返回效率（需要8 km/h速度优势）
        if (!isReturnEfficient(data)) {
            return false
        }
        
        // 检查返回安全
        if (!isReturnSafe(data)) {
            return false
        }
        
        return true
    }
    
    /**
     * 方案5：判断是否完全超越
     * 优化：不仅检查侧方车辆，还检查原车道前车是否已在后方
     */
    private fun hasCompletelyOvertaken(data: XiaogeVehicleData): Boolean {
        // 使用 modelV2 数据（纯视觉方案）
        val targetSide = if (netLaneChanges > 0) "right" else "left"
        val targetLead = if (targetSide == "right") {
            data.modelV2?.leadRight
        } else {
            data.modelV2?.leadLeft
        }
        
        // 检查原车道前车是否已在后方（优化：结合横向位置判断）
        val lead0 = data.modelV2?.lead0
        if (lead0 != null && lead0.prob > 0.5f) {
            // ✅ 修复：使用横向位置判断前车是否在原车道
            // 根据 Python 端定义：y > 0 表示车辆在右侧，y < 0 表示车辆在左侧
            // netLaneChanges > 0 表示在左侧，原车道在右侧（y > 0）
            // netLaneChanges < 0 表示在右侧，原车道在左侧（y < 0）
            val targetY = if (netLaneChanges > 0) {
                // 在左侧，原车道在右侧，y 应该 > 0
                LANE_CENTER_OFFSET
            } else {
                // 在右侧，原车道在左侧，y 应该 < 0
                -LANE_CENTER_OFFSET
            }
            
            // 检查前车是否在原车道（横向位置接近 targetY）
            // 如果前车距离较近（< 20m）且横向位置接近原车道（|y - targetY| < 1.0m），说明仍在原车道前方
            if (lead0.x < NEAR_LEAD_DISTANCE && abs(lead0.y - targetY) < LANE_MATCH_TOLERANCE) {
                // 前车仍在前方20米内且在原车道，未完全超越
                overtakeCompleteTimer = 0L
                return false
            }
        }
        
        // 目标侧无车或距离很远，已超越
        if (targetLead == null || !targetLead.status || targetLead.dRel > 50f) {
            // 等待一段时间确保完全超越
            if (overtakeCompleteTimer == 0L) {
                overtakeCompleteTimer = System.currentTimeMillis()
            }
            return System.currentTimeMillis() - overtakeCompleteTimer >= OVERTAKE_COMPLETE_DURATION_MS
        }
        
        // 重置计时器
        overtakeCompleteTimer = 0L
        return false
    }
    
    /**
     * 方案5：检查返回效率
     */
    private fun isReturnEfficient(data: XiaogeVehicleData): Boolean {
        val carState = data.carState ?: return false
        val currentSpeed = carState.vEgo * 3.6f
        // 巡航速度已移除（carrotMan.desiredSpeed 不再可用），使用当前速度作为参考
        
        // 获取目标车道（返回方向）的速度预期
        // 使用 modelV2 数据（纯视觉方案）
        val targetSide = if (netLaneChanges > 0) "right" else "left"
        val targetLead = if (targetSide == "right") {
            data.modelV2?.leadRight
        } else {
            data.modelV2?.leadLeft
        }
        
        val targetSpeed = if (targetLead == null || !targetLead.status) {
            // 目标车道无车，假设可以达到更高速度（当前速度 + 10 km/h）
            currentSpeed + TARGET_SPEED_BOOST_KPH
        } else {
            // ✅ 修复：目标车道有车，预期速度受前车限制
            // vRel 是相对速度 (m/s)，需要加上本车速度才是目标车道前车的绝对速度
            (carState.vEgo + targetLead.vRel) * 3.6f
        }
        
        // 当前车道的预期速度
        val lead0 = data.modelV2?.lead0
        val currentSpeedExpected = if (lead0 == null) {
            currentSpeed  // 无前车时使用当前速度
        } else {
            lead0.v * 3.6f
        }
        
        // 需要至少8 km/h的速度优势（🆕 使用自适应阈值: B + E）
        val baseAdvantage = RETURN_MIN_SPEED_ADVANTAGE_KPH
        val adaptiveAdvantage = config.getAdaptiveParameter("RETURN_MIN_SPEED_ADVANTAGE", baseAdvantage)
        
        // 🆕 动态调整：如果在超车道时间过长，逐步降低返回门槛 (B)
        val timeInOvertakeLane = if (laneMemoryStartTime > 0) System.currentTimeMillis() - laneMemoryStartTime else 0L
        val timeBonus = when {
            timeInOvertakeLane > 30000L -> 10.0f // 超过30秒，大幅降低门槛
            timeInOvertakeLane > 15000L -> {
                // 15秒到30秒之间，从 2km/h 线性增加到 10km/h
                2.0f + (timeInOvertakeLane - 15000f) / 15000f * 8.0f
            }
            else -> 0f
        }
        
        val finalThreshold = (adaptiveAdvantage - timeBonus).coerceAtLeast(1.0f) // 最低保留 1km/h 优势
        val speedAdvantage = targetSpeed - currentSpeedExpected
        
        if (speedAdvantage < finalThreshold) {
            // logThrottled("return_eff", "⏳ 返回效率不足: 优势 ${speedAdvantage.toInt()}km/h < 阈值 ${finalThreshold.toInt()}km/h", Log.DEBUG)
            return false
        }
        return true
    }
    
    /**
     * 方案5：检查返回安全
     */
    private fun isReturnSafe(data: XiaogeVehicleData): Boolean {
        // 使用 modelV2 数据（纯视觉方案）
        val targetSide = if (netLaneChanges > 0) "right" else "left"
        val targetLead = if (targetSide == "right") {
            data.modelV2?.leadRight
        } else {
            data.modelV2?.leadLeft
        }
        
        val blindspot = if (targetSide == "right") {
            data.carState?.rightBlindspot ?: false
        } else {
            data.carState?.leftBlindspot ?: false
        }
        
        // 盲区检查
        if (blindspot) {
            return false
        }
        
        // 目标车道无车，安全返回
        if (targetLead == null || !targetLead.status) {
            return true
        }
        
        // 目标车道有车，判断是否安全
        val carState = data.carState ?: return false
        val currentSpeed = carState.vEgo * 3.6f
        val targetRelativeSpeed = targetLead.vRel * 3.6f
        
        // 目标车道车辆比我们快+5km/h以上，且距离安全
        if (targetRelativeSpeed > SAFE_PASS_REL_SPEED_KPH) {
            val safeDistance = kotlin.math.max(SAFE_DISTANCE_MIN, currentSpeed * SAFE_DISTANCE_FACTOR)
            return targetLead.dRel > safeDistance
        }
        
        // 目标车道车辆距离超过50米，安全返回
        if (targetLead.dRel > 50f) {
            return true
        }
        
        return false
    }
    
    /**
     * 方案5：重置车道记忆
     */
    private fun resetLaneMemory() {
        originalLanePosition = 0f
        netLaneChanges = 0
        laneMemoryStartTime = 0L
        overtakeCompleteTimer = 0L
    }
    
    /**
     * 🆕 创建超车状态数据
     * @param statusText 状态文本
     * @param canOvertake 是否可以超车
     * @param lastDirection 最后超车方向
     * @param blockingReason 阻止超车的原因
     * @param cooldownRemaining 冷却剩余时间（毫秒），如果为null则自动计算
     */
    private fun createOvertakeStatus(
        data: XiaogeVehicleData,
        statusText: String,
        canOvertake: Boolean,
        lastDirection: String?,
        blockingReason: String? = null,  // 🆕 阻止超车的原因
        cooldownRemaining: Long? = null, // 🆕 冷却剩余时间（毫秒），如果为null则自动计算
        currentLane: Int = 0,           // 🆕 当前车道
        totalLanes: Int = 0,            // 🆕 总车道数
        laneReminder: String? = null    // 🆕 车道提醒
    ): OvertakeStatusData {
        // 🆕 自动计算冷却剩余时间（如果未指定）
        val calculatedCooldown = cooldownRemaining ?: run {
            if (lastOvertakeActionTime > 0) {
                val elapsed = System.currentTimeMillis() - lastOvertakeActionTime
                if (elapsed < OVERTAKE_ACTION_COOLDOWN_MS) {
                    OVERTAKE_ACTION_COOLDOWN_MS - elapsed
                } else {
                    null  // 冷却已完成
                }
            } else {
                null  // 没有操作记录
            }
        }
        
        return OvertakeStatusData(
            statusText = statusText,
            canOvertake = canOvertake,
            cooldownRemaining = calculatedCooldown,
            lastDirection = lastDirection ?: lastOvertakeDirection,
            blockingReason = blockingReason,
            currentLane = currentLane,
            totalLanes = totalLanes,
            laneReminder = laneReminder
        )
    }
    
    /**
     * 🆕 检查待执行的变道（延迟执行机制）
     * 如果超过2.5秒且条件仍满足，则执行变道；如果条件不满足，则取消
     * @param data 车辆数据
     * @param overtakeMode 超车模式
     * @param roadType 道路类型（高德地图 ROAD_TYPE）。如果为null，则不检查道路类型（向后兼容）
     * @return 如果有待执行变道，返回状态数据；否则返回null
     */
    private fun checkPendingLaneChange(
        data: XiaogeVehicleData, 
        overtakeMode: Int, 
        roadType: Int?,
        currentLane: Int,
        totalLanes: Int,
        laneReminder: String?
    ): OvertakeStatusData? {
        val pending = pendingLaneChange ?: return null
        
        val now = System.currentTimeMillis()
        val elapsed = now - pending.startTime
        
        // 如果还未到2.5秒，继续等待
        if (elapsed < LANE_CHANGE_DELAY_MS) {
            val remainingSeconds = (LANE_CHANGE_DELAY_MS - elapsed) / 1000
            return createOvertakeStatus(
                data,
                "准备变道 (${remainingSeconds}秒)",
                true,
                pending.direction,
                currentLane = currentLane,
                totalLanes = totalLanes,
                laneReminder = laneReminder
            )
        }
        
        // 已超过2.5秒，检查条件是否仍满足
        // 1. 检查前置条件
        val prerequisites = checkPrerequisites(data, roadType)
        if (prerequisites is CheckResult.Fail) {
            // 前置条件不满足，取消变道
            Log.w(TAG, "❌ 待执行变道取消：前置条件不满足 - ${prerequisites.reason}")
            cancelPendingLaneChange()
            return createOvertakeStatus(
                data,
                "监控中",
                false,
                null,
                blockingReason = prerequisites.reason,
                currentLane = currentLane,
                totalLanes = totalLanes,
                laneReminder = laneReminder
            )
        }
        
        // 2. 检查是否需要超车
        val overtakeCheck = shouldOvertake(data)
        if (overtakeCheck is CheckResult.Fail) {
            // 不需要超车，取消变道
            Log.w(TAG, "❌ 待执行变道取消：不需要超车 - ${overtakeCheck.reason}")
            cancelPendingLaneChange()
            return createOvertakeStatus(
                data,
                "监控中",
                false,
                null,
                blockingReason = overtakeCheck.reason,
                currentLane = currentLane,
                totalLanes = totalLanes,
                laneReminder = laneReminder
            )
        }
        
        // 3. 检查变道方向是否仍然可行
        val decision = checkOvertakeConditions(data)
        if (decision == null || decision.direction != pending.direction) {
            // 变道方向不可行或方向改变，取消变道
            val reason = if (decision == null) {
                "变道条件不满足"
            } else {
                "变道方向改变 (${pending.direction} -> ${decision.direction})"
            }
            Log.w(TAG, "❌ 待执行变道取消：$reason")
            cancelPendingLaneChange()
            return createOvertakeStatus(
                data,
                "监控中",
                false,
                null,
                blockingReason = reason,
                currentLane = currentLane,
                totalLanes = totalLanes,
                laneReminder = laneReminder
            )
        }
        
        // 4. 所有条件满足，执行变道
        val direction = pending.direction
        sendLaneChangeCommand(direction, playSound = false)  // 不播放音效，因为已在2.5秒前播放
        recordOvertakeStart(direction, data, currentLane)
        lastOvertakeResult = OvertakeResult.PENDING
        pendingOvertakeStartTime = now
        cancelPendingLaneChange()  // 清除待执行状态
        
        val carState = data.carState
        val lead0 = data.modelV2?.lead0
        val logContext = if (carState != null && lead0 != null) {
            ", 本车${(carState.vEgo * 3.6f).toInt()}km/h, 前车${(lead0.v * 3.6f).toInt()}km/h, 距离${lead0.x.toInt()}m"
        } else {
            ""
        }
        Log.i(TAG, "✅ 执行变道命令: $direction, 原因: ${decision.reason}$logContext")
        
        return createOvertakeStatus(data, "变道中", false, direction, 
            currentLane = currentLane, totalLanes = totalLanes, laneReminder = laneReminder)
    }
    
    /**
     * 🆕 取消待执行的变道
     */
    private fun cancelPendingLaneChange() {
        pendingLaneChange?.let {
            Log.d(TAG, "🔄 取消待执行变道: ${it.direction}")
            pendingLaneChange = null
        }
    }
    
    /**
     * 🆕 生成阻止超车的原因（当左右都不能超车时）
     * 优化：复用 checkFeasibility 逻辑，消除冗余
     */
    private fun generateBlockingReason(data: XiaogeVehicleData): String? {
        val carState = data.carState ?: return "车辆状态缺失"
        val modelV2 = data.modelV2 ?: return "模型数据缺失"
        
        val leftResult = checkLeftOvertakeFeasibility(carState, modelV2)
        val rightResult = checkRightOvertakeFeasibility(carState, modelV2)
        
        return when {
            leftResult is CheckResult.Fail -> leftResult.reason
            rightResult is CheckResult.Fail -> rightResult.reason
            else -> "左右车道均不可用"
        }
    }

    /**
     * 🆕 检查 TBT 方向偏好 (D)
     * 根据导航文本判断接下来的走向
     */
    private fun checkTbtDirectionBias(data: XiaogeVehicleData, tbtMainText: String?): String? {
        if (tbtMainText == null || data.tbtDist <= 0 || data.tbtDist > TBT_BIAS_DISTANCE_THRESHOLD) return null
        
        return when {
            tbtMainText.contains("左") -> "LEFT"
            tbtMainText.contains("右") || tbtMainText.contains("出口") || tbtMainText.contains("驶出") -> "RIGHT"
            else -> null
        }
    }

    /**
     * 清理资源，释放 SoundPool 等原生资源
     */
    fun cleanup() {
        try {
            cancelPendingLaneChange()
            resetLaneMemory()
            logThrottleMap.clear()
            // 释放 SoundPool 原生资源（避免内存泄漏）
            soundPool?.release()
            soundPool = null
            soundIdLeft = null
            soundIdRight = null
            soundIdLeftConfirm = null
            soundIdRightConfirm = null
            soundIdGoto = null
            soundLoadedMap.clear()
            lastLaneReminderTime = 0L
            Log.i(TAG, "🧹 自动超车管理器资源已清理")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ 清理自动超车管理器资源失败: ${e.message}")
        }
    }
}
