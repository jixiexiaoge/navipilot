package com.example.navipilot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.navipilot.ui.theme.NavipilotTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.content.pm.PackageManager
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import com.example.navipilot.navigation.GoogleNavManager
import com.example.navipilot.navigation.TencentPassiveSpeedMonitor
import com.example.navipilot.scoring.DrivingDataCollector


/**
 * 自检查状态数据类
 */
data class SelfCheckStatus(
    val currentComponent: String = "",
    val currentMessage: String = "",
    val isCompleted: Boolean = false,
    val completedComponents: List<String> = emptyList(),
    val completedMessages: Map<String, String> = emptyMap() // 存储组件名称和对应的消息内容
)

/**
 * 用户数据更新模型（简化版本）
 */
data class UserDataForUpdate(
    val sponsorAmount: Float,
    val userType: Int
)

/**
 * MainActivity核心逻辑类
 * 负责核心业务逻辑、状态管理、权限处理等
 */
class MainActivityCore(
    private val activity: ComponentActivity,
    private val context: Context
) {
    companion object {
        private const val TAG = AppConstants.Logging.MAIN_ACTIVITY_TAG
        /** 与停车/坐标等共用，保存用户选择的地图/导航源 */
        private const val PREF_CARROT_AMAP = "CarrotAmap"
        private const val KEY_USER_SELECTED_NAV_MODE = "user_selected_nav_mode"
        private val VALID_USER_NAV_MODES = setOf("OSM", "AMAP", "TENCENT", "AMAP_MOBILE", "GOOGLE")
        
        // 🆕 API基础URL配置
        // 优先使用IP方式，失败后切换到网站URL
        private const val API_BASE_URL_PRIMARY = "http://31.97.51.107:8600"  // 优先使用IP方式
        private const val API_BASE_URL_FALLBACK = "https://md.jixiexiaoge.com"  // 备用网站URL
        private const val HTTP_TIMEOUT_MS = 10000  // HTTP超时时间（恢复为10秒，防止网络抖动）
    }

    // ===============================
    // 核心状态管理
    // ===============================
    
    /** Comma3 CarrotMan字段映射数据 */
    val carrotManFields = mutableStateOf(CarrotManFields())
    
    // 设备状态
    val deviceId = mutableStateOf("")
    val userType = mutableStateOf(0) // 用户类型：0=未知，1=新用户，2=支持者，3=赞助者，4=铁粉
    
    // 使用统计状态（仅时长，单位：分钟）
    val usageDurationMinutes = mutableStateOf(0L)
    
    // 页面状态
    var currentPage by mutableStateOf(0) // 0: 主页, 1: 帮助, 2: 我的, 3: 腾讯导航
    
    // 先锋用户试用到期锁定标志
    var pioneerTrialExpired by mutableStateOf(false)
    
    // 存储启动Intent用于页面导航
    var pendingNavigationIntent: Intent? = null
    
    // 自检查状态
    val selfCheckStatus = mutableStateOf(SelfCheckStatus())
    
    // 网络连接状态
    val networkStatus = mutableStateOf("🔍 正在连接...")
    val deviceInfo = mutableStateOf("")
    
    // 地图服务状态 — 三模互斥（默认按高德车机版场景，仍可由广播与定时逻辑切 OSM）
    val mapServiceType = mutableStateOf("AMAP") // "OSM" / "AMAP" / "TENCENT"
    val activeNavMode = mutableStateOf("AMAP")  // 当前活跃导航模式（三选一）
    /** 底部切换器上用户选择的模式（与自动广播切换解耦，便于后续逻辑读取） */
    var userSelectedMode by mutableStateOf("AMAP") // "OSM" / "AMAP" / "TENCENT" / "AMAP_MOBILE"
    val lastAmapBroadcastTime = mutableStateOf(0L) // 最后一次接收到高德广播的时间

    init {
        // 恢复上次选择的地图/导航源（与 OsmMapView 传入的 mapServiceType 一致）
        try {
            val prefs = context.getSharedPreferences(PREF_CARROT_AMAP, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_USER_SELECTED_NAV_MODE, null)
            if (raw != null) {
                val normalized = when (raw) {
                    "BAIDU" -> "AMAP" // 国内单包：已移除百度导航选项
                    else -> raw
                }
                if (normalized in VALID_USER_NAV_MODES) {
                    userSelectedMode = normalized
                    if (raw != normalized) {
                        persistUserSelectedNavMode() // 将旧存储（如 GOOGLE）写回为有效模式
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取用户地图偏好失败: ${e.message}")
        }
    }

    /** 将当前 [userSelectedMode] 写入 SharedPreferences，供下次启动恢复 */
    fun persistUserSelectedNavMode() {
        try {
            context.getSharedPreferences(PREF_CARROT_AMAP, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_USER_SELECTED_NAV_MODE, userSelectedMode)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "保存用户地图偏好失败: ${e.message}")
        }
    }
    
    /**
     * 定时检查地图服务类型（仅在非 TENCENT 模式下自动切换 OSM/AMAP）
     */
    fun updateMapServiceType() {
        // 嵌入第三方 SDK 导航时不自动切换 OSM/车机高德
        if (activeNavMode.value == "TENCENT" ||
            activeNavMode.value == "AMAP_MOBILE" ||
            activeNavMode.value == "GOOGLE"
        ) {
            return
        }

        val currentTime = System.currentTimeMillis()
        val timeSinceLastBroadcast = currentTime - lastAmapBroadcastTime.value
        val newMode = if (timeSinceLastBroadcast < 30000) "AMAP" else "OSM"
        if (activeNavMode.value != newMode) {
            Log.i(TAG, "🔄 导航模式自动切换: ${activeNavMode.value} → $newMode")
        }
        activeNavMode.value = newMode
        // 不再写入 mapServiceType：与 [userSelectedMode] 解耦，避免与 OsmMapView 外部导航逻辑打架
    }
    
    /**
     * 标记收到高德广播（仅在非 TENCENT 模式下切换到 AMAP）
     */
    fun markAmapBroadcastReceived() {
        lastAmapBroadcastTime.value = System.currentTimeMillis()
        if (activeNavMode.value != "TENCENT" &&
            activeNavMode.value != "AMAP_MOBILE" &&
            activeNavMode.value != "GOOGLE"
        ) {
            activeNavMode.value = "AMAP"
        }
    }
    
    /**
     * 切换到腾讯导航模式（进入 TencentNavPage 时调用）
     */
    fun switchToTencentMode() {
        Log.i(TAG, "🔄 切换到腾讯导航模式 (之前: ${activeNavMode.value})")
        // 进入腾讯模式前必须先停止被动监控，避免与 TencentNavPage 的 NavigatorDrive 冲突
        TencentPassiveSpeedMonitor.stop()
        activeNavMode.value = "TENCENT"
        mapServiceType.value = "TENCENT"
        // 设置数据源标记
        carrotManFields.value = carrotManFields.value.copy(source_last = "tencent")
    }

    /**
     * 退出腾讯导航模式（离开 TencentNavPage 时调用）
     * 根据 AMAP 广播状态自动回退到 AMAP 或 OSM
     */
    fun exitTencentMode() {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastBroadcast = currentTime - lastAmapBroadcastTime.value
        val fallbackMode = if (timeSinceLastBroadcast < 30000) "AMAP" else "OSM"
        Log.i(TAG, "🔄 退出腾讯导航模式 → $fallbackMode")
        activeNavMode.value = fallbackMode
        mapServiceType.value = fallbackMode
    }

    /** 切换到高德手机 SDK 嵌入导航模式 */
    fun switchToAmapMobileMode() {
        Log.i(TAG, "🔄 切换到高德手机导航模式 (之前: ${activeNavMode.value})")
        activeNavMode.value = "AMAP_MOBILE"
        mapServiceType.value = "AMAP_MOBILE"
        carrotManFields.value = carrotManFields.value.copy(source_last = "amap_mobile")
        // 启动腾讯后台被动限速监控（自由行驶模式，为高德提供摄像头限速补充）
        startTencentPassiveMonitor()
    }

    /** 退出高德手机嵌入导航 */
    fun exitAmapMobileMode() {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastBroadcast = currentTime - lastAmapBroadcastTime.value
        val fallbackMode = if (timeSinceLastBroadcast < 30000) "AMAP" else "OSM"
        Log.i(TAG, "🔄 退出高德手机导航模式 → $fallbackMode")
        TencentPassiveSpeedMonitor.stop()
        activeNavMode.value = fallbackMode
        mapServiceType.value = fallbackMode
    }

    /** 切换到 Google 导航模式 */
    fun switchToGoogleMode() {
        Log.i(TAG, "🔄 切换到 Google 导航模式 (之前: ${activeNavMode.value})")
        activeNavMode.value = "GOOGLE"
        mapServiceType.value = "GOOGLE"
        carrotManFields.value = carrotManFields.value.copy(source_last = "google_nav")
        // 启动腾讯后台被动限速监控（自由行驶模式，为谷歌导航提供摄像头限速补充）
        startTencentPassiveMonitor()
    }

    /** 退出 Google 导航 */
    fun exitGoogleMode() {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastBroadcast = currentTime - lastAmapBroadcastTime.value
        val fallbackMode = if (timeSinceLastBroadcast < 30000) "AMAP" else "OSM"
        Log.i(TAG, "🔄 退出 Google 导航模式 → $fallbackMode")
        TencentPassiveSpeedMonitor.stop()
        activeNavMode.value = fallbackMode
        mapServiceType.value = fallbackMode
    }

    /**
     * 启动腾讯后台被动限速监控，并将摄像头限速结果写入 [carrotManFields]。
     *
     * 仅在 AMAP_MOBILE / GOOGLE 模式下调用；进入 TENCENT 模式或退出时停止。
     *
     * ⚠️ 限速优先级策略：
     * 1. Amap SDK 的 [AmapNavDataBridge.onUpdateNaviSpeedLimitSection] — 最高（道路分段限速）
     * 2. Amap SDK 的 TTS 文本提取 — 次高
     * 3. 腾讯被动摄像头限速 — **仅当以上来源未提供限速时**（nRoadLimitSpeed <= 0）
     *
     * 原因：腾讯 `onCameraInfoUpdate` 返回的是**摄像头执法限速**（如学校区域 40km/h），
     * 而非当前路段的**道路限速**（如 80km/h）。用摄像头限速覆盖道路限速会导致发送错误数据。
     */
    private fun startTencentPassiveMonitor() {
        val app = context.applicationContext as android.app.Application
        TencentPassiveSpeedMonitor.onSpeedLimitUpdate = { speedLimitKmh ->
            if (speedLimitKmh > 0) {
                val cur = carrotManFields.value
                // 🎯 仅当 Amap SDK 尚未提供道路限速时，才用腾讯摄像头限速兜底
                if (cur.nRoadLimitSpeed <= 0) {
                    Log.i(TAG, "📷 腾讯被动监控提供限速: ${speedLimitKmh}km/h → 写入 nRoadLimitSpeed（Amap 无限速，兜底）")
                    carrotManFields.value = cur.copy(
                        nRoadLimitSpeed = speedLimitKmh,
                        roadcate = if (speedLimitKmh >= 100) 10 else cur.roadcate.takeIf { it > 0 } ?: 6,
                        source_last = cur.source_last  // 保留当前导航源标记
                    )
                } else {
                    Log.d(TAG, "📷 腾讯摄像头限速 ${speedLimitKmh}km/h 跳过 — Amap 已提供限速 ${cur.nRoadLimitSpeed}km/h，不覆盖")
                }
            } else {
                // speedLimitKmh == -1：摄像头已过，不清除（让 SDK 自身管理清除逻辑）
                Log.d(TAG, "📷 腾讯被动监控：摄像头已过，保持当前限速")
            }
        }
        TencentPassiveSpeedMonitor.start(app)
    }

    // 实时网络流程事件（用于在主页顶部显示发现->连接链路）
    val pipelineEvents = mutableStateListOf<String>()

    fun addPipelineEvent(message: String) {
        // 带时间戳入队，最多保留20条
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        pipelineEvents.add("[$ts] $message")
        if (pipelineEvents.size > 40) {
            pipelineEvents.removeFirst()
        }
    }

    // ===============================
    // 管理器实例
    // ===============================
    
    // 广播接收器管理器
    lateinit var amapBroadcastManager: AmapBroadcastManager
    // 位置和传感器管理器
    lateinit var locationSensorManager: LocationSensorManager
    // 权限管理器
    lateinit var permissionManager: PermissionManager
    // 网络管理器
    lateinit var networkManager: NetworkManager
    // 条件实验模式管理器
    lateinit var conditionalExperimentManager: ConditionalExperimentManager

    // Google 导航管理器（懒创建，首次进入 Google 模式时初始化，跨页面保持引用）
    private var _googleNavManager: GoogleNavManager? = null
    val googleNavManager: GoogleNavManager?
        get() {
            if (_googleNavManager == null) {
                _googleNavManager = GoogleNavManager(context, carrotManFields)
            }
            return _googleNavManager
        }

    /**
     * 安全获取 ConditionalExperimentManager（用于 UI 组件）
     * 如果未初始化，返回 null
     */
    fun getConditionalExperimentManagerSafely(): ConditionalExperimentManager? {
        return try {
            if (::conditionalExperimentManager.isInitialized) {
                conditionalExperimentManager
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 安全获取 CarrotParamClient（用于 UI 组件）
     * 如果未创建，返回 null
     */
    fun getCarrotParamClientSafely(): CarrotParamClient? {
        return carrotParamClient
    }
    
    /**
     * 安全获取 NetworkClient（用于 UI 组件）
     * 如果 networkManager 未初始化，返回 null
     */
    fun getNetworkClientSafely(): CarrotManNetworkClient? {
        return try {
            if (::networkManager.isInitialized) {
                networkManager.getNetworkClient()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 安全获取设备 IP（用于 UI 组件）
     * 如果 networkManager 未初始化，返回 null
     */
    fun getDeviceIPSafely(): String? {
        return try {
            if (::networkManager.isInitialized) {
                networkManager.getCurrentDeviceIP()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 安全获取 DrivingDataCollector（用于 UI 组件）
     * 如果未初始化，返回 null
     * 暂时注释，待修复编译问题
     */
    /*
    fun getDrivingDataCollectorSafely(): com.example.navipilot.scoring.DrivingDataCollector? {
        // return drivingDataCollector
        return null  // 暂时返回null，待修复编译问题
    }
    */
    
    // 高德地图相关管理器（已整合到AmapBroadcastHandlers中）
    // 设备管理器
    lateinit var deviceManager: DeviceManager
    
    // 小鸽数据接收器
    lateinit var xiaogeDataReceiver: XiaogeDataReceiver
    val xiaogeData = mutableStateOf<XiaogeVehicleData?>(null)
    val xiaogeTcpConnected = mutableStateOf(false)  // 🆕 TCP连接状态
    val xiaogeDataTimeout = mutableStateOf(false)  // 🆕 数据超时状态（连接但数据超时）
    
    // 自动超车管理器
    lateinit var autoOvertakeManager: AutoOvertakeManager
    
    // 驾驶评分数据采集器
    var drivingDataCollector: DrivingDataCollector? = null
    
    /**
     * 安全获取 DrivingDataCollector（用于 UI 组件）
     * 如果未初始化，返回 null
     */
    fun getDrivingDataCollectorSafely(): DrivingDataCollector? {
        return drivingDataCollector
    }

    // HTTP参数客户端（替代ZMQ用于参数读写）
    var carrotParamClient: CarrotParamClient? = null
    
    // 内存监控定时器
    var memoryMonitorTimer: java.util.Timer? = null
    
    // 🔧 修复1.1：协程作用域管理 - 使用统一的作用域，确保可以在onDestroy时取消
    private val coreScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // 🔧 修复1.3：线程安全 - 使用Mutex保护carrotManFields的并发修改
    private val fieldsMutex = kotlinx.coroutines.sync.Mutex()
    
    /**
     * 🔧 修复1.3：线程安全的字段更新方法
     * 使用Mutex确保多线程修改carrotManFields时的原子性
     */
    suspend fun updateFieldsSafely(block: (CarrotManFields) -> CarrotManFields) {
        fieldsMutex.withLock {
            carrotManFields.value = block(carrotManFields.value)
        }
    }

    // ===============================
    // 权限处理
    // ===============================
    
    // Android 13+ 通知权限请求
    val notificationPermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Log.i(TAG, "🔔 通知权限已授予")
        } else {
            Log.w(TAG, "🔔 通知权限被拒绝")
        }
    }

    // ===============================
    // 控制指令广播接收器
    // ===============================
    
    val carrotCommandReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.example.navipilot.SEND_CARROT_COMMAND" -> {
                    val command = intent.getStringExtra("command") ?: return
                    val arg = intent.getStringExtra("arg") ?: return
                    
                    Log.i(TAG, "📡 收到控制指令广播: carrotCmd=$command, carrotArg=$arg")
                    
                    // 通过NetworkManager发送指令到设备
                    if (::networkManager.isInitialized) {
                        networkManager.sendControlCommand(command, arg)
                    } else {
                        Log.w(TAG, "⚠️ NetworkManager未初始化，无法发送控制指令")
                    }
                }
                "com.example.navipilot.CHANGE_SPEED_MODE" -> {
                    val mode = intent.getIntExtra("mode", 0)
                    val modeNames = arrayOf("智能控速", "原车巡航", "弯道减速")
                    
                    Log.i(TAG, "🔄 收到模式切换广播: ${modeNames[mode]} (SpeedFromPCM=$mode)")
                    
                    // 通过NetworkManager发送模式切换到设备
                    if (::networkManager.isInitialized) {
                        coreScope.launch {
                            try {
                                val result = networkManager.sendModeChangeToComma3(mode)
                                if (result.isSuccess) {
                                    Log.i(TAG, "✅ 模式切换成功: ${modeNames[mode]}")
                                } else {
                                    Log.e(TAG, "❌ 模式切换失败: ${result.exceptionOrNull()?.message}")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "❌ 模式切换异常: ${e.message}", e)
                            }
                        }
                    } else {
                        Log.w(TAG, "⚠️ NetworkManager未初始化，无法切换模式")
                    }
                }
                "com.example.navipilot.CHANGE_AUTO_TURN_CONTROL" -> {
                    val mode = intent.getIntExtra("mode", 2)
                    val modeNames = arrayOf("禁用控制", "自动变道", "控速变道", "导航限速")

                    Log.i(TAG, "🔄 收到自动转向控制模式切换广播: ${modeNames[mode]} (AutoTurnControl=$mode)")

                    if (::networkManager.isInitialized) {
                        coreScope.launch {
                            try {
                                val result = networkManager.sendAutoTurnControlChangeToComma3(mode)
                                if (result.isSuccess) {
                                    Log.i(TAG, "✅ 自动转向控制模式切换成功: ${modeNames[mode]}")
                                } else {
                                    Log.e(TAG, "❌ 自动转向控制模式切换失败: ${result.exceptionOrNull()?.message}")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "❌ 自动转向控制模式切换异常: ${e.message}", e)
                            }
                        }
                    } else {
                        Log.w(TAG, "⚠️ NetworkManager未初始化，无法切换自动转向控制模式")
                    }
                }
            }
        }
    }

    // ===============================
    // 权限管理方法
    // ===============================
    
    /**
     * 请求忽略电池优化，防止app被系统杀死
     */
    fun requestIgnoreBatteryOptimizations() {
        try {
            val powerManager = activity.getSystemService(PowerManager::class.java)
            val packageName = activity.packageName

            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                Log.i(TAG, "🔋 请求忽略电池优化")
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                activity.startActivity(intent)
            } else {
                Log.i(TAG, "🔋 已忽略电池优化")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ 请求电池优化权限失败: ${e.message}")
        }
    }

    /**
     * Android 13+ 请求通知权限，确保前台服务通知正常显示
     */
    fun requestNotificationPermissionIfNeeded() {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                val granted = activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    Log.i(TAG, "🔔 请求通知权限 (Android 13+)")
                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    Log.i(TAG, "🔔 已有通知权限")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ 请求通知权限失败: ${e.message}")
        }
    }

    // ===============================
    // 服务管理方法
    // ===============================
    
    /**
     * 启动前台服务
     */
    fun startForegroundService() {
        try {
            Log.i(TAG, "🔔 启动前台服务...")
            
            val serviceIntent = Intent(activity, CarrotAmapForegroundService::class.java).apply {
                action = CarrotAmapForegroundService.ACTION_START_SERVICE
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.startForegroundService(serviceIntent)
            } else {
                activity.startService(serviceIntent)
            }
            
            Log.i(TAG, "✅ 前台服务启动成功")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 启动前台服务失败: ${e.message}", e)
        }
    }
    
    /**
     * 停止前台服务
     */
    fun stopForegroundService() {
        try {
            Log.i(TAG, "🛑 停止前台服务...")
            
            val serviceIntent = Intent(activity, CarrotAmapForegroundService::class.java).apply {
                action = CarrotAmapForegroundService.ACTION_STOP_SERVICE
            }
            
            activity.stopService(serviceIntent)
            
            Log.i(TAG, "✅ 前台服务停止成功")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 停止前台服务失败: ${e.message}", e)
        }
    }

    // ===============================
    // 广播接收器管理
    // ===============================
    
    /**
     * 注册控制指令广播接收器
     */
    fun registerCarrotCommandReceiver() {
        try {
            val filter = android.content.IntentFilter().apply {
                addAction("com.example.navipilot.SEND_CARROT_COMMAND")
                addAction("com.example.navipilot.CHANGE_SPEED_MODE")
                addAction("com.example.navipilot.CHANGE_AUTO_TURN_CONTROL")
            }
            activity.registerReceiver(carrotCommandReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            Log.i(TAG, "✅ 控制指令广播接收器已注册（包含模式切换）")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 注册控制指令广播接收器失败: ${e.message}", e)
        }
    }

    /**
     * 注销控制指令广播接收器
     */
    fun unregisterCarrotCommandReceiver() {
        try {
            activity.unregisterReceiver(carrotCommandReceiver)
            Log.i(TAG, "✅ 控制指令广播接收器已注销")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 注销控制指令广播接收器失败: ${e.message}", e)
        }
    }

    // ===============================
    // 用户类型和API管理
    // ===============================
    
    /**
     * 🆕 通用HTTP GET请求（支持URL回退）
     * 优先使用IP方式，如果超时或失败，自动切换到网站URL
     * @param endpoint API端点（如 "/api/user/123"）
     * @return HTTP响应内容，如果两次都失败则返回null
     */
    private suspend fun httpGetWithFallback(endpoint: String): String? = withContext(Dispatchers.IO) {
        val urls = listOf(
            "$API_BASE_URL_PRIMARY$endpoint",
            "$API_BASE_URL_FALLBACK$endpoint"
        )
        
        for ((index, urlString) in urls.withIndex()) {
            try {
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                
                connection.apply {
                    requestMethod = "GET"
                    connectTimeout = HTTP_TIMEOUT_MS
                    readTimeout = HTTP_TIMEOUT_MS
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", "CP搭子/1.0")
                }
                
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    if (index == 0) {
                        Log.d(TAG, "✅ 使用IP方式获取成功: $urlString")
                    } else {
                        Log.i(TAG, "✅ IP方式失败，已切换到网站URL获取成功: $urlString")
                    }
                    return@withContext response
                } else {
                    // HTTP错误（非超时），如果是第一次尝试，继续尝试备用URL
                    if (index == 0) {
                        Log.w(TAG, "⚠️ IP方式返回错误码 $responseCode，正在尝试切换到网站URL: ${urls[1]}")
                        continue
                    } else {
                        val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "无错误详情"
                        Log.w(TAG, "⚠️ 网站URL也返回错误码 $responseCode: $urlString, 详情: $errorBody")
                        return@withContext null
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                // 超时异常，如果是第一次尝试，切换到备用URL
                if (index == 0) {
                    Log.w(TAG, "⏱️ IP方式超时（${HTTP_TIMEOUT_MS}ms），正在尝试切换到网站URL: ${urls[1]}")
                    continue
                } else {
                    Log.e(TAG, "⏱️ 网站URL也超时: $urlString", e)
                    return@withContext null
                }
            } catch (e: Exception) {
                // 其他异常，如果是第一次尝试，切换到备用URL
                if (index == 0) {
                    Log.w(TAG, "⚠️ IP方式请求失败: ${e.message}，正在尝试切换到网站URL: ${urls[1]}")
                    continue
                } else {
                    Log.e(TAG, "❌ 网站URL也失败: $urlString", e)
                    return@withContext null
                }
            }
        }
        
        null  // 所有URL都失败
    }
    
    /**
     * 🆕 通用HTTP POST请求（支持URL回退）
     * 优先使用IP方式，如果超时或失败，自动切换到网站URL
     * @param endpoint API端点（如 "/api/user/update"）
     * @param requestBody POST请求体（JSON字符串）
     * @return HTTP响应内容，如果两次都失败则返回null
     */
    private suspend fun httpPostWithFallback(endpoint: String, requestBody: String): String? = withContext(Dispatchers.IO) {
        val urls = listOf(
            "$API_BASE_URL_PRIMARY$endpoint",
            "$API_BASE_URL_FALLBACK$endpoint"
        )
        
        for ((index, urlString) in urls.withIndex()) {
            try {
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                
                connection.apply {
                    requestMethod = "POST"
                    connectTimeout = HTTP_TIMEOUT_MS
                    readTimeout = HTTP_TIMEOUT_MS
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", "CP搭子/1.0")
                    doOutput = true
                }
                
                connection.outputStream.use { outputStream ->
                    outputStream.write(requestBody.toByteArray())
                }
                
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    if (index == 0) {
                        Log.d(TAG, "✅ 使用IP方式POST成功: $urlString")
                    } else {
                        Log.i(TAG, "✅ IP方式失败，已切换到网站URL POST成功: $urlString")
                    }
                    return@withContext response
                } else {
                    // HTTP错误（非超时），如果是第一次尝试，继续尝试备用URL
                    if (index == 0) {
                        Log.w(TAG, "⚠️ IP方式POST返回错误码 $responseCode，正在尝试切换到网站URL: ${urls[1]}")
                        continue
                    } else {
                        val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "无错误详情"
                        Log.w(TAG, "⚠️ 网站URL POST也返回错误码 $responseCode: $urlString, 详情: $errorBody")
                        return@withContext null
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                // 超时异常，如果是第一次尝试，切换到备用URL
                if (index == 0) {
                    Log.w(TAG, "⏱️ IP方式POST超时（${HTTP_TIMEOUT_MS}ms），正在尝试切换到网站URL: ${urls[1]}")
                    continue
                } else {
                    Log.e(TAG, "⏱️ 网站URL POST也超时: $urlString", e)
                    return@withContext null
                }
            } catch (e: Exception) {
                // 其他异常，如果是第一次尝试，切换到备用URL
                if (index == 0) {
                    Log.w(TAG, "⚠️ IP方式POST失败: ${e.message}，正在尝试切换到网站URL: ${urls[1]}")
                    continue
                } else {
                    Log.e(TAG, "❌ 网站URL POST也失败: $urlString", e)
                    return@withContext null
                }
            }
        }
        
        null  // 所有URL都失败
    }
    
    /**
     * 自动更新使用时长到API（仅时长）
     */
    suspend fun autoUpdateUsageDuration(deviceId: String, durationMinutes: Long) = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "📊 自动更新使用时长: ${durationMinutes}分钟")
            
            if (durationMinutes <= 0) {
                Log.w(TAG, "⚠️ 使用时长为0，跳过API更新")
                return@withContext
            }
            
            // 首先获取用户当前数据
            val currentUserData = fetchUserDataForUpdate(deviceId)
            
            if (currentUserData.userType == 0) {
                Log.w(TAG, "⚠️ 无法获取有效用户数据，中止使用统计自动更新")
                return@withContext
            }
            
            val requestBody = JSONObject().apply {
                put("device_id", deviceId)
                put("sponsor_amount", currentUserData.sponsorAmount)
                put("user_type", currentUserData.userType)
                put("usage_duration", (durationMinutes / 60.0).toInt()) // 转换为小时（整数）
            }.toString()
            
            Log.d(TAG, "📤 发送使用时长更新请求: $requestBody")
            
            val response = httpPostWithFallback("/api/user/update", requestBody)
            if (response != null) {
                val jsonObject = JSONObject(response)
                if (jsonObject.getBoolean("success")) {
                    val data = jsonObject.optJSONObject("data")
                    val updatedDuration = data?.optInt("usage_duration", 0) ?: 0
                    Log.i(TAG, "✅ 使用时长更新成功: ${updatedDuration}小时")
                } else {
                    Log.w(TAG, "⚠️ 使用时长更新API返回失败: ${jsonObject.optString("message", "未知错误")}")
                }
            } else {
                Log.w(TAG, "⚠️ 使用时长更新失败：网站URL和IP方式都失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 自动更新使用时长失败", e)
            throw e
        }
    }

    /**
     * 获取用户数据用于更新（简化版本，只获取必要字段）
     * 🆕 优化：支持URL回退机制（优先网站URL，失败后切换IP）
     */
    private suspend fun fetchUserDataForUpdate(deviceId: String): UserDataForUpdate = withContext(Dispatchers.IO) {
        try {
            // 🆕 使用支持URL回退的GET请求
            val response = httpGetWithFallback("/api/user/$deviceId")
            if (response != null) {
                val jsonObject = JSONObject(response)
                
                if (jsonObject.getBoolean("success")) {
                    val data = jsonObject.getJSONObject("data")
                    UserDataForUpdate(
                        sponsorAmount = data.optDouble("sponsor_amount", 0.0).toFloat(),
                        userType = data.optInt("user_type", 0)
                    )
                } else {
                    throw Exception("API返回失败: ${jsonObject.optString("message", "未知错误")}")
                }
            } else {
                // 所有URL都失败，返回默认值
                throw Exception("网站URL和IP方式都失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取用户数据失败", e)
            // 返回默认值
            UserDataForUpdate(
                sponsorAmount = 0f,
                userType = 0
            )
        }
    }

    /**
     * 获取用户类型 - 直接调用API，不使用缓存
     * 🆕 优化：支持URL回退机制（优先网站URL，失败后切换IP）
     */
    suspend fun fetchUserType(deviceId: String): Int = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "👤 直接获取用户类型: $deviceId")
            
            // 🆕 使用支持URL回退的GET请求
            val response = httpGetWithFallback("/api/user/$deviceId")
            if (response != null) {
                val jsonObject = JSONObject(response)
                
                if (jsonObject.getBoolean("success")) {
                    val data = jsonObject.getJSONObject("data")
                    val type = data.optInt("user_type", 0)
                    Log.i(TAG, "✅ 用户类型获取成功: $type")
                    
                    type
                } else {
                    Log.w(TAG, "⚠️ API返回失败，使用默认用户类型0")
                    0
                }
            } else {
                // 所有URL都失败，使用默认值
                Log.w(TAG, "⚠️ 网站URL和IP方式都失败，使用默认用户类型0")
                0
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 获取用户类型失败: ${e.message}，使用默认用户类型0", e)
            0
        }
    }


    // ===============================
    // 命令发送方法
    // ===============================
    
    /**
     * 发送Carrot命令到设备
     */
    fun sendCarrotCommand(command: String, arg: String) {
        try {
            Log.i(TAG, "🎮 主页发送Carrot命令: $command $arg")
            
            // 检查NetworkManager是否已初始化
            if (::networkManager.isInitialized) {
                Log.d(TAG, "✅ NetworkManager已初始化，准备发送控制指令")
                networkManager.sendControlCommand(command, arg)
                Log.i(TAG, "✅ 指令已发送: $command $arg")
            } else {
                Log.w(TAG, "⚠️ NetworkManager未初始化，无法发送指令")
                Log.w(TAG, "⚠️ 请等待网络服务启动完成后再试")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 发送Carrot命令失败: ${e.message}", e)
        }
    }

    /**
     * 发送当前道路限速到comma3设备
     */
    fun sendCurrentRoadLimitSpeed() {
        try {
            // 🆕 从carrotManFields获取当前道路限速（与UI保持一致）
            val roadLimitSpeed = carrotManFields.value.nRoadLimitSpeed
            
            if (roadLimitSpeed > 0) {
                Log.i(TAG, "🎯 主页发送当前道路限速: ${roadLimitSpeed}km/h")
                
                // 发送速度设置命令
                sendCarrotCommand("SPEED", roadLimitSpeed.toString())
                
                Log.i(TAG, "✅ 道路限速已发送: ${roadLimitSpeed}km/h")
            } else {
                Log.w(TAG, "⚠️ 当前道路限速为0，无法发送")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 发送道路限速失败: ${e.message}", e)
        }
    }

    /**
     * 手动发送导航确认到comma3设备（"开地图"按钮功能）
     * 前提条件：active 为 true（OpenpPilot已激活）
     */
    fun sendNavigationConfirmationManually() {
        try {
            Log.i(TAG, "🗺️ 用户点击'开地图'按钮")
            
            // 检查NetworkManager是否已初始化
            if (!::networkManager.isInitialized) {
                Log.w(TAG, "⚠️ NetworkManager未初始化，无法发送导航确认")
                return
            }
            
            // 检查 active 状态
            val isActive = carrotManFields.value.active
            if (!isActive) {
                Log.w(TAG, "⚠️ OpenpPilot未激活（active=false），无法发送导航确认")
                return
            }
            
            // 获取目的地信息
            val goalName = carrotManFields.value.szGoalName.ifEmpty { "目的地" }
            val goalLat = carrotManFields.value.goalPosY
            val goalLon = carrotManFields.value.goalPosX
            
            // 检查坐标有效性
            if (goalLat == 0.0 || goalLon == 0.0) {
                Log.w(TAG, "⚠️ 无有效坐标信息: lat=$goalLat, lon=$goalLon")
                return
            }
            
            Log.i(TAG, "📍 准备发送导航确认: name=$goalName, lat=$goalLat, lon=$goalLon")
            
            // 使用 coreScope 而非匿名 CoroutineScope，与 Activity 生命周期绑定
            coreScope.launch(Dispatchers.IO) {
                try {
                    val result = networkManager.sendNavigationConfirmationToComma3(goalName, goalLat, goalLon)
                    withContext(Dispatchers.Main) {
                        if (result.isSuccess) {
                            Log.i(TAG, "✅ 导航确认发送成功")
                            android.widget.Toast.makeText(activity, "✅ 导航确认已发送", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            Log.e(TAG, "❌ 导航确认发送失败: ${result.exceptionOrNull()?.message}")
                            android.widget.Toast.makeText(activity, "❌ 导航确认发送失败", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 导航确认发送异常: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(activity, "❌ 发送失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 发送导航确认失败: ${e.message}", e)
        }
    }

    // ===============================
    // 高德地图相关方法
    // ===============================
    
    /**
     * 启动高德地图车机版
     */
    fun launchAmapAuto() {
        try {
            // 高德地图车机版包名
            val pkgName = "com.autonavi.amapauto"

            // 尝试启动高德地图主界面
            val launchIntent = Intent().apply {
                setComponent(
                    ComponentName(
                        pkgName,
                        "com.autonavi.auto.MainMapActivity" // 主地图Activity
                    )
                )
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            activity.startActivity(launchIntent)
            Log.i(TAG, "已启动高德地图车机版")

            // 更新UI状态
            amapBroadcastManager.receiverStatus.value = "已启动高德地图车机版"

        } catch (e: Exception) {
            Log.e(TAG, "启动高德地图失败: ${e.message}", e)
            amapBroadcastManager.receiverStatus.value = "启动高德地图失败: ${e.message}"

            // 尝试使用隐式Intent启动
            try {
                val intent = activity.packageManager.getLaunchIntentForPackage("com.autonavi.amapauto")
                if (intent != null) {
                    activity.startActivity(intent)
                    Log.i(TAG, "已通过隐式Intent启动高德地图车机版")
                    amapBroadcastManager.receiverStatus.value = "已启动高德地图车机版"
                } else {
                    amapBroadcastManager.receiverStatus.value = "未找到高德地图车机版应用"
                }
            } catch (e2: Exception) {
                Log.e(TAG, "隐式启动高德地图失败: ${e2.message}", e2)
                amapBroadcastManager.receiverStatus.value = "启动高德地图失败: ${e2.message}"
            }
        }
    }

    /**
     * 启动高德地图手机版（大众包名 com.autonavi.minimap）
     */
    fun launchAmapMobile() {
        val pkgName = "com.autonavi.minimap"
        try {
            val intent = activity.packageManager.getLaunchIntentForPackage(pkgName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(intent)
                Log.i(TAG, "已启动高德地图手机版")
                amapBroadcastManager.receiverStatus.value = "已启动高德地图手机版"
            } else {
                Log.w(TAG, "未安装高德地图手机版: $pkgName")
                amapBroadcastManager.receiverStatus.value = "未安装高德地图手机版"
                android.widget.Toast.makeText(
                    activity,
                    "未安装高德地图（手机版）",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动高德地图手机版失败: ${e.message}", e)
            amapBroadcastManager.receiverStatus.value = "启动高德地图手机版失败: ${e.message}"
        }
    }

    /**
     * 发送一键回家指令给高德地图
     */
    fun sendHomeNavigationToAmap() {
        try {
            Log.i(TAG, "🏠 发送一键回家指令给高德地图")

            val homeIntent = Intent("AUTONAVI_STANDARD_BROADCAST_RECV").apply {
                putExtra("KEY_TYPE", 10040)
                putExtra("SOURCE_APP", "Navipilot")
                putExtra("DEST", 0) // 0: 回家；1: 回公司
                putExtra("IS_START_NAVI", 0) // 0: 是直接开始导航；1: 否
                setPackage("com.autonavi.amapauto")
                flags = Intent.FLAG_INCLUDE_STOPPED_PACKAGES
            }

            activity.sendBroadcast(homeIntent)
            Log.i(TAG, "✅ 一键回家导航广播已发送 (KEY_TYPE: 10040, DEST: 0)")

        } catch (e: Exception) {
            Log.e(TAG, "❌ 发送一键回家指令失败: ${e.message}", e)
        }
    }

    /**
     * 发送导航到公司指令给高德地图
     */
    fun sendCompanyNavigationToAmap() {
        try {
            Log.i(TAG, "🏢 发送导航到公司指令给高德地图")

            val companyIntent = Intent("AUTONAVI_STANDARD_BROADCAST_RECV").apply {
                putExtra("KEY_TYPE", 10040)
                putExtra("SOURCE_APP", "Navipilot")
                putExtra("DEST", 1) // 0: 回家；1: 回公司
                putExtra("IS_START_NAVI", 0) // 0: 是直接开始导航；1: 否
                setPackage("com.autonavi.amapauto")
                flags = Intent.FLAG_INCLUDE_STOPPED_PACKAGES
            }

            activity.sendBroadcast(companyIntent)
            Log.i(TAG, "✅ 导航到公司广播已发送 (KEY_TYPE: 10040, DEST: 1)")

        } catch (e: Exception) {
            Log.e(TAG, "❌ 发送导航到公司指令失败: ${e.message}", e)
        }
    }

    // ===============================
    // 用户类型处理
    // ===============================
    
    /**
     * 根据用户类型执行不同操作
     */
    fun handleUserTypeAction(userType: Int) {
        Log.i(TAG, "🎯 根据用户类型执行操作: $userType")
        
        when (userType) {
            -1 -> {
                // 管理员专用 - 强制退出应用
                Log.i(TAG, "🔧 管理员用户，强制退出应用")
                forceExitApp()
            }
            0 -> {
                // 先锋用户 — 累计使用54000秒（15小时）后锁定到「我的」页面要求升级
                Log.i(TAG, "👤 先锋用户(0)，检查累计使用时长...")
                
                val totalUsed = getCumulativeUsageSeconds()
                if (totalUsed >= 54000) {
                    Log.w(TAG, "⚠️ 先锋用户累计使用已达 ${totalUsed}s ≥ 54000s（15小时），锁定到ProfilePage")
                    pioneerTrialExpired = true
                    currentPage = 2
                    showCumulativeLimitReached()
                } else {
                    Log.i(TAG, "✨ 先锋用户已累计 ${totalUsed}s / 54000s，继续使用")
                    startCumulativeUsageTracking()
                }
            }
            1 -> {
                // 新用户 - 直接进入主页使用
                Log.i(TAG, "🆕 新用户，直接进入主页")
            }
            2 -> {
                // 支持者 - 初始化完成（UI层控制显示功能弹窗）
                Log.i(TAG, "💚 支持者，初始化完成")
            }
            3, 4 -> {
                // 赞助者/铁粉 - 初始化完成（UI层控制显示功能弹窗）
                Log.i(TAG, "💎 赞助者/铁粉，初始化完成")
                // launchAmapAuto() // 已注释：改为手动启动（九宫格9号按钮）
            }
            else -> {
                // 其他情况 - 默认跳转到我的界面并显示功能弹窗
                Log.w(TAG, "⚠️ 未知用户类型: $userType，跳转到我的界面")
                currentPage = 2
            }
        }
    }

    /**
     * 获取先锋用户累计使用秒数
     */
    private fun getCumulativeUsageSeconds(): Long {
        return try {
            val prefs = context.getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
            prefs.getLong("pioneer_cumulative_seconds", 0L)
        } catch (e: Exception) {
            Log.e(TAG, "❌ 读取累计时长失败: ${e.message}", e)
            0L
        }
    }

    /**
     * 启动先锋用户累计使用时长追踪
     * 每60秒写入一次，应用退出/切后台时也会保存
     */
    private fun startCumulativeUsageTracking() {
        try {
            val prefs = context.getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
            Log.i(TAG, "⏱️ 启动先锋用户累计时长追踪")

            coreScope.launch {
                val startEpoch = System.currentTimeMillis()
                while (true) {
                    delay(60_000L) // 每60秒记录一次
                    val elapsed = (System.currentTimeMillis() - startEpoch) / 1000L
                    val base = prefs.getLong("pioneer_cumulative_seconds", 0L)
                    val newTotal = base + 60
                    prefs.edit().putLong("pioneer_cumulative_seconds", newTotal).apply()
                    Log.d(TAG, "⏱️ 先锋用户累计: ${newTotal}s / 54000s (本次 ${elapsed}s)")

                    if (newTotal >= 54000) {
                        Log.w(TAG, "⚠️ 先锋用户累计使用达到54000秒（15小时）上限")
                        withContext(Dispatchers.Main) {
                            showCumulativeLimitReached()
                        }
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 启动累计追踪失败: ${e.message}", e)
        }
    }

    /**
     * 先锋用户累计试用时长已达上限，硬锁定到「我的」页面，不允许继续使用
     */
    private fun showCumulativeLimitReached() {
        pioneerTrialExpired = true
        currentPage = 2 // 锁定到「我的」页面

        coreScope.launch(Dispatchers.Main) {
            try {
                android.widget.Toast.makeText(
                    activity,
                    "先锋用户累计15小时体验已结束，请到「我的」界面赞助升级用户类型后继续使用！",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                Log.i(TAG, "🔒 先锋用户试用到期，已锁定到ProfilePage")
            } catch (e: Exception) {
                Log.e(TAG, "❌ 显示提示失败: ${e.message}", e)
            }
        }
    }

    /**
     * 强制退出应用
     */
    private fun forceExitApp() {
        try {
            Log.i(TAG, "🚪 强制退出应用")
            
            // 延迟1秒后强制退出，确保日志记录完成
            CoroutineScope(Dispatchers.Main).launch {
                delay(1000)
                Log.i(TAG, "✅ 应用即将退出")
                activity.finishAffinity()
                System.exit(0)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 强制退出失败: ${e.message}", e)
            // 即使出错也强制退出
            activity.finishAffinity()
            System.exit(0)
        }
    }


    // ===============================
    // 内存管理
    // ===============================
    
    /**
     * 启动内存监控 - 优化版：减少监控频率
     */
    fun startMemoryMonitoring() {
        memoryMonitorTimer = java.util.Timer("MemoryMonitor", true).apply {
            scheduleAtFixedRate(object : java.util.TimerTask() {
                override fun run() {
                    try {
                        val runtime = Runtime.getRuntime()
                        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
                        val maxMemory = runtime.maxMemory()
                        val usagePercent = (usedMemory.toFloat() / maxMemory * 100).toInt()
                        
                        // 优化：只在内存使用较高时才记录日志
                        if (usagePercent > 60) {
                            Log.d(TAG, "📊 内存使用: ${usedMemory / 1024 / 1024}MB / ${maxMemory / 1024 / 1024}MB ($usagePercent%)")
                        }
                        
                        if (usagePercent > 80) {
                            Log.w(TAG, "⚠️ 内存使用过高 ($usagePercent%)，触发清理")
                            performMemoryCleanup()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ 内存监控失败: ${e.message}", e)
                    }
                }
            }, 60000, 60000) // 优化：改为60秒检查一次，减少系统开销
        }
        Log.i(TAG, "📊 内存监控已启动（优化版：60秒间隔）")
    }
    
    /**
     * 获取自动超车管理器实例（如果已初始化）
     */
    fun getAutoOvertakeManagerOrNull(): AutoOvertakeManager? {
        return if (::autoOvertakeManager.isInitialized) autoOvertakeManager else null
    }

    /**
     * 获取小鸽数据接收器实例（如果已初始化）
     */
    fun getXiaogeDataReceiverOrNull(): XiaogeDataReceiver? {
        return if (::xiaogeDataReceiver.isInitialized) xiaogeDataReceiver else null
    }
    
    /**
     * 获取高德广播管理器实例（如果已初始化）
     */
    fun getAmapBroadcastManagerOrNull(): AmapBroadcastManager? {
        return if (::amapBroadcastManager.isInitialized) amapBroadcastManager else null
    }
    
    /**
     * 获取位置传感器管理器实例（如果已初始化）
     */
    fun getLocationSensorManagerOrNull(): LocationSensorManager? {
        return if (::locationSensorManager.isInitialized) locationSensorManager else null
    }
    
    /**
     * 获取权限管理器实例（如果已初始化）
     */
    fun getPermissionManagerOrNull(): PermissionManager? {
        return if (::permissionManager.isInitialized) permissionManager else null
    }
    
    /**
     * 获取网络管理器实例（如果已初始化）
     */
    fun getNetworkManagerOrNull(): NetworkManager? {
        return if (::networkManager.isInitialized) networkManager else null
    }
    
    /**
     * 获取设备管理器实例（如果已初始化）
     */
    fun getDeviceManagerOrNull(): DeviceManager? {
        return if (::deviceManager.isInitialized) deviceManager else null
    }

    /**
     * 停止内存监控
     */
    fun stopMemoryMonitoring() {
        memoryMonitorTimer?.cancel()
        memoryMonitorTimer = null
        Log.i(TAG, "📊 内存监控已停止")
    }
    
    /**
     * 清理协程作用域
     */
    fun cleanupCoroutineScope() {
        try {
            coreScope.cancel()
            Log.i(TAG, "🧹 协程作用域已清理")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ 清理协程作用域失败: ${e.message}")
        }
    }
    
    /**
     * 清理所有管理器资源
     */
    fun cleanupManagers() {
        try {
            // 清理小鸽数据接收器
            if (::xiaogeDataReceiver.isInitialized) {
                xiaogeDataReceiver.stop()
                Log.i(TAG, "🧹 小鸽数据接收器已停止")
            }

            // 清理自动超车管理器
            if (::autoOvertakeManager.isInitialized) {
                autoOvertakeManager.cleanup()
                Log.i(TAG, "🧹 自动超车管理器已清理")
            }

            // 清理 Google 导航管理器
            _googleNavManager?.destroy()
            _googleNavManager = null
            Log.i(TAG, "🧹 Google 导航管理器已清理")
            
            // 停止内存监控
            stopMemoryMonitoring()
            
            // 清理协程作用域
            cleanupCoroutineScope()
            
            Log.i(TAG, "✅ 所有管理器资源已清理")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 清理管理器资源失败: ${e.message}", e)
        }
    }

    /**
     * 执行内存清理
     */
    private fun performMemoryCleanup() {
        try {
            // 清理广播数据列表
            if (::amapBroadcastManager.isInitialized) {
                amapBroadcastManager.clearBroadcastData()
                Log.i(TAG, "🧹 已清理广播数据列表")
            }
            
            // 建议GC
            System.gc()
            Log.i(TAG, "🧹 已建议系统执行GC")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 内存清理失败: ${e.message}", e)
        }
    }

    // ===============================
    // 辅助方法
    // ===============================
    
    /**
     * 更新UI消息
     */
    fun updateUIMessage(message: String) {
        Log.i(TAG, "📱 UI更新: $message")
        // 这里可以添加实际的UI更新逻辑，比如显示Toast或更新状态栏
    }

    /**
     * 处理从静态接收器启动的Intent
     */
    fun handleIntentFromStaticReceiver(intent: Intent?) {
        if (::amapBroadcastManager.isInitialized) {
            amapBroadcastManager.handleIntentFromStaticReceiver(intent)
        } else {
            Log.w(TAG, "⚠️ 广播管理器未初始化，无法处理静态接收器Intent")
        }
    }
}
