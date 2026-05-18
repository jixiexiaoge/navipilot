package com.example.navipilot

import android.Manifest

/**
 * 应用常量管理类
 * 按功能分组管理所有常量，便于维护和使用
 */
object AppConstants {
    
    // ===============================
    // 权限管理相关常量
    // ===============================
    object Permissions {
        // 核心权限 - 应用正常运行必需的权限
        val CORE_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,   // 精确位置权限 - 导航必需
            Manifest.permission.ACCESS_COARSE_LOCATION, // 粗略位置权限 - 导航必需
            Manifest.permission.INTERNET,               // 网络权限 - 通信必需
            Manifest.permission.ACCESS_NETWORK_STATE,   // 网络状态权限 - 通信必需
        )
        
        // 可选权限 - 增强功能的权限，用户可以选择拒绝
        val OPTIONAL_PERMISSIONS = arrayOf(
            Manifest.permission.WAKE_LOCK               // 唤醒锁权限 - 后台运行用
        ) + if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            arrayOf(
                Manifest.permission.FOREGROUND_SERVICE  // 前台服务权限 - Android 9+
            )
        } else {
            emptyArray()
        } + if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.POST_NOTIFICATIONS  // 通知权限 - Android 13+
            )
        } else {
            emptyArray()
        }
        
        // 所有权限的合集
        val ALL_PERMISSIONS = CORE_PERMISSIONS + OPTIONAL_PERMISSIONS
    }
    
    // ===============================
    // 高德地图广播相关常量
    // ===============================
    object AmapBroadcast {
        // 广播Action常量
        const val ACTION_AMAP_SEND = "AUTONAVI_STANDARD_BROADCAST_SEND"
        const val ACTION_AMAP_RECV = "AUTONAVI_STANDARD_BROADCAST_RECV"
        const val ACTION_AMAP_LEGACY = "AMAP_BROADCAST_SEND"
        const val ACTION_AUTONAVI = "AUTONAVI_BROADCAST_SEND"
        
        // 基础导航信息
        object Navigation {
            const val MAP_STATE = 10019          // 地图状态发送
            const val GUIDE_INFO = 10001         // 引导信息透出
            const val LOCATION_INFO = 10065      // 定位信息
            const val TURN_INFO = 10006          // 转向信息
            const val NAVIGATION_STATUS = 10042  // 导航状态
            const val ROUTE_INFO = 10003         // 路线信息
        }
        
        // 限速和摄像头信息
        object SpeedCamera {
            const val SPEED_LIMIT = 12110           // 限速信息
            const val CAMERA_INFO = 13005           // 电子眼信息
            const val CAMERA_INFO_V2 = 100001       // 电子眼信息新版
            const val SPEED_LIMIT_NEW = 13010       // 新版限速信息
            const val SDI_PLUS_INFO = 10007         // SDI Plus信息
        }
        
        // 车道信息
        object LaneInfo {
            const val DRIVE_WAY_INFO = 13012        // 车道线信息
        }
        
        // 地图和位置信息
        object MapLocation {
            const val FAVORITE_RESULT = 11003       // 收藏点结果
            const val ADMIN_AREA = 10067            // 行政区域信息
            const val NAVI_STATUS = 10069           // 导航状态变化
            const val TRAFFIC_INFO = 10070          // 路况信息
            const val NAVI_SITUATION = 13003        // 导航态势信息
            const val NEXT_INTERSECTION = 13004     // 下一路口信息
            const val SAPA_INFO = 13006             // 服务区信息
            const val TRAFFIC_LIGHT = 60073         // 红绿灯信息
            const val ROUTE_INFO_QUERY = 10056      // 路线信息查询结果
            const val GEOLOCATION_INFO = 12205      // 地理位置信息
            const val UNKNOWN_INFO_13011 = 13011    // 未知信息类型13011
        }
        
        // 导航控制
        object NavigationControl {
            const val SIMULATE_NAVIGATION = 10004       // 模拟导航
            const val ROUTE_PLANNING = 10001           // 规划路线
            const val START_NAVIGATION = 10002         // 开始导航
            const val STOP_NAVIGATION = 10005          // 停止导航
            const val HOME_COMPANY_NAVIGATION = 10040  // 导航到家/公司
        }

        // 导航状态常量
        object NavigationState {
            const val ARRIVE_DESTINATION = 39          // 到达目的地状态
        }
    }
    
    // ===============================
    // 智能限速相关常量
    // ===============================
    object SmartSpeedControl {
        const val SPEED_BUMP_SPEED = 30         // 减速带建议速度 km/h
        const val TURN_CONTROL_SPEED = 30       // 转向控制建议速度 km/h
        const val ROAD_SPEED_LIMIT_OFFSET = 0   // 路牌限速偏移 km/h
    }

    // ===============================
    // ATC自动转弯控制相关常量 (简化版本)
    // ===============================
    object AutoTurnControl {
        // ATC速度参数 (保留基本功能)
        const val ATC_TURN_SPEED = 30           // 转弯控制速度 km/h
        const val ATC_STOP_SPEED = 1            // 停车速度 km/h

        // 安全参数 (保留基本功能)
        const val SAFE_DECEL_RATE = 1.5         // 安全减速率 m/s²
        const val SAFE_TIME_BUFFER = 2.0        // 安全时间缓冲 s
    }
    
    // ===============================
    // 网络通信相关常量
    // ===============================
    object Network {
        // 端口配置
        const val BROADCAST_PORT = 7705         // 设备发现广播端口
        const val MAIN_DATA_PORT = 7706         // 主要数据通信端口
        const val COMMAND_PORT = 7710           // ZMQ命令控制端口
        
        // 时间参数配置
        const val DISCOVER_CHECK_INTERVAL = 2000L   // 设备发现检查间隔
        const val DATA_SEND_INTERVAL = 100L         // 数据发送间隔
        const val SOCKET_TIMEOUT = 5000             // Socket连接超时时间
        const val DEVICE_TIMEOUT = 10000L           // 设备离线判定超时
        
        // 数据配置
        const val MAX_PACKET_SIZE = 4096        // UDP数据包最大大小限制
    }
    
    // ===============================
    // 日志配置相关常量
    // ===============================
    object Logging {
        const val MAIN_ACTIVITY_TAG = "MainActivity"
        const val NETWORK_CLIENT_TAG = "CarrotManNetwork"
        const val BROADCAST_RECEIVER_TAG = "AmapAutoStaticReceiver"
        const val LOCATION_UPDATE_TAG = "LocationUpdate"
        
        // 日志级别控制
        const val ENABLE_VERBOSE_LOGS = false      // 详细日志开关
        const val ENABLE_DEBUG_LOGS = true         // 调试日志开关
    }
    
    // ===============================
    // 数据传输相关常量
    // ===============================
    object DataTransfer {
        const val SPEED_LIMIT_SEND_INTERVAL = 2000L    // 限速数据发送间隔
        const val DATA_SEND_INTERVAL = 200L             // 通用数据发送间隔，Python端能很好处理高频数据
        const val HEARTBEAT_INTERVAL = 1000L            // 心跳包发送间隔
        
        // 数据质量阈值
        const val DISTANCE_THRESHOLD = 100.0            // 距离变化阈值(米)
        const val SPEED_CHANGE_THRESHOLD = 5            // 速度变化阈值(km/h)
    }
    
    // ===============================
    // 地理坐标相关常量
    // ===============================
    object Geography {
        // 中国大陆坐标范围
        const val CHINA_MIN_LONGITUDE = 73.0
        const val CHINA_MAX_LONGITUDE = 135.0
        const val CHINA_MIN_LATITUDE = 18.0
        const val CHINA_MAX_LATITUDE = 54.0
        
        // 地球半径
        const val EARTH_RADIUS_METERS = 6371000.0
        
        // 坐标精度
        const val COORDINATE_PRECISION = 6
    }
} 