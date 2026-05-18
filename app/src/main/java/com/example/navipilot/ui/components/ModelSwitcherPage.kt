package com.example.navipilot.ui.components

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.navipilot.data.ModelDownloadManager
import com.example.navipilot.data.ModelDownloadStatus
import com.example.navipilot.data.ModelDownloadState
import com.example.navipilot.data.ModelFileInfo
import com.example.navipilot.data.ModelInfo
import com.example.navipilot.data.SshConnectionManager
import com.example.navipilot.data.SshConnectionState
import com.example.navipilot.ui.utils.localized
import com.example.navipilot.ui.components.SshConfigDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// ===============================
// 数据模型
// ===============================

/**
 * 模型列表 JSON 响应
 */
data class ModelListResponse(
    val version: Int,
    val updatedAt: String,
    val models: List<ModelInfo>,
    val keyId: String,
    val signature: String
)

// ===============================
// HTTP 请求工具类
// ===============================

/**
 * 模型列表 HTTP 客户端
 */
class ModelListClient {
    companion object {
        private const val TAG = "ModelListClient"
        private const val MODEL_LIST_URL = "https://jihulab.com/navipilot/openpilot-models/-/raw/main/models.json"
        private const val TIMEOUT_MS = 15000L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    suspend fun fetchModelList(): Result<ModelListResponse> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "开始获取模型列表: $MODEL_LIST_URL")

            val request = Request.Builder()
                .url(MODEL_LIST_URL)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()

            if (response.isSuccessful && body != null) {
                val json = JSONObject(body)
                val modelsArray = json.getJSONArray("models")
                val models = mutableListOf<ModelInfo>()

                for (i in 0 until modelsArray.length()) {
                    val modelJson = modelsArray.getJSONObject(i)
                    val filesJson = modelJson.getJSONObject("files")
                    val files = mutableMapOf<String, ModelFileInfo>()

                    filesJson.keys().forEach { key ->
                        val fileInfo = filesJson.getJSONObject(key)
                        files[key] = ModelFileInfo(
                            size = fileInfo.getLong("size"),
                            sha256 = fileInfo.getString("sha256")
                        )
                    }

                    models.add(ModelInfo(
                        id = modelJson.getString("id"),
                        name = modelJson.getString("name"),
                        baseUrl = modelJson.getString("base_url"),
                        files = files,
                        minimumSelectorVersion = modelJson.optInt("minimum_selector_version", 1)
                    ))
                }

                val result = ModelListResponse(
                    version = json.optInt("version", 1),
                    updatedAt = json.optString("updated_at", ""),
                    models = models,
                    keyId = json.optString("key_id", ""),
                    signature = json.optString("signature", "")
                )

                Log.i(TAG, "模型列表获取成功: ${models.size} 个模型")
                Result.success(result)
            } else {
                Log.e(TAG, "HTTP ${response.code} 获取模型列表失败")
                Result.failure(Exception("HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取模型列表异常: ${e.message}", e)
            Result.failure(e)
        }
    }
}

