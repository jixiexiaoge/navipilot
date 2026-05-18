package com.example.carrotamap

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * 解析结果密封类
 * 用于区分成功解析、空数据包（心跳包）和解析错误
 */
private sealed class ParseResult {
    data class Success(val data: XiaogeVehicleData) : ParseResult()
    object EmptyData : ParseResult()  // 空数据包（心跳包，data字段为空对象）
    data class ParseError(val error: String) : ParseResult()
}

/**
 * 小鸽数据接收器
 * 通过TCP连接到7711端口，接收数据包，解析数据，存储到内存，自动清理过期数据
 * ✅ 已更新：从UDP广播改为TCP连接模式，适配Python端的TCP服务器
 */
class XiaogeDataReceiver(
    private val context: Context,
    private val onDataReceived: (XiaogeVehicleData?) -> Unit,
    private val onConnectionStatusChanged: ((Boolean) -> Unit)? = null,
    private val onReconnectFailed: (() -> Unit)? = null,  // 🆕 重连失败回调
    private val onDataTimeoutChanged: ((Boolean) -> Unit)? = null  // 🆕 数据超时状态回调
) {
    companion object {
        private const val TAG = "XiaogeDataReceiver"
        private const val TCP_PORT = 7711  // TCP 端口号
        private const val MAX_PACKET_SIZE = 65536  // 🆕 64KB，支持更大的数据包
        private const val DATA_TIMEOUT_MS = 4000L // 4秒数据超时
        private const val CLEANUP_INTERVAL_MS = 1000L // 1秒检查一次
        private const val RECONNECT_DELAY_MS = 2000L // Socket错误后重连延迟（初始2秒）
        private const val MAX_RECONNECT_DELAY_MS = 30000L // 🆕 最大重连延迟（30秒）
        private const val MAX_RECONNECT_ATTEMPTS = 10 // 🆕 增加最大重连次数，使用指数退避
        private const val SOCKET_TIMEOUT_MS = 3000  // 🆕 Socket读取超时（3秒，必须小于DATA_TIMEOUT_MS）
        private const val MAX_CONSECUTIVE_FAILURES = 10 // 🆕 最大连续失败次数，超过后重新连接
    }

    private var _isRunning = false
    private var tcpSocket: Socket? = null  // TCP Socket连接
    private var dataInputStream: DataInputStream? = null  // 数据输入流
    private var dataOutputStream: java.io.DataOutputStream? = null // 数据输出流（用于发送心跳）
    private var listenJob: Job? = null
    private var cleanupJob: Job? = null
    private var heartbeatJob: Job? = null // 心跳任务
    private var networkScope: CoroutineScope? = null
    
    private var lastDataTime: Long = 0
    private var reconnectAttempts = 0  // 🆕 当前重连尝试次数
    private var serverIP: String? = null
    private var networkManager: NetworkManager? = null
    private var heartbeatSendCount = 0L
    private val isTcpConnected = AtomicBoolean(false)  // 🆕 使用AtomicBoolean保证线程安全
    private var hasNotifiedReconnectFailure = false  // 🆕 是否已通知重连失败
    @Volatile private var lastTimeoutState = false  // 🆕 记录上次的超时状态，避免频繁回调
    private var reconnectDelay = RECONNECT_DELAY_MS  // 🆕 当前重连延迟（指数退避）
    
    /**
     * 检查接收器是否正在运行
     */
    val isRunning: Boolean
        get() = _isRunning
    
    /**
     * 🆕 检查TCP是否已连接（线程安全）
     */
    val isTcpSocketConnected: Boolean
        get() = isTcpConnected.get()

    /**
     * 🆕 设置NetworkManager引用（用于自动获取设备IP）
     * @param networkManager NetworkManager实例
     */
    fun setNetworkManager(networkManager: NetworkManager?) {
        this.networkManager = networkManager
        Log.d(TAG, "🔗 已设置NetworkManager引用: ${if (networkManager != null) "已设置" else "已清除"}")
    }

    /**
     * 启动数据接收服务
     * 🆕 简化：移除IP检查任务，由NetworkManager回调触发连接
     */
    fun start(serverIP: String? = null) {
        if (_isRunning) {
            Log.w(TAG, "⚠️ 数据接收服务已在运行")
            return
        }

        val initialIP = serverIP ?: tryGetDeviceIPFromNetworkManager()
        this.serverIP = initialIP
        reconnectAttempts = 0  // 重置重连计数
        hasNotifiedReconnectFailure = false  // 重置通知标志
        reconnectDelay = RECONNECT_DELAY_MS  // 🆕 重置重连延迟
        
        Log.i(TAG, "🚀 启动数据接收 - TCP:$TCP_PORT, IP:${initialIP ?: "等待IP"}")
        _isRunning = true

        try {
            networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            startListener()
            startCleanupTask()
            // 🆕 移除IP检查任务，由NetworkManager回调触发连接
        } catch (e: Exception) {
            Log.e(TAG, "❌ 启动失败: ${e.message}")
            _isRunning = false
            networkScope?.cancel()
            networkScope = null
        }
    }
    
    /**
     * 从NetworkManager获取设备IP
     */
    private fun tryGetDeviceIPFromNetworkManager(): String? {
        return try {
            networkManager?.getCurrentDeviceIP()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 停止数据接收服务
     */
    fun stop() {
        if (!_isRunning) return

        Log.i(TAG, "🛑 停止数据接收服务")
        _isRunning = false

        listenJob?.cancel()
        cleanupJob?.cancel()
        heartbeatJob?.cancel()
        closeSocket()
        networkScope?.cancel()
        networkScope = null

        lastDataTime = 0
        reconnectAttempts = 0
        hasNotifiedReconnectFailure = false
        reconnectDelay = RECONNECT_DELAY_MS  // 🆕 重置重连延迟
        onDataReceived(null)
    }

    /**
     * 设置服务器IP地址
     * 🆕 简化：IP变化时立即更新并触发连接，重置重连计数
     */
    fun setServerIP(ip: String) {
        if (ip.isEmpty()) return
        
        if (serverIP != ip) {
            Log.i(TAG, "📍 更新IP: ${serverIP ?: "null"} -> $ip")
            serverIP = ip
            reconnectAttempts = 0  // 重置重连计数
            hasNotifiedReconnectFailure = false  // 重置通知标志
            reconnectDelay = RECONNECT_DELAY_MS  // 🆕 重置重连延迟
            
            // 如果正在运行，关闭旧连接触发重连
            if (_isRunning) {
                closeSocket()  // 关闭旧连接，触发重连
            }
        }
    }

    /**
     * 连接到TCP服务器
     * 🆕 简化：直接连接，不检查当前连接状态
     */
    private fun connectToServer(): Boolean {
        val ip = serverIP
        if (ip.isNullOrEmpty()) return false
        
        return try {
            // 关闭旧连接（如果存在）
            closeSocket()
            
            // 建立新连接
            tcpSocket = Socket(ip, TCP_PORT).apply {
                soTimeout = SOCKET_TIMEOUT_MS
                tcpNoDelay = true
            }
            dataInputStream = DataInputStream(tcpSocket!!.getInputStream())
            dataOutputStream = java.io.DataOutputStream(tcpSocket!!.getOutputStream())
            
            // 更新连接状态（线程安全）
            isTcpConnected.set(true)
                onConnectionStatusChanged?.invoke(true)
            
            Log.i(TAG, "✅ 已连接到 $ip:$TCP_PORT")
            
            // 🆕 初始化lastDataTime，避免连接刚建立时立即触发超时
            lastDataTime = System.currentTimeMillis()
            
            // 启动心跳任务
            startHeartbeatTask()
            
            true
        } catch (e: Exception) {
            Log.w(TAG, "❌ 连接失败 $ip:$TCP_PORT - ${e.message}")
            closeSocket()
            false
        }
    }
    
    /**
     * 关闭Socket连接
     * 优化：立即中断正在进行的读取操作，支持快速重连
     */
    private fun closeSocket() {
        heartbeatJob?.cancel()
        
        val wasConnected = isTcpConnected.get()
        
        // 更新连接状态（线程安全）
        if (isTcpConnected.compareAndSet(true, false)) {
            onConnectionStatusChanged?.invoke(false)
        }
        
        try {
            // 关键优化：立即中断读取操作
            tcpSocket?.shutdownInput()
        } catch (e: Exception) {
            // 忽略
        }
        
        try {
            dataOutputStream?.close()
            dataOutputStream = null
        } catch (e: Exception) {
            // 忽略
        }
        
        try {
            dataInputStream?.close()
            dataInputStream = null
        } catch (e: Exception) {
            // 忽略
        }
        
        try {
            tcpSocket?.close()
            tcpSocket = null
        } catch (e: Exception) {
            // 忽略
        }
        
        if (wasConnected) {
            Log.d(TAG, "🔌 连接已关闭")
        }
    }
    
    /**
     * 启动心跳任务（每5秒）
     * 🆕 改进：使用异常检测连接状态，而不是isConnected（不可靠）
     */
    private fun startHeartbeatTask() {
        heartbeatJob?.cancel()
        heartbeatJob = networkScope?.launch {
            while (_isRunning && isTcpConnected.get()) {
                try {
                    delay(5000)
                    
                    // 🆕 通过实际发送来检测连接状态
                    dataOutputStream?.apply {
                        writeInt(2)  // 发送心跳包
                        flush()
                        heartbeatSendCount++
                        if (heartbeatSendCount % 10 == 0L) {
                            Log.d(TAG, "💓 心跳 #$heartbeatSendCount")
                        }
                    } ?: break  // 如果输出流为null，退出循环
                } catch (e: Exception) {
                    // 🆕 发送失败，连接已断开
                    Log.w(TAG, "💓 心跳发送失败: ${e.message}，连接可能已断开")
                    closeSocket()
                    break
                }
            }
        }
    }
    
    /**
     * 获取Android设备的IP地址（用于调试）
     */
    private fun getDeviceIPAddress(): String {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isLoopback && networkInterface.isUp) {
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (address is java.net.Inet4Address && !address.isLoopbackAddress) {
                            return address.hostAddress ?: "未知"
                        }
                    }
                }
            }
            "未获取"
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ 获取设备IP地址失败: ${e.message}")
            "获取失败"
        }
    }

    /**
     * 启动监听任务
     * 🆕 重构：实现严格的重连机制（最多3次），失败后提示用户重启app
     */
    private fun startListener() {
        listenJob = networkScope?.launch {
            Log.i(TAG, "✅ 启动TCP数据接收任务")

            var packetCount = 0L
            var successCount = 0L
            var failCount = 0L
            var heartbeatCount = 0L  // 心跳包计数（长度为0）
            var emptyDataCount = 0L  // 🆕 空数据包计数（data字段为空对象）
            var consecutiveFailures = 0  // 连续失败次数（每次成功时重置）
            var lastLogTime = System.currentTimeMillis()  // 🆕 上次日志时间，用于计算接收频率
            var consecutiveSocketTimeouts = 0  // 🆕 连续Socket超时次数（用于容忍短暂网络波动）
            var lastPacketReceiveTime = 0L  // 🆕 上次数据包接收时间（用于计算接收间隔）
            
            while (_isRunning) {
                try {
                    // 检查 socket 是否已连接
                    val socket = tcpSocket
                    val inputStream = dataInputStream
                    
                    if (socket == null || socket.isClosed || inputStream == null) {
                        // TCP连接已断开，尝试重连
                        if (serverIP.isNullOrEmpty()) {
                            // 无IP地址，静默等待NetworkManager回调设置IP
                            if (reconnectAttempts == 0) {
                                Log.d(TAG, "等待NetworkManager发现设备...")
                            }
                            delay(RECONNECT_DELAY_MS)
                            continue
                        }
                        
                        // 🆕 检查是否超过最大重连次数（使用指数退避策略）
                        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                            // 超过最大重连次数，通知用户并延长重试间隔
                            if (!hasNotifiedReconnectFailure) {
                                Log.e(TAG, "❌ 重连失败，已尝试${MAX_RECONNECT_ATTEMPTS}次")
                                onReconnectFailed?.invoke()
                                hasNotifiedReconnectFailure = true
                            }
                            // 使用最大延迟继续重试（不再指数增长）
                            reconnectDelay = MAX_RECONNECT_DELAY_MS
                            Log.w(TAG, "⚠️ 重连次数过多，${reconnectDelay/1000}秒后重试...")
                            delay(reconnectDelay)
                            reconnectAttempts = 0  // 重置计数，继续重试
                            continue
                        }
                        
                        // 尝试重连（指数退避）
                        reconnectAttempts++
                        Log.i(TAG, "🔄 尝试重连到 $serverIP... (第${reconnectAttempts}/${MAX_RECONNECT_ATTEMPTS}次, 延迟: ${reconnectDelay/1000}秒)")
                        if (connectToServer()) {
                            // 连接成功，重置计数和延迟
                            reconnectAttempts = 0
                            reconnectDelay = RECONNECT_DELAY_MS  // 重置延迟
                            hasNotifiedReconnectFailure = false
                            consecutiveFailures = 0
                            consecutiveSocketTimeouts = 0  // 🆕 重置Socket超时计数
                            continue
                        } else {
                            // 连接失败，使用指数退避等待后继续重试
                            reconnectDelay = min(reconnectDelay * 2, MAX_RECONNECT_DELAY_MS)
                            Log.w(TAG, "❌ 重连失败，${reconnectDelay/1000}秒后重试...")
                            delay(reconnectDelay)
                            continue
                        }
                    }
                    
                    // TCP数据包格式：先读取4字节长度
                    val packetSize = try {
                        inputStream.readInt()  // 读取数据包长度（网络字节序，big-endian）
                    } catch (e: IOException) {
                        if (_isRunning) {
                            Log.w(TAG, "⚠️ 读取数据包长度失败: ${e.message}")
                            closeSocket()
                        }
                        continue
                    }
                    
                    packetCount++
                    // 🆕 成功接收数据，重置重连计数和失败计数
                    reconnectAttempts = 0
                    hasNotifiedReconnectFailure = false
                    consecutiveFailures = 0
                    consecutiveSocketTimeouts = 0  // 🆕 重置Socket超时计数
                    
                    // 处理心跳包（长度为0）
                    if (packetSize == 0) {
                        heartbeatCount++
                        lastDataTime = System.currentTimeMillis()
                        consecutiveSocketTimeouts = 0  // 🆕 收到心跳，重置Socket超时计数
                        // 🆕 收到心跳，清除超时标记（表示连接正常）
                        // 注意：心跳包不会频繁调用回调，由startCleanupTask统一管理超时状态
                        if (heartbeatCount == 1L || heartbeatCount % 20 == 0L) {
                            Log.d(TAG, "💓 心跳响应 #$heartbeatCount")
                        }
                        continue
                    }
                    
                    // 🆕 验证数据包大小（防止读取错误或数据损坏）
                    if (packetSize < 0 || packetSize > MAX_PACKET_SIZE) {
                        Log.w(TAG, "⚠️ 数据包大小异常: $packetSize bytes (范围: 0-${MAX_PACKET_SIZE})，可能是读取错误，重连")
                        failCount++
                        closeSocket()
                        delay(reconnectDelay)
                        continue
                    }
                    
                    // 🆕 额外检查：如果数据包大小异常大，可能是读取错误
                    if (packetSize > 1024 * 1024) {  // 超过1MB明显异常
                        Log.e(TAG, "❌ 数据包大小异常大: $packetSize bytes，可能是读取错误，关闭连接")
                        failCount++
                        closeSocket()
                        delay(reconnectDelay)
                        continue
                    }
                    
                    // 读取完整数据包（带超时保护）
                    val packetBytes = ByteArray(packetSize)
                    var bytesRead = 0
                    val readStartTime = System.currentTimeMillis()
                    while (bytesRead < packetSize) {
                        // 🆕 检查读取超时（防止长时间阻塞）
                        val elapsed = System.currentTimeMillis() - readStartTime
                        if (elapsed > SOCKET_TIMEOUT_MS * 2) {  // 给读取操作2倍超时时间
                            throw SocketTimeoutException("数据包读取超时: ${elapsed}ms")
                        }
                        val read = inputStream.read(packetBytes, bytesRead, packetSize - bytesRead)
                        if (read == -1) {
                            throw IOException("连接已关闭")
                        }
                        bytesRead += read
                    }
                    
                    // 解析数据包
                    val parseResult = parsePacket(packetBytes)
                    when (parseResult) {
                        is ParseResult.Success -> {
                            // 成功解析数据
                        successCount++
                        val now = System.currentTimeMillis()
                        lastDataTime = now
                            consecutiveFailures = 0  // 🆕 重置连续失败计数
                            consecutiveSocketTimeouts = 0  // 🆕 重置Socket超时计数
                            
                            val data = parseResult.data
                            
                            // 🆕 检查数据包接收间隔：如果间隔超过200ms（正常应该是50ms左右），说明数据不实时
                            // 注意：不使用Python时间戳，因为系统时间可能不同步
                            val packetInterval = if (lastPacketReceiveTime > 0) {
                                now - lastPacketReceiveTime
                            } else {
                                0L
                            }
                            
                            if (packetInterval > 200L) {
                                // 数据包间隔过大，可能是数据不实时，但不立即重连（可能是Python端短暂延迟）
                                if (packetInterval > 1000L) {
                                    // 间隔超过1秒，才认为是严重问题，记录警告但不重连（由数据超时机制处理）
                                    Log.w(TAG, "⚠️ 数据包接收间隔过大: ${packetInterval}ms (序号: ${data.sequence})")
                                }
                            }
                            lastPacketReceiveTime = now
                            
                            // 🆕 首次成功解析时打印日志
                            if (successCount == 1L) {
                                Log.i(TAG, "🎉 首次成功解析数据: ${serverIP}:${TCP_PORT}")
                            }
                            
                            // 🆕 每50个数据包打印一次日志，显示接收频率
                            if (successCount % 50 == 0L) {
                                val timeSinceLastLog = if (successCount == 50L) {
                                    "首次"
                    } else {
                                    val elapsed = now - lastLogTime
                                    "${elapsed}ms"
                                }
                                val avgInterval = if (successCount > 50) {
                                    val totalTime = now - (lastLogTime - (successCount - 50) * 50) // 估算总时间
                                    "${totalTime / successCount}ms"
                                } else {
                                    "计算中"
                                }
                                lastLogTime = now
                                val intervalStr = if (packetInterval > 0) {
                                    "${packetInterval}ms"
                                } else {
                                    "首次"
                                }
                                Log.i(TAG, "✅ 数据 #$successCount (上次间隔: $timeSinceLastLog, 平均: $avgInterval/包, 包间隔: $intervalStr)")
                            }
                            
                            // 🆕 立即更新数据，确保实时性（在主线程更新UI）
                            onDataReceived(data)
                            // 🆕 注意：不在这里调用 onDataTimeoutChanged，由 startCleanupTask 统一管理超时状态
                        }
                        is ParseResult.EmptyData -> {
                            // 🆕 空数据包（心跳包）：更新lastDataTime但不增加失败计数
                            lastDataTime = System.currentTimeMillis()
                            consecutiveFailures = 0  // 空数据包表示连接正常，重置失败计数
                            consecutiveSocketTimeouts = 0  // 🆕 收到空数据包，重置Socket超时计数
                            // 🆕 清除超时标记（空数据包表示连接正常，由startCleanupTask统一管理）
                            // 不调用 onDataReceived，保持UI显示上次数据
                            // 🆕 每100个空数据包打印一次日志，用于调试
                            emptyDataCount++
                            if (emptyDataCount % 100 == 0L) {  // 🆕 修复：改为 == 0L
                                Log.d(TAG, "📦 空数据包（心跳包） #$emptyDataCount")
                            }
                        }
                        is ParseResult.ParseError -> {
                            // 真正的解析失败
                        failCount++
                        consecutiveFailures++
                            
                            // 🆕 每10次失败打印一次详细日志
                            if (failCount % 10 == 1L || consecutiveFailures == MAX_CONSECUTIVE_FAILURES) {
                                Log.w(TAG, "❌ 解析失败 #$failCount (连续失败: $consecutiveFailures), 数据包大小: $packetSize, 错误: ${parseResult.error}")
                        }
                        
                            // 🆕 连续解析失败达到阈值时才重连（提高阈值，避免频繁重连）
                        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                                Log.w(TAG, "⚠️ 连续解析失败${consecutiveFailures}次，重新连接...")
                            closeSocket()
                            delay(RECONNECT_DELAY_MS)
                            continue
                            }
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    // 🆕 优化：Socket超时不应该立即关闭连接，允许连续多次超时
                    // Python端可能出现短暂的数据中断（4-8秒），这是正常的
                    consecutiveSocketTimeouts++
                    
                    if (!isTcpConnected.get()) {  // 🆕 使用AtomicBoolean检查连接状态
                        closeSocket()
                    } else {
                        // 连接正常但读取超时
                        val now = System.currentTimeMillis()
                        val dataTimeout = lastDataTime > 0 && (now - lastDataTime) > DATA_TIMEOUT_MS
                        
                        // 🆕 如果数据超时，标记为异常（由startCleanupTask统一管理）
                        if (dataTimeout) {
                            // 数据超时状态由startCleanupTask管理，这里不重复调用
                        }
                        
                        // 🆕 连续多次Socket超时（超过5次，即15秒）且数据超时，才关闭连接
                        // 这样可以容忍Python端的短暂数据中断
                        if (consecutiveSocketTimeouts >= 5 && dataTimeout) {
                            Log.w(TAG, "⚠️ 连续${consecutiveSocketTimeouts}次Socket超时且数据超时，关闭连接")
                            closeSocket()
                            consecutiveSocketTimeouts = 0
                            delay(reconnectDelay)
                            continue
                        } else if (consecutiveSocketTimeouts % 3 == 0) {
                            // 每3次超时打印一次日志，避免日志过多
                            Log.d(TAG, "⏱️ Socket超时 #$consecutiveSocketTimeouts (数据超时: $dataTimeout)")
                        }
                        
                        // 继续循环，尝试读取下一个数据包
                        continue
                    }
                } catch (e: Exception) {
                    if (_isRunning) {
                        Log.w(TAG, "❌ TCP数据接收异常: ${e.message}")
                        closeSocket()
                        delay(reconnectDelay)  // 🆕 使用当前的重连延迟（指数退避）
                    }
                }
            }
            Log.i(TAG, "TCP数据接收任务已停止 - 总计: $packetCount, 成功: $successCount, 失败: $failCount, 心跳: $heartbeatCount")
        }
    }

    /**
     * 启动自动清理任务
     */
    private fun startCleanupTask() {
        cleanupJob = networkScope?.launch {
            while (_isRunning) {
                delay(CLEANUP_INTERVAL_MS)
                
                val now = System.currentTimeMillis()
                val isConnected = isTcpConnected.get()  // 🆕 线程安全读取
                
                // 🆕 计算当前是否超时
                // 注意：如果连接刚建立（lastDataTime为0），不立即视为超时，等待第一个数据包
                val isCurrentlyTimeout = if (lastDataTime > 0) {
                    (now - lastDataTime) > DATA_TIMEOUT_MS
                } else if (isConnected) {
                    // 连接已建立但还没有收到任何数据，等待第一个数据包（给5秒缓冲时间）
                    false  // 不立即视为超时，等待数据
                } else {
                    true  // 未连接且没有数据时间戳，视为超时
                }
                
                if (isCurrentlyTimeout) {
                    // 数据超时
                    if (isConnected) {
                        // 连接但数据超时 = 异常状态
                        // 🆕 只在状态变化时调用回调
                        if (!lastTimeoutState) {
                            Log.w(TAG, "⚠️ 数据超时（连接但无数据），标记为异常")
                            onDataTimeoutChanged?.invoke(true)
                            lastTimeoutState = true
                        }
                    } else {
                        // 未连接 = 无连接状态
                        if (lastTimeoutState) {
                        Log.w(TAG, "🧹 数据超时，清理")
                    lastDataTime = 0
                    onDataReceived(null)
                            onDataTimeoutChanged?.invoke(false)  // 清除超时标记（因为已断开连接）
                            lastTimeoutState = false
                        }
                    }
                } else {
                    // 有数据且未超时，清除超时标记（只在状态变化时调用）
                    if (lastTimeoutState) {
                        onDataTimeoutChanged?.invoke(false)
                        lastTimeoutState = false
                    }
                }
            }
        }
    }

    /**
     * 解析数据包
     * TCP数据包格式: [4字节长度][JSON数据]
     * 注意：TCP外层已经读取了长度，这里接收的是完整的JSON数据
     * 
     * @param packetBytes JSON数据字节数组
     * @return 解析结果：成功、空数据包（心跳包）或解析错误
     */
    private fun parsePacket(packetBytes: ByteArray): ParseResult {
        if (packetBytes.isEmpty()) {
            return ParseResult.ParseError("数据包为空")
        }

        try {
            // 解析JSON
            val jsonString = String(packetBytes, Charsets.UTF_8)
            
            // 🆕 检查是否是空字符串或无效JSON
            if (jsonString.isBlank()) {
                return ParseResult.ParseError("JSON字符串为空")
            }
            
            val json = JSONObject(jsonString)
            
            return parseJsonData(json)
        } catch (e: org.json.JSONException) {
            // 🆕 JSON解析错误，打印前100个字符用于调试
            val preview = String(packetBytes, 0, kotlin.math.min(100, packetBytes.size), Charsets.UTF_8)
            val errorMsg = "JSON解析失败: ${e.message}, 预览: $preview"
            Log.w(TAG, errorMsg)
            return ParseResult.ParseError(errorMsg)
        } catch (e: Exception) {
            val errorMsg = "解析数据包失败: ${e.message}"
            Log.w(TAG, errorMsg, e)
            return ParseResult.ParseError(errorMsg)
        }
    }

    /**
     * 解析JSON数据
     */
    private fun parseJsonData(json: JSONObject): ParseResult {
        try {
            val dataObj = json.optJSONObject("data")
            if (dataObj == null) {
                return ParseResult.ParseError("JSON中缺少 'data' 字段")
            }
            
            // 🆕 检查是否为空数据包（Python端在无数据时发送空对象，作为心跳包）
            if (dataObj.length() == 0) {
                // 空数据包（心跳包）：返回EmptyData，表示连接正常但无新数据
                return ParseResult.EmptyData
            }
            
            val sequence = json.optLong("sequence", 0)
            val timestamp = json.optDouble("timestamp", 0.0)
            
            // 🆕 解析tbtDist：优先从JSON中获取，如果没有则使用默认值0（将在onDataReceived回调中从carrotManFields更新）
            val tbtDist = dataObj.optInt("tbtDist", 0)
            
            val data = XiaogeVehicleData(
                sequence = sequence,
                timestamp = timestamp,
                ip = serverIP,  // 使用当前连接的服务器IP
                receiveTime = System.currentTimeMillis(),
                carState = parseCarState(dataObj.optJSONObject("carState")),
                modelV2 = parseModelV2(dataObj.optJSONObject("modelV2")),
                systemState = parseSystemState(dataObj.optJSONObject("systemState")),
                overtakeStatus = parseOvertakeStatus(dataObj.optJSONObject("overtakeStatus")),
                tbtDist = tbtDist  // 🆕 从JSON解析，如果没有则使用0（将在回调中更新）
            )
            
            return ParseResult.Success(data)
        } catch (e: Exception) {
            val errorMsg = "解析JSON失败: ${e.message}"
            Log.w(TAG, errorMsg)
            return ParseResult.ParseError(errorMsg)
        }
    }

    private fun parseCarState(json: JSONObject?): CarStateData? {
        if (json == null) return null
        return CarStateData(
            vEgo = json.optDouble("vEgo", 0.0).toFloat(),
            steeringAngleDeg = json.optDouble("steeringAngleDeg", 0.0).toFloat(),
            leftLatDist = json.optDouble("leftLatDist", 0.0).toFloat(),
            leftBlindspot = json.optBoolean("leftBlindspot", false),
            rightBlindspot = json.optBoolean("rightBlindspot", false)
        )
    }

    /**
     * 解析模型数据 (modelV2)
     * ✅ 已更新：与修复后的 Python 端 (xiaoge_data.py) 完全匹配
     * Python 端修复：
     * - modelVEgo: 优先使用 carState.vEgo（来自CAN总线，更准确）
     * - laneWidth: 使用插值方法在指定距离处计算，而不是使用固定索引
     * - 所有字段都经过验证和优化
     */
    private fun parseModelV2(json: JSONObject?): ModelV2Data? {
        if (json == null) return null
        
        val lead0Obj = json.optJSONObject("lead0")
        val leadLeftObj = json.optJSONObject("leadLeft")
        val leadRightObj = json.optJSONObject("leadRight")
        val metaObj = json.optJSONObject("meta")
        val curvatureObj = json.optJSONObject("curvature")
        val laneLineProbsArray = json.optJSONArray("laneLineProbs")
        
        // 解析车道线置信度数组 [左车道线置信度, 右车道线置信度]
        val laneLineProbs = mutableListOf<Float>()
        if (laneLineProbsArray != null) {
            for (i in 0 until laneLineProbsArray.length()) {
                laneLineProbs.add(laneLineProbsArray.optDouble(i, 0.0).toFloat())
            }
        }
        
        return ModelV2Data(
            lead0 = parseLeadData(lead0Obj),  // 第一前车
            leadLeft = parseSideLeadDataExtended(leadLeftObj),  // 左侧车辆（纯视觉方案）
            leadRight = parseSideLeadDataExtended(leadRightObj), // 右侧车辆（纯视觉方案）
            laneLineProbs = laneLineProbs,  // [左车道线置信度, 右车道线置信度]
            meta = parseMetaData(metaObj),  // 车道宽度和变道状态
            curvature = parseCurvatureData(curvatureObj)  // 曲率信息（用于判断弯道）
        )
    }

    private fun parseLeadData(json: JSONObject?): LeadData? {
        if (json == null) return null
        // 简化版：只保留超车决策必需的字段
        return LeadData(
            x = json.optDouble("x", 0.0).toFloat(),  // 相对于相机的距离 (m)
            y = json.optDouble("y", 0.0).toFloat(),  // 横向位置（用于返回原车道判断）
            v = json.optDouble("v", 0.0).toFloat(),  // 速度 (m/s)
            prob = json.optDouble("prob", 0.0).toFloat()  // 置信度
        )
    }

    private fun parseMetaData(json: JSONObject?): MetaData? {
        if (json == null) return null
        return MetaData(
            distanceToRoadEdgeLeft = json.optDouble("distanceToRoadEdgeLeft", 0.0).toFloat(),
            distanceToRoadEdgeRight = json.optDouble("distanceToRoadEdgeRight", 0.0).toFloat()
        )
    }

    /**
     * 解析曲率数据
     * ✅ 已更新：与修复后的 Python 端 (xiaoge_data.py) 完全匹配
     * Python 端修复：改进空列表检查逻辑，使代码更清晰
     */
    private fun parseCurvatureData(json: JSONObject?): CurvatureData? {
        if (json == null) return null
        return CurvatureData(
            maxOrientationRate = json.optDouble("maxOrientationRate", 0.0).toFloat()  // 最大方向变化率 (rad/s)，方向可从符号推导（>0=左转，<0=右转）
        )
    }


    /**
     * 解析扩展的侧方车辆数据（纯视觉方案）
     * 简化版：只保留超车决策必需的字段
     */
    private fun parseSideLeadDataExtended(json: JSONObject?): SideLeadDataExtended? {
        if (json == null) return null
        return SideLeadDataExtended(
            dRel = json.optDouble("dRel", 0.0).toFloat(), // 相对于雷达的距离
            vRel = json.optDouble("vRel", 0.0).toFloat(), // 相对速度 (m/s)
            status = json.optBoolean("status", false)  // 是否有车辆
        )
    }


    private fun parseSystemState(json: JSONObject?): SystemStateData? {
        if (json == null) return null
        return SystemStateData(
            enabled = json.optBoolean("enabled", false),
            active = json.optBoolean("active", false)
        )
    }

    /**
     * 🆕 解析超车状态数据
     * 从 JSON 中解析超车状态信息，用于在 UI 中显示
     * 注意：此数据由 Android 端的 AutoOvertakeManager 生成，Python 端不发送此数据
     * 如果 Python 端未来发送此数据，此函数可以正确解析
     */
    private fun parseOvertakeStatus(json: JSONObject?): OvertakeStatusData? {
        if (json == null) return null
        
        val lastDirectionStr = json.optString("lastDirection", "")
        val blockingReasonStr = json.optString("blockingReason", "")
        
        return OvertakeStatusData(
            statusText = json.optString("statusText", "监控中"),
            canOvertake = json.optBoolean("canOvertake", false),
            cooldownRemaining = if (json.has("cooldownRemaining")) {
                json.optLong("cooldownRemaining", 0)
            } else {
                null
            },
            lastDirection = lastDirectionStr.takeIf { it.isNotEmpty() },
            blockingReason = blockingReasonStr.takeIf { it.isNotEmpty() },
            currentLane = json.optInt("currentLane", 0),
            totalLanes = json.optInt("totalLanes", 0),
            laneReminder = json.optString("laneReminder", "").takeIf { it.isNotEmpty() }
        )
    }
}

