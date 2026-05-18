package com.example.navipilot

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import java.security.MessageDigest
import kotlin.random.Random

/**
 * 设备管理器
 * 负责设备ID生成、存储和使用时长统计
 */
class DeviceManager(private val context: Context) {
    
    companion object {
        private const val TAG = "DeviceManager"
        private const val PREFS_NAME = "Navipilot_Device"
        
        private fun getDeviceSerial(): String {
            return try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    android.os.Build.getSerial()
                } else {
                    @Suppress("DEPRECATION")
                    android.os.Build.SERIAL
                }
            } catch (e: Exception) {
                "unknown"
            }
        }
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_USAGE_DURATION = "usage_duration"
        private const val KEY_APP_START_TIME = "app_start_time"
    }
    
    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * 获取或生成设备ID
     */
    fun getDeviceId(): String {
        val existingId = sharedPreferences.getString(KEY_DEVICE_ID, null)
        return if (existingId != null) {
            existingId
        } else {
            val newId = generatePersistentDeviceId()
            sharedPreferences.edit().putString(KEY_DEVICE_ID, newId).apply()
            Log.i(TAG, "🆕 生成持久化设备ID: $newId")
            newId
        }
    }

    private fun generatePersistentDeviceId(): String {
        return try {
            val androidId = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ANDROID_ID
            )
            val deviceInfo = "${android.os.Build.MODEL}_${android.os.Build.MANUFACTURER}_${android.os.Build.DEVICE}_${getDeviceSerial()}"
            val combined = "${androidId}_${deviceInfo}"
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(combined.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(12)
            hash.uppercase()
        } catch (e: Exception) {
            Log.e(TAG, "❌ 持久化设备ID生成失败，使用备用方案: ${e.message}", e)
            generateFallbackDeviceId()
        }
    }
    
    private fun generateFallbackDeviceId(): String {
        return try {
            val deviceInfo = "${android.os.Build.MODEL}_${android.os.Build.MANUFACTURER}_${android.os.Build.DEVICE}_${getDeviceSerial()}_${android.os.Build.BOARD}_${android.os.Build.HARDWARE}"
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(deviceInfo.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(12)
            hash.uppercase()
        } catch (e: Exception) {
            val timestamp = System.currentTimeMillis().toString().takeLast(8)
            val random = Random.nextInt(1000, 9999).toString()
            "$timestamp$random".uppercase()
        }
    }
    
    /**
     * 记录应用启动（仅记录启动时间，用于计算时长）
     */
    fun recordAppStart() {
        sharedPreferences.edit()
            .putLong(KEY_APP_START_TIME, System.currentTimeMillis())
            .apply()
        Log.i(TAG, "📊 记录应用启动时间")
    }
    
    /**
     * 记录应用使用时长（暂停/销毁时调用）
     */
    fun recordAppUsage() {
        val storedStartTime = sharedPreferences.getLong(KEY_APP_START_TIME, 0)
        if (storedStartTime > 0) {
            val sessionMinutes = (System.currentTimeMillis() - storedStartTime) / (1000 * 60)
            if (sessionMinutes > 0) {
                val total = sharedPreferences.getLong(KEY_USAGE_DURATION, 0) + sessionMinutes
                sharedPreferences.edit().putLong(KEY_USAGE_DURATION, total).commit()
                Log.i(TAG, "📊 记录使用时长: ${sessionMinutes}分钟，累计: ${total}分钟")
            }
        }
    }
    
    /**
     * 获取累计使用时长（分钟），包含当前会话
     */
    fun getTotalUsageDurationMinutes(): Long {
        val stored = sharedPreferences.getLong(KEY_USAGE_DURATION, 0)
        val startTime = sharedPreferences.getLong(KEY_APP_START_TIME, 0)
        val currentSession = if (startTime > 0) (System.currentTimeMillis() - startTime) / (1000 * 60) else 0
        return stored + currentSession
    }
    
    fun cleanup() {
        Log.i(TAG, "🧹 清理设备管理器资源")
    }
}
