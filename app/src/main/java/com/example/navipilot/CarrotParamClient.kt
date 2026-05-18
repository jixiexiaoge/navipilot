package com.example.navipilot

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Carrot HTTP参数客户端
 * 通过carrot_server.py的REST API (port 7000) 读写comma3设备参数
 * 替代ZMQ方式，延迟更低（毫秒级 vs 秒级）
 *
 * API端点:
 * - POST /api/param_set  body: {"name":"ExperimentalMode","value":1}
 * - GET  /api/params_bulk?names=ExperimentalMode
 */
class CarrotParamClient(
    private val deviceIP: String
) {
    companion object {
        private const val TAG = "CarrotParamClient"
        private const val PORT = 7000
        private const val TIMEOUT_MS = 3000L
    }

    private val baseUrl = "http://$deviceIP:$PORT"

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /**
     * 设置参数值
     */
    suspend fun setParam(name: String, value: Any): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("name", name)
                put("value", value)
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/api/param_set")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val resp = JSONObject(responseBody)
                if (resp.optBoolean("ok", false)) {
                    Log.i(TAG, "✅ 参数设置成功: $name=$value")
                    Result.success(true)
                } else {
                    val error = resp.optString("error", "未知错误")
                    Log.e(TAG, "❌ 参数设置失败: $error")
                    Result.failure(Exception(error))
                }
            } else {
                Log.e(TAG, "❌ HTTP错误: ${response.code} $responseBody")
                Result.failure(Exception("HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 参数设置异常: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 批量读取参数值
     */
    suspend fun getParams(vararg names: String): Result<Map<String, Any>> = withContext(Dispatchers.IO) {
        try {
            val namesStr = names.joinToString(",")
            val request = Request.Builder()
                .url("$baseUrl/api/params_bulk?names=$namesStr")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val resp = JSONObject(responseBody)
                if (resp.optBoolean("ok", false)) {
                    val values = resp.getJSONObject("values")
                    val result = mutableMapOf<String, Any>()
                    for (key in values.keys()) {
                        result[key] = values.get(key)
                    }
                    Result.success(result)
                } else {
                    Result.failure(Exception(resp.optString("error", "未知错误")))
                }
            } else {
                Result.failure(Exception("HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 参数读取异常: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 设置实验模式开关（便捷方法）
     */
    suspend fun setExperimentalMode(enabled: Boolean): Result<Boolean> {
        return setParam("ExperimentalMode", if (enabled) 1 else 0)
    }

    fun getDeviceIP(): String = deviceIP
}
