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
)

// 精简后的CarrotMan字段映射数据类
// 仅保留：①发送给comma3的44个UDP字段 ②内部处理/桥接需要的辅助字段
data class CarrotManFields(
    // ═══════════════════════════════════════════════════════════
    // ① 发送给 comma3 的 44 个 UDP 字段（build7706Payload 逐字段序列化）
    // ═══════════════════════════════════════════════════════════

    // 基础通信 (3)
    var carrotIndex: Long = 0,                  // 数据包序号
    var epochTime: Long = 0,                    // Unix时间戳
    var timezone: String = "Asia/Shanghai",     // 时区

    // GPS 定位 — 手机 GPS 回退 (5)
    var latitude: Double = 0.0,                 // GPS纬度 (WGS84)
    var longitude: Double = 0.0,                // GPS经度 (WGS84)
    var heading: Double = 0.0,                  // 方向角 (0-360度)
    var accuracy: Double = 0.0,                 // GPS精度 (米)
    var gps_speed: Double = 0.0,                // GPS速度 (m/s)

    // 目的地 (3)
    var goalPosX: Double = 0.0,                 // 目标经度
    var goalPosY: Double = 0.0,                 // 目标纬度
    var szGoalName: String = "",                // 目标名称

    // 道路限速 (1) — 关键开关：>0 时 comma 才读取导航数据块
    var nRoadLimitSpeed: Int = 0,               // 道路限速 (km/h)
    var roadcate: Int = 8,                      // 道路类别 (0=高速,8=地方)
    var szPosRoadName: String = "",             // 当前道路名称

    // SDI 电子眼 (7)
    var nSdiType: Int = -1,                     // SDI类型
    var nSdiSpeedLimit: Int = 0,                // 测速限速 (km/h)
    var nSdiDist: Int = 0,                      // 到测速点距离 (m)
    var nSdiSection: Int = 0,                   // 区间测速ID
    var nSdiBlockType: Int = -1,                // 区间状态 (1=开始,2=中,3=结束)
    var nSdiBlockSpeed: Int = 0,                // 区间限速
    var nSdiBlockDist: Int = 0,                 // 区间距离

    // SDI Plus 扩展速度 (6)
    var nSdiPlusType: Int = -1,                 // Plus类型 (22=减速带)
    var nSdiPlusSpeedLimit: Int = 0,            // Plus限速
    var nSdiPlusDist: Int = 0,                  // Plus距离
    var nSdiPlusBlockType: Int = -1,            // SDI Plus区间类型
    var nSdiPlusBlockSpeed: Int = 0,            // SDI Plus区间限速
    var nSdiPlusBlockDist: Int = 0,             // SDI Plus区间距离

    // TBT 转弯导航 (9)
    var nTBTDist: Int = 0,                      // 转弯距离 (m)
    var nTBTTurnType: Int = -1,                 // 转弯类型
    var szTBTMainText: String = "",             // 主要指令文本
    var szNearDirName: String = "",             // 近处方向名
    var szFarDirName: String = "",              // 远处方向名
    var nTBTNextRoadWidth: Int = 0,             // 下一道路宽度 (车道数)
    var nTBTDistNext: Int = 0,                  // 下一转弯距离
    var nTBTTurnTypeNext: Int = -1,             // 下一转弯类型
    var szTBTMainTextNext: String = "",         // 下一个转弯指令文本

    // 目的地剩余 (3)
    var nGoPosDist: Int = 0,                    // 剩余距离 (m)
    var nGoPosTime: Int = 0,                    // 剩余时间 (s)

    // 导航 GPS 位置 (4)
    var vpPosPointLat: Double = 0.0,            // 导航纬度
    var vpPosPointLon: Double = 0.0,            // 导航经度
    var nPosAngle: Double = 0.0,                // 导航方向角
    var nPosSpeed: Double = 0.0,                // 导航速度

    // 命令通道 (2)
    var carrotCmd: String = "",                 // 命令类型 (DETECT等)
    var carrotArg: String = "",                 // 命令参数

    // ═══════════════════════════════════════════════════════════
    // ② 内部辅助字段（不发送至 comma3，用于桥接/调试/状态管理）
    // ═══════════════════════════════════════════════════════════

    // 导航状态
    var isNavigating: Boolean = false,          // 是否正在导航
    var active_carrot: Int = 0,                 // CarrotMan激活状态
    var source_last: String = "none",           // 最后数据源
    var lastUpdateTime: Long = System.currentTimeMillis(), // 最后更新时间

    // 高德地图原始ICON（用于跨 handler 的转弯类型映射）
    var amapIcon: Int = -1,
    var amapIconNext: Int = -1,
    // 高德原始CAMERA_TYPE（用于调试）
    var nAmapCameraType: Int = -1,

    // 道路类型（被 MainActivityLifecycle 读取）
    var roadType: Int = 8,

    // 限速与区间平均速度
    var speedLimitType: Int = -1,
    var nSdiAverageSpeed: Int = -1,
    var extraState: Int = -1,

    // 路况
    var trafficLevel: Int = -1,
    var trafficDescription: String = "",

    // 车道信息（用于显示）
    var laneCount: Int = 0,
    var laneInfoList: List<LaneInfo> = emptyList(),
    var nLaneCount: Int = 0,

    // 出口与服务区（桥接存储）
    var exitNameInfo: String = "",
    var sapaName: String = "",
    var sapaDist: Int = -1,
    var sapaType: Int = -1,
    var sapaNum: Int = -1,

    // 航段辅助 — 被 MainActivityLifecycle 读取
    var segAssistantAction: Int = -1,
    // 当前途径点 — 腾讯导航
    var curPointNum: Int = 0,
    var curSegNum: Int = 0,

    // 红绿灯倒计时 — 腾讯导航
    var trafficLightState: Int = -1,
    var trafficLightDistance: Int = 0,
    var trafficLightCountdown: Int = 0,

    // 态势信息 — AmapNavDataBridge
    var situationType: Int = -1,
    var situationDistance: Int = 0,
    var situationDescription: String = "",

    // 高德手机 SDK 内嵌导航辅助状态
    var amapSdkCrossVisible: Boolean = false,
    var amapSdkModeCrossVisible: Boolean = false,
    var amapParallelElevatedFlag: Int = -1,
    var amapParallelMainSideFlag: Int = -1,
    var amapNaviMapMode: Int = 0,

    // 高德车机广播原始红绿灯调试字段
    var amap_traffic_light_status: Int = 0,
    var amap_traffic_light_dir: Int = 0,
    var amap_green_light_last_second: Int = 0,
    var amap_wait_round: Int = 0,

    // comma3 7705 接收状态（仅 active 被 app 读取）
    var active: Boolean = false,
    var isOnroad: Boolean = false,           // 7705: 是否在道路上行驶
    var xState: Int = 0,                     // 7705: 纵向控制状态码
    var vEgoKph: Int = 0,                    // 7705: 当前实际车速(km/h)
    var trafficState: Int = -1,              // 7705: 交通灯状态
    var vCruiseKph: Float = 0.0f,            // 7705: 巡航设定速度(km/h)
    var carcruiseSpeed: Float = 0.0f,        // 7705: 车辆巡航速度(km/h)

    // ATC 弯道减速
    var atcType: String = "",
    var vTurnSpeed: Double = 0.0,

    // 发送控制
    var needsImmediateSend: Boolean = false,    // 强制立即发送（限速变化等）

    // ═══════════════════════════════════════════════════════════
    // ③ 腾讯 SDK / 车道检测嵌套 slice（ART VerifyError 保护）
    // ═══════════════════════════════════════════════════════════
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