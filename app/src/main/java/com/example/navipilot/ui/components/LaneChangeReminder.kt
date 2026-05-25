package com.example.navipilot.ui.components

import android.content.Context
import android.media.SoundPool
import android.util.Log
import com.example.navipilot.LaneInfo
import com.example.navipilot.R

/**
 * 变道提醒管理器
 *
 * 当当前车道不在导航推荐车道内时，自动播放左/右变道提示音。
 * 参考 [com.example.navipilot.AutoOvertakeManager] 的 SoundPool 模式。
 */
class LaneChangeReminder(private val context: Context) {

    companion object {
        private const val TAG = "LaneChangeReminder"
        private const val REMINDER_COOLDOWN_MS = 15000L
    }

    private var soundPool: SoundPool? = null
    private var soundIdLeft: Int? = null
    private var soundIdRight: Int? = null
    private val soundLoadedMap = mutableMapOf<Int, Boolean>()

    private var lastReminderTime = 0L
    private var lastReminderDirection: String? = null

    /**
     * 检查并触发变道提醒
     * @return true 如果本次触发了提醒，false 如果被冷却抑制或无变化
     */
    fun checkAndRemind(
        laneConfig: List<LaneInfo>,
        currentLane: Int
    ): Boolean {
        if (currentLane <= 0 || laneConfig.size < 2) return false

        val recommendedLanes = laneConfig.filter { it.isRecommended }
        if (recommendedLanes.isEmpty()) return false

        val inRecommendedLane = recommendedLanes.any {
            it.driveWayNumber == currentLane || (laneConfig.indexOf(it) + 1 == currentLane)
        }
        if (inRecommendedLane) return false

        // 确定变道方向
        val firstRecIdx = laneConfig.indexOfFirst { it.isRecommended }
        val direction = if (currentLane < firstRecIdx + 1) "RIGHT" else "LEFT"

        val now = System.currentTimeMillis()
        if (now - lastReminderTime < REMINDER_COOLDOWN_MS) return false

        // 同一方向冷却期内不重复提醒
        if (direction == lastReminderDirection && now - lastReminderTime < REMINDER_COOLDOWN_MS * 2) {
            return false
        }

        lastReminderTime = now
        lastReminderDirection = direction
        playSound(direction)
        Log.i(TAG, "变道提醒: $direction (当前车道=$currentLane, 推荐索引=${firstRecIdx + 1})")
        return true
    }

    private fun playSound(direction: String) {
        try {
            ensureSoundPool()
            val id = when (direction.uppercase()) {
                "LEFT" -> soundIdLeft
                "RIGHT" -> soundIdRight
                else -> return
            } ?: return
            if (soundLoadedMap[id] == true) {
                soundPool?.play(id, 1f, 1f, 1, 0, 1f)
            }
        } catch (e: Exception) {
            Log.e(TAG, "播放变道音效失败: ${e.message}")
        }
    }

    private fun ensureSoundPool() {
        if (soundPool != null) return
        soundPool = SoundPool.Builder().setMaxStreams(2).build().apply {
            setOnLoadCompleteListener { _, sampleId, status ->
                soundLoadedMap[sampleId] = (status == 0)
            }
        }
        soundIdLeft = soundPool?.load(context, R.raw.left, 1)
        soundIdRight = soundPool?.load(context, R.raw.right, 1)
    }

    fun cleanup() {
        try {
            soundPool?.release()
            soundPool = null
            soundLoadedMap.clear()
        } catch (_: Exception) { }
    }
}
