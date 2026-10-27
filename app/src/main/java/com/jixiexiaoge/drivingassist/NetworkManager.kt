package com.jixiexiaoge.drivingassist
import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import com.jixiexiaoge.drivingassist.utils.NetworkPerformanceUtils
import com.jixiexiaoge.drivingassist.utils.NetworkStats

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

        /** 车端 `carrot_serv.py` / `cruise.py` 支持的命令名（区分大小写，App 侧统一转大写） */
        private val SUPPORTED_COMMANDS = setOf("SPEED", "LANECHANGE")

        /**
         * 一次操作连发几份**同序号**副本。
         *
         * 车端 `cruise.py::_carrot_command()` 与 `desire_helper::_update_atc_blinker()`
         * 都是「`carrotCmdIndex` 变了才执行」，所以同号副本只会被执行一次；
         * 最新 modeld 直接向 DesireHelper 传递 carrotMan；不能通过伪造导航或车速
         * 绕过车辆侧横向激活、速度、盲区和车道条件。
         */
        private const val LANE_CHANGE_COPIES = 8
        private const val SPEED_COPIES = 5
        private const val SINGLE_COPY = 1

        /** 同序号副本之间的间隔（× 份数 = 总覆盖时间窗） */
        private const val COPIES_GAP_MS = 100L

        /**
         * 副本发完后保持/独占的时长（期间拒绝新命令，避免左右变道交错）。
         * 必须 ≥ 副本总跨度，否则上一轮的尾巴会和下一轮的命令交错。
         */
        private const val LANE_CHANGE_HOLD_MS = 250L
        private const val SPEED_HOLD_MS = 200L
        private const val COMMAND_RETIRE_TIMEOUT_MS = 1_000L

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

    // 生命周期绑定的协程作用域（SupervisorJob 确保单个子协程失败不影响其他）
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // 命令互斥：同一时刻只允许一条用户操作在发送，避免左右变道交错
    private val commandInFlight = AtomicBoolean(false)
    
    // 设备IP更新监听器 - 允许主 WebSocket、Navi v2、AR 同时订阅，避免互相覆盖
    private val onDeviceIPUpdatedCallbacks = mutableListOf<(String) -> Unit>()
    
    /** 上次已连接的设备 IP，防止每条 JSON 都重复触发连接回调 */
    private var lastConnectedIp: String = ""
    
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
                managerScope.launch {
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
                    
                }
            }
            
            carrotNetworkClient.setOnConnectionStatusChanged { connected, message ->
                managerScope.launch {
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
                    notifySelectedDeviceIP()
                    
                    Log.i(TAG, "🌐 网络状态变化: connected=$connected, device=$deviceInfo")
                }
            }
            
            carrotNetworkClient.setOnDataSent { packetCount ->
                managerScope.launch {
                    networkStatistics.value = carrotNetworkClient.getConnectionStatus()
                }
            }

            carrotNetworkClient.setOnOpenpilotStatusReceived { jsonData ->
                val selected = carrotNetworkClient.getDeviceIP()
                managerScope.launch {
                    if (selected != null && selected == getCurrentDeviceIP()) {
                        parseOpenpilotStatusData(jsonData)
                    }
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

            
            val statusData = OpenpilotStatusData(
                carrot2 = jsonObject.optString("Carrot2", ""),
                isOnroad = isOnroad,
                carrotRouteActive = jsonObject.optBoolean("CarrotRouteActive", false),
                ip = getCurrentDeviceIP().orEmpty(),
                port = jsonObject.optInt("port", 0),
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
            
            // 🆕 如果JSON中包含设备IP，通知 WebSocket 客户端连接（仅 IP 变化时触发）
            notifySelectedDeviceIP()

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
     * 保存速度数据到SharedPreferences - 后台写入
     */
    private fun saveSpeedDataToPreferences(statusData: OpenpilotStatusData) {
        managerScope.launch(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences("openpilot_status", Context.MODE_PRIVATE)
                prefs.edit().apply {
                    putFloat("v_cruise_kph", statusData.vCruiseKph)
                    putFloat("carcruise_speed", statusData.carcruiseSpeed)
                    putInt("v_ego_kph", statusData.vEgoKph)
                    putBoolean("active", statusData.active)
                    putBoolean("is_onroad", statusData.isOnroad)
                    putLong("last_update", statusData.lastUpdateTime)
                    apply()
                }
            } catch (e: Exception) {
                Log.e(TAG, "保存速度数据失败: ${e.message}", e)
            }
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
     * 当从JSON解析到设备IP时，直接通知 WebSocket 客户端连接
     * @param callback IP更新回调函数，参数为选中设备IP地址；空字符串表示目标已清除
     */
    fun setOnDeviceIPUpdated(callback: ((String) -> Unit)?) {
        if (callback == null) {
            onDeviceIPUpdatedCallbacks.clear()
            Log.d(TAG, "✅ 已清空设备IP更新回调")
            return
        }
        onDeviceIPUpdatedCallbacks.add(callback)
        Log.d(TAG, "✅ 已添加设备IP更新回调: count=${onDeviceIPUpdatedCallbacks.size}")

        getCurrentDeviceIP()?.takeIf { it.isNotEmpty() }?.let { deviceIP ->
            try {
                callback(deviceIP)
            } catch (e: Exception) {
                Log.e(TAG, "设备IP回调执行失败: ${e.message}", e)
            }
        }
    }

    private fun notifyDeviceIPUpdated(deviceIP: String) {
        onDeviceIPUpdatedCallbacks.toList().forEach { callback ->
            try {
                callback(deviceIP)
            } catch (e: Exception) {
                Log.e(TAG, "设备IP回调执行失败: ${e.message}", e)
            }
        }
    }

    private fun notifySelectedDeviceIP() {
        val selected = getCurrentDeviceIP().orEmpty()
        if (selected == lastConnectedIp) return
        lastConnectedIp = selected
        notifyDeviceIPUpdated(selected)
    }

    /**
     * 重置设备 IP 缓存，允许重新触发连接回调。
     * 仅用于显式重新通知目标；各传输客户端自行处理同IP重连。
     */
    fun resetDeviceIpCache() {
        lastConnectedIp = ""
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
     * 立即补发一包完整导航数据（**不做差分跳过**）。
     *
     * 早先这里用 `NetworkPerformanceUtils.hasSignificantChange` 省流量，但那是个坑：
     * 车端 `carrot_serv` 用 `active_count`（80 帧 ≈ 20Hz × 4 秒）判断导航是否在线，
     * 差分一停，`cruise.py::get_carrot_man()` 就返回 null，控制命令会被整条丢弃。
     * 现在周期发送由 [CarrotManNetworkClient.startAutoDataSending]（200ms 无条件）负责，
     * 这里只留作「手动补一包」的入口。
     */
    fun sendCarrotManDataToComma3() {
        if (::carrotNetworkClient.isInitialized) {
            managerScope.launch(Dispatchers.IO) {
                try {
                    val fields = carrotManFields.value
                    carrotNetworkClient.sendCarrotManData(fields)
                    networkStats.updateStats(NetworkPerformanceUtils.estimateDataSize(fields), wasSkipped = false)
                    lastSentFields = fields.copy()
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
            managerScope.launch(Dispatchers.IO) {
                try {
                    val fields = withContext(Dispatchers.Main) {
                        carrotManFields.value.copy(goalPosX = longitude, goalPosY = latitude, szGoalName = name)
                    }
                    if (!carrotNetworkClient.sendDestination(fields)) {
                        throw java.io.IOException("目的地未发送：设备未连接")
                    }
                    Log.i(TAG, "目的地已通过7706发送（非执行确认）: $name")
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
     * 通过7000标准参数API逐项写入并检查结果。
     */
    suspend fun sendSettingsToComma3(): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val deviceIP = getCurrentDeviceIP()
                if (deviceIP == null) {
                    Log.w(TAG, "⚠️ 无法获取设备IP地址，无法发送设置")
                    return@withContext Result.failure(Exception("设备未连接"))
                }

                val url = "http://$deviceIP:7000/api/param_set"
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

                setDeviceParameters(deviceIP, settingsData)
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

                val url = "http://$deviceIP:7000/api/param_set"
                val modeData = mapOf(
                    "SpeedFromPCM" to speedFromPCM.toString(),
                    "ShowDateTime" to speedFromPCM.toString()  // ShowDateTime值跟随SpeedFromPCM变化
                )

                Log.i(TAG, "🔄 发送模式切换到comma3设备: $url")
                Log.d(TAG, "📋 模式数据: SpeedFromPCM=$speedFromPCM, ShowDateTime=$speedFromPCM")

                setDeviceParameters(deviceIP, modeData)
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

                val url = "http://$deviceIP:7000/api/param_set"
                
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

                setDeviceParameters(deviceIP, modeData)
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

                val url = "http://$deviceIP:7000/api/param_set"
                
                val modeValue = if (enableExperimentalMode) "1" else "0"
                val modeData = mapOf(
                    "ExperimentalMode" to modeValue
                )

                val modeName = if (enableExperimentalMode) "实验模式 (Experimental)" else "Chill模式"
                Log.i(TAG, "🧪 切换到$modeName: $url")
                Log.d(TAG, "📋 模式数据: ExperimentalMode=$modeValue")

                setDeviceParameters(deviceIP, modeData)
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
     * 使用 HTTP API `/api/param_set`。
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

                val url = "http://$deviceIP:7000/api/param_set"
                Log.i(TAG, "🛠 发送参数配置到comma3设备: $url")
                Log.d(TAG, "📋 参数数据: $parameterMap")

                setDeviceParameters(deviceIP, parameterMap)
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
     * 此版 carrot 没有旧 /nav_confirmation 接口；发送目的地，不宣称执行确认。
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

                val fields = withContext(Dispatchers.Main) {
                    carrotManFields.value.copy(goalPosX = goalLon, goalPosY = goalLat, szGoalName = goalName)
                }
                if (!carrotNetworkClient.sendDestination(fields)) {
                    throw java.io.IOException("目的地未发送：设备未连接")
                }
                Result.success("目的地已通过7706发送，尚非导航执行确认")

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
     * 使用7000设置快照的values字段。
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

                val url = "http://$deviceIP:7000/api/settings/snapshot"
                Log.i(TAG, "📥 从comma3设备获取参数值: $url")

                val response = sendHttpGetRequest(url)
                Log.d(TAG, "📥 HTTP响应内容: $response")

                // 解析JSON响应
                val snapshot = org.json.JSONObject(response)
                if (!snapshot.getBoolean("ok")) {
                    throw java.io.IOException(snapshot.optString("error", "读取设置失败"))
                }
                val jsonObject = snapshot.getJSONObject("values")
                val toggleValues = mutableMapOf<String, String>()
                
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = jsonObject.get(key).toString()
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
     * 发送控制指令到 comma3 设备（UDP 7706）。
     *
     * 车端 `carrot_serv.py` 只读**顶层** `carrotIndex`，并把它复制成 `carrotCmdIndex`
     * 用来判断"这条命令是不是新请求"。由此得出三条硬约束：
     * 1. **一次用户操作 = 一个序号**：LANECHANGE 要连发几份副本提高到达率，副本必须复用
     *    同一个序号。原实现每份都递增序号，车端会当成 3 次新请求 → 一次点击变道 3 次。
     * 2. **心跳/周期包不带 `carrotCmd`**：否则旧命令会被反复当成新命令（见 [sendCarrotManData]）。
     * 3. **发完补一个空命令包**（新序号、`carrotCmd=""`）：把车端的 `carrotCmdIndex`
     *    推进到"无命令"，之后的心跳再递增序号也不会触发动作。
     *
     * App 侧只保证"已发出"，不保证车端已执行：7705 状态里没有按序号关联的执行回执。
     *
     * @param command `SPEED`（arg = 目标 km/h）或 `LANECHANGE`（arg = LEFT / RIGHT）
     * @return true 表示已进入发送队列；false 表示被拒（未连接 / 参数不支持 / 上一条仍在发）
     */
    fun sendControlCommand(command: String, arg: String, onFailure: ((String) -> Unit)? = null): Boolean {
        // 车端区分大小写：统一规范成大写，同时用白名单挡掉拼错的值
        val cmd = command.trim().uppercase()
        if (cmd !in SUPPORTED_COMMANDS) {
            Log.w(TAG, "⚠️ 不支持的控制指令: $command")
            return false
        }

        // 参数比命令名更危险，必须自己挡：
        // - 车端 `carrotArg == "LEFT"` 之外**全部当成 RIGHT**，拼错就变成反向变道；
        // - 车端 `int(self.carrot_arg)` 没有 try，非数字且非 UP/DOWN 会抛 ValueError，
        //   直接把 cruise 那条链路打挂。
        val normalizedArg = arg.trim().uppercase()
        val argAccepted = when (cmd) {
            "LANECHANGE" -> normalizedArg == "LEFT" || normalizedArg == "RIGHT"
            // 车端再卡一次 0 < speed < 200，这里提前挡掉，省得白包一发
            "SPEED" -> normalizedArg == "UP" || normalizedArg == "DOWN" ||
                (normalizedArg.toIntOrNull()?.let { it in 1..199 } == true)
            else -> false
        }
        if (!argAccepted) {
            Log.w(TAG, "⚠️ 控制指令参数不合法: $cmd $arg")
            return false
        }

        if (!::carrotNetworkClient.isInitialized) {
            Log.w(TAG, "⚠️ 网络客户端未初始化，无法发送控制指令")
            return false
        }
        // 没连上设备就别假装"已发送"，让调用方拿到明确的失败
        if (!carrotNetworkClient.isConnected()) {
            Log.w(TAG, "⚠️ 设备未连接，指令未发送: $cmd $normalizedArg")
            return false
        }
        val targetIp = carrotNetworkClient.getCurrentDevice()?.ip ?: return false
        if (!commandInFlight.compareAndSet(false, true)) {
            Log.w(TAG, "⚠️ 控制指令仍在发送，已拒绝重叠命令: $cmd $normalizedArg")
            return false
        }

        managerScope.launch { deliverControlCommand(cmd, normalizedArg, targetIp, onFailure) }
        return true
    }

    private suspend fun deliverControlCommand(
        command: String,
        arg: String,
        targetIp: String,
        onFailure: ((String) -> Unit)?,
    ) {
        var sent = false
        var failure: String? = null
        try {
            val index = carrotNetworkClient.allocateCommandIndex()
            val copies = if (command == "LANECHANGE") LANE_CHANGE_COPIES else SPEED_COPIES
            repeat(copies) { attempt ->
                val packet = carrotManFields.value.copy(carrotCmd = command, carrotArg = arg)
                carrotNetworkClient.sendCommandPacket(packet, index, targetIp)
                sent = true
                if (attempt < copies - 1) delay(COPIES_GAP_MS)
            }
            Log.i(TAG, "UDP请求已发送，非车端回执: index=$index $command $arg")
            delay(if (command == "LANECHANGE") LANE_CHANGE_HOLD_MS else SPEED_HOLD_MS)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "发送控制指令失败", e)
            failure = e.message ?: "UDP 发送失败"
        } finally {
            // Retire partial/cancelled requests too, but never on a replacement device.
            if (sent) {
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        kotlinx.coroutines.withTimeout(COMMAND_RETIRE_TIMEOUT_MS) {
                            carrotNetworkClient.sendCommandPacket(
                                CarrotManFields(),
                                carrotNetworkClient.allocateCommandIndex(), targetIp,
                            )
                        }
                    }
                } catch (e: java.io.IOException) {
                    Log.e(TAG, "清除命令邮箱失败，未确认车端状态", e)
                    failure = failure ?: e.message ?: "命令清除失败"
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    Log.e(TAG, "清除命令邮箱超时，未确认车端状态", e)
                    failure = failure ?: "命令清除超时"
                }
            }
            commandInFlight.set(false)
            failure?.let { onFailure?.invoke(it) }
        }
    }

    private suspend fun setDeviceParameters(deviceIP: String, parameters: Map<String, String>) {
        val client = CarrotParamClient(deviceIP)
        var written = 0
        for ((name, value) in parameters) {
            val result = client.setParam(name, value)
            if (result.isFailure) {
                throw java.io.IOException("参数 $name 写入失败；此前 $written 项已更新", result.exceptionOrNull())
            }
            written++
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
        networkStatusUpdateJob = managerScope.launch(Dispatchers.IO) {
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
     * 保存网络连接状态到SharedPreferences（后台IO线程执行，避免主线程磁盘操作）
     */
    private fun saveNetworkStatusToPrefs(isConnected: Boolean, deviceInfo: String) {
        managerScope.launch(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences("network_status", Context.MODE_PRIVATE)
                prefs.edit().apply {
                    putBoolean("is_running", isConnected)
                    putString("current_device", deviceInfo)
                    putLong("last_update", System.currentTimeMillis())
                    apply()
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ 保存网络状态失败: ${e.message}", e)
            }
        }
    }
    
    /**
     * 同步保存网络状态（仅cleanup时使用，不受协程生命周期影响）
     */
    private fun saveNetworkStatusSync(isConnected: Boolean, deviceInfo: String) {
        try {
            val prefs = context.getSharedPreferences("network_status", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean("is_running", isConnected)
                putString("current_device", deviceInfo)
                putLong("last_update", System.currentTimeMillis())
                apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 同步保存网络状态失败: ${e.message}", e)
        }
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        try {
            // 停止网络状态更新
            stopNetworkStatusUpdate()
            managerScope.cancel()

            // 清除网络状态
            saveNetworkStatusSync(false, "")
            
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
