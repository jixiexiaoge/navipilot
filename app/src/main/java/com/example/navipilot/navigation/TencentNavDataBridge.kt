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
 * 腾讯导航数据桥接器 — 将腾讯SDK回调映射到 CarrotManFields
 */
class TencentNavDataBridge(
    private val carrotManFieldsState: MutableState<CarrotManFields>?
) {
    companion object {
        private const val TAG = "TencentNavBridge"

        /**
         * 腾讯SDK getNextIntersectionType() → nTBTTurnType 完整映射表
         */
        fun mapIntersectionType(type: Int): Int {
            return when (type) {
                // === 腾讯SDK v7.5.0 官方映射（映射关系.md §2.3）===
                0 -> 51     // 无动作 → 直行
                1 -> 51     // 直行
                2 -> 1001   // 向右前方
                17 -> 13    // 右转
                18 -> 19    // 急右转
                19 -> 19    // 向右后方（急右转）
                20 -> 133   // 环岛右转
                21 -> 6     // 向右变道（并入）
                22 -> 6     // 向右变道（离开）
                25 -> 1000  // 向左前方
                26 -> 12    // 左转
                27 -> 16    // 急左转
                28 -> 16    // 向左后方（急左转）
                29 -> 139   // 环岛左转
                30 -> 7     // 向左变道（并入）
                31 -> 7     // 向左变道（离开）
                32 -> 102   // 靠左行驶
                33 -> 102   // 靠左行驶
                34 -> 101   // 靠右行驶
                35 -> 101   // 靠右行驶
                36 -> 14    // ★ 掉头 ← 关键修复
                51 -> 102   // 分流左
                52 -> 101   // 分流右
                53 -> 131   // 进入环岛
                54 -> 142   // 环岛第一出口
                55 -> 139   // 环岛第二出口
                56 -> 133   // 环岛第三出口
                57 -> 133   // 环岛第四出口
                58 -> 133   // 环岛第五出口
                59 -> 133   // 环岛第六出口
                66 -> 201   // 到达目的地
                73 -> 102   // 匝道（默认靠左）

                // === 旧版兼容（SDK未记录的旧type，尽可能保留语义）===
                3 -> 51; 12 -> 51
                4 -> 102; 5 -> 101
                6 -> 17; 7 -> 19
                8 -> 153; 14 -> 153
                9 -> 102; 10 -> 101
                11 -> 131
                13 -> 55
                15 -> 201; 61 -> 201
                64 -> 51; 81 -> 51; 1001 -> 51
                87 -> 14    // 备用掉头

                else -> {
                    Log.w(TAG, "🆕 未知SDK intersectionType=$type，请记录并补充映射表！")
                    -1
                }
            }
        }

        /** 已发现的未知SDK枚举值集合（避免重复日志刷屏） */
        private val unknownIntersectionTypes = mutableSetOf<Int>()

        fun resolveNTBTTurnType(sdkType: Int, text: String): Int {
            val result = if (sdkType >= 0) {
                val mapped = mapIntersectionType(sdkType)
                if (mapped != -1) {
                    mapped
                } else {
                    if (unknownIntersectionTypes.add(sdkType)) {
                        Log.e(TAG, "🆕🆕🆕 发现新的SDK intersectionType=$sdkType, text='$text' — 请补充到 mapIntersectionType() 映射表！")
                    }
                    -1
                }
            } else {
                inferTurnTypeFromText(text)
            }
            Log.d(TAG, "🔄 [转弯映射] SDK=$sdkType, text='$text' → nTBTTurnType=$result")
            return result
        }

        fun inferTurnTypeFromText(text: String): Int =
            TurnTypeTextInference.inferTurnTypeFromText(text)

        /**
         * 腾讯SDK NavCameraInfo.getCameraType() → Python nSdiType 映射
         */
        fun mapCameraTypeToSdiType(cameraType: Int, zoneLength: Int): Int {
            if (zoneLength > 0) return 2
            return when (cameraType) {
                0 -> -1; 1 -> 6; 2 -> 8; 3 -> 1; 4 -> 7; 5 -> 9; 6 -> 8; 7 -> 11; 8 -> 8
                9 -> 2; 10 -> 3; 16 -> 8; 17 -> 8; 21 -> 14; 22 -> 14; 23 -> 8; 24 -> 8
                30 -> 8; 31 -> 8; 32 -> 17; 33 -> 75; 34 -> 1; 35 -> 1; 36 -> 1; 37 -> 8
                38 -> 8; 39 -> 8; 40 -> 8; 41 -> 8; 42 -> 8; 43 -> 14; 44 -> 76; 45 -> 14
                46 -> 14; 47 -> 8; 48 -> 8; 49 -> 8; 50 -> 26; 51 -> 8; 52 -> 8; 53 -> 76
                54 -> 8
                else -> { if (cameraType >= 0) 8 else -1 }
            }
        }

        /** 根据道路限速和道路名推断 roadcate */
        fun inferRoadcateFromSpeedLimit(speedLimit: Int, currentRoadcate: Int, roadName: String = ""): Int {
            if (roadName.isNotEmpty()) {
                val isHighway = roadName.contains("高速") || roadName.contains("快速")
                        || roadName.contains("环线") || roadName.contains("高架")
                        || roadName.matches(Regex(".*[GS]\\d{1,4}.*"))
                if (isHighway) return 10
            }
            return when {
                speedLimit >= 100 -> 10
                speedLimit >= 80 -> 10
                speedLimit > 0 -> 6
                else -> currentRoadcate
            }
        }

        /** 腾讯 NavRoadType → roadcate 映射 */
        fun mapNavRoadTypeToRoadcate(navRoadTypeName: String, currentRoadcate: Int): Int {
            return when {
                navRoadTypeName.contains("Elevated", ignoreCase = true) -> 10
                navRoadTypeName.contains("DownstairsMainRoad", ignoreCase = true) -> currentRoadcate
                navRoadTypeName.contains("DownstairsServingRoad", ignoreCase = true) -> 6
                navRoadTypeName.contains("Downstairs", ignoreCase = true) -> currentRoadcate
                navRoadTypeName.contains("ServingRoad", ignoreCase = true) ||
                navRoadTypeName.contains("Serving", ignoreCase = true) -> 6
                navRoadTypeName.contains("MainRoad", ignoreCase = true) -> currentRoadcate
                navRoadTypeName.contains("Direction", ignoreCase = true) -> currentRoadcate
                else -> currentRoadcate
            }
        }
    }

    /** 导航是否激活 */
    var isNavigating = false
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
            if (info == null) {
                Log.w(TAG, "⚠️ onNavDataInfoUpdate: info is null")
                return
            }

            val mainRoute = info.mainRouteData

            if (mainRoute == null) {
                Log.w(TAG, "⚠️ onNavDataInfoUpdate: mainRouteData is null (模拟导航模式)")

                val routeDataList = try {
                    info.routeDataList
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 无法获取routeDataList: ${e.message}")
                    null
                }

                if (routeDataList != null && routeDataList.isNotEmpty()) {
                    val firstRoute = routeDataList[0]
                    isNavigating = true

                    turnBitmap = try { firstRoute.nextIntersectionBitmap } catch (_: Exception) { null }
                    turnBitmapNext = try { firstRoute.nextNextIntersectionBitmap } catch (_: Exception) { null }

                    updateField { fields ->
                        val nTBTDist = try { firstRoute.nextIntersectionRemainingDistance } catch (_: Exception) { 0 }
                        // 限速保护：腾讯数据为 0 时保留当前值，防止掉到 0
                        val rawLimit = try { firstRoute.limitSpeed } catch (_: Exception) { 0 }
                        val nRoadLimitSpeed = rawLimit.takeIf { it > 0 } ?: fields.nRoadLimitSpeed
                        val nGoPosDist = try { firstRoute.remainingDist } catch (_: Exception) { 0 }
                        val nGoPosTime = try { firstRoute.remainingTimeInSeconds } catch (_: Exception) { try { firstRoute.remainingTime * 60 } catch (_: Exception) { 0 } }
                        val szPosRoadName = try { firstRoute.currRoadName ?: "" } catch (_: Exception) { "" }
                        val szTBTMainText = try { firstRoute.nextRoadName ?: "" } catch (_: Exception) { "" }
                        val nextIntersectionType = try { firstRoute.nextIntersectionType } catch (_: Exception) { -1 }
                        val nTBTTurnType = resolveNTBTTurnType(nextIntersectionType, szTBTMainText)
                        val szFarDirName = try { firstRoute.nextNextRoadName ?: "" } catch (_: Exception) { "" }
                        val nextNextType = try { firstRoute.nextNextIntersectionType } catch (_: Exception) { -1 }
                        val nTBTTurnTypeNext = resolveNTBTTurnType(nextNextType, szFarDirName)
                        val nTBTDistNext = 0
                        val passedDistance = try { firstRoute.passedDistance } catch (_: Exception) { 0 }
                        val remainingTrafficLights = try { firstRoute.remainingTrafficLightCount } catch (_: Exception) { 0 }
                        val curPointNum = try { firstRoute.currPointIdx } catch (_: Exception) { -1 }
                        val nPosSpeed = try { info.speedKMH } catch (_: Exception) { 0 }
                        val exitName = try { info.highwayExitName ?: "" } catch (_: Exception) { "" }
                        val entranceName = try { info.highwayEntranceName ?: "" } catch (_: Exception) { "" }
                        val passedDistInfo = try { info.passedDistance } catch (_: Exception) { 0 }

                        var updated = fields.copy(
                            isNavigating = true,
                            nTBTDist = nTBTDist, nRoadLimitSpeed = nRoadLimitSpeed,
                            nGoPosDist = nGoPosDist, nGoPosTime = nGoPosTime,
                            szPosRoadName = szPosRoadName,
                            szTBTMainText = szTBTMainText, szNearDirName = szTBTMainText,
                            szFarDirName = szFarDirName, szTBTMainTextNext = szFarDirName,
                            nTBTTurnType = nTBTTurnType, nTBTTurnTypeNext = nTBTTurnTypeNext,
                            nTBTDistNext = nTBTDistNext,
                            tencentSlice = fields.tencentSlice.copy(
                                tSdkIntersectionType = nextIntersectionType,
                                passedDistance = if (passedDistance > 0) passedDistance else passedDistInfo,
                                passedTime = try { info.passedTime } catch (_: Exception) { 0 },
                                remainingTrafficLights = remainingTrafficLights,
                            ),
                            curPointNum = curPointNum,
                            nPosSpeed = if (nPosSpeed > 0) nPosSpeed.toDouble() else fields.nPosSpeed,
                            exitNameInfo = exitName,
                        )

                        // 目的地信息
                        val waypoints = try { info.waypoints } catch (_: Exception) { null }
                        if (waypoints != null && waypoints.isNotEmpty()) {
                            val destination = waypoints[waypoints.size - 1]
                            try {
                                val destLatLng = safeGet(destination, "getLatLng", null as Any?)
                                if (destLatLng != null) {
                                    val lat = safeGetAny(destLatLng, 0.0, "getLatitude", "getLat")
                                    val lon = safeGetAny(destLatLng, 0.0, "getLongitude", "getLon", "getLng")
                                    if (lat != 0.0 && lon != 0.0) {
                                        val (wgsLat, wgsLon) = gcj02ToWgs84(lat, lon)
                                        updated = updated.copy(goalPosX = wgsLon, goalPosY = wgsLat)
                                    }
                                }
                                updated = updated.copy(szGoalName = safeGetAny(destination, "", "getName", "getTitle") ?: "")
                            } catch (_: Exception) {}
                        }

                        // 区间测速
                        try {
                            val speedMonitorZone = try { info.navSpeedMonitorZoneInfo } catch (_: Exception) { null }
                            if (speedMonitorZone != null) {
                                updated = bridgeSpeedMonitorZone(info, updated)
                            }
                        } catch (_: Exception) {}

                        // 放大图
                        try {
                            val enlargedMapInfo = try { info.navEnlargedMapInfo } catch (_: Exception) { null }
                            if (enlargedMapInfo != null) {
                                updated = bridgeEnlargedMapInfo(info, updated)
                            }
                        } catch (_: Exception) {}

                        // 服务区
                        try {
                            val facilities = try { info.highwayFacilities } catch (_: Exception) { null }
                            if (facilities != null && facilities.isNotEmpty()) {
                                updated = bridgeHighwayFacilities(info, updated)
                            }
                        } catch (_: Exception) {}

                        // 交通拥堵数据
                        try {
                            val trafficItems = try { firstRoute.trafficItems } catch (_: Exception) { null }
                            if (trafficItems != null && trafficItems.isNotEmpty()) {
                                updated = bridgeTrafficItems(firstRoute, updated)
                            }
                        } catch (_: Exception) {}

                        // 限行信息
                        try {
                            val restrictionInfo = try { info.restrictionInfo } catch (_: Exception) { null }
                            if (restrictionInfo != null) {
                                updated = bridgeRestrictionInfo(info, updated)
                            }
                        } catch (_: Exception) {}

                        // 红绿灯倒计时
                        updated = bridgeTrafficLightCountDown(info, updated)

                        // 从限速+道路名推断道路类型
                        if (updated.tencentSlice.roadGrade < 0 && updated.tencentSlice.roadKind < 0) {
                            if (nRoadLimitSpeed > 0 || szPosRoadName.isNotEmpty()) {
                                updated = updated.copy(
                                    roadcate = inferRoadcateFromSpeedLimit(nRoadLimitSpeed, updated.roadcate, szPosRoadName)
                                )
                            }
                        }

                        Log.d(TAG, "✅ [字段已更新-routeList] nTBTDist=$nTBTDist, nTBTTurnType=$nTBTTurnType, nGoPosDist=$nGoPosDist")
                        updated
                    }
                } else {
                    // NavDriveDataInfo 直接提取（无路线数据时的兜底）
                    updateField { fields ->
                        var updated = fields.copy(isNavigating = true)

                        val remainDist = safeGetAny(info, 0, "getRemainingDistance", "getRemainingDist")
                        if (remainDist > 0) updated = updated.copy(nGoPosDist = remainDist)

                        val remainTime = safeGetAny(info, 0, "getRemainingTime", "getRemainingTimeInSeconds")
                        if (remainTime > 0) {
                            updated = updated.copy(nGoPosTime = if (remainTime > 1000) remainTime else remainTime * 60)
                        }

                        val currRoad = safeGetAny(info, "", "getCurrentRoadName", "getCurrRoadName") ?: ""
                        if (currRoad.isNotEmpty()) updated = updated.copy(szPosRoadName = currRoad)

                        val nextRoad = safeGetAny(info, "", "getNextRoadName") ?: ""
                        if (nextRoad.isNotEmpty()) updated = updated.copy(szTBTMainText = nextRoad, szNearDirName = nextRoad)

                        val limitSpeed = safeGetAny(info, 0, "getLimitSpeed", "getCurrentSpeedLimit")
                        if (limitSpeed > 0) updated = updated.copy(nRoadLimitSpeed = limitSpeed)

                        val turnDist = safeGetAny(info, 0, "getNextIntersectionRemainingDistance", "getNextIntersectionDistance", "getTurnDistance")
                        if (turnDist > 0) updated = updated.copy(nTBTDist = turnDist)

                        val turnType = safeGetAny(info, -1, "getNextIntersectionType", "getTurnType")
                        val resolvedTurnType = resolveNTBTTurnType(turnType, nextRoad)
                        if (resolvedTurnType != -1) {
                            updated = updated.copy(
                                nTBTTurnType = resolvedTurnType,
                                tencentSlice = updated.tencentSlice.copy(tSdkIntersectionType = turnType)
                            )
                        }

                        val dest = safeGet(info, "getDestination", null as Any?)
                        if (dest != null) {
                            val destLat = safeGetAny(dest, 0.0, "getLatitude", "getLat")
                            val destLon = safeGetAny(dest, 0.0, "getLongitude", "getLon", "getLng")
                            val destName = safeGetAny(dest, "", "getName", "getTitle") ?: ""
                            if (destLat != 0.0 && destLon != 0.0) {
                                val (wgsLat, wgsLon) = gcj02ToWgs84(destLat, destLon)
                                updated = updated.copy(goalPosX = wgsLon, goalPosY = wgsLat, szGoalName = destName)
                            }
                        }

                        updated = bridgeSpeedMonitorZone(info, updated)
                        updated = bridgeTrafficLightCountDown(info, updated)
                        updated
                    }
                }
                return
            }

            isNavigating = true

            turnBitmap = try { mainRoute.nextIntersectionBitmap } catch (_: Exception) { null }
            turnBitmapNext = try { mainRoute.nextNextIntersectionBitmap } catch (_: Exception) { null }

            updateField { fields ->
                val nTBTDist = try { mainRoute.nextIntersectionRemainingDistance } catch (_: Exception) { 0 }
                // 限速保护：腾讯数据为 0 时保留当前值，防止掉到 0
                val rawLimit = try { mainRoute.limitSpeed } catch (_: Exception) { 0 }
                val nRoadLimitSpeed = rawLimit.takeIf { it > 0 } ?: fields.nRoadLimitSpeed
                val nGoPosDist = try { mainRoute.remainingDist } catch (_: Exception) { 0 }
                val nGoPosTime = try { mainRoute.remainingTimeInSeconds } catch (_: Exception) { try { mainRoute.remainingTime * 60 } catch (_: Exception) { 0 } }
                val szPosRoadName = try { mainRoute.currRoadName ?: "" } catch (_: Exception) { "" }
                val szTBTMainText = try { mainRoute.nextRoadName ?: "" } catch (_: Exception) { "" }
                val nextIntersectionType = try { mainRoute.nextIntersectionType } catch (_: Exception) { -1 }
                val nTBTTurnType = resolveNTBTTurnType(nextIntersectionType, szTBTMainText)
                val szFarDirName = try { mainRoute.nextNextRoadName ?: "" } catch (_: Exception) { "" }
                val nextNextType = try { mainRoute.nextNextIntersectionType } catch (_: Exception) { -1 }
                val nTBTTurnTypeNext = resolveNTBTTurnType(nextNextType, szFarDirName)
                val nTBTDistNext = 0
                val passedDistance = try { mainRoute.passedDistance } catch (_: Exception) { 0 }
                val remainingTrafficLights = try { mainRoute.remainingTrafficLightCount } catch (_: Exception) { 0 }
                val curPointNum = try { mainRoute.currPointIdx } catch (_: Exception) { -1 }
                val trafficItems = try { mainRoute.trafficItems } catch (_: Exception) { null }

                val sdkSpeed = try { info.speedKMH } catch (_: Exception) { 0 }
                val exitName = try { info.highwayExitName ?: "" } catch (_: Exception) { "" }
                val entranceName = try { info.highwayEntranceName ?: "" } catch (_: Exception) { "" }
                val passedDistInfo = try { info.passedDistance } catch (_: Exception) { 0 }
                val passedTime = try { info.passedTime } catch (_: Exception) { 0 }
                val speedMonitorZone = try { info.navSpeedMonitorZoneInfo } catch (_: Exception) { null }
                val enlargedMapInfo = try { info.navEnlargedMapInfo } catch (_: Exception) { null }
                val facilities = try { info.highwayFacilities } catch (_: Exception) { null }
                val restrictionInfo = try { info.restrictionInfo } catch (_: Exception) { null }
                val waypoints = try { info.waypoints } catch (_: Exception) { null }

                var goalPosX = 0.0; var goalPosY = 0.0; var szGoalName = ""

                if (waypoints != null && waypoints.isNotEmpty()) {
                    val destination = waypoints[waypoints.size - 1]
                    try {
                        val destLatLng = safeGet(destination, "getLatLng", null as Any?)
                        if (destLatLng != null) {
                            val lat = safeGetAny(destLatLng, 0.0, "getLatitude", "getLat")
                            val lon = safeGetAny(destLatLng, 0.0, "getLongitude", "getLon", "getLng")
                            if (lat != 0.0 && lon != 0.0) {
                                val (wgsLat, wgsLon) = gcj02ToWgs84(lat, lon)
                                goalPosX = wgsLon; goalPosY = wgsLat
                            }
                        }
                        szGoalName = safeGetAny(destination, "", "getName", "getTitle") ?: ""
                    } catch (_: Exception) {}
                }

                var updated = fields.copy(
                    isNavigating = true,
                    nTBTDist = nTBTDist, nRoadLimitSpeed = nRoadLimitSpeed,
                    nGoPosDist = nGoPosDist, nGoPosTime = nGoPosTime,
                    szPosRoadName = szPosRoadName,
                    szTBTMainText = szTBTMainText, szNearDirName = szTBTMainText,
                    szFarDirName = szFarDirName, szTBTMainTextNext = szFarDirName,
                    nTBTTurnType = nTBTTurnType, nTBTTurnTypeNext = nTBTTurnTypeNext,
                    nTBTDistNext = nTBTDistNext,
                    tencentSlice = fields.tencentSlice.copy(
                        tSdkIntersectionType = nextIntersectionType,
                        passedDistance = if (passedDistance > 0) passedDistance else passedDistInfo,
                        passedTime = passedTime,
                        remainingTrafficLights = remainingTrafficLights,
                    ),
                    curPointNum = curPointNum,
                    nPosSpeed = if (sdkSpeed > 0) sdkSpeed.toDouble() else fields.nPosSpeed,
                    exitNameInfo = exitName,
                    goalPosX = goalPosX, goalPosY = goalPosY, szGoalName = szGoalName
                )

                // 区间测速
                if (speedMonitorZone != null) {
                    updated = bridgeSpeedMonitorZone(info, updated)
                }

                // 放大图
                if (enlargedMapInfo != null) {
                    updated = bridgeEnlargedMapInfo(info, updated)
                }

                // 服务区
                if (facilities != null && facilities.isNotEmpty()) {
                    updated = bridgeHighwayFacilities(info, updated)
                }

                // 交通拥堵
                if (trafficItems != null && trafficItems.isNotEmpty()) {
                    updated = bridgeTrafficItems(mainRoute, updated)
                }

                // 限行信息
                if (restrictionInfo != null) {
                    updated = bridgeRestrictionInfo(info, updated)
                }

                // 红绿灯倒计时
                updated = bridgeTrafficLightCountDown(info, updated)

                // 从限速+道路名推断道路类型
                if (updated.tencentSlice.roadGrade < 0 && updated.tencentSlice.roadKind < 0) {
                    if (nRoadLimitSpeed > 0 || szPosRoadName.isNotEmpty()) {
                        updated = updated.copy(
                            roadcate = inferRoadcateFromSpeedLimit(nRoadLimitSpeed, updated.roadcate, szPosRoadName)
                        )
                    }
                }

                Log.d(TAG, "✅ [字段已更新] nTBTDist=$nTBTDist, nTBTTurnType=$nTBTTurnType, nGoPosDist=$nGoPosDist")
                updated
            }
        }

        override fun onWillShowLaneGuide(laneInfo: NavLaneInfo?) {
            super.onWillShowLaneGuide(laneInfo)
            laneInfo ?: return
            try {
                val laneCount = laneInfo.num
                val items = try { laneInfo.items } catch (_: Exception) { null }
                val laneInfoList = mutableListOf<LaneInfo>()
                items?.forEach { item ->
                    if (item != null) {
                        // 直接读 name 字段（getName() getter 在模拟导航下可能返回空）
                        val rawName = try {
                            val f = item.javaClass.getDeclaredField("name")
                            f.isAccessible = true
                            (f.get(item) as? String) ?: ""
                        } catch (_: Exception) {
                            safeGet(item, "getName", "") ?: ""
                        }
                        val recommend = try {
                            item.javaClass.getField("recommend").getBoolean(item)
                        } catch (_: Exception) { false }
                        // 腾讯 name 格式 "X_Y" → 取 X 作为车道类型（匹配 landback_X）
                        val laneId = if (rawName.contains("_")) rawName.substringBefore("_") else rawName
                        laneInfoList.add(LaneInfo(id = laneId, isRecommended = recommend))
                        Log.d(TAG, "onWillShowLaneGuide: rawName='$rawName' → laneId='$laneId' recommend=$recommend")
                    }
                }
                updateField { fields ->
                    fields.copy(
                        laneCount = if (laneCount > 0) laneCount else laneInfoList.size,
                        nTBTNextRoadWidth = if (laneCount > 0) laneCount else laneInfoList.size,
                        laneInfoList = laneInfoList
                    )
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

        override fun onCameraInfoUpdate(cameraInfoList: MutableList<com.tencent.navix.api.model.NavCameraInfo>) {
            super.onCameraInfoUpdate(cameraInfoList)

            if (cameraInfoList.isEmpty()) {
                updateField { fields ->
                    fields.copy(
                        tencentSlice = fields.tencentSlice.copy(tCameraType = -1, tCameraDist = 0, tCameraSpeedLimit = 0),
                        nSdiType = -1, nSdiSpeedLimit = 0, nSdiDist = 0,
                        nSdiBlockType = -1, nSdiBlockSpeed = 0, nSdiBlockDist = 0,
                        nSdiPlusType = -1, nSdiPlusSpeedLimit = 0, nSdiPlusDist = 0,
                        nSdiPlusBlockType = -1, nSdiPlusBlockSpeed = 0, nSdiPlusBlockDist = 0
                    )
                }
                return
            }

            try {
                val cameraInfo = cameraInfoList[0]
                val cType = safeGet(cameraInfo, "getCameraType", -1)
                val cDist = safeGet(cameraInfo, "getDistance", 0)
                val cLimit = safeGetAny(cameraInfo, 0, "getLimitSpeedKMPH", "getSpeedLimit")
                val zoneLength = safeGet(cameraInfo, "getSpeedZoneLimitLength", 0)
                val shouldClearSdi = cDist <= 20

                updateField { fields ->
                    var updated = fields.copy(
                        tencentSlice = fields.tencentSlice.copy(
                            tCameraType = if (shouldClearSdi) -1 else cType,
                            tCameraDist = if (shouldClearSdi) 0 else cDist,
                            tCameraSpeedLimit = if (shouldClearSdi) 0 else cLimit
                        )
                    )

                    if (shouldClearSdi) {
                        updated = updated.copy(
                            nSdiType = -1, nSdiSpeedLimit = 0, nSdiDist = 0,
                            nSdiBlockType = -1, nSdiBlockSpeed = 0, nSdiBlockDist = 0
                        )
                    } else if (cDist > 0) {
                        val sdiType = mapCameraTypeToSdiType(cType, zoneLength)
                        if (sdiType > 0) {
                            updated = updated.copy(nSdiType = sdiType, nSdiSpeedLimit = cLimit, nSdiDist = cDist)
                        }
                        if (zoneLength > 0 || cType == 9) {
                            updated = updated.copy(
                                nSdiBlockType = 1, nSdiBlockSpeed = cLimit,
                                nSdiBlockDist = if (zoneLength > 0) zoneLength else cDist
                            )
                        } else if (cType == 10) {
                            updated = updated.copy(nSdiBlockType = 3, nSdiBlockSpeed = cLimit, nSdiBlockDist = 0)
                        }
                    }

                    // 第二个电子眼
                    if (cameraInfoList.size >= 2) {
                        val cam2 = cameraInfoList[1]
                        val c2Type = safeGet(cam2, "getCameraType", -1)
                        val c2Dist = safeGet(cam2, "getDistance", 0)
                        val c2Limit = safeGetAny(cam2, 0, "getLimitSpeedKMPH", "getSpeedLimit")
                        val c2ZoneLen = safeGet(cam2, "getSpeedZoneLimitLength", 0)
                        val shouldClearSdiPlus = c2Dist <= 20

                        if (shouldClearSdiPlus) {
                            updated = updated.copy(
                                nSdiPlusType = -1, nSdiPlusSpeedLimit = 0, nSdiPlusDist = 0,
                                nSdiPlusBlockType = -1, nSdiPlusBlockSpeed = 0, nSdiPlusBlockDist = 0
                            )
                        } else if (c2Dist > 0) {
                            val plusSdiType = mapCameraTypeToSdiType(c2Type, c2ZoneLen)
                            updated = updated.copy(nSdiPlusType = plusSdiType, nSdiPlusSpeedLimit = c2Limit, nSdiPlusDist = c2Dist)
                            if (c2ZoneLen > 0) {
                                updated = updated.copy(nSdiPlusBlockType = 1, nSdiPlusBlockSpeed = c2Limit, nSdiPlusBlockDist = c2ZoneLen)
                            }
                        }
                    } else {
                        updated = updated.copy(
                            nSdiPlusType = -1, nSdiPlusSpeedLimit = 0, nSdiPlusDist = 0,
                            nSdiPlusBlockType = -1, nSdiPlusBlockSpeed = 0, nSdiPlusBlockDist = 0
                        )
                    }

                    updated
                }
            } catch (e: Exception) {
                Log.w(TAG, "电子眼信息解析失败: ${e.message}")
            }
        }

        override fun onUpdateParallelRoad(parallelRoadStatus: com.tencent.navix.api.model.NavParallelRoadStatus?) {
            super.onUpdateParallelRoad(parallelRoadStatus)
            parallelRoadStatus ?: return
            try {
                val currentType = safeGet(parallelRoadStatus, "getCurrentRoadType", null as Any?)
                val firstHint = safeGet(parallelRoadStatus, "getFirstHintType", null as Any?)
                val secHint = safeGet(parallelRoadStatus, "getSecHintType", null as Any?)

                val currentTypeName = currentType?.toString() ?: "UNKNOWN"
                val currentTypeValue = if (currentType != null) {
                    safeGet(currentType, "asValue", -1)
                } else -1

                val isMainRoad = !currentTypeName.contains("Serving", ignoreCase = true)
                val canSwitchMain = firstHint?.toString()?.contains("Main", ignoreCase = true) == true
                        || secHint?.toString()?.contains("Main", ignoreCase = true) == true
                val canSwitchSide = firstHint?.toString()?.contains("Serving", ignoreCase = true) == true
                        || secHint?.toString()?.contains("Serving", ignoreCase = true) == true

                updateField { fields ->
                    val newRoadcate = mapNavRoadTypeToRoadcate(currentTypeName, fields.roadcate)
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

        override fun onTollStationInfoUpdate(tollStationInfo: com.tencent.navix.api.model.NavTollStationInfo?) {
            super.onTollStationInfoUpdate(tollStationInfo)
            tollStationInfo ?: return
            try {
                val entranceName = safeGet(tollStationInfo, "getEntranceStationName", "") ?: ""
                val exitName = safeGet(tollStationInfo, "getExitStationName", "") ?: ""
                val fee = safeGet(tollStationInfo, "getFee", 0)

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

        override fun onPlayTTS(navTTSInfo: com.tencent.navix.api.model.NavTTSInfo?) {
            super.onPlayTTS(navTTSInfo)
            navTTSInfo ?: return
            try {
                val ttsText = safeGetAny(navTTSInfo, "", "getContent", "getText") ?: ""
                if (ttsText.isEmpty()) return

                val currentFields = carrotManFieldsState?.value ?: return
                if (currentFields.nTBTTurnType == -1 || currentFields.nTBTTurnType == 51) {
                    val inferred = inferTurnTypeFromText(ttsText)
                    if (inferred != -1 && inferred != 51) {
                        updateField { it.copy(nTBTTurnType = inferred) }
                    }
                }
            } catch (_: Exception) { /* ignore */ }
        }

        override fun onNavLocationInfoUpdate(navLocationInfo: NavLocationInfo?) {
            super.onNavLocationInfoUpdate(navLocationInfo)
            navLocationInfo ?: return
            try {
                val mainRouteLoc = navLocationInfo.mainRouteLocation ?: return
                val isValid = try { mainRouteLoc.isValid } catch (_: Exception) { true }
                if (!isValid) return

                val adsorbLoc = safeGet(mainRouteLoc, "getAdsorbLocation", null as Any?)
                if (adsorbLoc != null) {
                    val latLng = safeGet(adsorbLoc, "getLatLng", null as Any?)
                    if (latLng != null) {
                        val lat = safeGet(latLng, "getLatitude", 0.0)
                        val lon = safeGet(latLng, "getLongitude", 0.0)
                        val displayText = safeGet(adsorbLoc, "getDisplayText", "") ?: ""
                        val segIdx = safeGet(mainRouteLoc, "getSegmentIndex", -1)
                        val pointIdx = safeGet(mainRouteLoc, "getPointIndex", -1)

                        if (lat != 0.0 && lon != 0.0) {
                            updateField { fields ->
                                fields.copy(
                                    vpPosPointLat = lat,
                                    vpPosPointLon = lon,
                                    curSegNum = if (segIdx >= 0) segIdx else fields.curSegNum,
                                    curPointNum = if (pointIdx >= 0) pointIdx else fields.curPointNum,
                                    tencentSlice = fields.tencentSlice.copy(
                                        roadGrade = safeGet(mainRouteLoc, "getRoadGrade", -1),
                                        roadKind = safeGet(mainRouteLoc, "getRoadKind", -1),
                                    )
                                )
                            }
                        }

                        // segIdx变化时更新 roadcate
                        if (segIdx >= 0) {
                            updateRoadcateBySegIdx(segIdx)
                        }
                    }
                }

                // GPS信号状态
                val gpsStatus = safeGet(navLocationInfo, "getGpsStatus", null as Any?)
                if (gpsStatus != null) {
                    val statusName = gpsStatus.toString()
                    val statusInt = when {
                        statusName.contains("Normal", ignoreCase = true) || statusName.contains("Good", ignoreCase = true) -> 0
                        statusName.contains("Weak", ignoreCase = true) || statusName.contains("Poor", ignoreCase = true) -> 1
                        statusName.contains("Lost", ignoreCase = true) || statusName.contains("None", ignoreCase = true) -> 2
                        else -> 0
                    }
                    updateField { it.copy(tencentSlice = it.tencentSlice.copy(gpsSignalStatus = statusInt)) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "定位信息解析失败: ${e.message}")
            }
        }

        override fun onOffRoute() {
            super.onOffRoute()
            updateField { it.copy(
                nTBTTurnType = -1, nTBTDist = 0, szTBTMainText = "偏航重算中..."
            ).also { result -> result.isOffRoute = true } }
        }

        override fun onDidStartNavigation() {
            super.onDidStartNavigation()
            isNavigating = true
            updateField { it.copy(isNavigating = true) }
        }

        override fun onDidStopNavigation() {
            super.onDidStopNavigation()
            onNavigationStopped()
        }

        override fun onMainRouteDidChange(routeId: String?, reason: Int) {
            super.onMainRouteDidChange(routeId, reason)
            segmentRoadInfoList = emptyList()
            lastRoadcateSegIdx = -1
        }

        override fun onDayNightStatusChange(dayNightStatus: com.tencent.navix.api.model.NavDayNightStatus?) {
            super.onDayNightStatusChange(dayNightStatus)
            val statusName = dayNightStatus?.toString() ?: "UNKNOWN"
            val isNight = statusName.contains("NIGHT", ignoreCase = true)
            updateField { it.copy().also { result -> result.isNightMode = isNight } }
        }

        override fun onRerouteDidSucceed(reason: Int, navRoutePlan: com.tencent.navix.api.model.NavDriveRoutePlan?) {
            super.onRerouteDidSucceed(reason, navRoutePlan)
            updateField { it.copy().also { result -> result.isOffRoute = false } }
        }

        override fun onArriveWaypoint(waypoint: com.tencent.navix.api.model.NavWaypoint?) {
            super.onArriveWaypoint(waypoint)
            if (waypoint == null) return
            val trafficLightNum = safeGet(waypoint, "getTrafficLightNum", -1)
            if (trafficLightNum >= 0) {
                updateField { it.copy(tencentSlice = it.tencentSlice.copy(remainingTrafficLights = trafficLightNum)) }
            }
        }
    }

    // === 红绿灯倒计时数据桥接 ===
    private var trafficLightReflectionChecked = false
    private var hasGetTrafficLightCountDown = false
    private var hasGetTrafficLightStatus = false
    private var hasGetTrafficLightDistance = false

    private fun bridgeTrafficLightCountDown(info: Any, fields: CarrotManFields): CarrotManFields {
        var updated = fields
        try {
            if (!trafficLightReflectionChecked) {
                trafficLightReflectionChecked = true
                val clazz = info.javaClass
                fun has(name: String) = try { clazz.getMethod(name); true } catch (_: Exception) { false }
                hasGetTrafficLightCountDown = has("getTrafficLightCountDown") || has("getTrafficLightInfo")
                        || has("getNavTrafficLightInfo") || has("getTrafficLightCountDownInfo")
                hasGetTrafficLightStatus = has("getTrafficLightStatus")
                hasGetTrafficLightDistance = has("getTrafficLightDistance")
            }

            if (hasGetTrafficLightCountDown) {
                val tlInfo = safeGetAny(info, null as Any?, "getTrafficLightCountDown", "getTrafficLightInfo",
                    "getNavTrafficLightInfo", "getTrafficLightCountDownInfo")
                if (tlInfo != null) {
                    val countdown = safeGetAny(tlInfo, 0, "getCountDown", "getCountdown", "getRemainingTime")
                    val state = safeGetAny(tlInfo, -1, "getStatus", "getLightStatus", "getState")
                    val distance = safeGetAny(tlInfo, 0, "getDistance", "getLightDistance")
                    if (countdown > 0 || state >= 0) {
                        updated = updated.copy(
                            trafficLightCountdown = countdown,
                            trafficLightState = state,
                            trafficLightDistance = if (distance > 0) distance else updated.trafficLightDistance
                        )
                    }
                }
            }

            if (hasGetTrafficLightStatus && !hasGetTrafficLightCountDown) {
                val state = safeGet(info, "getTrafficLightStatus", -1)
                val distance = safeGet(info, "getTrafficLightDistance", 0)
                if (state >= 0) {
                    updated = updated.copy(trafficLightState = state, trafficLightDistance = distance)
                }
            }
        } catch (_: Exception) {}
        return updated
    }

    private fun bridgeSpeedMonitorZone(info: NavDriveDataInfo, fields: CarrotManFields): CarrotManFields {
        var updated = fields
        try {
            val zoneInfo = try { info.navSpeedMonitorZoneInfo } catch (_: Exception) { null } ?: return updated
            val zoneType = safeGet(zoneInfo, "getZoneType", -1)
            val avgSpeed = safeGet(zoneInfo, "getAverageSpeed", 0)
            val remainDist = safeGet(zoneInfo, "getRemainingDistance", 0)
            val remainTime = safeGet(zoneInfo, "getRemainingTime", 0)
            val limitSpeed = safeGet(zoneInfo, "getLimitSpeed", 0)
            val totalDist = safeGet(zoneInfo, "getTotalDistance", 0)

            val blockType = when (zoneType) {
                0 -> 2; 1 -> 2; 2 -> 3; else -> -1
            }

                updated = updated.copy(
                    nSdiBlockType = blockType, nSdiBlockSpeed = limitSpeed,
                    nSdiBlockDist = if (totalDist > 0) totalDist else remainDist,
                    nSdiAverageSpeed = avgSpeed
                )
        } catch (e: Exception) {
            Log.w(TAG, "区间测速解析失败: ${e.message}")
        }
        return updated
    }

    private fun bridgeHighwayFacilities(info: NavDriveDataInfo, fields: CarrotManFields): CarrotManFields {
        var updated = fields
        try {
            val facilities = try { info.highwayFacilities } catch (_: Exception) { null } ?: return updated
            if (facilities.isNotEmpty()) {
                val first = facilities[0] ?: return updated
                val name = safeGet(first, "getName", "") ?: ""
                val dist = safeGet(first, "getDistance", 0)
                val type = safeGet(first, "getFacilityType", -1)
                if (name.isNotEmpty() && dist > 0) {
                    updated = updated.copy(
                        sapaName = name, sapaDist = dist, sapaType = type, sapaNum = facilities.size
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "服务区信息解析失败: ${e.message}")
        }
        return updated
    }

    private fun bridgeEnlargedMapInfo(info: NavDriveDataInfo, fields: CarrotManFields): CarrotManFields {
        var updated = fields
        try {
            val enlargedInfo = safeGet(info, "getNavEnlargedMapInfo", null as Any?) ?: return updated
            val showing = safeGet(enlargedInfo, "isShowing", false)
            val distToMap = safeGet(enlargedInfo, "getDistanceToMap", 0)
            if (showing && distToMap > 0 && updated.nTBTDistNext == 0) {
                updated = updated.copy(nTBTDistNext = distToMap)
            }
        } catch (_: Exception) {}
        return updated
    }

    private fun bridgeTrafficItems(routeData: NavDriveRouteData, fields: CarrotManFields): CarrotManFields {
        var updated = fields
        try {
            val items = safeGet<List<*>?>(routeData, "getTrafficItems", null) ?: return updated
            if (items.isEmpty()) return updated

            val currIdx = try { routeData.currPointIdx } catch (_: Exception) { 0 }
            var congestedDist = 0; var totalDist = 0
            var nearestJamDist = Int.MAX_VALUE; var nearestJamDuration = 0; var nearestJamStatus = 0

            for (item in items) {
                item ?: continue
                val from = safeGet(item, "getFrom", 0)
                val dist = safeGet(item, "getDistance", 0)
                val passTime = safeGet(item, "getPassTime", 0)
                val statusObj = safeGet(item, "getTrafficStatus", null as Any?)
                val statusName = statusObj?.toString() ?: "UNKNOWN"
                totalDist += dist

                val statusLevel = when {
                    statusName.contains("VERY_CONGESTED", ignoreCase = true) -> 4
                    statusName.contains("CONGESTED", ignoreCase = true) -> 3
                    statusName.contains("SLOW", ignoreCase = true) -> 2
                    statusName.contains("UNBLOCKED", ignoreCase = true) -> 1
                    else -> 0
                }
                if (statusLevel >= 3) congestedDist += dist
                if (from >= currIdx && statusLevel >= 3 && dist < nearestJamDist) {
                    nearestJamDist = dist; nearestJamDuration = passTime * 60; nearestJamStatus = statusLevel
                }
            }

            if (nearestJamDist < Int.MAX_VALUE) {
                updated = updated.copy(tencentSlice = updated.tencentSlice.copy(
                    trafficJamAhead = true, trafficJamDistance = nearestJamDist,
                    trafficJamDuration = nearestJamDuration, trafficJamStatus = nearestJamStatus
                ))
            } else {
                updated = updated.copy(tencentSlice = updated.tencentSlice.copy(
                    trafficJamAhead = false, trafficJamDistance = 0, trafficJamDuration = 0, trafficJamStatus = 0
                ))
            }

            val congestionRatio = if (totalDist > 0) congestedDist.toFloat() / totalDist else 0f
            val overallLevel = when {
                congestionRatio > 0.3f -> 3; congestionRatio > 0.1f -> 2; congestionRatio > 0f -> 1
                else -> 0
            }
            val desc = when (overallLevel) { 3 -> "严重拥堵"; 2 -> "中度拥堵"; 1 -> "轻度拥堵"; else -> "畅通" }
            updated = updated.copy(trafficLevel = overallLevel, trafficDescription = desc)
        } catch (_: Exception) {}
        return updated
    }

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
                if (hasTruckRestriction) descriptions.add("货车限行(${truckList!!.size}处)")
                if (hasTurnRestriction) descriptions.add("转向限制(${turnList!!.size}处)")
                updated = updated.copy(
                    situationType = 5,
                    situationDescription = "前方${descriptions.joinToString("、")}"
                )
            }
        } catch (_: Exception) {}
        return updated
    }

    /** 诊断模拟导航数据完整性 */
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
            if (fields.nTBTDist > 0)
                appendLine("  - nTBTDist (转弯距离): ${fields.nTBTDist}m")

            appendLine("\n❌ 缺失的关键字段:")
            if (fields.nRoadLimitSpeed == 0) appendLine("  - nRoadLimitSpeed (道路限速)")
            if (fields.nTBTDist == 0) appendLine("  - nTBTDist (转弯距离)")
            if (fields.nTBTTurnType == -1) appendLine("  - nTBTTurnType (转弯类型)")
            if (fields.szTBTMainText.isEmpty()) appendLine("  - szTBTMainText (转弯指令)")
            if (fields.nGoPosDist == 0) appendLine("  - nGoPosDist (剩余距离)")
            if (fields.nGoPosTime == 0) appendLine("  - nGoPosTime (剩余时间)")
            if (fields.szPosRoadName.isEmpty()) appendLine("  - szPosRoadName (当前道路名)")
            if (fields.latitude == 0.0 && fields.longitude == 0.0)
                appendLine("  - latitude/longitude (GPS坐标)")
        }
        Log.i(TAG, report.toString())
    }

    /** 提取路线点串（供 Comma3 弯道限速） */
    fun extractRoutePoints(route: NavDriveRoute) {
        try {
            val routePoints = safeGet<List<*>?>(route, "getRoutePoints", null)
            if (routePoints.isNullOrEmpty()) return

            val points = mutableListOf<Pair<Double, Double>>()
            for (point in routePoints) {
                if (point == null) continue
                val lat = safeGetAny(point, 0.0, "getLatitude", "getLat")
                val lon = safeGetAny(point, 0.0, "getLongitude", "getLng", "getLon")
                if (lat != 0.0 && lon != 0.0) {
                    val wgs = gcj02ToWgs84(lat, lon)
                    points.add(Pair(wgs.second, wgs.first))
                }
            }

            if (points.isNotEmpty()) {
                updateField { it.copy(
                    tencentSlice = it.tencentSlice.copy(
                        tencentRoutePoints = points,
                        tencentRoutePointsReady = true
                    )
                ) }
            }

            extractSegmentRoadInfo(route)

            // 提取收费信息
            val fee = safeGet(route, "getFee", 0)
            if (fee > 0) {
                updateField { it.copy(tencentSlice = it.tencentSlice.copy(tollFee = fee)) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "路线点串提取失败: ${e.message}")
        }
    }

    private fun extractSegmentRoadInfo(route: NavDriveRoute) {
        try {
            val segments = safeGet<List<*>?>(route, "getSegmentItems", null)
            if (segments.isNullOrEmpty()) return

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
            lastRoadcateSegIdx = -1

            val (grade, kind) = infoList[0]
            if (grade >= 0 || kind >= 0) {
                val roadcate = mapGradeKindToRoadcate(grade, kind)
                updateField { it.copy(
                    tencentSlice = it.tencentSlice.copy(roadGrade = grade, roadKind = kind),
                    roadcate = if (roadcate > 0) roadcate else it.roadcate
                ) }
            }
        } catch (_: Exception) {}
    }

    private fun updateRoadcateBySegIdx(segIdx: Int) {
        if (segIdx < 0 || segIdx == lastRoadcateSegIdx) return
        if (segIdx >= segmentRoadInfoList.size) return
        lastRoadcateSegIdx = segIdx

        val (grade, kind) = segmentRoadInfoList[segIdx]
        if (grade < 0 && kind < 0) return

        val roadcate = mapGradeKindToRoadcate(grade, kind)
        updateField { it.copy(
            tencentSlice = it.tencentSlice.copy(roadGrade = grade, roadKind = kind),
            roadcate = if (roadcate > 0) roadcate else it.roadcate
        ) }
    }

    private fun mapGradeKindToRoadcate(grade: Int, kind: Int): Int {
        val gradeResult = when (grade) {
            0 -> 10; 1 -> 10; 2 -> 6; 3 -> 6; 4 -> 6; 6 -> 6; 8 -> 6; 9 -> 6; 10 -> 6
            else -> -1
        }
        if (gradeResult > 0) return gradeResult
        return when (kind) {
            3 -> 10; 5 -> 10; 11 -> 10; 2 -> 10; 13 -> 10; 15 -> 6; 4 -> 6; 12 -> 6
            else -> 6
        }
    }

    // === GCJ-02 → WGS-84 坐标转换 ===
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

    private fun updateField(transform: (CarrotManFields) -> CarrotManFields) {
        val state = carrotManFieldsState ?: return
        val transformed = transform(state.value)
        state.value = transformed.copy(
            source_last = "tencent",
            lastUpdateTime = System.currentTimeMillis()
        )
    }

    fun onNavigationStopped() {
        Log.i(TAG, "🛑 腾讯导航已停止")
        clearNavigationFields()
    }

    private fun clearNavigationFields() {
        updateField { fields ->
            fields.copy(
                isNavigating = false,
                nTBTDist = 0, nTBTTurnType = -1, szTBTMainText = "",
                szNearDirName = "", szFarDirName = "", nTBTDistNext = 0,
                nTBTTurnTypeNext = -1, szTBTMainTextNext = "",
                nGoPosDist = 0, nGoPosTime = 0, szPosRoadName = "",
                nRoadLimitSpeed = fields.nRoadLimitSpeed.takeIf { it > 0 } ?: 0,  // 保留已知限速，不掉 0
                laneInfoList = emptyList(), laneCount = 0,
                tencentSlice = CarrotManTencentSlice(),
                nSdiType = -1, nSdiSpeedLimit = 0, nSdiDist = 0,
                nSdiBlockType = -1, nSdiBlockSpeed = 0, nSdiBlockDist = 0,
                nSdiAverageSpeed = -1, exitNameInfo = "",
                sapaName = "", sapaDist = -1, sapaType = -1, sapaNum = -1,
                curSegNum = 0, curPointNum = 0,
                trafficLightCountdown = 0, trafficLightState = -1, trafficLightDistance = 0,
                trafficLevel = -1, trafficDescription = ""
            ).also { result ->
                result.isNightMode = false
                result.isOffRoute = false
            }
        }
    }
}
