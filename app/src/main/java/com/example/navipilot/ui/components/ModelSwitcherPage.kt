package com.example.navipilot.ui.components

import android.content.Context
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
import com.example.navipilot.data.SshConnectionManager
import com.example.navipilot.data.SshConnectionState
import com.example.navipilot.data.SunnyBundle
import com.example.navipilot.data.SunnyBundleOverrides
import com.example.navipilot.data.SunnyDownloadState
import com.example.navipilot.data.SunnyDownloadStatus
import com.example.navipilot.data.SunnyDownloadedBundle
import com.example.navipilot.data.SunnyModelDownloadManager
import com.example.navipilot.data.SunnyModelEntry
import com.example.navipilot.data.SunnyModelListResponse
import com.example.navipilot.ui.utils.localized
import com.example.navipilot.ui.components.SshConfigDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// ===============================
// Sunnypilot UI 组件
// ===============================

private sealed class SunnyCardState {
    data class Downloading(val state: SunnyDownloadState) : SunnyCardState()
    data object Downloaded : SunnyCardState()
    data class Failed(val error: String?) : SunnyCardState()
    data object NotDownloaded : SunnyCardState()
}

@Composable
private fun SunnySectionHeader(
    bundleCount: Int,
    isLoading: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF162032),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "☀️ ${localized("Sunnypilot 模型", "Sunnypilot Models")}",
                color = Color(0xFFE5D093),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFFE5D093)
                )
            } else {
                Text(
                    text = "$bundleCount",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun SunnyBundleCard(
    bundle: SunnyBundle,
    cardState: SunnyCardState,
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
            // 左侧：名称和版本信息
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = bundle.displayName,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    bundle.generation?.let { gen ->
                        Text(
                            text = "gen$gen",
                            color = Color(0xFF64748B),
                            fontSize = 11.sp
                        )
                    }
                    bundle.runner?.let { runner ->
                        Text(
                            text = runner,
                            color = Color(0xFF64748B),
                            fontSize = 11.sp
                        )
                    }
                    if (bundle.is20hz) {
                        Text(
                            text = "20Hz",
                            color = Color(0xFFFCD34D),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    text = "${bundle.models.size} ${localized("个文件", "files")}",
                    color = Color(0xFF475569),
                    fontSize = 10.sp
                )
            }

            // 右侧：根据状态显示不同 UI
            when (cardState) {
                is SunnyCardState.Downloading -> {
                    val state = cardState.state
                    val totalPct = state.fileProgress.values
                        .map { it.percentage }
                        .average()
                        .toInt()

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1.5f)
                    ) {
                        LinearProgressIndicator(
                            progress = { totalPct / 100f },
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp),
                            color = Color(0xFFE5D093),
                            trackColor = Color(0xFF334155),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "$totalPct%",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                }
                is SunnyCardState.Downloaded -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "✓",
                            color = Color(0xFF4ADE80),
                            fontSize = 16.sp
                        )
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
                is SunnyCardState.Failed -> {
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
                is SunnyCardState.NotDownloaded -> {
                    IconButton(
                        onClick = onDownloadClick,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = localized("下载", "Download"),
                            tint = Color(0xFFE5D093),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

// ===============================
// Sunnypilot 模型列表 HTTP 客户端
// ===============================

/**
 * sunnypilot driving_models_v20.json HTTP 客户端
 */
class SunnyModelListClient {
    companion object {
        private const val TAG = "SunnyModelListClient"
        private const val SUNNY_MODELS_URL = "https://raw.githubusercontent.com/sunnypilot/sunnypilot-models/refs/heads/gh-pages/docs/driving_models_v20.json"
        private const val TIMEOUT_MS = 15000L
        private const val MAX_RETRIES = 2
    }

    private val client: OkHttpClient by lazy { buildTolerantOkHttpClient() }

    /**
     * 为老旧 Android 设备（comma3 等）构建兼容 TLS 1.2 的 OkHttp 客户端。
     * 部分设备因 CA 证书过旧或 TLS 版本限制导致 SSL 握手失败。
     */
    private fun buildTolerantOkHttpClient(): OkHttpClient {
        return try {
            // 在不低于 API 16 的设备上显式启用 TLS 1.2
//            val sslContext = SSLContext.getInstance("TLSv1.2")
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, null, SecureRandom())

            OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .sslSocketFactory(sslContext.socketFactory, createTrustAllManager())
                .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
                .hostnameVerifier { _, _ -> true }
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "SSL 兼容配置失败，回退到默认 OkHttp: ${e.message}")
            OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
        }
    }

    /**
     * 创建信任所有证书的 TrustManager（仅用于访问公开的 GitHub raw 数据，不涉及敏感信息）
     */
    private fun createTrustAllManager(): X509TrustManager {
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
    }

    suspend fun fetchSunnyModels(): Result<List<SunnyBundle>> = withContext(Dispatchers.IO) {
        var lastError: Exception? = null

        for (attempt in 1..MAX_RETRIES) {
            try {
                Log.i(TAG, "获取 sunnypilot 模型列表 (第 ${attempt} 次尝试): $SUNNY_MODELS_URL")

                val request = Request.Builder()
                    .url(SUNNY_MODELS_URL)
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string()

                if (response.isSuccessful && body != null) {
                    val json = JSONObject(body)
                    val bundlesArray = json.getJSONArray("bundles")
                    val bundles = mutableListOf<SunnyBundle>()

                    for (i in 0 until bundlesArray.length()) {
                        val bundleJson = bundlesArray.getJSONObject(i)
                        val modelsArray = bundleJson.getJSONArray("models")
                        val models = mutableListOf<SunnyModelEntry>()

                        for (j in 0 until modelsArray.length()) {
                            val modelJson = modelsArray.getJSONObject(j)
                            val type = modelJson.getString("type")
                            val artifact = parseArtifact(modelJson.getJSONObject("artifact"))
                            val metadata = if (modelJson.has("metadata")) {
                                parseArtifact(modelJson.getJSONObject("metadata"))
                            } else null
                            models.add(SunnyModelEntry(type = type, artifact = artifact, metadata = metadata))
                        }

                        val overrides = if (bundleJson.has("overrides")) {
                            val ov = bundleJson.getJSONObject("overrides")
                            SunnyBundleOverrides(folder = ov.optString("folder", null))
                        } else null

                        bundles.add(SunnyBundle(
                            shortName = bundleJson.getString("short_name"),
                            displayName = bundleJson.getString("display_name"),
                            is20hz = bundleJson.optBoolean("is_20hz", false),
                            index = bundleJson.optInt("index", 0),
                            buildTime = bundleJson.optString("build_time", null),
                            generation = bundleJson.optString("generation", null),
                            minimumSelectorVersion = bundleJson.optString("minimum_selector_version", null),
                            runner = bundleJson.optString("runner", null),
                            overrides = overrides,
                            models = models
                        ))
                    }

                    Log.i(TAG, "sunnypilot 模型列表获取成功: ${bundles.size} 个 bundle")
                    return@withContext Result.success(bundles)
                } else {
                    val errMsg = "HTTP ${response.code} 获取 sunnypilot 模型列表失败"
                    Log.e(TAG, errMsg)
                    lastError = Exception(errMsg)
                }
            } catch (e: Exception) {
                Log.w(TAG, "获取 sunnypilot 模型列表失败 (尝试 ${attempt}/${MAX_RETRIES}): ${e.message}")
                lastError = e
                if (attempt < MAX_RETRIES) {
                    kotlinx.coroutines.delay(1000L)
                }
            }
        }

        Log.e(TAG, "所有 ${MAX_RETRIES} 次重试均失败: ${lastError?.message}")
        Result.failure(lastError ?: Exception("所有重试均失败"))
    }

    private fun parseArtifact(obj: JSONObject): com.example.navipilot.data.SunnyArtifact {
        val fileName = obj.getString("file_name")
        val uriObj = obj.getJSONObject("download_uri")
        val url = uriObj.getString("url")
        val sha256 = uriObj.getString("sha256")
        return com.example.navipilot.data.SunnyArtifact(
            fileName = fileName,
            downloadUri = com.example.navipilot.data.SunnyDownloadUri(url = url, sha256 = sha256)
        )
    }
}

// ===============================
// UI 页面
// ===============================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSwitcherPage(
    onBack: () -> Unit,
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

    // Sunnypilot 状态
    val sunnyDownloadManager = remember { SunnyModelDownloadManager.getInstance(context, context.getSharedPreferences("navipilot_prefs", Context.MODE_PRIVATE)) }
    val sunnyDownloadStates by sunnyDownloadManager.downloadStates.collectAsState()
    val sunnyDownloadedBundles by sunnyDownloadManager.downloadedBundles.collectAsState()
    var sunnyBundleList by remember { mutableStateOf<List<SunnyBundle>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // 加载 sunnypilot 模型列表（最新在前）
    fun loadModels() {
        coroutineScope.launch {
            isLoading = true
            val sunnyClient = SunnyModelListClient()
            val sunnyResult = sunnyClient.fetchSunnyModels()
            sunnyResult.onSuccess { bundles ->
                // 按 buildTime 降序（最新的在前），无 buildTime 的按 index 降序
                sunnyBundleList = bundles.sortedWith(
                    compareByDescending<SunnyBundle> { it.buildTime }
                        .thenByDescending { it.index }
                )
            }.onFailure { e ->
                Log.w("ModelSwitcher", "加载 sunnypilot 模型失败: ${e.message}")
            }
            isLoading = false
        }
    }

    // 刷新 sunnypilot 模型列表
    fun refreshModels() {
        loadModels()
    }

    // ========== Sunnypilot 模型操作 ==========

    // sunnypilot 下载点击
    fun onSunnyDownloadClick(bundle: SunnyBundle) {
        coroutineScope.launch {
            sunnyDownloadManager.downloadBundle(bundle) { state ->
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

    // sunnypilot 删除点击
    fun onSunnyDeleteClick(shortName: String) {
        coroutineScope.launch {
            val success = sunnyDownloadManager.deleteBundle(shortName)
            if (success) {
                android.widget.Toast.makeText(
                    context,
                    localized("已删除", "Deleted"),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // sunnypilot 上传点击
    fun onSunnyUploadClick(bundle: SunnyBundle) {
        if (sshConnectionState != SshConnectionState.CONNECTED) {
            android.widget.Toast.makeText(
                context,
                localized("请先连接 SSH", "Please connect SSH first"),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            showSshDialog = true
            return
        }

        val downloadedBundle = sunnyDownloadedBundles.find { it.shortName == bundle.shortName }
        if (downloadedBundle == null) {
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
                sshManager.logToUser(localized("开始上传 Sunnypilot 模型", "Start uploading Sunnypilot model") + ": ${bundle.displayName} ($host)")
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

                // 2. Remove old model files (任意格式：onnx, pkl, thneed)
                sshManager.logToUser(localized("删除旧模型文件", "Remove old model files"))
                sshManager.execCommand("rm -f /data/openpilot/selfdrive/modeld/models/driving_*.onnx /data/openpilot/selfdrive/modeld/models/*.pkl /data/openpilot/selfdrive/modeld/models/*.thneed")
                    .onFailure { Log.w("ModelSwitcher", "删除旧文件失败: ${it.message}") }

                // 3. 上传所有文件
                for ((remoteFileName, localPath) in downloadedBundle.files) {
                    sshManager.logToUser(localized("上传文件", "Upload file") + ": $remoteFileName")
                    val uploadResult = sshManager.uploadFile(
                        localPath,
                        "/data/openpilot/selfdrive/modeld/models/"
                    )
                    if (uploadResult.isFailure) {
                        throw uploadResult.exceptionOrNull() ?: Exception("Upload failed: $remoteFileName")
                    }
                }

                // 4. Clean and rebuild models
                sshManager.logToUser(localized("清理并重新编译模型", "Clean and rebuild models"))
                val cleanRebuildResult = sshManager.cleanAndRebuildModels()
                if (cleanRebuildResult.isFailure) {
                    val msg = cleanRebuildResult.exceptionOrNull()?.message ?: "unknown"
                    Log.w("ModelSwitcher", "清理重新编译失败: $msg")
                    sshManager.logToUser(localized("清理重新编译失败", "Clean and rebuild failed") + ": $msg")
                }

                // 5. Reboot device
                sshManager.logToUser(localized("重启设备", "Reboot device"))
                val rebootResult = sshManager.rebootDevice()
                if (rebootResult.isFailure) {
                    val msg = rebootResult.exceptionOrNull()?.message ?: "unknown"
                    Log.w("ModelSwitcher", "重启失败: $msg")
                    sshManager.logToUser(localized("重启失败", "Reboot failed") + ": $msg")
                    android.widget.Toast.makeText(
                        context,
                        localized("上传成功，但重启失败", "Upload succeeded, but reboot failed") + ": $msg",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                } else {
                    android.widget.Toast.makeText(
                        context,
                        localized("上传成功，重启中...", "Upload successful, rebooting..."),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }

            } catch (e: Exception) {
                Log.e("ModelSwitcher", "Sunny 上传失败: ${e.message}")
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
                                if (sunnyBundleList.isNotEmpty()) " (${sunnyBundleList.size})" else "",
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
                        enabled = !isLoading
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = localized("刷新", "Refresh"),
                            tint = Color.White
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
                sunnyBundleList.isEmpty() && !isLoading -> {
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
                            // Sunnypilot 模型列表
                            if (sunnyBundleList.isNotEmpty()) {
                                item {
                                    SunnySectionHeader(
                                        bundleCount = sunnyBundleList.size,
                                        isLoading = isLoading
                                    )
                                }
                                items(sunnyBundleList) { bundle ->
                                    val sunnyState = sunnyDownloadStates[bundle.shortName]
                                    val isSunnyDownloaded = sunnyDownloadedBundles.any { it.shortName == bundle.shortName }
                                    val cardState = when {
                                        sunnyState?.status == SunnyDownloadStatus.DOWNLOADING -> SunnyCardState.Downloading(sunnyState)
                                        isSunnyDownloaded -> SunnyCardState.Downloaded
                                        sunnyState?.status == SunnyDownloadStatus.FAILED -> SunnyCardState.Failed(sunnyState.errorMessage)
                                        else -> SunnyCardState.NotDownloaded
                                    }
                                    SunnyBundleCard(
                                        bundle = bundle,
                                        cardState = cardState,
                                        onDownloadClick = { onSunnyDownloadClick(bundle) },
                                        onDeleteClick = { onSunnyDeleteClick(bundle.shortName) },
                                        onUploadClick = { onSunnyUploadClick(bundle) }
                                    )
                                }
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


