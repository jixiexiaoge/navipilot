package com.example.carrotamap.ui.discovery

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Openpilot 连接管理器
 * 整合设备发现和自动重连逻辑
 */
class OpenpilotConnectionManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "OpenpilotConnectionManager"

        // 重试配置
        private const val RETRY_DELAY_MS = 2000L
        private const val REDISCOVER_DELAY_MS = 15000L
        private const val MAX_RETRIES = 3
    }

    // 设备发现
    private val deviceDiscovery = CommaDeviceDiscovery(context)

    // 状态
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // 连接参数
    private var currentHost: String? = null

    // 重试计数
    private var retryCount = 0

    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 回调
    var onConnectionStateChanged: ((ConnectionState) -> Unit)? = null

    /**
     * 启动连接流程
     */
    fun start(initialHost: String? = null) {
        scope.launch {
            if (initialHost.isNullOrEmpty()) {
                startDiscovery()
            } else {
                currentHost = initialHost
                connect(initialHost)
            }
        }
    }

    /**
     * 启动 mDNS 设备发现
     */
    private fun startDiscovery() {
        scope.launch {
            try {
                deviceDiscovery.discoverDevices().collect { device ->
                    if (_connectionState.value != ConnectionState.CONNECTED) {
                        Log.d(TAG, "发现设备: ${device.displayName} at ${device.host}")
                        currentHost = device.host
                        connect(device.host)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "设备发现失败: ${e.message}")
            }
        }
    }

    /**
     * 连接到设备
     */
    private fun connect(host: String) {
        scope.launch {
            _connectionState.value = ConnectionState.CONNECTING
            onConnectionStateChanged?.invoke(ConnectionState.CONNECTING)

            try {
                currentHost = host
                _connectionState.value = ConnectionState.CONNECTED
                onConnectionStateChanged?.invoke(ConnectionState.CONNECTED)

                retryCount = 0

                Log.d(TAG, "已连接到 $host")
            } catch (e: Exception) {
                Log.e(TAG, "连接失败: ${e.message}")
                scheduleReconnect()
            }
        }
    }

    /**
     * 重新连接
     */
    fun reconnect() {
        Log.d(TAG, "重新连接...")
        disconnect()
        retryCount = 0

        val host = currentHost
        if (host != null) {
            scope.launch {
                delay(100)
                connect(host)
            }
        } else {
            startDiscovery()
        }
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        _connectionState.value = ConnectionState.DISCONNECTED
        onConnectionStateChanged?.invoke(ConnectionState.DISCONNECTED)
    }

    /**
     * 暂停连接
     */
    fun pause() {
        disconnect()
        Log.d(TAG, "连接已暂停")
    }

    /**
     * 恢复连接
     */
    fun resume() {
        reconnect()
    }

    /**
     * 调度重连（指数退避）
     */
    private fun scheduleReconnect() {
        retryCount++

        _connectionState.value = ConnectionState.RECONNECTING
        onConnectionStateChanged?.invoke(ConnectionState.RECONNECTING)

        if (retryCount > MAX_RETRIES) {
            Log.d(TAG, "重试次数超过限制，等待 ${REDISCOVER_DELAY_MS}ms 后重新发现")
            currentHost = null
            scope.launch {
                delay(REDISCOVER_DELAY_MS)
                retryCount = 0
                startDiscovery()
            }
        } else {
            val delay = RETRY_DELAY_MS * retryCount
            Log.d(TAG, "重试 $retryCount/$MAX_RETRIES in ${delay}ms")
            scope.launch {
                delay(delay)
                currentHost?.let { connect(it) }
            }
        }
    }

    /**
     * 获取设备发现服务
     */
    fun getDeviceDiscovery(): CommaDeviceDiscovery = deviceDiscovery

    /**
     * 释放资源
     */
    fun dispose() {
        disconnect()
        scope.cancel()
    }
}

/**
 * 连接状态
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}