package com.jixiexiaoge.drivingassist

import com.jixiexiaoge.drivingassist.data.toDisplaySnapshot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import com.jixiexiaoge.drivingassist.ui.theme.NavipilotTheme
import com.jixiexiaoge.drivingassist.ui.utils.localized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.content.pm.PackageManager
import com.jixiexiaoge.drivingassist.navigation.NaviWebSocketV2Client
import com.jixiexiaoge.drivingassist.navigation.NaviStreamManager
import com.jixiexiaoge.drivingassist.navigation.DrivingMode
import com.jixiexiaoge.drivingassist.navigation.NaviV2Constants
import kotlin.math.roundToInt


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

/** 应用页面路由 */
sealed class Page {
    /** CP 搭子功能 */
    data object CpFunctions : Page()
    /** 地图投屏 */
    data object Projection : Page()
    /** 投屏会员 */
    data object Membership : Page()
    /** 自动切换实验配置页 */
    data object Experiment : Page()
    /** 智能调速配置页 */
    data object SmartSpeed : Page()
}

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
        private const val KEY_DRIVING_MODE = "driving_assistance_mode"
        private const val KEY_AUTOMATIC_DRIVING_MODE = "automatic_driving_assistance_mode"
        private const val AUTOMATIC_CRUISE_CHECK_INTERVAL_MS = 250L
        private val VALID_USER_NAV_MODES = setOf("AMAP")
        
    }

    // ===============================
    // 核心状态管理
    // ===============================

    /** Comma3 CarrotMan字段映射数据 */
    val carrotManFields = mutableStateOf(CarrotManFields())
    val naviSources = com.jixiexiaoge.drivingassist.navigation.NaviSourceTracker()
    
    // 设备状态
    val deviceId = mutableStateOf("")
    val userType = mutableStateOf(0) // 用户类型：0=未知，1=新用户，2=支持者，3=赞助者，4=铁粉
    
    // 使用统计状态（仅时长，单位：分钟）
    val usageDurationMinutes = mutableStateOf(0L)
    
    // 页面状态
    var currentPage: Page by mutableStateOf(Page.CpFunctions)
    
    
    // 存储启动Intent用于页面导航
    var pendingNavigationIntent: Intent? = null
    
    // 自检查状态
    val selfCheckStatus = mutableStateOf(SelfCheckStatus())
    
    val activeNavMode = mutableStateOf("AMAP")  // 当前活跃导航模式
    /** 底部切换器上用户选择的模式 */
    var userSelectedMode by mutableStateOf("AMAP")
    var drivingMode by mutableStateOf(DrivingMode.HIGHWAY_EAP)
        private set
    var automaticDrivingMode by mutableStateOf(true)
        private set
    internal var detectedDrivingMode by mutableStateOf<DrivingMode?>(null)
        private set
    internal var drivingSceneEvidence by mutableStateOf<com.jixiexiaoge.drivingassist.navigation.DrivingSceneEvidence?>(null)
        private set
    private val modeDetector = com.jixiexiaoge.drivingassist.navigation.AutomaticDrivingMode()
    private val routeTaskTracker = com.jixiexiaoge.drivingassist.navigation.RouteTaskTracker()
    internal var routeTaskState by mutableStateOf(com.jixiexiaoge.drivingassist.navigation.RouteTaskState())
        private set
    internal var naviDeliveryState by mutableStateOf(com.jixiexiaoge.drivingassist.navigation.NaviDeliverySnapshot())
        private set
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
        try {
            val saved = context.getSharedPreferences(PREF_CARROT_AMAP, Context.MODE_PRIVATE)
                .getString(KEY_DRIVING_MODE, null)
            if (saved != null) {
                val restored = DrivingMode.fromStorage(saved)
                if (restored != null) drivingMode = restored
                else Log.w(TAG, "未知驾驶辅助模式配置，恢复高速路线提示: $saved")
            }
        } catch (e: ClassCastException) {
            Log.w(TAG, "驾驶辅助模式配置类型错误，恢复高速路线提示", e)
        }
        try {
            automaticDrivingMode = context.getSharedPreferences(PREF_CARROT_AMAP, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTOMATIC_DRIVING_MODE, true)
        } catch (e: ClassCastException) {
            Log.w(TAG, "自动场景配置类型错误，恢复自动路线提示", e)
        }
    }

    // 第二个 init 块：初始化高德画面投射管理器（必须在属性声明之后）
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

    fun selectDrivingMode(mode: DrivingMode) {
        if (drivingMode == mode && !automaticDrivingMode) return
        getAutoOvertakeManagerOrNull()?.suspendForIncompleteTelemetry()
        context.getSharedPreferences(PREF_CARROT_AMAP, Context.MODE_PRIVATE)
            .edit().putString(KEY_DRIVING_MODE, mode.storageKey)
            .putBoolean(KEY_AUTOMATIC_DRIVING_MODE, false).apply()
        automaticDrivingMode = false
        modeDetector.reset()
        detectedDrivingMode = null
        drivingSceneEvidence = null
        drivingMode = mode
        Log.i(TAG, "驾驶辅助提示模式切换: ${mode.storageKey}，不发送车辆控制命令")
    }

    fun enableAutomaticDrivingMode() {
        if (automaticDrivingMode) return
        getAutoOvertakeManagerOrNull()?.suspendForIncompleteTelemetry()
        context.getSharedPreferences(PREF_CARROT_AMAP, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTOMATIC_DRIVING_MODE, true).apply()
        modeDetector.reset()
        detectedDrivingMode = null
        drivingSceneEvidence = null
        automaticDrivingMode = true
    }

    /** Dashboard 快照重建的最小间隔（见 sampleAutomaticDrivingMode 内的说明） */
    private val DASHBOARD_PUBLISH_INTERVAL_MS = 1_000L

    /** 上次重建 dashboard 的墙钟毫秒 */
    private var lastDashboardPublishMs = 0L
    private var laneFusionRoadEligible = false

    internal fun sampleAutomaticDrivingMode(map: com.jixiexiaoge.drivingassist.navigation.AmapNavigationDisplay) {
        val now = System.currentTimeMillis()
        laneFusionRoadEligible = com.jixiexiaoge.drivingassist.navigation.isLaneGeometryRoadEligible(
            map.state, map.reportedRoadType)
        com.jixiexiaoge.drivingassist.navigation.ModelLaneCountSource.observe(
            carrotTelemetry.value, selectedCameraHost.value, carrotManFields.value.noaRouteGeneration,
            eligibleRoad = laneFusionRoadEligible,
        )
        laneFusion.value = com.jixiexiaoge.drivingassist.navigation.ModelLaneCountSource.estimate()
        val vehicle = carrotTelemetry.value.toDisplaySnapshot()
        routeTaskState = routeTaskTracker.update(map, vehicle.navigation,
            vehicle.alert?.status in setOf("critical", "userPrompt"), now)
        // Dashboard 快照重建（全量面板 + 每个 panel 的 TTL 重算）限制到 1Hz：
        // 本函数以 2Hz 被调用，而 CP 侧并不消费 dashboard，500ms 一轮只是把主线程负载翻倍。
        // routeTask / modeDetector 仍然每轮都跑（它们对时间敏感，超时判定不能跳过）。
        if (now - lastDashboardPublishMs >= DASHBOARD_PUBLISH_INTERVAL_MS) {
            lastDashboardPublishMs = now
            val sources = naviSources.snapshot()
            com.jixiexiaoge.drivingassist.data.DashboardStore.publishNavigation(
                map, routeTaskState.ready,
                sources.updatedAt + listOfNotNull(
                    sources.roadSpeedLimit?.let { "dashboard_road_limit" to it.receivedAtMs },
                    sources.guideExtras?.let { "dashboard_guide" to it.receivedAtMs },
                    (map.sectionDetails?.receivedAtMs ?: sources.updatedAt["section"])?.let { "dashboard_section" to it },
                ),
                now, System.nanoTime() / 1_000_000,
            )
            refreshDashboard()
        }
        val delivery = naviStreamManager?.deliverySnapshot()
            ?: com.jixiexiaoge.drivingassist.navigation.NaviDeliverySnapshot()
        if (naviDeliveryState != delivery) naviDeliveryState = delivery
        if (!automaticDrivingMode) return
        val guide = map.guideDetails
        val detected = modeDetector.update(map.reportedRoadType, guide?.receivedAtMs,
            guide?.routeGeneration, System.currentTimeMillis())
        if (detectedDrivingMode != detected) getAutoOvertakeManagerOrNull()?.suspendForIncompleteTelemetry()
        detectedDrivingMode = detected
        val evidence = modeDetector.evidence
        if (drivingSceneEvidence != evidence) drivingSceneEvidence = evidence
    }
    
    /**
     * 标记收到高德广播
     */
    fun markAmapBroadcastReceived() {
        lastAmapBroadcastTime.value = System.currentTimeMillis()
        activeNavMode.value = "AMAP"
        // 触发 7706 调试面板自动弹出
        show7706DebugTrigger.value++
    }

    // 实时网络流程事件（用于在主页顶部显示发现->连接链路）
    val pipelineEvents = mutableStateListOf<String>()

    // 7706 调试面板触发计数器（每次收到高德广播递增，UI 观察后自动弹出）
    val show7706DebugTrigger = mutableIntStateOf(0)

    fun addPipelineEvent(message: String) {
        // 带时间戳入队，最多保留20条
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        pipelineEvents.add("[$ts] $message")
        if (pipelineEvents.size > 40) {
            pipelineEvents.removeAt(0)
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

    // 高德地图相关管理器（已整合到AmapBroadcastHandlers中）
    // 设备管理器
    lateinit var deviceManager: DeviceManager
    
    // ===== WebSocket 数据源 =====
    var carrotWsClient: com.jixiexiaoge.drivingassist.data.CarrotWsClient? = null
    // ===== Carrot Navi v2 WebSocket 客户端（新增） =====
    var naviV2Client: com.jixiexiaoge.drivingassist.navigation.NaviWebSocketV2Client? = null
    var naviStreamManager: com.jixiexiaoge.drivingassist.navigation.NaviStreamManager? = null

    /**
     * 车端 7711 广播（`xiaoge_data.py`，Tesla `DAS_road` 红绿灯 / 停止线）。
     * 与 7000 的 compact_state 是两条独立通道：7000 走 1 Hz~20 Hz 的车端状态，
     * 7711 只有路口两字段，但那是 CAN 直读，比导航灯组更接近实景。
     */
    var xiaogeBridge: com.jixiexiaoge.drivingassist.data.XiaogeBridgeClient? = null

    // 车辆数据（兼容 XiaogeVehicleData 结构）
    val xiaogeData = mutableStateOf<XiaogeVehicleData?>(null)
    internal val dashboardSnapshot = mutableStateOf(
        com.jixiexiaoge.drivingassist.data.DashboardSnapshot(emptyList()))

    internal fun refreshDashboard() {
        dashboardPublisher.refresh()
    }
    val carrotTelemetry = mutableStateOf<com.jixiexiaoge.drivingassist.data.CompactTelemetry?>(null)
    internal val laneGeometry = mutableStateOf(com.jixiexiaoge.drivingassist.data.LaneGeometry(
        com.jixiexiaoge.drivingassist.data.LaneGeometryStatus.Missing))
    private val laneGeometryTracker = com.jixiexiaoge.drivingassist.data.LaneGeometryTracker()
    internal val laneFusion = mutableStateOf(com.jixiexiaoge.drivingassist.data.LaneFusionEstimate())

    internal fun updateLaneGeometry(telemetry: com.jixiexiaoge.drivingassist.data.CompactTelemetry?) {
        laneGeometry.value = laneGeometryTracker.update(telemetry)
        com.jixiexiaoge.drivingassist.navigation.ModelLaneCountSource.observe(
            telemetry, selectedCameraHost.value, carrotManFields.value.noaRouteGeneration,
            eligibleRoad = laneFusionRoadEligible,
        )
    }
    val compactVehicleDisplayOnly = mutableStateOf(true)

    // WebSocket 连接状态
    val wsConnected = mutableStateOf(false)
    val wsDataTimeout = mutableStateOf(false)
    internal val automationObservation: com.jixiexiaoge.drivingassist.data.AutomationObservation
        get() = com.jixiexiaoge.drivingassist.data.resolveAutomationObservation(
            wsConnected.value, wsDataTimeout.value, carrotTelemetry.value)
    internal val selectedCameraHost = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    // 自动超车管理器
    lateinit var autoOvertakeManager: AutoOvertakeManager

    // HTTP参数客户端（替代ZMQ用于参数读写）
    var carrotParamClient: CarrotParamClient? = null

    // 内存监控定时器
    var memoryMonitorTimer: java.util.Timer? = null
    
    // 🔧 修复1.1：协程作用域管理 - 使用统一的作用域，确保可以在onDestroy时取消
    private val coreScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val dashboardPublisher by lazy {
        com.jixiexiaoge.drivingassist.data.DashboardSnapshotPublisher(coreScope) {
            dashboardSnapshot.value = it
        }
    }
    private val automaticCruiseSpeedController = AutomaticCruiseSpeedController()
    private var automaticCruiseSpeedJob: Job? = null
    
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
                "com.jixiexiaoge.drivingassist.SEND_CARROT_COMMAND" -> {
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
                "com.jixiexiaoge.drivingassist.CHANGE_SPEED_MODE" -> {
                    val mode = intent.getIntExtra("mode", 0)
                    val modeNames = arrayOf("智能控速", "原车巡航", "弯道减速")
                    // 广播 extra 来自外部，必须先做范围校验再索引，越界会直接崩在 onReceive 里
                    val modeLabel = modeNames.getOrElse(mode) { "未知($mode)" }

                    Log.i(TAG, "🔄 收到模式切换广播: $modeLabel (SpeedFromPCM=$mode)")
                    
                    // 通过NetworkManager发送模式切换到设备
                    if (::networkManager.isInitialized) {
                        coreScope.launch {
                            try {
                                val result = networkManager.sendModeChangeToComma3(mode)
                                if (result.isSuccess) {
                                    Log.i(TAG, "✅ 模式切换成功: $modeLabel")
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
                "com.jixiexiaoge.drivingassist.CHANGE_AUTO_TURN_CONTROL" -> {
                    val mode = intent.getIntExtra("mode", 2)
                    val modeNames = arrayOf("禁用控制", "自动变道", "控速变道", "导航限速")
                    // 同上：外部 extra 必须先校验范围
                    val modeLabel = modeNames.getOrElse(mode) { "未知($mode)" }

                    Log.i(TAG, "🔄 收到自动转向控制模式切换广播: $modeLabel (AutoTurnControl=$mode)")

                    if (::networkManager.isInitialized) {
                        coreScope.launch {
                            try {
                                val result = networkManager.sendAutoTurnControlChangeToComma3(mode)
                                if (result.isSuccess) {
                                    Log.i(TAG, "✅ 自动转向控制模式切换成功: $modeLabel")
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
                addAction("com.jixiexiaoge.drivingassist.SEND_CARROT_COMMAND")
                addAction("com.jixiexiaoge.drivingassist.CHANGE_SPEED_MODE")
                addAction("com.jixiexiaoge.drivingassist.CHANGE_AUTO_TURN_CONTROL")
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
    
    // ===============================
    // 命令发送方法
    // ===============================
    
    /**
     * 发送Carrot命令到设备
     */
    fun sendCarrotCommand(command: String, arg: String): Boolean {
        return try {
            Log.i(TAG, "🎮 主页发送Carrot命令: $command $arg")
            
            // 检查NetworkManager是否已初始化
            if (::networkManager.isInitialized) {
                Log.d(TAG, "✅ NetworkManager已初始化，准备发送控制指令")
                val accepted = networkManager.sendControlCommand(command, arg) { reason ->
                    android.widget.Toast.makeText(
                        activity,
                        localized(
                            "指令投递中断，未确认执行：$reason",
                            "Command delivery interrupted; execution unconfirmed: $reason",
                        ),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                if (accepted) {
                    Log.i(TAG, "✅ 指令已进入发送队列: $command $arg")
                } else {
                    // 没连上设备 / 上一条仍在发送 / 命令名不支持：必须给用户明确反馈，
                    // 否则变道按钮照常播放音效，看起来"点了但车没反应"。
                    android.widget.Toast.makeText(
                        activity,
                        localized("指令未发送：设备未连接或上一条指令仍在执行", "Command not sent: device offline or busy"),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
                accepted
            } else {
                Log.w(TAG, "⚠️ NetworkManager未初始化，无法发送指令")
                Log.w(TAG, "⚠️ 请等待网络服务启动完成后再试")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 发送Carrot命令失败: ${e.message}", e)
            false
        }
    }

    fun startAutomaticCruiseSpeedMonitoring() {
        if (automaticCruiseSpeedJob?.isActive == true) return
        automaticCruiseSpeedJob = coreScope.launch {
            while (isActive) {
                evaluateAutomaticCruiseSpeed()
                delay(AUTOMATIC_CRUISE_CHECK_INTERVAL_MS)
            }

        }
    }

    fun resetSpeedRecovery() {
        automaticCruiseSpeedController.reset()
        getAmapBroadcastManagerOrNull()?.resetSpeedRecovery()
    }

    private fun evaluateAutomaticCruiseSpeed() {
        val monotonicNowMs = System.nanoTime() / 1_000_000
        val wallNowMs = System.currentTimeMillis()
        val telemetry = carrotTelemetry.value?.copy(observedAtMs = monotonicNowMs)
        val controls = telemetry?.fresh("controlsState")?.frame?.values
        val carControl = telemetry?.fresh("carControl")?.frame?.values
        val cruiseSetKph = (controls?.get("vCruiseCluster") as? Number)
            ?.toFloat()
            ?.takeIf { it.isFinite() }
            ?.roundToInt()
        val sources = naviSources.snapshot()
        val fields = carrotManFields.value
        val routeBoundary = sources.updatedAt["ui_route_changed"] ?: Long.MIN_VALUE
        val rawRoadLimit = sources.roadSpeedLimit?.takeIf {
            activeNavMode.value == "AMAP" &&
                fields.isNavigating &&
                it.routeGeneration == fields.noaRouteGeneration &&
                wallNowMs - it.receivedAtMs in 0..NaviV2Constants.STALE_TIMEOUT_MS &&
                it.receivedAtMs >= routeBoundary
        }
        val targetKph = automaticCruiseSpeedController.update(
            AutomaticCruiseSpeedInput(
                cruiseEngaged = controls?.get("enabled") == true &&
                    carControl?.get("longActive") == true,
                cruiseSetKph = cruiseSetKph,
                roadLimitKph = rawRoadLimit?.limitKph,
                routeGeneration = rawRoadLimit?.routeGeneration,
                recoveryCapKph = getAmapBroadcastManagerOrNull()?.automaticCruiseCap(),
            ),
            monotonicNowMs,
        )
        automaticCruiseSpeedController.takeRecoveryCompletion()?.let {
            getAmapBroadcastManagerOrNull()?.completeSpeedRecovery(it)
        }
        if (targetKph == null) return

        if (sendCarrotCommand("SPEED", targetKph.toString())) {
            automaticCruiseSpeedController.recordSent(targetKph, monotonicNowMs)
            android.widget.Toast.makeText(
                activity,
                "已按道路限速自动设置巡航速度 $targetKph km/h",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            Log.i(TAG, "自动巡航提速已发送: $targetKph km/h")
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
                if (sendCarrotCommand("SPEED", roadLimitSpeed.toString())) {
                    Log.i(TAG, "✅ 道路限速已进入发送队列: ${roadLimitSpeed}km/h")
                } else {
                    Log.w(TAG, "⚠️ 道路限速发送被拒绝: ${roadLimitSpeed}km/h")
                }
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
            automaticCruiseSpeedController.reset()
            automaticCruiseSpeedJob = null
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
        selectedCameraHost.value = null
        laneFusionRoadEligible = false
        com.jixiexiaoge.drivingassist.navigation.ModelLaneCountSource.observe(
            null, null, -1, eligibleRoad = false,
        )
        com.jixiexiaoge.drivingassist.data.DashboardStore.clear()
        try {
            // 7711 世界量通道（Tesla 红绿灯 / 停止线）
            xiaogeBridge?.close()
            xiaogeBridge = null
            // 清理 WebSocket 客户端
            carrotWsClient?.disconnect()
            carrotWsClient = null
            Log.i(TAG, "🧹 WebSocket 客户端已清理")

            // 清理自动超车管理器
            if (::autoOvertakeManager.isInitialized) {
                autoOvertakeManager.cleanup()
                Log.i(TAG, "🧹 自动超车管理器已清理")
            }

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
