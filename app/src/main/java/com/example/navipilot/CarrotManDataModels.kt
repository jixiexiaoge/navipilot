package com.example.navipilot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * CarrotMan数据模型
 * 重构后的数据类，按功能分组，便于维护和理解
 * 整合了原DataClasses.kt中的所有数据类
 */

// 高德地图广播数据实体类
data class BroadcastData(
    val keyType: Int,                       // 广播类型键
    val dataType: String,                   // 数据类型描述
    val timestamp: Long,                    // 接收时间戳
    val rawExtras: Map<String, String>,     // 原始额外数据
    val parsedContent: String               // 解析后的内容
)

// CarrotMan数据包类 - 用于发送到Comma3设备
data class CarrotManData(
    // 导航信息
    val nTBTTurnType: Int,
    val nTBTDist: Int,
    val szTBTMainText: String,
    val szNearDirName: String,
    val szFarDirName: String,
    
    // 位置信息
    val vpPosPointLat: Double,
    val vpPosPointLon: Double,
    val vpPosPointLatNavi: Double,
    val vpPosPointLonNavi: Double,
    
    // 目的地信息
    val goalPosX: Double,
    val goalPosY: Double,
    val szGoalName: String,
    
    // 道路信息
    val roadcate: Int,
    val nRoadLimitSpeed: Int,
    
    // SDI信息
    val nSdiType: Int,
    val nSdiSpeedLimit: Int,
    val nSdiDist: Int,
    
    // 系统状态
    val active_carrot: Int,
    val isNavigating: Boolean,
    val carrotIndex: Long,
    val carcruiseSpeed: Float,
    
    // NOA增强信息
    val exitDirectionInfo: String = "",
    val exitNameInfo: String = "",
    val roundAboutNum: Int = -1,
    val roundAllNum: Int = -1,
    val segAssistantAction: Int = -1,
    val sapaName: String = "",
    val sapaDist: Int = -1,
    val nextNextAddIcon: String = "",
    
    // 时间戳
    val lastUpdateTime: Long
)

// OpenpPilot状态数据类 - 用于接收7705端口的JSON数据
data class OpenpilotStatusData(
    val carrot2: String = "",           // OpenpPilot版本信息
    val isOnroad: Boolean = false,      // 是否在道路上行驶
    val carrotRouteActive: Boolean = false, // 导航路线是否激活
    val ip: String = "",                // 设备IP地址
    val port: Int = 0,                  // 通信端口号
    val logCarrot: String = "",         // CarrotMan状态日志
    val vCruiseKph: Float = 0.0f,       // 巡航设定速度(km/h)
    val vEgoKph: Int = 0,               // 当前实际车速(km/h)
    val tbtDist: Int = 0,               // 到下个转弯距离(米)
    val sdiDist: Int = 0,               // 到速度限制点距离(米)
    val active: Boolean = false,        // 自动驾驶控制激活状态
    val xState: Int = 0,                // 纵向控制状态码
    val trafficState: Int = 0,          // 交通灯状态
    val carcruiseSpeed: Float = 0.0f,   // 车辆巡航速度(km/h) - 新增字段
    val lastUpdateTime: Long = System.currentTimeMillis() // 最后更新时间
)

/**
 * 车道信息数据类
 * 用于存储单个车道的信息
 * @param id 车道图标ID (对应资源名称后缀)
 * @param isRecommended 是否为推荐车道
 */
data class LaneInfo(
    val id: String,
    val isRecommended: Boolean,
    val driveWayNumber: Int = 0,
    val driveWayLaneExtended: String = "0",
    val trafficLaneExtendedNew: Int = 0,
    val trafficLaneType: Int = 0
)

/**
 * 腾讯导航 SDK 与车道检测等「尾部」字段单独成 data class，
 * 缩短 [CarrotManFields] 主构造 / [copy] 的 JVM 参数与 Kotlin 默认掩码数量，
 * 避免在真机上出现 `VerifyError`（ART 校验 copy$default / 巨型 <init> 失败）。
 */
