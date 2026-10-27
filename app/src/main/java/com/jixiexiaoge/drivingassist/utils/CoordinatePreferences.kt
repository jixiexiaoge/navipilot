package com.jixiexiaoge.drivingassist.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * GPS坐标精确存储工具类
 * 
 * 问题：使用Float存储GPS坐标会导致精度损失（约20-50厘米）
 * 解决：使用String存储Double值，保持完整精度
 * 
 * Float精度：约7位有效数字
 * Double精度：约15位有效数字
 * GPS需求：至少8位小数（厘米级精度）
 */
object CoordinatePreferences {
    private const val TAG = "CoordinatePreferences"
    
    /**
     * 保存坐标（使用String存储Double，保持精度）
     */
    fun saveCoordinate(prefs: SharedPreferences, key: String, value: Double) {
        prefs.edit().putString(key, value.toString()).apply()
    }
    
    /**
     * 读取坐标（兼容旧的Float格式）
     * 
     * 优先读取String格式（新格式，高精度）
     * 如果不存在，尝试读取Float格式（旧格式，低精度）
     */
    fun getCoordinate(prefs: SharedPreferences, key: String, defaultValue: Double = 0.0): Double {
        // 检查key是否存在
        if (!prefs.contains(key)) {
            return defaultValue
        }
        
        // 1. 尝试读取String格式（新格式）
        try {
            val stringValue = prefs.getString(key, null)
            if (stringValue != null) {
                return stringValue.toDoubleOrNull() ?: defaultValue
            }
        } catch (e: ClassCastException) {
            // 不是String类型，继续尝试Float
            Log.d(TAG, "🔄 $key 不是String格式，尝试Float格式")
        }
        
        // 2. 尝试读取Float格式（旧格式，兼容性）
        try {
            val floatValue = prefs.getFloat(key, defaultValue.toFloat())
            if (floatValue != 0f) {
                Log.w(TAG, "⚠️ 检测到旧格式坐标: $key = $floatValue (Float)，建议迁移到新格式")
                return floatValue.toDouble()
            }
        } catch (e: ClassCastException) {
            // 既不是String也不是Float，返回默认值
            Log.e(TAG, "❌ 坐标类型无法识别: $key", e)
        }
        
        return defaultValue
    }
    
    /**
     * 迁移旧的Float格式坐标到新的String格式
     * 
     * 使用场景：应用启动时一次性迁移所有坐标
     */
    fun migrateFloatCoordinates(context: Context, prefsName: String, keys: List<String>) {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        var migratedCount = 0
        
        keys.forEach { key ->
            // 检查是否是Float类型
            if (prefs.contains(key)) {
                try {
                    val floatValue = prefs.getFloat(key, 0f)
                    if (floatValue != 0f) {
                        // 转换为String存储
                        val doubleValue = floatValue.toDouble()
                        editor.remove(key) // 删除Float版本
                        editor.putString(key, doubleValue.toString()) // 存储String版本
                        migratedCount++
                        Log.i(TAG, "✅ 迁移坐标: $key = $floatValue (Float) -> $doubleValue (String)")
                    }
                } catch (e: ClassCastException) {
                    // 已经是String类型，跳过
                }
            }
        }
        
        if (migratedCount > 0) {
            editor.apply()
            Log.i(TAG, "🎉 坐标迁移完成: $migratedCount 个坐标已升级为高精度格式")
        }
    }
    
    /**
     * 保存地址（名称+坐标）
     */
    fun saveAddress(
        prefs: SharedPreferences,
        prefix: String,
        name: String,
        latitude: Double,
        longitude: Double
    ) {
        prefs.edit().apply {
            putString("${prefix}_name", name)
            putString("${prefix}_lat", latitude.toString())
            putString("${prefix}_lon", longitude.toString())
            apply()
        }
    }
    
    /**
     * 读取地址（名称+坐标）
     */
    data class Address(val name: String, val latitude: Double, val longitude: Double)
    
    fun getAddress(prefs: SharedPreferences, prefix: String): Address? {
        val name = prefs.getString("${prefix}_name", null) ?: return null
        val lat = getCoordinate(prefs, "${prefix}_lat", 0.0)
        val lon = getCoordinate(prefs, "${prefix}_lon", 0.0)

        return if (lat != 0.0 && lon != 0.0) {
            Address(name, lat, lon)
        } else {
            null
        }
    }

    }
