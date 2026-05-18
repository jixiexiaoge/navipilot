package com.example.carrotamap.utils

import com.example.carrotamap.CarrotManFields
import kotlin.math.*

/**
 * 网络性能优化工具类
 * 
 * 提供网络数据发送的优化策略：
 * 1. 差分发送 - 只在数据有显著变化时发送
 * 2. 智能节流 - 避免过于频繁的发送
 * 3. 数据压缩 - 减小传输数据量
 */
object NetworkPerformanceUtils {
    
    /**
     * 检查 CarrotManFields 是否有显著变化
     * 
     * @param old 旧数据（可为 null）
     * @param new 新数据
     * @return true 表示有显著变化，需要发送
     */
    fun hasSignificantChange(old: CarrotManFields?, new: CarrotManFields): Boolean {
        // 首次发送
        if (old == null) return true
        
        // 关键导航信息变化 - 必须立即发送
        if (old.nRoadLimitSpeed != new.nRoadLimitSpeed) return true
        if (old.nTBTDist != new.nTBTDist) return true
        if (old.nTBTTurnType != new.nTBTTurnType) return true
        
        // SDI摄像头信息变化
        if (old.nSdiType != new.nSdiType) return true
        if (old.nSdiDist != new.nSdiDist) return true
        if (old.nSdiSpeedLimit != new.nSdiSpeedLimit) return true
        
        // 交通灯状态变化
        if (old.traffic_state != new.traffic_state) return true
        
        // 命令变化 - 立即发送
        if (old.carrotCmd != new.carrotCmd) return true
        if (old.carrotArg != new.carrotArg) return true
        
        // GPS 坐标变化超过阈值（10米）
        val distance = haversineDistance(
            old.latitude, old.longitude,
            new.latitude, new.longitude
        )
        if (distance > 10.0) return true
        
        // 速度变化超过 5 km/h
        if (abs(old.gps_speed - new.gps_speed) > 5.0 / 3.6) return true
        
        // 导航状态变化
        if (old.isNavigating != new.isNavigating) return true
        
        // 无显著变化
        return false
    }
    
    /**
     * 计算两个GPS坐标之间的距离（米）
     * 使用 Haversine 公式
     */
    fun haversineDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        if (lat1 == 0.0 || lon1 == 0.0 || lat2 == 0.0 || lon2 == 0.0) {
            return Double.MAX_VALUE // 无效坐标，返回最大值强制发送
        }
        
        val R = 6371000.0 // 地球半径（米）
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }
    
    /**
     * 节流控制 - 检查是否应该发送数据
     * 
     * @param lastSendTime 上次发送时间（毫秒）
     * @param minInterval 最小发送间隔（毫秒）
     * @param isHighPriority 是否高优先级数据（命令等）
     * @return true 表示可以发送
     */
    fun shouldSendData(
        lastSendTime: Long,
        minInterval: Long = 200L,
        isHighPriority: Boolean = false
    ): Boolean {
        // 高优先级数据立即发送
        if (isHighPriority) return true
        
        // 检查时间间隔
        val currentTime = System.currentTimeMillis()
        return (currentTime - lastSendTime) >= minInterval
    }
    
    /**
     * 估算数据包大小（简化版）
     * 用于监控网络流量
     */
    fun estimateDataSize(fields: CarrotManFields): Int {
        // 粗略估计 JSON 数据大小
        // 实际大小会因为 JSON 序列化而有差异
        var size = 0
        
        // 字符串字段
        size += fields.szGoalName.length * 2
        size += fields.szTBTMainText.length * 2
        size += fields.szNearDirName.length * 2
        size += fields.szFarDirName.length * 2
        size += fields.szPosRoadName.length * 2
        size += fields.carrotCmd.length * 2
        size += fields.carrotArg.length * 2
        
        // 数值字段（约 200 字节）
        size += 200
        
        return size
    }
}

/**
 * 网络统计数据类
 * 用于监控网络性能
 */
data class NetworkStats(
    var totalPacketsSent: Int = 0,
    var totalBytesSent: Long = 0L,
    var packetsSkipped: Int = 0,  // 因无变化而跳过的包数
    var averagePacketSize: Int = 0,
    var lastSendTime: Long = 0L,
    var sendRate: Float = 0f  // 发送速率（包/秒）
) {
    /**
     * 更新统计信息
     */
    fun updateStats(packetSize: Int, wasSkipped: Boolean) {
        if (wasSkipped) {
            packetsSkipped++
        } else {
            totalPacketsSent++
            totalBytesSent += packetSize
            
            // 更新平均包大小
            if (totalPacketsSent > 0) {
                averagePacketSize = (totalBytesSent / totalPacketsSent).toInt()
            }
            
            // 更新发送速率
            val currentTime = System.currentTimeMillis()
            if (lastSendTime > 0) {
                val timeDiff = (currentTime - lastSendTime) / 1000f
                if (timeDiff > 0) {
                    sendRate = 1f / timeDiff
                }
            }
            lastSendTime = currentTime
        }
    }
    
    /**
     * 获取优化效果百分比
     */
    fun getOptimizationRate(): Float {
        val total = totalPacketsSent + packetsSkipped
        return if (total > 0) {
            (packetsSkipped.toFloat() / total) * 100
        } else 0f
    }
    
    /**
     * 格式化统计信息
     */
    fun formatStats(): String {
        return """
            已发送: $totalPacketsSent 包
            已跳过: $packetsSkipped 包 (优化 ${"%.1f".format(getOptimizationRate())}%)
            总流量: ${totalBytesSent / 1024} KB
            平均包大小: $averagePacketSize 字节
            发送速率: ${"%.1f".format(sendRate)} 包/秒
        """.trimIndent()
    }
}
