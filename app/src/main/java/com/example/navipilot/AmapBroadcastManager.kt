package com.example.navipilot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import org.json.JSONException
import org.json.JSONObject

/**
 * 高德地图广播管理器
 * 负责处理高德地图广播接收、数据解析和CarrotMan字段映射
 */
class AmapBroadcastManager(
    private val context: Context,
    private val carrotManFields: MutableState<CarrotManFields>,
    private val networkManager: NetworkManager? = null,
    private val onBroadcastReceived: (() -> Unit)? = null, // 收到广播时的回调
    private val activeNavMode: MutableState<String>? = null // 🆕 三模互斥：当前活跃导航模式
) {
    companion object {
        private const val TAG = "AmapBroadcastManager"
    }

    // 广播数据存储 - 优化版：减少内存占用
    private val broadcastBuffer = mutableListOf<BroadcastData>() // 使用简单的MutableList，最多保留20条
    private val maxBufferSize = 20
    val broadcastDataList = mutableStateListOf<BroadcastData>()
    val receiverStatus = mutableStateOf("等待广播数据...")
    val totalBroadcastCount = mutableIntStateOf(0)
    val lastUpdateTime = mutableLongStateOf(0L)
    
    // 🚀 性能优化：减少UI同步延迟，提升实时性
    private var lastSyncTime = 0L
    private val syncInterval = 1000L // 1秒同步一次，提升实时性

    // 协程作用域
    private val receiverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 广播处理Channel - 使用有限容量避免内存溢出
    // 使用BUFFERED(容量64)替代UNLIMITED，防止内存无限增长导致闪退
    private val broadcastChannel = Channel<Pair<Intent, Int>>(Channel.BUFFERED)
    
    // 🚀 性能优化：移除数据限流器，确保实时处理所有广播
    // private val throttler = DataThrottler(50L) // 已移除，改为实时处理

    // 广播处理器（整合了所有功能）
    private val broadcastHandlers = AmapBroadcastHandlers(
        carrotManFields, 
        networkManager, 
        context,
        null // updateUI回调，如果需要可以传入
    )

    // 智能数据变化检测
    private var lastSpeedLimit: Int? = null
    private var lastRoadName: String? = null
    private var lastSpeedLimitSendTime: Long = 0L
    private val speedLimitSendInterval = 2000L

    // 限速信息数据类
    private data class SpeedLimitInfo(
        val speedLimit: Int,
        val roadName: String,
        val sendTime: Long
    )

    // 增强版高德地图广播接收器
    private val enhancedAmapReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (context == null || intent == null) return
            
            try {
                val action = intent.action
                //Log.i(TAG, "📡 收到广播: $action")

                // 记录广播的基本信息（对简要类型抑制详细行）
                val keyType = intent.getIntExtra("KEY_TYPE", -1)
                val extraState = intent.getIntExtra("EXTRA_STATE", -1)
                val isBriefType = false
                if (!isBriefType) {
                    // Log.d(TAG, "📦 广播详情: action=$action, KEY_TYPE=$keyType, EXTRA_STATE=$extraState")
                }

                when (action) {
                    AppConstants.AmapBroadcast.ACTION_AMAP_SEND,
                    AppConstants.AmapBroadcast.ACTION_AMAP_LEGACY,
                    AppConstants.AmapBroadcast.ACTION_AUTONAVI -> {
                        //Log.i(TAG, "🎯 处理高德地图标准广播") //手动注释
                        onBroadcastReceived?.invoke() // 通知收到广播
                        handleAmapSendBroadcast(intent)
                    }
                    AppConstants.AmapBroadcast.ACTION_AMAP_RECV -> {
                        // Log.v(TAG, "收到发送给高德的广播数据")
                        logAllExtras(intent)
                    }
                    "AMAP_NAVI_ACTION_UPDATE", "AMAP_NAVI_ACTION_TURN",
                    "AMAP_NAVI_ACTION_ROUTE", "AMAP_NAVI_ACTION_LOCATION" -> {
                        Log.i(TAG, "🎯 处理高德地图导航广播")
                        onBroadcastReceived?.invoke() // 通知收到广播
                        handleAlternativeAmapBroadcast(intent)
                    }
                    else -> {
                        Log.w(TAG, "❓ 未知广播action: $action")
                        logAllExtras(intent)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "处理广播数据失败: ${e.message}", e)
            }
        }
    }

    /**
     * 创建Intent过滤器
     */
    private fun createIntentFilter(): IntentFilter {
        return IntentFilter().apply {
            // 高德地图标准广播
            addAction(AppConstants.AmapBroadcast.ACTION_AMAP_SEND)
            addAction(AppConstants.AmapBroadcast.ACTION_AMAP_RECV)
            addAction(AppConstants.AmapBroadcast.ACTION_AMAP_LEGACY)
            addAction(AppConstants.AmapBroadcast.ACTION_AUTONAVI)
            
            // 高德地图导航广播
            addAction("AMAP_NAVI_ACTION_UPDATE")
            addAction("AMAP_NAVI_ACTION_TURN")
            addAction("AMAP_NAVI_ACTION_ROUTE")
            addAction("AMAP_NAVI_ACTION_LOCATION")
            
            // 其他可能的广播
            addAction("com.autonavi.minimap.broadcast")
            addAction("com.autonavi.minimap.navigation.broadcast")
        }
    }

    /**
     * 注册广播接收器
     */
    fun registerReceiver(): Boolean {
        val intentFilter = createIntentFilter()
        return try {
            ContextCompat.registerReceiver(
                context,
                enhancedAmapReceiver,
                intentFilter,
                ContextCompat.RECEIVER_EXPORTED
            )
            
            // 启动广播处理协程
            startBroadcastProcessor()
            
            Log.i(TAG, "✅ 增强版广播接收器注册成功")
            /*
            Log.d(TAG, "📡 注册的广播Action列表:")
            intentFilter.actionsIterator().forEach { action ->
                Log.d(TAG, "  - $action")
            }
            */
            receiverStatus.value = "增强版接收器已启动，等待广播数据..."
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ 广播接收器注册失败: ${e.message}", e)
            receiverStatus.value = "接收器注册失败: ${e.message}"
            false
        }
    }
    
    /**
     * 启动广播处理协程 - 单个协程处理所有广播
     */
    private fun startBroadcastProcessor() {
        receiverScope.launch {
            Log.i(TAG, "🚀 启动广播处理协程")
            for ((intent, keyType) in broadcastChannel) {
                try {
                    processBroadcastData(intent, keyType)
                } catch (e: Exception) {
                    Log.e(TAG, "处理广播数据失败: ${e.message}", e)
                }
            }
        }
    }

    /**
     * 取消注册广播接收器
     */
    fun unregisterReceiver() {
        try {
            context.unregisterReceiver(enhancedAmapReceiver)
            broadcastChannel.close()
            receiverScope.cancel()
            Log.i(TAG, "✅ 广播接收器已注销")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 广播接收器注销失败: ${e.message}", e)
        }
    }

    /**
     * 清空广播数据
     */
    fun clearBroadcastData() {
        broadcastBuffer.clear()
        broadcastDataList.clear()
        totalBroadcastCount.intValue = 0
        receiverStatus.value = "数据已清空，等待新的广播..."
        Log.i(TAG, "🗑️ 广播数据已清空")
    }


    /**
     * 处理来自静态接收器的Intent
     */
    fun handleIntentFromStaticReceiver(intent: Intent?) {
        intent?.let {
            if (it.action == AppConstants.AmapBroadcast.ACTION_AMAP_SEND) {
                Log.i(TAG, "📨 从静态接收器启动，处理Intent数据")
                handleAmapSendBroadcast(it)
            }
        }
    }

    /**
     * 🎯 处理高德地图发送的广播数据 - 核心方法（优化版）
     */
    private fun handleAmapSendBroadcast(intent: Intent) {
        val keyType = intent.getIntExtra("KEY_TYPE", -1)
        
        // 🚀 性能优化：移除限流机制，确保所有广播都被实时处理
        // 注释掉原来的限流检查，改为实时处理所有数据
        // if (!throttler.shouldProcess()) {
        //     return
        // }
        
        // 🎯 根据KEY_TYPE决定日志输出级别
        val isBriefLog = false
        if (isBriefLog) {
            //Log.d(TAG, "📝 处理广播 (简要) KEY_TYPE=$keyType") //零时注释
        } else {
            // 其他KEY_TYPE - 输出详细广播数据
            // 🚀 显示所有类型的原始数据（包括 KEY_TYPE: 10001）
            Log.d(TAG, "🔍 开始处理高德地图广播数据 (KEY_TYPE: $keyType):")
            logAllExtras(intent, keyType)
        }

        // 对于频繁的广播类型，抑制详细日志输出（用于背压日志）
        val shouldSuppressLogs = false

        try {
            // 发送到Channel处理，避免创建新协程
            // 使用trySend避免阻塞，如果Channel满了就丢弃（防止内存堆积）
            val result = broadcastChannel.trySend(Pair(intent, keyType))
            if (result.isFailure) {
                // Channel满了，丢弃旧数据，这是正常的背压处理
                if (!shouldSuppressLogs) {
                    Log.v(TAG, "⚠️ 广播Channel已满，丢弃数据 (KEY_TYPE: $keyType) - 这是正常的背压控制")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "发送广播到Channel失败: ${e.message}", e)
        }
    }
    
    /**
     * 处理广播数据 - 由单个协程调用
     */
    private fun processBroadcastData(intent: Intent, keyType: Int) {
        try {
            // 🆕 三模互斥：腾讯导航模式下，跳过 AMAP 数据写入 CarrotManFields
            // 广播接收器仍然运行（用于检测 AMAP 可用性），但不覆盖导航数据
            if (activeNavMode?.value == "TENCENT" ||
                activeNavMode?.value == "AMAP_MOBILE" ||
                activeNavMode?.value == "GOOGLE"
            ) {
                Log.v(TAG, "🚫 ${activeNavMode?.value} 模式下跳过车机高德广播写入 (KEY_TYPE: $keyType)")
                return
            }
            
            // 🔧 解析基础广播数据
            val broadcastData = parseBroadcastData(intent)
            
            // 通知UI更新
            updateBroadcastData(broadcastData)

            // 根据具体类型处理数据
            when (keyType) {
                AppConstants.AmapBroadcast.Navigation.MAP_STATE -> handleMapState(intent)
                AppConstants.AmapBroadcast.Navigation.GUIDE_INFO -> handleGuideInfo(intent)
                AppConstants.AmapBroadcast.Navigation.LOCATION_INFO -> handleLocationInfo(intent)
                AppConstants.AmapBroadcast.Navigation.TURN_INFO -> handleTurnInfo(intent)
                AppConstants.AmapBroadcast.Navigation.NAVIGATION_STATUS -> handleNavigationStatus(intent)
                AppConstants.AmapBroadcast.Navigation.ROUTE_INFO -> handleRouteInfo(intent)
                
                // 🚀 修复：移除立即发送，由NetworkManager统一200ms间隔发送避免闪烁
                10056 -> {
                    // Log.d(TAG, "🛣️ 处理路线信息广播 (KEY_TYPE: 10056)")
                    // 数据已更新到CarrotMan字段，由自动发送任务统一发送
                }
                13022 -> {
                    // Log.d(TAG, "🧭 处理导航状态广播 (KEY_TYPE: 13022)")
                    // 数据已更新到CarrotMan字段，由自动发送任务统一发送
                }
                // 🎯 临时注释：只使用引导信息广播(KEY_TYPE: 10001)的限速数据
                // AppConstants.AmapBroadcast.SpeedCamera.SPEED_LIMIT -> handleSpeedLimit(intent)
                // 新增：区间测速(12110) 专用处理
                AppConstants.AmapBroadcast.SpeedCamera.SPEED_LIMIT -> handleSpeedLimit(intent)
                // 13005 与 10007 解析与映射已移除：仅跳过
                AppConstants.AmapBroadcast.SpeedCamera.CAMERA_INFO -> {
                    // Log.d(TAG, "🧹 忽略电子眼(13005)映射：已按要求移除")
                }
                AppConstants.AmapBroadcast.SpeedCamera.SDI_PLUS_INFO -> {
                    // Log.d(TAG, "🧹 忽略SDI Plus(10007)映射：已按要求移除")
                }
                AppConstants.AmapBroadcast.MapLocation.TRAFFIC_INFO -> broadcastHandlers.handleTrafficInfo(intent)
                AppConstants.AmapBroadcast.MapLocation.NAVI_SITUATION -> broadcastHandlers.handleNaviSituation(intent)
                AppConstants.AmapBroadcast.MapLocation.TRAFFIC_LIGHT -> broadcastHandlers.handleTrafficLightInfo(intent)
                AppConstants.AmapBroadcast.MapLocation.GEOLOCATION_INFO -> handleGeolocationInfo(intent)
                AppConstants.AmapBroadcast.LaneInfo.DRIVE_WAY_INFO -> handleDriveWayInfo(intent)
                else -> {
                    // 🚀 修复：移除立即发送，由NetworkManager统一200ms间隔发送避免闪烁
                    // Log.d(TAG, "📡 处理通用广播: KEY_TYPE=$keyType")
                    // 数据已更新到CarrotMan字段，由自动发送任务统一发送
                }
            }
            
            // 🚀 修复闪烁：移除通用发送调用，避免重复发送
            // 各个handler（如handleGuideInfo）内部已经有立即发送的逻辑
            // 在这里再次发送会导致每个广播发送2次，造成UI闪烁
            // networkManager?.sendCarrotManDataToComma3()  // 已移除
        } catch (e: Exception) {
            Log.e(TAG, "处理KEY_TYPE $keyType 失败: ${e.message}", e)
        }
    }

    /**
     * 🔧 记录所有Intent额外数据（调试用）
     * 🔑 优化：增强字段解析，确保所有类型都能正确输出
     */
    private fun logAllExtras(intent: Intent, keyType: Int = -1) {
        // 🚀 显示所有类型的原始数据（包括 KEY_TYPE: 10001）
        // 已移除日志抑制逻辑，确保所有广播数据都能在 logcat 中看到
        
        // 🔇 注释掉详细日志，减少日志输出
        /*
        val extras = intent.extras
        if (extras != null) {
            Log.d(TAG, "📋 Intent包含的所有数据 (KEY_TYPE: $keyType):")
            // 🔑 优化：按字母顺序排序，便于对比和查找
            val sortedKeys = extras.keySet().sorted()
            for (key in sortedKeys) {
                val value: String = try {
                    // 🔑 优化：使用更全面的类型检测和转换
                    @Suppress("DEPRECATION")
                    val obj = extras.get(key)
                    when (obj) {
                        is String -> {
                            // 空字符串也显示，用引号区分
                            if (obj.isEmpty()) "\"\""
                            else obj
                        }
                        is Int -> obj.toString()
                        is Long -> obj.toString()
                        is Double -> {
                            // 保留小数点，避免科学计数法
                            if (obj == obj.toLong().toDouble()) obj.toLong().toString()
                            else obj.toString()
                        }
                        is Float -> {
                            // Float类型：直接显示，保留小数点（如 14035.0 显示为 14035.0）
                            obj.toString()
                        }
                        is Boolean -> obj.toString()
                        is Byte -> obj.toString()
                        is Short -> obj.toString()
                        is Char -> obj.toString()
                        null -> "null"
                        is Array<*> -> {
                            // 数组类型：显示数组长度和类型
                            "${obj.javaClass.simpleName}[${obj.size}]"
                        }
                        is android.os.Bundle -> {
                            // Bundle类型：显示包含的键数量
                            "Bundle(${obj.keySet().size} keys)"
                        }
                        else -> {
                            // 其他类型：显示类型 and 值
                            val className = obj.javaClass.simpleName
                            val objString = obj.toString()
                            // 如果字符串太长，截断
                            if (objString.length > 200) {
                                "${className} = ${objString.take(200)}..."
                            } else {
                                "${className} = $objString"
                            }
                        }
                    }
                } catch (e: Exception) {
                    "获取失败: ${e.message}"
                }
                Log.d(TAG, "   📌 $key = $value")
            }
        } else {
            Log.d(TAG, "📋 Intent中没有额外数据")
        }
        */
    }

    private fun isVerboseAmapLogsEnabled(): Boolean {
        return try {
            context.getSharedPreferences("CarrotAmap", Context.MODE_PRIVATE)
                .getBoolean("amap_verbose_logs", false)
        } catch (_: Exception) {
            false
        }
    }

    // 处理其他格式的高德地图广播
    private fun handleAlternativeAmapBroadcast(intent: Intent) {
        // Log.i(TAG, "🔄 处理其他格式高德广播: ${intent.action}")
        logAllExtras(intent)
        extractBasicNavigationInfo(intent)
    }

    // 从未识别的广播中提取基础导航信息
    private fun extractBasicNavigationInfo(intent: Intent) {
        // Log.d(TAG, "🔍 尝试从未识别广播中提取基础导航信息...")
        // 提取常见的导航相关字段
        intent.extras?.let { bundle ->
            var hasUpdate = false

            // 提取位置信息
            val lat = bundle.getDouble("latitude", 0.0).takeIf { it != 0.0 }
                ?: bundle.getDouble("lat", 0.0)
            val lon = bundle.getDouble("longitude", 0.0).takeIf { it != 0.0 }
                ?: bundle.getDouble("lon", 0.0)

            if (lat != 0.0 && lon != 0.0) {
                carrotManFields.value = carrotManFields.value.copy(
                    vpPosPointLat = lat,
                    vpPosPointLon = lon,
                    lastUpdateTime = System.currentTimeMillis()
                )
                hasUpdate = true
                Log.i(TAG, "✅ 提取到位置信息: lat=$lat, lon=$lon")
            }

            // 提取速度信息
            val speed = bundle.getDouble("speed", 0.0).takeIf { it > 0.0 }
                ?: bundle.getFloat("speed", 0.0f).toDouble().takeIf { it > 0.0 }

            if (speed != null && speed > 0.0) {
                carrotManFields.value = carrotManFields.value.copy(
                    nPosSpeed = speed,
                    lastUpdateTime = System.currentTimeMillis()
                )
                hasUpdate = true
                Log.i(TAG, "✅ 提取到速度信息: speed=${speed}km/h")
            }

            if (hasUpdate) {
                // Log.i(TAG, "🔄 从未识别广播中成功提取并更新了导航信息")
            } else {
                // Log.d(TAG, "ℹ️ 未从广播中找到可用的导航信息")
            }
        }
    }

    // 解析广播数据的基础方法
    private fun parseBroadcastData(intent: Intent): BroadcastData {
        val keyType = intent.getIntExtra("KEY_TYPE", -1)
        val timestamp = System.currentTimeMillis()

        // 🔧 内存优化：只提取关键字段，避免存储所有额外数据导致内存膨胀
        // 这些关键字段足以用于UI显示和调试，同时大幅减少内存占用
        val rawExtras = mutableMapOf<String, String>()
        val keyFieldsToExtract = listOf(
            "KEY_TYPE", "EXTRA_STATE", "GUIDE_ICON", "SEG_REMAIN_DIS", 
            "ROAD_NAME", "LIMIT_SPEED", "CUR_SPEED", "EXTRA_VALUE"
        )
        
        intent.extras?.let { bundle ->
            // 只提取关键字段，忽略其他不必要的数据
            for (key in keyFieldsToExtract) {
                if (bundle.containsKey(key)) {
                    val value = try {
                        @Suppress("DEPRECATION")
                        val rawValue = bundle.get(key)
                        when (rawValue) {
                            is String -> rawValue.take(100) // 限制字符串长度
                            is Int -> rawValue.toString()
                            is Long -> rawValue.toString()
                            is Double -> rawValue.toString()
                            is Float -> rawValue.toString()
                            is Boolean -> rawValue.toString()
                            null -> "null"
                            else -> rawValue.toString().take(50) // 限制其他类型长度
                        }
                    } catch (e: Exception) {
                        "解析失败"
                    }
                    rawExtras[key] = value
                }
            }
        }

        return BroadcastData(
            keyType = keyType,
            dataType = getDataTypeDescription(keyType),
            timestamp = timestamp,
            rawExtras = rawExtras,
            parsedContent = "解析中..."
        )
    }

    // 更新广播数据到UI - 优化版：减少同步频率
    @Synchronized
    fun updateBroadcastData(broadcastData: BroadcastData) {
        try {
            // 添加到环形缓冲区 - O(1)操作
            // 添加到缓冲区，保持最多maxBufferSize条记录
            broadcastBuffer.add(broadcastData)
            if (broadcastBuffer.size > maxBufferSize) {
                broadcastBuffer.removeAt(0) // 移除最旧的记录
            }
            totalBroadcastCount.intValue++
            lastUpdateTime.longValue = broadcastData.timestamp

            // 优化：基于时间间隔同步，而不是数据条数
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastSyncTime > syncInterval) {
                syncBufferToList()
                lastSyncTime = currentTime
            }

            // 优化：减少状态更新频率
            if (totalBroadcastCount.intValue % 50 == 0) {
                receiverStatus.value = "已接收 ${totalBroadcastCount.intValue} 条广播数据"
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ 更新广播数据失败: ${e.message}", e)
        }
    }
    
    /**
     * 同步缓冲区数据到UI列表 - 优化版
     */
    private fun syncBufferToList() {
        try {
            // 优化：只在数据真正变化时才更新UI
            if (broadcastBuffer.size != broadcastDataList.size || 
                (broadcastBuffer.isNotEmpty() && broadcastDataList.isNotEmpty() && 
                 broadcastBuffer.last().timestamp != broadcastDataList.last().timestamp)) {
                
                broadcastDataList.clear()
                broadcastDataList.addAll(broadcastBuffer)
                //Log.v(TAG, "🔄 同步缓冲区到UI: ${broadcastBuffer.size} 条数据")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 同步缓冲区失败: ${e.message}", e)
        }
    }

    // 获取数据类型描述
    private fun getDataTypeDescription(keyType: Int): String {
        return when (keyType) {
            AppConstants.AmapBroadcast.Navigation.MAP_STATE -> "地图状态"
            AppConstants.AmapBroadcast.Navigation.GUIDE_INFO -> "引导信息"
            AppConstants.AmapBroadcast.Navigation.LOCATION_INFO -> "定位信息"
            AppConstants.AmapBroadcast.Navigation.TURN_INFO -> "转向信息"
            AppConstants.AmapBroadcast.Navigation.NAVIGATION_STATUS -> "导航状态"
            AppConstants.AmapBroadcast.Navigation.ROUTE_INFO -> "路线信息"
            AppConstants.AmapBroadcast.SpeedCamera.SPEED_LIMIT -> "限速信息"
            AppConstants.AmapBroadcast.SpeedCamera.CAMERA_INFO -> "电子眼信息"
            AppConstants.AmapBroadcast.SpeedCamera.CAMERA_INFO_V2 -> "电子眼信息V2"
            AppConstants.AmapBroadcast.MapLocation.FAVORITE_RESULT -> "收藏点结果"
            AppConstants.AmapBroadcast.NavigationControl.HOME_COMPANY_NAVIGATION -> "家/公司导航"
            AppConstants.AmapBroadcast.MapLocation.ADMIN_AREA -> "行政区域"
            AppConstants.AmapBroadcast.MapLocation.NAVI_STATUS -> "导航状态变化"
            AppConstants.AmapBroadcast.MapLocation.TRAFFIC_INFO -> "路况信息"
            AppConstants.AmapBroadcast.MapLocation.NAVI_SITUATION -> "导航态势"
            AppConstants.AmapBroadcast.MapLocation.NEXT_INTERSECTION -> "下一路口"
            AppConstants.AmapBroadcast.SpeedCamera.SPEED_LIMIT_NEW -> "新版限速"
            AppConstants.AmapBroadcast.MapLocation.SAPA_INFO -> "服务区信息"
            AppConstants.AmapBroadcast.MapLocation.TRAFFIC_LIGHT -> "红绿灯信息"
            AppConstants.AmapBroadcast.SpeedCamera.SDI_PLUS_INFO -> "SDI Plus信息"
            AppConstants.AmapBroadcast.MapLocation.ROUTE_INFO_QUERY -> "路线信息查询"
            AppConstants.AmapBroadcast.LaneInfo.DRIVE_WAY_INFO -> "车道线信息"
            AppConstants.AmapBroadcast.NavigationControl.ROUTE_PLANNING -> "路线规划"
            AppConstants.AmapBroadcast.NavigationControl.START_NAVIGATION -> "开始导航"
            AppConstants.AmapBroadcast.NavigationControl.STOP_NAVIGATION -> "停止导航"
            else -> "未知类型($keyType)"
        }
    }

    // ===============================
    // 广播处理方法委托
    // ===============================
    private fun handleMapState(intent: Intent) = broadcastHandlers.handleMapState(intent)
    private fun handleGuideInfo(intent: Intent) = broadcastHandlers.handleGuideInfo(intent)
    private fun handleLocationInfo(intent: Intent) = broadcastHandlers.handleLocationInfo(intent)
    private fun handleTurnInfo(intent: Intent) = broadcastHandlers.handleTurnInfo(intent)
    private fun handleNavigationStatus(intent: Intent) = broadcastHandlers.handleNavigationStatus(intent)
    private fun handleRouteInfo(intent: Intent) = broadcastHandlers.handleRouteInfo(intent)
    // 🎯 临时注释：只使用引导信息广播(KEY_TYPE: 10001)的限速数据
    // private fun handleSpeedLimit(intent: Intent) = broadcastHandlers.handleSpeedLimit(intent)
    private fun handleCameraInfo(intent: Intent) = broadcastHandlers.handleCameraInfo(intent)
    private fun handleSdiPlusInfo(intent: Intent) = broadcastHandlers.handleSdiPlusInfo(intent)
    private fun handleSpeedLimit(intent: Intent) = broadcastHandlers.handleSpeedLimit(intent)
    private fun handleTrafficInfo(intent: Intent) = broadcastHandlers.handleTrafficInfo(intent)
    private fun handleNaviSituation(intent: Intent) = broadcastHandlers.handleNaviSituation(intent)
    private fun handleTrafficLightInfo(intent: Intent) = broadcastHandlers.handleTrafficLightInfo(intent)
    private fun handleGeolocationInfo(intent: Intent) = broadcastHandlers.handleGeolocationInfo(intent)
    private fun handleDriveWayInfo(intent: Intent) = broadcastHandlers.handleDriveWayInfo(intent)
}