/**
 * 小鸽车辆数据结构
 */
data class XiaogeVehicleData(
    val sequence: Long,
    val timestamp: Double,  // Python端时间戳（秒）
    val ip: String?,        // 设备IP地址
    val receiveTime: Long = 0L,  // Android端接收时间（毫秒），用于计算数据年龄
    val carState: CarStateData?,
    val modelV2: ModelV2Data?,
    val systemState: SystemStateData?,
    val overtakeStatus: OvertakeStatusData? = null,  // 超车状态（可选，由 AutoOvertakeManager 更新）
    val tbtDist: Int = 0  // 🆕 转弯距离（米），来自CarrotManFields.nTBTDist
)

/**
 * 超车状态数据
 * 用于在 UI 中显示超车系统的实时状态
 * 注意：此数据需要在 openpilot 端的数据发送器中包含超车状态信息
 */
data class OvertakeStatusData(
    val statusText: String,           // 状态文本描述："监控中"/"可超车"/"冷却中"
    val canOvertake: Boolean,         // 是否可以超车
    val cooldownRemaining: Long?,     // 剩余冷却时间（毫秒），可选
    val lastDirection: String?,       // 上次超车方向（LEFT/RIGHT），可选
    val blockingReason: String? = null, // 🆕 阻止超车的原因（可选）
    val currentLane: Int = 0,         // 🆕 当前车道 (1-based, 从左往右)
    val totalLanes: Int = 0,          // 🆕 总车道数
    val laneReminder: String? = null  // 🆕 车道提醒文本
)

