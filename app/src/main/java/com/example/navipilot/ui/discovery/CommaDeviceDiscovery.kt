package com.example.navipilot.ui.discovery

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Comma 设备发现服务
 * 使用 mDNS 协议发现局域网内的 comma 设备
 *
 * 移植自 opview lib/services/impl/mdns_discovery.dart
 *
 * 注意：完整实现需要 NSD (Network Service Discovery) API
 * 当前提供基础功能和手动输入 IP 的备用方案
 */
class CommaDeviceDiscovery(private val context: Context) {

    companion object {
        private const val TAG = "CommaDeviceDiscovery"
    }

    /**
     * 发现 comma 设备 Flow
     *
     * 注意：由于 Android NSD API 版本兼容性问题，
     * 当前版本使用手动输入 IP 方式
     */
    fun discoverDevices(): Flow<DiscoveredCommaDevice> = callbackFlow {
        // 由于 NSD API 兼容性问题，当前版本不支持自动发现
        // 用户需要在设置中手动输入 Comma 设备 IP 地址
        Log.w(TAG, "mDNS discovery 需要在设置中配置设备 IP")

        // 发送一个空的设备以保持 Flow 活跃
        trySend(
            DiscoveredCommaDevice(
                host = "",
                port = 5001,
                displayName = "请手动输入设备IP",
                serviceName = "manual"
            )
        )

        awaitClose {
            Log.d(TAG, "Discovery stopped")
        }
    }

    /**
     * 获取本机 IP 地址
     */
    fun getLocalIPAddress(): String? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ipAddress = wifiInfo.ipAddress

            if (ipAddress == 0) return null

            return String.format(
                "%d.%d.%d.%d",
                ipAddress and 0xff,
                ipAddress shr 8 and 0xff,
                ipAddress shr 16 and 0xff,
                ipAddress shr 24 and 0xff
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP: ${e.message}")
        }

        // 备用方法：遍历网络接口
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP from interfaces: ${e.message}")
        }

        return null
    }

    /**
     * 检查 IP 是否在同一子网
     */
    fun isOnSameSubnet(targetIP: String): Boolean {
        val localIP = getLocalIPAddress() ?: return true // 失败时默认允许

        try {
            val localParts = localIP.split(".").map { it.toInt() }
            val targetParts = targetIP.split(".").map { it.toInt() }

            // /16 子网匹配：前两个八位字节相同
            if (localParts.size >= 2 && targetParts.size >= 2) {
                return localParts[0] == targetParts[0] && localParts[1] == targetParts[1]
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error comparing subnets: ${e.message}")
        }

        return true // 失败时默认允许
    }
}

/**
 * 发现的 Comma 设备
 */
data class DiscoveredCommaDevice(
    val host: String,
    val port: Int,
    val displayName: String,
    val serviceName: String
) {
    /**
     * 获取完整地址
     */
    fun getAddress(): String = "$host:$port"

    /**
     * 检查是否为 comma 设备
     */
    fun isCommaDevice(): Boolean =
        displayName.contains("comma", ignoreCase = true) ||
        serviceName.contains("comma", ignoreCase = true)
}
