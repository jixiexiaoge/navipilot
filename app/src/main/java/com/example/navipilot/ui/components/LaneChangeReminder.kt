package com.example.navipilot.ui.components

import android.content.Context
import android.media.SoundPool
import android.speech.tts.TextToSpeech
import android.util.Log
import com.example.navipilot.LaneInfo
import com.example.navipilot.R
import java.util.Locale

/**
 * 变道提醒管理器 — 转弯类型感知版
 *
 * 功能：
 *  - 根据前方 [turnType]（nTBTTurnType）和距离 [distToTurn] 判断当前车辆是否在正确车道
 *  - 识别非机动车道（自行车道）：右转时不把最右侧非机动车道纳入推荐
 *  - 距离自适应冷却期：越接近路口提醒越频繁
 *  - TTS 语音提醒（"前方500米左转，请向左变道"），同时播放方向音效
 *  - 直到变道到正确车道才停止提醒
 *
 * 转弯类型编码（nTBTTurnType）：
 *  51 = 直行，12 = 左转，13 = 右转，102 = 左前，101 = 右前，
 *  17 = 左后（急左），19 = 右后（急右），14 = 掉头，131/132 = 环岛
 */
class LaneChangeReminder(private val context: Context) {

    // ── 转弯类型分组 ───────────────────────────────────────────
    companion object {
        private const val TAG = "LaneChangeReminder"

        /** 左转系：需要左侧车道 */
        val TURN_LEFT_TYPES = setOf(12, 102, 17)
        /** 右转系：需要右侧车道 */
        val TURN_RIGHT_TYPES = setOf(13, 101, 19)
        /** 掉头：仅最左侧车道 */
        val TURN_UTURN_TYPES = setOf(14)
        /** 直行系：不强制变道 */
        val TURN_STRAIGHT_TYPES = setOf(51, 1, 5)
        /** 环岛：视具体车道推荐决定 */
        val TURN_ROUNDABOUT_TYPES = setOf(131, 132, 133, 134, 135, 136)

        // 距离档位（米）
        private const val DIST_TOO_FAR   = 1500   // 超过此距离不提醒
        private const val DIST_EARLY     = 800    // 早期提醒
        private const val DIST_NORMAL    = 400    // 正常提醒
        private const val DIST_URGENT    = 150    // 紧急提醒
        private const val DIST_CRITICAL  = 60     // 极紧急提醒

        // 各档冷却时间（毫秒）
        private const val COOLDOWN_EARLY    = 30_000L
        private const val COOLDOWN_NORMAL   = 15_000L
        private const val COOLDOWN_URGENT   =  8_000L
        private const val COOLDOWN_CRITICAL =  4_000L

        /**
         * 判断某条车道是否为非机动车道（自行车/摩托）。
         *
         * 高德 SDK trafficLaneType 编码规则（部分已知）：
         *   bit6 (64) 置位 → 非机动车道
         *   trafficLaneType == 7 也常见于"纯非机动车道"场景
         * driveWayLaneExtended 为二进制字符串，全 '0' 时表示无机动车方向允许。
         */
        fun isNonMotorizedLane(lane: LaneInfo): Boolean {
            // 方法1：trafficLaneType 位标记
            if (lane.trafficLaneType and 64 != 0) return true
            if (lane.trafficLaneType == 7) return true

            // 方法2：extended 二进制串全0（无机动车方向）且 id 不是普通车道
            val ext = lane.driveWayLaneExtended
            if (ext.isNotBlank() && ext.all { it == '0' } && ext.length >= 4) {
                // 全零扩展位 + id 数字较大时视为非机动
                val idNum = lane.id.toIntOrNull() ?: -1
                if (idNum > 32) return true
            }

            // 方法3：id 明确包含"non_motor"等关键词（某些 SDK 版本）
            if (lane.id.contains("non_motor", ignoreCase = true) ||
                lane.id.contains("bike", ignoreCase = true)) return true

            return false
        }
    }

