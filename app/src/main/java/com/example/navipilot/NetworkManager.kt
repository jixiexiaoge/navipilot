package com.example.navipilot
import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import com.example.navipilot.utils.NetworkPerformanceUtils
import com.example.navipilot.utils.NetworkStats

/**
 * 网络管理器
 * 负责处理所有网络相关的功能，包括CarrotMan网络客户端管理、设备发现、数据发送等
 */
class NetworkManager(
    private val context: Context,
    private val carrotManFields: MutableState<CarrotManFields>
) {
    companion object {
        private const val TAG = "NetworkManager"
    }

    // 网络客户端
    private lateinit var carrotNetworkClient: CarrotManNetworkClient

    // 网络状态
    private val networkConnectionStatus = mutableStateOf("未连接")
    private val discoveredDevicesList = mutableStateListOf<CarrotManNetworkClient.DeviceInfo>()
    private val networkStatistics = mutableStateOf(mapOf<String, Any>())
    private val autoSendEnabled = mutableStateOf(true)
    private var lastDataSendTime = 0L
    private val dataSendInterval = 200L  // 恢复200ms间隔，Python端能很好处理高频数据

    // OpenpPilot状态数据
    private val openpilotStatusData = mutableStateOf(OpenpilotStatusData())
    
    // 自动发送状态跟踪 - 已弃用（改为手动点击"开地图"按钮）
    // private var lastAutoSendState = false
    
    // 后台状态追踪 - 用于调整网络策略
    private var isInBackground = false
    
    // 网络状态更新定时器
    private var networkStatusUpdateJob: Job? = null
    
    // 🆕 CarrotMan命令索引 - 用于Python端检测命令变化
    private var carrotCmdIndex = 0
    
    // 🆕 XiaogeDataReceiver IP更新回调 - 当从JSON解析到设备IP时通知
    private var onDeviceIPUpdated: ((String) -> Unit)? = null
    
    // 🆕 性能优化 - 网络数据差分发送
    private var lastSentFields: CarrotManFields? = null
    private val networkStats = NetworkStats()

    // 导航确认服务已移除

    /**
     * 设置后台状态
     * @param inBackground 是否在后台运行
     */
    fun setBackgroundState(inBackground: Boolean) {
        isInBackground = inBackground
        Log.d(TAG, "🔄 网络管理器后台状态更新: $inBackground")
        
        // 通知网络客户端后台状态变化
        if (::carrotNetworkClient.isInitialized) {
            carrotNetworkClient.setBackgroundState(inBackground)
        }
    }

    /**
     * 初始化网络客户端
     */
    fun initializeNetworkClient(): Boolean {
        Log.i(TAG, "🌐 初始化CarrotMan网络客户端...")
        
        return try {
            carrotNetworkClient = CarrotManNetworkClient(context)

            carrotNetworkClient.setOnDeviceDiscovered { device ->
                CoroutineScope(Dispatchers.Main).launch {
                    // 避免重复添加设备
                    if (!discoveredDevicesList.any { it.ip == device.ip }) {
                        // 限制设备列表大小，避免内存无限增长
                        if (discoveredDevicesList.size >= 10) {
                            // 移除最旧的设备（FIFO策略）
                            discoveredDevicesList.removeAt(0)
                        }
                        discoveredDevicesList.add(device)
                        Log.i(TAG, "🎯 发现Comma3设备: $device")
                    }
                    
                    // 🆕 设备发现时，立即通知XiaogeDataReceiver连接
                    val deviceIP = device.ip
                    if (deviceIP.isNotEmpty()) {
                        //Log.i(TAG, "📡 设备发现，通知XiaogeDataReceiver连接: $deviceIP")
                        onDeviceIPUpdated?.invoke(deviceIP)
                    }
                }
            }
            
            carrotNetworkClient.setOnConnectionStatusChanged { connected, message ->
                CoroutineScope(Dispatchers.Main).launch {
                    networkConnectionStatus.value = if (connected) "✅ $message" else "❌ $message"
                    
                    // 获取当前连接的设备信息
                    val deviceInfo = if (connected) {
                        // 从网络客户端获取当前设备信息
                        val connectionStatus = carrotNetworkClient.getConnectionStatus()
                        val currentDevice = connectionStatus["currentDevice"] as? String ?: message
                        currentDevice
                    } else {
                        ""
                    }
                    
                    // 保存网络连接状态到SharedPreferences
                    saveNetworkStatusToPrefs(connected, deviceInfo)
                    
                    Log.i(TAG, "🌐 网络状态变化: connected=$connected, device=$deviceInfo")
                }
            }
            
            carrotNetworkClient.setOnDataSent { packetCount ->
                CoroutineScope(Dispatchers.Main).launch {
                    networkStatistics.value = carrotNetworkClient.getConnectionStatus()
                }
            }

            carrotNetworkClient.setOnOpenpilotStatusReceived { jsonData ->
                CoroutineScope(Dispatchers.Main).launch {
                    parseOpenpilotStatusData(jsonData)
                }
            }

            // UDP广播接收状态通过其他回调监控


            
            // 启动网络服务和自动数据发送
            carrotNetworkClient.start()
            // 确保自动发送开启，并以固定间隔推送导航数据，避免只在广播事件时发送导致中断
            autoSendEnabled.value = true
            carrotNetworkClient.startAutoDataSending(autoSendEnabled, carrotManFields, dataSendInterval)
            
            // 启动网络状态定期更新
            startNetworkStatusUpdate()

            // 导航确认服务已移除

            Log.i(TAG, "✅ CarrotMan网络客户端初始化成功")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 网络客户端初始化失败: ${e.message}", e)
            networkConnectionStatus.value = "❌ 初始化失败: ${e.message}"
            false
        }
    }

    /**
     * 解析OpenpPilot状态JSON数据
     */
    private fun parseOpenpilotStatusData(jsonData: String) {
        try {
            //Log.d(TAG, "🔍 开始解析OpenpPilot JSON数据: ${jsonData.take(200)}...")

            val jsonObject = JSONObject(jsonData)

            // 记录接收到的关键字段
            val vEgo = jsonObject.optInt("v_ego_kph", 0)
            val vCruise = jsonObject.optDouble("v_cruise_kph", 0.0).toFloat()
            val isActive = jsonObject.optBoolean("active", false)
            val isOnroad = jsonObject.optBoolean("IsOnroad", false)

            //Log.d(TAG, "🚗 解析关键数据: 车速=${vEgo}km/h, 巡航=${vCruise}km/h, 激活=${isActive}, 在路上=${isOnroad}")

            // 详细记录巡航速度相关字段
            if (jsonObject.has("v_cruise_kph")) {
                //Log.i(TAG, "✅ 发现v_cruise_kph字段: ${jsonObject.optDouble("v_cruise_kph", 0.0)}")
            } else {
                Log.w(TAG, "⚠️ 未发现v_cruise_kph字段，检查可能的替代字段...")
                // 检查可能的其他字段名
                val possibleFields = listOf("vCruiseKph", "cruise_speed", "v_cruise", "cruiseSpeed")
                possibleFields.forEach { field ->
                    if (jsonObject.has(field)) {
                        Log.i(TAG, "🔍 发现替代字段 $field: ${jsonObject.opt(field)}")
                    }
                }
            }

            // 解析新的carcruiseSpeed字段（兼容旧版本）
            val carcruiseSpeed = jsonObject.optDouble("carcruiseSpeed", 0.0).toFloat()
            if (jsonObject.has("carcruiseSpeed")) {
                //Log.i(TAG, "✅ 发现carcruiseSpeed字段: ${carcruiseSpeed}km/h")
            } else {
                //Log.d(TAG, "ℹ️ 未发现carcruiseSpeed字段，使用默认值0.0（兼容旧版本）")
            }

            val deviceIP = jsonObject.optString("ip", "")
            val devicePort = jsonObject.optInt("port", 0)
            
            val statusData = OpenpilotStatusData(
                carrot2 = jsonObject.optString("Carrot2", ""),
                isOnroad = isOnroad,
                carrotRouteActive = jsonObject.optBoolean("CarrotRouteActive", false),
                ip = deviceIP,
                port = devicePort,
                logCarrot = jsonObject.optString("log_carrot", ""),
                vCruiseKph = jsonObject.optDouble("v_cruise_kph", 0.0).toFloat(),
                vEgoKph = vEgo,
                tbtDist = jsonObject.optInt("tbt_dist", 0),
                sdiDist = jsonObject.optInt("sdi_dist", 0),
                active = isActive,
                xState = jsonObject.optInt("xState", 0),
                trafficState = jsonObject.optInt("trafficState", 0),
                carcruiseSpeed = carcruiseSpeed, // 新增字段
                lastUpdateTime = System.currentTimeMillis()
            )
            
            // 🆕 如果JSON中包含设备IP，直接通知XiaogeDataReceiver连接
            if (deviceIP.isNotEmpty() && devicePort > 0) {
                onDeviceIPUpdated?.invoke(deviceIP)
                //Log.d(TAG, "📡 从JSON解析到设备IP: $deviceIP:$devicePort，已通知XiaogeDataReceiver")
            }

            val oldData = openpilotStatusData.value
            openpilotStatusData.value = statusData

            // 更新CarrotManFields中的接收数据字段
            carrotManFields.value = carrotManFields.value.copy(
                active = statusData.active,
                isOnroad = statusData.isOnroad,
                xState = statusData.xState,
                vEgoKph = statusData.vEgoKph,
                trafficState = statusData.trafficState,
                vCruiseKph = statusData.vCruiseKph,
                carcruiseSpeed = statusData.carcruiseSpeed,
                lastUpdateTime = statusData.lastUpdateTime
            )

            // 保存速度数据到SharedPreferences，供FloatingWindowService使用
            saveSpeedDataToPreferences(statusData)

            //Log.i(TAG, "✅ OpenpPilot状态已更新: 车速=${statusData.vEgoKph}km/h, 激活=${statusData.active}, 在路上=${statusData.isOnroad}")

            // 如果是重要状态变化，记录详细日志
            if (oldData.vEgoKph != statusData.vEgoKph || oldData.active != statusData.active) {
                //Log.i(TAG, "🔄 状态变化: 车速 ${oldData.vEgoKph} -> ${statusData.vEgoKph}, 激活 ${oldData.active} -> ${statusData.active}")
            }
            
            // 检测巡航激活（active: false → true），播放 noo.mp3
            if (!oldData.active && statusData.active) {
                playRawSound(R.raw.noo, "巡航激活")
            }
            
            // 自动发送逻辑已移除 - 改为手动点击"开地图"按钮触发

        } catch (e: JSONException) {
            Log.e(TAG, "JSON解析失败: ${e.message}, 原始数据: $jsonData", e)
        } catch (e: Exception) {
            Log.e(TAG, "解析OpenpPilot状态数据失败: ${e.message}, 原始数据: $jsonData", e)
        }
    }



    /**
     * 检查并自动发送导航确认 - 已弃用
     * 改为手动点击"开地图"按钮触发
     */
    /*
    private fun checkAndAutoSendNavigationConfirmation(statusData: OpenpilotStatusData) {
        // 已弃用 - 改为手动触发
    }
    */

    /**
     * 映射xState枚举值到中文描述
     */
    fun mapXStateToDescription(xState: Int): String {
        return when (xState) {
            0 -> "跟车模式"      // lead
            1 -> "巡航模式"      // cruise
            2 -> "端到端巡航"    // e2eCruise
            3 -> "端到端停车"    // e2eStop
            4 -> "端到端准备"    // e2ePrepare
            5 -> "端到端已停"    // e2eStopped
            else -> "未知状态($xState)"
        }
    }

    /**
     * 获取交通状态描述
     */
    fun getTrafficStateDescription(trafficState: Int): String {
        return when (trafficState) {
            0 -> "无信号"
            1 -> "红灯"
            2 -> "绿灯"
            3 -> "左转"
            else -> "未知($trafficState)"
        }
    }

    /**
     * 格式化时间戳
     */
    fun formatTimestamp(timestamp: Long): String {
        if (timestamp == 0L) return "未设置"
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    /**
     * 获取OpenpPilot状态字段数据
     */
    fun getOpenpilotStatusFields(statusData: OpenpilotStatusData): List<Triple<String, String, String>> {
        return listOf(
            // 基础信息
            Triple("Carrot2", "版本信息", statusData.carrot2.ifEmpty { "未知" }),
            Triple("ip", "设备IP", statusData.ip.ifEmpty { "未连接" }),
            Triple("port", "通信端口", statusData.port.toString()),

            // 系统状态
            Triple("IsOnroad", "道路状态", if (statusData.isOnroad) "在路上" else "未上路"),
            Triple("active", "自动驾驶", if (statusData.active) "激活" else "未激活"),
            Triple("CarrotRouteActive", "导航状态", if (statusData.carrotRouteActive) "导航中" else "未导航"),
            Triple("log_carrot", "系统日志", statusData.logCarrot.ifEmpty { "无日志" }),

            // 速度信息
            Triple("v_ego_kph", "当前车速", "${statusData.vEgoKph} km/h"),
            Triple("v_cruise_kph", "巡航速度", "${statusData.vCruiseKph} km/h"),

            // 导航距离
            Triple("tbt_dist", "转弯距离", "${statusData.tbtDist} m"),
            Triple("sdi_dist", "限速距离", "${statusData.sdiDist} m"),

            // 控制状态
            Triple("xState", "纵向状态", mapXStateToDescription(statusData.xState)),
            Triple("trafficState", "交通状态", getTrafficStateDescription(statusData.trafficState)),

            // 时间信息
            Triple("lastUpdateTime", "更新时间", formatTimestamp(statusData.lastUpdateTime))
        )
    }

    /**
     * 获取网络连接状态
     */
    fun getNetworkConnectionStatus(): String = networkConnectionStatus.value

    /**
     * 获取发现的设备列表
     */
    fun getDiscoveredDevices(): List<CarrotManNetworkClient.DeviceInfo> = discoveredDevicesList.toList()

    /**
     * 获取网络统计信息
     */
    fun getNetworkStatistics(): Map<String, Any> = networkStatistics.value

    /**
     * 保存速度数据到SharedPreferences - 实时写入（不使用批量写入）
     * 速度数据需要实时显示，不能延迟
     */
    private fun saveSpeedDataToPreferences(statusData: OpenpilotStatusData) {
        try {
            // 直接写入SharedPreferences，确保实时性
            val prefs = context.getSharedPreferences("openpilot_status", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putFloat("v_cruise_kph", statusData.vCruiseKph)
                putFloat("carcruise_speed", statusData.carcruiseSpeed)
                putInt("v_ego_kph", statusData.vEgoKph)
                putBoolean("active", statusData.active)
                putBoolean("is_onroad", statusData.isOnroad)
                putLong("last_update", statusData.lastUpdateTime)
                apply() // 使用apply()异步写入，不阻塞主线程
            }
            
            //Log.v(TAG, "📊 速度数据已实时保存: 巡航设定=${statusData.vCruiseKph}km/h, 车辆巡航=${statusData.carcruiseSpeed}km/h")
        } catch (e: Exception) {
            Log.e(TAG, "保存速度数据失败: ${e.message}", e)
        }
    }

    /**
     * 获取OpenpPilot状态数据
     */
    fun getOpenpilotStatusData(): OpenpilotStatusData = openpilotStatusData.value

    /**
     * 获取网络客户端实例
     */
    fun getNetworkClient(): CarrotManNetworkClient? {
        return if (::carrotNetworkClient.isInitialized) carrotNetworkClient else null
    }

    /**
     * 获取当前连接设备的IP地址
     */
    fun getCurrentDeviceIP(): String? {
        return if (::carrotNetworkClient.isInitialized) {
            carrotNetworkClient.getDeviceIP()
        } else {
            null
        }
    }
    
    /**
     * 🆕 设置设备IP更新回调
     * 当从JSON解析到设备IP时，直接通知XiaogeDataReceiver连接
     * @param callback IP更新回调函数，参数为设备IP地址
     */
    fun setOnDeviceIPUpdated(callback: ((String) -> Unit)?) {
        onDeviceIPUpdated = callback
        Log.d(TAG, "✅ 已设置设备IP更新回调")
    }

    /**
     * 🎵 播放原始资源音效（一次性）
     * 使用 MediaPlayer，播放完后自动释放
     */
    private fun playRawSound(resourceId: Int, soundName: String) {
        try {
            MediaPlayer.create(context, resourceId)?.apply {
                setOnCompletionListener { release() }
                setOnErrorListener { mp, what, extra ->
                    Log.e(TAG, "❌ 播放音效失败: $soundName (what=$what extra=$extra)")
                    release()
                    true
                }
                start()
                Log.i(TAG, "🎵 播放音效: $soundName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 播放音效异常: ${e.message}")
        }
    }

    /**
     * 获取当前手机IP地址
     */
    fun getPhoneIP(): String {
        return if (::carrotNetworkClient.isInitialized) {
            carrotNetworkClient.getPhoneIP()
        } else {
            "未初始化"
        }
    }

    /**
     * 集成差分优化：只在数据有显著变化时发送，减少网络流量
     */
    fun sendCarrotManDataToComma3() {
        if (::carrotNetworkClient.isInitialized) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val fields = carrotManFields.value
                    
                    // 🚀 性能优化：差分发送检测
                    val hasChange = NetworkPerformanceUtils.hasSignificantChange(
                        old = lastSentFields,
                        new = fields
                    )
                    
                    // 估算数据包大小
                    val packetSize = NetworkPerformanceUtils.estimateDataSize(fields)
                    
                    if (!hasChange) {
                        // 无显著变化，跳过发送
                        networkStats.updateStats(packetSize, wasSkipped = true)
                        // 每 20 次跳过记录一次日志，避免刷屏
                        if (networkStats.packetsSkipped % 20 == 0) {
                            timber.log.Timber.d("⏭️ 数据无变化，已跳过 ${networkStats.packetsSkipped} 次发送 (优化率 ${"%.1f".format(networkStats.getOptimizationRate())}%)")
                        }
                        return@launch
                    }
                    
                    // 有显著变化，发送数据
                    carrotNetworkClient.sendCarrotManData(fields)
                    
                    // 更新统计和缓存
                    networkStats.updateStats(packetSize, wasSkipped = false)
                    lastSentFields = fields.copy()
                    
                    // 每 50 次发送记录一次统计
                    if (networkStats.totalPacketsSent % 50 == 0) {
                        timber.log.Timber.i("📊 网络统计: ${networkStats.formatStats()}")
                    }
                    
                    timber.log.Timber.v("📤 数据已发送: 转弯=${fields.nTBTTurnType}, 距离=${fields.nTBTDist}m")
                    
                } catch (e: Exception) {
                    timber.log.Timber.e(e, "❌ 发送CarrotMan数据失败")
                }
            }
        } else {
            timber.log.Timber.w("⚠️ 网络客户端未初始化，无法发送数据")
        }
    }
    
    /**
     * 获取网络性能统计信息
     */
    fun getNetworkPerformanceStats(): NetworkStats = networkStats

    /**
     * 发送目的地信息到comma3设备
     */
    fun sendDestinationToComma3(longitude: Double, latitude: Double, name: String, address: String = "") {
        if (::carrotNetworkClient.isInitialized) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // 目的地更新功能已移除，只记录日志
                    Log.i(TAG, "🎯 目的地信息: $name ($latitude, $longitude)")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 发送目的地信息到comma3失败: ${e.message}", e)
                }
            }
        } else {
            Log.w(TAG, "⚠️ 网络客户端未初始化，无法发送目的地信息")
        }
    }

    /**
     * 发送交通灯状态更新到comma3设备
     * 基于逆向工程文档的协议规范实现
     */
    fun sendTrafficLightUpdate(trafficState: Int, leftSec: Int) {
        if (::carrotNetworkClient.isInitialized) {
            try {
                // 交通灯更新功能已移除，只记录日志
                Log.i(TAG, "🚦 交通灯状态: 状态=$trafficState, 倒计时=${leftSec}s")
            } catch (e: Exception) {
                Log.e(TAG, "❌ 发送交通灯状态更新失败: ${e.message}", e)
            }
        } else {
            Log.w(TAG, "⚠️ 网络客户端未初始化，无法发送交通灯状态")
        }
    }

    /**
     * 发送DETECT命令到comma3设备（只在前方120m内有红灯时发送）
     * 基于优化后的检测逻辑实现，使用真实GPS坐标
     */
    fun sendDetectCommand(trafficState: Int, leftSec: Int, distance: Int, gpsLat: Double = 0.0, gpsLon: Double = 0.0) {
        if (::carrotNetworkClient.isInitialized) {
            try {
                // DETECT命令功能已移除，只记录日志
                Log.i(TAG, "🔍 DETECT命令: 状态=$trafficState, 倒计时=${leftSec}s, 距离=${distance}m, GPS=($gpsLat,$gpsLon)")
            } catch (e: Exception) {
                Log.e(TAG, "❌ 发送DETECT命令失败: ${e.message}", e)
            }
        } else {
            Log.w(TAG, "⚠️ 网络客户端未初始化，无法发送DETECT命令")
        }
    }

    /**
     * 发送设置配置到comma3设备
     * 通过HTTP POST请求发送到 http://设备IP:8082/store_toggle_values
     */
    suspend fun sendSettingsToComma3(): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val deviceIP = getCurrentDeviceIP()
                if (deviceIP == null) {
                    Log.w(TAG, "⚠️ 无法获取设备IP地址，无法发送设置")
                    return@withContext Result.failure(Exception("设备未连接"))
                }

                val url = "http://$deviceIP:8082/store_toggle_values"
                val settingsData = mapOf(
                    "AutoTurnControl" to "2",
                    "AutoTurnControlSpeedTurn" to "20",
                    "IsMetric" to "1",
                    "LanguageSetting" to "main_zh-CHS",
                    "SpeedFromPCM" to "0",
                    "ShowDebugUI" to "1"  // 齿轮图标点击时添加此字段
                )

                Log.i(TAG, "🔧 发送设置配置到comma3设备: $url")
                Log.d(TAG, "📋 设置数据: $settingsData")

                val result = sendHttpPostRequest(url, settingsData)
                Log.i(TAG, "✅ 设置配置发送成功")
                Result.success("设置配置发送成功")

            } catch (e: Exception) {
                Log.e(TAG, "❌ 发送设置配置失败: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    /**
     * 发送模式切换到comma3设备
     * 通过HTTP POST请求发送SpeedFromPCM参数
     */
    suspend fun sendModeChangeToComma3(speedFromPCM: Int): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val deviceIP = getCurrentDeviceIP()
                if (deviceIP == null) {
                    Log.w(TAG, "⚠️ 无法获取设备IP地址，无法发送模式切换")
                    return@withContext Result.failure(Exception("设备未连接"))
                }

                val url = "http://$deviceIP:8082/store_toggle_values"
                val modeData = mapOf(
                    "SpeedFromPCM" to speedFromPCM.toString(),
                    "ShowDateTime" to speedFromPCM.toString()  // ShowDateTime值跟随SpeedFromPCM变化
                )

                Log.i(TAG, "🔄 发送模式切换到comma3设备: $url")
                Log.d(TAG, "📋 模式数据: SpeedFromPCM=$speedFromPCM, ShowDateTime=$speedFromPCM")

                val result = sendHttpPostRequest(url, modeData)
                Log.i(TAG, "✅ 模式切换发送成功: SpeedFromPCM=$speedFromPCM")
                Result.success("模式切换成功")

            } catch (e: Exception) {
                Log.e(TAG, "❌ 发送模式切换失败: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    /**
     * 发送自动转向控制模式切换到comma3设备
     * 通过HTTP POST请求发送AutoTurnControl和ShowDebugUI参数
     * 
     * @param autoTurnControl 自动转向控制模式 (0=禁用控制, 1=自动变道, 2=控速变道, 3=导航限速)
     * @return Result<String> 发送结果
     */
    suspend fun sendAutoTurnControlChangeToComma3(autoTurnControl: Int): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val deviceIP = getCurrentDeviceIP()
                if (deviceIP == null) {
                    Log.w(TAG, "⚠️ 无法获取设备IP地址，无法发送自动转向控制模式切换")
                    return@withContext Result.failure(Exception("设备未连接"))
                }

                val url = "http://$deviceIP:8082/store_toggle_values"
                
                // ShowDebugUI规则：当autoTurnControl为2或3时，都传递2；否则传递原值
                val showDebugUI = when (autoTurnControl) {
                    2, 3 -> 2
                    else -> autoTurnControl
                }
                
                val modeData = mapOf(
                    "AutoTurnControl" to autoTurnControl.toString(),
                    "ShowDebugUI" to showDebugUI.toString()
                )

                Log.i(TAG, "🔄 发送自动转向控制模式切换到comma3设备: $url")
                Log.d(TAG, "📋 模式数据: AutoTurnControl=$autoTurnControl, ShowDebugUI=$showDebugUI")

                val result = sendHttpPostRequest(url, modeData)
                Log.i(TAG, "✅ 自动转向控制模式切换发送成功: AutoTurnControl=$autoTurnControl, ShowDebugUI=$showDebugUI")
                Result.success("自动转向控制模式切换成功")

            } catch (e: Exception) {
                Log.e(TAG, "❌ 发送自动转向控制模式切换失败: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    /**
     * 切换实验模式 (Experimental Mode)
     * @param enableExperimentalMode true=实验模式(1), false=Chill模式(0)
     * @return Result<String> 成功或失败信息
     */
    suspend fun switchExperimentalMode(enableExperimentalMode: Boolean): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val deviceIP = getCurrentDeviceIP()
                if (deviceIP == null) {
                    Log.w(TAG, "⚠️ 无法获取设备IP地址，无法切换实验模式")
                    return@withContext Result.failure(Exception("设备未连接"))
                }

                val url = "http://$deviceIP:8082/store_toggle_values"
                
                val modeValue = if (enableExperimentalMode) "1" else "0"
                val modeData = mapOf(
                    "ExperimentalMode" to modeValue
                )

                val modeName = if (enableExperimentalMode) "实验模式 (Experimental)" else "Chill模式"
                Log.i(TAG, "🧪 切换到$modeName: $url")
                Log.d(TAG, "📋 模式数据: ExperimentalMode=$modeValue")

                val result = sendHttpPostRequest(url, modeData)
                Log.i(TAG, "✅ 成功切换到$modeName")
                Result.success("切换到${modeName}成功")

            } catch (e: Exception) {
                Log.e(TAG, "❌ 切换实验模式失败: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    /**
     * 统一发送参数配置到 comma3 设备
     * 使用 HTTP API `/store_toggle_values`
     */
    suspend fun sendParameterSettingsToComma3(parameterMap: Map<String, String>): Result<String> {
        if (parameterMap.isEmpty()) {
            return Result.success("无需更新参数")
        }

        return withContext(Dispatchers.IO) {
            try {
                val deviceIP = getCurrentDeviceIP()
                if (deviceIP == null) {
                    Log.w(TAG, "⚠️ 无法获取设备IP地址，无法发送参数配置")
                    return@withContext Result.failure(Exception("设备未连接"))
                }

                val url = "http://$deviceIP:8082/store_toggle_values"
                Log.i(TAG, "🛠 发送参数配置到comma3设备: $url")
                Log.d(TAG, "📋 参数数据: $parameterMap")

                sendHttpPostRequest(url, parameterMap)
                Log.i(TAG, "✅ 参数配置发送成功")
                Result.success("参数配置发送成功")
            } catch (e: Exception) {
                Log.e(TAG, "❌ 发送参数配置失败: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    /**
     * 发送导航确认到comma3设备
     * 通过HTTP POST请求发送导航确认数据到 /nav_confirmation
     * 根据抓包分析，需要同时在URL和Body中发送参数
     */
    suspend fun sendNavigationConfirmationToComma3(
        goalName: String,
        goalLat: Double,
        goalLon: Double
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val deviceIP = getCurrentDeviceIP()
                if (deviceIP == null) {
                    Log.w(TAG, "⚠️ 无法获取设备IP地址，无法发送导航确认")
                    return@withContext Result.failure(Exception("设备未连接"))
                }

                // URL编码目的地名称
                val encodedName = java.net.URLEncoder.encode(goalName, "UTF-8")

                // 构建带参数的URL（根据抓包信息）
                val url = "http://$deviceIP:8082/nav_confirmation?addr=$encodedName&lon=$goalLon&lat=$goalLat"

                // Body参数（根据抓包信息）- name参数也需要URL编码
                val navData = mapOf(
                    "name" to encodedName,  // 使用URL编码后的名称
                    "lat" to goalLat.toString(),
                    "lon" to goalLon.toString(),
                    "save_type" to "recent"
                )

                Log.i(TAG, "🧭 发送导航确认到comma3设备: $url")
                Log.d(TAG, "📍 导航数据: name=$goalName, lat=$goalLat, lon=$goalLon, save_type=recent")

                val result = sendHttpPostFormRequest(url, navData)
                Log.i(TAG, "✅ 导航确认发送成功: $goalName")
                Result.success("导航确认成功")

            } catch (e: Exception) {
                Log.e(TAG, "❌ 发送导航确认失败: ${e.message}", e)
                Result.failure(e)
            }
        }
    }


    /**
     * 发送form-urlencoded格式的HTTP POST请求
     * 专门用于导航确认功能
     */
    private suspend fun sendHttpPostFormRequest(url: String, data: Map<String, String>): String {
        return withContext(Dispatchers.IO) {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1")
                connection.doOutput = true
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                // 构建form-urlencoded数据
                val formData = data.entries.joinToString("&") { (key, value) ->
                    "$key=$value"  // value已经是URL编码的
                }

                Log.d(TAG, "📤 发送Form数据: $formData")

                // 发送数据
                connection.outputStream.use { outputStream ->
                    outputStream.write(formData.toByteArray())
                    outputStream.flush()
                }

                // 读取响应
                val responseCode = connection.responseCode
                Log.d(TAG, "📥 HTTP响应码: $responseCode")

                if (responseCode == java.net.HttpURLConnection.HTTP_OK || responseCode == 302) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    Log.d(TAG, "📥 HTTP响应内容: $response")
                    response
                } else {
                    val errorResponse = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "未知错误"
                    Log.w(TAG, "⚠️ HTTP请求失败: $responseCode - $errorResponse")
                    throw Exception("HTTP请求失败: $responseCode")
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * 发送JSON格式的HTTP POST请求
     */
    private suspend fun sendHttpPostRequestJson(url: String, data: Map<String, String>): String {
        return withContext(Dispatchers.IO) {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                // 构建JSON数据
                val jsonData = buildString {
                    append("{")
                    data.entries.forEachIndexed { index, entry ->
                        append("\"${entry.key}\":\"${entry.value}\"")
                        if (index < data.size - 1) append(",")
                    }
                    append("}")
                }

                Log.d(TAG, "📤 发送JSON数据: $jsonData")

                // 发送数据
                connection.outputStream.use { outputStream ->
                    outputStream.write(jsonData.toByteArray())
                    outputStream.flush()
                }

                // 读取响应
                val responseCode = connection.responseCode
                Log.d(TAG, "📥 HTTP响应码: $responseCode")

                if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    Log.d(TAG, "📥 HTTP响应内容: $response")
                    response
                } else {
                    val errorResponse = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "未知错误"
                    Log.w(TAG, "⚠️ HTTP请求失败: $responseCode - $errorResponse")
                    throw Exception("HTTP请求失败: $responseCode")
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * 发送HTTP POST请求的通用方法
     */
    private suspend fun sendHttpPostRequest(url: String, data: Map<String, String>): String {
        return withContext(Dispatchers.IO) {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 5000 // 5秒连接超时
                connection.readTimeout = 10000 // 10秒读取超时

                // 构建JSON数据
                val jsonData = org.json.JSONObject(data).toString()

                // 发送数据
                connection.outputStream.use { outputStream ->
                    outputStream.write(jsonData.toByteArray())
                    outputStream.flush()
                }

                // 读取响应
                val responseCode = connection.responseCode
                if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    connection.inputStream.use { inputStream ->
                        inputStream.bufferedReader().readText()
                    }
                } else {
                    throw Exception("HTTP错误: $responseCode")
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * 发送HTTP GET请求的通用方法
     */
    private suspend fun sendHttpGetRequest(url: String): String {
        return withContext(Dispatchers.IO) {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/json")
                connection.connectTimeout = 5000 // 5秒连接超时
                connection.readTimeout = 10000 // 10秒读取超时

                // 读取响应
                val responseCode = connection.responseCode
                if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    connection.inputStream.use { inputStream ->
                        inputStream.bufferedReader().readText()
                    }
                } else {
                    throw Exception("HTTP错误: $responseCode")
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * 从comma3设备获取所有toggle参数值
     * 使用HTTP API: GET http://设备IP:8082/get_toggle_values
     * 返回Result<Map<String, String>>，键为参数名，值为字符串格式的参数值
     */
    suspend fun getToggleValuesFromComma3(): Result<Map<String, String>> {
        return withContext(Dispatchers.IO) {
            try {
                val deviceIP = getCurrentDeviceIP()
                if (deviceIP == null) {
                    Log.w(TAG, "⚠️ 无法获取设备IP地址，无法获取参数值")
                    return@withContext Result.failure(Exception("设备未连接"))
                }

                val url = "http://$deviceIP:8082/get_toggle_values"
                Log.i(TAG, "📥 从comma3设备获取参数值: $url")

                val response = sendHttpGetRequest(url)
                Log.d(TAG, "📥 HTTP响应内容: $response")

                // 解析JSON响应
                val jsonObject = org.json.JSONObject(response)
                val toggleValues = mutableMapOf<String, String>()
                
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = jsonObject.getString(key)
                    toggleValues[key] = value
                }

                Log.i(TAG, "✅ 成功获取 ${toggleValues.size} 个参数值")
                Result.success(toggleValues)
            } catch (e: Exception) {
                Log.e(TAG, "❌ 获取参数值失败: ${e.message}", e)
                Result.failure(e)
            }
        }
    }



    /**
     * 🔍 获取连接状态详情
     */
    fun getConnectionStatus(): Map<String, Any> {
        return if (::carrotNetworkClient.isInitialized) {
            carrotNetworkClient.getConnectionStatus()
        } else {
            mapOf("error" to "网络客户端未初始化")
        }
    }

    /**
     * 发送控制指令到comma3设备 - 支持SPEED和LANECHANGE命令
     * 使用统一的 CarrotManFields 数据源和 JSON 生成机制
     * 
     * 为了适配 desire_helper.py 的 0.2秒窗口限制，采用重复发送策略：
     * - 立即发送第1次
     * - 间隔100ms后再发送5次（共6次，覆盖600ms）
     * - 确保在各种网络延迟下都能被 Python 端捕获
     * 
     * @param command 指令类型 (SPEED, LANECHANGE)
     * @param arg 指令参数 (UP, DOWN, LEFT, RIGHT)
     */
    fun sendControlCommand(command: String, arg: String) {
        Log.d(TAG, "🎮 NetworkManager.sendControlCommand: $command $arg")
        
        if (!::carrotNetworkClient.isInitialized) {
            Log.w(TAG, "⚠️ 网络客户端未初始化，无法发送控制指令")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "📡 准备发送控制指令到设备（重复发送模式）")

                // 🆕 递增命令索引，用于Python端检测命令变化
                carrotCmdIndex++
                
                // 1. 更新 CarrotManFields 中的命令字段（统一数据源）
                carrotManFields.value = carrotManFields.value.copy(
                    carrotCmd = command,
                    carrotArg = arg
                )
                
                Log.d(TAG, "🔄 已更新CarrotManFields: carrotCmd=$command, carrotArg=$arg, carrotCmdIndex=$carrotCmdIndex")

                // 2. 根据命令类型决定发送策略
                // 🆕 变道命令（LANECHANGE）需要重复发送，确保被 desire_helper.py 捕获（0.2秒窗口）
                // 🆕 速度命令（SPEED）只需发送一次，cruise.py 会立即处理（一次性执行）
                if (command == "LANECHANGE") {
                    // 变道命令：重复发送3次，间隔50ms，确保在0.2秒窗口内被捕获
                    repeat(3) { attemptIndex ->
                        carrotNetworkClient.sendCarrotManDataImmediately(carrotManFields.value)
                        Log.v(TAG, "📤 变道指令发送 #${attemptIndex + 1}/3")
                        
                        if (attemptIndex < 2) {
                            delay(50) // 间隔50ms
                        }
                    }
                    Log.i(TAG, "✅ 变道指令已发送完成（3次重复）: $command $arg")
                    
                    // 延迟清理命令字段，确保 desire_helper.py 有时间设置计数器
                    CoroutineScope(Dispatchers.Main).launch {
                        delay(150) // 150ms后清理，确保计数器已设置
                        carrotManFields.value = carrotManFields.value.copy(
                            carrotCmd = "",
                            carrotArg = ""
                        )
                        Log.d(TAG, "🧹 已清理变道命令字段")
                    }
                } else {
                    // 速度命令：只发送一次，cruise.py 会立即处理（一次性执行）
                    carrotNetworkClient.sendCarrotManDataImmediately(carrotManFields.value)
                    Log.i(TAG, "✅ 速度指令已发送（单次）: $command $arg")
                    
                    // 立即清理命令字段，避免重复处理
                    CoroutineScope(Dispatchers.Main).launch {
                        delay(100) // 100ms后清理，确保cruise.py已处理
                        carrotManFields.value = carrotManFields.value.copy(
                            carrotCmd = "",
                            carrotArg = ""
                        )
                        Log.d(TAG, "🧹 已清理速度命令字段")
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ 发送控制指令失败: ${e.message}", e)
            }
        }
    }



    /**
     * 获取导航确认服务状态 (已移除)
     */
    fun getNavigationConfirmationStatus(): Map<String, Any> {
        return mapOf("error" to "导航确认服务已移除")
    }



    /**
     * 启动网络状态定期更新
     * 每3秒更新一次网络状态到SharedPreferences
     */
    private fun startNetworkStatusUpdate() {
        networkStatusUpdateJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    // 获取当前连接状态
                    val connectionStatus = if (::carrotNetworkClient.isInitialized) {
                        carrotNetworkClient.getConnectionStatus()
                    } else {
                        null
                    }
                    
                    if (connectionStatus != null) {
                        val isRunning = connectionStatus["isRunning"] as? Boolean ?: false
                        val currentDevice = connectionStatus["currentDevice"] as? String ?: ""
                        
                        // 保存到SharedPreferences
                        saveNetworkStatusToPrefs(isRunning, currentDevice)
                        
                        //Log.v(TAG, "🔄 定期更新网络状态: running=$isRunning, device='$currentDevice'")
                    }
                    
                    delay(3000) // 每3秒更新一次
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 定期更新网络状态失败: ${e.message}", e)
                    delay(5000) // 出错后等待5秒再重试
                }
            }
        }
        Log.i(TAG, "🔄 网络状态定期更新已启动")
    }
    
    /**
     * 停止网络状态定期更新
     */
    private fun stopNetworkStatusUpdate() {
        networkStatusUpdateJob?.cancel()
        networkStatusUpdateJob = null
        Log.i(TAG, "⏹️ 网络状态定期更新已停止")
    }
    
    /**
     * 保存网络连接状态到SharedPreferences
     */
    private fun saveNetworkStatusToPrefs(isConnected: Boolean, deviceInfo: String) {
        try {
            val prefs = context.getSharedPreferences("network_status", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean("is_running", isConnected)
                putString("current_device", deviceInfo)
                putLong("last_update", System.currentTimeMillis())
                apply()
            }
            //Log.d(TAG, "💾 网络状态已保存: connected=$isConnected, device='$deviceInfo'")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 保存网络状态失败: ${e.message}", e)
        }
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        try {
            // 停止网络状态更新
            stopNetworkStatusUpdate()

            // 清除网络状态
            saveNetworkStatusToPrefs(false, "")
            
            if (::carrotNetworkClient.isInitialized) {
                carrotNetworkClient.cleanup()
            }
            discoveredDevicesList.clear()
            Log.i(TAG, "🧹 网络管理器资源已清理")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 网络管理器清理失败: ${e.message}", e)
        }
    }
}
