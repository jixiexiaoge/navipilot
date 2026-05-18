package com.example.navipilot.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 安全存储工具
 * 使用 EncryptedSharedPreferences 存储敏感数据（auth token、OAuth secrets 等）
 * 降级方案：加密失败时回退到普通 SharedPreferences
 */
object SecurePrefs {
    private const val TAG = "SecurePrefs"
    private const val FILE_NAME = "secure_prefs"
    private var instance: SharedPreferences? = null

    fun get(context: Context): SharedPreferences {
        instance?.let { return it }
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context, FILE_NAME, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ).also { instance = it }
        } catch (e: Exception) {
            Log.e(TAG, "加密存储初始化失败，降级到普通存储: ${e.message}")
            context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).also { instance = it }
        }
    }
}
