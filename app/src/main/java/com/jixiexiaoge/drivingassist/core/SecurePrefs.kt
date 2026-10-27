package com.jixiexiaoge.drivingassist.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 安全存储工具
 *
 * 使用 `EncryptedSharedPreferences`（AES256-SIV 键 + AES256-GCM 值）存储敏感数据
 * （auth token、OAuth secrets 等）。
 *
 * ## 降级策略（重要）
 * Keystore 在部分设备上会初始化失败（root / 定制 ROM 尤其常见，而本项目依赖 Shizuku，
 * 这类设备占比不低）。失败时**不再静默**回退到同名明文文件，而是：
 * 1. 把 [isEncrypted] 置 false，让调用方能够感知当前处于明文态；
 * 2. 明文回退写到**独立的** [PLAINTEXT_FILE_NAME]，绝不与加密文件同名
 *    （同名会导致：加密文件已存在时，普通 SP 读到的是密文乱码，写入还可能把密文覆盖成明文）；
 * 3. 敏感凭证的调用方应改用 [getEncryptedOnly]，拿不到加密存储就走「不可用」分支，
 *    宁可不持久化，也不要明文落盘。
 */
object SecurePrefs {
    private const val TAG = "SecurePrefs"
    private const val FILE_NAME = "secure_prefs"
    private const val PLAINTEXT_FILE_NAME = "secure_prefs_plaintext"

    @Volatile
    private var instance: SharedPreferences? = null

    /** 当前存储是否处于加密状态；false = 已降级为明文（敏感写入应拒绝） */
    @Volatile
    var isEncrypted: Boolean = false
        private set

    /**
     * 取加密存储；若加密不可用则返回**明文回退**（非敏感数据可继续用）。
     */
    fun get(context: Context): SharedPreferences {
        instance?.let { return it }
        val plaintextFallback = {
            context.getSharedPreferences(PLAINTEXT_FILE_NAME, Context.MODE_PRIVATE).also {
                instance = it
                isEncrypted = false
            }
        }
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context, FILE_NAME, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            ).also {
                instance = it
                isEncrypted = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "加密存储初始化失败，降级到明文存储($PLAINTEXT_FILE_NAME): ${e.message}", e)
            plaintextFallback()
        }
    }

    /**
     * 仅供**敏感凭证**使用：加密不可用返回 null，调用方据此跳过持久化并提示重新登录 / 注册，
     * 而不是把 token 明文写到磁盘上。
     */
    fun getEncryptedOnly(context: Context): SharedPreferences? {
        val prefs = get(context)
        if (isEncrypted) return prefs
        Log.w(TAG, "加密存储不可用，拒绝为敏感数据提供明文落盘入口")
        return null
    }
}