data class CarStateData(
    val vEgo: Float,              // 本车速度 (m/s)
    val steeringAngleDeg: Float,  // 方向盘角度
    val leftLatDist: Float,       // 到左车道线距离（返回原车道）
    val leftBlindspot: Boolean,   // 左盲区
    val rightBlindspot: Boolean   // 右盲区
)

/**
 * 模型数据 (modelV2)
 * 简化版：只保留超车决策必需的字段
 */
data class ModelV2Data(
    val lead0: LeadData?,         // 第一前车
    val leadLeft: SideLeadDataExtended?,  // 左侧车辆（纯视觉方案）
    val leadRight: SideLeadDataExtended?, // 右侧车辆（纯视觉方案）
    val laneLineProbs: List<Float>, // [左车道线置信度, 右车道线置信度]
    val meta: MetaData?,          // 车道宽度和变道状态
    val curvature: CurvatureData? // 曲率信息（用于判断弯道）
)

/**
 * 前车数据（lead0）
 * 简化版：只保留超车决策必需的字段
 */
data class LeadData(
    val x: Float,    // 距离 (m) - 相对于相机的距离
    val y: Float,    // 横向位置（用于返回原车道判断）
    val v: Float,    // 速度 (m/s)
    val prob: Float  // 置信度
)

data class MetaData(
    val distanceToRoadEdgeLeft: Float = 0.0f,
    val distanceToRoadEdgeRight: Float = 0.0f
)

data class CurvatureData(
    val maxOrientationRate: Float  // 曲率 (rad/s)，方向可从符号推导（>0=左转，<0=右转）
)

/**
 * 扩展的侧方车辆数据（纯视觉方案）
 * 简化版：只保留超车决策必需的字段
 */
data class SideLeadDataExtended(
    val dRel: Float,           // 相对于雷达的距离
    val vRel: Float,           // 相对速度 (m/s)
    val status: Boolean        // 是否有车辆
)

data class SystemStateData(
    val enabled: Boolean,
    val active: Boolean
)