    // ── TTS ────────────────────────────────────────────────────
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // ── SoundPool（方向音效） ───────────────────────────────────
    private var soundPool: SoundPool? = null
    private var soundIdLeft: Int? = null
    private var soundIdRight: Int? = null
    private val soundLoadedMap = mutableMapOf<Int, Boolean>()

    // ── 状态 ───────────────────────────────────────────────────
    private var lastReminderTime = 0L
    private var lastDirection: String? = null
    /** 当前是否处于"需要变道"状态（用于连续检查） */
    private var isInChangeState = false

    init {
        initTts()
    }

    private fun initTts() {
        try {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val result = tts?.setLanguage(Locale.CHINA)
                    ttsReady = (result != TextToSpeech.LANG_MISSING_DATA &&
                                result != TextToSpeech.LANG_NOT_SUPPORTED)
                    if (!ttsReady) {
                        // 回退到默认语言
                        ttsReady = true
                    }
                    Log.d(TAG, "TTS 初始化成功，ttsReady=$ttsReady")
                } else {
                    Log.w(TAG, "TTS 初始化失败: status=$status，仅使用音效")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "TTS 创建失败: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────────────────
    // 主入口
    // ──────────────────────────────────────────────────────────

    /**
     * 分析当前车道是否合适，并在需要时触发提醒。
     *
     * @param laneConfig   SDK 提供的车道列表（含 isRecommended / trafficLaneType）
     * @param currentLane  当前车道（1-based，从左计数；0 = 未知）
     * @param turnType     前方转弯类型（nTBTTurnType），-1 = 未知
     * @param distToTurn   距离路口距离（米），0 = 未知
     * @return true 如果本次触发了提醒
     */
    fun checkAndRemind(
        laneConfig: List<LaneInfo>,
        currentLane: Int,
        turnType: Int = -1,
        distToTurn: Int = 0
    ): Boolean {
        // 车道数 < 2 或车道位置未知 → 不处理
        if (currentLane <= 0 || laneConfig.size < 2) {
            isInChangeState = false
            return false
        }

        // 距离太远 → 不提醒
        if (distToTurn > DIST_TOO_FAR && distToTurn > 0) {
            isInChangeState = false
            return false
        }

        // 直行且前方较远 → 不强制提醒
        if (turnType in TURN_STRAIGHT_TYPES && distToTurn > DIST_NORMAL) {
            isInChangeState = false
            return false
        }

        val analysis = analyzeLane(laneConfig, currentLane, turnType)

        if (!analysis.needsChange) {
            isInChangeState = false
            return false
        }

        isInChangeState = true

        // 计算本次冷却期
        val cooldown = getCooldown(distToTurn)
        val now = System.currentTimeMillis()
        if (now - lastReminderTime < cooldown) return false

        // 同方向冷却内不重复
        if (analysis.direction == lastDirection && now - lastReminderTime < cooldown * 2) {
            return false
        }

        lastReminderTime = now
        lastDirection = analysis.direction

        // 生成语音文本
        val voiceText = buildVoiceText(analysis.direction!!, turnType, distToTurn)
        speak(voiceText)
        playDirectionSound(analysis.direction)

        Log.i(TAG, "🔔 变道提醒: ${analysis.direction} dist=${distToTurn}m " +
            "turnType=$turnType 当前=$currentLane 目标=${analysis.targetRange}")
        return true
    }

    /**
     * 检查是否已经变到了正确车道（连续调用，用于解除提醒状态）。
     */
    fun isInCorrectLane(
        laneConfig: List<LaneInfo>,
        currentLane: Int,
        turnType: Int = -1
    ): Boolean {
        if (currentLane <= 0 || laneConfig.size < 2) return true
        return !analyzeLane(laneConfig, currentLane, turnType).needsChange
    }

    // ──────────────────────────────────────────────────────────
    // 车道分析核心
    // ──────────────────────────────────────────────────────────

    data class LaneAnalysis(
        val needsChange: Boolean,
        val direction: String?,      // "LEFT" / "RIGHT"
        val targetRange: IntRange?,  // 目标车道范围（1-based）
        val reason: String = ""
    )

    /**
     * 判断当前车道是否合适，返回分析结果。
     *
     * 优先级：
     *   1. SDK 已给出 isRecommended 标志 → 直接用
     *   2. 根据 turnType 推断目标车道区间
     */
    private fun analyzeLane(
        laneConfig: List<LaneInfo>,
        currentLane: Int,
        turnType: Int
    ): LaneAnalysis {
        val total = laneConfig.size

        // ── 优先：SDK 推荐标志 ──────────────────────────────────
        val sdkRecommended = laneConfig.filter { it.isRecommended }
        if (sdkRecommended.isNotEmpty()) {
            val recIndices = sdkRecommended.map { laneConfig.indexOf(it) + 1 }.toSet()
            val inRec = currentLane in recIndices
            if (inRec) return LaneAnalysis(false, null, null, "SDK推荐:已在推荐车道")

            // 确定变道方向
            val minRec = recIndices.min()
            val maxRec = recIndices.max()
            val dir = when {
                currentLane < minRec -> "RIGHT"  // 当前在推荐区间左侧，需向右
                currentLane > maxRec -> "LEFT"   // 当前在推荐区间右侧，需向左
                else -> "RIGHT"
            }
            return LaneAnalysis(true, dir, minRec..maxRec, "SDK推荐:需变道")
        }

        // ── 无SDK推荐，按转弯类型推断 ──────────────────────────

        // 右侧非机动车道边界（有效最右车道索引，1-based）
        val effectiveRightmost = findEffectiveRightmost(laneConfig)

        return when {
            turnType in TURN_UTURN_TYPES -> {
                // 掉头：只能在最左一道（车道1）
                val target = 1..1
                if (currentLane in target)
                    LaneAnalysis(false, null, null, "掉头:已在最左道")
                else
                    LaneAnalysis(true, "LEFT", target, "掉头:需到最左道")
            }

            turnType in TURN_LEFT_TYPES -> {
                // 左转：左侧1~2条车道（至多2条，且不超过总数/2+1）
                val maxLeftLanes = if (total <= 3) 1 else 2
                val target = 1..maxLeftLanes
                if (currentLane in target)
                    LaneAnalysis(false, null, null, "左转:已在左侧车道")
                else
                    LaneAnalysis(true, "LEFT", target, "左转:需向左变道")
            }

            turnType in TURN_RIGHT_TYPES -> {
                // 右转：右侧1~2条可行车道（排除非机动车道）
                val minRightLane = if (total <= 3) effectiveRightmost
                                   else maxOf(effectiveRightmost - 1, (total / 2) + 1)
                val target = minRightLane..effectiveRightmost
                if (currentLane in target)
                    LaneAnalysis(false, null, null, "右转:已在右侧车道")
                else
                    LaneAnalysis(true, "RIGHT", target, "右转:需向右变道")
            }

            turnType in TURN_ROUNDABOUT_TYPES -> {
                // 环岛：一般在右侧（非最右排除非机动车道）
                val target = maxOf(1, effectiveRightmost - 1)..effectiveRightmost
                if (currentLane in target)
                    LaneAnalysis(false, null, null, "环岛:已在合适车道")
                else
                    LaneAnalysis(true, if (currentLane < target.first) "RIGHT" else "LEFT",
                        target, "环岛:需调整车道")
            }

            else -> {
                // 直行或未知：不强制变道
                LaneAnalysis(false, null, null, "无需变道(turnType=$turnType)")
            }
        }
    }

    /**
     * 找出最右侧有效机动车道（排除非机动车道），返回 1-based 索引。
     */
    private fun findEffectiveRightmost(laneConfig: List<LaneInfo>): Int {
        for (i in laneConfig.indices.reversed()) {
            if (!isNonMotorizedLane(laneConfig[i])) {
                return i + 1  // 1-based
            }
        }
        return laneConfig.size  // fallback：全部无法判断时用最右
    }

    // ──────────────────────────────────────────────────────────
    // 冷却 & 紧迫度
    // ──────────────────────────────────────────────────────────

    /**
     * 根据距离返回适当的冷却期（ms）。
     * 距离越近，冷却越短，提醒越频繁。
     */
    private fun getCooldown(distToTurn: Int): Long {
        if (distToTurn <= 0) return COOLDOWN_NORMAL
        return when {
            distToTurn <= DIST_CRITICAL -> COOLDOWN_CRITICAL
            distToTurn <= DIST_URGENT   -> COOLDOWN_URGENT
            distToTurn <= DIST_NORMAL   -> COOLDOWN_NORMAL
            else                        -> COOLDOWN_EARLY
        }
    }

    /**
     * 根据方向、转弯类型和距离构造语音提醒文本。
     */
    private fun buildVoiceText(direction: String, turnType: Int, distToTurn: Int): String {
        val isLeft = direction == "LEFT"

        val turnDesc = when {
            turnType in TURN_UTURN_TYPES      -> "掉头"
            turnType in TURN_LEFT_TYPES && isLeft  -> "左转"
            turnType in TURN_RIGHT_TYPES && !isLeft -> "右转"
            turnType in TURN_ROUNDABOUT_TYPES -> "进入环岛"
            else -> if (isLeft) "左转" else "右转"
        }

        val changeDir = if (isLeft) "向左变道" else "向右变道"

        return when {
            distToTurn <= DIST_CRITICAL ->
                "请立即${changeDir}！前方${turnDesc}"
            distToTurn <= DIST_URGENT ->
                "请尽快${changeDir}，前方${turnDesc}"
            distToTurn > 0 ->
                "前方${distToTurn}米${turnDesc}，请${changeDir}"
            else ->
                "请${changeDir}"
        }
    }

    // ──────────────────────────────────────────────────────────
    // 音频输出
    // ──────────────────────────────────────────────────────────

    private fun speak(text: String) {
        if (!ttsReady || tts == null) {
            Log.d(TAG, "TTS 未就绪，跳过语音: $text")
            return
        }
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lane_reminder_${System.currentTimeMillis()}")
        } catch (e: Exception) {
            Log.w(TAG, "TTS speak 失败: ${e.message}")
        }
    }

    private fun playDirectionSound(direction: String) {
        try {
            ensureSoundPool()
            val id = when (direction.uppercase()) {
                "LEFT"  -> soundIdLeft
                "RIGHT" -> soundIdRight
                else    -> return
            } ?: return
            if (soundLoadedMap[id] == true) {
                soundPool?.play(id, 0.6f, 0.6f, 1, 0, 1f)
            }
        } catch (e: Exception) {
            Log.w(TAG, "播放方向音效失败: ${e.message}")
        }
    }

    private fun ensureSoundPool() {
        if (soundPool != null) return
        soundPool = SoundPool.Builder().setMaxStreams(2).build().apply {
            setOnLoadCompleteListener { _, sampleId, status ->
                soundLoadedMap[sampleId] = (status == 0)
            }
        }
        soundIdLeft  = soundPool?.load(context, R.raw.left, 1)
        soundIdRight = soundPool?.load(context, R.raw.right, 1)
    }

    // ──────────────────────────────────────────────────────────
    // 资源释放
    // ──────────────────────────────────────────────────────────

    fun cleanup() {
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
            ttsReady = false
        } catch (_: Exception) {}
        try {
            soundPool?.release()
            soundPool = null
            soundLoadedMap.clear()
        } catch (_: Exception) {}
        isInChangeState = false
    }
}