// ===============================
// UI 页面
// ===============================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSwitcherPage(
    onBack: () -> Unit,
    downloadManager: ModelDownloadManager,
    sshManager: SshConnectionManager,
    discoveredDeviceIp: String? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // SSH 弹窗状态
    var showSshDialog by remember { mutableStateOf(false) }

    // SSH 连接状态
    val sshConnectionState by sshManager.connectionState.collectAsState()
    val sshConnectionInfo by sshManager.connectionInfo.collectAsState()
    val sshUserLogs by sshManager.userLogs.collectAsState()

    // 状态管理
    var isLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    var modelList by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 从下载管理器收集状态
    val downloadStates by downloadManager.downloadStates.collectAsState()
    val downloadedModels by downloadManager.downloadedModels.collectAsState()

    // 计算总进度百分比
    fun calculateOverallProgress(state: ModelDownloadState): Int {
        val policyPct = state.policyProgress?.percentage ?: 0
        val visionPct = state.visionProgress?.percentage ?: 0
        return (policyPct + visionPct) / 2
    }

    // 获取卡片状态
    fun getCardState(modelId: String): CardState {
        val state = downloadStates[modelId]
        val isDownloaded = downloadedModels.any { it.modelId == modelId }

        return when {
            state?.status == ModelDownloadStatus.DOWNLOADING -> CardState.Downloading(state)
            isDownloaded -> CardState.Downloaded
            state?.status == ModelDownloadStatus.FAILED -> CardState.Failed(state.errorMessage)
            else -> CardState.NotDownloaded
        }
    }

    // 加载模型列表
    fun loadModels() {
        coroutineScope.launch {
            isLoading = true
            errorMessage = null

            val client = ModelListClient()
            val result = client.fetchModelList()

            result.onSuccess { response ->
                modelList = response.models
                isLoading = false
            }.onFailure { exception ->
                errorMessage = exception.message ?: localized("获取模型列表失败", "Failed to load models")
                isLoading = false
            }
        }
    }

    // 刷新模型列表
    fun refreshModels() {
        coroutineScope.launch {
            isRefreshing = true
            errorMessage = null

            val client = ModelListClient()
            val result = client.fetchModelList()

            result.onSuccess { response ->
                modelList = response.models
            }.onFailure { exception ->
                errorMessage = exception.message ?: localized("刷新失败", "Refresh failed")
            }
            isRefreshing = false
        }
    }

    // 下载按钮点击
    fun onDownloadClick(modelInfo: ModelInfo) {
        coroutineScope.launch {
            downloadManager.downloadModel(modelInfo) { state ->
                // StateFlow 自动更新 UI
            }.onSuccess {
                android.widget.Toast.makeText(
                    context,
                    "✅ ${localized("下载完成", "Download complete")}",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }.onFailure { error ->
                android.widget.Toast.makeText(
                    context,
                    "❌ ${error.message ?: localized("下载失败", "Download failed")}",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // 删除按钮点击
    fun onDeleteClick(modelId: String) {
        coroutineScope.launch {
            val success = downloadManager.deleteModel(modelId)
            if (success) {
                android.widget.Toast.makeText(
                    context,
                    localized("已删除", "Deleted"),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // 上传按钮点击
    fun onUploadClick(modelInfo: ModelInfo) {
        // 检查 SSH 是否已连接
        if (sshConnectionState != SshConnectionState.CONNECTED) {
            android.widget.Toast.makeText(
                context,
                localized("请先连接 SSH", "Please connect SSH first"),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            showSshDialog = true
            return
        }

        // 获取下载的模型文件
        val downloadedModel = downloadedModels.find { it.modelId == modelInfo.id }
        if (downloadedModel == null) {
            android.widget.Toast.makeText(
                context,
                localized("未找到本地模型文件", "Model files not found"),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        val host = sshConnectionInfo?.host ?: return

        coroutineScope.launch {
            try {
                sshManager.clearUserLogs()
                sshManager.logToUser(localized("开始上传模型", "Start uploading model") + ": ${modelInfo.name} ($host)")
                android.widget.Toast.makeText(
                    context,
                    localized("正在上传...", "Uploading..."),
                    android.widget.Toast.LENGTH_SHORT
                ).show()

                // 1. Stop comma tmux session + clear overlay lock
                sshManager.logToUser(localized("停止 comma 会话并清理锁文件", "Stop comma session and clear lock"))
                val killResult = sshManager.execCommand(
                    "tmux has-session -t comma 2>/dev/null && tmux kill-session -t comma; " +
                        "rm -f /tmp/safe_staging_overlay.lock; " +
                        "sleep 1;"
                )
                if (killResult.isFailure) {
                    android.widget.Toast.makeText(
                        context,
                        "Stop session failed: ${killResult.exceptionOrNull()?.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                // 2. Remove old model files
                sshManager.logToUser(localized("删除旧模型文件", "Remove old model files"))
                val rmResult = sshManager.execCommand("rm /data/openpilot/selfdrive/modeld/models/driving_*.onnx")
                if (rmResult.isFailure) {
                    Log.w("ModelSwitcher", "删除旧文件失败: ${rmResult.exceptionOrNull()?.message}")
                }

                // 3. Upload policy file
                sshManager.logToUser(localized("上传 policy 文件", "Upload policy file"))
                val policyUpload = sshManager.uploadFile(
                    downloadedModel.policyFilePath,
                    "/data/openpilot/selfdrive/modeld/models/"
                )
                if (policyUpload.isFailure) {
                    throw policyUpload.exceptionOrNull() ?: Exception("Policy upload failed")
                }

                // 4. Upload vision file
                sshManager.logToUser(localized("上传 vision 文件", "Upload vision file"))
                val visionUpload = sshManager.uploadFile(
                    downloadedModel.visionFilePath,
                    "/data/openpilot/selfdrive/modeld/models/"
                )
                if (visionUpload.isFailure) {
                    throw visionUpload.exceptionOrNull() ?: Exception("Vision upload failed")
                }

                // 5. Reboot device
                sshManager.logToUser(localized("重启设备", "Reboot device"))
                val rebootResult = sshManager.execCommand("reboot")
                if (rebootResult.isFailure) {
                    Log.w("ModelSwitcher", "重启命令可能未成功返回: ${rebootResult.exceptionOrNull()?.message}")
                }

                android.widget.Toast.makeText(
                    context,
                    localized("上传成功，重启中...", "Upload successful, rebooting..."),
                    android.widget.Toast.LENGTH_SHORT
                ).show()

            } catch (e: Exception) {
                Log.e("ModelSwitcher", "上传失败: ${e.message}")
                sshManager.logToUser(localized("上传失败", "Upload failed") + ": ${e.message}")
                android.widget.Toast.makeText(
                    context,
                    "❌ ${localized("上传失败", "Upload failed")}: ${e.message}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // 首次加载
    LaunchedEffect(Unit) {
        loadModels()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = localized("模型切换器", "Model Switcher") +
                                if (modelList.isNotEmpty()) " (${modelList.size})" else "",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        // SSH 连接状态指示
                        if (sshConnectionState == SshConnectionState.CONNECTED && sshConnectionInfo != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = Color(0xFF064E3B),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "SSH ✓",
                                    color = Color(0xFF4ADE80),
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = localized("返回", "Back")
                        )
                    }
                },
                actions = {
                    // SSH 配置按钮
                    IconButton(
                        onClick = { showSshDialog = true }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = "SSH",
                            tint = if (sshConnectionState == SshConnectionState.CONNECTED) Color(0xFF4ADE80) else Color.White
                        )
                    }
                    // 刷新按钮
                    IconButton(
                        onClick = { refreshModels() },
                        enabled = !isRefreshing && !isLoading
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = localized("刷新", "Refresh"),
                            tint = if (isRefreshing) Color(0xFF60A5FA) else Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1E293B),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0F172A))
                .padding(paddingValues)
        ) {
            when {
                isLoading -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF60A5FA),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = localized("加载模型列表...", "Loading models..."),
                            color = Color(0xFF94A3B8),
                            fontSize = 14.sp
                        )
                    }
                }
                errorMessage != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "⚠️",
                            fontSize = 48.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = errorMessage!!,
                            color = Color(0xFFEF4444),
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { loadModels() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF3B82F6)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(localized("重试", "Retry"))
                        }
                    }
                }
                modelList.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "📭",
                            fontSize = 48.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = localized("暂无模型", "No models available"),
                            color = Color(0xFF94A3B8),
                            fontSize = 14.sp
                        )
                    }
                }
                else -> {
                    Column(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (sshUserLogs.isNotEmpty()) {
                            SshLogPanel(
                                logs = sshUserLogs,
                                onClear = { sshManager.clearUserLogs() },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            items(modelList) { model ->
                                val cardState = getCardState(model.id)
                                DownloadableModelCard(
                                    model = model,
                                    cardState = cardState,
                                    onDownloadClick = { onDownloadClick(model) },
                                    onDeleteClick = { onDeleteClick(model.id) },
                                    onUploadClick = { onUploadClick(model) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // SSH 配置弹窗
    if (showSshDialog) {
        SshConfigDialog(
            sshManager = sshManager,
            discoveredIp = discoveredDeviceIp,
            onDismiss = { showSshDialog = false }
        )
    }
}

@Composable
private fun SshLogPanel(
    logs: List<String>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1220)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = localized("操作日志", "Action log"),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = Color(0xFFE5E7EB),
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onClear) {
                    Text(text = localized("清空", "Clear"))
                }
            }
            logs.takeLast(8).forEach { line ->
                Text(
                    text = line,
                    fontSize = 11.sp,
                    color = Color(0xFFCBD5E1),
                    maxLines = 1
                )
            }
        }
    }
}

private sealed class CardState {
    data class Downloading(val state: ModelDownloadState) : CardState()
    data object Downloaded : CardState()
    data class Failed(val error: String?) : CardState()
    data object NotDownloaded : CardState()
}

// ===============================
// 紧凑型模型卡片
// ===============================

@Composable
private fun DownloadableModelCard(
    model: ModelInfo,
    cardState: CardState,
    onDownloadClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onUploadClick: () -> Unit = {}
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF1E293B),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：名称和版本
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.name,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "v${model.minimumSelectorVersion}",
                    color = Color(0xFF64748B),
                    fontSize = 11.sp
                )
            }

            // 右侧：根据状态显示不同 UI
            when (cardState) {
                is CardState.Downloading -> {
                    val progress = cardState.state
                    val policyPct = progress.policyProgress?.percentage ?: 0
                    val visionPct = progress.visionProgress?.percentage ?: 0

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1.5f)
                    ) {
                        LinearProgressIndicator(
                            progress = { (policyPct + visionPct) / 200f },
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp),
                            color = Color(0xFF60A5FA),
                            trackColor = Color(0xFF334155),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${(policyPct + visionPct) / 2}%",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                }
                is CardState.Downloaded -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 已下载标记
                        Text(
                            text = "✓",
                            color = Color(0xFF4ADE80),
                            fontSize = 16.sp
                        )
                        // 删除按钮
                        IconButton(
                            onClick = onDeleteClick,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = localized("删除", "Delete"),
                                tint = Color(0xFFEF4444),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        // 上传按钮
                        IconButton(
                            onClick = onUploadClick,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Upload,
                                contentDescription = localized("上传", "Upload"),
                                tint = Color(0xFF3B82F6),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                is CardState.Failed -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "⚠️",
                            fontSize = 14.sp
                        )
                        IconButton(
                            onClick = onDownloadClick,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = localized("重试", "Retry"),
                                tint = Color(0xFF3B82F6),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                is CardState.NotDownloaded -> {
                    IconButton(
                        onClick = onDownloadClick,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = localized("下载", "Download"),
                            tint = Color(0xFF3B82F6),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
