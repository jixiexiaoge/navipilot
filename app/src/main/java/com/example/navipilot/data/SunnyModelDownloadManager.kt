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
 * sunnypilot 模型下载管理器
 * 独立于 ModelDownloadManager，专用于 sunnypilot v20 格式
 */
class SunnyModelDownloadManager private constructor(
    private val context: Context,
    private val prefs: SharedPreferences
) {
    companion object {
        private const val TAG = "SunnyDownloadMgr"
        private const val DOWNLOADED_KEY = "sunny_downloaded_bundles"
        private const val TIMEOUT_MS = 120_000L // 2min，pkl 文件较大

        @Volatile
        private var instance: SunnyModelDownloadManager? = null

        fun getInstance(context: Context, prefs: SharedPreferences): SunnyModelDownloadManager {
            return instance ?: synchronized(this) {
                instance ?: SunnyModelDownloadManager(context, prefs).also { instance = it }
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    // 下载状态（shortName -> SunnyDownloadState）
    private val _downloadStates = MutableStateFlow<Map<String, SunnyDownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, SunnyDownloadState>> = _downloadStates.asStateFlow()

    // 已下载 Bundle 列表
    private val _downloadedBundles = MutableStateFlow<List<SunnyDownloadedBundle>>(emptyList())
    val downloadedBundles: StateFlow<List<SunnyDownloadedBundle>> = _downloadedBundles.asStateFlow()

    init {
        loadDownloaded()
    }

    private fun loadDownloaded() {
        val json = prefs.getString(DOWNLOADED_KEY, null) ?: return
        try {
            val bundles = parseDownloaded(json)
            _downloadedBundles.value = bundles
            val states = bundles.associate { b ->
                b.shortName to SunnyDownloadState(
                    shortName = b.shortName,
                    status = SunnyDownloadStatus.DOWNLOADED
                )
            }
            _downloadStates.value = states
        } catch (e: Exception) {
            Log.e(TAG, "解析已下载 sunny bundle 失败: ${e.message}")
        }
    }

    private fun parseDownloaded(json: String): List<SunnyDownloadedBundle> {
        val result = mutableListOf<SunnyDownloadedBundle>()
        try {
            val clean = json.trim()
            if (clean.startsWith("[") && clean.endsWith("]")) {
                val content = clean.substring(1, clean.length - 1)
                if (content.isNotEmpty()) {
                    splitJsonArray(content).forEach { obj ->
                        parseBundleJson(obj)?.let { result.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "JSON 解析失败: ${e.message}")
        }
        return result
    }

    private fun parseBundleJson(json: String): SunnyDownloadedBundle? {
        return try {
            val shortName = extractJsonString(json, "shortName") ?: return null
            val displayName = extractJsonString(json, "displayName") ?: ""
            val localDirPath = extractJsonString(json, "localDirPath") ?: ""
            val downloadedAt = extractJsonLong(json, "downloadedAt", System.currentTimeMillis())
            val filesJson = extractJsonObject(json, "files") ?: "{}"
            val files = mutableMapOf<String, String>()
            // files 格式: {"fileName":"localPath",...}
            val filePairs = splitTopLevelPairs(filesJson)
            for ((key, value) in filePairs) {
                val v = value.trim().removeSurrounding("\"")
                files[key] = v
            }
            SunnyDownloadedBundle(
                shortName = shortName,
                displayName = displayName,
                localDirPath = localDirPath,
                files = files,
                downloadedAt = downloadedAt
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析 bundle 失败: ${e.message}")
            null
        }
    }

    private fun saveDownloaded(bundles: List<SunnyDownloadedBundle>) {
        val json = buildString {
            append("[")
            bundles.forEachIndexed { i, b ->
                append("{")
                append("\"shortName\":\"${b.shortName}\",")
                append("\"displayName\":\"${b.displayName}\",")
                append("\"localDirPath\":\"${b.localDirPath}\",")
                append("\"downloadedAt\":${b.downloadedAt},")
                append("\"files\":{")
                b.files.entries.forEachIndexed { j, (k, v) ->
                    append("\"$k\":\"$v\"")
                    if (j < b.files.size - 1) append(",")
                }
                append("}")
                append("}")
                if (i < bundles.size - 1) append(",")
            }
            append("]")
        }
        prefs.edit().putString(DOWNLOADED_KEY, json).apply()
        _downloadedBundles.value = bundles
    }

    /**
     * 下载一个 sunny bundle
     * 自动下载所有文件，按类型排列
     */
    suspend fun downloadBundle(
        bundle: SunnyBundle,
        onProgress: (SunnyDownloadState) -> Unit
    ): Result<SunnyDownloadedBundle> = withContext(Dispatchers.IO) {
        val shortName = bundle.shortName
        val bundleDir = File(context.filesDir, "sunny_models/$shortName")
        if (!bundleDir.exists()) bundleDir.mkdirs()

        // 收集所有需下载的文件
        val files = bundle.collectDownloadableFiles()

        // 初始化状态
        var state = SunnyDownloadState(
            shortName = shortName,
            status = SunnyDownloadStatus.DOWNLOADING
        )
        updateState(state)
        onProgress(state)

        val localFiles = mutableMapOf<String, String>() // remoteFileName -> localPath

        for (artifact in files) {
            val remoteFileName = artifact.fileName
            val url = artifact.downloadUri.url.toSunnyMirrorUrl()
            val destFile = File(bundleDir, remoteFileName)

            val result = downloadFile(
                shortName = shortName,
                fileName = remoteFileName,
                url = url,
                destFile = destFile,
                expectedSha256 = artifact.downloadUri.sha256
            ) { progress ->
                val newProgress = state.fileProgress.toMutableMap()
                newProgress[remoteFileName] = progress
                state = state.copy(fileProgress = newProgress)
                updateState(state)
                onProgress(state)
            }

            if (result.isFailure) {
                state = state.copy(
                    status = SunnyDownloadStatus.FAILED,
                    errorMessage = result.exceptionOrNull()?.message
                )
                updateState(state)
                onProgress(state)
                return@withContext Result.failure(result.exceptionOrNull()!!)
            }

            localFiles[remoteFileName] = destFile.absolutePath
        }

        // 全部下载成功
        val downloaded = SunnyDownloadedBundle(
            shortName = shortName,
            displayName = bundle.displayName,
            localDirPath = bundleDir.absolutePath,
            files = localFiles,
            downloadedAt = System.currentTimeMillis()
        )

        val currentList = _downloadedBundles.value.toMutableList()
        currentList.removeAll { it.shortName == shortName }
        currentList.add(downloaded)
        saveDownloaded(currentList)

        state = state.copy(status = SunnyDownloadStatus.DOWNLOADED)
        updateState(state)
        onProgress(state)

        Log.i(TAG, "Bundle 下载完成: $shortName, ${localFiles.size} 个文件")
        Result.success(downloaded)
    }

    /**
     * 删除已下载的 bundle
     */
    suspend fun deleteBundle(shortName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val bundleDir = File(context.filesDir, "sunny_models/$shortName")
            if (bundleDir.exists()) bundleDir.deleteRecursively()

            val currentList = _downloadedBundles.value.toMutableList()
            currentList.removeAll { it.shortName == shortName }
            saveDownloaded(currentList)

            val states = _downloadStates.value.toMutableMap()
            states.remove(shortName)
            _downloadStates.value = states

            Log.i(TAG, "Bundle 删除成功: $shortName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "删除 bundle 失败: ${e.message}")
            false
        }
    }

    /**
     * 检查 bundle 是否已下载
     */
    fun isBundleDownloaded(shortName: String): Boolean {
        return _downloadedBundles.value.any { it.shortName == shortName }
    }

    fun getDownloadedBundle(shortName: String): SunnyDownloadedBundle? {
        return _downloadedBundles.value.find { it.shortName == shortName }
    }

    // ===================== 内部方法 =====================

    private fun updateState(state: SunnyDownloadState) {
        val states = _downloadStates.value.toMutableMap()
        states[state.shortName] = state
        _downloadStates.value = states
    }

    /**
     * 下载单个文件，支持进度回调 + SHA256 校验
     */
    private suspend fun downloadFile(
        shortName: String,
        fileName: String,
        url: String,
        destFile: File,
        expectedSha256: String,
        onProgress: (SunnyFileProgress) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "开始下载: $url")

            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "HTTP ${response.code} $url")
                return@withContext Result.failure(Exception("HTTP ${response.code}"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Empty body"))
            val totalBytes = body.contentLength().takeIf { it > 0 }
                ?: return@withContext Result.failure(Exception("Unknown content length"))

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

                        val now = System.currentTimeMillis()
                        val pct = ((bytesDownloaded * 100) / totalBytes).toInt()
                        if (bytesDownloaded - lastProgressUpdate > 512 * 1024 ||
                            pct - ((lastProgressUpdate * 100) / totalBytes).toInt() >= 1
                        ) {
                            onProgress(SunnyFileProgress(
                                fileName = fileName,
                                bytesDownloaded = bytesDownloaded,
                                totalBytes = totalBytes,
                                percentage = pct
                            ))
                            lastProgressUpdate = bytesDownloaded
                        }
                    }
                }
            }

            // 最终进度
            onProgress(SunnyFileProgress(
                fileName = fileName,
                bytesDownloaded = bytesDownloaded,
                totalBytes = totalBytes,
                percentage = 100
            ))

            // SHA256 校验（可选，有则校验）
            if (expectedSha256.isNotBlank()) {
                val actualSha256 = destFile.computeSha256()
                if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                    destFile.delete()
                    Log.e(TAG, "SHA256 不匹配: $fileName, expected=$expectedSha256, actual=$actualSha256")
                    return@withContext Result.failure(
                        Exception("SHA256 mismatch for $fileName")
                    )
                }
                Log.i(TAG, "SHA256 校验通过: $fileName")
            }

            Log.i(TAG, "下载完成: ${destFile.name}, ${bytesDownloaded}bytes")
            Result.success(destFile)
        } catch (e: Exception) {
            Log.e(TAG, "下载异常: ${e.message}", e)
            destFile.delete()
            Result.failure(e)
        }
    }

    // ===================== JSON 工具 =====================

    private fun extractJsonString(json: String, key: String): String? {
        val regex = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
        return regex.find(json)?.groupValues?.get(1)
    }

    private fun extractJsonLong(json: String, key: String, default: Long): Long {
        val regex = Regex("\"$key\"\\s*:\\s*(\\d+)")
        return regex.find(json)?.groupValues?.get(1)?.toLongOrNull() ?: default
    }

    private fun extractJsonObject(json: String, key: String): String? {
        val regex = Regex("\"$key\"\\s*:\\s*\\{")
        val match = regex.find(json) ?: return null
        val start = match.range.last + 1
        var depth = 1
        var end = start
        for (i in start until json.length) {
            when (json[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) { end = i; break } }
            }
        }
        return json.substring(start, end)
    }

    private fun splitJsonArray(content: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var start = -1
        for (i in content.indices) {
            when (content[i]) {
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> { depth--; if (depth == 0 && start >= 0) { result.add(content.substring(start, i + 1)); start = -1 } }
            }
        }
        return result
    }

    /**
     * 从 JSON 对象字符串中分割顶级 key-value pair（不支持嵌套对象嵌套）
     */
    private fun splitTopLevelPairs(json: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val regex = Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"")
        for (m in regex.findAll(json)) {
            result.add(m.groupValues[1] to m.groupValues[2])
        }
        return result
    }
}

/**
 * 计算文件的 SHA256 十六进制字符串
 */
fun File.computeSha256(): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    inputStream().use { stream ->
        val buffer = ByteArray(8192)
        while (true) {
            val bytesRead = stream.read(buffer)
            if (bytesRead == -1) break
            digest.update(buffer, 0, bytesRead)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
