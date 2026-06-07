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
     * 扫描所有窗口，找到目标 App 的悬浮窗
     */
    private fun scanAllWindows() {
        val allWindows = windows ?: return
        for (window in allWindows) {
            val pkgName = window.root?.packageName?.toString() ?: continue
            if (pkgName != TARGET_PACKAGE) continue

            window.root?.let { rootNode ->
                textNodes.clear()
                parseTrafficData(rootNode)
                resolveCollectedData()
            }
        }
    }

    /**
     * 遍历控件树，提取红绿灯和路口数据
     *
     * 魔行星悬浮窗结构示例：
     *   "135米"          → 距离
     *   "←" + 红色 + "21" → 左转红灯 21s
     *   "↑" + 红色 + "21" → 直行红灯 21s
     */
    private fun parseTrafficData(node: AccessibilityNodeInfo) {
        val text = node.text?.toString()
        val contentDesc = node.contentDescription?.toString()
        val displayText = (text ?: contentDesc ?: "").trim()
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        val viewId = node.viewIdResourceName ?: ""

        // 收集所有文本节点及其屏幕坐标位置
        if (displayText.isNotEmpty()) {
            collectTextNode(displayText, bounds.centerX(), bounds.centerY())
        }

        // 递归子节点
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { parseTrafficData(it) }
        }
    }

    // 按 y 坐标分组的文本节点（同一行的为一组）
    private data class TextNode(val text: String, val x: Int, val y: Int)
    private val textNodes = mutableListOf<TextNode>()

    private fun collectTextNode(text: String, x: Int, y: Int) {
        textNodes.add(TextNode(text, x, y))
    }

    /**
     * 收集完一轮后按行分组解析
     */
    private fun resolveCollectedData() {
        if (textNodes.isEmpty()) return
        val nodes = textNodes.toList()
        textNodes.clear()

        // 按 y 坐标分组（同一行容差 20px）
        val rows = nodes.groupBy { it.y / 20 }
        var distance = 0
        val lanes = mutableListOf<LaneData>()

        for ((_, rowNodes) in rows) {
            val texts = rowNodes.sortedBy { it.x }.map { it.text }

            // 整行拼接
            val line = texts.joinToString(" ")

            // 检测距离: 包含"米"或纯数字+单位
            if (line.contains("米") || line.contains("m")) {
                val digits = line.filter { it.isDigit() || it == '.' }
                distance = digits.toDoubleOrNull()?.toInt() ?: 0
                continue
            }

            // 检测车道方向行: 箭头 + 颜色 + 数字
            val arrow = texts.firstOrNull { it in listOf("←", "↑", "→", "↙", "↗", "↘", "<", "^", ">") }
            val number = texts.firstOrNull { it.toIntOrNull() != null }

            if (arrow != null && number != null) {
                val sec = number.toIntOrNull() ?: 0
                // 判断颜色: 检查是否有红色/绿色描述文本，或从 contentDesc 推断
                val isRed = texts.any { it.contains("红") || it.contains("red") }
                val isGreen = texts.any { it.contains("绿") || it.contains("green") }
                val state = when {
                    isRed -> TrafficLightState.RED
                    isGreen -> TrafficLightState.GREEN
                    else -> TrafficLightState.RED // 默认红灯
                }
                val direction = when (arrow) {
                    "←", "<" -> "左转"
                    "↑", "^" -> "直行"
                    "→", ">" -> "右转"
                    else -> "未知"
                }
                lanes.add(LaneData(direction, state.zhName, state, sec))
            }
        }

        // 更新状态
        val prev = _trafficData.value
        _trafficData.value = prev.copy(
            distance = if (distance > 0) distance else prev.distance,
            lanes = lanes.ifEmpty { prev.lanes }
        )
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
