package com.example.navipilot.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 轻量级匿名使用分析
 * 仅统计功能使用频率，不收集任何个人信息
 *
 * 数据项：
 * - 启动次数、导航模式使用次数（AMAP/Tencent/OSM）
 * - 功能使用次数（超车、驾驶报告、配置导入等）
 * - 每日活跃天数
 *
 * 隐私：
 * - 所有数据匿名（仅 device_id hash）
 * - 本地存储，定期批量上报
 * - 用户可在隐私设置中关闭
 */
object AppAnalytics {
    private const val TAG = "AppAnalytics"
    private const val PREFS_NAME = "app_analytics"
    private const val UPLOAD_INTERVAL_MS = 24 * 3600 * 1000L // 24小时上报一次
    private const val REPORT_URL_PRIMARY = "http://31.97.51.107:8600/api/analytics"
    private const val REPORT_URL_FALLBACK = "https://md.jixiexiaoge.com/api/analytics"

    private var prefs: SharedPreferences? = null
    private var deviceId: String = ""
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var enabled = true

    fun init(context: Context, deviceId: String) {
        this.deviceId = deviceId
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        enabled = prefs?.getBoolean("analytics_enabled", true) ?: true
        increment("app_launch")
        recordActiveDay()
        // 检查是否需要上报
        scope.launch { uploadIfNeeded() }
        Log.i(TAG, "✅ 匿名分析已初始化 (enabled=$enabled)")
    }

    /** 记录事件（+1） */
    fun track(event: String) {
        if (!enabled) return
        increment(event)
    }

    /** 记录导航模式使用 */
    fun trackNavMode(mode: String) {
        if (!enabled) return
        increment("nav_$mode")
    }

    /** 设置是否启用 */
    fun setEnabled(value: Boolean) {
        enabled = value
        prefs?.edit()?.putBoolean("analytics_enabled", value)?.apply()
    }

    fun isEnabled(): Boolean = enabled

    /** 获取所有统计数据（用于调试/展示） */
    fun getStats(): Map<String, Long> {
        val p = prefs ?: return emptyMap()
        return p.all.filterValues { it is Long || it is Int }
            .mapValues { (it.value as? Long) ?: (it.value as? Int)?.toLong() ?: 0L }
            .filterKeys { !it.startsWith("_") && it != "analytics_enabled" }
    }

    // ── 内部实现 ──

    private fun increment(key: String) {
        val p = prefs ?: return
        val current = p.getLong(key, 0)
        p.edit().putLong(key, current + 1).apply()
    }

    private fun recordActiveDay() {
        val p = prefs ?: return
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
            .format(java.util.Date())
        val lastDay = p.getString("_last_active_day", "") ?: ""
        if (today != lastDay) {
            increment("active_days")
            p.edit().putString("_last_active_day", today).apply()
        }
    }

    private suspend fun uploadIfNeeded() {
        val p = prefs ?: return
        val lastUpload = p.getLong("_last_upload", 0)
        if (System.currentTimeMillis() - lastUpload < UPLOAD_INTERVAL_MS) return
        if (!enabled) return

        try {
            val stats = getStats()
            if (stats.isEmpty()) return

            val json = JSONObject().apply {
                put("device_id_hash", deviceId.hashCode().toString(16))
                put("timestamp", System.currentTimeMillis())
                put("stats", JSONObject(stats.mapValues { it.value }))
            }

            val success = upload(json.toString())
            if (success) {
                // 上报成功后重置计数器（保留 active_days）
                val activeDays = p.getLong("active_days", 0)
                val analyticsEnabled = p.getBoolean("analytics_enabled", true)
                p.edit().clear()
                    .putBoolean("analytics_enabled", analyticsEnabled)
                    .putLong("active_days", activeDays)
                    .putLong("_last_upload", System.currentTimeMillis())
                    .apply()
                Log.i(TAG, "📤 分析数据上报成功")
            }
        } catch (e: Exception) {
            Log.w(TAG, "分析数据上报失败: ${e.message}")
        }
    }

    private fun upload(jsonBody: String): Boolean {
        val urls = listOf(REPORT_URL_PRIMARY, REPORT_URL_FALLBACK)
        for (url in urls) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.apply {
                    requestMethod = "POST"
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
                conn.outputStream.use { it.write(jsonBody.toByteArray()) }
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299) return true
            } catch (_: Exception) { /* try next */ }
        }
        return false
    }

    fun cleanup() {
        scope.cancel()
    }
}
