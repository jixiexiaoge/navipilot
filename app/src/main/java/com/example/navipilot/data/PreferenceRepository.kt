package com.example.navipilot.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 统一的偏好设置仓库
 * 
 * 功能：
 * - 响应式数据访问（Flow）
 * - 内存缓存（避免重复读取）
 * - 批量更新
 * - 类型安全
 * 
 * 使用示例：
 * ```kotlin
 * val repo = PreferenceRepository(context)
 * 
 * // 响应式读取
 * repo.overtakeMode.collect { mode ->
 *     println("当前模式: $mode")
 * }
 * 
 * // 写入
 * repo.setOvertakeMode(2)
 * 
 * // 批量更新
 * repo.updateSettings(overtakeMode = 2, minSpeed = 70)
 * ```
 */
class PreferenceRepository(context: Context) {
    
    private val prefs: SharedPreferences = 
        context.getSharedPreferences("CarrotAmap", Context.MODE_PRIVATE)
    
    // ===============================
    // 缓存的设置值（避免重复读取）
    // ===============================
    
    private val _overtakeMode = MutableStateFlow(prefs.getInt("overtake_mode", 1))  // 默认值改为 1（拨杆超车）
    val overtakeMode: Flow<Int> = _overtakeMode.asStateFlow()
    
    private val _minOvertakeSpeed = MutableStateFlow(prefs.getFloat("overtake_param_min_speed_kph", 65f))
    val minOvertakeSpeed: Flow<Float> = _minOvertakeSpeed.asStateFlow()
    
    private val _speedDiffThreshold = MutableStateFlow(prefs.getFloat("overtake_param_speed_diff_kph", 20f))
    val speedDiffThreshold: Flow<Float> = _speedDiffThreshold.asStateFlow()
    
    private val _deviceId = MutableStateFlow(prefs.getString("device_id", "") ?: "")
    val deviceId: Flow<String> = _deviceId.asStateFlow()
    
    private val _userType = MutableStateFlow(prefs.getInt("user_type", 0))
    val userType: Flow<Int> = _userType.asStateFlow()
    
    init {
        // 监听 SharedPreferences 变化并更新缓存
        prefs.registerOnSharedPreferenceChangeListener { _, key ->
            updateCacheForKey(key)
        }
    }
    
    private fun updateCacheForKey(key: String?) {
        when (key) {
            "overtake_mode" -> _overtakeMode.value = prefs.getInt(key, 1)  // 默认值改为 1（拨杆超车）
            "overtake_param_min_speed_kph" -> _minOvertakeSpeed.value = prefs.getFloat(key, 65f)
            "overtake_param_speed_diff_kph" -> _speedDiffThreshold.value = prefs.getFloat(key, 20f)
            "device_id" -> _deviceId.value = prefs.getString(key, "") ?: ""
            "user_type" -> _userType.value = prefs.getInt(key, 0)
        }
    }
    
    // ===============================
    // 写入方法（带缓存更新）
    // ===============================
    
    fun setOvertakeMode(mode: Int) {
        prefs.edit().putInt("overtake_mode", mode).apply()
        _overtakeMode.value = mode
    }
    
    fun setMinOvertakeSpeed(speed: Float) {
        prefs.edit().putFloat("overtake_param_min_speed_kph", speed).apply()
        _minOvertakeSpeed.value = speed
    }
    
    fun setSpeedDiffThreshold(threshold: Float) {
        prefs.edit().putFloat("overtake_param_speed_diff_kph", threshold).apply()
        _speedDiffThreshold.value = threshold
    }
    
    fun setDeviceId(id: String) {
        prefs.edit().putString("device_id", id).apply()
        _deviceId.value = id
    }
    
    fun setUserType(type: Int) {
        prefs.edit().putInt("user_type", type).apply()
        _userType.value = type
    }
    
    /**
     * 批量更新（使用 apply 而非 commit）
     */
    fun updateSettings(
        overtakeMode: Int? = null,
        minSpeed: Float? = null,
        speedDiff: Float? = null
    ) {
        prefs.edit().apply {
            overtakeMode?.let { 
                putInt("overtake_mode", it)
                _overtakeMode.value = it
            }
            minSpeed?.let { 
                putFloat("overtake_param_min_speed_kph", it)
                _minOvertakeSpeed.value = it
            }
            speedDiff?.let { 
                putFloat("overtake_param_speed_diff_kph", it)
                _speedDiffThreshold.value = it
            }
        }.apply()
    }
    
    /**
     * 同步读取（使用缓存值，避免磁盘IO）
     */
    fun getOvertakeModeSync(): Int = _overtakeMode.value
    fun getMinOvertakeSpeedSync(): Float = _minOvertakeSpeed.value
    fun getSpeedDiffThresholdSync(): Float = _speedDiffThreshold.value
    fun getDeviceIdSync(): String = _deviceId.value
    fun getUserTypeSync(): Int = _userType.value

    // ===============================
    // 通用 setter/getter (用于灵活扩展)
    // ===============================

    fun setInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
        updateCacheForKey(key)
    }

    fun getInt(key: String, defaultValue: Int): Int = prefs.getInt(key, defaultValue)

    fun setFloat(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
        updateCacheForKey(key)
    }

    fun getFloat(key: String, defaultValue: Float): Float = prefs.getFloat(key, defaultValue)

    fun setBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
        updateCacheForKey(key)
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean = prefs.getBoolean(key, defaultValue)

    fun setString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
        updateCacheForKey(key)
    }

    fun getString(key: String, defaultValue: String): String = prefs.getString(key, defaultValue) ?: defaultValue
}
