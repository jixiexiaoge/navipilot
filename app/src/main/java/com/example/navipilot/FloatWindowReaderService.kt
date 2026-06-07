package com.example.navipilot

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 魔行星悬浮窗读取服务
 *
 * 读取 com.mojoxing.light 的悬浮窗控件内容，
 * 获取红绿灯倒计时、路口距离等信息，
 * 通过 StateFlow 对外暴露供 UI 消费。
 */
class FloatWindowReaderService : AccessibilityService() {

    companion object {
        private const val TAG = "FloatReader"
        private const val TARGET_PACKAGE = "com.mojoxing.light"

        /** 单例引用，用于在 Activity 中读取数据 */
        var instance: FloatWindowReaderService? = null

        /** 红绿灯倒计时数据 */
        private val _trafficData = MutableStateFlow(TrafficLightData())
        val trafficData = _trafficData.asStateFlow()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scanRunnable = object : Runnable {
        override fun run() {
            scanAllWindows()
            handler.postDelayed(this, 500) // 每 500ms 扫描一次
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 事件驱动 + 轮询双保险
    }

    override fun onInterrupt() {
        Log.d(TAG, "无障碍服务中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(scanRunnable)
        instance = null
        Log.i(TAG, "无障碍服务已销毁")
    }

    /**
     * 扫描所有窗口，找到目标 App 的悬浮窗并提取距离
     */
    private fun scanAllWindows() {
        val allWindows = windows ?: return
        for (window in allWindows) {
            val pkgName = window.root?.packageName?.toString() ?: continue
            if (pkgName != TARGET_PACKAGE) continue

            Log.i(TAG, "✅ 找到魔行星窗口")
            // 直接遍历控件树找 "数字+米" 的组合
            val distance = findDistance(window.root)
            if (distance > 0) {
                val prev = _trafficData.value
                _trafficData.value = prev.copy(distance = distance)
                Log.d(TAG, "📏 距离: ${distance}m")
            }
        }
    }

    /**
     * 递归查找 "数字 + 米" 文本组合
     */
    private fun findDistance(node: android.view.accessibility.AccessibilityNodeInfo?): Int {
        if (node == null) return 0
        val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
        // 查找类似 "135米" 或 "135 米" 的文本
        if (text.contains("米") || text.contains("m")) {
            val digits = text.replace("米", "").replace("m", "").trim()
            val dist = digits.toIntOrNull()
            if (dist != null && dist in 1..9999) return dist
        }
        // 递归子节点
        for (i in 0 until node.childCount) {
            val result = findDistance(node.getChild(i))
            if (result > 0) return result
        }
        return 0
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        handler.post(scanRunnable)
        Log.i(TAG, "✅ 无障碍服务已连接，目标: $TARGET_PACKAGE")
    }

    // ===================== 数据模型 =====================

    /** 单条车道方向的红绿灯数据 */
    data class LaneData(
        val direction: String = "--",     // "左转"/"直行"/"右转"
        val stateText: String = "--",     // "红灯"/"绿灯"/"黄灯"
        val state: TrafficLightState = TrafficLightState.UNKNOWN,
        val countdown: Int = 0
    )

    /** 红绿灯路口完整数据 */
    data class TrafficLightData(
        val state: TrafficLightState = TrafficLightState.UNKNOWN,
        val stateText: String = "--",
        val countdown: Int = 0,
        val distance: Int = 0,
        /** 多车道方向数据（魔行星支持多个方向） */
        val lanes: List<LaneData> = emptyList()
    )

    enum class TrafficLightState(val zhName: String) {
        UNKNOWN("未知"), RED("红灯"), GREEN("绿灯"), YELLOW("黄灯")
    }
}
