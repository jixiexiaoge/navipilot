package com.example.navipilot.data

/**
 * sunnypilot driving_models_v20.json 数据模型
 */

/**
 * 下载 URI（URL + SHA256）
 */
data class SunnyDownloadUri(
    val url: String,
    val sha256: String
)

/**
 * 单个模型文件（artifact 或 metadata）
 */
data class SunnyArtifact(
    val fileName: String,
    val downloadUri: SunnyDownloadUri
)

/**
 * 一个模型条目（supercombo / vision / policy）
 */
data class SunnyModelEntry(
    val type: String, // "supercombo", "vision", "policy"
    val artifact: SunnyArtifact,
    val metadata: SunnyArtifact? = null   // vision/policy 有 metadata
)

/**
 * Bundle 覆盖配置
 */
data class SunnyBundleOverrides(
    val folder: String? = null
)

/**
 * 一个 Bundle（对应一个可选模型）
 */
data class SunnyBundle(
    val shortName: String,
    val displayName: String,
    val is20hz: Boolean = false,
    val generation: String? = null,
    val minimumSelectorVersion: String? = null,
    val runner: String? = null,
    val overrides: SunnyBundleOverrides? = null,
    val models: List<SunnyModelEntry>
)

/**
 * 顶层 JSON 响应
 */
data class SunnyModelListResponse(
    val tinygradRef: String? = null,
    val bundles: List<SunnyBundle>
)

// ===============================
// 下载状态模型
// ===============================

enum class SunnyDownloadStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    FAILED
}

/**
 * 单个文件下载进度
 */
data class SunnyFileProgress(
    val fileName: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val percentage: Int
)

/**
 * Bundle 下载状态
 */
data class SunnyDownloadState(
    val shortName: String,
    val status: SunnyDownloadStatus,
    val fileProgress: Map<String, SunnyFileProgress> = emptyMap(),
    val errorMessage: String? = null
)

/**
 * 已下载 Bundle 记录
 */
data class SunnyDownloadedBundle(
    val shortName: String,
    val displayName: String,
    val localDirPath: String,
    val files: Map<String, String>, // remoteFileName -> localFilePath
    val downloadedAt: Long
)

/**
 * 获取 bundle 中所有需要下载的文件列表
 * 返回 (type, fileName, downloadUri, hasMetadata) 列表
 */
fun SunnyBundle.collectDownloadableFiles(): List<SunnyArtifact> {
    val result = mutableListOf<SunnyArtifact>()
    for (entry in models) {
        result.add(entry.artifact)
        entry.metadata?.let { result.add(it) }
    }
    return result
}

/**
 * 将 GitLab URL 转换为可能的镜像 URL（预留，当前直接返回原 URL）
 * 如果将来需要国内镜像，可在此修改
 */
fun String.toSunnyMirrorUrl(): String = this
