package com.example.navipilot.navigation

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.MutableState
import com.example.navipilot.CarrotManFields
import com.example.navipilot.CarrotManTencentSlice
import com.example.navipilot.LaneInfo
import com.tencent.navix.api.model.NavDriveDataInfo
import com.tencent.navix.api.model.NavDriveRoute
import com.tencent.navix.api.model.NavDriveRouteData
import com.tencent.navix.api.model.NavLaneInfo
import com.tencent.navix.api.model.NavLocationInfo
import com.tencent.navix.api.observer.SimpleNavigatorDriveObserver

/**
 * 腾讯导航数据桥接器（Phase 6 — SDK文档驱动全面增强版）
 *
 * 基于 SDK v7.5.0 官方文档 (mapapi.qq.com) 的完整 API 分析：
 *
 * === NavDriveRouteData（每次 onNavDataInfoUpdate 回调） ===
 * - getLimitSpeed()              → nRoadLimitSpeed（道路限速，替代旧的 getCurrentSpeedLimit 反射）
 * - getNextIntersectionType()    → nTBTTurnType（转向类型枚举，替代文本推断）
 * - getNextNextIntersectionType()→ nTBTTurnTypeNext（第二转弯类型）
 * - getNextNextRoadName()        → szFarDirName / szTBTMainTextNext
 * - getNextNextIntersectionBitmap() → turnBitmapNext（第二转向图标）
 * - getCurrRoadName()            → szPosRoadName
 * - getNextRoadName()            → szTBTMainText / szNearDirName
 * - getRemainingDist()           → nGoPosDist
 * - getRemainingTimeInSeconds()  → nGoPosTime
 * - getPassedDistance()          → passedDistance
 * - getRemainingTrafficLightCount() → remainingTrafficLights
 *
 * === NavDriveDataInfo（onNavDataInfoUpdate 的参数本身） ===
 * - getNavSpeedMonitorZoneInfo() → 区间测速（nSdiBlockType/Speed/Dist）
 * - isOverSpeed()                → 超速检测
 * - getSpeedKMH()                → SDK报告的当前速度
 * - getHighwayExitName()         → 高速出口名
 * - getHighwayEntranceName()     → 高速入口名
 *
 * === NavCameraInfo（onCameraInfoUpdate 回调） ===
 * - getCameraType()              → nSdiType 映射
 * - getLimitSpeedKMPH()          → nSdiSpeedLimit
 * - getDistance()                → nSdiDist
 * - getSpeedZoneLimitLength()    → 区间测速长度
 *
 * === NavLaneInfo（onWillShowLaneGuide 回调） ===
 * - getNum()                     → laneCount（车道数）
 * - getItems() → LaneItem(name, recommend) → laneInfoList
 *
 * === NavParallelRoadStatus（onUpdateParallelRoad 回调） ===
 * - getCurrentRoadType()         → isOnMainRoad
 */
