package com.example.navipilot.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 模型下载管理器
 * 单例模式，负责模型文件的下载、存储和状态管理
 */
class ModelDownloadManager private constructor(
    private val context: Context,
    private val prefs: SharedPreferences
) {
    companion object {
        private const val TAG = "ModelDownloadManager"
        private const val DOWNLOADED_MODELS_KEY = "downloaded_models"
        private const val TIMEOUT_MS = 60_000L

        @Volatile
        private var instance: ModelDownloadManager? = null

        fun getInstance(context: Context, prefs: SharedPreferences): ModelDownloadManager {
            return instance ?: synchronized(this) {
                instance ?: ModelDownloadManager(context, prefs).also { instance = it }
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    // 下载状态（modelId -> ModelDownloadState）
    private val _downloadStates = MutableStateFlow<Map<String, ModelDownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, ModelDownloadState>> = _downloadStates.asStateFlow()

    // 已下载模型列表
    private val _downloadedModels = MutableStateFlow<List<DownloadedModel>>(emptyList())
    val downloadedModels: StateFlow<List<DownloadedModel>> = _downloadedModels.asStateFlow()

    init {
        loadDownloadedModels()
    }

    private fun loadDownloadedModels() {
        val json = prefs.getString(DOWNLOADED_MODELS_KEY, null)
        if (json != null) {
            try {
                val models = parseDownloadedModels(json)
                _downloadedModels.value = models
                // 更新下载状态
                val states = models.associate { model ->
                    model.modelId to ModelDownloadState(
                        modelId = model.modelId,
                        status = ModelDownloadStatus.DOWNLOADED
                    )
                }
                _downloadStates.value = states
            } catch (e: Exception) {
                Log.e(TAG, "解析已下载模型失败: ${e.message}")
            }
        }
    }

    private fun parseDownloadedModels(json: String): List<DownloadedModel> {
        val result = mutableListOf<DownloadedModel>()
        try {
            // 简单 JSON 解析：[{modelId:xxx,name:xxx,...}]
            val cleanJson = json.trim()
            if (cleanJson.startsWith("[") && cleanJson.endsWith("]")) {
                val content = cleanJson.substring(1, cleanJson.length - 1)
                if (content.isNotEmpty()) {
                    // 分割每个对象
                    val objects = splitJsonArray(content)
                    for (obj in objects) {
                        parseDownloadedModel(obj)?.let { result.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "JSON解析失败: ${e.message}")
        }
        return result
    }

    private fun parseDownloadedModel(json: String): DownloadedModel? {
        try {
            val modelId = extractJsonString(json, "modelId") ?: return null
            val name = extractJsonString(json, "name") ?: ""
            val version = extractJsonInt(json, "version", 1)
            val policyFilePath = extractJsonString(json, "policyFilePath") ?: ""
            val visionFilePath = extractJsonString(json, "visionFilePath") ?: ""
            val policySize = extractJsonLong(json, "policySize", 0L)
            val visionSize = extractJsonLong(json, "visionSize", 0L)
            val downloadedAt = extractJsonLong(json, "downloadedAt", System.currentTimeMillis())

            return DownloadedModel(
                modelId = modelId,
                name = name,
                version = version,
                policyFilePath = policyFilePath,
                visionFilePath = visionFilePath,
                policySize = policySize,
                visionSize = visionSize,
                downloadedAt = downloadedAt
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析模型失败: ${e.message}")
            return null
        }
    }

    private fun extractJsonString(json: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*\"([^\"]*)\""
        val regex = Regex(pattern)
        return regex.find(json)?.groupValues?.get(1)
    }

    private fun extractJsonInt(json: String, key: String, default: Int): Int {
        val pattern = "\"$key\"\\s*:\\s*(\\d+)"
        val regex = Regex(pattern)
        return regex.find(json)?.groupValues?.get(1)?.toIntOrNull() ?: default
    }

    private fun extractJsonLong(json: String, key: String, default: Long): Long {
        val pattern = "\"$key\"\\s*:\\s*(\\d+)"
        val regex = Regex(pattern)
        return regex.find(json)?.groupValues?.get(1)?.toLongOrNull() ?: default
    }

    private fun splitJsonArray(content: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var start = -1
        for (i in content.indices) {
            val c = content[i]
            when (c) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        result.add(content.substring(start, i + 1))
                        start = -1
                    }
                }
            }
        }
        return result
    }

    private fun saveDownloadedModels(models: List<DownloadedModel>) {
        val json = buildString {
            append("[")
            models.forEachIndexed { index, model ->
                append("{")
                append("\"modelId\":\"${model.modelId}\",")
                append("\"name\":\"${model.name}\",")
                append("\"version\":${model.version},")
                append("\"policyFilePath\":\"${model.policyFilePath}\",")
                append("\"visionFilePath\":\"${model.visionFilePath}\",")
                append("\"policySize\":${model.policySize},")
                append("\"visionSize\":${model.visionSize},")
                append("\"downloadedAt\":${model.downloadedAt}")
                append("}")
                if (index < models.size - 1) append(",")
            }
            append("]")
        }
        prefs.edit().putString(DOWNLOADED_MODELS_KEY, json).apply()
        _downloadedModels.value = models
    }

    /**
     * 检查模型是否已下载
     */
    fun isModelDownloaded(modelId: String): Boolean {
        return _downloadedModels.value.any { it.modelId == modelId }
    }

    /**
     * 下载模型
     * 顺序下载 driving_policy.onnx 和 driving_vision.onnx
     */
    suspend fun downloadModel(
        modelInfo: ModelInfo,
        onProgress: (ModelDownloadState) -> Unit
    ): Result<DownloadedModel> = withContext(Dispatchers.IO) {
        val modelId = modelInfo.id
        val modelDir = File(context.filesDir, "models/$modelId")
        if (!modelDir.exists()) modelDir.mkdirs()

        // 初始化下载状态
        var currentState = ModelDownloadState(
            modelId = modelId,
            status = ModelDownloadStatus.DOWNLOADING
        )
        updateState(currentState)
        onProgress(currentState)

        // 下载 driving_policy.onnx
        val policyFile = File(modelDir, "driving_policy.onnx")
        val policyResult = downloadFile(
            modelId = modelId,
            fileName = "driving_policy.onnx",
            url = modelInfo.baseUrl.toJihulabUrl() + "/driving_policy.onnx",
            destFile = policyFile,
            expectedSize = modelInfo.files["driving_policy.onnx"]?.size ?: 0L
        ) { progress ->
            currentState = currentState.copy(policyProgress = progress)
            updateState(currentState)
            onProgress(currentState)
        }

        if (policyResult.isFailure) {
            currentState = currentState.copy(
                status = ModelDownloadStatus.FAILED,
                errorMessage = policyResult.exceptionOrNull()?.message
            )
            updateState(currentState)
            onProgress(currentState)
            return@withContext Result.failure(policyResult.exceptionOrNull()!!)
        }

        // 下载 driving_vision.onnx
        val visionFile = File(modelDir, "driving_vision.onnx")
        val visionResult = downloadFile(
            modelId = modelId,
            fileName = "driving_vision.onnx",
            url = modelInfo.baseUrl.toJihulabUrl() + "/driving_vision.onnx",
            destFile = visionFile,
            expectedSize = modelInfo.files["driving_vision.onnx"]?.size ?: 0L
        ) { progress ->
            currentState = currentState.copy(visionProgress = progress)
            updateState(currentState)
            onProgress(currentState)
        }

        if (visionResult.isFailure) {
            currentState = currentState.copy(
                status = ModelDownloadStatus.FAILED,
                errorMessage = visionResult.exceptionOrNull()?.message
            )
            updateState(currentState)
            onProgress(currentState)
            return@withContext Result.failure(visionResult.exceptionOrNull()!!)
        }

        // 下载成功，保存记录
        val downloadedModel = DownloadedModel(
            modelId = modelId,
            name = modelInfo.name,
            version = modelInfo.minimumSelectorVersion,
            policyFilePath = policyFile.absolutePath,
            visionFilePath = visionFile.absolutePath,
            policySize = modelInfo.files["driving_policy.onnx"]?.size ?: 0L,
            visionSize = modelInfo.files["driving_vision.onnx"]?.size ?: 0L,
            downloadedAt = System.currentTimeMillis()
        )

        // 更新已下载列表
        val currentList = _downloadedModels.value.toMutableList()
        currentList.removeAll { it.modelId == modelId }
        currentList.add(downloadedModel)
        saveDownloadedModels(currentList)

        currentState = currentState.copy(status = ModelDownloadStatus.DOWNLOADED)
        updateState(currentState)
        onProgress(currentState)

        Log.i(TAG, "模型下载完成: $modelId")
        Result.success(downloadedModel)
    }

    /**
     * 删除已下载的模型
     */
    suspend fun deleteModel(modelId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val modelDir = File(context.filesDir, "models/$modelId")
            if (modelDir.exists()) {
                modelDir.deleteRecursively()
            }

            // 从已下载列表移除
            val currentList = _downloadedModels.value.toMutableList()
            currentList.removeAll { it.modelId == modelId }
            saveDownloadedModels(currentList)

            // 更新状态
            val states = _downloadStates.value.toMutableMap()
            states.remove(modelId)
            _downloadStates.value = states

            Log.i(TAG, "模型删除成功: $modelId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "删除模型失败: ${e.message}")
            false
        }
    }

    private fun updateState(state: ModelDownloadState) {
        val states = _downloadStates.value.toMutableMap()
        states[state.modelId] = state
        _downloadStates.value = states
    }

    /**
     * 单文件下载，使用 OkHttp + 流式写入
     */
    private suspend fun downloadFile(
        modelId: String,
        fileName: String,
        url: String,
        destFile: File,
        expectedSize: Long,
        onProgress: (DownloadProgress) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "开始下载: $url")

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "HTTP ${response.code} 下载失败: $url")
                return@withContext Result.failure(Exception("HTTP ${response.code}"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Empty response body"))
            val totalBytes = body.contentLength().takeIf { it > 0 } ?: expectedSize

            destFile.parentFile?.mkdirs()

            var bytesDownloaded = 0L
            val buffer = ByteArray(8192)
            var lastProgressUpdate = 0L

            body.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    while (true) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break

                        output.write(buffer, 0, bytesRead)
                        bytesDownloaded += bytesRead

                        // 节流：每 1% 或 512KB 更新一次
                        val now = System.currentTimeMillis()
                        val progressPercent = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt() else 0
                        if (bytesDownloaded - lastProgressUpdate > 512 * 1024 ||
                            (totalBytes > 0 && progressPercent - ((lastProgressUpdate * 100) / totalBytes) >= 1)) {
                            onProgress(DownloadProgress(
                                modelId = modelId,
                                fileName = fileName,
                                bytesDownloaded = bytesDownloaded,
                                totalBytes = totalBytes,
                                percentage = progressPercent
                            ))
                            lastProgressUpdate = bytesDownloaded
                        }
                    }
                }
            }

            // 最终进度更新
            onProgress(DownloadProgress(
                modelId = modelId,
                fileName = fileName,
                bytesDownloaded = bytesDownloaded,
                totalBytes = totalBytes,
                percentage = 100
            ))

            Log.i(TAG, "文件下载完成: ${destFile.name}, size: $bytesDownloaded")
            Result.success(destFile)
        } catch (e: Exception) {
            Log.e(TAG, "下载异常: ${e.message}", e)
            destFile.delete()
            Result.failure(e)
        }
    }
}