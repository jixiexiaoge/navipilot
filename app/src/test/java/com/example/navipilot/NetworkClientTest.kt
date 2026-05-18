package com.example.navipilot

import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject

/**
 * CarrotMan网络客户端功能测试
 * 测试网络通信相关的核心功能
 */
class NetworkClientTest {
    
    @Test
    fun deviceInfo_creation_isCorrect() {
        val deviceInfo = CarrotManNetworkClient.DeviceInfo(
            ip = "192.168.1.100",
            port = 7706,
            version = "0.9.4"
        )
        
        assertEquals("192.168.1.100", deviceInfo.ip)
        assertEquals(7706, deviceInfo.port)
        assertEquals("0.9.4", deviceInfo.version)
        assertTrue("新创建的设备应该是活跃的", deviceInfo.isActive())
    }
    
    @Test
    fun deviceInfo_toString_formatCorrect() {
        val deviceInfo = CarrotManNetworkClient.DeviceInfo(
            ip = "192.168.1.100",
            port = 7706,
            version = "0.9.4"
        )
        
        val expected = "192.168.1.100:7706 (v0.9.4) [?]"
        assertEquals(expected, deviceInfo.toString())
    }
    
    @Test
    fun carrotManFields_jsonConversion_isCorrect() {
        val fields = CarrotManFields(
            nRoadLimitSpeed = 60,
            active_carrot = 1,
            szPosRoadName = "测试道路",
            goalPosX = 116.397128,
            goalPosY = 39.916527,
            szGoalName = "测试目的地"
        )
        
        // 验证关键字段
        assertEquals(60, fields.nRoadLimitSpeed)
        assertEquals(1, fields.active_carrot)
        assertEquals("测试道路", fields.szPosRoadName)
        assertEquals(116.397128, fields.goalPosX, 0.000001)
        assertEquals(39.916527, fields.goalPosY, 0.000001)
        assertEquals("测试目的地", fields.szGoalName)
    }
    
    @Test
    fun appConstants_networkPorts_areValid() {
        // 验证网络端口配置
        assertTrue("广播端口应该在有效范围内", 
                   AppConstants.Network.BROADCAST_PORT > 1024 && 
                   AppConstants.Network.BROADCAST_PORT < 65536)
        
        assertTrue("数据端口应该在有效范围内", 
                   AppConstants.Network.MAIN_DATA_PORT > 1024 && 
                   AppConstants.Network.MAIN_DATA_PORT < 65536)
        
        assertTrue("命令端口应该在有效范围内", 
                   AppConstants.Network.COMMAND_PORT > 1024 && 
                   AppConstants.Network.COMMAND_PORT < 65536)
    }
    
    @Test
    fun appConstants_timeIntervals_areReasonable() {
        // 验证时间间隔配置
        assertTrue("设备发现间隔应该合理", 
                   AppConstants.Network.DISCOVER_CHECK_INTERVAL >= 1000L)
        
        assertTrue("数据发送间隔应该合理", 
                   AppConstants.Network.DATA_SEND_INTERVAL >= 50L)
        
        assertTrue("Socket超时应该合理", 
                   AppConstants.Network.SOCKET_TIMEOUT >= 1000)
    }
    
    @Test
    fun broadcastData_creation_isCorrect() {
        val broadcastData = BroadcastData(
            keyType = 10001,
            dataType = "引导信息",
            timestamp = System.currentTimeMillis(),
            rawExtras = mapOf("EXTRA_STATE" to "1", "ROAD_NAME" to "测试路"),
            parsedContent = "测试解析内容"
        )
        
        assertEquals(10001, broadcastData.keyType)
        assertEquals("引导信息", broadcastData.dataType)
        assertEquals("测试解析内容", broadcastData.parsedContent)
        assertTrue("时间戳应该是最近的", 
                   System.currentTimeMillis() - broadcastData.timestamp < 1000)
    }
}