data class CarrotManTencentSlice(
    var tCameraType: Int = -1,
    var tCameraDist: Int = 0,
    var tCameraSpeedLimit: Int = 0,
    var remainingTrafficLights: Int = 0,
    var passedDistance: Int = 0,
    var passedTime: Int = 0,
    var isOnMainRoad: Boolean = true,
    var canSwitchToMainRoad: Boolean = false,
    var canSwitchToSideRoad: Boolean = false,
    var tencentRoutePoints: List<Pair<Double, Double>> = emptyList(),
    var tencentRoutePointsReady: Boolean = false,
    var trafficJamAhead: Boolean = false,
    var trafficJamDistance: Int = 0,
    var trafficJamDuration: Int = 0,
    var trafficJamStatus: Int = 0,
    var tollEntranceName: String = "",
    var tollExitName: String = "",
    var tollFee: Int = 0,
    var gpsSignalStatus: Int = 0,
    var roadGrade: Int = -1,
    var roadKind: Int = -1,
    var tSdkIntersectionType: Int = -1,
    var leftLaneVehicle: Boolean = false,
    var rightLaneVehicle: Boolean = false,
    var leftLaneVehicleDist: Float = 0f,
    var rightLaneVehicleDist: Float = 0f,
    var laneDetectFps: Int = 0,
    var laneDetectSource: String = "",
    var leftLanePedestrian: Boolean = false,
    var rightLanePedestrian: Boolean = false,
    var leftLanePedestrianDist: Float = 0f,
    var rightLanePedestrianDist: Float = 0f,
    var leftLanePedestrianConfidence: Int = 0,
    var rightLanePedestrianConfidence: Int = 0,
)

