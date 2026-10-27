package com.jixiexiaoge.drivingassist

import com.jixiexiaoge.drivingassist.data.toDisplayVehicleData

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import com.jixiexiaoge.drivingassist.navigation.NaviWebSocketV2Client
import com.jixiexiaoge.drivingassist.navigation.NaviStreamManager

/**
 * MainActivity生命周期管理类
 * 负责Activity生命周期管理、初始化流程、自检查等
 */
class MainActivityLifecycle(
    private val activity: ComponentActivity,
    private val core: MainActivityCore
) {
    companion object {
        private const val TAG = AppConstants.Logging.MAIN_ACTIVITY_TAG
    }
    
    // 生命周期绑定的协程作用域，onDestroy 时统一取消
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 网络状态监控Job
    private var networkStatusMonitoringJob: Job? = null
    private var isActivityForeground = false

    // ===============================
    // Activity生命周期管理
    // ===============================
    
    /**
     * Activity创建时的处理
     */
    fun onCreate(savedInstanceState: Bundle?) {
        // 保持屏幕常亮
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.i(TAG, "🔆 已设置屏幕常亮")

        // 🔧 修复：先不请求电池优化和通知权限，避免与位置权限弹窗冲突
        // 这些权限延迟到位置权限获取后再请求（在自检查流程中）
        
        // 启动前台服务
        core.startForegroundService()

        Log.i(TAG, "🚀 MainActivity正在启动...")

        // 立即初始化权限管理器，在Activity早期阶段
        initializePermissionManagerEarly()
        
        // 立即设置用户界面，避免白屏
        setupUserInterface()
        
        // 存储Intent用于后续页面导航
        core.pendingNavigationIntent = activity.intent

        // 开始自检查流程
        startSelfCheckProcess()
        
        // 启动内存监控
        core.startMemoryMonitoring()
        
        // 注册控制指令广播接收器
        core.registerCarrotCommandReceiver()

        Log.i(TAG, "✅ MainActivity启动完成")
    }
    
    /**
     * 处理新的Intent
     */
    fun onNewIntent(intent: Intent) {
        Log.i(TAG, "📱 收到新的Intent")
        // 保存Intent供后续使用
        core.pendingNavigationIntent = intent
    }

    /**
     * Activity暂停时的处理
     */
    fun onPause() {
        Log.i(TAG, "⏸️ Activity暂停")
        isActivityForeground = false
        core.naviStreamManager?.sendAppStatus(false)
        
        // 记录使用时长（检查是否已初始化）
        try {
            core.deviceManager.recordAppUsage()
        } catch (e: UninitializedPropertyAccessException) {
            Log.d(TAG, "📝 deviceManager未初始化，跳过使用统计记录")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ 记录使用时长失败: ${e.message}")
        }
        
        // 设置网络管理器为后台模式，调整网络策略
        try {
            core.networkManager.setBackgroundState(true)
            Log.i(TAG, "🔄 网络管理器已切换到后台模式")
        } catch (e: UninitializedPropertyAccessException) {
            Log.d(TAG, "📝 networkManager未初始化，跳过后台状态设置")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ 设置后台状态失败: ${e.message}")
        }
        
        // 注意：不暂停GPS更新，让GPS在后台继续工作
        Log.i(TAG, "🌍 GPS位置更新在后台继续运行")
    }

    /**
     * Activity恢复时的处理
     */
    fun onResume() {
        Log.i(TAG, "▶️ Activity恢复")
        isActivityForeground = true
        core.naviStreamManager?.sendAppStatus(true)
        
        // 设置网络管理器为前台模式，恢复正常网络策略
        try {
            core.networkManager.setBackgroundState(false)
            Log.i(TAG, "🔄 网络管理器已切换到前台模式")
        } catch (e: UninitializedPropertyAccessException) {
            Log.d(TAG, "📝 networkManager未初始化，跳过前台状态设置")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ 设置前台状态失败: ${e.message}")
        }
        
        // 重新设置屏幕常亮，确保不会被清除
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // 🔧 修复：恢复时检查位置权限，如果已授予但GPS未启动则立即启动
        // 解决用户在系统设置中手动授予权限后返回app的场景
        try {
            if (core.permissionManager.isLocationPermissionGranted()) {
                val fields = core.carrotManFields.value
                // 如果位置数据为空（GPS未启动），立即启动位置更新
                if (fields.latitude == 0.0 && fields.longitude == 0.0) {
                    Log.i(TAG, "📍 检测到位置权限已授予但GPS未启动，立即启动位置更新")
                    core.locationSensorManager.startLocationUpdates()
                }
            }
        } catch (e: UninitializedPropertyAccessException) {
            Log.d(TAG, "📝 permissionManager或locationSensorManager未初始化，跳过位置检查")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ 恢复时位置检查失败: ${e.message}")
        }
    }

    /**
     * Activity销毁时的处理
     * 🔧 关键修复：使用异步清理避免阻塞主线程，防止ANR和卡顿
     */
    fun onDestroy() {
        Log.i(TAG, "🔧 MainActivity正在销毁，清理资源...")

        try {
            // 先取消生命周期 scope，子协程随即停止
            lifecycleScope.cancel()

            // 🔧 立即停止监控协程（轻量级操作，可以同步执行）
            stopNetworkStatusMonitoring()
            stopConditionalExperimentCheck()
            core.stopMemoryMonitoring()
            core.cleanupCoroutineScope()
            
            // 🔧 在主线程上执行必须同步的轻量级操作
            core.stopForegroundService()
            core.unregisterCarrotCommandReceiver()
            
            // 🔧 关键修复：在后台线程异步清理重量级资源，避免阻塞主线程
            // 使用IO调度器执行耗时的清理操作，避免"Skipped frames"警告
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    Log.i(TAG, "🧹 开始后台清理重量级资源...")
                    
                    // 记录应用使用时长（可能涉及IO操作）
                    try {
                        core.deviceManager.recordAppUsage()
                    } catch (e: UninitializedPropertyAccessException) {
                        Log.d(TAG, "📝 deviceManager未初始化，跳过使用统计记录")
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ 记录使用时长失败: ${e.message}")
                    }
                    
                    // 清理广播管理器（可能涉及IPC操作）
                    try {
                        core.amapBroadcastManager.unregisterReceiver()
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ 清理广播管理器失败: ${e.message}")
                    }
                    
                    // 清理位置传感器管理器（可能涉及系统服务注销）
                    try {
                        core.locationSensorManager.cleanup()
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ 清理位置传感器管理器失败: ${e.message}")
                    }
                    
                    // 清理权限管理器
                    try {
                        core.permissionManager.cleanup()
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ 清理权限管理器失败: ${e.message}")
                    }
                    
                    // 清理网络管理器（可能涉及socket关闭等耗时操作）
                    try {
                        core.networkManager.cleanup()
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ 清理网络管理器失败: ${e.message}")
                    }
                    
                    // 停止条件实验模式管理器
                    try {
                        val manager = core.getConditionalExperimentManagerSafely()
                        manager?.cleanup()
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ 停止条件实验模式管理器失败: ${e.message}")
                    }
                    
                    // 清理设备管理器
                    try {
                        core.deviceManager.cleanup()
                    } catch (e: UninitializedPropertyAccessException) {
                        Log.d(TAG, "📝 deviceManager未初始化，跳过清理")
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ 清理设备管理器失败: ${e.message}")
                    }
                    
                    // 停止 WebSocket 客户端
                    try {
                        core.carrotWsClient?.close()
                        Log.i(TAG, "✅ WebSocket 客户端已停止")
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ 停止 WebSocket 客户端失败: ${e.message}")
                    }
                    // 停止 Carrot Navi v2 客户端
                    try {
                        core.naviStreamManager?.stop()
                        core.naviV2Client?.stop()
                        Log.i(TAG, "✅ Carrot Navi v2 客户端已停止")
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ 停止 Navi v2 客户端失败: ${e.message}")
                    }

                    // 关闭 7711 世界量通道（TCP 长连 + 无限重连协程）。
                    // 旧实现在这里漏了 7711：Activity 销毁后 socket 与重连循环仍在运行，
                    // 车机反复重建会叠加出多条 7711 连接，且 DashboardStore 的进程级状态一直残留。
                    try {
                        core.xiaogeBridge?.close()
                        com.jixiexiaoge.drivingassist.data.DashboardStore.clear()
                        Log.i(TAG, "✅ 7711 世界量通道已关闭")
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ 关闭 7711 世界量通道失败: ${e.message}")
                    }
                    

                    // 清理HTTP参数客户端
                    core.carrotParamClient = null
                    Log.i(TAG, "✅ HTTP参数客户端已清理")

                    // 清理自动超车管理器
                    try {
                        core.getAutoOvertakeManagerOrNull()?.cleanup()
                        Log.i(TAG, "✅ 自动超车管理器已清理")
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ 清理自动超车管理器失败: ${e.message}")
                    }
                    
                    Log.i(TAG, "✅ 所有监听器已注销并释放资源（后台清理完成）")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 后台清理资源异常: ${e.message}", e)
                }
            }
            
            Log.i(TAG, "✅ 主线程清理完成，重量级清理在后台进行")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 资源清理失败: ${e.message}", e)
        }
    }

    /**
     * 重写onBackPressed，防止用户意外退出
     */
    fun onBackPressed() {
        // 不调用super.onBackPressed()，防止退出应用
        Log.i(TAG, "🔙 拦截返回键，防止退出应用")
    }

    // ===============================
    // 初始化流程管理
    // ===============================
    
    /**
     * 设置权限和位置服务
     */
    private fun setupPermissionsAndLocation() {
        try {
            core.permissionManager.smartPermissionRequest()
            
            // 输出权限状态报告
            val permissionReport = core.permissionManager.getPermissionStatusReport()
            Log.i(TAG, permissionReport)
        } catch (e: Exception) {
            Log.e(TAG, "❌ 权限设置失败: ${e.message}", e)
        }
    }

    /**
     * 初始化广播管理器
     */
    private fun initializeBroadcastManager() {
        Log.i(TAG, "📡 初始化广播管理器...")

        try {
            core.amapBroadcastManager = AmapBroadcastManager(
                activity, 
                core.carrotManFields, 
                core.networkManager,
                onBroadcastReceived = { core.markAmapBroadcastReceived() },
                naviSources = core.naviSources,
                isActiveSource = { core.activeNavMode.value == "AMAP" },
            )
            val success = core.amapBroadcastManager.registerReceiver()

            if (success) {
                Log.i(TAG, "✅ 广播管理器初始化成功")
            } else {
                Log.e(TAG, "❌ 广播管理器初始化失败")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ 广播管理器初始化异常: ${e.message}", e)
        }
    }

    /**
     * 初始化设备管理器
     */
    private suspend fun initializeDeviceManager() {
        Log.i(TAG, "📱 初始化设备管理器...")

        try {
            core.deviceManager = DeviceManager(activity)

            // 获取设备ID并更新UI（Compose State 必须在主线程写；本函数跑在 Dispatchers.IO）
            val id = core.deviceManager.getDeviceId()
            withContext(Dispatchers.Main) { core.deviceId.value = id }

            // 记录应用启动（在设备管理器初始化后）
            core.deviceManager.recordAppStart()

            Log.i(TAG, "✅ 设备管理器初始化成功，设备ID: $id")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 设备管理器初始化失败: ${e.message}", e)
        }
    }

    /**
     * 早期初始化权限管理器（在Activity早期阶段）
     */
    private fun initializePermissionManagerEarly() {
        Log.i(TAG, "🔐 早期初始化权限管理器...")

        try {
            // 创建一个临时的LocationSensorManager用于权限管理器初始化
            val tempCarrotManFields = mutableStateOf(CarrotManFields())
            val tempLocationSensorManager = LocationSensorManager(activity, tempCarrotManFields)
            core.permissionManager = PermissionManager(activity, tempLocationSensorManager)
            // 在Activity早期阶段初始化，此时可以安全注册ActivityResultLauncher
            core.permissionManager.initialize()
            Log.i(TAG, "✅ 权限管理器早期初始化成功")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 权限管理器早期初始化失败: ${e.message}", e)
        }
    }

    /**
     * 初始化权限管理器（在自检查流程中）
     */
    private fun initializePermissionManager() {
        Log.i(TAG, "🔐 初始化权限管理器...")

        try {
            // 更新权限管理器中的locationSensorManager引用
            core.permissionManager.updateLocationSensorManager(core.locationSensorManager)
            Log.i(TAG, "✅ 权限管理器引用更新成功")
            
            // GPS预热：提前开始位置获取
            startGpsWarmup()
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 权限管理器初始化失败: ${e.message}", e)
        }
    }

    /**
     * GPS预热：提前开始位置获取
     */
    private fun startGpsWarmup() {
        try {
            Log.i(TAG, "🌡️ 开始GPS预热...")
            // 启动GPS位置更新，提前获取位置数据
            // 🔧 修复：使用实际存在的 startLocationUpdates() 方法
            core.locationSensorManager.startLocationUpdates()
            Log.i(TAG, "✅ GPS预热已启动")
        } catch (e: Exception) {
            Log.e(TAG, "❌ GPS预热失败: ${e.message}", e)
        }
    }

    /**
     * 初始化位置和传感器管理器
     */
    private fun initializeLocationSensorManager() {
        Log.i(TAG, "🧭 初始化位置和传感器管理器...")

        try {
            core.locationSensorManager = LocationSensorManager(activity, core.carrotManFields, core.naviSources)
            core.locationSensorManager.initializeSensors()
            
            // 🚀 关键修复：立即启动GPS位置更新服务
            // 这样可以确保手机GPS数据能够实时更新到carrotManFields中
            Log.i(TAG, "📍 正在启动GPS位置更新服务...")
            core.locationSensorManager.startLocationUpdates()
            
            Log.i(TAG, "✅ 位置和传感器管理器初始化成功（GPS已启动）")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 位置和传感器管理器初始化失败: ${e.message}", e)
        }
    }

    /**
     * 初始化网络管理器（仅初始化，不启动网络服务）
     */
    /**
     * 启动 7711 世界量通道（Tesla 红绿灯 / 停止线）。
     *
     * 客户端只负责收帧与断线重连，收到什么原样交给 `DashboardStore`，
     * 过期判定与「灯色 / 停止线是否可见」都在数据层做，UI 不重复判断。
     */
    private fun startXiaogeBridge() {
        if (core.xiaogeBridge != null) return
        val bridge = com.jixiexiaoge.drivingassist.data.XiaogeBridgeClient(lifecycleScope)
        core.xiaogeBridge = bridge
        lifecycleScope.launch {
            bridge.world.collect { com.jixiexiaoge.drivingassist.data.DashboardStore.publishWorld(it) }
        }
        bridge.connect(core.networkManager.getCurrentDeviceIP())
        Log.i(TAG, "🌉 7711 世界量通道已启动")
    }

    private fun initializeNetworkManagerOnly() {
        Log.i(TAG, "🌐 初始化网络管理器（延迟启动网络服务）...")

        try {
            core.networkManager = NetworkManager(activity, core.carrotManFields)
            core.networkManager.setOnDeviceIPUpdated { deviceIP ->
                if (core.selectedCameraHost.value != deviceIP.takeIf { it.isNotBlank() }) {
                    core.resetSpeedRecovery()
                }
                core.selectedCameraHost.value = deviceIP.takeIf { it.isNotBlank() }
                com.jixiexiaoge.drivingassist.navigation.ModelLaneCountSource.observe(null,
                    core.selectedCameraHost.value, core.carrotManFields.value.noaRouteGeneration)
                // 车端 7711 广播同一台设备，IP 一变就跟着换
                core.xiaogeBridge?.connect(deviceIP)
            }
            startXiaogeBridge()
            
            // 初始化条件实验模式管理器（使用lambda获取HTTP参数客户端）
            core.conditionalExperimentManager = ConditionalExperimentManager(
                context = activity,
                getParamClient = { core.carrotParamClient }
            )
            Log.i(TAG, "✅ 条件实验模式管理器初始化成功")
            
            // 启动条件实验模式监控（如果功能已启用）
            core.conditionalExperimentManager.startMonitoring()
            
            // 启动条件实验模式检查循环
            startConditionalExperimentCheck()
            
            // 启动网络状态监控
            startNetworkStatusMonitoring()

            core.startAutomaticCruiseSpeedMonitoring()
            
            // 仅创建NetworkManager实例，不启动网络服务
            Log.i(TAG, "✅ 网络管理器初始化成功（网络服务待启动）")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 网络管理器初始化失败: ${e.message}", e)
        }
    }

    /**
     * 启动网络状态监控
     */
    private fun startNetworkStatusMonitoring() {
        // 🔧 关键修复：使用Job跟踪协程，确保可以在onDestroy时停止
        networkStatusMonitoringJob = lifecycleScope.launch {
            try {
                while (isActive) { // 使用isActive检查协程是否被取消
                    try {
                        val connectionStatus = core.networkManager.getConnectionStatus()
                        val deviceInfo = connectionStatus["currentDevice"] as? String ?: ""
                        val isRunning = connectionStatus["isRunning"] as? Boolean ?: false
                        
                        // 改进日志显示逻辑：只有在网络未运行或明确断开连接时才记录警告
                        // 如果网络正在运行但设备信息为"无连接"，说明只是还没发现设备，这是正常的
                        if (isRunning && deviceInfo == "无连接") {
                            // 网络运行中但还没发现设备，使用VERBOSE级别
                            Log.v(TAG, "🔍 网络状态监控 (运行中，搜索设备...)")
                        } else {
                            // 其他情况正常记录
                            //Log.d(TAG, "🌐 网络状态监控: $status, 设备: $deviceInfo")
                        }
                    } catch (e: UninitializedPropertyAccessException) {
                        // NetworkManager还未初始化，跳过本次更新
                        Log.d(TAG, "🔍 NetworkManager未初始化，跳过状态更新")
                    } catch (e: CancellationException) {
                        // 协程被取消，正常退出
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ 网络状态监控异常: ${e.message}")
                    }
                    
                    delay(2000) // 每2秒更新一次
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "⏹️ 网络状态监控已停止（协程已取消）")
            }
        }
        Log.i(TAG, "🔍 网络状态监控已启动")
    }
    
    /**
     * 停止网络状态监控
     */
    private fun stopNetworkStatusMonitoring() {
        networkStatusMonitoringJob?.cancel()
        networkStatusMonitoringJob = null
        Log.i(TAG, "⏹️ 停止网络状态监控")
    }

    // 条件实验模式检查Job
    private var conditionalExperimentCheckJob: Job? = null
    
    /**
     * 启动条件实验模式检查循环
     */
    private fun startConditionalExperimentCheck() {
        conditionalExperimentCheckJob = lifecycleScope.launch(Dispatchers.Default) {
            try {
                while (isActive) {
                    try {
                        // 交给 manager 统一判断主开关和条件，避免生命周期层与 manager 各自维护轮询逻辑。
                        val xiaogeData = core.xiaogeData.value
                        val carrotManFields = core.carrotManFields.value
                        val manager = core.getConditionalExperimentManagerSafely()
                        if (manager != null && !core.compactVehicleDisplayOnly.value) {
                            manager.performModeSwitch(
                                xiaogeData,
                                carrotManFields
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ 条件实验模式检查异常: ${e.message}")
                    }
                    
                    delay(500) // 每500ms检查一次
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "⏹️ 条件实验模式检查已停止")
            }
        }
        Log.i(TAG, "🔍 条件实验模式检查循环已启动")
    }
    
    /**
     * 停止条件实验模式检查循环
     */
    private fun stopConditionalExperimentCheck() {
        conditionalExperimentCheckJob?.cancel()
        conditionalExperimentCheckJob = null
        Log.i(TAG, "⏹️ 停止条件实验模式检查循环")
    }

    /**
     * 启动网络服务（延迟启动）
     */
    private fun startNetworkService() {
        Log.i(TAG, "🌐 启动网络服务...")

        try {
            val success = core.networkManager.initializeNetworkClient()
            if (success) {
                Log.i(TAG, "✅ 网络服务启动成功")
            } else {
                Log.e(TAG, "❌ 网络服务启动失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 网络服务启动失败: ${e.message}", e)
        }
    }

    /**
     * 初始化高德地图管理器
     * 🎯 已整合：所有功能已整合到AmapBroadcastHandlers中，无需单独初始化
     */
    private fun initializeAmapManagers() {
        Log.i(TAG, "🗺️ 高德地图管理器已整合到AmapBroadcastHandlers，无需单独初始化")
        // 所有高德地图相关功能已整合到AmapBroadcastHandlers中
        // AmapBroadcastManager会自动创建AmapBroadcastHandlers实例
    }

    /**
     * 执行初始位置更新
     */
    private fun performInitialLocationUpdate() {
        Log.i(TAG, "🚀 执行初始位置更新...")

        lifecycleScope.launch {
            try {
                val currentFields = core.carrotManFields.value
                val latitude = if (currentFields.vpPosPointLat != 0.0) currentFields.vpPosPointLat else 39.9042
                val longitude = if (currentFields.vpPosPointLon != 0.0) currentFields.vpPosPointLon else 116.4074
                val source = if (currentFields.vpPosPointLat != 0.0) "缓存/GPS" else "默认(北京)"
                Log.i(TAG, "📍 初始位置: lat=$latitude, lon=$longitude ($source)")
            } catch (e: Exception) {
                Log.e(TAG, "❌ 初始位置更新失败: ${e.message}", e)
            }
        }
    }

    // ===============================
    // 自检查流程管理
    // ===============================
    
    /**
     * 开始自检查流程 - 优化版：异步初始化
     */
    private fun startSelfCheckProcess() {
        // 使用IO调度器在后台线程执行初始化，避免阻塞主线程
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "🚀 开始异步自检查流程...")
                
                // 1. 网络管理器初始化（仅创建实例，不启动服务）
                updateSelfCheckStatusAsync("网络管理器", "正在初始化...", false)
                initializeNetworkManagerOnly()
                updateSelfCheckStatusAsync("网络管理器", "初始化完成", true)
                delay(100)

                // 2. 位置和传感器管理器初始化（主线程）
                updateSelfCheckStatusAsync("位置传感器管理器", "正在初始化...", false)
                withContext(Dispatchers.Main) { // LocationManager requires main thread
                    initializeLocationSensorManager()
                }
                updateSelfCheckStatusAsync("位置传感器管理器", "初始化完成", true)
                delay(100) // 减少延迟时间

                // 4. 权限管理器初始化（主线程）
                updateSelfCheckStatusAsync("权限管理器", "正在初始化...", false)
                withContext(Dispatchers.Main) { // PermissionManager might interact with UI/LocationManager
                    initializePermissionManager()
                }
                updateSelfCheckStatusAsync("权限管理器", "初始化完成", true)
                delay(100)

                // 5. 权限管理和位置服务初始化（主线程）— 位置权限优先请求
                updateSelfCheckStatusAsync("权限和位置服务", "正在设置...", false)
                withContext(Dispatchers.Main) { // LocationManager requires main thread
                    setupPermissionsAndLocation()
                }
                updateSelfCheckStatusAsync("权限和位置服务", "设置完成", true)
                delay(100)

                // 5.5 🔧 延迟请求电池优化和通知权限，避免与位置权限弹窗冲突
                // 等待一段时间让位置权限弹窗先处理完
                delay(2000)
                withContext(Dispatchers.Main) {
                    core.requestIgnoreBatteryOptimizations()
                }
                // 延迟后再请求通知权限，避免同时请求多个权限冲突
                delay(1500)
                withContext(Dispatchers.Main) {
                    core.requestNotificationPermissionIfNeeded()
                }
                delay(100)

                // 7-9. 并行初始化高德地图、广播和设备管理器（后台线程）
                updateSelfCheckStatusAsync("系统管理器", "正在并行初始化...", false)
                
                // 并行执行三个管理器的初始化
                val amapJob = launch(Dispatchers.IO) {
                    initializeAmapManagers()
                }
                val broadcastJob = launch(Dispatchers.IO) {
                    initializeBroadcastManager()
                }
                val deviceJob = launch(Dispatchers.IO) {
                    initializeDeviceManager()
                }
                
                // 等待所有并行任务完成
                amapJob.join()
                broadcastJob.join()
                deviceJob.join()
                
                updateSelfCheckStatusAsync("系统管理器", "并行初始化完成", true)
                delay(100)

                delay(50)

                // 10. 执行初始位置更新（主线程）
                updateSelfCheckStatusAsync("位置更新", "正在执行...", false)
                withContext(Dispatchers.Main) { // LocationManager requires main thread
                    performInitialLocationUpdate()
                }
                updateSelfCheckStatusAsync("位置更新", "执行完成", true)
                delay(100)

                // 9. 处理静态接收器Intent（后台线程）
                updateSelfCheckStatusAsync("静态接收器", "正在处理...", false)
                core.handleIntentFromStaticReceiver(activity.intent)
                updateSelfCheckStatusAsync("静态接收器", "处理完成", true)
                delay(50)

                // 10. 使用统计更新
                updateSelfCheckStatusAsync("使用统计", "后台更新中...", false)
                launch(Dispatchers.IO) {
                    try {
                        val durationMinutes = core.deviceManager.getTotalUsageDurationMinutes()
                        withContext(Dispatchers.Main) {
                            core.usageDurationMinutes.value = durationMinutes
                            updateSelfCheckStatus("使用统计", "更新完成", true)
                        }
                    } catch (e: UninitializedPropertyAccessException) {
                        Log.d(TAG, "📝 deviceManager未初始化，跳过使用统计更新")
                        withContext(Dispatchers.Main) {
                            updateSelfCheckStatus("使用统计", "设备管理器未初始化", false)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ 自动更新使用时长失败: ${e.message}", e)
                        withContext(Dispatchers.Main) {
                            updateSelfCheckStatus("使用统计", "更新失败: ${e.message}", false)
                        }
                    }
                }
                delay(50)

                // 11. 启动网络服务（NetworkManager）
                updateSelfCheckStatusAsync("网络服务", "正在启动...", false)
                startNetworkService()
                updateSelfCheckStatusAsync("网络服务", "启动完成", true)
                delay(100)

                // 6. 获取和显示IP地址信息（网络服务已启动，可以正确获取IP）
                updateSelfCheckStatusAsync("IP地址信息", "正在获取...", false)
                val ipPhone = try { core.networkManager.getPhoneIP() } catch (_: Exception) { "获取失败" }
                val ipDevice = try { core.networkManager.getCurrentDeviceIP() } catch (_: Exception) { null }
                val ipInfo = "手机: $ipPhone, 设备: ${ipDevice ?: "未连接"}"
                Log.i(TAG, "📱 IP地址信息: $ipInfo")
                updateSelfCheckStatusAsync("IP地址信息", ipInfo, true)
                delay(100)

                // 12. 等待设备发现并启动 WebSocket 客户端
                waitForDeviceAndStartXiaogeReceiver()

                // 13. 初始化 AutoOvertakeManager
                updateSelfCheckStatusAsync("自动超车管理器", "正在初始化...", false)
                try {
                    core.autoOvertakeManager = AutoOvertakeManager(activity, core.networkManager)
                    updateSelfCheckStatusAsync("自动超车管理器", "初始化完成", true)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 自动超车管理器初始化失败: ${e.message}", e)
                    updateSelfCheckStatusAsync("自动超车管理器", "初始化失败: ${e.message}", false)
                }
                delay(50)

                // 13.5. 初始化 Carrot Navi v2 WebSocket 客户端（与旧版 UDP 共存）
                updateSelfCheckStatusAsync("Navi v2 连接", "正在初始化...", false)
                initializeNaviV2Client()
                updateSelfCheckStatusAsync("Navi v2 连接", "初始化完成", true)
                delay(50)
                
                // 13.5. [已移除] 驾驶评分数据采集器（模块暂未恢复）
                updateSelfCheckStatusAsync("驾驶评分系统", "模块暂未加载", false)
                delay(50)

                // 14. 设置UI界面（后台线程）
                updateSelfCheckStatusAsync("用户界面", "正在设置...", false)
                updateSelfCheckStatusAsync("用户界面", "设置完成", true)
                delay(50)

                // 所有检查完成
                updateSelfCheckStatusAsync("系统检查", "所有检查完成", true)
                withContext(Dispatchers.Main) {
                    core.selfCheckStatus.value = core.selfCheckStatus.value.copy(isCompleted = true)
                }

                Log.i(TAG, "✅ 异步自检查流程完成")

            } catch (e: Exception) {
                Log.e(TAG, "❌ 异步自检查流程失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    updateSelfCheckStatus("系统检查", "检查失败: ${e.message}", false)
                }
            }
        }
    }

    /**
     * 等待设备发现并启动 WebSocket 客户端（替代 XiaogeDataReceiver）
     */
    private suspend fun waitForDeviceAndStartXiaogeReceiver() {
        updateSelfCheckStatusAsync("车辆数据连接", "正在初始化...", false)

        try {
            // 创建 WebSocket 客户端
            val wsClient = com.jixiexiaoge.drivingassist.data.CarrotWsClient(lifecycleScope)
            core.carrotWsClient = wsClient

            // 监听连接状态
            lifecycleScope.launch {
                wsClient.connectionState.collect { state ->
                    core.wsConnected.value = (state == com.jixiexiaoge.drivingassist.data.ConnectionState.CONNECTED)
                }
            }

            // 监听数据超时
            lifecycleScope.launch {
                wsClient.isDataTimeout.collect { timeout ->
                    core.wsDataTimeout.value = timeout
                }
            }
            lifecycleScope.launch {
                wsClient.compactState.collect {
                    core.carrotTelemetry.value = it
                    core.getAmapBroadcastManagerOrNull()?.updateSpeedCurve(it)
                    com.jixiexiaoge.drivingassist.data.DashboardStore.telemetry = it
                    core.refreshDashboard()
                    core.updateLaneGeometry(it)
                }
            }
            lifecycleScope.launch {
                wsClient.decodeFailure.collect { failure ->
                    if (failure != null) Log.e(TAG, "7000 数据解析失败: $failure")
                }
            }
            lifecycleScope.launch {
                wsClient.connectionError.collect { error ->
                    if (error != null) Log.e(TAG, "7000 连接错误: $error")
                }
            }

            // Compact仅供展示，不携带完整的驾驶员接管/故障/控制授权信息。
            lifecycleScope.launch {
                wsClient.vehicleData.collect { vd ->
                    core.getAutoOvertakeManagerOrNull()?.suspendForIncompleteTelemetry()
                    core.compactVehicleDisplayOnly.value = true
                    core.xiaogeData.value = vd?.toDisplayVehicleData(core.carrotManFields.value.nTBTDist)
                }
            }

            // 设置网络回调：获取设备 IP 后连接
            core.networkManager.setOnDeviceIPUpdated { deviceIP ->
                if (deviceIP.isNotEmpty()) {
                    wsClient.connect(deviceIP)
                    // HTTP 参数客户端
                    core.carrotParamClient = com.jixiexiaoge.drivingassist.CarrotParamClient(deviceIP)
                    Log.i(TAG, "7000 compact 客户端开始连接: $deviceIP")
                }
            }

            updateSelfCheckStatusAsync("车辆数据连接", "等待设备IP...", true)

        } catch (e: Exception) {
            Log.e(TAG, "❌ WebSocket 客户端初始化失败: ${e.message}")
            updateSelfCheckStatusAsync("车辆数据连接", "初始化失败: ${e.message}", false)
        }
    }

    /** Navi v2 数据推送协程 */
    private var naviV2SendJob: Job? = null

    /**
     * 初始化 Carrot Navi v2 WebSocket 客户端
     * 与旧版 UDP NetworkManager 共存，旧版 UDP 通道不受影响
     */
    private fun initializeNaviV2Client() {
        try {
            val v2Client = NaviWebSocketV2Client()
            core.naviV2Client = v2Client

            val guidancePrefs = activity.getSharedPreferences("CarrotAmap", Context.MODE_PRIVATE)
            val streamManager = NaviStreamManager(v2Client, core.naviSources,
                suppressHighwayLeftGuidance = {
                    guidancePrefs.getBoolean(
                        com.jixiexiaoge.drivingassist.navigation.LeftGuidancePolicy.PREFERENCE_KEY, true,
                    )
                })
            core.naviStreamManager = streamManager

            // 监听会话就绪事件 → 启动流管理器 + 数据推送
            v2Client.onSessionReady = { session ->
                Log.i(TAG, "✅ Navi v2 会话就绪: ${session.sessionId}")
                lifecycleScope.launch {
                    if (core.naviV2Client !== v2Client || v2Client.getSession()?.sessionId != session.sessionId ||
                        !v2Client.isReady()) return@launch
                    streamManager.start()
                    streamManager.sendAppStatus(isActivityForeground)
                    updateNaviStreams(streamManager)
                    startNaviV2DataPush() // 启动数据推送协程
                    core.addPipelineEvent("Navi v2 已连接")
                }
            }

            v2Client.onStateChanged = { state ->
                Log.d(TAG, "Navi v2 状态: $state")
            }

            v2Client.onError = { error ->
                Log.e(TAG, "Navi v2 错误: $error")
            }

            core.networkManager.setOnDeviceIPUpdated { deviceIP ->
                if (deviceIP.isNotBlank() && core.naviV2Client === v2Client) {
                    v2Client.setTarget(deviceIP)
                    v2Client.start()
                }
            }

            Log.i(TAG, "✅ Navi v2 客户端初始化完成")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Navi v2 客户端初始化失败: ${e.message}", e)
        }
    }

    /**
     * 启动 Navi v2 数据推送协程
     * 周期性读取 CarrotManFields，通过 v2 StreamManager 发送到 CarrotPilot
     */
    private fun startNaviV2DataPush() {
        naviV2SendJob?.cancel()
        naviV2SendJob = lifecycleScope.launch {
            Log.i(TAG, "🚀 Navi v2 数据推送已启动")
            while (isActive) {
                delay(NaviStreamManager.Companion.CHECK_INTERVAL_MS)
                val msgr = core.naviStreamManager ?: continue
                if (core.naviV2Client?.isReady() != true) continue
                updateNaviStreams(msgr)
            }
        }
    }

    private fun updateNaviStreams(manager: NaviStreamManager) {
        android.os.Trace.beginSection("Navipilot.7714.update")
        try {
            manager.update(core.carrotManFields.value, core.activeNavMode.value == "AMAP")
        } finally {
            android.os.Trace.endSection()
        }
    }

    /**
     * 获取手机IP地址
     */
    private fun getPhoneIPAddress(): String {
        return try {
            // 直接尝试访问networkManager，如果未初始化会抛出异常
            val phoneIP = core.networkManager.getPhoneIP()
            Log.i(TAG, "📱 获取到手机IP: $phoneIP")
            phoneIP
        } catch (e: UninitializedPropertyAccessException) {
            Log.w(TAG, "⚠️ 网络管理器未初始化，无法获取手机IP")
            "网络管理器未初始化"
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ 获取手机IP地址失败: ${e.message}")
            "获取失败"
        }
    }

    /**
     * 获取comma3设备IP地址
     */
    private fun getDeviceIPAddress(): String? {
        return try {
            // 直接尝试访问networkManager，如果未初始化会抛出异常
            val deviceIP = core.networkManager.getCurrentDeviceIP()
            Log.i(TAG, "🔗 获取到设备IP: $deviceIP")
            deviceIP
        } catch (e: UninitializedPropertyAccessException) {
            Log.w(TAG, "⚠️ 网络管理器未初始化，无法获取设备IP")
            null
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ 获取设备IP地址失败: ${e.message}")
            null
        }
    }

    /**
     * 更新自检查状态
     */
    private fun updateSelfCheckStatus(component: String, message: String, isCompleted: Boolean) {
        val currentStatus = core.selfCheckStatus.value
        val newStatus = currentStatus.copy(
            currentComponent = component,
            currentMessage = message,
            isCompleted = isCompleted,
            completedComponents = if (isCompleted) {
                currentStatus.completedComponents + component
            } else {
                currentStatus.completedComponents
            },
            completedMessages = if (isCompleted) {
                currentStatus.completedMessages + (component to message)
            } else {
                currentStatus.completedMessages
            }
        )
        core.selfCheckStatus.value = newStatus
        Log.i(TAG, "🔍 自检查: $component - $message")
    }

    /**
     * 异步更新自检查状态（从后台线程调用）
     */
    private suspend fun updateSelfCheckStatusAsync(component: String, message: String, isCompleted: Boolean) {
        withContext(Dispatchers.Main) {
            updateSelfCheckStatus(component, message, isCompleted)
        }
    }

    // ===============================
    // UI设置
    // ===============================
    
    /**
     * 设置用户界面
     */
    private fun setupUserInterface() {
        // UI设置逻辑已移至MainActivityUI类
        // 这里只是占位，实际UI设置在MainActivity中调用
        Log.i(TAG, "🎨 用户界面设置已委托给MainActivityUI")
    }
    
    // ===============================
    // 驾驶评分系统监听
}
