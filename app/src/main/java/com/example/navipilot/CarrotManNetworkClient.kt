package com.example.navipilot

// Android 系统相关导入
import android.content.Context
import android.media.MediaPlayer
import android.net.wifi.WifiManager
import android.util.Log

// 协程相关导入
import kotlinx.coroutines.*

// JSON数据处理导入
import org.json.JSONObject

// Java 网络和IO相关导入
import java.io.DataOutputStream
import java.io.IOException
import java.net.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.HashMap

// Compose相关导入
import androidx.compose.runtime.MutableState

// CarrotMan 网络客户端类 - 负责与 Comma3 OpenPilot 设备进行 UDP 网络通信
class CarrotManNetworkClient(
    private val context: Context
) {
    
    companion object {
        private const val TAG = AppConstants.Logging.NETWORK_CLIENT_TAG
        
        // 网络通信端口配置 - 基于逆向分析的准确配置
        private const val BROADCAST_PORT = 7705  // 固定监听端口（接收设备广播）
        private const val MAIN_DATA_PORT = 7706  // 默认发送端口（动态配置）
        private const val TCP_VERTEX_PORT = 7709 // TCP端口（用于Vertex数据）
        private const val COMMAND_PORT = 7706    // 命令端口
        
        // 通信时间参数配置 - 使用统一的常量管理
        private const val DISCOVER_CHECK_INTERVAL = AppConstants.Network.DISCOVER_CHECK_INTERVAL
        private const val DATA_SEND_INTERVAL = AppConstants.Network.DATA_SEND_INTERVAL
        private const val SOCKET_TIMEOUT = AppConstants.Network.SOCKET_TIMEOUT
        private const val DEVICE_TIMEOUT = AppConstants.Network.DEVICE_TIMEOUT
        
        // 网络数据配置 - 使用统一的常量管理
        private const val MAX_PACKET_SIZE = AppConstants.Network.MAX_PACKET_SIZE

        /**
         * 与 UDP 7706 包体字段一致（不含 socket）。
         * [packetCarrotIndex] 写入 JSON 的 `carrotIndex`（真实发送时为 ++client.carrotIndex）。
         */
        fun build7706Payload(
            fields: CarrotManFields,
            packetCarrotIndex: Long,
            currentTimeMs: Long = System.currentTimeMillis(),
        ): JSONObject = JSONObject().apply {
            // ===== 组1: 基础通信 (3字段) =====
            put("carrotIndex", packetCarrotIndex)
            put("epochTime", currentTimeMs / 1000)
            put("timezone", fields.timezone.ifEmpty { "Asia/Shanghai" })

            // ===== 组2: 目的地 (3字段) =====
            put("goalPosX", fields.goalPosX)
            put("goalPosY", fields.goalPosY)
            put("szGoalName", fields.szGoalName)

            // ===== 组3: 道路限速 (1字段，触发整个导航数据块) =====
            put("nRoadLimitSpeed", fields.nRoadLimitSpeed)

            // ===== 组4: SDI 电子眼 (11字段) =====
            put("nSdiType", fields.nSdiType)
            put("nSdiSpeedLimit", fields.nSdiSpeedLimit)
            put("nSdiSection", fields.nSdiSection)
            put("nSdiDist", fields.nSdiDist)
            put("nSdiBlockType", fields.nSdiBlockType)
            put("nSdiBlockSpeed", fields.nSdiBlockSpeed)
            put("nSdiBlockDist", fields.nSdiBlockDist)
            put("nSdiPlusType", fields.nSdiPlusType)
            put("nSdiPlusSpeedLimit", fields.nSdiPlusSpeedLimit)
            put("nSdiPlusDist", fields.nSdiPlusDist)
            put("nSdiPlusBlockType", fields.nSdiPlusBlockType)
            put("nSdiPlusBlockSpeed", fields.nSdiPlusBlockSpeed)
            put("nSdiPlusBlockDist", fields.nSdiPlusBlockDist)
            put("roadcate", fields.roadcate)

            // ===== 组5: TBT 转弯导航 (9字段) =====
            put("nTBTDist", fields.nTBTDist)
            put("nTBTTurnType", fields.nTBTTurnType)
            put("szTBTMainText", fields.szTBTMainText)
            put("szNearDirName", fields.szNearDirName)
            put("szFarDirName", fields.szFarDirName)
            put("nTBTNextRoadWidth", fields.nTBTNextRoadWidth)
            put("nTBTDistNext", fields.nTBTDistNext)
            put("nTBTTurnTypeNext", fields.nTBTTurnTypeNext)
            put("szTBTMainTextNext", fields.szTBTMainTextNext)

            // ===== 组6: 目的地剩余 (3字段) =====
            put("nGoPosDist", fields.nGoPosDist)
            put("nGoPosTime", fields.nGoPosTime)
            put("szPosRoadName", fields.szPosRoadName)

            // ===== 组7: 导航 GPS 位置 (4字段) =====
            put("vpPosPointLat", fields.vpPosPointLat)
            put("vpPosPointLon", fields.vpPosPointLon)
            put("nPosAngle", fields.nPosAngle)
            put("nPosSpeed", fields.nPosSpeed)

            // ===== 组8: 手机 GPS 回退 (4字段) =====
            put("latitude", fields.latitude)
            put("longitude", fields.longitude)
            put("heading", fields.heading)
            put("accuracy", fields.accuracy)
            put("gps_speed", fields.gps_speed)

            // ===== 组9: 命令通道 (2字段) =====
            put("carrotCmd", fields.carrotCmd)
            put("carrotArg", fields.carrotArg)
        }
    }

    // 网络状态管理
    private var isRunning = false
    private val discoveredDevices = ConcurrentHashMap<String, DeviceInfo>()
    private var currentTargetDevice: DeviceInfo? = null

    // 动态端口配置（基于逆向分析）
    private var dynamicSendPort: Int = MAIN_DATA_PORT  // 从广播数据动态获取
    private var deviceIP: String? = null               // 从广播数据动态获取
    private var phoneIP: String = ""                   // 手机IP地址

    // Socket连接管理
    private var listenSocket: DatagramSocket? = null
    private var dataSocket: DatagramSocket? = null
    private var tcpSocket: Socket? = null  // TCP连接（用于Vertex数据）

    // TCP 7712 连接管理（rgdata/vrtx）
    private var tcp7712Socket: Socket? = null
    private var tcp7712Writer: java.io.PrintWriter? = null
    
    // 协程任务管理
    private val networkScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listenJob: Job? = null
    private var dataSendJob: Job? = null
    private var autoSendJob: Job? = null
    private var deviceCheckJob: Job? = null
    private var heartbeatJob: Job? = null
    
    // 数据统计管理
    private var carrotIndex = 0L
    private var totalPacketsSent = 0
    private var lastSendTime = 0L
    private var lastDataReceived = 0L
    private var lastNoConnectionLogTime = 0L // 添加无连接日志时间控制
    private var lastNetworkErrorLogTime = 0L // 添加网络错误日志时间控制

    // 公开的发送统计（供UI显示）
    val sendStats: Triple<Int, Long, Long>  // (totalSent, lastSendMs, lastRecvMs)
        get() = Triple(totalPacketsSent, lastSendTime, lastDataReceived)

    // 心跳管理 - 改为在数据发送中处理，避免Socket冲突
    private var lastHeartbeatTime = 0L
    private val heartbeatInterval = 1000L // 1秒心跳间隔

    // 网络错误处理和重连机制 - 增强版
    private var consecutiveNetworkErrors = 0
    private var maxConsecutiveErrors = 3 // 降低阈值，更快触发恢复
    private var lastNetworkErrorTime = 0L
    private var networkErrorThreshold = 5000L // 5秒内连续错误阈值
    private var isNetworkRecovering = false
    
    // 智能重连策略
    private var reconnectAttempts = 0
    private var maxReconnectAttempts = 3
    private var lastReconnectTime = 0L
    private var reconnectDelay = 2000L // 2秒重连延迟
    private var lastSuccessfulSendTime = 0L
    
    // 连接稳定性监控
    private var connectionSwitchCount = 0
    private var lastConnectionSwitchTime = 0L
    private var connectionStabilityThreshold = 10000L // 10秒内切换超过3次认为不稳定

    // ATC状态跟踪（用于日志记录）
    private var lastAtcPausedState: Boolean? = null
    
    // 后台状态追踪 - 用于调整网络策略
    private var isInBackground = false
    
    // 移除数据去重机制，恢复简单发送逻辑
    
    // 事件回调接口
    private var onDeviceDiscovered: ((DeviceInfo) -> Unit)? = null
    private var onConnectionStatusChanged: ((Boolean, String) -> Unit)? = null
    private var onDataSent: ((Int) -> Unit)? = null
    
    /**
     * 设置后台状态
     * @param inBackground 是否在后台运行
     */
    fun setBackgroundState(inBackground: Boolean) {
        isInBackground = inBackground
        // 手动 Log.d(TAG, "🔄 CarrotManNetworkClient后台状态更新: $inBackground")
    }
    private var onOpenpilotStatusReceived: ((String) -> Unit)? = null
    
    // Comma3设备信息数据类（增强设备确认机制）
    data class DeviceInfo(
        val ip: String,          // 设备IP地址
        val port: Int,           // 通信端口号
        val version: String,     // 设备版本信息
        val lastSeen: Long = System.currentTimeMillis(),  // 最后发现时间
        val deviceId: String = "",  // 设备唯一标识
        val capabilities: List<String> = emptyList(),  // 设备能力列表
        val connectionQuality: Float = 0.0f,  // 连接质量评分
        val responseTime: Long = 0L,  // 响应时间
        val isVerified: Boolean = false  // 是否已验证
    ) {
        override fun toString(): String = "$ip:$port (v$version) [${if (isVerified) "✓" else "?"}]"
        
        fun isActive(): Boolean {
            return System.currentTimeMillis() - lastSeen < DEVICE_TIMEOUT
        }
        
        fun isReliable(): Boolean {
            return isVerified && connectionQuality > 0.5f && responseTime < 1000L
        }
    }
    
    // 启动 CarrotMan 网络服务
    fun start() {
        if (isRunning) {
            Log.w(TAG, "网络服务已在运行中，忽略重复启动请求")
            return
        }
        
        Log.i(TAG, "启动 CarrotMan 网络客户端服务")
        
        // 禁用系统调试输出以减少日志噪音
        disableSystemDebugOutput()
        
        isRunning = true
        
        try {
            // 获取手机IP地址
            phoneIP = getPhoneIPAddress()
            Log.i(TAG, "📱 手机IP地址: $phoneIP")
            
            initializeSockets()
            startDeviceListener()
            startDeviceHealthCheck()
            startHeartbeatTask() // 启动心跳任务而不是定时器
            onConnectionStatusChanged?.invoke(false, "")
            Log.i(TAG, "CarrotMan 网络服务启动成功")
        } catch (e: Exception) {
            Log.e(TAG, "启动网络服务失败: ${e.message}", e)
            onConnectionStatusChanged?.invoke(false, "")
            stop()
        }
    }
    
    // 停止 CarrotMan 网络服务
    fun stop() {
        Log.i(TAG, "停止 CarrotMan 网络客户端服务")
        isRunning = false
        
        listenJob?.cancel()
        dataSendJob?.cancel()
        autoSendJob?.cancel()
        deviceCheckJob?.cancel()
        heartbeatJob?.cancel()
        heartbeatJob = null
        
        listenSocket?.close()
        dataSocket?.close()
        tcpSocket?.close()
        tcp7712Socket?.close()

        listenSocket = null
        dataSocket = null
        tcpSocket = null
        tcp7712Socket = null
        tcp7712Writer = null
        currentTargetDevice = null
        
        // 保存停止状态到SharedPreferences
        saveNetworkStatus(false, "")
        
        onConnectionStatusChanged?.invoke(false, "")
        Log.i(TAG, "CarrotMan 网络服务已完全停止")
    }
    
    // 初始化UDP Socket连接
    private fun initializeSockets() {
        try {
            // 手动 Log.d(TAG, "开始初始化UDP Socket连接...")

            listenSocket = DatagramSocket(BROADCAST_PORT).apply {
                soTimeout = 1000 // 1秒超时，更频繁地检查isRunning状态
                reuseAddress = true
                broadcast = true // 启用广播接收
                // 手动 Log.d(TAG, "监听Socket已创建，端口: $BROADCAST_PORT，超时: 1000ms")
            }

            dataSocket = DatagramSocket().apply {
                soTimeout = SOCKET_TIMEOUT
                // 手动 Log.d(TAG, "数据发送Socket已创建，端口: ${localPort}")
            }

            // 手动 Log.i(TAG, "Socket初始化成功 - 监听端口: $BROADCAST_PORT (广播模式)")

        } catch (e: Exception) {
            Log.e(TAG, "Socket初始化失败: ${e.message}", e)
            listenSocket?.close()
            dataSocket?.close()
            listenSocket = null
            dataSocket = null
            throw e
        }
    }
    
    // 启动设备广播监听服务
    private fun startDeviceListener() {
        listenJob = networkScope.launch {
            Log.i(TAG, "✅ 启动设备广播监听服务 - 端口: $BROADCAST_PORT")

            while (isRunning) {
                try {
                    // 持续监听设备广播
                    listenForDeviceBroadcasts()
                } catch (e: Exception) {
                    if (isRunning) {
                        Log.e(TAG, "❌ 设备广播监听异常: ${e.message}", e)

                        // 短暂延迟后重试，避免快速失败循环
                        delay(1000)
                    }
                }

                if (isRunning) {
                    delay(100) // 短暂延迟，避免CPU占用过高
                }
            }
            Log.d(TAG, "设备广播监听服务已停止")
        }
    }
    
    // 持续监听设备广播消息
    private suspend fun listenForDeviceBroadcasts() = withContext(Dispatchers.IO) {
        val buffer = ByteArray(MAX_PACKET_SIZE)
        val packet = DatagramPacket(buffer, buffer.size)

        // 手动 Log.d(TAG, "开始监听UDP广播数据，端口: $BROADCAST_PORT")

        try {
            // 单次接收广播数据
            listenSocket?.receive(packet)
            val receivedData = String(packet.data, 0, packet.length)
            val deviceIP = packet.address.hostAddress ?: "unknown"

            //Log.i(TAG, "📡 收到设备广播: [$receivedData] from $deviceIP")
            //Log.d(TAG, "📊 当前状态: 已发现设备=${discoveredDevices.size}, 当前连接=${currentTargetDevice?.ip ?: "无"}")

            lastDataReceived = System.currentTimeMillis()
            parseDeviceBroadcast(receivedData, deviceIP)

        } catch (e: SocketTimeoutException) {
            // 超时是正常的，不需要特殊处理
            // 手动 Log.v(TAG, "广播监听超时，继续等待...")
        } catch (e: Exception) {
            if (isRunning) {
                Log.w(TAG, "接收广播数据异常: ${e.message}")
                throw e // 重新抛出异常，由上层处理
            }
        }
    }
    
    // 解析收到的设备广播数据
    private fun parseDeviceBroadcast(broadcastData: String, deviceIP: String) {
        try {
            //Log.i(TAG, "🔍 解析设备广播数据: $broadcastData from $deviceIP")
            //Log.d(TAG, "📊 解析前状态: 已发现设备=${discoveredDevices.size}, 当前连接=${currentTargetDevice?.ip ?: "无"}")

            if (broadcastData.trim().startsWith("{")) {
                val jsonBroadcast = JSONObject(broadcastData)

                // 检查是否为OpenpPilot状态数据
                if (isOpenpilotStatusData(jsonBroadcast)) {
                    //Log.d(TAG, "📡 检测到OpenpPilot状态数据 from $deviceIP")
                    onOpenpilotStatusReceived?.invoke(broadcastData)

                    // OpenpPilot状态数据也表示设备存在，需要添加到设备列表
                    val ip = jsonBroadcast.optString("ip", deviceIP)
                    val port = jsonBroadcast.optInt("port", MAIN_DATA_PORT)
                    val version = "openpilot"
                    val device = DeviceInfo(ip, port, version)
                    addDiscoveredDevice(device)
                    //Log.d(TAG, "从OpenpPilot状态数据中发现设备: $device")
                    return
                }

                // 处理设备发现数据
                val ip = jsonBroadcast.optString("ip", deviceIP)
                val port = jsonBroadcast.optInt("port", MAIN_DATA_PORT)
                val version = jsonBroadcast.optString("version", "unknown")
                
                val device = DeviceInfo(ip, port, version)
                addDiscoveredDevice(device)
                //Log.d(TAG, "JSON格式设备信息解析成功: $device")

            } else {
                //Log.d(TAG, "收到简单格式广播，使用默认配置: $deviceIP")
                val device = DeviceInfo(deviceIP, MAIN_DATA_PORT, "detected")
                addDiscoveredDevice(device)
            }

        } catch (e: Exception) {
            Log.w(TAG, "广播解析失败，回退到默认模式: $broadcastData - ${e.message}")
            val device = DeviceInfo(deviceIP, MAIN_DATA_PORT, "fallback")
            addDiscoveredDevice(device)
        }
    }
    
    // 检查JSON数据是否为OpenpPilot状态数据
    private fun isOpenpilotStatusData(jsonObject: JSONObject): Boolean {
        // OpenpPilot状态数据的特征字段
        val hasCarrot2 = jsonObject.has("Carrot2")
        val hasIsOnroad = jsonObject.has("IsOnroad")
        val hasVEgoKph = jsonObject.has("v_ego_kph")
        val hasActive = jsonObject.has("active")
        val hasXState = jsonObject.has("xState")
        
        val isOpenpilot = hasCarrot2 || hasIsOnroad || hasVEgoKph || hasActive || hasXState
        
        //Log.d(TAG, "🔍 检查OpenpPilot数据: Carrot2=$hasCarrot2, IsOnroad=$hasIsOnroad, v_ego_kph=$hasVEgoKph, active=$hasActive, xState=$hasXState -> $isOpenpilot")
        
        return isOpenpilot
    }
    
    // 添加新发现的设备到设备列表
    private fun addDiscoveredDevice(device: DeviceInfo) {
        val deviceKey = "${device.ip}:${device.port}"

        //Log.d(TAG, "🔍 尝试添加设备: $device, 设备键: $deviceKey")
        //Log.d(TAG, "📊 当前设备列表: ${discoveredDevices.keys}")

        if (!discoveredDevices.containsKey(deviceKey)) {
            discoveredDevices[deviceKey] = device
            //Log.i(TAG, "🎯 发现新的Comma3设备: $device")
            onDeviceDiscovered?.invoke(device)

            // 更新状态为发现设备
            if (currentTargetDevice == null) {
                Log.i(TAG, "🔄 更新状态: 发现设备 ${device.ip}，正在连接...")
                onConnectionStatusChanged?.invoke(false, "发现设备 ${device.ip}，正在连接...")
                //Log.i(TAG, "🚀 自动连接到第一个发现的设备")
                connectToDevice(device)
        } else {
                //Log.d(TAG, "⚠️ 已有连接设备 ${currentTargetDevice?.ip}，不自动连接新设备")
            }
        } else {
            discoveredDevices[deviceKey] = device.copy(lastSeen = System.currentTimeMillis())
            //Log.v(TAG, "🔄 更新设备活跃时间: $deviceKey")
        }

        //Log.d(TAG, "📊 添加后状态: 已发现设备=${discoveredDevices.size}, 当前连接=${currentTargetDevice?.ip ?: "无"}")
    }
    
    // 连接到指定的Comma3设备
    fun connectToDevice(device: DeviceInfo) {
        //Log.i(TAG, "🔗 开始连接到Comma3设备: $device")

        currentTargetDevice = device
        // 重置心跳时间，让心跳任务立即发送第一次心跳
        lastHeartbeatTime = 0L

        // 保存连接状态到SharedPreferences
        saveNetworkStatus(true, device.toString())

        //Log.i(TAG, "✅ 更新连接状态: 已连接到设备 ${device.ip}")
        onConnectionStatusChanged?.invoke(true, "")
        Log.i(TAG, "🎉 设备连接建立成功: ${device.ip}")
        
        // 播放连接成功音效 sound.mp3
        playRawSound(R.raw.sound, "设备连接成功")
    }
    
    // 生成设备ID
    private fun generateDeviceId(ip: String, port: Int): String {
        return "${ip.replace(".", "")}_${port}_${System.currentTimeMillis() % 10000}"
    }
    
    // 检测设备能力
    private fun detectDeviceCapabilities(device: DeviceInfo): List<String> {
        val capabilities = mutableListOf<String>()
        
        when (device.version) {
            "openpilot" -> {
                capabilities.add("openpilot")
                capabilities.add("autopilot")
                capabilities.add("navigation")
            }
            "comma3" -> {
                capabilities.add("comma3")
                capabilities.add("navigation")
            }
            else -> {
                capabilities.add("basic")
            }
        }
        
        return capabilities
    }
    
    /**
     * 启动心跳任务 - 保存 Job 引用以便 stop() 时可以取消
     */
    private fun startHeartbeatTask() {
        heartbeatJob?.cancel()
        heartbeatJob = networkScope.launch {
            Log.i(TAG, "💓 启动心跳任务")
            
            while (isRunning) {
                try {
                    if (currentTargetDevice != null) {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastHeartbeatTime >= heartbeatInterval) {
                    sendHeartbeat()
                            lastHeartbeatTime = currentTime
                        }
                    }
                    delay(100) // 100ms检查一次，避免过于频繁
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 心跳任务异常: ${e.message}", e)
                    delay(1000) // 异常时等待1秒再继续
                }
            }
            Log.d(TAG, "💓 心跳任务已停止")
        }
    }
    
    // 启动设备健康检查服务
    private fun startDeviceHealthCheck() {
        deviceCheckJob = networkScope.launch {
            Log.i(TAG, "启动设备健康检查服务，检查间隔: ${DISCOVER_CHECK_INTERVAL}ms")
            
            while (isRunning) {
                try {
                    val currentTime = System.currentTimeMillis()
                    val initialDeviceCount = discoveredDevices.size
                    
                    val removedDevices = discoveredDevices.values.filter { device ->
                        currentTime - device.lastSeen > DEVICE_TIMEOUT
                    }
                    
                    removedDevices.forEach { device ->
                        val deviceKey = "${device.ip}:${device.port}"
                        discoveredDevices.remove(deviceKey)
                        Log.i(TAG, "移除离线设备: $device")
                    }
                    
                    currentTargetDevice?.let { device ->
                        val deviceKey = "${device.ip}:${device.port}"
                        
                        if (!discoveredDevices.containsKey(deviceKey)) {
                            Log.w(TAG, "当前连接设备已离线: $device")
                            
                            currentTargetDevice = null
                            dataSendJob?.cancel()
                            
                            // 保存断开连接状态
                            saveNetworkStatus(false, "")
                            
                            discoveredDevices.values.firstOrNull()?.let { newDevice ->
                                Log.i(TAG, "自动切换到备用设备: $newDevice")
                                connectToDevice(newDevice)
                            } ?: run {
                                Log.w(TAG, "没有可用的备用设备")
                                onConnectionStatusChanged?.invoke(false, "")
                            }
                        }
                    }
                    
                    if (removedDevices.isNotEmpty()) {
                        Log.d(TAG, "健康检查完成 - 设备数量: $initialDeviceCount -> ${discoveredDevices.size}")
                    }

                    // 检查是否需要更新连接状态
                    if (currentTargetDevice == null && discoveredDevices.isEmpty()) {
                        onConnectionStatusChanged?.invoke(false, "")
                    } else if (currentTargetDevice == null && discoveredDevices.isNotEmpty()) {
                        onConnectionStatusChanged?.invoke(false, "")
                    }

                    delay(DISCOVER_CHECK_INTERVAL)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 设备健康检查失败: ${e.message}", e)
                    delay(5000)
                }
            }
            Log.d(TAG, "设备健康检查服务已停止")
        }
    }
    
    // 发送心跳包维持连接
    private suspend fun sendHeartbeat() = withContext(Dispatchers.IO) {
        val heartbeatData = JSONObject().apply {
            put("carrotIndex", ++carrotIndex)
            put("epochTime", System.currentTimeMillis() / 1000)
            put("timezone", "Asia/Shanghai")
            put("carrotCmd", "heartbeat")
            put("carrotArg", "")
            put("source", "android_app")
        }
        
        sendDataPacket(heartbeatData)
        //Log.v(TAG, "心跳包已发送，索引: $carrotIndex")
    }
    
    // 发送CarrotMan导航数据包
    fun sendCarrotManData(carrotFields: CarrotManFields) {
        if (!isRunning || currentTargetDevice == null) {
            // 降低无连接时的日志级别，避免日志刷屏
            if (System.currentTimeMillis() - lastNoConnectionLogTime > 60000) { // 60秒记录一次
                Log.d(TAG, "发送跳过: 服务=${isRunning}, 设备=${currentTargetDevice != null}")
                lastNoConnectionLogTime = System.currentTimeMillis()
            }
            return
        }

        // 如果正在网络恢复中，跳过发送
        if (isNetworkRecovering) {
            Log.d(TAG, "⏸️ 网络恢复中，跳过CarrotMan数据发送")
            return
        }

        // 发送完整导航数据（许可证系统已移除）
        //Log.d(TAG, "发送完整导航数据")

        networkScope.launch {
            try {
                val jsonData = convertCarrotFieldsToJson(carrotFields)
                sendDataPacket(jsonData)
                onDataSent?.invoke(++totalPacketsSent)
                //Log.v(TAG, "CarrotMan数据包发送成功 #$totalPacketsSent")
            } catch (e: Exception) {
                // 使用新的错误处理机制
                handleNetworkError(e, "CarrotMan数据发送")
                
                // 控制CarrotMan数据发送错误日志频率
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastNetworkErrorLogTime > 5000) { // 5秒记录一次
                    Log.w(TAG, "⚠️ CarrotMan数据发送失败: ${e.message}")
                    if (e.message?.contains("ENETUNREACH") == true) {
                        Log.w(TAG, "💡 建议：检查设备连接状态和网络配置")
                    }
                    lastNetworkErrorLogTime = currentTime
                }
            }
        }
    }
    
    /**
     * 立即发送当前 CarrotManFields 数据（用于命令发送）
     * 与 sendCarrotManData() 不同，此方法会立即发送，不受定时器控制
     * 适用于需要即时响应的控制指令（如变道、速度调节等）
     */
    fun sendCarrotManDataImmediately(fields: CarrotManFields) {
        if (!isRunning || currentTargetDevice == null) {
            Log.w(TAG, "⚠️ 立即发送命令失败 - 服务未运行或无连接设备")
            return
        }

        networkScope.launch {
            try {
                val jsonData = convertCarrotFieldsToJson(fields)
                sendDataPacket(jsonData)
                Log.i(TAG, "✅ 命令数据包立即发送成功: carrotCmd=${fields.carrotCmd}, carrotArg=${fields.carrotArg}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ 命令数据包发送失败: ${e.message}", e)
                handleNetworkError(e, "命令数据发送")
            }
        }
    }
    
    // 转换CarrotManFields为JSON协议格式（真实发送，递增 carrotIndex）
    private fun convertCarrotFieldsToJson(fields: CarrotManFields): JSONObject {
        val currentTime = System.currentTimeMillis()
        return build7706Payload(fields, ++carrotIndex, currentTime)
    }

    /**
     * 调试预览：与下一包 UDP 7706 正文一致，但不递增 [carrotIndex]（避免界面刷新「偷跑」序号）。
     */
    fun preview7706Json(fields: CarrotManFields): JSONObject =
        build7706Payload(fields, carrotIndex + 1, System.currentTimeMillis())
    
    
    // 🎵 播放原始资源音效（一次性）
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

    // 发送UDP数据包到目标设备
    private suspend fun sendDataPacket(jsonData: JSONObject) = withContext(Dispatchers.IO) {
        val device = currentTargetDevice ?: return@withContext
        
        // 如果正在网络恢复中，跳过发送
        if (isNetworkRecovering) {
            Log.d(TAG, "⏸️ 网络恢复中，跳过数据发送")
            return@withContext
        }
        
        try {
            val dataBytes = jsonData.toString().toByteArray(Charsets.UTF_8)
            
            if (dataBytes.size > MAX_PACKET_SIZE) {
                Log.w(TAG, "数据包过大: ${dataBytes.size} bytes (最大: $MAX_PACKET_SIZE)")
                return@withContext
            }
            
            val packet = DatagramPacket(
                dataBytes,
                dataBytes.size,
                InetAddress.getByName(device.ip),
                device.port
            )
            
            dataSocket?.send(packet)
            lastSendTime = System.currentTimeMillis()
            
            // 记录成功发送
            recordSuccessfulSend()
            
            //Log.v(TAG, "UDP数据包发送成功 -> ${device.ip}:${device.port} (${dataBytes.size} bytes)")
            
        } catch (e: Exception) {
            // 使用新的错误处理机制
            val shouldReconnect = handleNetworkError(e, "数据包发送")
            
            // 控制网络错误日志频率，避免刷屏
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastNetworkErrorLogTime > 5000) { // 5秒记录一次网络错误
                Log.w(TAG, "⚠️ 网络发送失败: ${e.message}")
                if (e.message?.contains("ENETUNREACH") == true) {
                    Log.w(TAG, "💡 网络不可达 - 请检查：1)设备是否在线 2)WiFi连接 3)网络配置")
                }
                lastNetworkErrorLogTime = currentTime
            }
            
            // 如果不需要重连，则抛出异常
            if (!shouldReconnect) {
                throw e
            }
        }
    }
    



    // 获取网络连接状态信息
    /**
     * 获取运行状态
     */
    fun isRunning(): Boolean = isRunning

    fun getConnectionStatus(): Map<String, Any> {
        // 改进设备连接状态显示逻辑
        val deviceStatus = when {
            currentTargetDevice != null -> currentTargetDevice.toString()
            isRunning && lastDataReceived > 0 -> "已连接(接收数据中)" // 网络运行且有数据接收
            isRunning && discoveredDevices.isNotEmpty() -> "搜索到${discoveredDevices.size}个设备"
            isRunning -> "搜索设备中..." // 网络运行但还没发现设备
            else -> "未连接"
        }
        
        return mapOf(
            "isRunning" to isRunning,
            "discoveredDevices" to discoveredDevices.size,
            "currentDevice" to deviceStatus,
            "totalPacketsSent" to totalPacketsSent,
            "lastSendTime" to lastSendTime,
            "lastDataReceived" to lastDataReceived,
            "carrotIndex" to carrotIndex,
            "deviceList" to discoveredDevices.values.map { it.toString() }
        )
    }
    
    // 获取发现的设备列表
    fun getDiscoveredDevices(): List<DeviceInfo> {
        return discoveredDevices.values.toList()
    }
    
    // 获取当前连接的设备信息
    fun getCurrentDevice(): DeviceInfo? {
        // 优先使用从JSON数据中获取的deviceIP
        if (deviceIP != null && currentTargetDevice != null) {
            // 如果deviceIP和currentTargetDevice都存在，返回使用deviceIP的设备信息
            return currentTargetDevice!!.copy(ip = deviceIP!!)
        }
        return currentTargetDevice
    }
    
    // 获取手机IP地址
    private fun getPhoneIPAddress(): String {
        try {
            // 优先使用网络接口方法，避免使用弃用的WiFi API
            val networkIP = getPhoneIPFromNetworkInterface()
            if (networkIP.isNotEmpty()) {
                return networkIP
            }
            
            // 如果网络接口方法失败，回退到WiFi方法（带弃用抑制）
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val wifiInfo = wifiManager.connectionInfo
            @Suppress("DEPRECATION")
            val ipAddress = wifiInfo.ipAddress
            
            if (ipAddress != 0) {
            val ip = String.format(
                "%d.%d.%d.%d",
                ipAddress and 0xff,
                ipAddress shr 8 and 0xff,
                ipAddress shr 16 and 0xff,
                ipAddress shr 24 and 0xff
            )
            return ip
            }
            
            return ""
            
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ 获取手机IP地址失败: ${e.message}")
            return ""
        }
    }
    
    // 从网络接口获取手机IP地址（备用方法）
    private fun getPhoneIPFromNetworkInterface(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isLoopback && networkInterface.isUp) {
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (address is Inet4Address && !address.isLoopbackAddress) {
                            return address.hostAddress ?: ""
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ 从网络接口获取IP地址失败: ${e.message}")
        }
        return ""
    }

    // 获取设备IP地址（优先使用从JSON数据中解析的IP）
    fun getDeviceIP(): String? {
        // 优先返回从JSON数据中解析的deviceIP
        val ip = deviceIP ?: currentTargetDevice?.ip
        return ip
    }

    // 获取手机IP地址
    fun getPhoneIP(): String {
        return phoneIP.ifEmpty { "未获取" }
    }
    
    // 设置设备发现事件回调
    fun setOnDeviceDiscovered(callback: (DeviceInfo) -> Unit) {
        onDeviceDiscovered = callback
        // 手动 Log.d(TAG, "设备发现回调已设置")
    }
    
    // 设置连接状态变化事件回调
    fun setOnConnectionStatusChanged(callback: (Boolean, String) -> Unit) {
        onConnectionStatusChanged = callback
        // 手动 Log.d(TAG, "连接状态回调已设置")
    }
    
    // 设置数据发送完成事件回调
    fun setOnDataSent(callback: (Int) -> Unit) {
        onDataSent = callback
        // 手动 Log.d(TAG, "数据发送回调已设置")
    }

    // 设置OpenpPilot状态数据接收回调
    fun setOnOpenpilotStatusReceived(callback: (String) -> Unit) {
        onOpenpilotStatusReceived = callback
        // 手动 Log.d(TAG, "OpenpPilot状态接收回调已设置")
    }
    
    /**
     * 禁用系统调试输出
     * 减少System.out的调试信息输出
     */
    private fun disableSystemDebugOutput() {
        try {
            // 重定向System.out到空输出流（兼容Android 7）
            System.setOut(object : java.io.PrintStream(object : java.io.OutputStream() {
                override fun write(b: Int) {
                    // 静默处理，不输出
                }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    // 静默处理，不输出
                }
            }) {
                override fun println(x: String?) {
                    // 静默处理，不输出
                }
                override fun print(s: String?) {
                    // 静默处理，不输出
                }
            })
        } catch (e: Exception) {
            // 忽略设置失败，不影响主要功能
        }
    }

    /**
     * 检查网络连接状态
     */
    fun checkNetworkStatus(): Map<String, Any> {
        val currentTime = System.currentTimeMillis()
        val hasConnection = currentTargetDevice != null && isRunning
        val lastErrorTime = if (lastNetworkErrorLogTime > 0) currentTime - lastNetworkErrorLogTime else -1
        
        return mapOf(
            "isRunning" to isRunning,
            "hasConnection" to hasConnection,
            "currentDevice" to (currentTargetDevice?.toString() ?: "无连接"),
            "discoveredDevices" to discoveredDevices.size,
            "lastSendTime" to lastSendTime,
            "lastDataReceived" to lastDataReceived,
            "lastErrorTime" to lastErrorTime,
            "networkQuality" to when {
                hasConnection && lastErrorTime > 30000 -> "优秀"
                hasConnection && lastErrorTime > 10000 -> "良好"
                hasConnection -> "一般"
                else -> "断开"
            }
        )
    }
    
    /**
     * 获取网络状态报告
     */
    fun getNetworkStatusReport(): String {
        val status = checkNetworkStatus()
        return buildString {
            appendLine("🌐 网络状态报告:")
            appendLine("  🔗 连接状态: ${if (status["hasConnection"] as Boolean) "已连接" else "未连接"}")
            appendLine("  📱 当前设备: ${status["currentDevice"]}")
            appendLine("  🔍 发现设备: ${status["discoveredDevices"]}个")
            appendLine("  📊 网络质量: ${status["networkQuality"]}")
            appendLine("  ⏰ 最后发送: ${if (status["lastSendTime"] as Long > 0) "${(System.currentTimeMillis() - status["lastSendTime"] as Long) / 1000}秒前" else "从未发送"}")
            appendLine("  📡 最后接收: ${if (status["lastDataReceived"] as Long > 0) "${(System.currentTimeMillis() - status["lastDataReceived"] as Long) / 1000}秒前" else "从未接收"}")
            if (status["lastErrorTime"] as Long > 0) {
                appendLine("  ⚠️ 最后错误: ${(status["lastErrorTime"] as Long) / 1000}秒前")
            }
            appendLine("  🔄 连续错误: $consecutiveNetworkErrors/$maxConsecutiveErrors")
            appendLine("  🛠️ 恢复状态: ${if (isNetworkRecovering) "正在恢复" else "正常"}")
        }
    }

    /**
     * 处理网络错误并决定是否重连 - 增强版
     */
    private fun handleNetworkError(exception: Exception, operation: String): Boolean {
        val currentTime = System.currentTimeMillis()
        
        // 检查是否在错误阈值时间内
        if (currentTime - lastNetworkErrorTime < networkErrorThreshold) {
            consecutiveNetworkErrors++
        } else {
            consecutiveNetworkErrors = 1
        }
        
        lastNetworkErrorTime = currentTime
        
        // 控制错误日志频率
        if (currentTime - lastNetworkErrorLogTime > 3000) { // 减少到3秒
            Log.w(TAG, "⚠️ 网络错误 [$operation]: ${exception.message}")
            lastNetworkErrorLogTime = currentTime
        }
        
        Log.w(TAG, "🔄 连续错误计数: $consecutiveNetworkErrors/$maxConsecutiveErrors")
        
        // 达到错误阈值时启动智能恢复流程
        if (consecutiveNetworkErrors >= maxConsecutiveErrors) {
            Log.w(TAG, "🚨 达到连续错误阈值，启动智能网络恢复")
            startIntelligentNetworkRecovery()
        }
        
        return consecutiveNetworkErrors >= maxConsecutiveErrors
    }

    /**
     * 启动智能网络恢复流程
     */
    private fun startIntelligentNetworkRecovery() {
        if (isNetworkRecovering) {
            Log.d(TAG, "🔄 网络恢复已在进行中，跳过重复启动")
            return
        }
        
        isNetworkRecovering = true
        reconnectAttempts = 0
        reconnectDelay = 2000L  // 重置退避延迟

        networkScope.launch {
            performIntelligentNetworkRecovery()
        }
    }
    
    /**
     * 执行智能网络恢复流程
     */
    private suspend fun performIntelligentNetworkRecovery() {
        try {
            Log.i(TAG, "🔄 开始智能网络恢复流程...")
            
            // 1. 重置当前连接
        currentTargetDevice = null
            onConnectionStatusChanged?.invoke(false, "智能恢复中...")
            
            // 2. 重新初始化Socket
            try {
                dataSocket?.close()
                dataSocket = null
                
                dataSocket = DatagramSocket().apply {
                    soTimeout = SOCKET_TIMEOUT
                }
                Log.i(TAG, "✅ Socket重新初始化成功")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Socket重新初始化失败: ${e.message}", e)
            }
            
            // 3. 智能重连策略
            while (reconnectAttempts < maxReconnectAttempts && isRunning) {
                reconnectAttempts++
                val currentTime = System.currentTimeMillis()
                
                // 检查重连间隔
                if (currentTime - lastReconnectTime < reconnectDelay) {
                    val waitTime = reconnectDelay - (currentTime - lastReconnectTime)
                    Log.d(TAG, "⏳ 等待重连间隔: ${waitTime}ms")
                    delay(waitTime)
                }
                
                lastReconnectTime = System.currentTimeMillis()
                
                Log.i(TAG, "🔍 重新扫描可用设备... (尝试 $reconnectAttempts/$maxReconnectAttempts)")
                
                // 4. 重新扫描设备
                val availableDevices = discoveredDevices.values.filter { it.isActive() }
                
                if (availableDevices.isNotEmpty()) {
                    val targetDevice = availableDevices.first()
                    Log.i(TAG, "🎯 发现可用设备，尝试重连: $targetDevice")
                    
                    // 尝试连接
                    try {
                        connectToDevice(targetDevice)
                        
                        // 等待连接稳定
                        delay(1000)
                        
                        // 验证连接是否成功
                        if (currentTargetDevice != null) {
                            // 重置错误计数
                            consecutiveNetworkErrors = 0
                            isNetworkRecovering = false
                            lastSuccessfulSendTime = System.currentTimeMillis()
                            
                            Log.i(TAG, "✅ 智能网络恢复成功")
                            onConnectionStatusChanged?.invoke(true, "")
                            return
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ 重连尝试失败: ${e.message}")
                    }
                } else {
                    Log.w(TAG, "⚠️ 未发现可用设备，等待设备上线...")
                }
                
                // 增加重连延迟
                reconnectDelay = minOf(reconnectDelay * 2, 10000L) // 最大10秒
            }
            
            // 所有重连尝试失败
            Log.w(TAG, "❌ 智能网络恢复失败，已达到最大重连次数")
            isNetworkRecovering = false
            onConnectionStatusChanged?.invoke(false, "网络恢复失败")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 智能网络恢复异常: ${e.message}", e)
            isNetworkRecovering = false
        }
    }

    /**
     * 记录成功发送，重置错误计数
     */
    private fun recordSuccessfulSend() {
        consecutiveNetworkErrors = 0
        lastSuccessfulSendTime = System.currentTimeMillis()
        isNetworkRecovering = false
    }

    // 保存网络状态到SharedPreferences
    private fun saveNetworkStatus(isRunning: Boolean, currentDevice: String) {
        try {
            val sharedPreferences = context.getSharedPreferences("network_status", Context.MODE_PRIVATE)
            sharedPreferences.edit().apply {
                putBoolean("is_running", isRunning)
                putString("current_device", currentDevice)
                putLong("last_update", System.currentTimeMillis())
                apply()
            }
            Log.d(TAG, "网络状态已保存: running=$isRunning, device=$currentDevice")
        } catch (e: Exception) {
            Log.e(TAG, "保存网络状态失败: ${e.message}", e)
        }
    }

    /**
     * 启动自动发送 CarrotMan 导航数据的后台任务
     * @param autoSendEnabled 是否启用自动发送的可变状态
     * @param carrotManFieldsState 当前 CarrotMan 字段的状态容器
     * @param sendInterval      发送间隔，默认为 200ms
     */
    fun startAutoDataSending(
        autoSendEnabled: MutableState<Boolean>,
        carrotManFieldsState: MutableState<CarrotManFields>,
        sendInterval: Long = 200L
    ) {
        Log.i(TAG, "📡 启动自动数据发送任务(客户端)…")

        // 若已有任务在运行，先取消
        autoSendJob?.cancel()

        autoSendJob = networkScope.launch {
            var lastSendTime = 0L
            while (isRunning) {
                try {
                    val currentFields = carrotManFieldsState.value
                    val shouldSend = autoSendEnabled.value && (
                        System.currentTimeMillis() - lastSendTime > sendInterval || 
                        currentFields.needsImmediateSend
                    )
                    
                    if (shouldSend) {
                        // 只在有连接设备时记录详细日志
                        if (currentTargetDevice != null) {
                            if (currentFields.needsImmediateSend) {
                                Log.i(TAG, "🚀 立即发送数据包 (限速变化):")
                            } else {
                                //Log.d(TAG, "📤 准备自动发送数据包:")
                            }
                            //Log.d(TAG, "   位置: lat=${currentFields.latitude}, lon=${currentFields.longitude}")
                            //Log.d(TAG, "  🛣️ 道路: ${currentFields.szPosRoadName}")
                            //Log.d(TAG, "  🚦 限速: ${currentFields.nRoadLimitSpeed}km/h")
                            //Log.d(TAG, "  🎯 目标: ${currentFields.szGoalName}")
                            //Log.d(TAG, "  🧭 导航状态: ${currentFields.isNavigating}")
                            //Log.d(TAG, "  🔄 转向信息: 类型=${currentFields.nTBTTurnType}, 距离=${currentFields.nTBTDist}m, 指令=${currentFields.szTBTMainText}")
                            //Log.d(TAG, "  🔄 下一转向: 类型=${currentFields.nTBTTurnTypeNext}, 距离=${currentFields.nTBTDistNext}m")
                        }

                        sendCarrotManData(currentFields)
                        lastSendTime = System.currentTimeMillis()
                        
                        // 重置立即发送标记
                        if (currentFields.needsImmediateSend) {
                            carrotManFieldsState.value = currentFields.copy(needsImmediateSend = false)
                        }

                        // 只在有连接设备时记录成功日志
                        if (currentTargetDevice != null) {
                            if (currentFields.needsImmediateSend) {
                                Log.i(TAG, "✅ 立即发送数据包完成 (限速已更新)")
                            } else {
                                //Log.i(TAG, "✅ 自动发送数据包完成")
                            }
                        }
                    } else {
                        //Log.v(TAG, "⏸️ 自动发送跳过: enabled=${autoSendEnabled.value}, 时间间隔=${System.currentTimeMillis() - lastSendTime}ms, 立即发送=${currentFields.needsImmediateSend}")
                    }
                    delay(sendInterval)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 自动数据发送失败: ${e.message}", e)
                    delay(1000)
                }
            }
        }
    }

    /**
     * 发送自定义JSON数据包（用于控制指令等）
     * @param jsonData 要发送的JSON数据
     */
    fun sendCustomDataPacket(jsonData: JSONObject) {
        if (!isRunning || currentTargetDevice == null) {
            Log.w(TAG, "⚠️ 网络服务未运行或无连接设备，无法发送自定义数据包")
            return
        }

        networkScope.launch {
            try {
                sendDataPacket(jsonData)
                totalPacketsSent++
                
                //Log.i(TAG, "✅ 自定义数据包发送成功 #$totalPacketsSent")
                //Log.d(TAG, "📦 数据内容: ${jsonData.toString()}")
                
                onDataSent?.invoke(totalPacketsSent)
            } catch (e: Exception) {
                Log.e(TAG, "❌ 发送自定义数据包失败: ${e.message}", e)
            }
        }
    }

    // 清理网络客户端资源
    fun cleanup() {
        Log.i(TAG, "开始清理CarrotMan网络客户端资源")
        
        stop()
        networkScope.cancel()
        discoveredDevices.clear()
        currentTargetDevice = null
        
        carrotIndex = 0L
        totalPacketsSent = 0
        lastSendTime = 0L
        lastDataReceived = 0L
        
        Log.i(TAG, "CarrotMan网络客户端资源清理完成")
    }

    /**
     * 🆕 Feature 1: 通过 TCP 7709 发送路线点串到 Comma3
     * Python 端 carrot_route() 在 7709 端口监听 TCP 连接，
     * 协议格式：4字节总长度(big-endian uint32) + N个(float32 lon, float32 lat)对
     *
     * @param routePoints 路线点列表 List<Pair<Double, Double>> (lon, lat) WGS-84
     */
    fun sendRoutePointsViaTcp(routePoints: List<Pair<Double, Double>>) {
        val device = currentTargetDevice ?: run {
            Log.w(TAG, "⚠️ 无连接设备，无法发送路线点")
            return
        }

        if (routePoints.isEmpty()) {
            Log.w(TAG, "⚠️ 路线点为空，跳过发送")
            return
        }

        networkScope.launch {
            try {
                val ip = device.ip
                Log.i(TAG, "🛣️ 开始发送路线点到 $ip:$TCP_VERTEX_PORT (${routePoints.size}个点)")

                // 构建二进制数据：每个点 = 2个float32 = 8字节
                val totalSize = routePoints.size * 8
                val buffer = java.nio.ByteBuffer.allocate(totalSize)
                buffer.order(java.nio.ByteOrder.BIG_ENDIAN)

                for ((lon, lat) in routePoints) {
                    buffer.putFloat(lon.toFloat())
                    buffer.putFloat(lat.toFloat())
                }

                // TCP连接并发送
                val socket = Socket()
                socket.connect(InetSocketAddress(ip, TCP_VERTEX_PORT), 5000)
                socket.soTimeout = 5000

                val out = DataOutputStream(socket.getOutputStream())
                // 先发送4字节总长度
                out.writeInt(totalSize)
                // 再发送所有点数据
                out.write(buffer.array())
                out.flush()

                socket.close()
                Log.i(TAG, "✅ 路线点发送成功: ${routePoints.size}个点, ${totalSize}字节")

            } catch (e: Exception) {
                Log.w(TAG, "⚠️ 路线点TCP发送失败: ${e.message}")
                // 不触发网络错误恢复，TCP发送失败不影响UDP通信
            }
        }
    }

    // ==================== 7712/7713 端口支持 ====================

    private val TCP_NAVI_PORT = 7712  // TCP导航端口（rgdata/vrtx）
    private val HTTP_NAVI_PORT = 7713  // HTTP导航端口（sinf）

    /**
     * 连接到 TCP 7712 端口（rgdata/vrtx）
     */
    private fun connectTcp7712(ip: String) {
        try {
            tcp7712Socket?.close()
            tcp7712Socket = Socket()
            tcp7712Socket?.connect(InetSocketAddress(ip, TCP_NAVI_PORT), 5000)
            tcp7712Socket?.soTimeout = 5000
            tcp7712Writer = java.io.PrintWriter(java.io.BufferedWriter(java.io.OutputStreamWriter(tcp7712Socket?.getOutputStream())), true)
            Log.i(TAG, "✅ TCP 7712 连接成功: $ip:$TCP_NAVI_PORT")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ TCP 7712 连接失败: ${e.message}")
            tcp7712Socket = null
            tcp7712Writer = null
        }
    }

    /**
     * 通过 TCP 7712 发送 rgdata（导航状态数据）
     * 格式: {"rgdata": {...}}
     */
    fun sendRgdataViaTcp7712(fields: CarrotManFields) {
        val device = currentTargetDevice ?: return
        val ip = device.ip

        // 确保连接已建立
        if (tcp7712Socket == null || tcp7712Writer == null) {
            connectTcp7712(ip)
        }

        networkScope.launch {
            try {
                val rgdataJson = buildRgdataJson(fields)
                tcp7712Writer?.println(rgdataJson)
                Log.v(TAG, "📤 TCP 7712 rgdata 发送成功")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ TCP 7712 rgdata 发送失败: ${e.message}")
                // 重置连接，下次会重新建立
                tcp7712Socket = null
                tcp7712Writer = null
            }
        }
    }

    /**
     * 通过 TCP 7712 发送 vrtx（路线点数据）
     * 格式: {"vrtx": [{"x": lon, "y": lat}, ...]}
     */
    fun sendVrtxViaTcp7712(points: List<Pair<Double, Double>>) {
        if (points.isEmpty()) return

        val device = currentTargetDevice ?: return
        val ip = device.ip

        // 确保连接已建立
        if (tcp7712Socket == null || tcp7712Writer == null) {
            connectTcp7712(ip)
        }

        networkScope.launch {
            try {
                val vrtxJson = buildVrtxJson(points)
                tcp7712Writer?.println(vrtxJson)
                Log.i(TAG, "📤 TCP 7712 vrtx 发送成功: ${points.size}个点")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ TCP 7712 vrtx 发送失败: ${e.message}")
                tcp7712Socket = null
                tcp7712Writer = null
            }
        }
    }

    /**
     * 通过 HTTP 7713 发送 sinf（交通灯数据）
     * 格式: POST /api/navi {"sinf": {...}}
     */
    fun sendSinfViaHttp7713(fields: CarrotManFields) {
        val device = currentTargetDevice ?: return
        val ip = device.ip

        networkScope.launch {
            try {
                val sinfJson = buildSinfJson(fields)
                val url = "http://$ip:$HTTP_NAVI_PORT/api/navi"
                val result = sendHttpPost(url, sinfJson)
                if (result) {
                    Log.v(TAG, "📤 HTTP 7713 sinf 发送成功")
                } else {
                    Log.w(TAG, "⚠️ HTTP 7713 sinf 发送失败")
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ HTTP 7713 sinf 发送异常: ${e.message}")
            }
        }
    }

    /**
     * 构建 rgdata JSON（从 CarrotManFields 提取导航数据）
     */
    private fun buildRgdataJson(fields: CarrotManFields): String {
        val json = JSONObject()
        val rgdata = JSONObject().apply {
            put("nRoadLimitSpeed", fields.nRoadLimitSpeed)
            put("roadcate", fields.roadcate)
            put("nSdiType", fields.nSdiType)
            put("nSdiSpeedLimit", fields.nSdiSpeedLimit)
            put("nSdiDist", fields.nSdiDist)
            put("nSdiSection", fields.nSdiSection)
            put("nSdiBlockType", fields.nSdiBlockType)
            put("nSdiBlockSpeed", fields.nSdiBlockSpeed)
            put("nSdiBlockDist", fields.nSdiBlockDist)
            put("nSdiPlusType", fields.nSdiPlusType)
            put("nSdiPlusSpeedLimit", fields.nSdiPlusSpeedLimit)
            put("nSdiPlusDist", fields.nSdiPlusDist)
            put("nSdiPlusBlockType", fields.nSdiPlusBlockType)
            put("nSdiPlusBlockSpeed", fields.nSdiPlusBlockSpeed)
            put("nSdiPlusBlockDist", fields.nSdiPlusBlockDist)
            put("nTBTDist", fields.nTBTDist)
            put("nTBTTurnType", fields.nTBTTurnType)
            put("szTBTMainText", fields.szTBTMainText)
            put("szNearDirName", fields.szNearDirName)
            put("szFarDirName", fields.szFarDirName)
            put("nTBTNextRoadWidth", fields.nTBTNextRoadWidth)
            put("nTBTDistNext", fields.nTBTDistNext)
            put("nTBTTurnTypeNext", fields.nTBTTurnTypeNext)
            put("szTBTMainTextNext", fields.szTBTMainTextNext)
            put("nGoPosDist", fields.nGoPosDist)
            put("nGoPosTime", fields.nGoPosTime)
            put("szPosRoadName", fields.szPosRoadName)
            put("vpPosPointLat", fields.vpPosPointLat)
            put("vpPosPointLon", fields.vpPosPointLon)
            put("nPosAngle", fields.nPosAngle)
            put("nPosSpeed", fields.nPosSpeed)
            put("goalPosX", fields.goalPosX)
            put("goalPosY", fields.goalPosY)
            put("szGoalName", fields.szGoalName)
            put("timestamp_ms", System.currentTimeMillis())
        }
        json.put("rgdata", rgdata)
        return json.toString()
    }

    /**
     * 构建 vrtx JSON（路线点数组）
     */
    private fun buildVrtxJson(points: List<Pair<Double, Double>>): String {
        val json = JSONObject()
        val vrtxArray = org.json.JSONArray()
        for ((lon, lat) in points) {
            val point = JSONObject().apply {
                put("x", lon)
                put("y", lat)
                put("valid", true)
            }
            vrtxArray.put(point)
        }
        json.put("vrtx", vrtxArray)
        return json.toString()
    }

    /**
     * 构建 sinf JSON（交通灯数据）
     */
    private fun buildSinfJson(fields: CarrotManFields): String {
        val json = JSONObject()
        val sinf = JSONObject().apply {
            put("distance", fields.trafficLightDistance)
            when (fields.trafficLightState) {
                1 -> {  // 红灯
                    put("redLightOn", true)
                    put("redLightRemainTime", fields.trafficLightCountdown)
                }
                2 -> {  // 绿灯
                    put("greenLightOn", true)
                    put("greenLightRemainTime", fields.trafficLightCountdown)
                }
                else -> {
                    put("redLightOn", false)
                    put("greenLightOn", false)
                }
            }
            // 添加GPS位置（可选）
            if (fields.latitude != 0.0 && fields.longitude != 0.0) {
                val location = JSONObject().apply {
                    put("latitude", fields.latitude)
                    put("longitude", fields.longitude)
                }
                put("location", location)
            }
        }
        json.put("sinf", sinf)
        return json.toString()
    }

    /**
     * 发送 HTTP POST 请求
     */
    private suspend fun sendHttpPost(url: String, jsonBody: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val urlObj = URL(url)
            val conn = urlObj.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val os = conn.outputStream
            os.write(jsonBody.toByteArray(Charsets.UTF_8))
            os.flush()
            os.close()

            val responseCode = conn.responseCode
            conn.disconnect()
            responseCode in 200..299
        } catch (e: Exception) {
            Log.w(TAG, "HTTP POST 失败: ${e.message}")
            false
        }
    }
}
