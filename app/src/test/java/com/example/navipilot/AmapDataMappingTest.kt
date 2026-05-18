package com.example.navipilot

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 高德地图数据映射测试
 * 测试 CAMERA_TYPE → nSdiType 映射的正确性
 * 
 * 基于 映射关系.md 文档中的映射规则
 */
class AmapDataMappingTest {
    
    @Test
    fun `测试未知道路设施映射 CAMERA_TYPE_0 到 nSdiType_100`() {
        // Given
        val cameraType = 0 // 未知道路设施 (高德官方定义)
        
        // When
        val nSdiType = AmapBroadcastHandlers.mapAmapCameraTypeToSdi(cameraType)
        
        // Then
        assertThat(nSdiType).isEqualTo(100) // 需更新0 (100+0)
    }
    
    @Test
    fun `测试区间测速启点映射 CAMERA_TYPE_8 到 nSdiType_2`() {
        // Given
        val cameraType = 8 // 区间限速启点
        
        // When
        val nSdiType = AmapBroadcastHandlers.mapAmapCameraTypeToSdi(cameraType)
        
        // Then
        assertThat(nSdiType).isEqualTo(2) // 区间测速开始
    }
    
    @Test
    fun `测试区间测速终点映射 CAMERA_TYPE_9 到 nSdiType_3`() {
        // Given
        val cameraType = 9 // 区间限速终点
        
        // When
        val nSdiType = AmapBroadcastHandlers.mapAmapCameraTypeToSdi(cameraType)
        
        // Then
        assertThat(nSdiType).isEqualTo(3) // 区间测速结束
    }
    
    @Test
    fun `测试闯红灯拍照映射 CAMERA_TYPE_2 到 nSdiType_6`() {
        // Given
        val cameraType = 2 // 闯红灯拍照
        
        // When
        val nSdiType = AmapBroadcastHandlers.mapAmapCameraTypeToSdi(cameraType)
        
        // Then
        assertThat(nSdiType).isEqualTo(6) // 闯红灯拍照
    }
    
    @Test
    fun `测试流动测速电子眼映射 CAMERA_TYPE_10 到 nSdiType_7`() {
        // Given
        val cameraType = 10 // 流动测速电子眼
        
        // When
        val nSdiType = AmapBroadcastHandlers.mapAmapCameraTypeToSdi(cameraType)
        
        // Then
        assertThat(nSdiType).isEqualTo(7) // 流动测速摄像头
    }
    
    @Test
    fun `测试部分直接映射类型`() {
        // 验证一些确实是直接映射的类型
        val directMappingTypes = listOf(17, 30, 32, 33, 34, 35, 45, 46, 51, 53, 55, 56, 57, 60, 61, 62, 63, 64, 65)
        
        directMappingTypes.forEach { cameraType ->
            // When
            val nSdiType = AmapBroadcastHandlers.mapAmapCameraTypeToSdi(cameraType)
            
            // Then - 直接映射时，值应该相等
            assertThat(nSdiType).isEqualTo(cameraType)
        }
    }
    
    @Test
    fun `测试测速拍照映射 CAMERA_TYPE_4 到 nSdiType_8`() {
        // Given
        val cameraType = 4 // 测速拍照 (高德官方定义)
        
        // When
        val nSdiType = AmapBroadcastHandlers.mapAmapCameraTypeToSdi(cameraType)
        
        // Then
        assertThat(nSdiType).isEqualTo(8) // 测速拍照
    }
    
    @Test
    fun `测试违章拍照映射 CAMERA_TYPE_5 到 nSdiType_5`() {
        // Given
        val cameraType = 5 // 违章拍照 (高德官方定义)
        
        // When
        val nSdiType = AmapBroadcastHandlers.mapAmapCameraTypeToSdi(cameraType)
        
        // Then
        assertThat(nSdiType).isEqualTo(5) // 路口压线拍照
    }
    
    @Test
    fun `测试未知类型映射到 100加编号`() {
        // Given - 未定义的类型
        val unknownType = 999
        
        // When
        val nSdiType = AmapBroadcastHandlers.mapAmapCameraTypeToSdi(unknownType)
        
        // Then - (100 + 999).coerceAtMost(999) = 999
        assertThat(nSdiType).isEqualTo(999)
    }
    
    @Test
    fun `测试负数类型映射到 100加编号`() {
        // Given
        val negativeType = -1
        
        // When
        val nSdiType = AmapBroadcastHandlers.mapAmapCameraTypeToSdi(negativeType)
        
        // Then - (100 + (-1)).coerceAtMost(999) = 99
        assertThat(nSdiType).isEqualTo(99)
    }
    
    @Test
    fun `测试所有需要映射的类型 0到13`() {
        // 验证需要映射的类型（0-13）
        val mappings = mapOf(
            0 to 100,  // 未知道路设施 → 需更新0 (100+0)
            1 to 14,   // 道路拍照 → 治安监控
            2 to 6,    // 闯红灯拍照 → 闯红灯拍照
            3 to 17,   // 违章拍照 → 违停拍照点
            4 to 8,    // 测速拍照 → 测速拍照
            5 to 5,    // 违章拍照 → 路口压线拍照
            6 to 8,    // 测速拍照 → 测速拍照
            7 to 7,    // 非机动车道拍照 → 流动测速摄像头
            8 to 2,    // 区间限速启点 → 区间测速开始
            9 to 3,    // 区间限速终点 → 区间测速结束
            10 to 7,   // 流动测速电子眼 → 流动测速摄像头
            11 to 26,  // ECT计费拍照 → ETC计费拍照
            12 to 19,  // 铁路道口 → 铁路道口
            13 to 48   // 左侧落石 → 落石危险路段
        )
        
        mappings.forEach { (cameraType, expectedSdiType) ->
            // When
            val actualSdiType = AmapBroadcastHandlers.mapAmapCameraTypeToSdi(cameraType)
            
            // Then
            assertThat(actualSdiType).isEqualTo(expectedSdiType)
        }
    }
}
