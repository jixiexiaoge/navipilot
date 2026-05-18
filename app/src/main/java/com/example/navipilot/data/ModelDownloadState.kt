package com.example.navipilot.data

/**
 * 下载进度信息
 */
data class DownloadProgress(
    val modelId: String,
    val fileName: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val percentage: Int  // 0-100
)

/**
 * 模型下载状态
 */
enum class ModelDownloadStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    FAILED
}

/**
 * 模型下载状态详情
 */
data class ModelDownloadState(
    val modelId: String,
    val status: ModelDownloadStatus,
    val policyProgress: DownloadProgress? = null,
    val visionProgress: DownloadProgress? = null,
    val errorMessage: String? = null
)

/**
 * 已下载模型记录
 */
data class DownloadedModel(
    val modelId: String,
    val name: String,
    val version: Int,
    val policyFilePath: String,
    val visionFilePath: String,
    val policySize: Long,
    val visionSize: Long,
    val downloadedAt: Long
)

/**
 * 单个模型信息（从 models.json 解析）
 */
data class ModelInfo(
    val id: String,
    val name: String,
    val baseUrl: String,
    val files: Map<String, ModelFileInfo>,
    val minimumSelectorVersion: Int
)

/**
 * 模型文件信息
 */
data class ModelFileInfo(
    val size: Long,
    val sha256: String
)

/**
 * 将 GitHub raw URL 转换为 jihulab URL
 * 输入: https://raw.githubusercontent.com/happymaj11r/openpilot-models/main/models/CD210
 * 输出: https://jihulab.com/navipilot/openpilot-models/-/raw/main/models/CD210
 */
fun String.toJihulabUrl(): String {
    // 移除协议前缀
    val path = this
        .removePrefix("https://raw.githubusercontent.com/")
        .removePrefix("http://raw.githubusercontent.com/")

    // 移除仓库前缀happymaj11r/openpilot-models/，保留 main/models/XXX 部分
    val cleanPath = path.removePrefix("happymaj11r/openpilot-models/")

    return "https://jihulab.com/navipilot/openpilot-models/-/raw/$cleanPath"
}

/**
 * 将 models.json 的 GitHub URL 转换为 jihulab URL（用于列表获取）
 * 输入: https://raw.githubusercontent.com/happymaj11r/openpilot-models/main/models.json
 * 输出: https://jihulab.com/navipilot/openpilot-models/-/raw/main/models.json
 */
fun String.toJihulabModelsJsonUrl(): String {
    // models.json 在仓库根目录，不是 models/ 下
    val path = this
        .removePrefix("https://raw.githubusercontent.com/")
        .removePrefix("http://raw.githubusercontent.com/")
        .substringAfter("main/")  // 获取 main/ 之后的部分

    return "https://jihulab.com/navipilot/openpilot-models/-/raw/main/$path"
}