// 简化的CarrotMan字段映射数据类 - 只保留手机App实际需要的核心字段
data class CarrotManFields(
    // === 发送给comma3的核心字段 ===

    // 基础通信参数
    var carrotIndex: Long = 0,                  // 数据包序号 (必需)
    var epochTime: Long = 0,                    // Unix时间戳
    var timezone: String = "Asia/Shanghai",     // 时区

    // GPS定位参数 (必需)
    var latitude: Double = 0.0,                 // GPS纬度 (WGS84)
    var longitude: Double = 0.0,                // GPS经度 (WGS84)
    var heading: Double = 0.0,                  // 方向角 (0-360度)
    var accuracy: Double = 0.0,                 // GPS精度 (米)
    var gps_speed: Double = 0.0,                // GPS速度 (m/s)

    // 目的地信息
    var goalPosX: Double = 0.0,                 // 目标经度
    var goalPosY: Double = 0.0,                 // 目标纬度
    var szGoalName: String = "",                // 目标名称

    // 道路信息
    var nRoadLimitSpeed: Int = 0,               // 道路限速 (km/h) - 初始化为空
    var roadcate: Int = 8,                      // 道路类别 (0=高速,8=地方)
    var roadType: Int = 8,                      // 高德地图道路类型 (0=高速,1=国道,2=省道等)
    var szPosRoadName: String = "",             // 当前道路名称

    // SDI速度检测信息
    var nSdiType: Int = -1,                     // SDI类型
    var nSdiSpeedLimit: Int = 0,                // 测速限速 (km/h)
    var nSdiDist: Int = 0,                      // 到测速点距离 (m)
    var nAmapCameraType: Int = -1,              // 高德原始CAMERA_TYPE
    var nSdiSection: Int = 0,                   // 区间测速ID
    var nSdiBlockType: Int = -1,                // 区间状态 (1=开始,2=中,3=结束)
    var nSdiBlockSpeed: Int = 0,                // 区间限速
    var nSdiBlockDist: Int = 0,                 // 区间距离
    var nSdiAverageSpeed: Int = -1,             // 区间平均速度 (新增)

    // SDI Plus扩展速度信息
    var nSdiPlusType: Int = -1,                 // Plus类型 (22=减速带)
    var nSdiPlusSpeedLimit: Int = 0,            // Plus限速
    var nSdiPlusDist: Int = 0,                  // Plus距离

    // TBT转弯导航
    var nTBTDist: Int = 0,                      // 转弯距离 (m)
    var nTBTTurnType: Int = -1,                 // 转弯类型
    var szTBTMainText: String = "",             // 主要指令文本
    var szNearDirName: String = "",             // 近处方向名
    var szFarDirName: String = "",              // 远处方向名
    var nTBTNextRoadWidth: Int = 0,             // 下一道路宽度 (车道数)
    var nTBTDistNext: Int = 0,                  // 下一转弯距离
    var nTBTTurnTypeNext: Int = -1,             // 下一转弯类型
    
    // 高德地图原始ICON信息
    var amapIcon: Int = -1,                     // 高德地图原始ICON值
    var amapIconNext: Int = -1,                 // 高德地图下一ICON值

    // 目的地信息
    var nGoPosDist: Int = 0,                    // 剩余距离 (m)
    var nGoPosTime: Int = 0,                    // 剩余时间 (s)

    // 导航位置 (兼容字段)
    var vpPosPointLat: Double = 0.0,            // 导航纬度
    var vpPosPointLon: Double = 0.0,            // 导航经度
    var nPosAngle: Double = 0.0,                // 导航方向角
    var nPosSpeed: Double = 0.0,                // 导航速度

    // 命令控制
    var carrotCmd: String = "",                 // 命令类型 (DETECT等)
    var carrotArg: String = "",                 // 命令参数

    // === 从comma3接收的显示字段 ===

    // 系统状态 (从7705端口广播接收)
    var carrot2: String = "",                   // 系统版本号
    var isOnroad: Boolean = false,              // 是否在路上行驶
    var carrotRouteActive: Boolean = false,     // 路线是否激活
    var ip: String = "",                        // comma3 IP地址
    var port: Int = 7706,                       // 数据接收端口
    var logCarrot: String = "",                 // 调试日志信息
    var vCruiseKph: Float = 0.0f,               // 巡航速度
    var vEgoKph: Int = 0,                       // 当前车速
    var tbtDist: Int = 0,                       // 转弯距离
    var sdiDist: Int = 0,                       // 速度检测距离
    var active: Boolean = false,                // 控制系统激活状态
    var xState: Int = 0,                        // 驾驶状态
    var trafficState: Int = 0,                  // 交通灯状态
    var carcruiseSpeed: Float = 0.0f,           // 车辆巡航速度(km/h) - 新增字段

    // === 内部处理字段 ===

    // 简化的内部状态
    var xSpdLimit: Int = 0,                     // 当前生效的速度限制
    var xSpdDist: Int = 0,                      // 速度限制距离
    var xSpdType: Int = -1,                     // 速度限制类型
    var xTurnInfo: Int = -1,                    // 转弯信息
    var xDistToTurn: Double = 0.0,              // 转弯距离

    // 基础倒计时
    var left_tbt_sec: Int = 0,                  // TBT倒计时
    var left_spd_sec: Int = 0,                  // 速度倒计时
    var left_sec: Int = 0,                      // 综合倒计时
    var carrot_left_sec: Int = 0,               // CarrotMan倒计时

    // 新增字段 - 用于AmapBroadcastHandlers
    var mapState: Int = -1,                     // 地图状态
    var extraState: Int = -1,                   // 额外状态
    var navStatus: Int = -1,                     // 导航状态
    var routeDistance: Int = 0,                 // 路线距离
    var routeTime: Int = 0,                     // 路线时间
    var routeType: Int = -1,                    // 路线类型
    var speedLimitType: Int = -1,               // 限速类型
    var trafficLevel: Int = -1,                 // 交通等级
    var trafficDescription: String = "",         // 交通描述
    var situationType: Int = -1,                // 态势类型
    var situationDistance: Int = 0,             // 态势距离
    var situationDescription: String = "",      // 态势描述
    var trafficLightState: Int = -1,            // 红绿灯状态
    var trafficLightDistance: Int = 0,          // 红绿灯距离
    var trafficLightCountdown: Int = 0,         // 红绿灯倒计时
    var adminArea: String = "",                 // 行政区
    var cityName: String = "",                  // 城市名称
    var districtName: String = "",               // 区县名称
    var laneCount: Int = 0,                     // 车道数
    var laneType: Int = -1,                     // 车道类型
    var laneDescription: String = "",            // 车道描述
    var vTurnSpeed: Double = 0.0,               // 转弯速度

    // 导航类型映射
    var navType: String = "invalid",            // 导航类型
    var navModifier: String = "",               // 导航修饰符

    // ATC控制
    var atcType: String = "",                   // ATC类型描述
    
    // NOA 增强字段
    var exitDirectionInfo: String = "",         // 出口方向信息
    var exitNameInfo: String = "",              // 出口名称信息
    var roundAboutNum: Int = -1,                // 环岛出口序号
    var roundAllNum: Int = -1,                  // 环岛出口总数
    var segAssistantAction: Int = -1,           // 航段辅助动作
    var sapaName: String = "",                  // 服务区名称
    var sapaDist: Int = -1,                     // 服务区距离
    var sapaType: Int = -1,                     // 设施类型
    var sapaNum: Int = -1,                      // 设施总数
    var nextNextAddIcon: String = "",           // 下下个动作图标
    var routeRemainDisAuto: String = "",        // 路线剩余距离文本
    var routeRemainTimeAuto: String = "",       // 路线剩余时间文本
    var nextSegRemainDisAuto: String = "",      // 下一段路剩余距离文本
    var nextSapaDistAuto: String = "",          // 下一服务区距离文本
    var sapaDistAuto: String = "",              // 当前服务区距离文本
    var nextRoadProgressPercent: Int = -1,      // 下一道路进度百分比
    var cameraID: Long = -1L,                   // 电子眼ID
    var cameraPenalty: Boolean = false,         // 是否抓拍违章
    var newCamera: Boolean = false,             // 是否为新电子眼
    var viaPOIdistance: Int = -1,               // 途径点距离
    var viaPOItime: Int = -1,                   // 途径点时间

    // 系统字段
    var debugText: String = "",                 // 调试文本
    var isNavigating: Boolean = false,          // 是否正在导航
    var lastUpdateTime: Long = System.currentTimeMillis(), // 最后更新时间

    // === 兼容性字段 (保持现有代码正常工作) ===

    // 基础兼容字段
    var active_carrot: Int = 0,                 // CarrotMan激活状态
    var source_last: String = "none",           // 最后数据源
    var dataQuality: String = "good",           // 数据质量
    var remote: String = "",                    // 远程IP地址
    var leftSec: Int = 0,                       // 剩余秒数
    var naviPaths: String = "",                 // 导航路径
    var szSdiDescr: String = "",                // SDI描述

    // GPS兼容字段
    var vpPosPointLatNavi: Double = 0.0,        // 导航模式纬度
    var vpPosPointLonNavi: Double = 0.0,        // 导航模式经度
    var xPosLat: Double = 0.0,                  // X系列纬度
    var xPosLon: Double = 0.0,                  // X系列经度
    var xPosAngle: Double = 0.0,                // X系列角度
    var xPosSpeed: Double = 0.0,                // X系列速度
    var nPosAnglePhone: Double = 0.0,           // 手机角度
    var bearing: Double = 0.0,                  // 方位角
    var bearing_measured: Double = 0.0,         // 测量方位角
    var bearing_offset: Double = 0.0,           // 方位偏移量
    var gps_valid: Boolean = false,             // GPS是否有效
    var gps_accuracy_phone: Double = 0.0,       // 手机GPS精度
    var gps_accuracy_device: Double = 0.0,      // 设备GPS精度
    var last_update_gps_time: Long = 0,         // 最后GPS更新时间
    var last_update_gps_time_phone: Long = 0,   // 最后手机GPS更新时间
    var last_update_gps_time_navi: Long = 0,    // 最后导航GPS更新时间

    // GPS 状态兼容字段（简化版）
    var positionAccuracy: Double = 0.0,         // 位置精度
    var szPosFrom: String = "unknown",          // 位置来源
    var gpsAgeMs: Int = 0,                      // GPS 数据延迟

    // 转弯兼容字段
    var xTurnCountDown: Int = 100,              // X系列转弯倒计时
    var xTurnInfoNext: Int = -1,                // X系列下一转弯信息
    var xDistToTurnNext: Int = 0,               // X系列下一转弯距离
    var szTBTMainTextNext: String = "",         // 下一个转弯指令文本
    var navTypeNext: String = "invalid",        // 下一导航类型
    var navModifierNext: String = "",           // 下一导航修饰符

    // 速度兼容字段
    var xSpdCountDown: Int = 100,               // X系列速度倒计时
    var desiredSpeed: Int = 0,                  // 期望速度
    var desiredSource: String = "",             // 期望数据源
    var nSdiPlusBlockType: Int = -1,            // SDI Plus区间类型
    var nSdiPlusBlockSpeed: Int = 0,            // SDI Plus区间限速
    var nSdiPlusBlockDist: Int = 0,             // SDI Plus区间距离

    // 交通兼容字段
    var traffic_light_count: Int = -1,          // 红绿灯数量
    var routeRemainTrafficLightNum: Int = 0,    // 剩余路程红绿灯数量
    var nextRoadNOAOrNot: Boolean = false,      // 下一路段是否NOA
    var curSegNum: Int = 0,                     // 当前段号
    var curPointNum: Int = 0,                   // 当前点号
    var traffic_state: Int = 0,                 // 交通状态
    var traffic_light_direction: Int = 0,       // 交通灯方向
    var max_left_sec: Int = 100,                // 最大剩余秒数
    
    // 高德地图原始广播字段（用于调试显示）
    var amap_traffic_light_status: Int = 0,     // 高德原始交通灯状态
    var amap_traffic_light_dir: Int = 0,        // 高德原始交通灯方向
    var amap_green_light_last_second: Int = 0,  // 高德原始绿灯剩余秒数
    var amap_wait_round: Int = 0,               // 高德原始等待轮次

    // 高德手机 SDK 内嵌导航（AmapMobileNavPage / AmapNavDataBridge）
    /** 并行路：高架状态（[com.amap.api.navi.enums.AMapNaviParallelRoadStatus] 枚举，未回调时为 -1） */
    var amapParallelElevatedFlag: Int = -1,
    /** 并行路：主辅路状态（同上） */
    var amapParallelMainSideFlag: Int = -1,
    /** 导航地图朝向：0=车头向上 1=北向上（与 [AMapNaviViewListener.onNaviMapMode] 一致） */
    var amapNaviMapMode: Int = 0,
    /** SDK 矢量路口大图是否处于展示回调周期内 */
    var amapSdkCrossVisible: Boolean = false,
    /** SDK 模型路口大图是否处于展示回调周期内 */
    var amapSdkModeCrossVisible: Boolean = false,

    // 车道兼容字段
    var nLaneCount: Int = 0,                    // 当前道路车道数量
    var laneInfoList: List<LaneInfo> = emptyList(), // 🆕 车道详细信息列表
    var totalDistance: Int = 0,                 // 总距离

    // 命令兼容字段
    var carrotCmdIndex: Int = 0,                // CarrotMan命令索引
    var carrotCmdIndex_last: Int = 0,           // 上次CarrotMan命令索引

    // ATC兼容字段
    var atc_paused: Boolean = true,             // ATC是否暂停
    var atc_activate_count: Int = 0,            // ATC激活计数
    var gas_override_speed: Int = 0,            // 油门覆盖速度
    var gas_pressed_state: Boolean = false,    // 油门是否按下

    // 计数器兼容字段
    var active_sdi_count: Int = 0,              // SDI激活计数器
    var active_sdi_count_max: Int = 200,        // SDI最大激活计数
    var sdi_inform: Boolean = false,            // SDI是否已通知
    var diff_angle_count: Int = 0,              // 角度差计数器
    var last_calculate_gps_time: Long = 0,     // 最后GPS计算时间
    
    // 数据发送控制字段
    var needsImmediateSend: Boolean = false,     // 是否需要立即发送数据包

    // === 腾讯 SDK / 车道检测（嵌套 slice，缩短主 data class 的 copy 字节码，避免 ART VerifyError）===
    var tencentSlice: CarrotManTencentSlice = CarrotManTencentSlice(),
) {
    /** SDK日夜模式 (true=夜间) — 非构造函数参数，避免copy()膨胀 */
    @Transient var isNightMode: Boolean = false
    /** 是否偏航中 — 非构造函数参数，避免copy()膨胀 */
    @Transient var isOffRoute: Boolean = false
}