class TencentNavDataBridge(
    private val carrotManFieldsState: MutableState<CarrotManFields>?
) {
    companion object {
        private const val TAG = "TencentNavBridge"

        /**
         * 腾讯SDK getNextIntersectionType() → nTBTTurnType 完整映射表
         *
         * 数据来源：NavDriveRouteData.getNextIntersectionType() 返回的SDK枚举编号
         * 映射目标：CarrotMan nTBTTurnType → Python turn_type_mapping → xTurnInfo
         *
         * SDK枚举值来源：
         * - 0~15: 基础动作（官方文档 v7.2.0 确认）
         * - 60~70: 特殊设施/目的地（实测确认）
         * - 80~89: 扩展动作（实测确认，官方文档未公开）
         *
         * nTBTTurnType → Python xTurnInfo 对照：
         *   51  → 0 (notification/直行)
         *   12  → 1 (left turn/左转)
         *   13  → 2 (right turn/右转)
         *   102 → 3 (slight left/左前方)
         *   101 → 4 (slight right/右前方)
         *   17  → 3 (fork left/左后方)
         *   19  → 2 (sharp right/右后方)
         *   131 → 5 (rotary/进入环岛)
         *   132 → 5 (rotary/驶出环岛)
         *   153 → 6 (TG/收费站/岔路)
         *   14  → 7 (uturn/掉头)
         *   201 → 8 (arrive/到达目的地)
         *   55  → 0 (notification/服务区)
         *
         * ⚠️ 发现新的未知SDK枚举值时，日志会打印 "🆕 未知SDK intersectionType"，
         *    请记录并补充到此映射表中。
         */
        fun mapIntersectionType(type: Int): Int {
            return when (type) {
                // === 基础动作 (0~15, 官方文档 v7.2.0 确认) ===
                0 -> 51    // 无动作/直行
                1 -> 51    // 直行
                2 -> 12    // 左转
                3 -> 13    // 右转
                4 -> 102   // 左前方
                5 -> 101   // 右前方
                6 -> 17    // 左后方
                7 -> 19    // 右后方
                8 -> 153   // 走中间/岔路/上高架 → TG  ✅ 实测确认
                9 -> 102   // 靠左
                10 -> 101  // 靠右
                11 -> 131  // 进入环岛
                12 -> 51   // 靠左沿主路直行  ✅ 用户实测确认
                13 -> 55   // 到达服务区
                14 -> 153  // 到达收费站 → TG
                15 -> 201  // 到达目的地

                // === 高速/匝道/立交相关 (16~30, 实测逐步确认) ===
                18 -> 102  // 靠左行驶  ✅ 实测确认 (G1522)
                22 -> 101  // 靠右行驶  ✅ 实测确认 (东南环立交)

                // === 收费站/服务区/特殊设施 (60~70, 实测逐步确认) ===
                61 -> 201  // 到达目的地  ✅ 用户实测确认
                64 -> 51   // 进入隧道  ✅ 用户实测确认
                66 -> 153  // 过收费站 → TG  ✅ 实测确认 (吴中互通)

                // === 掉头/调头 (80~89, 实测逐步确认) ===
                81 -> 51   // 沿主路直行  ✅ 用户实测确认
                87 -> 14   // 右转掉头(右侧U-turn)  ✅ 实测确认

                // === 匝道/出入口 (1000+, 实测逐步确认) ===
                1001 -> 51   // 直行(匝道行驶)  ✅ 实测确认 (北京 南京)

                else -> {
                    Log.w(TAG, "🆕 未知SDK intersectionType=$type，请记录并补充映射表！")
                    -1
                }
            }
        }

        /** 已发现的未知SDK枚举值集合（避免重复日志刷屏） */
        private val unknownIntersectionTypes = mutableSetOf<Int>()

        /**
         * SDK枚举编号 → nTBTTurnType（纯编号映射，不依赖文本推断）
         *
         * 映射优先级：
         * 1. mapIntersectionType() 编号映射（主要）
         * 2. 未知编号时返回 -1，由 Python 端忽略
         *
         * 文本推断 inferTurnTypeFromText() 仅在 SDK 返回 -1（无数据）时作为最后兜底。
         */
        fun resolveNTBTTurnType(sdkType: Int, text: String): Int {
            val result = if (sdkType >= 0) {
                val mapped = mapIntersectionType(sdkType)
                if (mapped != -1) {
                    mapped
                } else {
                    // 未知SDK编号：记录一次，方便后续补充映射表
                    if (unknownIntersectionTypes.add(sdkType)) {
                        Log.e(TAG, "🆕🆕🆕 发现新的SDK intersectionType=$sdkType, text='$text' — 请补充到 mapIntersectionType() 映射表！")
                    }
                    // 未知编号不做文本推断，直接返回-1让Python端忽略
                    -1
                }
            } else {
                // SDK返回-1（无数据），尝试文本兜底
                inferTurnTypeFromText(text)
            }
            Log.d(TAG, "🔄 [转弯映射] SDK=$sdkType, text='$text' → nTBTTurnType=$result")
            return result
        }

        /**
         * 从NavTurnRestriction获取转向限制信息
         * 
         * 根据SDK官方文档：
         * - getTurnRestrictionType() 返回 NavTurnRestrictionType 常量
         * - TurnRestrictionTypeNone = 0 (无限制)
         * - TurnRestrictionTypeLeft = 1 (禁止左转)
         * - TurnRestrictionTypeRight = 2 (禁止右转)
         * - TurnRestrictionTypeLeftUTurn = 3 (禁止左调头)
         * - TurnRestrictionTypeRightUTurn = 4 (禁止右调头)
         * - TurnRestrictionTypeStraight = 5 (禁止直行)
         * 
         * @param restriction NavTurnRestriction对象
         * @return 转向限制描述，如"禁止左转"
         */
        fun getTurnRestrictionInfo(restriction: Any?): String {
            if (restriction == null) return ""
            
            try {
                // 获取转向限制类型 - getTurnRestrictionType()
                val restrictionType = try {
                    restriction.javaClass.getMethod("getTurnRestrictionType").invoke(restriction) as? Int ?: 0
                } catch (_: Exception) { 0 }
                
                // 获取限行时间描述 - getTimeDesc()
                val timeDesc = try {
                    restriction.javaClass.getMethod("getTimeDesc").invoke(restriction) as? String ?: ""
                } catch (_: Exception) { "" }
                
                // 是否24小时限制 - isAlways()
                val isAlways = try {
                    restriction.javaClass.getMethod("isAlways").invoke(restriction) as? Boolean ?: false
                } catch (_: Exception) { false }
                
                // 所在路名 - getRoadName()
                val roadName = try {
                    restriction.javaClass.getMethod("getRoadName").invoke(restriction) as? String ?: ""
                } catch (_: Exception) { "" }
                
                // 根据NavTurnRestrictionType映射
                val restrictionDesc = when (restrictionType) {
                    0 -> ""  // TurnRestrictionTypeNone - 无限制
                    1 -> "禁止左转"  // TurnRestrictionTypeLeft
                    2 -> "禁止右转"  // TurnRestrictionTypeRight
                    3 -> "禁止左调头"  // TurnRestrictionTypeLeftUTurn
                    4 -> "禁止右调头"  // TurnRestrictionTypeRightUTurn
                    5 -> "禁止直行"  // TurnRestrictionTypeStraight
                    else -> "未知限制($restrictionType)"
                }
                
                if (restrictionDesc.isEmpty()) return ""
                
                // 构建完整描述
                val fullDesc = buildString {
                    if (roadName.isNotEmpty()) append("$roadName ")
                    append(restrictionDesc)
                    if (!isAlways && timeDesc.isNotEmpty()) {
                        append(" ($timeDesc)")
                    } else if (isAlways) {
                        append(" (24小时)")
                    }
                }
                
                return fullDesc
            } catch (e: Exception) {
                Log.w(TAG, "解析NavTurnRestriction失败: ${e.message}")
                return ""
            }
        }

        /**
         * 从SDK返回的下一路口名/指令文本推断转弯类型（回退方案）
         * 当 getNextIntersectionType() 返回未知值时使用
         */
        fun inferTurnTypeFromText(text: String): Int =
            TurnTypeTextInference.inferTurnTypeFromText(text)

        /**
         * 腾讯SDK NavCameraInfo.getCameraType() → Python nSdiType 映射
         *
         * 基于 SDK v7.5.0 NavCameraType 常量（已从官方文档确认）：
         *   0=None, 1=RedLight, 2=ElectronicMonitoring, 3=FixedSpeedTraps,
         *   4=MobileSpeedZone, 5=BusOnlyWay, 6=OneWay, 7=EmergencyWay,
         *   8=NoneMotorWay, 9=QujianEnter, 10=QujianExit,
         *   16=HOV, 17=LaLian, 21=TailNumber, 22=GoToBeijing,
         *   23=IllegalBlow, 24=BusStation, 30=ForbiddenTure,
         *   31=ForbiddenLine, 32=ForbiddenParking, 33=LowestSpeed,
         *   34=AlterableSpeed, 35=LaneSpeed, 36=VehicelTypeSpeed,
         *   37=LaneOccupy, 38=Crossing, 39=ForbiddenSign,
         *   40=ForbiddenLight, 41=LifeBelt, 42=ForbiddenCall,
         *   43=LimitLine, 44=PedestrainFirst, 45=AnnualInpection,
         *   46=VehicelExhaust, 47=Traffic, 48=Entrance,
         *   49=ForbiddenUTurn, 50=EtcToll, 51=NotFollowGuideLane,
         *   52=TrafficFlowMonitor, 53=KeepSafeDistance, 54=IllegalChangeLane
         *
         * Python _update_sdi() 检查 nSdiType in [0,1,2,3,4,7,8,75,76] 触发减速
         * Python 减速带检查: nSdiPlusType==22 or nSdiType==22
         */
        fun mapCameraTypeToSdiType(cameraType: Int, zoneLength: Int): Int {
            // 区间测速优先判断
            if (zoneLength > 0) return 2  // 区间测速开始

            return when (cameraType) {
                0 -> -1    // None → 无效
                1 -> 6     // RedLight 闯红灯 → sdi=6
                2 -> 8     // ElectronicMonitoring 电子监控 → sdi=8(拍照)
                3 -> 1     // FixedSpeedTraps 固定测速 → sdi=1(固定测速)
                4 -> 7     // MobileSpeedZone 流动测速 → sdi=7
                5 -> 9     // BusOnlyWay 公交车道 → sdi=9
                6 -> 8     // OneWay 单行道 → sdi=8(拍照)
                7 -> 11    // EmergencyWay 应急车道 → sdi=11
                8 -> 8     // NoneMotorWay 非机动车道 → sdi=8(拍照)
                9 -> 2     // QujianEnter 区间测速入口 → sdi=2
                10 -> 3    // QujianExit 区间测速出口 → sdi=3
                16 -> 8    // HOV车道 → sdi=8(拍照)
                17 -> 8    // LaLian 拉链通行 → sdi=8(拍照)
                21 -> 14   // TailNumber 尾号限行 → sdi=14(治安监控)
                22 -> 14   // GoToBeijing 进京检查 → sdi=14
                23 -> 8    // IllegalBlow 违法鸣笛 → sdi=8(拍照)
                24 -> 8    // BusStation 公交站 → sdi=8(拍照)
                30 -> 8    // ForbiddenTure 禁止转弯 → sdi=8(拍照)
                31 -> 8    // ForbiddenLine 压线 → sdi=8(拍照)
                32 -> 17   // ForbiddenParking 违停 → sdi=17(违停拍照)
                33 -> 75   // LowestSpeed 最低限速 → sdi=75(Python触发减速)
                34 -> 1    // AlterableSpeed 可变限速 → sdi=1(固定测速)
                35 -> 1    // LaneSpeed 车道限速 → sdi=1(固定测速)
                36 -> 1    // VehicelTypeSpeed 车型限速 → sdi=1(固定测速)
                37 -> 8    // LaneOccupy 占用车道 → sdi=8(拍照)
                38 -> 8    // Crossing 路口 → sdi=8(拍照)
                39 -> 8    // ForbiddenSign 禁令标志 → sdi=8(拍照)
                40 -> 8    // ForbiddenLight 禁止远光灯 → sdi=8(拍照)
                41 -> 8    // LifeBelt 安全带 → sdi=8(拍照)
                42 -> 8    // ForbiddenCall 禁止打电话 → sdi=8(拍照)
                43 -> 14   // LimitLine 限行 → sdi=14
                44 -> 76   // PedestrainFirst 礼让行人 → sdi=76(Python触发减速)
                45 -> 14   // AnnualInpection 年检 → sdi=14
                46 -> 14   // VehicelExhaust 尾气检测 → sdi=14
                47 -> 8    // Traffic 交通 → sdi=8(拍照)
                48 -> 8    // Entrance 入口 → sdi=8(拍照)
                49 -> 8    // ForbiddenUTurn 禁止掉头 → sdi=8(拍照)
                50 -> 26   // EtcToll ETC收费 → sdi=26
                51 -> 8    // NotFollowGuideLane 不按导向车道 → sdi=8(拍照)
                52 -> 8    // TrafficFlowMonitor 车流量监控 → sdi=8(拍照)
                53 -> 76   // KeepSafeDistance 保持安全距离 → sdi=76
                54 -> 8    // IllegalChangeLane 违法变道 → sdi=8(拍照)
                else -> {
                    if (cameraType >= 0) 8 else -1  // 未知类型当拍照
                }
            }
        }

        /**
         * NavCameraType 整数值 → 中文名称（用于日志和UI）
         */
        fun cameraTypeName(cameraType: Int): String = when (cameraType) {
            0 -> "无"
            1 -> "闯红灯拍照"
            2 -> "电子监控"
            3 -> "固定测速"
            4 -> "流动测速"
            5 -> "公交车道"
            6 -> "单行道"
            7 -> "应急车道"
            8 -> "非机动车道"
            9 -> "区间测速入口"
            10 -> "区间测速出口"
            16 -> "HOV车道"
            17 -> "拉链通行"
            21 -> "尾号限行"
            22 -> "进京检查"
            23 -> "违法鸣笛"
            24 -> "公交站"
            30 -> "禁止转弯"
            31 -> "压线拍照"
            32 -> "违停拍照"
            33 -> "最低限速"
            34 -> "可变限速"
            35 -> "车道限速"
            36 -> "车型限速"
            37 -> "占用车道"
            38 -> "路口监控"
            39 -> "禁令标志"
            40 -> "禁止远光灯"
            41 -> "安全带检查"
            42 -> "禁止打电话"
            43 -> "限行拍照"
            44 -> "礼让行人"
            45 -> "年检拍照"
            46 -> "尾气检测"
            47 -> "交通监控"
            48 -> "入口拍照"
            49 -> "禁止掉头"
            50 -> "ETC收费"
            51 -> "不按导向车道"
            52 -> "车流量监控"
            53 -> "保持安全距离"
            54 -> "违法变道"
            else -> "电子眼($cameraType)"
        }

        /**
         * 根据道路限速和道路名推断道路类型（roadcate）
         * roadcate: 10/11=高速公路, 6=普通道路
         */
        fun inferRoadcateFromSpeedLimit(speedLimit: Int, currentRoadcate: Int, roadName: String = ""): Int {
            // 优先通过道路名判断（更准确）
            if (roadName.isNotEmpty()) {
                val isHighway = roadName.contains("高速") || roadName.contains("快速")
                        || roadName.contains("环线") || roadName.contains("高架")
                        || roadName.matches(Regex(".*[GS]\\d{1,4}.*"))  // G1, S58 等编号
                if (isHighway) return 10
            }
            return when {
                speedLimit >= 100 -> 10
                speedLimit >= 80 -> 10
                speedLimit > 0 -> 6
                else -> currentRoadcate
            }
        }

        /**
         * 腾讯SDK NavRoadType 枚举 → roadcate 映射
         *
         * NavRoadType 枚举值（SDK v7.5.0 文档）：
         *   MainRoad           — 在主路
         *   ServingRoad         — 在辅路
         *   Elevated            — 在桥上（高架/立交）
         *   Downstairs          — 在桥下
         *   DownstairsMainRoad  — 在桥下主路
         *   DownstairsServingRoad — 在桥下辅路
         *   DirectionRoad       — 在对面（初始算路无效）
         *   None                — 无详细类型
         *
         * roadcate 协议值（Python carrot_serv.py）：
         *   10 = 高速公路/快速路/高架
         *   6  = 普通道路
         *   8  = 未知/默认
         *
         * @param navRoadTypeName NavRoadType.toString() 或 NavRoadType.name()
         * @param currentRoadcate 当前 roadcate 值（无法判断时保留）
         * @return 映射后的 roadcate
         */
        fun mapNavRoadTypeToRoadcate(navRoadTypeName: String, currentRoadcate: Int): Int {
            return when {
                // 高架/桥上 → 高速/快速路（高架通常是城市快速路或高速匝道）
                navRoadTypeName.contains("Elevated", ignoreCase = true) -> 10
                // 桥下主路 → 保持当前值（桥下主路可能是高速也可能是普通路）
                navRoadTypeName.contains("DownstairsMainRoad", ignoreCase = true) -> currentRoadcate
                // 桥下辅路 → 普通道路
                navRoadTypeName.contains("DownstairsServingRoad", ignoreCase = true) -> 6
                // 桥下（未区分主辅路）→ 保持当前值
                navRoadTypeName.contains("Downstairs", ignoreCase = true) -> currentRoadcate
                // 辅路 → 普通道路
                navRoadTypeName.contains("ServingRoad", ignoreCase = true) ||
                navRoadTypeName.contains("Serving", ignoreCase = true) -> 6
                // 主路 → 保持当前值（主路可能是高速也可能是普通路）
                navRoadTypeName.contains("MainRoad", ignoreCase = true) -> currentRoadcate
                // 对面 → 保持当前值
                navRoadTypeName.contains("Direction", ignoreCase = true) -> currentRoadcate
                // None / 未知 → 保持当前值
                else -> currentRoadcate
            }
        }
    }

    /** 导航是否激活 */
    var isNavigating = false
        private set

    /** SDK报告的超速状态（比自行计算更准确，区分普通超速/区间超速） */
    var sdkIsOverSpeed = false
        private set
    var sdkOverSpeedType = 0  // 0=无, 1=普通超速, 2=区间超速
        private set
    var sdkSpeedKMH = 0
        private set

    /** 推荐路线信息（SDK发现更优路线时填充） */
    var recommendRouteInfo: String? = null
        private set
    var recommendRouteId: String? = null
        private set

    /** 最新转向图标（SDK提供的Bitmap，供UI直接显示） */
    var turnBitmap: Bitmap? = null
        private set

    /** 第二转向图标（连续转弯时） */
    var turnBitmapNext: Bitmap? = null
        private set

    /** 最新车道引导图（SDK提供的Bitmap） */
    var laneBitmap: Bitmap? = null
        private set

    // === 路段道路信息缓存（用于实时 roadcate 更新）===
    /** 每个 segment 的 (grade, kind) 列表，路线规划时填充 */
    private var segmentRoadInfoList: List<Pair<Int, Int>> = emptyList()
    /** 上次更新 roadcate 时的 segIdx，避免重复更新 */
    private var lastRoadcateSegIdx = -1
    
    /** 上次记录的 bitmap 哈希，避免重复日志 */
    private var lastBitmapHash = 0
    
    /**
     * 📸 记录转向图标 Bitmap 信息（尺寸 + 像素哈希 + 中心区域采样）
     * 不同转弯动作的图标像素不同，哈希值可用于反向确认 SDK intersectionType 的含义
     */
    private fun logBitmapInfo(label: String, bitmap: Bitmap?) {
        if (bitmap == null) return
        try {
            val w = bitmap.width
            val h = bitmap.height
            // 采样中心区域像素计算哈希
            val cx = w / 2; val cy = h / 2
            val sampleSize = minOf(8, w / 2, h / 2)
            var hash = 0
            for (dy in -sampleSize until sampleSize) {
                for (dx in -sampleSize until sampleSize) {
                    val px = bitmap.getPixel((cx + dx).coerceIn(0, w - 1), (cy + dy).coerceIn(0, h - 1))
                    hash = hash * 31 + px
                }
            }
            // 只在哈希变化时记录（避免每200ms重复刷屏）
            if (hash != lastBitmapHash) {
                lastBitmapHash = hash
                // 采样四角+中心的像素颜色（辅助人工识别图标内容）
                val tl = Integer.toHexString(bitmap.getPixel(w / 4, h / 4))
                val tr = Integer.toHexString(bitmap.getPixel(3 * w / 4, h / 4))
                val bl = Integer.toHexString(bitmap.getPixel(w / 4, 3 * h / 4))
                val br = Integer.toHexString(bitmap.getPixel(3 * w / 4, 3 * h / 4))
                val cc = Integer.toHexString(bitmap.getPixel(cx, cy))
                Log.i(TAG, "📸 [$label] ${w}x${h}, hash=${Integer.toHexString(hash)}, " +
                        "pixels: TL=$tl TR=$tr BL=$bl BR=$br C=$cc")
            }
        } catch (e: Exception) {
            Log.w(TAG, "📸 [$label] 读取Bitmap信息失败: ${e.message}")
        }
    }

    // === 反射探测缓存（NavDriveRouteData 字段） ===
    private var routeDataReflectionChecked = false
    private var hasGetLimitSpeed = false
    private var hasGetRemainingDist = false
    private var hasGetRemainingTimeInSeconds = false
    private var hasGetRemainingTime = false
    private var hasGetCurrRoadName = false
    private var hasGetNextRoadName = false
    private var hasGetNextNextRoadName = false
    private var hasGetNextIntersectionType = false
    private var hasGetNextNextIntersectionType = false
    private var hasGetNextNextIntersectionBitmap = false
    private var hasGetPassedDistance = false
    private var hasGetRemainingTrafficLightCount = false
    private var hasGetNextNextIntersectionRemainingDistance = false
    private var hasGetTrafficItems = false

    // === 反射探测缓存（NavDriveDataInfo 字段） ===
    private var dataInfoReflectionChecked = false
    private var hasGetNavSpeedMonitorZoneInfo = false
    private var hasIsOverSpeed = false
    private var hasGetSpeedKMH = false
    private var hasGetHighwayExitName = false
    private var hasGetHighwayEntranceName = false
    private var hasGetHighwayFacilities = false
    private var hasGetPassedTime = false
    private var hasGetPassedDistanceInfo = false  // NavDriveDataInfo级别的passedDistance
    private var hasGetNavEnlargedMapInfo = false
    private var hasGetRestrictionInfo = false

    // === 反射探测缓存（NavDriveRoute 路线级别字段） ===
    private var routeReflectionChecked = false
    private var hasGetRoutePoints = false
    private var hasGetSegmentItems = false
    private var hasGetTrafficLights = false
    private var hasGetForkPoints = false
    private var hasGetFee = false

    /**
     * 探测 NavDriveRouteData 上可用的 getter 方法
     * SDK v7.5.0 文档确认的方法名（但运行时可能因SDK版本差异而缺失）
     */
    private fun checkRouteDataFields(routeData: NavDriveRouteData) {
        if (routeDataReflectionChecked) return
        routeDataReflectionChecked = true
        val clazz = routeData.javaClass
        fun has(name: String) = try { clazz.getMethod(name); true } catch (_: Exception) { false }

        hasGetLimitSpeed = has("getLimitSpeed")
        hasGetRemainingDist = has("getRemainingDist")
        hasGetRemainingTimeInSeconds = has("getRemainingTimeInSeconds")
        hasGetRemainingTime = has("getRemainingTime")
        hasGetCurrRoadName = has("getCurrRoadName")
        hasGetNextRoadName = has("getNextRoadName")
        hasGetNextNextRoadName = has("getNextNextRoadName")
        hasGetNextIntersectionType = has("getNextIntersectionType")
        hasGetNextNextIntersectionType = has("getNextNextIntersectionType")
        hasGetNextNextIntersectionBitmap = has("getNextNextIntersectionBitmap")
        hasGetPassedDistance = has("getPassedDistance")
        hasGetRemainingTrafficLightCount = has("getRemainingTrafficLightCount")
        hasGetTrafficItems = has("getTrafficItems")

        // 🆕 探测第二转弯距离方法（SDK文档未公开，但可能存在）
        hasGetNextNextIntersectionRemainingDistance = has("getNextNextIntersectionRemainingDistance")
        if (!hasGetNextNextIntersectionRemainingDistance) {
            // 尝试其他可能的方法名
            hasGetNextNextIntersectionRemainingDistance = has("getNextNextRemainingDistance")
                    || has("getNextNextIntersectionDistance")
                    || has("getNextNextDistance")
        }

        // 兼容旧反射名（v5.x SDK 可能用不同方法名）
        if (!hasGetLimitSpeed) hasGetLimitSpeed = has("getCurrentSpeedLimit")
        if (!hasGetRemainingDist) hasGetRemainingDist = has("getRemainingDistance")
        if (!hasGetCurrRoadName) hasGetCurrRoadName = has("getCurrentRoadName")

        // 打印所有包含 "Next" 的方法名，帮助发现未文档化的API
        val nextMethods = clazz.methods.map { it.name }
            .filter { it.contains("Next", ignoreCase = false) || it.contains("next", ignoreCase = false) }
            .filter { !it.startsWith("wait") && !it.startsWith("notify") }
        Log.i(TAG, "📊 RouteData含'Next'方法: $nextMethods")

        Log.i(TAG, "📊 RouteData字段探测: limitSpeed=$hasGetLimitSpeed, remainDist=$hasGetRemainingDist, " +
                "remainTimeSec=$hasGetRemainingTimeInSeconds, currRoad=$hasGetCurrRoadName, " +
                "nextRoad=$hasGetNextRoadName, nextNextRoad=$hasGetNextNextRoadName, " +
                "intersectionType=$hasGetNextIntersectionType, nextNextType=$hasGetNextNextIntersectionType, " +
                "nextNextBitmap=$hasGetNextNextIntersectionBitmap, passedDist=$hasGetPassedDistance, " +
                "trafficLights=$hasGetRemainingTrafficLightCount, trafficItems=$hasGetTrafficItems, " +
                "nextNextDist=$hasGetNextNextIntersectionRemainingDistance")
    }

    /**
     * 探测 NavDriveDataInfo 上可用的 getter 方法
     */
    private fun checkDataInfoFields(info: NavDriveDataInfo) {
        if (dataInfoReflectionChecked) return
        dataInfoReflectionChecked = true
        val clazz = info.javaClass
        fun has(name: String) = try { clazz.getMethod(name); true } catch (_: Exception) { false }

        hasGetNavSpeedMonitorZoneInfo = has("getNavSpeedMonitorZoneInfo")
        hasIsOverSpeed = has("isOverSpeed")
        hasGetSpeedKMH = has("getSpeedKMH")
        hasGetHighwayExitName = has("getHighwayExitName")
        hasGetHighwayEntranceName = has("getHighwayEntranceName")
        hasGetHighwayFacilities = has("getHighwayFacilities")
        hasGetPassedTime = has("getPassedTime")
        hasGetPassedDistanceInfo = has("getPassedDistance")
        hasGetNavEnlargedMapInfo = has("getNavEnlargedMapInfo")
        hasGetRestrictionInfo = has("getRestrictionInfo")

        Log.i(TAG, "📊 DataInfo字段探测: speedMonitorZone=$hasGetNavSpeedMonitorZoneInfo, " +
                "overSpeed=$hasIsOverSpeed, speedKMH=$hasGetSpeedKMH, " +
                "hwExit=$hasGetHighwayExitName, hwEntrance=$hasGetHighwayEntranceName, " +
                "hwFacilities=$hasGetHighwayFacilities, passedTime=$hasGetPassedTime, " +
                "enlargedMap=$hasGetNavEnlargedMapInfo, restrictionInfo=$hasGetRestrictionInfo")
    }

    /** 安全调用getter，失败返回默认值 */
    private fun <T> safeGet(obj: Any, methodName: String, default: T): T {
        return try {
            @Suppress("UNCHECKED_CAST")
            obj.javaClass.getMethod(methodName).invoke(obj) as? T ?: default
        } catch (_: Exception) { default }
    }

    /** 安全调用getter（尝试多个方法名，兼容不同SDK版本） */
    private fun <T> safeGetAny(obj: Any, default: T, vararg methodNames: String): T {
        for (name in methodNames) {
            try {
                @Suppress("UNCHECKED_CAST")
                val result = obj.javaClass.getMethod(name).invoke(obj) as? T
                if (result != null) return result
            } catch (_: Exception) { /* try next */ }
        }
        return default
    }

    /**
     * SDK观察者（注册到NavigatorDrive）
     */
    val driveObserver = object : SimpleNavigatorDriveObserver() {

        override fun onWillArriveDestination() {
            super.onWillArriveDestination()
            Log.i(TAG, "🏁 即将到达目的地")
            updateField { it.copy(nTBTTurnType = 201, szTBTMainText = "到达目的地") }
        }

        override fun onNavDataInfoUpdate(info: NavDriveDataInfo?) {
            super.onNavDataInfoUpdate(info)
            
            // 🔍 关键调试：确认回调被触发
            Log.i(TAG, "🔔 onNavDataInfoUpdate 被调用 - info=${if (info != null) "有数据" else "null"}")
            
            if (info == null) {
                Log.w(TAG, "⚠️ onNavDataInfoUpdate: info is null")
                return
            }
            
            val mainRoute = info.mainRouteData
            Log.i(TAG, "🔔 mainRouteData = ${if (mainRoute != null) "有数据" else "null (模拟导航模式)"}")
            
            if (mainRoute == null) {
                Log.w(TAG, "⚠️ onNavDataInfoUpdate: mainRouteData is null (模拟导航模式)")
                
                // 🔑 关键：模拟导航时mainRouteData为null，但可以从routeDataList获取
                val routeDataList = try { 
                    info.routeDataList 
                } catch (e: Exception) { 
                    Log.e(TAG, "❌ 无法获取routeDataList: ${e.message}")
                    null 
                }
                
                Log.i(TAG, "📋 routeDataList size = ${routeDataList?.size ?: 0}")
                
                // 如果routeDataList有数据，使用第一条路线数据
                if (routeDataList != null && routeDataList.isNotEmpty()) {
                    val firstRoute = routeDataList[0]
                    Log.i(TAG, "✅ 从routeDataList[0]获取数据")
                    
                    // 递归调用，使用routeDataList中的数据
                    // 创建一个临时info对象，将firstRoute作为mainRouteData
                    // 但由于无法修改info，我们直接在这里处理firstRoute
                    
                    checkRouteDataFields(firstRoute)
                    checkDataInfoFields(info)
                    isNavigating = true
                    
                    // 获取转向图标
                    turnBitmap = try { firstRoute.nextIntersectionBitmap } catch (_: Exception) { null }
                    turnBitmapNext = try { firstRoute.nextNextIntersectionBitmap } catch (_: Exception) { null }
                    
                    // 📸 记录转向图标信息
                    logBitmapInfo("turnBitmap[routeList]", turnBitmap)
                    logBitmapInfo("turnBitmapNext[routeList]", turnBitmapNext)
                    
                    // 使用firstRoute提取数据（复用主分支的逻辑）
                    updateField { fields ->
                        // 提取NavDriveRouteData字段（使用firstRoute）
                        val nTBTDist = try { 
                            val dist = firstRoute.nextIntersectionRemainingDistance
                            Log.d(TAG, "🔍 [routeList提取] nTBTDist = $dist")
                            dist
                        } catch (e: Exception) { 
                            Log.e(TAG, "❌ [routeList提取失败] nTBTDist: ${e.message}")
                            0 
                        }
                        
                        val nRoadLimitSpeed = try { 
                            val limit = firstRoute.limitSpeed
                            Log.d(TAG, "🔍 [routeList提取] nRoadLimitSpeed = $limit")
                            limit
                        } catch (e: Exception) { 
                            Log.e(TAG, "❌ [routeList提取失败] nRoadLimitSpeed: ${e.message}")
                            0 
                        }
                        
                        val nGoPosDist = try { 
                            val dist = firstRoute.remainingDist
                            Log.d(TAG, "🔍 [routeList提取] nGoPosDist = $dist")
                            dist
                        } catch (e: Exception) { 
                            Log.e(TAG, "❌ [routeList提取失败] nGoPosDist: ${e.message}")
                            0 
                        }
                        
                        val nGoPosTime = try { 
                            val time = firstRoute.remainingTimeInSeconds
                            Log.d(TAG, "🔍 [routeList提取] nGoPosTime = $time")
                            time
                        } catch (e: Exception) { 
                            try { 
                                val time = firstRoute.remainingTime * 60
                                Log.d(TAG, "🔍 [routeList提取] nGoPosTime = $time (from remainingTime)")
                                time
                            } catch (e2: Exception) { 
                                Log.e(TAG, "❌ [routeList提取失败] nGoPosTime: ${e2.message}")
                                0 
                            }
                        }
                        
                        val szPosRoadName = try { 
                            val name = firstRoute.currRoadName ?: ""
                            Log.d(TAG, "🔍 [routeList提取] szPosRoadName = '$name'")
                            name
                        } catch (e: Exception) { 
                            Log.e(TAG, "❌ [routeList提取失败] szPosRoadName: ${e.message}")
                            "" 
                        }
                        
                        val szTBTMainText = try { 
                            val name = firstRoute.nextRoadName ?: ""
                            Log.d(TAG, "🔍 [routeList提取] szTBTMainText = '$name'")
                            name
                        } catch (e: Exception) { 
                            Log.e(TAG, "❌ [routeList提取失败] szTBTMainText: ${e.message}")
                            "" 
                        }
                        
                        val szFarDirName = try { 
                            val name = firstRoute.nextNextRoadName ?: ""
                            Log.d(TAG, "🔍 [routeList提取] szFarDirName = '$name'")
                            name
                        } catch (e: Exception) { 
                            Log.e(TAG, "❌ [routeList提取失败] szFarDirName: ${e.message}")
                            "" 
                        }
                        
                        val nextIntersectionType = try { 
                            val type = firstRoute.nextIntersectionType
                            Log.d(TAG, "🔍 [routeList提取] nextIntersectionType = $type")
                            type
                        } catch (e: Exception) { 
                            Log.e(TAG, "❌ [routeList提取失败] nextIntersectionType: ${e.message}")
                            -1 
                        }
                        
                        // 🆕 尝试获取转向限制信息（NavTurnRestriction）
                        val turnRestriction = try {
                            safeGet(firstRoute, "getTurnRestriction", null as Any?)
                        } catch (e: Exception) {
                            null
                        }
                        val turnRestrictionInfo = getTurnRestrictionInfo(turnRestriction)
                        if (turnRestrictionInfo.isNotEmpty()) {
                            Log.d(TAG, "🚫 [routeList转向限制] $turnRestrictionInfo")
                        }
                        
                        val nTBTTurnType = resolveNTBTTurnType(nextIntersectionType, szTBTMainText)
                        
                        // 🆕 第二转弯类型 - getNextNextIntersectionType()
                        val nextNextIntersectionType = try { 
                            val type = firstRoute.nextNextIntersectionType
                            Log.d(TAG, "🔍 [routeList提取] nextNextIntersectionType = $type")
                            type
                        } catch (e: Exception) { 
                            Log.e(TAG, "❌ [routeList提取失败] nextNextIntersectionType: ${e.message}")
                            -1 
                        }
                        
                        val nTBTTurnTypeNext = if (nextNextIntersectionType >= 0) {
                            mapIntersectionType(nextNextIntersectionType)
                        } else -1
                        
                        val passedDistance = try { firstRoute.passedDistance } catch (_: Exception) { 0 }
                        val remainingTrafficLights = try { firstRoute.remainingTrafficLightCount } catch (_: Exception) { 0 }
                        val curPointNum = try { firstRoute.currPointIdx } catch (_: Exception) { -1 }
                        
                        // 从NavDriveDataInfo提取
                        val sdkSpeed = try { info.speedKMH } catch (_: Exception) { 0 }
                        val passedTime = try { info.passedTime } catch (_: Exception) { 0 }
                        val passedDistInfo = try { info.passedDistance } catch (_: Exception) { 0 }
                        
                        // 🆕 目的地信息 - 从waypoints获取
                        val waypoints = try { info.waypoints } catch (_: Exception) { null }
                        var goalPosX = 0.0
                        var goalPosY = 0.0
                        var szGoalName = ""
                        
                        if (waypoints != null && waypoints.isNotEmpty()) {
                            // 最后一个waypoint是目的地
                            val destination = waypoints[waypoints.size - 1]
                            try {
                                // 尝试获取坐标
                                val destLatLng = safeGet(destination, "getLatLng", null as Any?)
                                if (destLatLng != null) {
                                    val lat = safeGetAny(destLatLng, 0.0, "getLatitude", "getLat")
                                    val lon = safeGetAny(destLatLng, 0.0, "getLongitude", "getLon", "getLng")
                                    if (lat != 0.0 && lon != 0.0) {
                                        // GCJ-02 → WGS-84
                                        val (wgsLat, wgsLon) = gcj02ToWgs84(lat, lon)
                                        goalPosX = wgsLon
                                        goalPosY = wgsLat
                                    }
                                }
                                // 尝试获取名称
                                szGoalName = safeGetAny(destination, "", "getName", "getTitle") ?: ""
                                Log.d(TAG, "🔍 [routeList提取] 目的地 = '$szGoalName' ($goalPosY, $goalPosX)")
                            } catch (e: Exception) {
                                Log.e(TAG, "❌ [routeList提取失败] 目的地信息: ${e.message}")
                            }
                        }
                        
                        // 构建更新
                        var updated = fields.copy(
                            isNavigating = true,
                            nTBTDist = nTBTDist,
                            nRoadLimitSpeed = nRoadLimitSpeed,
                            nGoPosDist = nGoPosDist,
                            nGoPosTime = nGoPosTime,
                            szPosRoadName = szPosRoadName,
                            szTBTMainText = szTBTMainText,
                            szNearDirName = szTBTMainText,  // 🆕 填充szNearDirName
                            szFarDirName = szFarDirName,
                            szTBTMainTextNext = szFarDirName,
                            nTBTTurnType = nTBTTurnType,
                            nTBTTurnTypeNext = nTBTTurnTypeNext,  // 🆕 第二转弯类型
                            // nTBTNextRoadWidth 由 onWillShowLaneGuide 回调更新
                            tencentSlice = fields.tencentSlice.copy(
                                tSdkIntersectionType = nextIntersectionType,
                                passedDistance = if (passedDistance > 0) passedDistance else passedDistInfo,
                                passedTime = passedTime,
                                remainingTrafficLights = remainingTrafficLights,
                            ),
                            curPointNum = curPointNum,
                            nPosSpeed = if (sdkSpeed > 0) sdkSpeed.toDouble() else fields.nPosSpeed,
                            // 🆕 目的地信息
                            goalPosX = goalPosX,
                            goalPosY = goalPosY,
                            szGoalName = szGoalName
                        )
                        
                        Log.i(TAG, "✅ [routeList字段已更新] nTBTDist=$nTBTDist, nRoadLimitSpeed=$nRoadLimitSpeed, " +
                                "nGoPosDist=$nGoPosDist, szPosRoadName='$szPosRoadName', szTBTMainText='$szTBTMainText', " +
                                "szGoalName='$szGoalName', nTBTTurnTypeNext=$nTBTTurnTypeNext")
                        
                        // 处理其他信息
                        val speedMonitorZone = try { info.navSpeedMonitorZoneInfo } catch (_: Exception) { null }
                        if (speedMonitorZone != null) {
                            updated = bridgeSpeedMonitorZone(info, updated)
                        }
                        
                        updated = bridgeTrafficLightCountDown(info, updated)
                        updated = bridgeNavigationCommands(updated)
                        updated
                    }
                    return
                }
                
                // 如果routeDataList也是空的，尝试从info直接获取（旧的fallback逻辑）
                Log.w(TAG, "⚠️ routeDataList也是空的，尝试从NavDriveDataInfo直接获取")
                
                // 模拟导航时mainRouteData为null，但info本身包含数据
                // 直接从NavDriveDataInfo获取导航数据
                checkDataInfoFields(info)
                isNavigating = true
                
                // 🔍 调试：打印info对象的所有可用方法
                val infoMethods = info.javaClass.methods
                    .map { it.name }
                    .filter { !it.startsWith("wait") && !it.startsWith("notify") && 
                             !it.startsWith("getClass") && !it.startsWith("hash") &&
                             !it.startsWith("equals") && !it.startsWith("toString") }
                    .sorted()
                Log.i(TAG, "📋 NavDriveDataInfo 所有方法: ${infoMethods.joinToString(", ")}")
                
                updateField { fields ->
                    var updated = fields.copy(isNavigating = true)
                    
                    // 从NavDriveDataInfo直接获取数据（模拟导航模式）
                    // 根据SDK文档，这些方法在NavDriveDataInfo上直接可用
                    
                    // 剩余距离
                    val remainDist = safeGetAny(info, 0, "getRemainingDistance", "getRemainingDist")
                    if (remainDist > 0) {
                        updated = updated.copy(nGoPosDist = remainDist)
                        Log.d(TAG, "🔍 [Info] nGoPosDist = $remainDist")
                    }
                    
                    // 剩余时间
                    val remainTime = safeGetAny(info, 0, "getRemainingTime", "getRemainingTimeInSeconds")
                    if (remainTime > 0) {
                        // getRemainingTime可能返回分钟或秒，需要判断
                        val timeInSeconds = if (remainTime > 1000) remainTime else remainTime * 60
                        updated = updated.copy(nGoPosTime = timeInSeconds)
                        Log.d(TAG, "🔍 [Info] nGoPosTime = $timeInSeconds")
                    }
                    
                    // 当前道路名
                    val currRoad = safeGetAny(info, "", "getCurrentRoadName", "getCurrRoadName") ?: ""
                    if (currRoad.isNotEmpty()) {
                        updated = updated.copy(szPosRoadName = currRoad)
                        Log.d(TAG, "🔍 [Info] szPosRoadName = $currRoad")
                    }
                    
                    // 下一道路名
                    val nextRoad = safeGetAny(info, "", "getNextRoadName") ?: ""
                    if (nextRoad.isNotEmpty()) {
                        updated = updated.copy(
                            szTBTMainText = nextRoad,
                            szNearDirName = nextRoad
                        )
                        Log.d(TAG, "🔍 [Info] szTBTMainText = $nextRoad")
                    }
                    
                    // 道路限速
                    val limitSpeed = safeGetAny(info, 0, "getLimitSpeed", "getCurrentSpeedLimit")
                    if (limitSpeed > 0) {
                        updated = updated.copy(nRoadLimitSpeed = limitSpeed)
                        Log.d(TAG, "🔍 [Info] nRoadLimitSpeed = $limitSpeed")
                    }
                    
                    // 转弯距离
                    val turnDist = safeGetAny(info, 0, "getNextIntersectionRemainingDistance", 
                        "getNextIntersectionDistance", "getTurnDistance")
                    if (turnDist > 0) {
                        updated = updated.copy(nTBTDist = turnDist)
                        Log.d(TAG, "🔍 [Info] nTBTDist = $turnDist")
                    }
                    
                    // 转弯类型
                    val turnType = safeGetAny(info, -1, "getNextIntersectionType", "getTurnType")
                    val resolvedTurnType = resolveNTBTTurnType(turnType, nextRoad)
                    if (resolvedTurnType != -1) {
                        updated = updated.copy(
                            nTBTTurnType = resolvedTurnType,
                            tencentSlice = updated.tencentSlice.copy(tSdkIntersectionType = turnType)  // 原始 SDK 值
                        )
                        Log.d(TAG, "🔍 [Info] nTBTTurnType = $resolvedTurnType (SDK=$turnType, text='$nextRoad')")
                    }
                    
                    // 目的地信息
                    val dest = safeGet(info, "getDestination", null as Any?)
                    if (dest != null) {
                        val destLat = safeGetAny(dest, 0.0, "getLatitude", "getLat")
                        val destLon = safeGetAny(dest, 0.0, "getLongitude", "getLon", "getLng")
                        val destName = safeGetAny(dest, "", "getName", "getTitle") ?: ""
                        
                        if (destLat != 0.0 && destLon != 0.0) {
                            // 腾讯SDK坐标是GCJ-02，转换为WGS-84
                            val (wgsLat, wgsLon) = gcj02ToWgs84(destLat, destLon)
                            updated = updated.copy(
                                goalPosX = wgsLon,
                                goalPosY = wgsLat,
                                szGoalName = destName
                            )
                            Log.d(TAG, "🔍 [Info] 目的地 = $destName ($wgsLat, $wgsLon)")
                        }
                    }
                    
                    // 区间测速信息
                    if (hasGetNavSpeedMonitorZoneInfo) {
                        updated = bridgeSpeedMonitorZone(info, updated)
                    }
                    
                    // 红绿灯倒计时
                    updated = bridgeTrafficLightCountDown(info, updated)
                    
                    updated
                }
                return
            }

            // 首次回调时探测可用字段（保留用于兼容性检查）
            checkRouteDataFields(mainRoute)
            checkDataInfoFields(info)

            isNavigating = true

            // 获取转向图标（SDK官方API）
            turnBitmap = try { mainRoute.nextIntersectionBitmap } catch (_: Exception) { null }
            turnBitmapNext = try { mainRoute.nextNextIntersectionBitmap } catch (_: Exception) { null }
            
            // 📸 记录转向图标信息（用于调试SDK intersectionType与实际图标的对应关系）
            logBitmapInfo("turnBitmap", turnBitmap)
            logBitmapInfo("turnBitmapNext", turnBitmapNext)

            updateField { fields ->
                // ========== 严格按照SDK官方文档映射 NavDriveRouteData 字段 ==========
                
                // 1. 转弯距离 - getNextIntersectionRemainingDistance()
                val nTBTDist = try { 
                    val dist = mainRoute.nextIntersectionRemainingDistance
                    Log.d(TAG, "🔍 [提取] nTBTDist = $dist")
                    dist
                } catch (e: Exception) { 
                    Log.e(TAG, "❌ [提取失败] nTBTDist: ${e.message}")
                    0 
                }
                
                // 2. 道路限速 - getLimitSpeed()
                val nRoadLimitSpeed = try { 
                    val limit = mainRoute.limitSpeed
                    Log.d(TAG, "🔍 [提取] nRoadLimitSpeed = $limit")
                    limit
                } catch (e: Exception) { 
                    Log.e(TAG, "❌ [提取失败] nRoadLimitSpeed: ${e.message}")
                    0 
                }
                
                // 3. 剩余距离 - getRemainingDist()
                val nGoPosDist = try { 
                    val dist = mainRoute.remainingDist
                    Log.d(TAG, "🔍 [提取] nGoPosDist = $dist")
                    dist
                } catch (e: Exception) { 
                    Log.e(TAG, "❌ [提取失败] nGoPosDist: ${e.message}")
                    0 
                }
                
                // 4. 剩余时间（秒）- getRemainingTimeInSeconds()
                val nGoPosTime = try { 
                    val time = mainRoute.remainingTimeInSeconds
                    Log.d(TAG, "🔍 [提取] nGoPosTime = $time")
                    time
                } catch (e: Exception) { 
                    try { 
                        val time = mainRoute.remainingTime * 60
                        Log.d(TAG, "🔍 [提取] nGoPosTime = $time (from remainingTime)")
                        time
                    } catch (e2: Exception) { 
                        Log.e(TAG, "❌ [提取失败] nGoPosTime: ${e2.message}")
                        0 
                    }
                }
                
                // 5. 当前道路名 - getCurrRoadName()
                val szPosRoadName = try { 
                    val name = mainRoute.currRoadName ?: ""
                    Log.d(TAG, "🔍 [提取] szPosRoadName = '$name'")
                    name
                } catch (e: Exception) { 
                    Log.e(TAG, "❌ [提取失败] szPosRoadName: ${e.message}")
                    "" 
                }
                
                // 6. 下一道路名 - getNextRoadName()
                val szTBTMainText = try { 
                    val name = mainRoute.nextRoadName ?: ""
                    Log.d(TAG, "🔍 [提取] szTBTMainText = '$name'")
                    name
                } catch (e: Exception) { 
                    Log.e(TAG, "❌ [提取失败] szTBTMainText: ${e.message}")
                    "" 
                }
                
                // 7. 下下道路名 - getNextNextRoadName()
                val szFarDirName = try { 
                    val name = mainRoute.nextNextRoadName ?: ""
                    Log.d(TAG, "🔍 [提取] szFarDirName = '$name'")
                    name
                } catch (e: Exception) { 
                    Log.e(TAG, "❌ [提取失败] szFarDirName: ${e.message}")
                    "" 
                }
                
                // 8. 转弯类型 - getNextIntersectionType()
                val nextIntersectionType = try { 
                    val type = mainRoute.nextIntersectionType
                    Log.d(TAG, "🔍 [提取] nextIntersectionType = $type")
                    type
                } catch (e: Exception) { 
                    Log.e(TAG, "❌ [提取失败] nextIntersectionType: ${e.message}")
                    -1 
                }
                
                // 🆕 尝试获取转向限制信息（NavTurnRestriction）
                val turnRestriction = try {
                    safeGet(mainRoute, "getTurnRestriction", null as Any?)
                } catch (e: Exception) {
                    null
                }
                val turnRestrictionInfo = getTurnRestrictionInfo(turnRestriction)
                if (turnRestrictionInfo.isNotEmpty()) {
                    Log.d(TAG, "🚫 [转向限制] $turnRestrictionInfo")
                }
                
                val nTBTTurnType = resolveNTBTTurnType(nextIntersectionType, szTBTMainText)
                Log.d(TAG, "🔍 [映射] nTBTTurnType = $nTBTTurnType (SDK=$nextIntersectionType, text='$szTBTMainText')")
                // 📸 关联日志：SDK type + bitmap + 最终映射
                if (turnBitmap != null) {
                    Log.i(TAG, "📸 [关联] SDK_type=$nextIntersectionType → nTBTTurnType=$nTBTTurnType, text='$szTBTMainText', bitmap=${turnBitmap?.width}x${turnBitmap?.height}")
                }
                
                // 9. 第二转弯类型 - getNextNextIntersectionType()
                val nextNextIntersectionType = try { 
                    val type = mainRoute.nextNextIntersectionType
                    Log.d(TAG, "🔍 [提取] nextNextIntersectionType = $type")
                    type
                } catch (e: Exception) { 
                    Log.e(TAG, "❌ [提取失败] nextNextIntersectionType: ${e.message}")
                    -1 
                }
                val nTBTTurnTypeNext = if (nextNextIntersectionType >= 0) {
                    mapIntersectionType(nextNextIntersectionType)
                } else -1
                
                // 10. 已驶过距离 - getPassedDistance()
                val passedDistance = try { 
                    val dist = mainRoute.passedDistance
                    Log.d(TAG, "🔍 [提取] passedDistance = $dist")
                    dist
                } catch (e: Exception) { 
                    Log.e(TAG, "❌ [提取失败] passedDistance: ${e.message}")
                    0 
                }
                
                // 11. 剩余红绿灯数 - getRemainingTrafficLightCount()
                val remainingTrafficLights = try { 
                    val count = mainRoute.remainingTrafficLightCount
                    Log.d(TAG, "🔍 [提取] remainingTrafficLights = $count")
                    count
                } catch (e: Exception) { 
                    Log.e(TAG, "❌ [提取失败] remainingTrafficLights: ${e.message}")
                    0 
                }
                
                // 12. 当前点索引 - getCurrPointIdx()
                val curPointNum = try { 
                    val idx = mainRoute.currPointIdx
                    Log.d(TAG, "🔍 [提取] curPointNum = $idx")
                    idx
                } catch (e: Exception) { 
                    Log.e(TAG, "❌ [提取失败] curPointNum: ${e.message}")
                    -1 
                }
                
                // 13. 路线交通数据 - getTrafficItems()
                val trafficItems = try { mainRoute.trafficItems } catch (_: Exception) { null }

                // ========== 严格按照SDK官方文档映射 NavDriveDataInfo 字段 ==========
                
                // 1. 当前速度 - getSpeedKMH()
                val sdkSpeed = try { info.speedKMH } catch (_: Exception) { 0 }
                if (sdkSpeed > 0) {
                    sdkSpeedKMH = sdkSpeed
                }
                
                // 2. 是否超速 - isOverSpeed()
                sdkIsOverSpeed = try { info.isOverSpeed } catch (_: Exception) { false }
                
                // 3. 超速类型 - getOverSpeedType()
                val overSpeedType = try { info.overSpeedType } catch (_: Exception) { null }
                sdkOverSpeedType = when (overSpeedType?.toString()) {
                    "ZONE", "MONITOR_ZONE" -> 2  // 区间超速
                    "NORMAL", "SPEED" -> 1        // 普通超速
                    "NONE" -> 0
                    else -> if (sdkIsOverSpeed) 1 else 0
                }
                
                // 4. 高速出口名 - getHighwayExitName()
                val exitName = try { info.highwayExitName ?: "" } catch (_: Exception) { "" }
                
                // 5. 高速入口名 - getHighwayEntranceName()
                val entranceName = try { info.highwayEntranceName ?: "" } catch (_: Exception) { "" }
                
                // 6. 已驶过距离（Info级别）- getPassedDistance()
                val passedDistInfo = try { info.passedDistance } catch (_: Exception) { 0 }
                
                // 7. 已驶过时间 - getPassedTime()
                val passedTime = try { info.passedTime } catch (_: Exception) { 0 }
                
                // 8. 区间测速信息 - getNavSpeedMonitorZoneInfo()
                val speedMonitorZone = try { info.navSpeedMonitorZoneInfo } catch (_: Exception) { null }
                
                // 9. 放大图信息 - getNavEnlargedMapInfo()
                val enlargedMapInfo = try { info.navEnlargedMapInfo } catch (_: Exception) { null }
                
                // 10. 服务区信息 - getHighwayFacilities()
                val facilities = try { info.highwayFacilities } catch (_: Exception) { null }
                
                // 11. 限行信息 - getRestrictionInfo()
                val restrictionInfo = try { info.restrictionInfo } catch (_: Exception) { null }
                
                // 12. 途经点 - getWaypoints()
                val waypoints = try { info.waypoints } catch (_: Exception) { null }
                
                // 🆕 目的地信息 - 从waypoints获取
                var goalPosX = 0.0
                var goalPosY = 0.0
                var szGoalName = ""
                
                if (waypoints != null && waypoints.isNotEmpty()) {
                    val destination = waypoints[waypoints.size - 1]
                    try {
                        val destLatLng = safeGet(destination, "getLatLng", null as Any?)
                        if (destLatLng != null) {
                            val lat = safeGetAny(destLatLng, 0.0, "getLatitude", "getLat")
                            val lon = safeGetAny(destLatLng, 0.0, "getLongitude", "getLon", "getLng")
                            if (lat != 0.0 && lon != 0.0) {
                                val (wgsLat, wgsLon) = gcj02ToWgs84(lat, lon)
                                goalPosX = wgsLon
                                goalPosY = wgsLat
                            }
                        }
                        szGoalName = safeGetAny(destination, "", "getName", "getTitle") ?: ""
                        Log.d(TAG, "🔍 [提取] 目的地 = '$szGoalName' ($goalPosY, $goalPosX)")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ [提取失败] 目的地信息: ${e.message}")
                    }
                }

                // ========== 构建更新后的字段 ==========
                var updated = fields.copy(
                    isNavigating = true,
                    // NavDriveRouteData 字段
                    nTBTDist = nTBTDist,
                    nRoadLimitSpeed = nRoadLimitSpeed,
                    nGoPosDist = nGoPosDist,
                    nGoPosTime = nGoPosTime,
                    szPosRoadName = szPosRoadName,
                    szTBTMainText = szTBTMainText,
                    szNearDirName = szTBTMainText,  // 同时填充
                    szFarDirName = szFarDirName,
                    szTBTMainTextNext = szFarDirName,
                    nTBTTurnType = nTBTTurnType,
                    nTBTTurnTypeNext = nTBTTurnTypeNext,  // 🆕 第二转弯类型
                    // nTBTNextRoadWidth 由 onWillShowLaneGuide 回调更新
                    tencentSlice = fields.tencentSlice.copy(
                        tSdkIntersectionType = nextIntersectionType,
                        passedDistance = if (passedDistance > 0) passedDistance else passedDistInfo,
                        passedTime = passedTime,
                        remainingTrafficLights = remainingTrafficLights,
                    ),
                    curPointNum = curPointNum,
                    // NavDriveDataInfo 字段
                    nPosSpeed = if (sdkSpeed > 0) sdkSpeed.toDouble() else fields.nPosSpeed,
                    exitNameInfo = exitName,
                    // 🆕 目的地信息
                    goalPosX = goalPosX,
                    goalPosY = goalPosY,
                    szGoalName = szGoalName
                )

                // 🔍 关键调试：确认字段已更新
                Log.i(TAG, "✅ [字段已更新] nTBTDist=$nTBTDist, nRoadLimitSpeed=$nRoadLimitSpeed, " +
                        "nGoPosDist=$nGoPosDist, nGoPosTime=$nGoPosTime")
                Log.i(TAG, "✅ [字段已更新] szPosRoadName='$szPosRoadName', szTBTMainText='$szTBTMainText', " +
                        "nTBTTurnType=$nTBTTurnType, szGoalName='$szGoalName'")

                // 调试日志（每5秒输出一次）
                if (System.currentTimeMillis() % 5000 < 200) {
                    Log.d(TAG, "📊 腾讯导航数据: dist=$nTBTDist, limit=$nRoadLimitSpeed, " +
                            "remain=$nGoPosDist, time=$nGoPosTime, road=$szPosRoadName, " +
                            "turn=$szTBTMainText, type=$nTBTTurnType")
                }

                // ========== 处理区间测速信息 ==========
                if (speedMonitorZone != null) {
                    updated = bridgeSpeedMonitorZone(info, updated)
                }

                // ========== 处理放大图信息 ==========
                if (enlargedMapInfo != null) {
                    updated = bridgeEnlargedMapInfo(info, updated)
                }

                // ========== 处理服务区信息 ==========
                if (facilities != null && facilities.isNotEmpty()) {
                    updated = bridgeHighwayFacilities(info, updated)
                }

                // ========== 处理交通拥堵数据 ==========
                if (trafficItems != null && trafficItems.isNotEmpty()) {
                    updated = bridgeTrafficItems(mainRoute, updated)
                }

                // ========== 处理限行信息 ==========
                if (restrictionInfo != null) {
                    updated = bridgeRestrictionInfo(info, updated)
                }

                // ========== 处理红绿灯倒计时 ==========
                updated = bridgeTrafficLightCountDown(info, updated)

                // ========== 从限速+道路名推断道路类型 ==========
                if (updated.tencentSlice.roadGrade < 0 && updated.tencentSlice.roadKind < 0) {
                    if (nRoadLimitSpeed > 0 || szPosRoadName.isNotEmpty()) {
                        updated = updated.copy(
                            roadcate = inferRoadcateFromSpeedLimit(
                                nRoadLimitSpeed, updated.roadcate, szPosRoadName
                            )
                        )
                    }
                }

                // ========== 桥接导航指令到Comma3 ==========
                updated = bridgeNavigationCommands(updated)
                updated
            }
        }

        override fun onWillShowLaneGuide(laneInfo: NavLaneInfo?) {
            super.onWillShowLaneGuide(laneInfo)
            laneInfo ?: return
            // 保存车道引导位图供UI显示
            laneBitmap = try { laneInfo.guideLaneBitmap } catch (_: Exception) { null }

            // 🆕 提取车道数和车道级别信息
            try {
                val laneCount = safeGet(laneInfo, "getNum", 0)
                val items = safeGet<List<*>?>(laneInfo, "getItems", null)
                val laneInfoList = mutableListOf<LaneInfo>()
                items?.forEachIndexed { idx, item ->
                    if (item != null) {
                        val name = safeGet(item, "getName", "") ?: ""
                        // LaneItem.recommend 是 public field，用反射读取
                        val recommend = try {
                            item.javaClass.getField("recommend").getBoolean(item)
                        } catch (_: Exception) {
                            try { item.javaClass.getField("name"); false } catch (_: Exception) { false }
                        }
                        laneInfoList.add(LaneInfo(id = name, isRecommended = recommend))
                    }
                }
                updateField { fields ->
                    fields.copy(
                        laneCount = if (laneCount > 0) laneCount else laneInfoList.size,
                        nTBTNextRoadWidth = if (laneCount > 0) laneCount else laneInfoList.size,
                        laneInfoList = laneInfoList
                    )
                }
                if (laneInfoList.isNotEmpty()) {
                    Log.d(TAG, "🛤️ 车道信息: ${laneInfoList.size}条, 推荐=${laneInfoList.filter { it.isRecommended }.map { it.id }}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "车道信息解析失败: ${e.message}")
            }
        }

        override fun onHideLaneGuide() {
            super.onHideLaneGuide()
            laneBitmap = null
            updateField { it.copy(laneInfoList = emptyList(), laneCount = 0) }
        }

        /**
         * 电子眼信息回调
         * NavCameraInfo: getCameraType(), getLimitSpeedKMPH(), getDistance(), getSpeedZoneLimitLength()
         * 
         * 🆕 SDK回调传入List<NavCameraInfo>，可能包含多个电子眼：
         * - cameraInfoList[0] → 主SDI字段 (nSdiType/SpeedLimit/Dist)
         * - cameraInfoList[1] → Plus SDI字段 (nSdiPlusType/SpeedLimit/Dist)
         * 这样可以同时报告前方两个电子眼（如：固定测速 + 减速带）
         * 
         * 🔑 清空逻辑（参考高德）：
         * - 当距离 <= 20米时，清空所有SDI字段（表示已通过摄像头）
         * - 当SDK不再回调（cameraInfoList为空）时，也清空字段
         */
        override fun onCameraInfoUpdate(cameraInfoList: MutableList<com.tencent.navix.api.model.NavCameraInfo>) {
            super.onCameraInfoUpdate(cameraInfoList)
            
            // SDK不再提供摄像头信息时，清空所有字段
            if (cameraInfoList.isEmpty()) {
                Log.d(TAG, "🧹 摄像头列表为空，清空所有SDI字段")
                updateField { fields ->
                    fields.copy(
                        tencentSlice = fields.tencentSlice.copy(
                            tCameraType = -1,
                            tCameraDist = 0,
                            tCameraSpeedLimit = 0,
                        ),
                        nSdiType = -1,
                        nSdiSpeedLimit = 0,
                        nSdiDist = 0,
                        nSdiBlockType = -1,
                        nSdiBlockSpeed = 0,
                        nSdiBlockDist = 0,
                        nSdiPlusType = -1,
                        nSdiPlusSpeedLimit = 0,
                        nSdiPlusDist = 0,
                        nSdiPlusBlockType = -1,
                        nSdiPlusBlockSpeed = 0,
                        nSdiPlusBlockDist = 0
                    )
                }
                return
            }
            
            try {
                val cameraInfo = cameraInfoList[0]
                val cType = safeGet(cameraInfo, "getCameraType", -1)
                val cDist = safeGet(cameraInfo, "getDistance", 0)
                // 🔑 SDK v7.5.0 方法名是 getLimitSpeedKMPH()，不是 getSpeedLimit()
                val cLimit = safeGetAny(cameraInfo, 0, "getLimitSpeedKMPH", "getSpeedLimit")
                // 🆕 区间测速长度
                val zoneLength = safeGet(cameraInfo, "getSpeedZoneLimitLength", 0)

                // 🔑 判断是否应该清空SDI（参考高德：距离 <= 20米表示已通过）
                val shouldClearSdi = cDist <= 20

                // 电子眼信息仅在距离较近时打日志（避免远距离频繁刷屏）
                if (cDist in 1..500) {
                    Log.d(TAG, "📷 电子眼: type=$cType(${cameraTypeName(cType)}), dist=${cDist}m, limit=${cLimit}km/h")
                }

                updateField { fields ->
                    // 始终更新腾讯原始字段（用于调试）
                    var updated = fields.copy(
                        tencentSlice = fields.tencentSlice.copy(
                            tCameraType = if (shouldClearSdi) -1 else cType,
                            tCameraDist = if (shouldClearSdi) 0 else cDist,
                            tCameraSpeedLimit = if (shouldClearSdi) 0 else cLimit
                        )
                    )
                    
                    if (shouldClearSdi) {
                        // 🧹 已通过摄像头，清空所有SDI字段
                        Log.d(TAG, "🧹 摄像头已通过(距离=${cDist}m ≤ 20m)，清空SDI字段")
                        updated = updated.copy(
                            nSdiType = -1,
                            nSdiSpeedLimit = 0,
                            nSdiDist = 0,
                            nSdiBlockType = -1,
                            nSdiBlockSpeed = 0,
                            nSdiBlockDist = 0
                        )
                    } else if (cDist > 0) {
                        // 📷 前方有摄像头，映射到Python SDI字段
                        val sdiType = mapCameraTypeToSdiType(cType, zoneLength)
                        if (sdiType > 0) {
                            updated = updated.copy(
                                nSdiType = sdiType,
                                nSdiSpeedLimit = cLimit,
                                nSdiDist = cDist
                            )
                            Log.d(TAG, "📷 SDI映射: 腾讯type=$cType → Python sdi=$sdiType, dist=${cDist}m, limit=${cLimit}km/h")
                        }
                        // 如果是区间测速（QujianEnter=9 或 zoneLength>0），填充区间字段
                        if (zoneLength > 0 || cType == 9) {
                            updated = updated.copy(
                                nSdiBlockType = 1,          // 区间开始
                                nSdiBlockSpeed = cLimit,
                                nSdiBlockDist = if (zoneLength > 0) zoneLength else cDist
                            )
                            Log.d(TAG, "🚦 区间测速开始: 限速=${cLimit}km/h, 长度=${if (zoneLength > 0) zoneLength else cDist}m")
                        } else if (cType == 10) {
                            // QujianExit 区间测速出口
                            updated = updated.copy(
                                nSdiBlockType = 3,          // 区间结束
                                nSdiBlockSpeed = cLimit,
                                nSdiBlockDist = 0
                            )
                            Log.d(TAG, "🚦 区间测速结束")
                        }
                    }

                    // 🆕 第二个电子眼 → nSdiPlus 字段
                    // Python用途: nSdiPlusType==22 时触发减速带减速
                    if (cameraInfoList.size >= 2) {
                        val cam2 = cameraInfoList[1]
                        val c2Type = safeGet(cam2, "getCameraType", -1)
                        val c2Dist = safeGet(cam2, "getDistance", 0)
                        val c2Limit = safeGetAny(cam2, 0, "getLimitSpeedKMPH", "getSpeedLimit")
                        val c2ZoneLen = safeGet(cam2, "getSpeedZoneLimitLength", 0)

                        // 判断第二个摄像头是否也应该清空
                        val shouldClearSdiPlus = c2Dist <= 20

                        if (shouldClearSdiPlus) {
                            // 🧹 第二个摄像头已通过
                            Log.d(TAG, "🧹 第二摄像头已通过(距离=${c2Dist}m ≤ 20m)，清空Plus字段")
                            updated = updated.copy(
                                nSdiPlusType = -1,
                                nSdiPlusSpeedLimit = 0,
                                nSdiPlusDist = 0,
                                nSdiPlusBlockType = -1,
                                nSdiPlusBlockSpeed = 0,
                                nSdiPlusBlockDist = 0
                            )
                        } else if (c2Dist > 0) {
                            // 📷 前方有第二个摄像头
                            val plusSdiType = mapCameraTypeToSdiType(c2Type, c2ZoneLen)
                            updated = updated.copy(
                                nSdiPlusType = plusSdiType,
                                nSdiPlusSpeedLimit = c2Limit,
                                nSdiPlusDist = c2Dist
                            )
                            // Plus区间测速
                            if (c2ZoneLen > 0) {
                                updated = updated.copy(
                                    nSdiPlusBlockType = 1,
                                    nSdiPlusBlockSpeed = c2Limit,
                                    nSdiPlusBlockDist = c2ZoneLen
                                )
                            }
                            Log.d(TAG, "📷 第二电子眼: type=$c2Type(${cameraTypeName(c2Type)}) → sdi=$plusSdiType, dist=${c2Dist}m, limit=${c2Limit}km/h")
                        }
                    } else {
                        // 只有一个电子眼时清除Plus字段
                        updated = updated.copy(
                            nSdiPlusType = -1,
                            nSdiPlusSpeedLimit = 0,
                            nSdiPlusDist = 0,
                            nSdiPlusBlockType = -1,
                            nSdiPlusBlockSpeed = 0,
                            nSdiPlusBlockDist = 0
                        )
                    }

                    // 电子眼限速 < 500m 时覆盖道路限速（仅当未通过时）
                    // ⚠️ xSpdLimit/desiredSpeed 字段已删除
                    updated
                }
            } catch (e: Exception) {
                Log.w(TAG, "电子眼信息解析失败: ${e.message}")
            }
        }

        /**
         * 🆕 主辅路切换回调
         * NavParallelRoadStatus: getCurrentRoadType(), getFirstHintType(), getSecHintType()
         * 
         * NavRoadType 枚举（SDK v7.5.0）：
         *   MainRoad / ServingRoad / Elevated / Downstairs /
         *   DownstairsMainRoad / DownstairsServingRoad / DirectionRoad / None
         * 
         * 除了更新 isOnMainRoad，还利用 NavRoadType 辅助推断 roadcate：
         * - Elevated（高架/桥上）→ roadcate=10（高速/快速路）
         * - ServingRoad（辅路）→ roadcate=6（普通道路）
         */
        override fun onUpdateParallelRoad(parallelRoadStatus: com.tencent.navix.api.model.NavParallelRoadStatus?) {
            super.onUpdateParallelRoad(parallelRoadStatus)
            parallelRoadStatus ?: return
            try {
                val currentType = safeGet(parallelRoadStatus, "getCurrentRoadType", null as Any?)
                val firstHint = safeGet(parallelRoadStatus, "getFirstHintType", null as Any?)
                val secHint = safeGet(parallelRoadStatus, "getSecHintType", null as Any?)

                // NavRoadType 枚举：通过 toString() 或 name() 判断
                val currentTypeName = currentType?.toString() ?: "UNKNOWN"
                
                // 获取 NavRoadType.asValue() 整数值（用于日志和精确判断）
                val currentTypeValue = if (currentType != null) {
                    safeGet(currentType, "asValue", -1)
                } else -1

                // 主辅路判断：ServingRoad / DownstairsServingRoad 为辅路
                val isMainRoad = !currentTypeName.contains("Serving", ignoreCase = true)

                val canSwitchMain = firstHint?.toString()?.contains("Main", ignoreCase = true) == true
                        || secHint?.toString()?.contains("Main", ignoreCase = true) == true
                val canSwitchSide = firstHint?.toString()?.contains("Serving", ignoreCase = true) == true
                        || secHint?.toString()?.contains("Serving", ignoreCase = true) == true

                Log.i(TAG, "🛤️ 主辅路: type=$currentTypeName(val=$currentTypeValue), " +
                        "hint1=$firstHint, hint2=$secHint → main=$isMainRoad")

                updateField { fields ->
                    // 利用 NavRoadType 辅助推断 roadcate
                    val newRoadcate = mapNavRoadTypeToRoadcate(currentTypeName, fields.roadcate)
                    if (newRoadcate != fields.roadcate) {
                        Log.i(TAG, "🛣️ NavRoadType→roadcate: $currentTypeName → $newRoadcate (was ${fields.roadcate})")
                    }
                    fields.copy(
                        tencentSlice = fields.tencentSlice.copy(
                            isOnMainRoad = isMainRoad,
                            canSwitchToMainRoad = canSwitchMain,
                            canSwitchToSideRoad = canSwitchSide,
                        ),
                        roadcate = newRoadcate
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "主辅路状态解析失败: ${e.message}")
            }
        }

        /**
         * 🆕 收费站信息回调 — Feature 6: 提取收费站名称和费用
         */
        override fun onTollStationInfoUpdate(tollStationInfo: com.tencent.navix.api.model.NavTollStationInfo?) {
            super.onTollStationInfoUpdate(tollStationInfo)
            tollStationInfo ?: return
            try {
                val entranceName = safeGet(tollStationInfo, "getEntranceStationName", "") ?: ""
                val exitName = safeGet(tollStationInfo, "getExitStationName", "") ?: ""
                val fee = safeGet(tollStationInfo, "getFee", 0)

                Log.d(TAG, "🏗️ 收费站: 入口=$entranceName, 出口=$exitName, 费用=${fee}分")

                updateField { it.copy(
                    tencentSlice = it.tencentSlice.copy(
                        tollEntranceName = entranceName,
                        tollExitName = exitName,
                        tollFee = if (fee > 0) fee else it.tencentSlice.tollFee
                    )
                ) }
            } catch (e: Exception) {
                Log.w(TAG, "收费站信息解析失败: ${e.message}")
            }
        }

        /**
         * 🆕 TTS播报回调 — Feature 5: 用TTS文本辅助验证/补充转弯类型推断
         * NavTTSInfo: getContent()/getText(), getBeepType(), getPriority(), getType()
         */
        override fun onPlayTTS(navTTSInfo: com.tencent.navix.api.model.NavTTSInfo?) {
            super.onPlayTTS(navTTSInfo)
            navTTSInfo ?: return
            try {
                val ttsText = safeGetAny(navTTSInfo, "", "getContent", "getText") ?: ""
                if (ttsText.isEmpty()) return

                val beepType = safeGet(navTTSInfo, "getBeepType", -1)
                val priority = safeGet(navTTSInfo, "getPriority", -1)
                val ttsType = safeGet(navTTSInfo, "getType", -1)
                Log.d(TAG, "🔊 TTS: $ttsText (beep=$beepType, pri=$priority, type=$ttsType)")

                // 用TTS文本辅助推断转弯类型（当SDK枚举返回-1时作为补充）
                val currentFields = carrotManFieldsState?.value ?: return
                if (currentFields.nTBTTurnType == -1 || currentFields.nTBTTurnType == 51) {
                    val inferred = inferTurnTypeFromText(ttsText)
                    if (inferred != -1 && inferred != 51) {
                        updateField { it.copy(nTBTTurnType = inferred) }
                        Log.d(TAG, "🔊 TTS辅助推断转弯类型: $inferred from '$ttsText'")
                    }
                }
            } catch (_: Exception) { /* ignore */ }
        }

        /**
         * 🆕 导航定位信息回调 — 提供道路吸附坐标（比原始GPS更精确）
         * NavLocationInfo → NavRouteLocation → NavLocation(吸附点)
         * 吸附点是SDK将原始GPS投射到道路上的坐标，用于 vpPosPointLat/Lon
         */
        override fun onNavLocationInfoUpdate(navLocationInfo: NavLocationInfo?) {
            super.onNavLocationInfoUpdate(navLocationInfo)
            navLocationInfo ?: return
            try {
                // 获取主路线吸附信息
                val mainRouteLoc = navLocationInfo.mainRouteLocation ?: return
                // 检查点是否合法
                val isValid = try { mainRouteLoc.isValid } catch (_: Exception) { true }
                if (!isValid) return

                // 获取吸附点（道路投射坐标）
                val adsorbLoc = safeGet(mainRouteLoc, "getAdsorbLocation", null as Any?)
                if (adsorbLoc != null) {
                    val latLng = safeGet(adsorbLoc, "getLatLng", null as Any?)
                    if (latLng != null) {
                        val lat = safeGet(latLng, "getLatitude", 0.0)
                        val lon = safeGet(latLng, "getLongitude", 0.0)
                        val direction = safeGet(adsorbLoc, "getDirection", 0.0f)
                        val speed = safeGet(adsorbLoc, "getSpeed", 0.0f)

                        if (lat != 0.0 && lon != 0.0) {
                            // 🆕 腾讯SDK吸附坐标是GCJ-02，转换为WGS-84给comma3
                            val (wgsLat, wgsLon) = gcj02ToWgs84(lat, lon)
                            
                            // 🔍 调试日志
                            Log.d(TAG, "🔍 GPS坐标: GCJ($lat,$lon) → WGS($wgsLat,$wgsLon), " +
                                    "方向=${direction}°, 速度=${speed}m/s")
                            
                            updateField { fields ->
                                fields.copy(
                                    vpPosPointLat = wgsLat,
                                    vpPosPointLon = wgsLon,
                                    // 🆕 同时更新 latitude/longitude（原始GPS坐标）
                                    latitude = wgsLat,
                                    longitude = wgsLon,
                                    nPosAngle = direction.toDouble(),
                                    nPosSpeed = (speed * 3.6).toDouble()  // m/s → km/h
                                )
                            }
                        }
                    }
                }

                // 获取段索引（用于精确定位）
                val segIdx = safeGet(mainRouteLoc, "getSegIdx", -1)
                if (segIdx >= 0) {
                    updateField { it.copy(curSegNum = segIdx) }
                    // 路段切换时实时更新 roadGrade/roadKind/roadcate
                    updateRoadcateBySegIdx(segIdx)
                }
            } catch (e: Exception) {
                Log.w(TAG, "导航定位信息解析失败: ${e.message}")
            }
        }

        /**
         * 🆕 拥堵信息回调 — Feature 4: 前方拥堵预警
         * NavTrafficJamInfo: isInJamArea(), getDistance(), getDuration(), getTrafficStatus()
         * 可用于提前降低巡航速度，避免急刹
         */
        override fun onTrafficJamInfoUpdate(trafficJamInfo: com.tencent.navix.api.model.NavTrafficJamInfo?) {
            super.onTrafficJamInfoUpdate(trafficJamInfo)
            trafficJamInfo ?: return
            try {
                val inJam = safeGet(trafficJamInfo, "isInJamArea", false)
                val dist = safeGet(trafficJamInfo, "getDistance", 0)
                val duration = safeGet(trafficJamInfo, "getDuration", 0)
                val statusObj = safeGet(trafficJamInfo, "getTrafficStatus", null as Any?)
                val statusName = statusObj?.toString() ?: "Unknown"

                // 将拥堵等级映射为数值
                val statusInt = when {
                    statusName.contains("Smooth", ignoreCase = true) -> 0
                    statusName.contains("Slow", ignoreCase = true) -> 1
                    statusName.contains("Congested", ignoreCase = true) || statusName.contains("Jam", ignoreCase = true) -> 2
                    statusName.contains("Blocked", ignoreCase = true) || statusName.contains("Severe", ignoreCase = true) -> 3
                    else -> 0
                }

                if (inJam || dist > 0) {
                    Log.i(TAG, "🚗 拥堵预警: inJam=$inJam, dist=${dist}m, duration=${duration}s, status=$statusName")
                }

                updateField { it.copy(
                    tencentSlice = it.tencentSlice.copy(
                        trafficJamAhead = inJam || dist > 0,
                        trafficJamDistance = dist,
                        trafficJamDuration = duration,
                        trafficJamStatus = statusInt,
                    ),
                    // 映射到已有的态势字段
                    situationType = if (inJam || dist > 0) statusInt else it.situationType,
                    situationDistance = if (dist > 0) dist else it.situationDistance
                ) }
            } catch (e: Exception) {
                Log.w(TAG, "拥堵信息解析失败: ${e.message}")
            }
        }

        /**
         * 🆕 路口放大图展示回调 — 用于近似 nTBTDistNext
         * NavEnlargedMapInfo: isShowing(), getDistanceToMap(), getDisplayText()
         */
        override fun onShowEnlargedMap(navEnlargedMapInfo: com.tencent.navix.api.model.NavEnlargedMapInfo?) {
            super.onShowEnlargedMap(navEnlargedMapInfo)
            navEnlargedMapInfo ?: return
            try {
                val dist = safeGet(navEnlargedMapInfo, "getDistanceToMap", 0)
                val text = safeGet(navEnlargedMapInfo, "getDisplayText", "") ?: ""
                val showing = safeGet(navEnlargedMapInfo, "isShowing", false)

                if (dist > 0) {
                    Log.d(TAG, "🗺️ 路口放大图: dist=${dist}m, text=$text, showing=$showing")
                    updateField { it.copy(nTBTDistNext = dist) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "路口放大图信息解析失败: ${e.message}")
            }
        }

        /**
         * 🆕 路口放大图隐藏回调
         */
        override fun onHideEnlargedMap() {
            super.onHideEnlargedMap()
            Log.d(TAG, "🗺️ 路口放大图隐藏")
        }

        /**
         * 🆕 GPS信号质量变化回调
         * NavGpsStatusInfo 枚举: Normal / Weak / Lost 等
         */
        override fun onGPSStatusChanged(gpsStatusInfo: com.tencent.navix.api.model.NavGpsStatusInfo?) {
            super.onGPSStatusChanged(gpsStatusInfo)
            gpsStatusInfo ?: return
            try {
                val statusName = gpsStatusInfo.toString()
                val statusInt = when {
                    statusName.contains("Normal", ignoreCase = true) || statusName.contains("Good", ignoreCase = true) -> 0
                    statusName.contains("Weak", ignoreCase = true) || statusName.contains("Poor", ignoreCase = true) -> 1
                    statusName.contains("Lost", ignoreCase = true) || statusName.contains("None", ignoreCase = true) -> 2
                    else -> 0
                }
                Log.i(TAG, "📡 GPS信号状态: $statusName → $statusInt")
                updateField { it.copy(tencentSlice = it.tencentSlice.copy(gpsSignalStatus = statusInt)) }
            } catch (e: Exception) {
                Log.w(TAG, "GPS状态解析失败: ${e.message}")
            }
        }

        /**
         * 🆕 偏航回调 — 通知Python端暂停ATC控制
         */
        override fun onOffRoute() {
            super.onOffRoute()
            Log.w(TAG, "⚠️ 偏航! 等待SDK重新算路")
            updateField { it.copy(
                nTBTTurnType = -1,
                nTBTDist = 0,
                szTBTMainText = "偏航重算中..."
            ).also { result -> result.isOffRoute = true } }
        }

        /**
         * 🆕 导航开始回调
         */
        override fun onDidStartNavigation() {
            super.onDidStartNavigation()
            isNavigating = true
            Log.i(TAG, "🚀 腾讯导航已开始")
            updateField { it.copy(isNavigating = true) }
        }

        /**
         * 🆕 导航结束回调
         */
        override fun onDidStopNavigation() {
            super.onDidStopNavigation()
            Log.i(TAG, "🛑 腾讯导航已结束(SDK回调)")
            onNavigationStopped()
        }

        /**
         * 🆕 主路线变更回调
         * @param routeId 新路线ID
         * @param reason 变更原因
         */
        override fun onMainRouteDidChange(routeId: String?, reason: Int) {
            super.onMainRouteDidChange(routeId, reason)
            Log.i(TAG, "🔄 主路线变更: routeId=$routeId, reason=$reason")
            // 路线变更时重置路段缓存，等待新路线数据
            segmentRoadInfoList = emptyList()
            lastRoadcateSegIdx = -1
        }

        /**
         * 🆕 道路路况刷新回调
         * 可用于更新前方路况信息
         */
        override fun onUpdateRouteTraffic(driveNavRoutes: MutableList<com.tencent.navix.api.model.NavDriveRouteData>?) {
            super.onUpdateRouteTraffic(driveNavRoutes)
            driveNavRoutes ?: return
            if (driveNavRoutes.isEmpty()) return
            Log.d(TAG, "🚦 路况刷新: ${driveNavRoutes.size}条路线数据")
        }

        /**
         * 🆕 进入偏航态回调（开始重新算路）
         */
        override fun onStartRerouting(reason: Int) {
            super.onStartRerouting(reason)
            Log.i(TAG, "🔄 开始重新算路, reason=$reason")
        }

        /**
         * 🆕 经过途经点回调
         * NavWaypoint v7.5.0: getTrafficLightNum() 途径点红绿灯数量
         */
        override fun onArriveWaypoint(waypoint: com.tencent.navix.api.model.NavWaypoint?) {
            super.onArriveWaypoint(waypoint)
            if (waypoint == null) {
                Log.i(TAG, "📍 经过途经点: unknown")
                return
            }
            val trafficLightNum = safeGet(waypoint, "getTrafficLightNum", -1)
            val waypointStr = waypoint.toString()
            Log.i(TAG, "📍 经过途经点: $waypointStr, 红绿灯数=$trafficLightNum")
            if (trafficLightNum >= 0) {
                updateField { it.copy(
                    tencentSlice = it.tencentSlice.copy(remainingTrafficLights = trafficLightNum)  // 更新剩余红绿灯
                ) }
            }
        }

        /**
         * 🆕 推荐路线信息回调 — SDK发现更优路线时通知
         * NavRecommendRouteInfo: 包含推荐路线ID和原因
         */
        override fun onRecommendRouteInfo(routeInfo: com.tencent.navix.api.model.NavRecommendRouteInfo?) {
            super.onRecommendRouteInfo(routeInfo)
            routeInfo ?: return
            try {
                val routeId = safeGet(routeInfo, "getRouteId", "") ?: ""
                val reason = safeGet(routeInfo, "getReason", -1)
                val reasonStr = safeGet(routeInfo, "getReasonDescription", "") ?: ""
                // 尝试获取时间/距离对比
                val timeSave = safeGet(routeInfo, "getTimeSave", 0)
                val distSave = safeGet(routeInfo, "getDistanceSave", 0)

                val infoText = buildString {
                    if (reasonStr.isNotEmpty()) append(reasonStr)
                    else append("发现更优路线")
                    if (timeSave > 0) append(" 省${timeSave / 60}分钟")
                    if (distSave > 0) append(" 近${distSave / 1000}公里")
                }

                recommendRouteInfo = infoText
                recommendRouteId = routeId
                Log.i(TAG, "🛣️ 推荐路线: $infoText (routeId=$routeId, reason=$reason)")
            } catch (e: Exception) {
                Log.w(TAG, "推荐路线信息解析失败: ${e.message}")
            }
        }

        /**
         * 🆕 日夜模式切换回调
         * NavDayNightStatus: DAY / NIGHT
         * 用于同步UI主题或通知Comma3切换夜间模式
         */
        override fun onDayNightStatusChange(dayNightStatus: com.tencent.navix.api.model.NavDayNightStatus?) {
            super.onDayNightStatusChange(dayNightStatus)
            val statusName = dayNightStatus?.toString() ?: "UNKNOWN"
            val isNight = statusName.contains("NIGHT", ignoreCase = true)
            Log.i(TAG, "🌙 日夜模式切换: $statusName (isNight=$isNight)")
            updateField { it.copy().also { result -> result.isNightMode = isNight } }
        }

        /**
         * 🆕 重新算路成功回调
         * 偏航或路况变化后SDK自动重算路，成功后更新路线数据
         */
        override fun onRerouteDidSucceed(reason: Int, navRoutePlan: com.tencent.navix.api.model.NavDriveRoutePlan?) {
            super.onRerouteDidSucceed(reason, navRoutePlan)
            val routeCount = try { navRoutePlan?.routes?.size ?: 0 } catch (_: Exception) { 0 }
            val reasonDesc = when (reason) {
                0 -> "偏航"
                1 -> "路况变化"
                2 -> "用户请求"
                else -> "未知($reason)"
            }
            Log.i(TAG, "✅ 重新算路成功: reason=$reasonDesc, 路线数=$routeCount")
            updateField { it.copy().also { result -> result.isOffRoute = false } }
        }

        /**
         * 🆕 重新算路失败回调
         * 偏航后SDK重算路失败，需要提示用户
         */
        override fun onRerouteDidFail(reason: Int, error: com.tencent.navix.api.model.NavError?) {
            super.onRerouteDidFail(reason, error)
            val errorCode = try { error?.toString() ?: "UNKNOWN" } catch (_: Exception) { "UNKNOWN" }
            Log.e(TAG, "❌ 重新算路失败: reason=$reason, error=$errorCode")
        }

        /**
         * 🆕 导航启动失败回调
         */
        override fun onDidStartNavigationFail(routeId: String?, fail: com.tencent.navix.api.model.NavNavigationStartFail?) {
            super.onDidStartNavigationFail(routeId, fail)
            val failReason = fail?.toString() ?: "UNKNOWN"
            Log.e(TAG, "❌ 导航启动失败: routeId=$routeId, reason=$failReason")
        }
    }

    // === 红绿灯倒计时数据桥接（通过反射从NavDriveDataInfo提取）===
    /** 反射探测缓存 */
    private var trafficLightReflectionChecked = false
    private var hasGetTrafficLightCountDown = false
    private var hasGetTrafficLightStatus = false
    private var hasGetTrafficLightDistance = false

    /**
     * 从 NavDriveDataInfo 中尝试提取红绿灯倒计时信息
     * SDK可能通过 NavDriveDataInfo.getTrafficLightInfo() 或类似方法提供
     * 在 onNavDataInfoUpdate 中调用
     */
    private fun bridgeTrafficLightCountDown(info: Any, fields: CarrotManFields): CarrotManFields {
        var updated = fields
        try {
            if (!trafficLightReflectionChecked) {
                trafficLightReflectionChecked = true
                val clazz = info.javaClass
                val methodNames = clazz.methods.map { it.name }
                // 查找红绿灯相关方法（NavDriveDataInfo级别）
                val tlMethods = methodNames.filter {
                    it.contains("TrafficLight", ignoreCase = true) ||
                    it.contains("trafficLight", ignoreCase = false) ||
                    it.contains("Signal", ignoreCase = true)
                }
                Log.i(TAG, "🚦 NavDriveDataInfo红绿灯API: $tlMethods")

                // 也探测 mainRouteData 上的红绿灯方法
                try {
                    val mainRoute = (info as? com.tencent.navix.api.model.NavDriveDataInfo)?.mainRouteData
                    if (mainRoute != null) {
                        val routeMethods = mainRoute.javaClass.methods.map { it.name }
                        val routeTlMethods = routeMethods.filter {
                            it.contains("TrafficLight", ignoreCase = true) ||
                            it.contains("Signal", ignoreCase = true) ||
                            it.contains("CountDown", ignoreCase = true)
                        }
                        Log.i(TAG, "🚦 NavDriveRouteData红绿灯API: $routeTlMethods")
                    }
                } catch (_: Exception) {}

                fun has(name: String) = try { clazz.getMethod(name); true } catch (_: Exception) { false }
                hasGetTrafficLightCountDown = has("getTrafficLightCountDown") || has("getTrafficLightInfo")
                        || has("getNavTrafficLightInfo") || has("getTrafficLightCountDownInfo")
                hasGetTrafficLightStatus = has("getTrafficLightStatus")
                hasGetTrafficLightDistance = has("getTrafficLightDistance")
                Log.i(TAG, "📊 红绿灯倒计时探测: countDown=$hasGetTrafficLightCountDown, " +
                        "status=$hasGetTrafficLightStatus, distance=$hasGetTrafficLightDistance")
            }

            // 方式1: 直接从 NavDriveDataInfo 获取
            if (hasGetTrafficLightCountDown) {
                val tlInfo = safeGetAny(info, null as Any?, "getTrafficLightCountDown",
                    "getTrafficLightInfo", "getNavTrafficLightInfo", "getTrafficLightCountDownInfo")
                if (tlInfo != null) {
                    // tlInfo 可能是 NavTrafficLightInfo 对象或直接是 int
                    if (tlInfo is Int || tlInfo is Long) {
                        val countdown = (tlInfo as Number).toInt()
                        if (countdown > 0) {
                            updated = updated.copy(trafficLightCountdown = countdown)
                        }
                    } else {
                        // 对象类型 — 提取字段
                        // 首次遇到时打印所有可用方法
                        val tlMethods = tlInfo.javaClass.methods.map { it.name }
                            .filter { !it.startsWith("wait") && !it.startsWith("notify") &&
                                    !it.startsWith("getClass") && !it.startsWith("hash") &&
                                    !it.startsWith("equals") && !it.startsWith("toString") }
                        Log.i(TAG, "🚦 红绿灯对象方法: $tlMethods")

                        val countdown = safeGetAny(tlInfo, 0, "getCountDown", "getRemainingTime",
                            "getSeconds", "getTime", "getCountDownTime", "getRestTime")
                        val statusObj = safeGetAny(tlInfo, null as Any?, "getStatus", "getColor",
                            "getLightColor", "getState", "getLightStatus", "getPhase")
                        val distance = safeGetAny(tlInfo, 0, "getDistance", "getDist",
                            "getDistanceToLight", "getDistToTrafficLight")

                        val statusName = statusObj?.toString() ?: ""
                        val statusInt = when {
                            statusName.contains("Red", ignoreCase = true) || statusName.contains("红") -> 1
                            statusName.contains("Green", ignoreCase = true) || statusName.contains("绿") -> 2
                            statusName.contains("Yellow", ignoreCase = true) || statusName.contains("黄") -> 3
                            statusName == "1" -> 1
                            statusName == "2" -> 2
                            statusName == "3" -> 3
                            else -> 0
                        }

                        if (countdown > 0 || statusInt > 0) {
                            Log.d(TAG, "🚦 红绿灯倒计时: ${countdown}s, 状态=$statusName($statusInt), 距离=${distance}m")
                            updated = updated.copy(
                                trafficLightCountdown = countdown,
                                trafficLightState = statusInt,
                                trafficLightDistance = if (distance > 0) distance else updated.trafficLightDistance
                            )
                        }
                    }
                }
            }

            // 方式2: 从 NavDriveDataInfo 的其他字段间接获取
            if (hasGetTrafficLightStatus) {
                val status = safeGet(info, "getTrafficLightStatus", 0)
                if (status > 0) updated = updated.copy(trafficLightState = status)
            }
            if (hasGetTrafficLightDistance) {
                val dist = safeGet(info, "getTrafficLightDistance", 0)
                if (dist > 0) updated = updated.copy(trafficLightDistance = dist)
            }

            // 方式3: 从 mainRouteData 获取（某些SDK版本红绿灯数据在RouteData上）
            try {
                val mainRoute = (info as? com.tencent.navix.api.model.NavDriveDataInfo)?.mainRouteData
                if (mainRoute != null) {
                    val tlCountdown = safeGetAny(mainRoute, 0,
                        "getTrafficLightCountDown", "getNextTrafficLightCountDown",
                        "getTrafficLightRestTime")
                    if (tlCountdown > 0 && updated.trafficLightCountdown == 0) {
                        updated = updated.copy(trafficLightCountdown = tlCountdown)
                        Log.d(TAG, "🚦 从RouteData获取红绿灯倒计时: ${tlCountdown}s")
                    }
                    val tlDist = safeGetAny(mainRoute, 0,
                        "getNextTrafficLightDistance", "getTrafficLightDistance")
                    if (tlDist > 0 && updated.trafficLightDistance == 0) {
                        updated = updated.copy(trafficLightDistance = tlDist)
                    }
                }
            } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.w(TAG, "红绿灯倒计时解析失败: ${e.message}")
        }
        return updated
    }

    /**
     * 🆕 桥接区间测速信息
     * NavSpeedMonitorZoneInfo: isInMonitorZone(), getLimitSpeed(), getAverageSpeed(),
     *   getDistanceToZoneEnd(), getStatus()
     *
     * 映射到 Python SDI 区间测速字段：
     * - nSdiBlockType: 1=开始, 2=进行中, 3=结束
     * - nSdiBlockSpeed: 区间限速
     * - nSdiBlockDist: 距区间结束距离
     */
    private fun bridgeSpeedMonitorZone(info: NavDriveDataInfo, fields: CarrotManFields): CarrotManFields {
        var updated = fields
        try {
            val zoneInfo = safeGet(info, "getNavSpeedMonitorZoneInfo", null as Any?) ?: return updated
            val inZone = safeGet(zoneInfo, "isInMonitorZone", false)
            val zoneLimitSpeed = safeGet(zoneInfo, "getLimitSpeed", 0)
            val avgSpeed = safeGet(zoneInfo, "getAverageSpeed", 0)
            val distToEnd = safeGet(zoneInfo, "getDistanceToZoneEnd", 0)
            val statusObj = safeGet(zoneInfo, "getStatus", null as Any?)
            val statusName = statusObj?.toString() ?: "Normal"

            if (inZone && zoneLimitSpeed > 0) {
                Log.i(TAG, "🚦 区间测速: 限速=${zoneLimitSpeed}km/h, 均速=${avgSpeed}km/h, " +
                        "距结束=${distToEnd}m, 状态=$statusName")

                // 区间测速中 → nSdiType=4（Python _update_sdi 中 4=区间测速中）
                updated = updated.copy(
                    nSdiType = 4,                    // 区间测速中
                    nSdiSpeedLimit = zoneLimitSpeed,
                    nSdiDist = distToEnd,
                    nSdiBlockType = 2,               // 进行中
                    nSdiBlockSpeed = zoneLimitSpeed,
                    nSdiBlockDist = distToEnd,
                    nSdiAverageSpeed = avgSpeed
                )

                // 超速警告
                if (statusName.contains("OverSpeed", ignoreCase = true)) {
                    Log.w(TAG, "⚠️ 区间测速超速! 均速=${avgSpeed}km/h > 限速=${zoneLimitSpeed}km/h")
                }
            } else if (!inZone && fields.nSdiBlockType == 2) {
                // 刚离开区间测速区域
                Log.i(TAG, "🚦 区间测速结束")
                updated = updated.copy(
                    nSdiType = 3,                    // 区间测速结束
                    nSdiBlockType = 3,
                    nSdiBlockDist = 0
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "区间测速信息解析失败: ${e.message}")
        }
        return updated
    }

    /**
     * 🆕 桥接高速服务区/收费站信息
     * NavHighwayFacility: getFacilityType() (1=服务区, 2=收费站), getName(), getDistance()
     */
    private fun bridgeHighwayFacilities(info: NavDriveDataInfo, fields: CarrotManFields): CarrotManFields {
        var updated = fields
        try {
            val facilities = safeGet<List<*>?>(info, "getHighwayFacilities", null) ?: return updated
            if (facilities.isEmpty()) return updated

            // 取最近的设施
            val nearest = facilities[0] ?: return updated
            val name = safeGet(nearest, "getName", "") ?: ""
            val dist = safeGet(nearest, "getDistance", 0)
            val type = safeGet(nearest, "getFacilityType", 0)

            if (name.isNotEmpty() && dist > 0) {
                updated = updated.copy(
                    sapaName = name,
                    sapaDist = dist,
                    sapaType = type,
                    sapaNum = facilities.size
                )
                Log.d(TAG, "🏗️ 服务区/收费站: $name, ${dist}m, type=$type, total=${facilities.size}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "服务区信息解析失败: ${e.message}")
        }
        return updated
    }

    /**
     * 🆕 Feature 2: 桥接路口放大图信息 → nTBTDistNext 近似
     * NavEnlargedMapInfo: isShowing(), getDistanceToMap(), getDisplayText()
     */
    private fun bridgeEnlargedMapInfo(info: NavDriveDataInfo, fields: CarrotManFields): CarrotManFields {
        var updated = fields
        try {
            val enlargedInfo = safeGet(info, "getNavEnlargedMapInfo", null as Any?) ?: return updated
            val showing = safeGet(enlargedInfo, "isShowing", false)
            val distToMap = safeGet(enlargedInfo, "getDistanceToMap", 0)

            if (showing && distToMap > 0) {
                // 放大图距离可以近似为到下一个复杂路口的距离
                // 当 nTBTDistNext 为0时用此值填充
                if (updated.nTBTDistNext == 0) {
                    updated = updated.copy(nTBTDistNext = distToMap)
                    Log.d(TAG, "🗺️ 放大图距离→nTBTDistNext: ${distToMap}m")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "放大图信息解析失败: ${e.message}")
        }
        return updated
    }

    /**
     * 🆕 桥接路线交通拥堵数据
     * NavRouteTrafficItem: getTrafficStatus(), getDistance(), getPassTime(), getFrom(), getTo()
     *
     * TrafficStatus 枚举: UNBLOCKED(畅通), SLOW(缓行), CONGESTED(拥堵), VERY_CONGESTED(严重拥堵), UNKNOWN
     *
     * 策略：扫描 trafficItems，找到当前点之后最近的拥堵段（CONGESTED/VERY_CONGESTED），
     * 填充 trafficJamAhead/Distance/Duration/Status 供 Comma3 提前减速。
     * 同时更新 trafficLevel/trafficDescription 反映整体路况。
     */
    private fun bridgeTrafficItems(routeData: NavDriveRouteData, fields: CarrotManFields): CarrotManFields {
        var updated = fields
        try {
            val items = safeGet<List<*>?>(routeData, "getTrafficItems", null) ?: return updated
            if (items.isEmpty()) return updated

            val currIdx = try { routeData.currPointIdx } catch (_: Exception) { 0 }

            // 统计各等级路段
            var congestedDist = 0
            var totalDist = 0
            var nearestJamDist = Int.MAX_VALUE
            var nearestJamDuration = 0
            var nearestJamStatus = 0

            for (item in items) {
                item ?: continue
                val from = safeGet(item, "getFrom", 0)
                val to = safeGet(item, "getTo", 0)
                val dist = safeGet(item, "getDistance", 0)
                val passTime = safeGet(item, "getPassTime", 0)
                val statusObj = safeGet(item, "getTrafficStatus", null as Any?)
                val statusName = statusObj?.toString() ?: "UNKNOWN"

                totalDist += dist

                // 判断拥堵等级
                val statusLevel = when {
                    statusName.contains("VERY_CONGESTED", ignoreCase = true) -> 4
                    statusName.contains("CONGESTED", ignoreCase = true) -> 3
                    statusName.contains("SLOW", ignoreCase = true) -> 2
                    statusName.contains("UNBLOCKED", ignoreCase = true) -> 1
                    else -> 0
                }

                if (statusLevel >= 3) congestedDist += dist

                // 找当前点之后最近的拥堵段
                if (from >= currIdx && statusLevel >= 3 && dist < nearestJamDist) {
                    nearestJamDist = dist
                    nearestJamDuration = passTime * 60  // passTime是分钟，转秒
                    nearestJamStatus = statusLevel
                }
            }

            // 更新拥堵预警字段
            if (nearestJamDist < Int.MAX_VALUE) {
                updated = updated.copy(
                    tencentSlice = updated.tencentSlice.copy(
                        trafficJamAhead = true,
                        trafficJamDistance = nearestJamDist,
                        trafficJamDuration = nearestJamDuration,
                        trafficJamStatus = nearestJamStatus
                    )
                )
            } else {
                updated = updated.copy(
                    tencentSlice = updated.tencentSlice.copy(
                        trafficJamAhead = false,
                        trafficJamDistance = 0,
                        trafficJamDuration = 0,
                        trafficJamStatus = 0
                    )
                )
            }

            // 整体路况等级
            val congestionRatio = if (totalDist > 0) congestedDist.toFloat() / totalDist else 0f
            val overallLevel = when {
                congestionRatio > 0.3f -> 3  // 严重拥堵
                congestionRatio > 0.1f -> 2  // 中度拥堵
                congestionRatio > 0f -> 1    // 轻度拥堵
                else -> 0                     // 畅通
            }
            val desc = when (overallLevel) {
                3 -> "严重拥堵"
                2 -> "中度拥堵"
                1 -> "轻度拥堵"
                else -> "畅通"
            }
            updated = updated.copy(
                trafficLevel = overallLevel,
                trafficDescription = desc
            )
        } catch (e: Exception) {
            Log.w(TAG, "交通路况解析失败: ${e.message}")
        }
        return updated
    }

    /**
     * 🆕 桥接前方限行信息
     * NavRestrictionInfo: getTruckRestrictionInfoList(), getTurnRestrictionInfoList()
     *
     * 货车限制和转向限制信息，当前以日志形式输出供调试。
     * 如果前方有限行，更新 situationType/situationDescription 提示用户。
     */
    private fun bridgeRestrictionInfo(info: NavDriveDataInfo, fields: CarrotManFields): CarrotManFields {
        var updated = fields
        try {
            val restrictionInfo = safeGet(info, "getRestrictionInfo", null as Any?) ?: return updated

            val truckList = safeGet<List<*>?>(restrictionInfo, "getTruckRestrictionInfoList", null)
            val turnList = safeGet<List<*>?>(restrictionInfo, "getTurnRestrictionInfoList", null)

            val hasTruckRestriction = !truckList.isNullOrEmpty()
            val hasTurnRestriction = !turnList.isNullOrEmpty()

            if (hasTruckRestriction || hasTurnRestriction) {
                val descriptions = mutableListOf<String>()
                if (hasTruckRestriction) {
                    descriptions.add("货车限行(${truckList!!.size}处)")
                    // 提取详细信息
                    truckList.firstOrNull()?.let { truck ->
                        val desc = safeGet(truck, "getDescription", "") ?: ""
                        val dist = safeGet(truck, "getDistance", 0)
                        if (desc.isNotEmpty() || dist > 0) {
                            Log.i(TAG, "🚛 货车限行: dist=${dist}m, desc=$desc")
                        }
                    }
                }
                if (hasTurnRestriction) {
                    descriptions.add("转向限制(${turnList!!.size}处)")
                    turnList.firstOrNull()?.let { turn ->
                        val desc = safeGet(turn, "getDescription", "") ?: ""
                        val dist = safeGet(turn, "getDistance", 0)
                        if (desc.isNotEmpty() || dist > 0) {
                            Log.i(TAG, "🚫 转向限制: dist=${dist}m, desc=$desc")
                        }
                    }
                }

                // 更新态势信息字段
                updated = updated.copy(
                    situationType = 5,  // 5=限行
                    situationDescription = "前方${descriptions.joinToString("、")}"
                )
                Log.d(TAG, "⚠️ 限行信息: ${descriptions.joinToString(", ")}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "限行信息解析失败: ${e.message}")
        }
        return updated
    }

    /**
     * 🔍 诊断模拟导航数据完整性
     * 在导航开始后调用，检查哪些字段有数据，哪些为空
     */
    fun diagnoseSimulationData() {
        val fields = carrotManFieldsState?.value ?: return
        val report = buildString {
            appendLine("=== 腾讯模拟导航数据诊断 ===")
            appendLine("✅ 有数据的字段:")
            if (fields.vpPosPointLat != 0.0 && fields.vpPosPointLon != 0.0) 
                appendLine("  - vpPosPoint: (${fields.vpPosPointLat}, ${fields.vpPosPointLon})")
            if (fields.latitude != 0.0 && fields.longitude != 0.0)
                appendLine("  - GPS坐标: (${fields.latitude}, ${fields.longitude})")
            if (fields.nPosSpeed > 0) 
                appendLine("  - 速度: ${fields.nPosSpeed} km/h")
            if (fields.nPosAngle != 0.0) 
                appendLine("  - 方向: ${fields.nPosAngle}°")
            if (fields.nSdiDist > 0) 
                appendLine("  - 电子眼距离: ${fields.nSdiDist}m")
            if (fields.nTBTDistNext > 0)
                appendLine("  - 第二转弯距离: ${fields.nTBTDistNext}m")
            
            appendLine("\n❌ 缺失的关键字段:")
            if (fields.nRoadLimitSpeed == 0) 
                appendLine("  - nRoadLimitSpeed (道路限速)")
            if (fields.nTBTDist == 0) 
                appendLine("  - nTBTDist (转弯距离)")
            if (fields.nTBTTurnType == -1) 
                appendLine("  - nTBTTurnType (转弯类型)")
            if (fields.szTBTMainText.isEmpty()) 
                appendLine("  - szTBTMainText (转弯指令)")
            if (fields.nGoPosDist == 0) 
                appendLine("  - nGoPosDist (剩余距离)")
            if (fields.nGoPosTime == 0) 
                appendLine("  - nGoPosTime (剩余时间)")
            if (fields.szPosRoadName.isEmpty()) 
                appendLine("  - szPosRoadName (当前道路名)")
            if (fields.latitude == 0.0 && fields.longitude == 0.0)
                appendLine("  - latitude/longitude (GPS坐标)")
            
            appendLine("\n📊 字段探测结果:")
            appendLine("  - getLimitSpeed: $hasGetLimitSpeed")
            appendLine("  - getRemainingDist: $hasGetRemainingDist")
            appendLine("  - getCurrRoadName: $hasGetCurrRoadName")
            appendLine("  - getNextRoadName: $hasGetNextRoadName")
            appendLine("  - getNextIntersectionType: $hasGetNextIntersectionType")
            
            appendLine("\n💡 可能原因:")
            appendLine("  1. 腾讯模拟导航不提供完整的 mainRouteData")
            appendLine("  2. 需要真实导航才能获取完整数据")
            appendLine("  3. SDK版本不支持某些API")
            appendLine("  4. 模拟导航未触发某些回调（如 onNavDataInfoUpdate）")
        }
        Log.i(TAG, report)
    }

    /** 停止导航时调用 */
    fun onNavigationStopped() {
        isNavigating = false
        turnBitmap = null
        turnBitmapNext = null
        laneBitmap = null
        segmentRoadInfoList = emptyList()
        lastRoadcateSegIdx = -1
        sdkIsOverSpeed = false
        sdkOverSpeedType = 0
        sdkSpeedKMH = 0
        recommendRouteInfo = null
        recommendRouteId = null
        Log.i(TAG, "🛑 腾讯导航已停止")
        clearNavigationFields()
    }

    /** 清除推荐路线提示（用户忽略或切换后调用） */
    fun clearRecommendRoute() {
        recommendRouteInfo = null
        recommendRouteId = null
    }

    /**
     * 🆕 Feature 1: 从 NavDriveRoute 提取路线点串（用于弯道限速 vturn_speed）
     * 路线规划成功后调用，提取路线经纬度点串存入 CarrotManFields
     * Python 端通过 TCP 7709 接收这些点，计算曲率和弯道限速
     *
     * @param route 路线规划结果 NavDriveRoute
     */
    fun extractRoutePoints(route: NavDriveRoute) {
        try {
            // 探测 NavDriveRoute 可用方法
            if (!routeReflectionChecked) {
                routeReflectionChecked = true
                val clazz = route.javaClass
                fun has(name: String) = try { clazz.getMethod(name); true } catch (_: Exception) { false }
                hasGetRoutePoints = has("getRoutePoints")
                hasGetSegmentItems = has("getSegmentItems")
                hasGetTrafficLights = has("getTrafficLights")
                hasGetForkPoints = has("getForkPoints")
                hasGetFee = has("getFee")
                Log.i(TAG, "📊 NavDriveRoute字段探测: routePoints=$hasGetRoutePoints, " +
                        "segments=$hasGetSegmentItems, trafficLights=$hasGetTrafficLights, " +
                        "forkPoints=$hasGetForkPoints, fee=$hasGetFee")
            }

            if (!hasGetRoutePoints) {
                Log.w(TAG, "⚠️ SDK不支持 getRoutePoints()，无法提取路线点串")
                return
            }

            val routePoints = safeGet<List<*>?>(route, "getRoutePoints", null)
            if (routePoints.isNullOrEmpty()) {
                Log.w(TAG, "⚠️ 路线点串为空")
                return
            }

            val points = mutableListOf<Pair<Double, Double>>()
            for (point in routePoints) {
                if (point == null) continue
                // NavRoutePoint 或 LatLng — 尝试多种方式获取坐标
                val lat = safeGetAny(point, 0.0, "getLatitude", "getLat")
                val lon = safeGetAny(point, 0.0, "getLongitude", "getLng", "getLon")
                if (lat != 0.0 && lon != 0.0) {
                    // 腾讯SDK坐标是GCJ-02，需要转换为WGS-84给Comma3
                    val wgs = gcj02ToWgs84(lat, lon)
                    points.add(Pair(wgs.second, wgs.first))  // (lon, lat) 格式
                }
            }

            if (points.isNotEmpty()) {
                Log.i(TAG, "🛣️ 提取路线点串: ${points.size}个点 (GCJ-02→WGS-84)")
                updateField { it.copy(
                    tencentSlice = it.tencentSlice.copy(
                        tencentRoutePoints = points,
                        tencentRoutePointsReady = true
                    )
                ) }
            }

            // Feature 3: 提取路段信息获取精确道路类型
            extractSegmentRoadInfo(route)

            // Feature 7: 提取红绿灯坐标
            extractTrafficLights(route)

            // Feature 8: 提取分叉点
            extractForkPoints(route)

            // 提取收费信息
            if (hasGetFee) {
                val fee = safeGet(route, "getFee", 0)
                if (fee > 0) {
                    updateField { it.copy(tencentSlice = it.tencentSlice.copy(tollFee = fee)) }
                    Log.d(TAG, "💰 全程收费: ${fee}分")
                }
            }

        } catch (e: Exception) {
            Log.w(TAG, "路线点串提取失败: ${e.message}")
        }
    }

    /**
     * 🆕 Feature 3: 从路段信息提取精确道路类型
     * NavRouteSegment.getRoadNames() → NavRouteSegmentRoadName.getGrade()/getKind()
     *
     * 同时缓存所有路段的 grade/kind，供导航过程中按 segIdx 实时更新 roadcate
     */
    private fun extractSegmentRoadInfo(route: NavDriveRoute) {
        if (!hasGetSegmentItems) return
        try {
            val segments = safeGet<List<*>?>(route, "getSegmentItems", null)
            if (segments.isNullOrEmpty()) return

            // 缓存所有路段的 grade/kind
            val infoList = mutableListOf<Pair<Int, Int>>()
            for (seg in segments) {
                if (seg == null) { infoList.add(Pair(-1, -1)); continue }
                val roadNames = safeGet<List<*>?>(seg, "getRoadNames", null)
                if (!roadNames.isNullOrEmpty()) {
                    val rn = roadNames[0]
                    val g = if (rn != null) safeGet(rn, "getGrade", -1) else -1
                    val k = if (rn != null) safeGet(rn, "getKind", -1) else -1
                    infoList.add(Pair(g, k))
                } else {
                    infoList.add(Pair(-1, -1))
                }
            }
            segmentRoadInfoList = infoList
            lastRoadcateSegIdx = -1  // 重置，让下次 onNavLocationInfoUpdate 触发更新

            Log.i(TAG, "🛣️ 缓存 ${infoList.size} 个路段道路信息")

            // 用第一个路段初始化 roadcate
            val (grade, kind) = infoList[0]
            if (grade >= 0 || kind >= 0) {
                val roadcate = mapGradeKindToRoadcate(grade, kind)
                updateField { it.copy(
                    tencentSlice = it.tencentSlice.copy(
                        roadGrade = grade,
                        roadKind = kind,
                    ),
                    roadcate = if (roadcate > 0) roadcate else it.roadcate
                ) }
                Log.d(TAG, "🛣️ 初始路段: grade=$grade(${gradeToName(grade)}), " +
                        "kind=$kind(${kindToName(kind)}) → roadcate=$roadcate")
            }
        } catch (e: Exception) {
            Log.w(TAG, "路段道路信息提取失败: ${e.message}")
        }
    }

    /**
     * 根据当前 segIdx 更新 roadGrade/roadKind/roadcate
     * 在 onNavLocationInfoUpdate 中调用，当 segIdx 变化时触发
     */
    private fun updateRoadcateBySegIdx(segIdx: Int) {
        if (segIdx < 0 || segIdx == lastRoadcateSegIdx) return
        if (segIdx >= segmentRoadInfoList.size) return
        lastRoadcateSegIdx = segIdx

        val (grade, kind) = segmentRoadInfoList[segIdx]
        if (grade < 0 && kind < 0) return  // 无有效数据

        val roadcate = mapGradeKindToRoadcate(grade, kind)
        updateField { it.copy(
            tencentSlice = it.tencentSlice.copy(
                roadGrade = grade,
                roadKind = kind,
            ),
            roadcate = if (roadcate > 0) roadcate else it.roadcate
        ) }
        Log.d(TAG, "🛣️ 路段切换[seg=$segIdx]: grade=$grade(${gradeToName(grade)}), " +
                "kind=$kind(${kindToName(kind)}) → roadcate=$roadcate")
    }

    /**
     * NavRoadGrade 整数值 → 名称（用于日志）
     */
    private fun gradeToName(grade: Int): String = when (grade) {
        0 -> "HighWay"; 1 -> "FastWay"; 2 -> "NationWay"; 3 -> "ProvinceWay"
        4 -> "CountryWay"; 6 -> "TownWay"; 8 -> "OtherWay"; 9 -> "NineWay"
        10 -> "FerryWay"; 11 -> "FootWay"; 13 -> "Bicycle"; -1 -> "Null"
        else -> "Unknown($grade)"
    }

    /**
     * NavRoadKind 整数值 → 名称（用于日志）
     */
    private fun kindToName(kind: Int): String = when (kind) {
        0 -> "Roundabout"; 1 -> "Normal"; 2 -> "Separate"; 3 -> "JCT"
        4 -> "Inner"; 5 -> "Ramp"; 6 -> "PA"; 7 -> "SA"
        8 -> "RightTurn"; 9 -> "LeftTurn"; 10 -> "UTurn"; 11 -> "IC"
        12 -> "MainSideIC"; 13 -> "Tunnel"; 14 -> "MainRoad"; 15 -> "SideRoad"
        16 -> "Bridge"; -1 -> "Null"
        else -> "Unknown($kind)"
    }

    /**
     * 根据 NavRouteSegmentRoadName 的 grade/kind 映射到 roadcate
     *
     * NavRoadGrade（道路等级，SDK v7.5.0）：
     *   0=HighWay(高速), 1=FastWay(城市快速路), 2=NationWay(国道),
     *   3=ProvinceWay(省道), 4=CountryWay(县道), 6=TownWay(乡村公路),
     *   8=OtherWay(其它道路), 9=NineWay(九级道路), 10=FerryWay(轮渡),
     *   11=FootWay(行人道路), 13=Bicycle(自行车专用路), -1=Null(无效)
     *
     * NavRoadKind（道路属性，SDK v7.5.0）：
     *   0=Roundabout(环岛), 1=Normal(普通道路), 2=Separate(上下线分离),
     *   3=JCT(高速互通), 4=Inner(内部路), 5=Ramp(匝道),
     *   6=PA(停车区), 7=SA(服务区), 8=RightTurn, 9=LeftTurn,
     *   10=UTurn(调头路), 11=IC(高速出入口), 12=MainSideIC(辅主辅路出入口),
     *   13=Tunnel(隧道), 14=MainRoad(主路), 15=SideRoad(辅路),
     *   16=Bridge(桥梁), -1=Null(无效)
     *
     * roadcate 协议值（Python carrot_serv.py）：
     *   10 = 高速公路/快速路/高架
     *   6  = 普通道路
     *   8  = 未知/默认
     */
    private fun mapGradeKindToRoadcate(grade: Int, kind: Int): Int {
        // 优先用 grade（道路等级）判断
        val gradeResult = when (grade) {
            0 -> 10    // HighWay 高速
            1 -> 10    // FastWay 城市快速路
            2 -> 6     // NationWay 国道
            3 -> 6     // ProvinceWay 省道
            4 -> 6     // CountryWay 县道
            6 -> 6     // TownWay 乡村公路
            8 -> 6     // OtherWay 其它道路
            9 -> 6     // NineWay 九级道路
            10 -> 6    // FerryWay 轮渡
            else -> -1 // 无效或未知，继续用 kind 判断
        }
        if (gradeResult > 0) return gradeResult

        // grade 无效时，用 kind（道路属性）辅助判断
        return when (kind) {
            3 -> 10    // JCT 高速互通 → 高速
            5 -> 10    // Ramp 匝道 → 高速/快速路
            11 -> 10   // IC 高速出入口 → 高速
            2 -> 10    // Separate 上下线分离 → 通常是高速/快速路
            13 -> 10   // Tunnel 隧道 → 保守按快速路（隧道多在高速/快速路上）
            15 -> 6    // SideRoad 辅路 → 普通道路
            4 -> 6     // Inner 内部路 → 普通道路
            12 -> 6    // MainSideIC 辅主辅路出入口 → 普通道路
            else -> 6  // Normal/Roundabout/其他 → 普通道路
        }
    }

    /**
     * 🆕 Feature 7: 提取全路线红绿灯位置
     */
    private fun extractTrafficLights(route: NavDriveRoute) {
        if (!hasGetTrafficLights) return
        try {
            val lights = safeGet<List<*>?>(route, "getTrafficLights", null)
            if (!lights.isNullOrEmpty()) {
                Log.i(TAG, "🚦 全路线红绿灯: ${lights.size}个")
            }
        } catch (e: Exception) {
            Log.w(TAG, "红绿灯信息提取失败: ${e.message}")
        }
    }

    /**
     * 🆕 Feature 8: 提取分叉点坐标（用于NOA车道变换决策）
     */
    private fun extractForkPoints(route: NavDriveRoute) {
        if (!hasGetForkPoints) return
        try {
            val forkPoints = safeGet<List<*>?>(route, "getForkPoints", null)
            if (!forkPoints.isNullOrEmpty()) {
                Log.i(TAG, "🔀 分叉点: ${forkPoints.size}个")
                for ((idx, fp) in forkPoints.withIndex()) {
                    if (fp == null) continue
                    val latlon = safeGet(fp, "getLatlon", null as Any?)
                    val pointIdx = safeGet(fp, "getPointIndex", -1)
                    if (latlon != null) {
                        val lat = safeGet(latlon, "getLatitude", 0.0)
                        val lon = safeGet(latlon, "getLongitude", 0.0)
                        Log.d(TAG, "  分叉点[$idx]: ($lat,$lon), pointIdx=$pointIdx")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "分叉点信息提取失败: ${e.message}")
        }
    }

    /**
     * GCJ-02 → WGS-84 坐标转换（简化版）
     * 返回 Pair(lat, lon)
     */
    private fun gcj02ToWgs84(gcjLat: Double, gcjLon: Double): Pair<Double, Double> {
        val a = 6378245.0
        val ee = 0.00669342162296594323
        var dLat = transformLat(gcjLon - 105.0, gcjLat - 35.0)
        var dLon = transformLon(gcjLon - 105.0, gcjLat - 35.0)
        val radLat = gcjLat / 180.0 * Math.PI
        var magic = Math.sin(radLat)
        magic = 1 - ee * magic * magic
        val sqrtMagic = Math.sqrt(magic)
        dLat = (dLat * 180.0) / ((a * (1 - ee)) / (magic * sqrtMagic) * Math.PI)
        dLon = (dLon * 180.0) / (a / sqrtMagic * Math.cos(radLat) * Math.PI)
        return Pair(gcjLat - dLat, gcjLon - dLon)
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }

    /**
     * 尝试注册主辅路状态监听（通过反射，SDK版本可能不支持）
     * 注意：SDK v7.5.0 文档确认 onUpdateParallelRoad 回调存在于 SimpleNavigatorDriveObserver
     */
    fun tryRegisterParallelRoadListener(navigatorDrive: Any) {
        try {
            val methods = navigatorDrive.javaClass.methods
            val parallelMethod = methods.find { it.name.contains("ParallelRoad", ignoreCase = true) }
            if (parallelMethod != null) {
                Log.i(TAG, "🛤️ 发现主辅路API: ${parallelMethod.name}")
            } else {
                Log.w(TAG, "🛤️ 当前SDK版本不支持主辅路状态回调")
            }
        } catch (e: Exception) {
            Log.w(TAG, "主辅路监听注册失败: ${e.message}")
        }
    }

    /**
     * 桥接导航指令到Comma3
     */
    private fun bridgeNavigationCommands(fields: CarrotManFields): CarrotManFields {
        // xDistToTurn/xSpdLimit/desiredSpeed 字段已删除，不再桥接
        return fields
    }

    private fun updateField(transform: (CarrotManFields) -> CarrotManFields) {
        val state = carrotManFieldsState ?: return
        val transformed = transform(state.value)
        // 🆕 三模互斥：腾讯模式下自动标记数据源
        state.value = transformed.copy(
            source_last = "tencent",
            lastUpdateTime = System.currentTimeMillis()
        )
    }

    private fun clearNavigationFields() {
        updateField { fields ->
            fields.copy(
                isNavigating = false,
                nTBTDist = 0,
                nTBTTurnType = -1,
                szTBTMainText = "",
                szNearDirName = "",
                szFarDirName = "",
                nTBTDistNext = 0,
                nTBTTurnTypeNext = -1,
                szTBTMainTextNext = "",
                nGoPosDist = 0,
                nGoPosTime = 0,
                szPosRoadName = "",
                nRoadLimitSpeed = 0,
                laneInfoList = emptyList(),
                laneCount = 0,
                tencentSlice = CarrotManTencentSlice(),
                nSdiType = -1,
                nSdiSpeedLimit = 0,
                nSdiDist = 0,
                nSdiBlockType = -1,
                nSdiBlockSpeed = 0,
                nSdiBlockDist = 0,
                nSdiAverageSpeed = -1,
                exitNameInfo = "",
                sapaName = "",
                sapaDist = -1,
                sapaType = -1,
                sapaNum = -1,
                curSegNum = 0,
                curPointNum = 0,
                // 红绿灯倒计时
                trafficLightCountdown = 0,
                trafficLightState = -1,
                trafficLightDistance = 0,
                // 导航状态增强
                trafficLevel = -1,
                trafficDescription = ""
            ).also { result ->
                result.isNightMode = false
                result.isOffRoute = false
            }
        }
    }
}