/** 仅替换腾讯/车道检测子块，避免手写冗长的 `copy(tencentSlice = …)` */
fun CarrotManFields.withTencentSlice(transform: (CarrotManTencentSlice) -> CarrotManTencentSlice): CarrotManFields =
    copy(tencentSlice = transform(tencentSlice))





// 高德地图广播静态接收器 - 用于接收高德地图发送的广播，即使应用未启动
class AmapAutoStaticReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "AmapAutoStaticReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        try {
            val action = intent.action
            Log.d(TAG, "收到静态广播: $action")

            if (action == "AUTONAVI_STANDARD_BROADCAST_SEND" ||
                action == "AMAP_BROADCAST_SEND" ||
                action == "AUTONAVI_BROADCAST_SEND" ||
                action == "AMAP_NAVI_ACTION_UPDATE" ||
                action == "AMAP_NAVI_ACTION_TURN" ||
                action == "AMAP_NAVI_ACTION_ROUTE" ||
                action == "AMAP_NAVI_ACTION_LOCATION") {
                // 启动主Activity处理广播
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtras(intent)
                }
                context.startActivity(launchIntent)

                // 记录广播数据
                val keyType = intent.getIntExtra("KEY_TYPE", -1)
                Log.i(TAG, "接收到高德地图广播: KEY_TYPE=$keyType")

                // 记录所有额外数据
                intent.extras?.let { bundle ->
                    for (key in bundle.keySet()) {
                        val value: String = try {
                            @Suppress("DEPRECATION")
                            when (val raw = bundle.get(key)) {
                                is String -> raw
                                is Int -> raw.toString()
                                is Long -> raw.toString()
                                is Double -> raw.toString()
                                is Float -> raw.toString()
                                is Boolean -> raw.toString()
                                is Short -> raw.toString()
                                is Byte -> raw.toString()
                                is Char -> raw.toString()
                                null -> "null"
                                else -> raw.toString()
                            }
                        } catch (e: Exception) {
                            "获取失败: ${e.message}"
                        }
                        Log.v(TAG, "   $key = $value")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理广播失败: ${e.message}", e)
        }
    }
}

/**
 * 车道数量现在通过高德地图车道线广播(KEY_TYPE:13012)实时获取
 * 不再使用基于道路宽度的估算方法
 */